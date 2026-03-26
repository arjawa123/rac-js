package com.example.devicecontrol

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

class ControlService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var pollingManager: PollingManager
    private lateinit var commandHandler: CommandHandler

    companion object {
        private const val CHANNEL_ID = "device_control_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "ControlService"
    }

    override fun onCreate() {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 34) { // Android 14+ = UPSIDE_DOWN_CAKE
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        super.onCreate()
        Log.d(TAG, "Service Created & Polling Started")

        acquireWakeLock()
        
        // Read config
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("ws_url", "https://pygram.xnv.biz.id") ?: ""
        val devId = prefs.getString("device_id", "my_phone") ?: "unknown"
        val authToken = prefs.getString("auth_token", "AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8") ?: ""
        
        // Normalisasikan URL (hapus /ws jika ada, ganti wss:// ke https://)
        var cleanUrl = serverUrl.replace("/ws", "").replace("wss://", "https://").replace("ws://", "http://")
        if (cleanUrl.endsWith("/")) cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)

        commandHandler = CommandHandler(this)
        pollingManager = PollingManager(cleanUrl, devId, authToken, commandHandler)
        pollingManager.start()
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
            .setContentTitle("Background check status")
            .setContentText("Service normal")
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
        if (::pollingManager.isInitialized) {
            pollingManager.stop()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        Log.d(TAG, "Service Destroyed")
    }
}
