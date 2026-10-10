package com.smsbridge.gateway.service

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

data class TlsConfig(
    val sslContext: SSLContext,
    val certSha256: String,
    val certificate: X509Certificate
)

object TlsManager {
    private const val KEYSTORE_FILE = "sms_bridge_keystore.bks"
    private const val KEY_ALIAS = "sms_bridge_tls"
    private val KEY_PASSWORD = "SmsBridgeInternalPassword".toCharArray()
    private const val TAG = "TlsManager"

    init {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register BouncyCastleProvider in TlsManager", e)
        }
    }

    fun getOrCreateTlsConfig(context: Context): TlsConfig {
        val file = File(context.filesDir, KEYSTORE_FILE)
        val keyStore = KeyStore.getInstance("PKCS12")

        if (file.exists()) {
            try {
                FileInputStream(file).use { fis ->
                    keyStore.load(fis, KEY_PASSWORD)
                }
                val cert = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
                if (cert != null) {
                    val sha256 = calculateSha256Fingerprint(cert)
                    val sslContext = createSslContext(keyStore)
                    return TlsConfig(sslContext, sha256, cert)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Existing keystore corrupted or unreadable, regenerating...", e)
                file.delete()
            }
        }

        return try {
            keyStore.load(null, null)
            val keyPair = generateKeyPair()
            val cert = generateSelfSignedCertificate(keyPair)
            val chain = arrayOf<Certificate>(cert)

            keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, KEY_PASSWORD, chain)
            try {
                FileOutputStream(file).use { fos ->
                    keyStore.store(fos, KEY_PASSWORD)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist keystore to disk, using in-memory keystore", e)
            }

            val sha256 = calculateSha256Fingerprint(cert)
            val sslContext = createSslContext(keyStore)
            TlsConfig(sslContext, sha256, cert)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating TLS configuration, creating fallback SSLContext", e)
            // Universal fallback
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, SecureRandom())
            TlsConfig(sslContext, "DEFAULT_TLS_UNPINNED", generateSelfSignedCertificate(generateKeyPair()))
        }
    }

    private fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        return keyGen.generateKeyPair()
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val startDate = Date(now - 24 * 60 * 60 * 1000L) // Yesterday to avoid clock skew
        val endDate = Date(now + 10L * 365 * 24 * 60 * 60 * 1000L) // 10 years

        val serial = BigInteger(64, SecureRandom())
        val subject = X500Name("CN=SMSBridge-Gateway, OU=PersonalBridge, O=SmsBridge, C=US")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            startDate,
            endDate,
            subject,
            keyPair.public
        )

        val signerBuilder = JcaContentSignerBuilder("SHA256withRSA")
        try {
            signerBuilder.setProvider(BouncyCastleProvider.PROVIDER_NAME)
        } catch (e: Exception) {
            // fallback to default provider
        }
        val signer = signerBuilder.build(keyPair.private)
        val certHolder = certBuilder.build(signer)

        val converter = JcaX509CertificateConverter()
        try {
            converter.setProvider(BouncyCastleProvider.PROVIDER_NAME)
        } catch (e: Exception) {
            // fallback
        }
        return converter.getCertificate(certHolder)
    }

    private fun calculateSha256Fingerprint(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun createSslContext(keyStore: KeyStore): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEY_PASSWORD)

        // Using "TLS" automatically supports TLS 1.2 and TLS 1.3 across all Android versions
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())
        return sslContext
    }
}
