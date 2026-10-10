package com.smsbridge.gateway.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.smsbridge.gateway.SmsBridgeApp
import com.smsbridge.gateway.data.AppDatabase
import com.smsbridge.gateway.data.SentRequestEntity
import com.smsbridge.gateway.protocol.ErrorCodes
import com.smsbridge.gateway.protocol.SendStatus
import com.smsbridge.gateway.protocol.SmsDeliveryStatusPayload
import com.smsbridge.gateway.protocol.SmsSendRequestPayload
import com.smsbridge.gateway.protocol.SmsSendResultPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SmsSender(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = AppDatabase.getInstance(context)

    // Tracks multipart send progress: requestId -> counter of successful sent parts
    private val multipartProgress = ConcurrentHashMap<String, AtomicInteger>()

    init {
        registerStatusReceiver()
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_SMS_SENT)
            addAction(ACTION_SMS_DELIVERED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let { handleStatusBroadcast(it, resultCode) }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            }
        } catch (e: Exception) {
            try {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            } catch (ignored: Exception) {
                Log.w(TAG, "Failed to register SMS status receiver", ignored)
            }
        }
    }

    suspend fun processSendRequest(payload: SmsSendRequestPayload): SmsSendResultPayload {
        val requestId = payload.requestId
        val recipient = payload.recipient.trim()
        val body = payload.body

        // 1. Validate Input
        if (recipient.isBlank() || recipient.length < 3) {
            return SmsSendResultPayload(
                requestId = requestId,
                status = SendStatus.FAILED,
                errorCode = ErrorCodes.INVALID_PAYLOAD,
                errorMessage = "Invalid recipient phone number"
            )
        }
        if (body.isEmpty()) {
            return SmsSendResultPayload(
                requestId = requestId,
                status = SendStatus.FAILED,
                errorCode = ErrorCodes.INVALID_PAYLOAD,
                errorMessage = "Message body cannot be empty"
            )
        }

        // 2. Check Permissions
        if (!SmsPermissionHelper.hasSmsPermissions(context)) {
            return SmsSendResultPayload(
                requestId = requestId,
                status = SendStatus.FAILED,
                errorCode = ErrorCodes.PERMISSION_DENIED,
                errorMessage = "SMS permission not granted on Android device"
            )
        }

        // 3. Idempotency Check
        val existingRequest = db.sentRequestDao().getRequestById(requestId)
        if (existingRequest != null) {
            Log.d(TAG, "Idempotency hit for requestId $requestId, status=${existingRequest.status}")
            return SmsSendResultPayload(
                requestId = existingRequest.requestId,
                status = existingRequest.status,
                partsCount = existingRequest.partsCount,
                timestamp = existingRequest.updatedAt,
                errorCode = existingRequest.errorCode,
                errorMessage = existingRequest.errorMessage
            )
        }

        // 4. Resolve SmsManager (Dual-SIM handling)
        val smsManager = resolveSmsManager(payload.subscriptionId)
        if (smsManager == null) {
            return SmsSendResultPayload(
                requestId = requestId,
                status = SendStatus.FAILED,
                errorCode = ErrorCodes.NO_SIM,
                errorMessage = "No active SIM card available for SMS"
            )
        }

        // 5. Divide Message (Single vs Multipart)
        val parts = try {
            smsManager.divideMessage(body)
        } catch (e: Exception) {
            return SmsSendResultPayload(
                requestId = requestId,
                status = SendStatus.FAILED,
                errorCode = ErrorCodes.INTERNAL_ERROR,
                errorMessage = "Failed to divide message: ${e.message}"
            )
        }

        val partsCount = parts.size
        multipartProgress[requestId] = AtomicInteger(0)

        // 6. Record Initial Request in Database as SUBMITTED
        val sentEntity = SentRequestEntity(
            requestId = requestId,
            recipient = recipient,
            body = body,
            subscriptionId = payload.subscriptionId,
            status = SendStatus.SUBMITTED,
            partsCount = partsCount,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.sentRequestDao().insertRequest(sentEntity)

        // 7. Dispatch to RIL via SmsManager
        return try {
            if (partsCount <= 1) {
                val sentPI = createSentPendingIntent(requestId, 0, 1)
                val deliveryPI = createDeliveryPendingIntent(requestId, 0, 1)
                smsManager.sendTextMessage(recipient, null, body, sentPI, deliveryPI)
            } else {
                val sentPIs = ArrayList<PendingIntent>(partsCount)
                val deliveryPIs = ArrayList<PendingIntent>(partsCount)
                for (i in 0 until partsCount) {
                    sentPIs.add(createSentPendingIntent(requestId, i, partsCount))
                    deliveryPIs.add(createDeliveryPendingIntent(requestId, i, partsCount))
                }
                smsManager.sendMultipartTextMessage(recipient, null, parts, sentPIs, deliveryPIs)
            }

            // Return immediate SUBMITTED result
            SmsSendResultPayload(
                requestId = requestId,
                status = SendStatus.SUBMITTED,
                partsCount = partsCount,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending SMS", e)
            val failedResult = SmsSendResultPayload(
                requestId = requestId,
                status = SendStatus.FAILED,
                partsCount = partsCount,
                errorCode = ErrorCodes.CARRIER_ERROR,
                errorMessage = e.message ?: "Failed to dispatch SMS to cellular radio"
            )
            db.sentRequestDao().updateStatus(
                requestId,
                SendStatus.FAILED,
                System.currentTimeMillis(),
                failedResult.errorCode,
                failedResult.errorMessage
            )
            failedResult
        }
    }

    private fun handleStatusBroadcast(intent: Intent, resultCode: Int) {
        val action = intent.action ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val totalParts = intent.getIntExtra(EXTRA_TOTAL_PARTS, 1)

        val app = context.applicationContext as? SmsBridgeApp
        val serverManager = app?.webSocketServerManager

        scope.launch {
            if (action == ACTION_SMS_SENT) {
                if (resultCode == Activity.RESULT_OK) {
                    val counter = multipartProgress[requestId] ?: AtomicInteger(0)
                    val currentCount = counter.incrementAndGet()
                    Log.d(TAG, "SMS part $partIndex sent for request $requestId ($currentCount/$totalParts)")

                    if (currentCount >= totalParts) {
                        multipartProgress.remove(requestId)
                        db.sentRequestDao().updateStatus(requestId, SendStatus.SENT)

                        val payload = SmsSendResultPayload(
                            requestId = requestId,
                            status = SendStatus.SENT,
                            partsCount = totalParts,
                            timestamp = System.currentTimeMillis()
                        )
                        serverManager?.broadcastSendResult(payload)
                    }
                } else {
                    val (errCode, errDesc) = getSmsSendErrorDetails(resultCode)
                    Log.e(TAG, "SMS part $partIndex failed for request $requestId: $errDesc ($resultCode)")
                    multipartProgress.remove(requestId)

                    db.sentRequestDao().updateStatus(
                        requestId = requestId,
                        status = SendStatus.FAILED,
                        errorCode = errCode,
                        errorMessage = errDesc
                    )

                    val payload = SmsSendResultPayload(
                        requestId = requestId,
                        status = SendStatus.FAILED,
                        partsCount = totalParts,
                        errorCode = errCode,
                        errorMessage = errDesc
                    )
                    serverManager?.broadcastSendResult(payload)
                }
            } else if (action == ACTION_SMS_DELIVERED) {
                Log.d(TAG, "Carrier SMS Delivery Report received for request $requestId (resultCode: $resultCode)")
                if (resultCode == Activity.RESULT_OK) {
                    db.sentRequestDao().updateStatus(requestId, SendStatus.DELIVERED)
                    val payload = SmsDeliveryStatusPayload(
                        requestId = requestId,
                        status = "DELIVERED",
                        timestamp = System.currentTimeMillis()
                    )
                    serverManager?.broadcastDeliveryStatus(payload)
                } else {
                    val payload = SmsDeliveryStatusPayload(
                        requestId = requestId,
                        status = "DELIVERY_FAILED",
                        timestamp = System.currentTimeMillis()
                    )
                    serverManager?.broadcastDeliveryStatus(payload)
                }
            }
        }
    }

    private fun resolveSmsManager(subscriptionId: Int): SmsManager? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val base = context.getSystemService(SmsManager::class.java)
                if (subscriptionId > 0) {
                    base.createForSubscriptionId(subscriptionId)
                } else {
                    base
                }
            } else {
                @Suppress("DEPRECATION")
                if (subscriptionId > 0) {
                    SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                } else {
                    SmsManager.getDefault()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve SmsManager", e)
            null
        }
    }

    private fun createSentPendingIntent(requestId: String, partIndex: Int, totalParts: Int): PendingIntent {
        val intent = Intent(ACTION_SMS_SENT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_TOTAL_PARTS, totalParts)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val reqCode = (requestId.hashCode() xor partIndex) and 0x7FFFFFFF
        return PendingIntent.getBroadcast(context, reqCode, intent, flags)
    }

    private fun createDeliveryPendingIntent(requestId: String, partIndex: Int, totalParts: Int): PendingIntent {
        val intent = Intent(ACTION_SMS_DELIVERED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_TOTAL_PARTS, totalParts)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val reqCode = (requestId.hashCode() xor (partIndex + 10000)) and 0x7FFFFFFF
        return PendingIntent.getBroadcast(context, reqCode, intent, flags)
    }

    private fun getSmsSendErrorDetails(resultCode: Int): Pair<String, String> {
        return when (resultCode) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                Pair("GENERIC_FAILURE", "Radio generic failure")
            SmsManager.RESULT_ERROR_NO_SERVICE ->
                Pair("NO_SERVICE", "No cellular network service")
            SmsManager.RESULT_ERROR_NULL_PDU ->
                Pair("NULL_PDU", "Radio received null PDU")
            SmsManager.RESULT_ERROR_RADIO_OFF ->
                Pair("RADIO_OFF", "Cellular radio is off (Airplane mode)")
            else ->
                Pair("CARRIER_ERROR", "Carrier error code: $resultCode")
        }
    }

    companion object {
        private const val TAG = "SmsSender"
        const val ACTION_SMS_SENT = "com.smsbridge.gateway.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.smsbridge.gateway.SMS_DELIVERED"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_PART_INDEX = "extra_part_index"
        const val EXTRA_TOTAL_PARTS = "extra_total_parts"
    }
}
