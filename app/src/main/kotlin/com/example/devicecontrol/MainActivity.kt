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
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

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
        val urlLayout = TextInputLayout(this).apply {
            hint = "WebSocket Server URL"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(0, 0, 0, 24)
        }
        val urlInput = TextInputEditText(this).apply {
            setText(prefs.getString("ws_url", "wss://bot-q7uitriv.b4a.run/"))
        }
        urlLayout.addView(urlInput)
        rootLayout.addView(urlLayout)

        // Auth Token Input
        val authLayout = TextInputLayout(this).apply {
            hint = "Auth Token"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(0, 0, 0, 24)
        }
        val authInput = TextInputEditText(this).apply {
            setText(prefs.getString("auth_token", "my-secret-token"))
        }
        authLayout.addView(authInput)
        rootLayout.addView(authLayout)

        // Device ID Input
        val idLayout = TextInputLayout(this).apply {
            hint = "Device ID (Optional)"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(0, 0, 0, 48)
        }
        val idInput = TextInputEditText(this).apply {
            setText(prefs.getString("device_id", "my_phone"))
        }
        idLayout.addView(idInput)
        rootLayout.addView(idLayout)

        // Status
        val statusText = TextView(this).apply {
            text = "Status: Service Stopped"
            textSize = 16f
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText)

        // Start Button
        val startButton = MaterialButton(this).apply {
            text = "START SYSTEM"
            cornerRadius = 24
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
