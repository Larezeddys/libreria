package com.eddyslarez.siplibrary.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.eddyslarez.siplibrary.data.models.AppLifecycleEvent
import com.eddyslarez.siplibrary.data.models.AppLifecycleListener

/**
 * Platform registration for Android lifecycle events
 * 
 * @author Eddys Larez
 */
class PlatformRegistration() {
    
    fun setupNotificationObservers(listener: AppLifecycleListener) {
        // FinishedLaunching
        listener.onEvent(AppLifecycleEvent.FinishedLaunching)

        // Foreground / Background
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = listener.onEvent(AppLifecycleEvent.EnterForeground)
            override fun onStop(owner: LifecycleOwner) = listener.onEvent(AppLifecycleEvent.EnterBackground)
        })
    }
}