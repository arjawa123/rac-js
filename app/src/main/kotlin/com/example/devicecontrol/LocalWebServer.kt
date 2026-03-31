package com.example.devicecontrol

import fi.iki.elonen.NanoHTTPD
import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class LocalWebServer(private val context: Context, port: Int) : NanoHTTPD("::", port) {

    private val commandHandler = CommandHandler(context)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d("LocalWebServer", "Request: $method $uri")

        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveAsset("index.html", "text/html")
                uri.startsWith("/api/info") -> serveDeviceInfo()
                uri.startsWith("/api/files") -> serveFileManager(session)
                uri.startsWith("/api/command") && method == Method.POST -> handleDirectCommand(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"404 Not Found\"}")
            }
        } catch (e: Exception) {
            Log.e("LocalWebServer", "Global Serve Error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleDirectCommand(session: IHTTPSession): Response {
        return try {
            // Membaca body secara manual untuk menghindari konflik parseBody NanoHTTPD
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            val jsonStr = if (contentLength > 0) {
                val buffer = ByteArray(contentLength)
                session.inputStream.read(buffer, 0, contentLength)
                val rawBody = String(buffer)
                
                if (rawBody.startsWith("postData=")) {
                    java.net.URLDecoder.decode(rawBody.substring(9), "UTF-8")
                } else {
                    rawBody
                }
            } else {
                "{}"
            }

            Log.d("LocalWebServer", "Executing: $jsonStr")
            val result = commandHandler.handle(jsonStr) { /* Async callback */ }
            
            if (result != null) {
                newFixedLengthResponse(Response.Status.OK, "application/json", result)
            } else {
                val asyncResp = JSONObject().apply {
                    put("type", "async_started")
                    put("data", "Perintah latar belakang dimulai.")
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", asyncResp.toString())
            }
        } catch (e: Exception) {
            Log.e("LocalWebServer", "Command Error", e)
            val errJson = JSONObject().apply {
                put("type", "error")
                put("data", e.message ?: "Unknown Error")
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", errJson.toString())
        }
    }

    private fun serveAsset(fileName: String, mimeType: String): Response {
        return try {
            val inputStream = context.assets.open("web/$fileName")
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found")
        }
    }

    private fun serveDeviceInfo(): Response {
        val info = JSONObject().apply {
            put("status", "online")
            put("model", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.RELEASE)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", info.toString())
    }

    private fun serveFileManager(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.get(0) ?: "/storage/emulated/0"
        val file = File(path)

        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"File not found\"}")
        }

        return if (file.isDirectory) {
            val filesList = file.listFiles()?.toList() ?: emptyList()
            val sortedFiles = filesList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            
            val jsonArray = JSONArray()
            sortedFiles.forEach {
                jsonArray.put(JSONObject().apply {
                    put("name", it.name)
                    put("is_dir", it.isDirectory)
                    put("path", it.absolutePath)
                    put("size", it.length())
                })
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
        } else {
            try {
                newFixedLengthResponse(Response.Status.OK, resolveMimeType(file.name), FileInputStream(file), file.length())
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Read error\"}")
            }
        }
    }

    private fun resolveMimeType(fileName: String): String {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".html") -> "text/html"
            name.endsWith(".js") -> "application/javascript"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
