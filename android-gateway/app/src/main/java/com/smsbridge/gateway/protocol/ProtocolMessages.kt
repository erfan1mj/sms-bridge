package com.smsbridge.gateway.protocol

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * Android-to-iPhone SMS Bridge Protocol v1 Messages
 */

data class BridgeMessage<T>(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("type") val type: String,
    @SerializedName("payload") val payload: T
)

object MessageTypes {
    const val PAIR_REQUEST = "PAIR_REQUEST"
    const val PAIR_RESPONSE = "PAIR_RESPONSE"
    const val AUTHENTICATE = "AUTHENTICATE"
    const val AUTH_RESPONSE = "AUTH_RESPONSE"
    const val CONNECTION_STATUS = "CONNECTION_STATUS"
    const val SMS_INCOMING = "SMS_INCOMING"
    const val SMS_SEND_REQUEST = "SMS_SEND_REQUEST"
    const val SMS_SEND_RESULT = "SMS_SEND_RESULT"
    const val SMS_DELIVERY_STATUS = "SMS_DELIVERY_STATUS"
    const val SYNC_REQUEST = "SYNC_REQUEST"
    const val SYNC_RESPONSE = "SYNC_RESPONSE"
    const val ERROR = "ERROR"
}

// 1. Pairing
data class PairRequestPayload(
    @SerializedName("pairingToken") val pairingToken: String,
    @SerializedName("clientName") val clientName: String,
    @SerializedName("clientId") val clientId: String
)

data class SimCardInfo(
    @SerializedName("subscriptionId") val subscriptionId: Int,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("carrierName") val carrierName: String,
    @SerializedName("slotIndex") val slotIndex: Int,
    @SerializedName("isDefault") val isDefault: Boolean
)

data class PairResponsePayload(
    @SerializedName("success") val success: Boolean,
    @SerializedName("clientAuthToken") val clientAuthToken: String?,
    @SerializedName("gatewayDeviceName") val gatewayDeviceName: String,
    @SerializedName("gatewayAndroidVersion") val gatewayAndroidVersion: String,
    @SerializedName("simCards") val simCards: List<SimCardInfo> = emptyList(),
    @SerializedName("errorMessage") val errorMessage: String? = null
)

// 2. Authentication
data class AuthenticatePayload(
    @SerializedName("clientAuthToken") val clientAuthToken: String,
    @SerializedName("clientId") val clientId: String
)

data class AuthResponsePayload(
    @SerializedName("success") val success: Boolean,
    @SerializedName("gatewayDeviceName") val gatewayDeviceName: String,
    @SerializedName("simCards") val simCards: List<SimCardInfo> = emptyList(),
    @SerializedName("serverTimestamp") val serverTimestamp: Long = System.currentTimeMillis(),
    @SerializedName("errorMessage") val errorMessage: String? = null
)

// 3. Connection Status
data class ConnectionStatusPayload(
    @SerializedName("status") val status: String, // "CONNECTED", "AUTHENTICATED"
    @SerializedName("simCards") val simCards: List<SimCardInfo> = emptyList(),
    @SerializedName("smsPermissionGranted") val smsPermissionGranted: Boolean,
    @SerializedName("isDefaultSmsApp") val isDefaultSmsApp: Boolean,
    @SerializedName("batteryLevel") val batteryLevel: Int = -1
)

// 4. Incoming SMS
data class SmsIncomingPayload(
    @SerializedName("messageId") val messageId: String,
    @SerializedName("sender") val sender: String,
    @SerializedName("body") val body: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("subscriptionId") val subscriptionId: Int = -1,
    @SerializedName("isMultipart") val isMultipart: Boolean = false,
    @SerializedName("partCount") val partCount: Int = 1
)

// 5. Send SMS Request
data class SmsSendRequestPayload(
    @SerializedName("requestId") val requestId: String,
    @SerializedName("recipient") val recipient: String,
    @SerializedName("body") val body: String,
    @SerializedName("subscriptionId") val subscriptionId: Int = -1
)

// 6. Send SMS Result
object SendStatus {
    const val QUEUED = "QUEUED"
    const val SUBMITTED = "SUBMITTED"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"
    const val UNKNOWN = "UNKNOWN"
}

data class SmsSendResultPayload(
    @SerializedName("requestId") val requestId: String,
    @SerializedName("status") val status: String,
    @SerializedName("partsCount") val partsCount: Int = 1,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("errorCode") val errorCode: String? = null,
    @SerializedName("errorMessage") val errorMessage: String? = null
)

// 7. Delivery Status
data class SmsDeliveryStatusPayload(
    @SerializedName("requestId") val requestId: String,
    @SerializedName("status") val status: String, // "DELIVERED", "DELIVERY_FAILED"
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("rawPduStatus") val rawPduStatus: Int = 0
)

// 8. Synchronization
data class SyncRequestPayload(
    @SerializedName("sinceTimestamp") val sinceTimestamp: Long = 0L,
    @SerializedName("lastKnownMessageId") val lastKnownMessageId: String? = null,
    @SerializedName("limit") val limit: Int = 100
)

data class SyncResponsePayload(
    @SerializedName("messages") val messages: List<SmsIncomingPayload> = emptyList(),
    @SerializedName("pendingOutboxStatus") val pendingOutboxStatus: List<SmsSendResultPayload> = emptyList(),
    @SerializedName("serverTimestamp") val serverTimestamp: Long = System.currentTimeMillis()
)

// 9. Error
object ErrorCodes {
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val NO_SIM = "NO_SIM"
    const val CARRIER_ERROR = "CARRIER_ERROR"
    const val DUPLICATE_REQUEST = "DUPLICATE_REQUEST"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

data class ErrorPayload(
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String,
    @SerializedName("relatedRequestId") val relatedRequestId: String? = null
)

// QR Code Connection Envelope
data class QrPairingEnvelope(
    @SerializedName("v") val version: Int = 1,
    @SerializedName("endpoints") val endpoints: List<String>,
    @SerializedName("certSha256") val certSha256: String,
    @SerializedName("pairingToken") val pairingToken: String,
    @SerializedName("deviceName") val deviceName: String
)
