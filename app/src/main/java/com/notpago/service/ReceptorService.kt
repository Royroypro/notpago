package com.notpago.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.notpago.MainActivity
import com.notpago.data.remote.RetrofitClient
import com.notpago.util.Constants
import com.notpago.util.PrefsManager
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.*

class ReceptorService : Service() {

    private lateinit var prefs: PrefsManager
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val CHANNEL_ID = "ReceptorServiceChannel"
    private var lastCheckTime: Long = System.currentTimeMillis()
    private var pollingJob: Job? = null
    private var pusher: Pusher? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var guardianJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        createNotificationChannel()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotPago::ReceptorWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes timeout to prevent battery drain
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requiere startForeground() dentro de 5s después de startForegroundService().
        startForegroundService()

        if (intent?.action == "ACTION_RECREATE_NOTIFICATION") {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (prefs.operationMode == "RECEPTOR" && prefs.isReceptorActive) {
                    startForegroundService()
                }
            }, 1000)
            return START_STICKY
        }

        if (prefs.operationMode == "RECEPTOR" && prefs.isReceptorActive) {
            acquireWakeLock()
            startWebSocket()
            startPolling()
            startGuardian()
        } else {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            releaseWakeLock()
            stopWebSocket()
            pollingJob?.cancel()
            stopGuardian()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startGuardian() {
        if (guardianJob?.isActive == true) return
        guardianJob = scope.launch {
            while (true) {
                val manager = getSystemService(NotificationManager::class.java)
                val hasNotification = manager.activeNotifications.any { it.id == 2 }
                if (!hasNotification && prefs.isReceptorActive && prefs.operationMode == "RECEPTOR") {
                    startForegroundService()
                }
                kotlinx.coroutines.delay(60000)
            }
        }
    }

    private fun stopGuardian() {
        guardianJob?.cancel()
        guardianJob = null
    }

    private fun stopWebSocket() {
        pusher?.disconnect()
        pusher = null
    }

    private fun generateChannelHash(token: String, id: String): String {
        val input = token.trim() + id.trim()
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hash = bytes.joinToString("") { "%02x".format(it) }
        Log.i("ReceptorService", "Hash para '$input' generado: $hash")
        return hash
    }

    private fun startWebSocket() {
        if (!prefs.isConfigured() || prefs.restaurantId.isEmpty()) {
            Log.w("ReceptorService", "WebSocket omitido: Falta Token o ID")
            return
        }

        // Evitar duplicados: desconectar la conexión anterior antes de crear una nueva
        if (pusher != null) {
            Log.i("ReceptorService", "Desconectando WebSocket anterior para evitar duplicados...")
            pusher?.disconnect()
            pusher = null
        }

        try {
            val cleanHost = prefs.host.replace("https://", "").replace("http://", "").removeSuffix("/")
            Log.i("ReceptorService", "Iniciando WebSocket en $cleanHost")
            val options = PusherOptions().apply {
                setHost(cleanHost)
                setWssPort(443)
                setUseTLS(true)
            }

            pusher = Pusher(Constants.PUSHER_APP_KEY, options)
            pusher?.connect(object : ConnectionEventListener {
                override fun onConnectionStateChange(change: ConnectionStateChange) {
                    Log.i("ReceptorService", "ESTADO WEBSOCKET: ${change.previousState} -> ${change.currentState}")
                    // Al reconectar, posibles eventos perdidos durante la desconexión.
                    // Resetear lastReceptorSyncTime obliga al próximo fetchRemote a hacer
                    // una reconciliación completa (compara local vs servidor).
                    if (change.currentState == ConnectionState.CONNECTED &&
                        change.previousState == ConnectionState.RECONNECTING) {
                        prefs.lastReceptorSyncTime = null
                        sendBroadcast(Intent("com.notpago.REQUEST_REFRESH").apply { setPackage(packageName) })
                        Log.i("ReceptorService", "Reconexión detectada — forzando reconciliación completa")
                    }
                }

                override fun onError(message: String, code: String?, e: Exception?) {
                    Log.e("ReceptorService", "ERROR WEBSOCKET: $message | Code: $code", e)
                }
            }, ConnectionState.ALL)

            val hash = generateChannelHash(prefs.agentToken, prefs.restaurantId)
            val channelName = "yape.receptor.$hash"
            Log.i("ReceptorService", "Suscribiendo a canal: $channelName")
            
            val channel = pusher?.subscribe(channelName)

            channel?.bind("yape.notification.received") { event ->
                Log.i("ReceptorService", "¡EVENTO REAL-TIME RECIBIDO!: ${event.data}")
                try {
                    val data = JSONObject(event.data)
                    val sender = data.optString("sender_name", "Cliente")
                    val amount = data.optDouble("amount", 0.0)
                    val reference = data.optString("operation_reference", "")

                    // Sincronizar lastCheckTime para que el polling no muestre este pago de nuevo.
                    // Si no podemos parsear la fecha, usamos la hora actual como fallback.
                    val eventTime = parseDate(data.optString("received_at", ""))
                    lastCheckTime = maxOf(lastCheckTime, if (eventTime > 0L) eventTime else System.currentTimeMillis())

                    showPaymentNotification(sender, amount)
                    
                    // Notificar a la UI (Broadcast)
                    val intent = Intent("com.notpago.NEW_PAYMENT").apply {
                        setPackage(packageName)
                        putExtra("sender", sender)
                        putExtra("amount", amount)
                        putExtra("reference", reference)
                        putExtra("received_at", data.optString("received_at", ""))
                    }
                    sendBroadcast(intent)
                    Log.i("ReceptorService", "Broadcast enviado: com.notpago.NEW_PAYMENT")
                } catch (e: Exception) {
                    Log.e("ReceptorService", "Error al procesar JSON del evento", e)
                }
            }

            channel?.bind("yape.notification.linked") { event ->
                Log.i("ReceptorService", "EVENTO VINCULADO RECIBIDO: ${event.data}")
                try {
                    val data = JSONObject(event.data)
                    val reference = data.optString("operation_reference", "")
                    val status = data.optString("status", "linked")
                    
                    // Notificar a la UI para cambiar estado a "Pagado"
                    val intent = Intent("com.notpago.STATUS_UPDATED").apply {
                        setPackage(packageName)
                        putExtra("reference", reference)
                        putExtra("status", status)
                    }
                    sendBroadcast(intent)
                } catch (e: Exception) {
                    Log.e("ReceptorService", "Error al procesar JSON de vinculación", e)
                }
            }

            channel?.bind("yape.history.cleared") { event ->
                Log.i("ReceptorService", "EVENTO LIMPIEZA GLOBAL RECIBIDO")
                val intent = Intent("com.notpago.HISTORY_CLEARED").apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }

        } catch (e: Exception) {
            Log.e("ReceptorService", "Error crítico al iniciar Pusher", e)
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val deleteIntent = Intent(this, ReceptorService::class.java).apply {
            action = "ACTION_RECREATE_NOTIFICATION"
        }
        val deletePendingIntent = PendingIntent.getService(
            this, 2, deleteIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recepción de pagos Yape activo")
            .setContentText("Escuchando notificaciones de pago...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(true)
            .build()

        startForeground(2, notification)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2, notification)
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    if (prefs.isConfigured()) {
                        val api = RetrofitClient.getApi(prefs.host)
                        val response = api.getNotifications(prefs.agentToken)
                        if (response.isSuccessful) {
                            val notifications = response.body()?.data ?: emptyList()
                            Log.d("ReceptorService", "Polling result: ${notifications.size} items")
                            notifications.forEach { req ->
                                val time = parseDate(req.receivedAt ?: "")
                                if (time > lastCheckTime) {
                                    showPaymentNotification(req.senderName, req.amount)
                                    lastCheckTime = time
                                    // Notificar a la UI para que actualice la lista
                                    val intent = Intent("com.notpago.NEW_PAYMENT").apply {
                                        setPackage(packageName)
                                        putExtra("sender", req.senderName ?: "")
                                        putExtra("amount", req.amount)
                                        putExtra("reference", req.operationReference ?: "")
                                        putExtra("received_at", req.receivedAt ?: "")
                                    }
                                    sendBroadcast(intent)
                                }
                            }
                            // Mantener la lista de la UI sincronizada con el servidor periódicamente
                            sendBroadcast(Intent("com.notpago.REQUEST_REFRESH").apply { setPackage(packageName) })
                        } else {
                            Log.e("ReceptorService", "Polling error: ${response.code()}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ReceptorService", "Polling exception", e)
                }
                delay(60000) // Poll cada 1 minuto si hay WebSocket, como respaldo
            }
        }
    }

    private fun showPaymentNotification(sender: String, amount: Double) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = System.currentTimeMillis().toInt()
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, id, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("¡Nuevo Pago Recibido!")
            .setContentText("$sender te envió S/ %.2f".format(amount))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        // Forzar sonido personalizado si está configurado
        playSound()

        notificationManager.notify(id, notificationBuilder.build())
    }

    private fun playSound() {
        try {
            val soundUri = when (prefs.notificationSound) {
                "cash_register" -> Uri.parse("android.resource://${packageName}/raw/cash_register")
                "chime" -> Uri.parse("android.resource://${packageName}/raw/chime")
                else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            // Si el recurso no existe, cae al predeterminado
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(this@ReceptorService, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                prepare()
                start()
            }
            mediaPlayer.setOnCompletionListener { it.release() }
        } catch (e: Exception) {
            // Si hay error (recurso falta), no hacer nada o usar sonido default
            Log.e("ReceptorService", "Error playing sound: ${e.message}")
        }
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        return try {
            if (dateStr.contains("T")) {
                val format = if (dateStr.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
                java.text.SimpleDateFormat(format, Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(dateStr)?.time ?: 0L
            } else {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("America/Lima")
                }.parse(dateStr)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Notificaciones de Receptor",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Muestra alertas de nuevos pagos recibidos"
                enableLights(true)
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopWebSocket()
        releaseWakeLock()
        scope.cancel()
        stopGuardian()
    }
}
