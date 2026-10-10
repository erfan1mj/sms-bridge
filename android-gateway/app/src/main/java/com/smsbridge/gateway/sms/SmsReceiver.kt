package com.smsbridge.gateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.smsbridge.gateway.SmsBridgeApp
import com.smsbridge.gateway.data.MessageEntity
import com.smsbridge.gateway.protocol.SmsIncomingPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class SmsReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages: Array<SmsMessage>? = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SMS messages from intent", e)
            null
        }

        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "Empty SMS intent received")
            return
        }

        val pendingResult = goAsync()

        scope.launch {
            try {
                // Group messages by originating address in case multiple senders arrive simultaneously
                val sender = messages[0].originatingAddress ?: "Unknown"
                val timestamp = messages[0].timestampMillis

                // Concatenate multipart SMS in exact sequence
                val bodyBuilder = StringBuilder()
                for (sms in messages) {
                    val body = sms.displayMessageBody
                    if (body != null) {
                        bodyBuilder.append(body)
                    }
                }
                val fullBody = bodyBuilder.toString()
                val isMultipart = messages.size > 1
                val partCount = messages.size

                // Extract subscription ID (Dual-SIM) from extras if present
                val subId = extractSubscriptionId(intent)

                // Generate stable message ID
                val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(16)}"

                // Avoid logging full private body for security & privacy
                Log.d(TAG, "Received incoming SMS (id: $messageId, parts: $partCount, subId: $subId)")

                val app = context.applicationContext as? SmsBridgeApp
                val db = app?.database

                val entity = MessageEntity(
                    messageId = messageId,
                    sender = sender,
                    body = fullBody,
                    timestamp = timestamp,
                    subscriptionId = subId,
                    isMultipart = isMultipart,
                    partCount = partCount,
                    direction = "INCOMING",
                    syncedToClient = false
                )

                db?.messageDao()?.insertMessage(entity)

                // Dispatch to active connected iPhone client via WebSocket
                val payload = SmsIncomingPayload(
                    messageId = messageId,
                    sender = sender,
                    body = fullBody,
                    timestamp = timestamp,
                    subscriptionId = subId,
                    isMultipart = isMultipart,
                    partCount = partCount
                )

                val serverManager = app?.webSocketServerManager
                val dispatched = serverManager?.broadcastIncomingSms(payload) ?: false

                if (dispatched) {
                    db?.messageDao()?.markMessagesAsSynced(listOf(messageId))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun extractSubscriptionId(intent: Intent): Int {
        val extras = intent.extras ?: return -1
        return when {
            extras.containsKey("subscription") -> extras.getInt("subscription", -1)
            extras.containsKey("sub_id") -> extras.getInt("sub_id", -1)
            extras.containsKey("subId") -> extras.getInt("subId", -1)
            extras.containsKey("android.telephony.extra.SUBSCRIPTION_INDEX") ->
                extras.getInt("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
            else -> -1
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
