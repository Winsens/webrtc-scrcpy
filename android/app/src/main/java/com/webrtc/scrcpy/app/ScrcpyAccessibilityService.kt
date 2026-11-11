package com.webrtc.scrcpy.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.webrtc.scrcpy.core.input.AccessibilityInputInjector

class ScrcpyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityInputInjector.serviceInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        AccessibilityInputInjector.serviceInstance = null
        super.onDestroy()
    }
}

