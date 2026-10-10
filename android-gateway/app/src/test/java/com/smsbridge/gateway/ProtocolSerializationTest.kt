package com.smsbridge.gateway

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.smsbridge.gateway.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProtocolSerializationTest {
    private val gson = Gson()

    @Test
    fun testPairRequestSerialization() {
        val payload = PairRequestPayload(
            pairingToken = "abc123token",
            clientName = "iPhone 15",
            clientId = "client-uuid-1"
        )
        val msg = BridgeMessage(
            version = 1,
            type = MessageTypes.PAIR_REQUEST,
            payload = payload
        )

        val json = gson.toJson(msg)
        assertNotNull(json)

        val parsedObj = JsonParser.parseString(json).asJsonObject
        assertEquals(1, parsedObj.get("version").asInt)
        assertEquals("PAIR_REQUEST", parsedObj.get("type").asString)

        val payloadObj = parsedObj.getAsJsonObject("payload")
        assertEquals("abc123token", payloadObj.get("pairingToken").asString)
        assertEquals("iPhone 15", payloadObj.get("clientName").asString)
    }

    @Test
    fun testSmsIncomingPersianUnicodeSerialization() {
        val persianText = "سلام! کد فعال‌سازی شما: ۱۲۳۴۵ است."
        val payload = SmsIncomingPayload(
            messageId = "msg_001",
            sender = "+989121112233",
            body = persianText,
            timestamp = 1728608400000L,
            subscriptionId = 1,
            isMultipart = false,
            partCount = 1
        )
        val msg = BridgeMessage(
            version = 1,
            type = MessageTypes.SMS_INCOMING,
            payload = payload
        )

        val json = gson.toJson(msg)
        assertTrue(json.contains("سلام! کد فعال‌سازی شما: ۱۲۳۴۵ است."))

        val deserialized = gson.fromJson(json, BridgeMessage::class.java)
        assertEquals(MessageTypes.SMS_INCOMING, deserialized.type)
    }

    @Test
    fun testSmsSendRequestAndStatusEnums() {
        assertEquals("QUEUED", SendStatus.QUEUED)
        assertEquals("SUBMITTED", SendStatus.SUBMITTED)
        assertEquals("SENT", SendStatus.SENT)
        assertEquals("DELIVERED", SendStatus.DELIVERED)
        assertEquals("FAILED", SendStatus.FAILED)

        val sendPayload = SmsSendRequestPayload(
            requestId = "req_100",
            recipient = "+1234567890",
            body = "Testing bridge outgoing message",
            subscriptionId = 1
        )
        val msg = BridgeMessage(
            version = 1,
            type = MessageTypes.SMS_SEND_REQUEST,
            payload = sendPayload
        )
        val json = gson.toJson(msg)
        assertTrue(json.contains("req_100"))
        assertTrue(json.contains("Testing bridge outgoing message"))
    }
}
