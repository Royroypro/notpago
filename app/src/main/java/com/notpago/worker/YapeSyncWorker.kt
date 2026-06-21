package com.notpago.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.notpago.data.local.database.AppDatabase
import com.notpago.data.local.entity.SyncStatus
import com.notpago.data.remote.RetrofitClient
import com.notpago.data.remote.YapeWebhookRequest
import com.notpago.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YapeSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d("YapeSyncWorker", "Starting sync job...")
        val database = AppDatabase.getDatabase(applicationContext)
        val dao = database.yapeNotificationDao()
        val prefsManager = PrefsManager(applicationContext)
        val token = prefsManager.agentToken
        val host = prefsManager.host

        if (token.isEmpty() || host.isEmpty()) {
            Log.e("YapeSyncWorker", "No agent token or host configured. Sync failed.")
            return@withContext Result.failure()
        }

        val api = RetrofitClient.getApi(host)

        try {
            val pendingNotifications = dao.getNotificationsByStatus(SyncStatus.PENDING)
            if (pendingNotifications.isEmpty()) {
                Log.d("YapeSyncWorker", "No pending notifications to sync.")
                return@withContext Result.success()
            }

            Log.d("YapeSyncWorker", "Found ${pendingNotifications.size} pending notifications.")
            var allSuccess = true
            var unauthorized = false

            for (notification in pendingNotifications) {
                try {
                    val request = YapeWebhookRequest(
                        amount = notification.amount,
                        operationReference = notification.operationReference,
                        approvalCode = notification.approvalCode,
                        senderName = notification.senderName ?: "",
                        message = notification.message,
                        transactionType = notification.transactionType,
                        receivedAt = notification.receivedAt
                    )
                    val response = api.sendWebhook(token, request)

                    if (response.isSuccessful || response.code() == 201 || response.code() == 200) {
                        Log.d("YapeSyncWorker", "Synced notification: ${notification.operationReference}")
                        dao.updateSyncStatus(notification.id, SyncStatus.SYNCED)
                    } else if (response.code() == 401) {
                        Log.e("YapeSyncWorker", "Unauthorized (401). Token might be invalid.")
                        allSuccess = false
                        unauthorized = true
                        break
                    } else {
                        Log.e("YapeSyncWorker", "Server error (${response.code()}) for: ${notification.operationReference}")
                        allSuccess = false
                    }
                } catch (e: Exception) {
                    Log.e("YapeSyncWorker", "Network error while syncing: ${e.message}")
                    allSuccess = false
                }
            }

            if (unauthorized) Result.failure()
            else if (allSuccess) Result.success()
            else Result.retry()

        } catch (e: Exception) {
            Log.e("YapeSyncWorker", "Unexpected error: ${e.message}")
            Result.retry()
        }
    }
}
