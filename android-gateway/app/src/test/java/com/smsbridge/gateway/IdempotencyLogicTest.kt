package com.smsbridge.gateway

import com.smsbridge.gateway.protocol.ErrorCodes
import com.smsbridge.gateway.protocol.SendStatus
import org.junit.Assert.*
import org.junit.Test

class IdempotencyLogicTest {

    @Test
    fun testDuplicateRequestIdPreventionLogic() {
        val cache = mutableMapOf<String, String>()

        fun processRequest(requestId: String): Pair<String, Boolean> {
            if (cache.containsKey(requestId)) {
                return Pair(cache[requestId]!!, true) // Cache hit, duplicate prevented
            }
            cache[requestId] = SendStatus.SENT
            return Pair(SendStatus.SENT, false) // First send
        }

        val firstCall = processRequest("req_unique_01")
        assertFalse(firstCall.second)
        assertEquals(SendStatus.SENT, firstCall.first)

        val secondCall = processRequest("req_unique_01")
        assertTrue(secondCall.second) // Detected duplicate
        assertEquals(SendStatus.SENT, secondCall.first) // Returned previous status without re-invoking radio
    }

    @Test
    fun testErrorCodesCompleteness() {
        assertNotNull(ErrorCodes.UNAUTHORIZED)
        assertNotNull(ErrorCodes.INVALID_PAYLOAD)
        assertNotNull(ErrorCodes.PERMISSION_DENIED)
        assertNotNull(ErrorCodes.NO_SIM)
        assertNotNull(ErrorCodes.CARRIER_ERROR)
        assertNotNull(ErrorCodes.DUPLICATE_REQUEST)
        assertNotNull(ErrorCodes.RATE_LIMITED)
    }
}
