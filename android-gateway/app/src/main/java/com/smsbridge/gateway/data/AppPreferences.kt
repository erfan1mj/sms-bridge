package com.smsbridge.gateway.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.security.SecureRandom

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sms_bridge_prefs", Context.MODE_PRIVATE)

    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, 8765)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null) ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var tlsCertSha256: String?
        get() = prefs.getString(KEY_TLS_SHA256, null)
        set(value) = prefs.edit().putString(KEY_TLS_SHA256, value).apply()

    var currentPairingToken: String?
        get() = prefs.getString(KEY_PAIRING_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_PAIRING_TOKEN, value).apply()

    var pairingTokenExpiry: Long
        get() = prefs.getLong(KEY_PAIRING_TOKEN_EXPIRY, 0L)
        private set(value) = prefs.edit().putLong(KEY_PAIRING_TOKEN_EXPIRY, value).apply()

    fun generateNewPairingToken(validityMillis: Long = 5 * 60 * 1000L): String {
        val randomBytes = ByteArray(16)
        SecureRandom().nextBytes(randomBytes)
        val token = randomBytes.joinToString("") { "%02x".format(it) }
        val expiry = System.currentTimeMillis() + validityMillis
        currentPairingToken = token
        pairingTokenExpiry = expiry
        return token
    }

    fun isPairingTokenValid(token: String): Boolean {
        val active = currentPairingToken ?: return false
        val expiry = pairingTokenExpiry
        return active == token && System.currentTimeMillis() <= expiry
    }

    fun clearPairingToken() {
        currentPairingToken = null
        pairingTokenExpiry = 0L
    }

    companion object {
        private const val KEY_PORT = "server_port"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_TLS_SHA256 = "tls_cert_sha256"
        private const val KEY_PAIRING_TOKEN = "pairing_token"
        private const val KEY_PAIRING_TOKEN_EXPIRY = "pairing_token_expiry"
    }
}
