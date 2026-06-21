package com.notpago.data

import android.content.Context
import android.util.Log
import com.notpago.data.local.AppDatabase
import com.notpago.data.local.NotificationEntity
import com.notpago.data.remote.RetrofitClient
import com.notpago.data.remote.YapeWebhookRequest
import com.notpago.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class SyncManager(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val prefs = PrefsManager(context)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("America/Lima")
    }

    suspend fun syncPending() = withContext(Dispatchers.IO) {
        if (!prefs.isConfigured()) {
            Log.w("SyncManager", "Sync aborted: Configuration missing (Host: ${prefs.host.isNotEmpty()}, Token: ${prefs.agentToken.isNotEmpty()})")
            return@withContext
        }

        val pending = db.notificationDao().getPendingNotifications()
        if (pending.isEmpty()) return@withContext

        Log.d("SyncManager", "Starting sync for ${pending.size} notifications")
        val api = RetrofitClient.getApi(prefs.host)

        pending.forEach { notification ->
            try {
                val request = YapeWebhookRequest(
                    amount = notification.amount,
                    operationReference = notification.operationReference,
                    approvalCode = notification.approvalCode,
                    senderName = notification.senderName,
                    message = notification.message,
                    receivedAt = dateFormat.format(Date(notification.receivedAt))
                )

                val response = api.sendWebhook(prefs.agentToken, request)
                if (response.isSuccessful) {
                    Log.i("SyncManager", "Successfully synced notification ${notification.id}")
                    db.notificationDao().update(notification.copy(status = "SENT"))
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("SyncManager", "Error syncing ${notification.id}: ${response.code()} ${response.message()} - Body: $errorBody")
                    db.notificationDao().update(notification.copy(status = "FAILED"))
                }
            } catch (e: Exception) {
                Log.e("SyncManager", "Exception syncing notification ${notification.id}", e)
                db.notificationDao().update(notification.copy(status = "FAILED"))
            }
        }
    }
}
