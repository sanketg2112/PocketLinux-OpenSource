package com.sg.linuxgo

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash J: Compose / androidx.core calls AccessibilityEvent.setAccessibilityDataSensitive
 * on API 34+ images whose framework.jar does not have that method.
 *
 * Nested main Looper.loop so that one NoSuchMethodError does not kill the process.
 * AccessibilityEventCompat$Api34Impl is also instrumented at build time when AGP
 * can see the library class.
 */
object A11yEventCompatGuard {
    private const val TAG = "A11yEventCompatGuard"
    private val installed = AtomicBoolean(false)

    fun isMissingAccessibilityDataSensitive(throwable: Throwable): Boolean {
        var cur: Throwable? = throwable
        while (cur != null) {
            if (cur is NoSuchMethodError) {
                val msg = cur.message.orEmpty()
                if (msg.contains("setAccessibilityDataSensitive") ||
                    msg.contains("isAccessibilityDataSensitive")
                ) {
                    return true
                }
            }
            cur = cur.cause
        }
        return false
    }

    fun installOnMainLooper() {
        if (!installed.compareAndSet(false, true)) return
        val looper = Looper.getMainLooper() ?: return
        Handler(looper).post {
            while (true) {
                try {
                    Looper.loop()
                    return@post
                } catch (t: Throwable) {
                    if (!isMissingAccessibilityDataSensitive(t)) throw t
                    Log.w(TAG, "Ignored missing AccessibilityEvent.setAccessibilityDataSensitive")
                }
            }
        }
    }
}
