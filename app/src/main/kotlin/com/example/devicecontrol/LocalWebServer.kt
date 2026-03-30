package com.example.devicecontrol

import fi.iki.elonen.NanoHTTPD
import android.content.Context
import android.util.Log
import org.json.JSONObject
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
            val jsonStr = map["postData"] ?: "{}"
            
            // Execute command and wait for immediate response (synchronous part)
            val result = commandHandler.handle(jsonStr) { /* Ignore async callback for direct web requests */ }
            
            if (result != null) {
                newFixedLengthResponse(Response.Status.OK, "application/json", result)
            } else {
                // Command is async (like recording), return a placeholder
                val asyncResp = JSONObject().apply {
                    put("type", "async_started")
                    put("data", "Command started in background. Result will be in Telegram/Logs.")
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", asyncResp.toString())
            }
        } catch (e: Exception) {
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
            put("ipv6", "Enabled")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", info.toString())
    }

    private fun serveFileManager(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.get(0) ?: "/storage/emulated/0"
        val file = File(path)

        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }

        return if (file.isDirectory) {
            val filesList = file.listFiles()?.map {
                JSONObject().apply {
                    put("name", it.name)
                    put("is_dir", it.isDirectory)
                    put("path", it.absolutePath)
                    put("size", it.length())
                }
            } ?: emptyList()
            newFixedLengthResponse(Response.Status.OK, "application/json", filesList.joinToString(",", "[", "]"))
        } else {
            try {
                val inputStream: InputStream = FileInputStream(file)
                newFixedLengthResponse(Response.Status.OK, resolveMimeType(file.name), inputStream, file.length())
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error reading file")
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
