package com.smsbridge.gateway.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.smsbridge.gateway.SmsBridgeApp
import com.smsbridge.gateway.data.MessageEntity
import com.smsbridge.gateway.data.PairedClientEntity
import com.smsbridge.gateway.data.SentRequestEntity
import com.smsbridge.gateway.protocol.QrPairingEnvelope
import com.smsbridge.gateway.protocol.SimCardInfo
import com.smsbridge.gateway.service.NetworkUtils
import com.smsbridge.gateway.service.ServerStatus
import com.smsbridge.gateway.service.SmsBridgeService
import com.smsbridge.gateway.sms.SimManager
import com.smsbridge.gateway.sms.SmsPermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val serverStatus: ServerStatus = ServerStatus(),
    val ipAddresses: List<String> = emptyList(),
    val simCards: List<SimCardInfo> = emptyList(),
    val hasSmsPermissions: Boolean = false,
    val hasPhoneStatePermission: Boolean = false,
    val isDefaultSmsApp: Boolean = false,
    val pairedClients: List<PairedClientEntity> = emptyList(),
    val recentMessages: List<MessageEntity> = emptyList(),
    val recentRequests: List<SentRequestEntity> = emptyList(),
    val pairingQrBitmap: Bitmap? = null,
    val pairingToken: String? = null,
    val pairingExpiry: Long = 0L,
    val isPairingModeActive: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmsBridgeApp
    private val db = app.database
    private val prefs = app.preferences
    private val serverManager = app.webSocketServerManager
    private val gson = Gson()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshDiagnostics()
        observeServerStatus()
        observeDatabase()
    }

    private fun observeServerStatus() {
        viewModelScope.launch {
            serverManager.serverStatus.collectLatest { status ->
                _uiState.value = _uiState.value.copy(serverStatus = status)
            }
        }
    }

    private fun observeDatabase() {
        viewModelScope.launch {
            db.pairedClientDao().getActiveClientsFlow().collectLatest { clients ->
                _uiState.value = _uiState.value.copy(pairedClients = clients)
            }
        }
        viewModelScope.launch {
            db.messageDao().getAllMessagesFlow().collectLatest { msgs ->
                _uiState.value = _uiState.value.copy(recentMessages = msgs.take(20))
            }
        }
        viewModelScope.launch {
            db.sentRequestDao().getAllRequestsFlow().collectLatest { reqs ->
                _uiState.value = _uiState.value.copy(recentRequests = reqs.take(20))
            }
        }
    }

    fun refreshDiagnostics() {
        try {
            val ips = NetworkUtils.getLocalIpAddresses()
            val sims = SimManager.getActiveSimCards(app)
            val hasSms = SmsPermissionHelper.hasSmsPermissions(app)
            val hasPhone = SmsPermissionHelper.hasPhoneStatePermission(app)
            val isDefault = SmsPermissionHelper.isDefaultSmsApp(app)

            _uiState.value = _uiState.value.copy(
                ipAddresses = ips,
                simCards = sims,
                hasSmsPermissions = hasSms,
                hasPhoneStatePermission = hasPhone,
                isDefaultSmsApp = isDefault
            )
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error in refreshDiagnostics", e)
        }
    }

    fun toggleService() {
        if (_uiState.value.serverStatus.isRunning) {
            SmsBridgeService.stop(app)
        } else {
            SmsBridgeService.start(app)
        }
    }

    fun startPairingMode() {
        viewModelScope.launch {
            val token = prefs.generateNewPairingToken()
            val expiry = prefs.pairingTokenExpiry
            val port = prefs.serverPort
            val endpoints = NetworkUtils.getWebSocketEndpoints(port)
            val certSha256 = prefs.tlsCertSha256 ?: ""

            val envelope = QrPairingEnvelope(
                version = 1,
                endpoints = endpoints,
                certSha256 = certSha256,
                pairingToken = token,
                deviceName = prefs.deviceName
            )
            val json = gson.toJson(envelope)
            val bitmap = generateQrCodeBitmap(json, 512)

            _uiState.value = _uiState.value.copy(
                isPairingModeActive = true,
                pairingToken = token,
                pairingExpiry = expiry,
                pairingQrBitmap = bitmap
            )
        }
    }

    fun closePairingMode() {
        prefs.clearPairingToken()
        _uiState.value = _uiState.value.copy(
            isPairingModeActive = false,
            pairingToken = null,
            pairingQrBitmap = null
        )
    }

    fun revokeClient(clientId: String) {
        viewModelScope.launch {
            db.pairedClientDao().revokeClient(clientId)
            serverManager.disconnectAllClients()
        }
    }

    fun revokeAllPairings() {
        viewModelScope.launch {
            db.pairedClientDao().revokeAllClients()
            serverManager.disconnectAllClients()
        }
    }

    private suspend fun generateQrCodeBitmap(content: String, size: Int): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to generate QR code bitmap", e)
            null
        }
    }
}
