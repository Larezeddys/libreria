package com.eddyslarez.siplibrary.platform

/**
 * Window management for Android
 * 
 * @author Eddys Larez
 */
class WindowManager {
    
    fun registerComposeWindow(window: Any) {
        // No-op for Android
    }

    fun bringToFront() {
        // Could implement bringing app to foreground if needed
    }

    fun showNotification(
        title: String,
        message: String,
        iconPath: String? = null
    ) {
        // Could implement local notifications
    }

    fun incomingCall(callerName: String, callerNumber: String) {
        // Could implement incoming call UI
    }
}