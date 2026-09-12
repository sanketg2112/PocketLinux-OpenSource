package com.sg.linuxgo

import android.content.ComponentCallbacks2
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central registry for components that can release non-critical caches
 * under system memory pressure (Android 17 limiter).
 */
object MemoryTrimRegistry {
    private const val TAG = "MemoryTrimRegistry"

    fun interface TrimListener {
        fun onTrim(level: Int)
    }

    private val listeners = CopyOnWriteArrayList<TrimListener>()

    fun register(listener: TrimListener) {
        listeners.add(listener)
    }

    fun unregister(listener: TrimListener) {
        listeners.remove(listener)
    }

    fun onTrimMemory(level: Int) {
        Log.i(TAG, "Dispatching trimMemory: level=$level to ${listeners.size} listeners")
        for (listener in listeners) {
            try {
                listener.onTrim(level)
            } catch (t: Throwable) {
                Log.w(TAG, "Listener failed during trimMemory", t)
            }
        }
    }

    fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    internal fun clearListenersForTest() {
        listeners.clear()
    }
}
