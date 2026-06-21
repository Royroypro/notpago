package com.notpago.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notpago.data.local.entity.YapeNotificationEntity
import com.notpago.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface YapeNotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: YapeNotificationEntity): Long

    @Update
    suspend fun updateNotification(notification: YapeNotificationEntity)

    @Query("SELECT * FROM yape_notifications WHERE sync_status = :status ORDER BY received_at ASC")
    fun getNotificationsByStatusFlow(status: SyncStatus): Flow<List<YapeNotificationEntity>>

    @Query("SELECT * FROM yape_notifications ORDER BY received_at DESC")
    fun getAllNotificationsFlow(): Flow<List<YapeNotificationEntity>>

    @Query("SELECT * FROM yape_notifications WHERE sync_status = :status ORDER BY received_at ASC")
    suspend fun getNotificationsByStatus(status: SyncStatus): List<YapeNotificationEntity>

    @Query("SELECT * FROM yape_notifications WHERE operation_reference = :reference LIMIT 1")
    suspend fun getNotificationByReference(reference: String): YapeNotificationEntity?

    @Query("UPDATE yape_notifications SET sync_status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: Int, status: SyncStatus)
    
    @Query("DELETE FROM yape_notifications WHERE sync_status = 'SYNCED' AND received_at < datetime('now', '-7 days')")
    suspend fun deleteOldSyncedNotifications()

    @Query("DELETE FROM yape_notifications")
    suspend fun deleteAllNotifications()

    @Query("DELETE FROM yape_notifications WHERE operation_reference = :reference")
    suspend fun deleteByReference(reference: String)
}
