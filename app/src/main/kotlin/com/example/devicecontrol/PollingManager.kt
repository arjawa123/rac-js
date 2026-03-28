package com.example.devicecontrol

import android.util.Log
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class PollingManager(
    private val baseUrl: String,
    private val clientId: String,
    private val authToken: String,
    private val handler: CommandHandler,
    private val isTurbo: Boolean = true
) {
    // Timeout lebih pendek untuk mode Turbo agar cepat reconnect jika putus
    private val client = OkHttpClient.Builder()
        .connectTimeout(if (isTurbo) 10L else 30L, TimeUnit.SECONDS)
        .readTimeout(if (isTurbo) 15L else 60L, TimeUnit.SECONDS) 
        .writeTimeout(20L, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var isRunning = false
    private val TAG = "PollingManager"
    
    private var currentInterval = if (isTurbo) 1000L else 5000L 
    private val maxInterval = 30000L

    fun start() {
        if (isRunning) return
        isRunning = true
        poll()
    }

    fun stop() {
        isRunning = false
    }

    private fun poll() {
        if (!isRunning) return

        try {
            val urlBuilder = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("poll")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("auth", authToken)
            
            if (isTurbo) {
                urlBuilder.addQueryParameter("mode", "short")
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .addHeader("Connection", "keep-alive")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!isRunning) return
                    Log.e(TAG, "Polling failed: ${e.message}")
                    currentInterval = (currentInterval * 1.5).toLong().coerceAtMost(maxInterval)
                    scheduleNext(currentInterval)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!isRunning) return
                    response.use {
                        if (!it.isSuccessful) {
                            Log.e(TAG, "Server error: ${it.code}")
                            currentInterval = (currentInterval * 1.5).toLong().coerceAtMost(maxInterval)
                            scheduleNext(currentInterval)
                        } else {
                            // Berhasil, gunakan interval cepat
                            currentInterval = if (isTurbo) 1000L else 5000L 
                            val body = it.body?.string() ?: ""
                            try {
                                if (body.isNotEmpty()) {
                                    val json = JSONObject(body)
                                    val command = json.optString("command", "none")
                                    if (command != "none") {
                                        Log.i(TAG, "Executing: $command")
                                        handler.handle(body) { result -> sendResponse(result) }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "JSON error: ${e.message}")
                            }
                            // Polling berikutnya 1 detik untuk Turbo, agar tetap panas
                            scheduleNext(currentInterval)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "URL error: ${e.message}")
            scheduleNext(10000L)
        }
    }

    private fun sendResponse(jsonResponse: String) {
        try {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("response")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("auth", authToken)
                .build()

            val body = jsonResponse.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Failed to send response: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {}
    }

    private fun scheduleNext(delayMs: Long) {
        if (isRunning) {
            executor.schedule({ poll() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }
}
