package com.example.devicecontrol

import fi.iki.elonen.NanoHTTPD
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class LocalWebServer(private val context: Context, port: Int) : NanoHTTPD("::", port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d("LocalWebServer", "Request: $method $uri")

        return when {
            uri == "/" || uri == "/index.html" -> serveAsset("index.html", "text/html")
            uri.startsWith("/api/info") -> serveDeviceInfo()
            uri.startsWith("/api/files") -> serveFileManager(session)
            uri.startsWith("/api/camera/stream") -> serveCameraStream()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
        }
    }

    private fun serveCameraStream(): Response {
        // MJPEG Stream Implementation
        // This is a simplified version. A real one would pipe camera frames.
        return newFixedLengthResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--frame", "Stream implementation required")
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
        val path = session.parameters["path"]?.get(0) ?: "/"
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
            newFixedLengthResponse(Response.Status.OK, "application/json", filesList.toString())
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
        return when {
            fileName.endsWith(".html") -> "text/html"
            fileName.endsWith(".js") -> "application/javascript"
            fileName.endsWith(".css") -> "text/css"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".mp4") -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
