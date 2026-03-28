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
    private val handler: CommandHandler
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var isRunning = false
    private val TAG = "PollingManager"

    fun start() {
        isRunning = true
        poll()
    }

    fun stop() {
        isRunning = false
    }

    private fun poll() {
        if (!isRunning) return

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("poll")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("auth", authToken)
            .build()

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Polling failed: ${e.message}")
                scheduleNext()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.e(TAG, "Server error: ${it.code}")
                    } else {
                        val body = it.body?.string() ?: ""
                        try {
                            val json = JSONObject(body)
                            val command = json.optString("command", "none")
                            if (command != "none") {
                                Log.i(TAG, "Received command: $command")
                                handler.handle(body) { result ->
                                    sendResponse(result)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "JSON error: ${e.message}")
                        }
                    }
                    scheduleNext()
                }
            }
        })
    }

    private fun sendResponse(jsonResponse: String) {
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
    }

    private fun scheduleNext() {
        if (isRunning) {
            executor.schedule({ poll() }, 5, TimeUnit.SECONDS) // Poll every 5 seconds
        }
    }
}
