package com.notpago.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY receivedAt DESC")
    fun getAllNotifications(): Flow<List<NotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity): Long

    @Update
    suspend fun update(notification: NotificationEntity)

    @Query("SELECT * FROM notifications WHERE status = 'PENDING'")
    suspend fun getPendingNotifications(): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE approvalCode = :code OR operationReference = :code LIMIT 1")
    suspend fun getByCode(code: String): NotificationEntity?

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()

    @Query("DELETE FROM notifications WHERE operationReference = :code")
    suspend fun deleteByCode(code: String)

    @Query("SELECT * FROM notifications ORDER BY receivedAt DESC")
    suspend fun getAllNotificationsOnce(): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE operationReference = :reference LIMIT 1")
    suspend fun getByOperationReference(reference: String): NotificationEntity?
}
