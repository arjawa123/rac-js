package com.example.devicecontrol

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import android.os.Handler
import android.os.Looper

class CommandHandler(private val context: Context) {

    fun handle(jsonStr: String, sendResponse: (String) -> Unit) {
        var cmdId = ""
        try {
            val json = JSONObject(jsonStr)
            val command = json.getString("command")
            cmdId = json.optString("id", "")
            
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
                    val message = json.optString("text", "Hello from Remote!")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                    sendResponse(createResponse(cmdId, "toast", "Displayed"))
                }
                else -> {
                    sendResponse(createResponse(cmdId, "error", "Unknown command: $command"))
                }
            }
        } catch (e: Exception) {
            sendResponse(createResponse(cmdId, "error", e.message ?: "Invalid JSON"))
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
