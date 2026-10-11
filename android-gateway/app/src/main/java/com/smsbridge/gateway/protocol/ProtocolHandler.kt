package com.smsbridge.gateway.protocol

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smsbridge.gateway.data.AppDatabase
import com.smsbridge.gateway.data.AppPreferences
import com.smsbridge.gateway.data.PairedClientEntity
import com.smsbridge.gateway.sms.SimManager
import com.smsbridge.gateway.sms.SmsPermissionHelper
import com.smsbridge.gateway.sms.SmsSender
import java.security.SecureRandom
import java.util.UUID

class ProtocolHandler(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: AppPreferences,
    private val smsSender: SmsSender
) {
    private val gson = Gson()

    suspend fun handleMessage(
        rawJson: String,
        sessionAuthenticatedClientId: String?,
        onAuthenticated: (clientId: String) -> Unit
    ): String {
        return try {
            val jsonObject = JsonParser.parseString(rawJson).asJsonObject
            val type = jsonObject.get("type")?.asString ?: return createErrorJson(ErrorCodes.INVALID_PAYLOAD, "Missing message type")
            val id = jsonObject.get("id")?.asString ?: UUID.randomUUID().toString()
            val payloadElement = jsonObject.get("payload") ?: JsonObject()

            when (type) {
                MessageTypes.PAIR_REQUEST -> {
                    val pairReq = gson.fromJson(payloadElement, PairRequestPayload::class.java)
                    handlePairRequest(id, pairReq, onAuthenticated)
                }

                MessageTypes.AUTHENTICATE -> {
                    val authReq = gson.fromJson(payloadElement, AuthenticatePayload::class.java)
                    handleAuthenticate(id, authReq, onAuthenticated)
                }

                MessageTypes.SMS_SEND_REQUEST -> {
                    if (sessionAuthenticatedClientId == null) {
                        createErrorJson(ErrorCodes.UNAUTHORIZED, "Session not authenticated", id)
                    } else {
                        val sendReq = gson.fromJson(payloadElement, SmsSendRequestPayload::class.java)
                        val result = smsSender.processSendRequest(sendReq)
                        val bridgeMsg = BridgeMessage(
                            version = 1,
                            type = MessageTypes.SMS_SEND_RESULT,
                            payload = result
                        )
                        gson.toJson(bridgeMsg)
                    }
                }

                MessageTypes.SYNC_REQUEST -> {
                    if (sessionAuthenticatedClientId == null) {
                        createErrorJson(ErrorCodes.UNAUTHORIZED, "Session not authenticated", id)
                    } else {
                        val syncReq = gson.fromJson(payloadElement, SyncRequestPayload::class.java)
                        handleSyncRequest(id, syncReq)
                    }
                }

                MessageTypes.CONNECTION_STATUS -> {
                    handleConnectionStatus(id)
                }

                else -> {
                    createErrorJson(ErrorCodes.INVALID_PAYLOAD, "Unknown message type: $type", id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming message", e)
            createErrorJson(ErrorCodes.INVALID_PAYLOAD, "Malformed JSON or protocol error: ${e.message}")
        }
    }

    private suspend fun handlePairRequest(
        msgId: String,
        req: PairRequestPayload,
        onAuthenticated: (clientId: String) -> Unit
    ): String {
        val isValid = prefs.isPairingTokenValid(req.pairingToken)
        if (!isValid) {
            val response = BridgeMessage(
                version = 1,
                type = MessageTypes.PAIR_RESPONSE,
                payload = PairResponsePayload(
                    success = false,
                    clientAuthToken = null,
                    gatewayDeviceName = prefs.deviceName,
                    gatewayAndroidVersion = Build.VERSION.RELEASE,
                    errorMessage = "Invalid or expired pairing token. Please scan the QR code again."
                )
            )
            return gson.toJson(response)
        }

        // Generate persistent auth token
        val tokenBytes = ByteArray(32)
        SecureRandom().nextBytes(tokenBytes)
        val authToken = tokenBytes.joinToString("") { "%02x".format(it) }

        val clientEntity = PairedClientEntity(
            clientId = req.clientId,
            clientName = req.clientName,
            authToken = authToken,
            pairedAt = System.currentTimeMillis(),
            lastSeenAt = System.currentTimeMillis(),
            isRevoked = false
        )
        db.pairedClientDao().insertOrUpdate(clientEntity)
        prefs.clearPairingToken()

        onAuthenticated(req.clientId)

        val simCards = SimManager.getActiveSimCards(context)
        val response = BridgeMessage(
            version = 1,
            type = MessageTypes.PAIR_RESPONSE,
            payload = PairResponsePayload(
                success = true,
                clientAuthToken = authToken,
                gatewayDeviceName = prefs.deviceName,
                gatewayAndroidVersion = Build.VERSION.RELEASE,
                simCards = simCards
            )
        )
        return gson.toJson(response)
    }

    private suspend fun handleAuthenticate(
        msgId: String,
        req: AuthenticatePayload,
        onAuthenticated: (clientId: String) -> Unit
    ): String {
        val client = db.pairedClientDao().getActiveClientByToken(req.clientAuthToken)
        if (client == null || client.clientId != req.clientId || client.isRevoked) {
            return createErrorJson(ErrorCodes.UNAUTHORIZED, "Authentication failed: invalid or revoked token", msgId)
        }

        db.pairedClientDao().updateLastSeen(client.clientId)
        onAuthenticated(client.clientId)

        val simCards = SimManager.getActiveSimCards(context)
        val response = BridgeMessage(
            version = 1,
            type = MessageTypes.AUTH_RESPONSE,
            payload = AuthResponsePayload(
                success = true,
                gatewayDeviceName = prefs.deviceName,
                simCards = simCards,
                serverTimestamp = System.currentTimeMillis()
            )
        )
        return gson.toJson(response)
    }

    private suspend fun handleSyncRequest(msgId: String, req: SyncRequestPayload): String {
        val limit = req.limit.coerceIn(1, 200)
        val messages = db.messageDao().getMessagesSince(req.sinceTimestamp, limit).map {
            SmsIncomingPayload(
                messageId = it.messageId,
                sender = it.sender,
                body = it.body,
                timestamp = it.timestamp,
                subscriptionId = it.subscriptionId,
                isMultipart = it.isMultipart,
                partCount = it.partCount
            )
        }

        // Mark fetched messages as synced
        if (messages.isNotEmpty()) {
            db.messageDao().markMessagesAsSynced(messages.map { it.messageId })
        }

        val pendingRequests = db.sentRequestDao().getPendingOrRecentSent().map {
            SmsSendResultPayload(
                requestId = it.requestId,
                status = it.status,
                partsCount = it.partsCount,
                timestamp = it.updatedAt,
                errorCode = it.errorCode,
                errorMessage = it.errorMessage
            )
        }

        val response = BridgeMessage(
            version = 1,
            type = MessageTypes.SYNC_RESPONSE,
            payload = SyncResponsePayload(
                messages = messages,
                pendingOutboxStatus = pendingRequests,
                serverTimestamp = System.currentTimeMillis()
            )
        )
        return gson.toJson(response)
    }

    private fun handleConnectionStatus(msgId: String): String {
        val simCards = SimManager.getActiveSimCards(context)
        val hasSms = SmsPermissionHelper.hasSmsPermissions(context)
        val isDefault = SmsPermissionHelper.isDefaultSmsApp(context)

        val response = BridgeMessage(
            version = 1,
            type = MessageTypes.CONNECTION_STATUS,
            payload = ConnectionStatusPayload(
                status = "AUTHENTICATED",
                simCards = simCards,
                smsPermissionGranted = hasSms,
                isDefaultSmsApp = isDefault
            )
        )
        return gson.toJson(response)
    }

    fun createErrorJson(code: String, message: String, relatedId: String? = null): String {
        val err = BridgeMessage(
            version = 1,
            type = MessageTypes.ERROR,
            payload = ErrorPayload(code = code, message = message, relatedRequestId = relatedId)
        )
        return gson.toJson(err)
    }

    companion object {
        private const val TAG = "ProtocolHandler"
    }
}
