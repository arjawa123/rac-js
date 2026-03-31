package com.example.devicecontrol

import fi.iki.elonen.NanoHTTPD
import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import android.hardware.camera2.*
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.PipedInputStream
import java.io.PipedOutputStream
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder

class LocalWebServer(private val context: Context, port: Int) : NanoHTTPD(null, port) {

    private val commandHandler = CommandHandler(context)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d("LocalWebServer", "Request: $method $uri")

        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveAsset("index.html", "text/html")
                uri == "/api/login" && method == Method.POST -> handleLogin(session)
                else -> {
                    if (!isAuthorized(session)) {
                        newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"Unauthorized\"}")
                    } else {
                        when {
                            uri.startsWith("/api/info") -> serveDeviceInfo()
                            uri.startsWith("/api/files") -> serveFileManager(session)
                            uri.startsWith("/api/command") && method == Method.POST -> handleDirectCommand(session)
                            uri == "/stream" -> serveMjpegStream(session.parameters["facing"]?.get(0) ?: "back")
                            uri == "/mic" -> serveMicStream()
                            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"404 Not Found\"}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalWebServer", "Global Serve Error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun serveMicStream(): Response {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        val pipedIn = PipedInputStream(128 * 1024)
        val pipedOut = PipedOutputStream(pipedIn)
        
        var active = true
        
        Thread {
            try {
                val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBufferSize)
                audioRecord.startRecording()
                val buffer = ByteArray(minBufferSize)
                while (active) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        pipedOut.write(buffer, 0, read)
                    } else if (read < 0) {
                        active = false
                    }
                }
                audioRecord.stop()
                audioRecord.release()
            } catch (e: Exception) {
                Log.e("LocalWebServer", "Mic stream error", e)
                active = false
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.start()

        val response = newChunkedResponse(Response.Status.OK, "audio/pcm", pipedIn)
        response.addHeader("Cache-Control", "no-cache, no-store")
        response.addHeader("Connection", "close")
        return response
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
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var backRot = 90
        var frontRot = 270

        try {
            camManager.cameraIdList.forEach { id ->
                val char = camManager.getCameraCharacteristics(id)
                val facing = char.get(CameraCharacteristics.LENS_FACING)
                val rot = char.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                if (facing == CameraCharacteristics.LENS_FACING_BACK) backRot = rot
                else if (facing == CameraCharacteristics.LENS_FACING_FRONT) frontRot = rot
            }
        } catch (e: Exception) {}

        val info = JSONObject().apply {
            put("status", "online")
            put("model", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("back_rot", backRot)
            put("front_rot", frontRot)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", info.toString())
    }

    private fun serveFileManager(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.get(0) ?: "/storage/emulated/0"
        val download = session.parameters["dl"]?.get(0) == "1"
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
                val response = newFixedLengthResponse(Response.Status.OK, resolveMimeType(file.name), FileInputStream(file), file.length())
                if (download) {
                    response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
                }
                return response
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Read error\"}")
            }
        }
    }

    private fun handleLogin(session: IHTTPSession): Response {
        return try {
            val contentLength = session.headers["content-length"]?.toInt() ?: 0
            val buffer = ByteArray(contentLength)
            session.inputStream.read(buffer, 0, contentLength)
            val json = JSONObject(String(buffer))
            val pass = json.optString("password", "")
            
            val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
            val correct = prefs.getString("admin_web_password", "admin123") ?: "admin123"
            
            if (pass == correct) {
                val token = md5(correct)
                val resp = JSONObject().apply { put("token", token) }
                return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
            } else {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"error\":\"Password salah!\"}")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val cookies = session.cookies
        val token = cookies.read("session_token")
        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        val correct = prefs.getString("admin_web_password", "admin123") ?: "admin123"
        return token == md5(correct)
    }

    private fun serveMjpegStream(facing: String): Response {
        val pipedIn = PipedInputStream(1 * 1024 * 1024) // 1MB buffer
        val pipedOut = PipedOutputStream(pipedIn)

        Thread {
            val handlerThread = HandlerThread("mjpeg_stream").apply { start() }
            val camHandler = Handler(handlerThread.looper)
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camManager.cameraIdList.firstOrNull { id ->
                val characteristics = camManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == "front") lensFacing == CameraCharacteristics.LENS_FACING_FRONT
                else lensFacing == CameraCharacteristics.LENS_FACING_BACK
            } ?: camManager.cameraIdList.first()

            val imageReader = ImageReader.newInstance(320, 240, ImageFormat.JPEG, 2)
            var active = true
            var cameraDevice: CameraDevice? = null

            imageReader.setOnImageAvailableListener({ reader ->
                if (!active) return@setOnImageAvailableListener
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    image.close()

                    val boundary = "--RACJSFrame\r\n"
                    val header = "Content-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\n\r\n"
                    pipedOut.write(boundary.toByteArray())
                    pipedOut.write(header.toByteArray())
                    pipedOut.write(bytes)
                    pipedOut.write("\r\n".toByteArray())
                    pipedOut.flush()
                } catch (e: Exception) {
                    active = false
                }
            }, camHandler)

            try {
                camManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                val characteristics = camManager.getCameraCharacteristics(cameraId)
                                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                                val jpegOrientation = sensorOrientation

                                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(imageReader.surface)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                                }.build()
                                session.setRepeatingRequest(req, null, camHandler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { active = false }
                        }, camHandler)
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); active = false }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); active = false }
                }, camHandler)

                // Tunggu sampai stream selesai (koneksi client putus)
                while (active) Thread.sleep(200)
            } catch (e: Exception) {
                Log.e("MjpegStream", "Stream error", e)
            } finally {
                cameraDevice?.close()
                imageReader.close()
                handlerThread.quitSafely()
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.start()

        val response = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=RACJSFrame", pipedIn)
        response.addHeader("Cache-Control", "no-cache, no-store")
        response.addHeader("Connection", "close")
        return response
    }

    private fun md5(s: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
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
