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

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PERMISSIONS = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Root layout
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFFF5F5F5.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = TextView(this).apply {
            text = "Device Controller"
            textSize = 28f
            setTextColor(0xFF333333.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 80)
        }
        rootLayout.addView(header)

        // Preferences for Config
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)

        // URL Input
        val urlInput = EditText(this).apply {
            hint = "Server URL"
            setText(prefs.getString("ws_url", "https://pygram.xnv.biz.id"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        rootLayout.addView(urlInput)

        // Auth Token Input
        val authInput = EditText(this).apply {
            hint = "Auth Token"
            setText(prefs.getString("auth_token", "AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        rootLayout.addView(authInput)

        // Device ID Input
        val idInput = EditText(this).apply {
            hint = "Device ID (Optional)"
            setText(prefs.getString("device_id", "my_phone"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 48) }
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        rootLayout.addView(idInput)

        // Status
        val statusText = TextView(this).apply {
            text = "Status: Service Stopped"
            textSize = 16f
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText)

        // Start Button
        val startButton = Button(this).apply {
            text = "START SYSTEM"
            setPadding(0, 40, 0, 40)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                val devId = idInput.text.toString().trim()
                val authToken = authInput.text.toString().trim()
                
                if (url.isEmpty()) {
                    Toast.makeText(this@MainActivity, "URL is required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Save Config
                prefs.edit().apply {
                    putString("ws_url", url)
                    putString("device_id", devId)
                    putString("auth_token", authToken)
                    apply()
                }

                statusText.text = "Status: Service Starting..."
                checkAndStartService()
            }
        }
        rootLayout.addView(startButton)

        setContentView(rootLayout)
        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }

        // Meminta user mematikan optimasi baterai (Doze) untuk aplikasi ini
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun checkAndStartService() {
        val intent = Intent(this, ControlService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
