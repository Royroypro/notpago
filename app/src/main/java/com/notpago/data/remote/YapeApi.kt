package com.notpago.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

data class YapeWebhookRequest(
    val id: Long? = null,
    val amount: Double,
    @SerializedName("operation_reference") val operationReference: String?,
    @SerializedName("approval_code") val approvalCode: String?,
    @SerializedName("sender_name") val senderName: String,
    @SerializedName("sender_phone") val senderPhone: String? = null,
    val message: String?,
    @SerializedName("transaction_type") val transactionType: String = "yape",
    @SerializedName("received_at") val receivedAt: String,
    val status: String? = null,
    @SerializedName("order_id") val orderId: Long? = null,
    @SerializedName("payment_id") val paymentId: Long? = null,
    @SerializedName("is_pending") val isPending: Boolean? = null,
    @SerializedName("deleted_at") val deletedAt: String? = null
)

data class YapeMeta(
    @SerializedName("yape_app_pin") val yapeAppPin: String?
)

data class YapeResponse(
    val data: List<YapeWebhookRequest>,
    val meta: YapeMeta? = null
)

interface YapeApi {
    @Headers("Accept: application/json")
    @POST("/api/webhooks/yape")
    suspend fun sendWebhook(
        @Header("X-Yape-Agent-Token") token: String,
        @Body request: YapeWebhookRequest
    ): Response<Unit>

    @Headers("Accept: application/json")
    @POST("/api/webhooks/yape")
    suspend fun testConnection(
        @Header("X-Yape-Agent-Token") token: String,
        @Body request: Map<String, String>
    ): Response<Unit>

    @Headers("Accept: application/json")
    @retrofit2.http.GET("/api/webhooks/yape")
    suspend fun getNotifications(
        @Header("X-Yape-Agent-Token") token: String,
        @retrofit2.http.Query("only_pending") onlyPending: Int = 1,
        @retrofit2.http.Query("updated_since") updatedSince: String? = null
    ): Response<YapeResponse>

    @retrofit2.http.DELETE("/api/webhooks/yape/clear")
    suspend fun clearHistory(
        @Header("X-Yape-Agent-Token") token: String
    ): Response<Unit>

    @retrofit2.http.DELETE("/api/webhooks/yape/{reference}")
    suspend fun deleteNotification(
        @Header("X-Yape-Agent-Token") token: String,
        @retrofit2.http.Path("reference") reference: String
    ): Response<Unit>
}
