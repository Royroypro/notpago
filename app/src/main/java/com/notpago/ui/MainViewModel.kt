package com.notpago.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notpago.data.local.AppDatabase
import com.notpago.data.local.NotificationEntity
import com.notpago.data.local.database.AppDatabase as EmisorDatabase
import com.notpago.data.remote.RetrofitClient
import com.notpago.util.Constants
import com.notpago.util.PrefsManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import org.json.JSONObject
import java.security.MessageDigest

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val emisorDb = EmisorDatabase.getDatabase(application)
    private val prefs = PrefsManager(application)
    
    private val _remoteNotifications = MutableStateFlow<List<NotificationEntity>>(emptyList())
    val remoteNotifications: StateFlow<List<NotificationEntity>> = _remoteNotifications

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _connectionResult = MutableStateFlow<String?>(null)
    val connectionResult: StateFlow<String?> = _connectionResult

    private val _operationMode = MutableStateFlow(prefs.operationMode)
    val operationMode: StateFlow<String> = _operationMode

    fun updateOperationMode() {
        _operationMode.value = prefs.operationMode
    }

    private val _authStatus = MutableStateFlow<AuthStatus>(AuthStatus.UNKNOWN)
    val authStatus: StateFlow<AuthStatus> = _authStatus

    enum class AuthStatus {
        UNKNOWN, CHECKING, UNAUTHENTICATED, AUTHENTICATED
    }

    val localNotifications = db.notificationDao().getAllNotifications()

    val emisorNotifications = emisorDb.yapeNotificationDao().getAllNotificationsFlow()
        .map { list ->
            list.map { yape ->
                val timeMillis = try {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).parse(yape.receivedAt)?.time ?: System.currentTimeMillis()
                } catch (e: Exception) { System.currentTimeMillis() }
                
                NotificationEntity(
                    id = yape.id.toLong(),
                    senderName = normalizeSenderName(yape.senderName ?: "Desconocido"),
                    amount = yape.amount,
                    operationReference = yape.operationReference ?: "",
                    approvalCode = yape.approvalCode,
                    message = yape.message,
                    rawPayload = "YAPE",
                    status = yape.syncStatus.name, // PENDING, SYNCED, FAILED
                    receivedAt = timeMillis
                )
            }
        }
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedTab = MutableStateFlow(0) // 0: Todos, 1: Pendientes, 2: Pagados
    val selectedTab: StateFlow<Int> = _selectedTab
    
    private var pusher: Pusher? = null

    init {
        setupWebSocket()
    }

    private fun generateChannelHash(token: String, id: String): String {
        val input = token.trim() + id.trim()
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun setupWebSocket() {
        if (!prefs.isConfigured() || prefs.restaurantId.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                pusher?.disconnect()
                val options = PusherOptions().apply {
                    val cleanHost = prefs.host.replace("https://", "").replace("http://", "").removeSuffix("/")
                    setHost(cleanHost)
                    setWssPort(443)
                    setUseTLS(true)
                }

                pusher = Pusher(Constants.PUSHER_APP_KEY, options)
                pusher?.connect()

                val hash = generateChannelHash(prefs.agentToken, prefs.restaurantId)
                val channelName = "yape.receptor.$hash"
                val channel = pusher?.subscribe(channelName)

                channel?.bind("yape.notification.linked") { event ->
                    try {
                        val data = JSONObject(event.data)
                        val reference = data.optString("operation_reference", "")
                        val status = data.optString("status", "linked")
                        updateNotificationStatus(reference, status.uppercase())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // En modo RECEPTOR el ReceptorService ya escucha este evento por WebSocket
                // y envía un broadcast que MainActivity recibe. Manejarlo aquí también
                // causaría que la misma notificación se agregue dos veces a la lista.
                // Solo se procesa en modo EMISOR (para actualizar la UI en tiempo real).
                channel?.bind("yape.notification.received") { event ->
                    if (prefs.operationMode == "EMISOR") {
                        try {
                            val data = JSONObject(event.data)
                            val entity = NotificationEntity(
                                senderName = normalizeSenderName(data.optString("sender_name", "Cliente")),
                                amount = data.optDouble("amount", 0.0),
                                operationReference = data.optString("operation_reference", ""),
                                approvalCode = null,
                                message = null,
                                rawPayload = "REALTIME",
                                status = "PENDING",
                                receivedAt = parseDate(data.optString("received_at", ""))
                            )
                            addRemoteNotification(entity)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                channel?.bind("yape.history.cleared") {
                    forceClear()
                }

                channel?.bind("yape.notification.deleted") { event ->
                    try {
                        val data = JSONObject(event.data)
                        val reference = data.optString("operation_reference", "")
                        if (reference.isNotEmpty()) {
                            removeNotificationLocally(reference)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateNotificationStatus(reference: String, newStatus: String) {
        viewModelScope.launch {
            // Actualizar lista en memoria (UI)
            val currentList = _remoteNotifications.value.toMutableList()
            val index = currentList.indexOfFirst { it.operationReference == reference }
            if (index != -1) {
                currentList[index] = currentList[index].copy(status = newStatus)
                _remoteNotifications.value = currentList
            }
            
            // Si estamos en modo emisor, actualizar también la base de datos local
            if (prefs.operationMode == "EMISOR") {
                val existing = db.notificationDao().getByCode(reference)
                if (existing != null) {
                    db.notificationDao().update(existing.copy(status = newStatus))
                }
                
                // ACTUALIZAR TAMBIÉN EMISOR DB PARA QUE LA UI LO REFLEJE EN TIEMPO REAL
                val emisorExisting = emisorDb.yapeNotificationDao().getNotificationByReference(reference)
                if (emisorExisting != null) {
                    val newSyncStatus = when(newStatus.uppercase()) {
                        "LINKED", "DISCARDED" -> com.notpago.data.local.entity.SyncStatus.PAID
                        else -> emisorExisting.syncStatus
                    }
                    emisorDb.yapeNotificationDao().updateNotification(emisorExisting.copy(syncStatus = newSyncStatus))
                }
            } else {
                val existing = db.notificationDao().getByCode(reference)
                if (existing != null) {
                    db.notificationDao().update(existing.copy(status = newStatus))
                }
            }
        }
    }

    fun testConnection(host: String, agentToken: String) {
        viewModelScope.launch {
            _connectionResult.value = "Probando conexión..."
            try {
                val api = RetrofitClient.getApi(host.trim())
                val response = api.getNotifications(agentToken.trim(), onlyPending = 1)
                when {
                    response.isSuccessful -> {
                        val count = response.body()?.data?.size ?: 0
                        _connectionResult.value = "Conexión Exitosa: Token válido. ($count registros)"
                    }
                    response.code() == 401 || response.code() == 403 -> {
                        _connectionResult.value = "Token inválido (${response.code()}). Verifica el token."
                    }
                    response.code() >= 500 -> {
                        val error = response.errorBody()?.string() ?: response.message()
                        _connectionResult.value = "Error del servidor (${response.code()}): $error"
                    }
                    else -> {
                        val error = response.errorBody()?.string() ?: response.message()
                        _connectionResult.value = "Error ${response.code()}: $error"
                    }
                }
            } catch (e: Exception) {
                _connectionResult.value = "Error de red: ${e.localizedMessage}"
            }
        }
    }

    fun checkAuthentication() {
        if (!prefs.isConfigured() || prefs.agentToken.isBlank()) {
            _authStatus.value = AuthStatus.UNAUTHENTICATED
            return
        }
        viewModelScope.launch {
            _authStatus.value = AuthStatus.CHECKING
            try {
                val api = RetrofitClient.getApi(prefs.host.trim())
                val response = api.getNotifications(prefs.agentToken.trim(), onlyPending = 1)
                val code = response.code()
                android.util.Log.i("MainViewModel", "Auth check: HTTP $code")
                _authStatus.value = when {
                    response.isSuccessful -> AuthStatus.AUTHENTICATED
                    // 4xx = credenciales inválidas → pedir reconfiguración
                    code in 400..499 -> {
                        android.util.Log.e("MainViewModel", "Auth failed 4xx: ${response.errorBody()?.string()}")
                        AuthStatus.UNAUTHENTICATED
                    }
                    // 5xx = error del servidor → dejar pasar para no bloquear la app
                    else -> {
                        android.util.Log.w("MainViewModel", "Auth server error $code — asumiendo configurado")
                        AuthStatus.AUTHENTICATED
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Auth exception: ${e.localizedMessage}")
                // Error de red (sin conexión) → no bloquear la app
                _authStatus.value = AuthStatus.AUTHENTICATED
            }
        }
    }

    fun clearConnectionResult() {
        _connectionResult.value = null
    }

    fun forceClear() {
        viewModelScope.launch {
            // Capturar todos los IDs antes de borrar para que fetchRemote no los re-agregue
            val remoteIds = _remoteNotifications.value.mapNotNull { it.operationReference }
            val localIds = db.notificationDao().getAllNotificationsOnce().mapNotNull { it.operationReference }
            val newExcluded = prefs.clearedNotificationIds.toMutableSet()
            newExcluded.addAll(remoteIds)
            newExcluded.addAll(localIds)
            prefs.clearedNotificationIds = newExcluded

            db.notificationDao().deleteAll()
            emisorDb.yapeNotificationDao().deleteAllNotifications()
            _remoteNotifications.value = emptyList()

            // Forzar reconciliación completa en el próximo sync del RECEPTOR.
            // Si el evento Pusher llegó tarde o se perdió, el siguiente fetchRemoteReceptor
            // comparará local vs servidor y eliminará lo que ya no exista en el servidor.
            prefs.lastReceptorSyncTime = null
        }
    }

    fun deleteNotificationGlobally(reference: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (prefs.isConfigured()) {
                    val api = RetrofitClient.getApi(prefs.host)
                    api.deleteNotification(prefs.agentToken, reference)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            removeNotificationLocally(reference)
        }
    }

    fun removeNotificationLocally(reference: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.notificationDao().deleteByCode(reference)
            emisorDb.yapeNotificationDao().deleteByReference(reference)
            
            val newExcluded = prefs.clearedNotificationIds.toMutableSet()
            newExcluded.add(reference)
            prefs.clearedNotificationIds = newExcluded
            
            val currentList = _remoteNotifications.value.toMutableList()
            currentList.removeAll { it.operationReference == reference }
            _remoteNotifications.value = currentList
        }
    }

    fun clearNotifications() {
        viewModelScope.launch {
            try {
                val api = RetrofitClient.getApi(prefs.host)
                api.clearHistory(prefs.agentToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Forzar limpieza local inmediatamente en caso de que el servidor no responda rápido
            forceClear()
        }
    }

    fun refresh() {
        fetchRemote()
    }

    fun addRemoteNotification(entity: NotificationEntity) {
        val excludedIds = prefs.clearedNotificationIds
        if (entity.operationReference != null && excludedIds.contains(entity.operationReference)) return

        if (prefs.operationMode == "RECEPTOR") {
            // En modo RECEPTOR la fuente de verdad es la DB local.
            // Room Flow actualizará la UI automáticamente.
            viewModelScope.launch(Dispatchers.IO) {
                upsertReceptorNotification(entity)
            }
            return
        }

        // Modo EMISOR: sólo actualiza el StateFlow en memoria
        _remoteNotifications.update { currentList ->
            val hasValidRef = !entity.operationReference.isNullOrBlank()
            if (hasValidRef && currentList.any { it.operationReference == entity.operationReference }) {
                currentList
            } else {
                val newList = currentList.toMutableList()
                newList.add(0, entity)
                newList
            }
        }
    }

    private suspend fun upsertReceptorNotification(entity: NotificationEntity) {
        val ref = entity.operationReference ?: return
        if (prefs.clearedNotificationIds.contains(ref)) return
        val existing = db.notificationDao().getByOperationReference(ref)
        if (existing == null) {
            db.notificationDao().insert(entity.copy(id = 0))
        } else if (existing.status != entity.status) {
            db.notificationDao().update(existing.copy(status = entity.status))
        }
    }

    private fun fetchRemote() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                if (!prefs.isConfigured()) return@launch
                val api = RetrofitClient.getApi(prefs.host)

                if (prefs.operationMode == "EMISOR") {
                    fetchRemoteEmisor(api)
                } else {
                    fetchRemoteReceptor(api)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetchRemoteEmisor(api: com.notpago.data.remote.YapeApi) {
        val response = api.getNotifications(prefs.agentToken.trim(), onlyPending = 0)
        if (!response.isSuccessful) return
        val body = response.body()
        body?.meta?.yapeAppPin?.let { pin -> prefs.yapeAppPin = pin.trim() }

        val excludedIds = prefs.clearedNotificationIds
        val remoteData = body?.data ?: emptyList()

        // Actualizar estado local si el servidor cambió el estado (linked o discarded)
        remoteData.forEach { req ->
            if (req.operationReference != null && (req.status == "linked" || req.status == "discarded")) {
                val existing = db.notificationDao().getByCode(req.operationReference)
                if (existing != null && existing.status != "LINKED" && existing.status != "DISCARDED") {
                    db.notificationDao().update(existing.copy(status = req.status.uppercase()))
                }
            }
        }

        _remoteNotifications.value = remoteData.filter { req ->
            req.operationReference == null || !excludedIds.contains(req.operationReference)
        }.map { req ->
            NotificationEntity(
                amount = req.amount,
                senderName = normalizeSenderName(req.senderName),
                approvalCode = req.approvalCode,
                message = req.message,
                operationReference = req.operationReference,
                rawPayload = "REMOTE",
                status = req.status ?: "REMOTE",
                receivedAt = parseDate(req.receivedAt)
            )
        }
    }

    private suspend fun fetchRemoteReceptor(api: com.notpago.data.remote.YapeApi) {
        // Capturar el inicio del sync ANTES de la llamada para no perder registros
        // creados en el servidor durante el tiempo que tarda la petición HTTP.
        val syncStart = isoNow()
        val lastSync = prefs.lastReceptorSyncTime  // null = primer sync

        val response = api.getNotifications(
            token = prefs.agentToken.trim(),
            // Primer sync (lastSync=null): solo pendientes para no inundar con historial acumulado.
            // Syncs delta (lastSync!=null): todo (pending + linked) para detectar cambios de estado.
            onlyPending = if (lastSync == null) 1 else 0,
            updatedSince = lastSync  // null → solo activos | ISO 8601 → activos Y borrados desde esa fecha
        )
        if (!response.isSuccessful) return

        val body = response.body()
        body?.meta?.yapeAppPin?.let { pin -> prefs.yapeAppPin = pin.trim() }

        val excludedIds = prefs.clearedNotificationIds
        val remoteData = body?.data ?: emptyList()

        remoteData
            .filter { req -> req.operationReference == null || !excludedIds.contains(req.operationReference) }
            .forEach { req ->
                if (req.deletedAt != null) {
                    // Borrado en servidor mientras estábamos offline → borrar local
                    req.operationReference?.let { ref ->
                        db.notificationDao().deleteByCode(ref)
                        val excluded = prefs.clearedNotificationIds.toMutableSet()
                        excluded.add(ref)
                        prefs.clearedNotificationIds = excluded
                    }
                } else {
                    upsertReceptorNotification(
                        NotificationEntity(
                            amount = req.amount,
                            senderName = normalizeSenderName(req.senderName),
                            approvalCode = req.approvalCode,
                            message = req.message,
                            operationReference = req.operationReference,
                            rawPayload = "REMOTE",
                            status = req.status ?: "PENDING",
                            receivedAt = parseDate(req.receivedAt)
                        )
                    )
                }
            }

        // Reconciliación completa: cuando lastSync era null (primer sync o después de forceClear),
        // el servidor devuelve TODOS los activos. Cualquier registro local que no esté en esa
        // lista ya no existe en el servidor (fue borrado) → eliminarlo localmente.
        if (lastSync == null) {
            val serverRefs = remoteData
                .filter { it.deletedAt == null }
                .mapNotNull { it.operationReference }
                .toSet()
            db.notificationDao().getAllNotificationsOnce()
                .filter { local -> local.operationReference != null && local.operationReference !in serverRefs }
                .forEach { local -> db.notificationDao().deleteByCode(local.operationReference!!) }
        }

        prefs.lastReceptorSyncTime = syncStart
    }

    private fun isoNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private fun parseDate(dateStr: String): Long {
        return try {
            if (dateStr.contains("T")) {
                // Laravel ISO8601 (ej. 2024-05-25T12:00:00.000000Z)
                val format = if (dateStr.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
                val sdf = java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
            } else {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("America/Lima")
                }
                sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
            }
        } catch (e: Exception) {
            try {
                // Fallback a un formato sin microsegundos
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
            } catch (e2: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    override fun onCleared() {
        pusher?.disconnect()
        super.onCleared()
    }

    private fun normalizeSenderName(rawName: String?): String {
        if (rawName.isNullOrBlank()) return "Desconocido"
        
        // Si el servidor guardó basura de antes como "Confirmación de Pago | Adelicia Fer* te envió..."
        // Tratamos de extraer el nombre limpio usando el patrón
        val yapePattern = java.util.regex.Pattern.compile("¡?([^|]+?)\\s+te\\s+(?:yape[oó]|envi[oó])(?:\\s+un\\s+pago\\s+por)?\\s+S/\\s?[0-9.,]+", java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcher = yapePattern.matcher(rawName)
        if (matcher.find()) {
            return matcher.group(1)?.trim()?.removePrefix("Yape! ")?.trim() ?: rawName
        }
        
        // Si tiene "|", agarramos lo que esté después de "|" y antes de "te envió"
        if (rawName.contains("|")) {
             val parts = rawName.split("|")
             if (parts.size > 1) {
                 val part2 = parts[1].trim()
                 val idx = part2.indexOf(" te envi")
                 if (idx > -1) {
                     return part2.substring(0, idx).trim().removePrefix("Yape! ").trim()
                 }
                 val idx2 = part2.indexOf(" te yape")
                 if (idx2 > -1) {
                     return part2.substring(0, idx2).trim().removePrefix("Yape! ").trim()
                 }
             }
        }
        return rawName.trim().removePrefix("Yape! ").trim()
    }
}
