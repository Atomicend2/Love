package com.uk.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import android.util.Base64
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class RemoteService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client by lazy { OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build() }
    private var socket: WebSocket? = null
    private var ringtone: Ringtone? = null
    private var connected = false
    private var reconnectDelayMs = 3000L
    private var reconnectTask: Runnable? = null
    private var ringStopTask: Runnable? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var lastFrameAt = 0L
    private var cameraRunning = false
    private val prefs by lazy { getSharedPreferences("vani_bobo", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Connecting to your server…"))
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (socket == null) connect()
        return START_NOT_STICKY
    }

    private fun connect() {
        val server = prefs.getString("server_url", "").orEmpty().trimEnd('/')
        val id = prefs.getString("device_id", "").orEmpty()
        val token = prefs.getString("device_token", "").orEmpty()
        if (!server.startsWith("https://") || token.isBlank() || id.isBlank()) {
            updateNotification("Open Vani Bobo to configure connection")
            stopSelf()
            return
        }
        val wsBase = server.replaceFirst("https://", "wss://")
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val request = Request.Builder().url("$wsBase/?role=device&id=$encodedId&token=$encodedToken").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                reconnectDelayMs = 3000L
                updateNotification("Connected · $id")
                sendStatus()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val command = JSONObject(text)
                    when (command.optString("action")) {
                        "status" -> sendStatus()
                        "ring" -> startRing(command.optString("requestId"))
                        "stop_ring" -> stopRing(command.optString("requestId"))
                        "open_settings" -> openSettings(command.optString("requestId"))
                        "camera_start" -> startCamera(command.optString("requestId"))
                        "camera_stop" -> stopCamera(command.optString("requestId"))
                    }
                } catch (_: Exception) {
                    // Ignore malformed commands from the authenticated server.
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                socket = null
                updateNotification("Disconnected · reconnecting")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                socket = null
                updateNotification("Connection failed · retrying")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectTask?.let { mainHandler.removeCallbacks(it) }
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
        reconnectTask = Runnable { if (!connected) connect() }
        mainHandler.postDelayed(reconnectTask!!, delay)
    }

    private fun sendStatus() {
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val json = JSONObject()
            .put("type", "status")
            .put("batteryPercent", if (level in 0..100) level else JSONObject.NULL)
            .put("charging", plugged != 0)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("model", Build.MANUFACTURER + " " + Build.MODEL)
        socket?.send(json.toString())
        updateNotification(if (connected) "Connected · battery ${if (level in 0..100) "$level%" else "unknown"}" else "Connecting…")
    }

    private fun startRing(requestId: String) {
        try {
            stopRingSound()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: Settings.System.DEFAULT_RINGTONE_URI
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
            ringStopTask = Runnable { stopRingSound() }
            mainHandler.postDelayed(ringStopTask!!, 20_000L)
            result("ring", true, "Phone is ringing for up to 20 seconds", requestId)
        } catch (e: Exception) {
            result("ring", false, e.message ?: "Could not play ringtone", requestId)
        }
    }

    private fun stopRing(requestId: String) {
        stopRingSound()
        result("stop_ring", true, "Ringing stopped", requestId)
    }

    private fun stopRingSound() {
        ringStopTask?.let { mainHandler.removeCallbacks(it) }
        ringStopTask = null
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
    }

    private fun openSettings(requestId: String) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            result("open_settings", true, "Android Settings opened", requestId)
        } catch (e: Exception) {
            result("open_settings", false, e.message ?: "Could not open Settings", requestId)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startCamera(requestId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            result("camera_start", false, "Camera permission has not been granted on the phone", requestId)
            return
        }
        if (cameraRunning) { result("camera_start", true, "Camera feed is already running", requestId); return }
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
            if (cameraId == null) { result("camera_start", false, "No camera found", requestId); return }
            cameraThread = HandlerThread("VaniBoboCamera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrameAt >= 1100 && cameraRunning) {
                        lastFrameAt = now
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val frame = JSONObject().put("type", "camera_frame").put("jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("at", System.currentTimeMillis())
                        socket?.send(frame.toString())
                    }
                } catch (_: Exception) { } finally { image.close() }
            }, cameraHandler)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface: Surface = imageReader!!.surface
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                cameraSession = session
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    }.build()
                                    cameraRunning = true
                                    session.setRepeatingRequest(request, null, cameraHandler)
                                    updateNotification("Camera feed active · visible notification")
                                    result("camera_start", true, "Camera feed started. Keep the notification visible.", requestId)
                                } catch (e: Exception) { stopCameraInternal(); result("camera_start", false, e.message ?: "Could not start camera", requestId) }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                stopCameraInternal(); result("camera_start", false, "Camera session configuration failed", requestId)
                            }
                        }, cameraHandler)
                    } catch (e: Exception) { stopCameraInternal(); result("camera_start", false, e.message ?: "Camera setup failed", requestId) }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); stopCameraInternal() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); stopCameraInternal(); result("camera_start", false, "Android camera error: $error", requestId) }
            }, cameraHandler)
        } catch (e: Exception) {
            stopCameraInternal()
            result("camera_start", false, e.message ?: "Camera unavailable. Open the app and start the connection while the phone is unlocked.", requestId)
        }
    }

    private fun stopCamera(requestId: String) {
        stopCameraInternal()
        updateNotification(if (connected) "Connected · camera off" else "Disconnected · reconnecting")
        result("camera_stop", true, "Camera feed stopped", requestId)
    }

    private fun stopCameraInternal() {
        cameraRunning = false
        try { cameraSession?.stopRepeating() } catch (_: Exception) { }
        try { cameraSession?.close() } catch (_: Exception) { }
        cameraSession = null
        try { cameraDevice?.close() } catch (_: Exception) { }
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        try { cameraThread?.quitSafely() } catch (_: Exception) { }
        cameraThread = null
        cameraHandler = null
    }

    private fun result(action: String, ok: Boolean, message: String, requestId: String) {
        socket?.send(JSONObject().put("type", "result").put("action", action).put("ok", ok)
            .put("message", message).put("requestId", requestId).toString())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Vani Bobo connection", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows when your phone is connected to your remote dashboard."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Vani Bobo")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        reconnectTask?.let { mainHandler.removeCallbacks(it) }
        stopRingSound()
        stopCameraInternal()
        socket?.close(1000, "Disconnected by user")
        socket = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "vani_bobo_connection"
        private const val NOTIFICATION_ID = 42
    }
}
