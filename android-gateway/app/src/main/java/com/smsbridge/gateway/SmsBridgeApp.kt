package com.smsbridge.gateway

import android.app.Application
import android.util.Log
import com.smsbridge.gateway.data.AppDatabase
import com.smsbridge.gateway.data.AppPreferences
import com.smsbridge.gateway.service.WebSocketServerManager
import com.smsbridge.gateway.sms.SmsSender
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

class SmsBridgeApp : Application() {
    lateinit var database: AppDatabase
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var smsSender: SmsSender
        private set

    lateinit var webSocketServerManager: WebSocketServerManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Setup crash logging to prevent silent crashes and record stack traces
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashLog = File(filesDir, "crash.log")
                crashLog.writeText("CRASH on thread ${thread.name}:\n" + Log.getStackTraceString(throwable))
                Log.e("SmsBridgeCrash", "FATAL CRASH in application", throwable)
            } catch (e: Exception) {
                // ignore
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 2. Register BouncyCastle Security Provider
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        } catch (e: Exception) {
            Log.e("SmsBridgeApp", "Failed to register BouncyCastleProvider", e)
        }

        // 3. Initialize components safely
        try {
            database = AppDatabase.getInstance(this)
            preferences = AppPreferences(this)
            smsSender = SmsSender(this)
            webSocketServerManager = WebSocketServerManager(this, database, preferences, smsSender)
        } catch (e: Exception) {
            Log.e("SmsBridgeApp", "Failed to initialize app components", e)
        }
    }

    companion object {
        lateinit var instance: SmsBridgeApp
            private set
    }
}
