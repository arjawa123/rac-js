package com.example.devicecontrol

import android.util.Log
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketManager(private val url: String, private val handler: CommandHandler) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val TAG = "WebSocketManager"

    fun connect() {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to WebSocket")
                isConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Message received: $text")
                handler.handle(text) { response ->
                    webSocket.send(response)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.w(TAG, "WebSocket Closing: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e(TAG, "WebSocket Failure: ${t.message}")
                reconnect()
            }
        })
    }

    private fun reconnect() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isConnected) {
                Log.i(TAG, "Attempting to reconnect...")
                connect()
            }
        }, 5000)
    }

    fun disconnect() {
        webSocket?.close(1000, "App closed")
    }
}
