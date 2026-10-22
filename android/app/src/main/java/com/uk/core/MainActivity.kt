package com.uk.core

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat

class MainActivity : Activity() {
    private lateinit var serverInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusText: TextView
    private val prefs by lazy { getSharedPreferences("vani_bobo", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(14, 15, 19)
        window.navigationBarColor = android.graphics.Color.rgb(14, 15, 19)
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = 0

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(24))
            setBackgroundColor(android.graphics.Color.rgb(14, 15, 19))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)

        content.addView(text("Vani Bobo", 30f, 0xFFF3F4F6.toInt(), true))
        content.addView(text("Personal remote access", 14f, 0xFFB5A2FF.toInt(), false).apply {
            setPadding(0, dp(4), 0, dp(18))
        })
        content.addView(text("Pair this app with your own Vani Bobo server. The foreground notification stays visible while connected.", 14f, 0xFFB1B4BF.toInt(), false))
        content.addView(text("Server endpoint (preconfigured)", 13f, 0xFFE5E6EB.toInt(), true).apply { setPadding(0, dp(22), 0, dp(6)) })
        serverInput = field("https://your-server.example", "url").also { content.addView(it) }
        content.addView(text("Device ID", 13f, 0xFFE5E6EB.toInt(), true).apply { setPadding(0, dp(14), 0, dp(6)) })
        deviceIdInput = field("my-phone", "text").also { content.addView(it) }
        content.addView(text("Device access key", 13f, 0xFFE5E6EB.toInt(), true).apply { setPadding(0, dp(14), 0, dp(6)) })
        tokenInput = field("Paste DEVICE_TOKEN from server settings", "text").also { content.addView(it) }
        statusText = text("Status: not connected", 13f, 0xFFB1B4BF.toInt(), false).apply { setPadding(0, dp(18), 0, dp(14)) }
        content.addView(statusText)

        val connect = Button(this).apply {
            text = "Save & connect"
            setOnClickListener { connectDevice() }
        }
        content.addView(connect, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        content.addView(Button(this).apply {
            text = "Disconnect"
            setOnClickListener {
                stopService(Intent(this@MainActivity, RemoteService::class.java))
                statusText.text = "Status: disconnected"
                Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        content.addView(text("Remote camera is opt-in: grant camera permission, keep the visible Vani Bobo foreground notification active, then start the feed from your authenticated dashboard. The feed can be stopped at any time. No microphone audio or WhatsApp data is collected.", 12f, 0xFF888D99.toInt(), false).apply {
            setPadding(0, dp(20), 0, dp(8))
        })

        serverInput.setText(prefs.getString("server_url", "https://love-v1xj.onrender.com"))
        serverInput.isEnabled = false
        deviceIdInput.setText(prefs.getString("device_id", "my-phone"))
        tokenInput.setText(prefs.getString("device_token", ""))
    }

    private fun connectDevice() {
        val server = serverInput.text.toString().trim().trimEnd('/')
        val id = deviceIdInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()
        if (!server.startsWith("https://")) {
            serverInput.error = "Use your HTTPS server URL"
            return
        }
        if (!id.matches(Regex("[a-zA-Z0-9_-]{1,48}"))) {
            deviceIdInput.error = "Use 1–48 letters, numbers, _ or -"
            return
        }
        if (token.length < 12) {
            tokenInput.error = "Enter the device key configured on your server"
            return
        }
        prefs.edit().putString("server_url", server).putString("device_id", id).putString("device_token", token).apply()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 102)
            Toast.makeText(this, "Grant camera permission, then tap Save & connect again.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        val intent = Intent(this, RemoteService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        statusText.text = "Status: connecting to $server"
        Toast.makeText(this, "Connection started. Keep the notification enabled.", Toast.LENGTH_LONG).show()
    }

    private fun field(hintText: String, type: String) = EditText(this).apply {
        hint = hintText
        textSize = 14f
        setSingleLine(true)
        setTextColor(0xFFF3F4F6.toInt())
        setHintTextColor(0xFF777D8A.toInt())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(0xFF20222A.toInt())
        if (type == "url") inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        else inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
