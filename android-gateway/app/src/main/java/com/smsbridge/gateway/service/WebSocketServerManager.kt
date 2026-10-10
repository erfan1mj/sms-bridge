package com.smsbridge.gateway.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.smsbridge.gateway.data.AppDatabase
import com.smsbridge.gateway.data.AppPreferences
import com.smsbridge.gateway.protocol.BridgeMessage
import com.smsbridge.gateway.protocol.MessageTypes
import com.smsbridge.gateway.protocol.ProtocolHandler
import com.smsbridge.gateway.protocol.SmsDeliveryStatusPayload
import com.smsbridge.gateway.protocol.SmsIncomingPayload
import com.smsbridge.gateway.protocol.SmsSendResultPayload
import com.smsbridge.gateway.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

data class ServerStatus(
    val isRunning: Boolean = false,
    val port: Int = 8765,
    val connectedClientsCount: Int = 0,
    val authenticatedClientsCount: Int = 0,
    val lastError: String? = null
)

class WebSocketServerManager(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: AppPreferences,
    private val smsSender: SmsSender
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val protocolHandler = ProtocolHandler(context, db, prefs, smsSender)

    private var serverInstance: InternalWebSocketServer? = null
    private val sessionMap = ConcurrentHashMap<WebSocket, SessionState>()

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    data class SessionState(
        var authenticatedClientId: String? = null,
        val connectedAt: Long = System.currentTimeMillis()
    )

    fun startServer(port: Int = prefs.serverPort): Boolean {
        if (serverInstance != null) {
            stopServer()
        }

        return try {
            val tlsConfig = TlsManager.getOrCreateTlsConfig(context)
            prefs.tlsCertSha256 = tlsConfig.certSha256

            val server = InternalWebSocketServer(InetSocketAddress(port))
            val sslFactory = DefaultSSLWebSocketServerFactory(tlsConfig.sslContext)
            server.setWebSocketFactory(sslFactory)
            server.isReuseAddr = true
            server.connectionLostTimeout = 30 // Ping every 30s
            server.start()

            serverInstance = server
            _serverStatus.value = ServerStatus(
                isRunning = true,
                port = port,
                connectedClientsCount = 0,
                authenticatedClientsCount = 0,
                lastError = null
            )
            Log.i(TAG, "TLS WebSocket Server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TLS WebSocket Server on port $port", e)
            _serverStatus.value = ServerStatus(
                isRunning = false,
                port = port,
                lastError = e.message
            )
            false
        }
    }

    fun stopServer() {
        try {
            serverInstance?.stop(1000)
            serverInstance = null
            sessionMap.clear()
            _serverStatus.value = ServerStatus(isRunning = false, port = prefs.serverPort)
            Log.i(TAG, "WebSocket Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    fun broadcastIncomingSms(payload: SmsIncomingPayload): Boolean {
        val msg = BridgeMessage(
            version = 1,
            type = MessageTypes.SMS_INCOMING,
            payload = payload
        )
        val json = gson.toJson(msg)
        return broadcastToAuthenticated(json)
    }

    fun broadcastSendResult(payload: SmsSendResultPayload): Boolean {
        val msg = BridgeMessage(
            version = 1,
            type = MessageTypes.SMS_SEND_RESULT,
            payload = payload
        )
        val json = gson.toJson(msg)
        return broadcastToAuthenticated(json)
    }

    fun broadcastDeliveryStatus(payload: SmsDeliveryStatusPayload): Boolean {
        val msg = BridgeMessage(
            version = 1,
            type = MessageTypes.SMS_DELIVERY_STATUS,
            payload = payload
        )
        val json = gson.toJson(msg)
        return broadcastToAuthenticated(json)
    }

    fun disconnectAllClients() {
        sessionMap.keys.forEach { ws ->
            try {
                ws.close(1000, "Pairing revoked by gateway host")
            } catch (e: Exception) {
                // ignore
            }
        }
        sessionMap.clear()
        updateClientCounts()
    }

    private fun broadcastToAuthenticated(json: String): Boolean {
        var dispatched = false
        sessionMap.forEach { (ws, state) ->
            if (state.authenticatedClientId != null && ws.isOpen) {
                try {
                    ws.send(json)
                    dispatched = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send to client", e)
                }
            }
        }
        return dispatched
    }

    private fun updateClientCounts() {
        val total = sessionMap.size
        val authenticated = sessionMap.values.count { it.authenticatedClientId != null }
        _serverStatus.value = _serverStatus.value.copy(
            connectedClientsCount = total,
            authenticatedClientsCount = authenticated
        )
    }

    private inner class InternalWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
            Log.d(TAG, "New incoming client connection: ${conn.remoteSocketAddress}")
            sessionMap[conn] = SessionState()
            updateClientCounts()

            // Enforce authentication timeout (15 seconds)
            scope.launch {
                delay(15_000)
                val state = sessionMap[conn]
                if (state != null && state.authenticatedClientId == null && conn.isOpen) {
                    Log.w(TAG, "Closing unauthenticated connection timeout: ${conn.remoteSocketAddress}")
                    conn.close(4001, "Authentication timeout")
                }
            }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            Log.d(TAG, "Client disconnected: ${conn.remoteSocketAddress}, code=$code, reason=$reason")
            sessionMap.remove(conn)
            updateClientCounts()
        }

        override fun onMessage(conn: WebSocket, message: String) {
            scope.launch {
                val state = sessionMap[conn] ?: SessionState().also { sessionMap[conn] = it }
                val responseJson = protocolHandler.handleMessage(
                    rawJson = message,
                    sessionAuthenticatedClientId = state.authenticatedClientId,
                    onAuthenticated = { clientId ->
                        state.authenticatedClientId = clientId
                        updateClientCounts()
                    }
                )
                if (conn.isOpen) {
                    conn.send(responseJson)
                }
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "WebSocket server error on conn: ${conn?.remoteSocketAddress}", ex)
        }

        override fun onStart() {
            Log.i(TAG, "WebSocket server instance started successfully")
        }
    }

    companion object {
        private const val TAG = "WebSocketServerManager"
    }
}
