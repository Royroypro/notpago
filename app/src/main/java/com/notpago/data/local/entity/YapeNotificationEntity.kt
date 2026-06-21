package com.notpago.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "yape_notifications")
data class YapeNotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    val amount: Double,
    
    @ColumnInfo(name = "operation_reference")
    val operationReference: String,
    
    @ColumnInfo(name = "sender_name")
    val senderName: String?,
    
    @ColumnInfo(name = "sender_phone")
    val senderPhone: String?,
    
    @ColumnInfo(name = "approval_code")
    val approvalCode: String?,
    
    val message: String?,
    
    @ColumnInfo(name = "transaction_type")
    val transactionType: String = "yape",
    
    @ColumnInfo(name = "received_at")
    val receivedAt: String, // format YYYY-MM-DD HH:mm:ss
    
    // Enum or Int to track status: PENDING, SYNCED, ERROR
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0
)

enum class SyncStatus {
    PENDING,
    SYNCED,
    ERROR,
    PAID
}
