package com.notpago.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notpago.data.local.database.AppDatabase
import com.notpago.data.local.entity.YapeNotificationEntity
import com.notpago.data.local.entity.SyncStatus
import com.notpago.util.PrefsManager
import com.notpago.worker.SyncHelper
import com.notpago.data.remote.RetrofitClient
import com.notpago.data.remote.YapeWebhookRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.regex.Pattern

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.notpago.MainActivity

import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class YapeNotificationService : NotificationListenerService() {

    private lateinit var prefsManager: PrefsManager
    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(Dispatchers.IO)
    private val CHANNEL_ID = "PaymentServiceChannel"
    private var wakeLock: PowerManager.WakeLock? = null
    private var guardianJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        prefsManager = PrefsManager(this)
        db = AppDatabase.getDatabase(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requiere startForeground() dentro de 5s después de startForegroundService().
        // Lo llamamos siempre aquí; handleToggleAction() lo detiene si el modo no corresponde.
        startForegroundService()

        if (intent?.action == "ACTION_RECREATE_NOTIFICATION") {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                handleToggleAction()
            }, 1000)
            return START_STICKY
        }
        handleToggleAction()
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        handleToggleAction()
    }

    private fun handleToggleAction() {
        if (prefsManager.operationMode == "EMISOR" && prefsManager.isEmisorActive) {
            acquireWakeLock()
            startGuardian()
            readExistingNotifications()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            releaseWakeLock()
            stopGuardian()
            stopSelf()
        }
    }

    private fun startGuardian() {
        if (guardianJob?.isActive == true) return
        guardianJob = scope.launch {
            while (true) {
                val manager = getSystemService(NotificationManager::class.java)
                val hasNotification = manager.activeNotifications.any { it.id == 1 }
                if (!hasNotification && prefsManager.isEmisorActive && prefsManager.operationMode == "EMISOR") {
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

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopGuardian()
        scope.cancel()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotPago::CaptureWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes timeout to prevent battery drain
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val deleteIntent = Intent(this, YapeNotificationService::class.java).apply {
            action = "ACTION_RECREATE_NOTIFICATION"
        }
        val deletePendingIntent = PendingIntent.getService(
            this, 1, deleteIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Escuchando pagos de Yape")
            .setContentText("Interceptando notificaciones en Modo Emisor...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Servicio de Pagos",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun readExistingNotifications() {
        try {
            val activeNotifs = activeNotifications ?: return
            for (sbn in activeNotifs) {
                processNotification(sbn)
            }
        } catch (e: Exception) {
            Log.e("YapeService", "Error leyendo notificaciones existentes", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (prefsManager.operationMode != "EMISOR") return
        if (!prefsManager.isEmisorActive) return
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // EVITAR BUCLE INFINITO: Si es una notificación generada por el propio ReceptorService, ignorarla.
        if (packageName == "com.notpago" && sbn.notification.channelId == "ReceptorServiceChannel") {
            Log.d("YapeService", "Ignorando notificación del propio ReceptorService para evitar bucle.")
            return
        }

        val pkg = sbn.packageName ?: ""
        val postTime = sbn.postTime
        // Log para debug de todos los paquetes cuando el agente está escuchando
        Log.d("YapeService", "Notificacion interceptada de paquete: $pkg")

        val validPackages = prefsManager.monitoredPackages
        if (pkg !in validPackages) {
            Log.d("YapeService", "Paquete $pkg ignorado porque no está en la lista de monitoreo.")
            return
        }

        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d("YapeService", "Ignorando resumen de grupo para evitar duplicados")
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val textLinesArray = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val textLines = textLinesArray?.joinToString(" | ") ?: ""

        parseAndSave(pkg, title, text, bigText, textLinesArray, postTime, sbn.key)
    }

    private fun parseAndSave(pkg: String, title: String, text: String, bigText: String, textLinesArray: Array<CharSequence>?, postTime: Long, notifKey: String) {
        // sourceText: texto principal para regex. Si viene en líneas, las unimos sin pipes
        // para que el patrón no se rompa cuando nombre y monto están en líneas distintas.
        val sourceText = if (!textLinesArray.isNullOrEmpty()) {
            textLinesArray.joinToString(" ")
        } else if (bigText.isNotBlank()) {
            bigText
        } else if (text.isNotBlank()) {
            text
        } else {
            title
        }

        // fullText con todo unido (sin pipes) — para codePattern y logs
        val fullText = listOf(title, text, bigText, textLinesArray?.joinToString(" ") ?: "")
            .filter { it.isNotBlank() }.joinToString(" ")

        Log.d("YapeService", "parseAndSave pkg=$pkg source='$sourceText'")

        val yapePattern = Pattern.compile(
            "¡?([^\\n]+?)\\s+te\\s+(?:yape[oó]|envi[oó])(?:\\s+un\\s+pago\\s+por)?\\s+S/\\s?([0-9.,]+)",
            Pattern.CASE_INSENSITIVE
        )
        val plinPattern = Pattern.compile(
            "transferencia\\s+de\\s+(.+?)\\s+por\\s+S/\\s?([0-9.,]+)\\s+desde\\s+Plin",
            Pattern.CASE_INSENSITIVE
        )
        val codePattern = Pattern.compile(
            "(?:C[oó]digo|c[oó]d\\.?\\s*de\\s*seguridad\\s*es)[: ]+([0-9]+)",
            Pattern.CASE_INSENSITIVE
        )
        val altPattern = Pattern.compile("S/\\s?([0-9.,]+)")

        var foundAny = false
        var matchIndex = 0

        val yapeMatcher = yapePattern.matcher(sourceText)
        while (yapeMatcher.find()) {
            val matchText = yapeMatcher.group(0) ?: ""
            val senderName = yapeMatcher.group(1)?.trim() ?: "Desconocido"
            val amount = parsePeruvianAmount(yapeMatcher.group(2) ?: "")
            val approvalCode = if (codePattern.matcher(fullText).also { it.find() }.groupCount() > 0)
                codePattern.matcher(fullText).let { m -> if (m.find()) m.group(1) else null } else null

            if (amount > 0) {
                saveNotificationToDb(amount, senderName, approvalCode, text, fullText, "yape", matchText, postTime, matchIndex++, notifKey)
                foundAny = true
            } else {
                Log.w("YapeService", "yapePattern matched pero amount=0 para: '$matchText'")
            }
        }

        val plinMatcher = plinPattern.matcher(sourceText)
        while (plinMatcher.find()) {
            val matchText = plinMatcher.group(0) ?: ""
            val senderName = plinMatcher.group(1)?.trim() ?: "Desconocido"
            val amount = parsePeruvianAmount(plinMatcher.group(2) ?: "")
            val approvalCode = codePattern.matcher(fullText).let { m -> if (m.find()) m.group(1) else null }

            if (amount > 0) {
                saveNotificationToDb(amount, senderName, approvalCode, text, fullText, "plin", matchText, postTime, matchIndex++, notifKey)
                foundAny = true
            } else {
                Log.w("YapeService", "plinPattern matched pero amount=0 para: '$matchText'")
            }
        }

        if (!foundAny) {
            // Fallback genérico — busca "S/ XX" en cualquier parte
            val altMatcher = altPattern.matcher(sourceText)
            if (altMatcher.find()) {
                val matchText = altMatcher.group(0) ?: ""
                val amount = parsePeruvianAmount(altMatcher.group(1) ?: "")
                if (amount > 0) {
                    var fallbackName = text.trim().ifBlank { bigText.trim() }
                        .ifBlank { textLinesArray?.joinToString(" ") ?: "" }
                        .ifBlank { title }
                    Log.d("YapeService", "altPattern encontró amount=$amount en: '$sourceText'")
                    saveNotificationToDb(amount, fallbackName, null, text, fullText, "yape", matchText, postTime, matchIndex++, notifKey)
                } else {
                    Log.w("YapeService", "altPattern encontró S/ pero amount=0 — ignorando. source='$sourceText'")
                }
            } else {
                Log.d("YapeService", "Ningún patrón matcheó. source='$sourceText'")
            }
        }
    }

    /** Parsea montos peruanos: "25.00", "1,500", "1,500.00", "1.500.000" */
    private fun parsePeruvianAmount(raw: String): Double {
        if (raw.isBlank()) return 0.0
        val s = raw.trim()
        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }
        return when {
            // "1,500.00" o "1,500" — coma=miles, punto=decimal (formato Perú)
            commas >= 1 && dots <= 1 -> s.replace(",", "").toDoubleOrNull() ?: 0.0
            // "1.500.000" — múltiples puntos = separadores de miles europeos
            dots > 1 -> s.replace(".", "").replace(",", "").toDoubleOrNull() ?: 0.0
            // "25.00" — un solo punto = decimal
            else -> s.toDoubleOrNull() ?: 0.0
        }
    }

    companion object {
        private val mutex = Mutex()
    }

    private fun saveNotificationToDb(
        amount: Double, 
        senderName: String, 
        approvalCode: String?, 
        originalText: String,
        fullText: String,
        transactionType: String,
        matchText: String,
        postTime: Long,
        matchIndex: Int,
        notifKey: String
    ) {
        // Guard: nunca guardar ni enviar montos de 0 — indica fallo de parseo
        if (amount <= 0.0) {
            Log.w("YapeService", "Ignorando notificación con amount=$amount (parseo fallido). text='$originalText'")
            return
        }

        val safeKeyHash = kotlin.math.abs(notifKey.hashCode()).toString()
        val fallbackRef = "FK${safeKeyHash}${matchIndex}"
        val opReference = approvalCode ?: fallbackRef
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(postTime))

        scope.launch {
            var insertedId: Long = -1L
            mutex.withLock {
                val existing = db.yapeNotificationDao().getNotificationByReference(opReference)
                if (existing != null) return@launch // Ignoramos duplicados

                if (prefsManager.clearedNotificationIds.contains(opReference)) {
                    Log.d("YapeService", "Ignorando notificación previamente eliminada: $opReference")
                    return@launch
                }

                val entity = com.notpago.data.local.entity.YapeNotificationEntity(
                    amount = amount,
                    senderName = senderName,
                    senderPhone = null,
                    approvalCode = approvalCode,
                    message = originalText,
                    operationReference = opReference,
                    transactionType = transactionType,
                    receivedAt = currentTime,
                    syncStatus = com.notpago.data.local.entity.SyncStatus.PENDING
                )

                insertedId = db.yapeNotificationDao().insertNotification(entity)
            }
            
            // Intento de envío en tiempo real inmediato
            if (insertedId != -1L) {
                try {
                    val token = prefsManager.agentToken
                    val host = prefsManager.host
                    if (token.isNotEmpty() && host.isNotEmpty()) {
                        val api = RetrofitClient.getApi(host)
                        val insertedEntity = db.yapeNotificationDao().getNotificationByReference(opReference)
                        if (insertedEntity != null) {
                            val request = YapeWebhookRequest(
                                amount = insertedEntity.amount,
                                operationReference = insertedEntity.operationReference,
                                approvalCode = insertedEntity.approvalCode,
                                senderName = insertedEntity.senderName ?: "",
                                message = insertedEntity.message,
                                transactionType = insertedEntity.transactionType,
                                receivedAt = insertedEntity.receivedAt
                            )
                            val response = api.sendWebhook(token, request)
                            if (response.isSuccessful || response.code() == 201 || response.code() == 200) {
                                db.yapeNotificationDao().updateSyncStatus(insertedEntity.id, SyncStatus.SYNCED)
                                Log.d("YapeService", "Envío en tiempo real exitoso para: $opReference")
                                return@launch // Salimos, no necesitamos WorkManager
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("YapeService", "Error en envío en tiempo real: ${e.message}")
                }
            }

            com.notpago.worker.SyncHelper.scheduleSync(this@YapeNotificationService)
        }
    }
}
