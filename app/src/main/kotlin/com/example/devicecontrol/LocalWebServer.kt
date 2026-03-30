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

        return when {
            uri == "/" || uri == "/index.html" -> serveAsset("index.html", "text/html")
            uri.startsWith("/api/info") -> serveDeviceInfo()
            uri.startsWith("/api/files") -> serveFileManager(session)
            uri.startsWith("/api/command") && method == Method.POST -> handleDirectCommand(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
        }
    }

    private fun handleDirectCommand(session: IHTTPSession): Response {
        return try {
            val map = mutableMapOf<String, String>()
            session.parseBody(map)
            
            // NanoHTTPD parsing body results in parameters being populated or files if it's multipart
            // We'll prefer looking at session.parameters["postData"] or reading raw body
            var jsonStr = session.parameters["postData"]?.get(0)
            
            if (jsonStr == null) {
                // Fallback: Read raw body if not form-encoded
                val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength > 0) {
                    val buffer = ByteArray(contentLength)
                    session.inputStream.read(buffer, 0, contentLength)
                    jsonStr = String(buffer)
                }
            }

            if (jsonStr == null || jsonStr.isEmpty()) {
                jsonStr = "{}"
            }

            Log.d("LocalWebServer", "Executing command: $jsonStr")
            val result = commandHandler.handle(jsonStr) { /* Async ignored */ }
            
            if (result != null) {
                newFixedLengthResponse(Response.Status.OK, "application/json", result)
            } else {
                val asyncResp = JSONObject().apply {
                    put("type", "async_started")
                    put("data", "Command started in background.")
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", asyncResp.toString())
            }
        } catch (e: Exception) {
            Log.e("LocalWebServer", "Error processing command", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun serveAsset(fileName: String, mimeType: String): Response {
        return try {
            val inputStream = context.assets.open("web/$fileName")
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Asset not found: $fileName")
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

        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

        return if (file.isDirectory) {
            val files = file.listFiles()?.toList() ?: emptyList()
            // URUTAN: Folder dulu (A-Z), baru File (A-Z)
            val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            
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
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Read error")
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
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
