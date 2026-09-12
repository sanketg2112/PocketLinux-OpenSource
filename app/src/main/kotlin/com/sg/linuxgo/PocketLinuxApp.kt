package com.sg.linuxgo

import android.app.Application
import android.util.Log

/**
 * Main Android Application class for PocketLinux.
 *
 * Handles process initialization and hooks into system memory callbacks
 * ([onTrimMemory] and [onLowMemory]) to drop caches and protect guest Linux
 * sessions from OS OOM kills (Android 17 limiter / exit 137).
 */
class PocketLinuxApp : Application() {

    companion object {
        private const val TAG = "PocketLinuxApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application created")
        SessionKeepAliveService.ensureChannel(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(TAG, "onTrimMemory received: level=$level")
        MemoryTrimRegistry.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory received")
        MemoryTrimRegistry.onLowMemory()
    }
}
