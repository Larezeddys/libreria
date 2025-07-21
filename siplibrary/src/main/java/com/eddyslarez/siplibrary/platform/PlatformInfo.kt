package com.eddyslarez.siplibrary.platform

import android.os.Build

/**
 * Platform information provider for Android
 * 
 * @author Eddys Larez
 */
class PlatformInfo {
    
    fun getPlatform(): String = "Android ${Build.VERSION.RELEASE}"
    
    fun getDeviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    
    fun getOSVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}