package com.smsbridge.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.smsbridge.gateway.MainActivity
import com.smsbridge.gateway.R
import com.smsbridge.gateway.SmsBridgeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SmsBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsBridge::ServerWakeLock")?.apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_SERVICE) {
            stopBridge()
            stopSelf()
            return START_NOT_STICKY
        }

        startBridge()
        return START_STICKY
    }

    private fun startBridge() {
        val notification = buildNotification("Starting SMS Bridge...", "Initializing TLS server...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 14 ForegroundServiceStartNotAllowedException or SecurityException
            android.util.Log.e("SmsBridgeService", "startForeground failed gracefully", e)
        }

        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max

        val app = application as? SmsBridgeApp ?: return
        val serverManager = app.webSocketServerManager

        val success = serverManager.startServer()
        if (!success) {
            updateNotification("Error starting bridge", "Port may be in use")
        }

        scope.launch {
            serverManager.serverStatus.collectLatest { status ->
                val title = if (status.isRunning) "SMS Bridge: Active" else "SMS Bridge: Stopped"
                val desc = if (status.isRunning) {
                    val auth = status.authenticatedClientsCount
                    val total = status.connectedClientsCount
                    "Port ${status.port} | $auth active iPhone(s) connected ($total socket(s))"
                } else {
                    status.lastError ?: "Server is stopped"
                }
                updateNotification(title, desc)
            }
        }
    }

    private fun stopBridge() {
        val app = application as? SmsBridgeApp
        app?.webSocketServerManager?.stopServer()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        scope.cancel()
    }

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String, content: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SmsBridgeService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_sms_bridge)
            .setContentIntent(mainPendingIntent)
            .addAction(0, getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "sms_bridge_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVICE = "com.smsbridge.gateway.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.smsbridge.gateway.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, SmsBridgeService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SmsBridgeService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
