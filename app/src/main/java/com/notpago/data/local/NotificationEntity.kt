package com.notpago.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val senderName: String,
    val approvalCode: String?,
    val message: String?,
    val operationReference: String?,
    val rawPayload: String,
    val status: String, // "PENDING", "SENT", "FAILED"
    val receivedAt: Long = System.currentTimeMillis()
)
