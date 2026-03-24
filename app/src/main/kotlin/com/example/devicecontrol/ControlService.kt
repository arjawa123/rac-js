package com.example.devicecontrol

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import okhttp3.*
import java.util.concurrent.TimeUnit

class ControlService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var commandHandler: CommandHandler

    companion object {
        private const val CHANNEL_ID = "device_control_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "ControlService"
    }

    override fun onCreate() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        super.onCreate()
        Log.d(TAG, "Service Created & Foreground Started")

        acquireWakeLock()
        
        // Read config
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val wsUrl = prefs.getString("ws_url", "wss://bot-q7uitriv.b4a.run/") ?: ""
        val devId = prefs.getString("device_id", "my_phone") ?: "unknown"
        val authToken = prefs.getString("auth_token", "my-secret-token") ?: ""
        
        // Build URL with Query Params
        val httpUrl = HttpUrl.get(wsUrl).newBuilder()
            .addQueryParameter("client_id", devId)
            .addQueryParameter("auth", authToken)
            .build()

        commandHandler = CommandHandler(this)
        webSocketManager = WebSocketManager(httpUrl.toString(), commandHandler)
        webSocketManager.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Device Control Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Active")
            .setContentText("Listening for remote commands...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceControl::WakeLock")
        wakeLock?.acquire()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webSocketManager.isInitialized) {
            webSocketManager.disconnect()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        Log.d(TAG, "Service Destroyed")
    }
}
