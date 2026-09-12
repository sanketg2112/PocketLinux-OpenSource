package com.sg.linuxgo

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.preference.PreferenceManager

/** Screen active & Wake lock management */

/**
 * Applies or clears [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] on the activity window
 * based on the "keepScreenOn" shared preference (default true).
 */
internal fun Activity.applyKeepScreenOnPreference() {
    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
    val keepScreenOn = sharedPrefs.getBoolean("keepScreenOn", true)
    runOnUiThread {
        try {
            if (keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (e: Exception) {
            Log.w("ScreenKeepOn", "Failed to update FLAG_KEEP_SCREEN_ON: ${e.message}")
        }
    }
}

internal fun MainActivity.acquireWakeLock() {
    if (wakeLock == null) {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PocketLinux:TerminalWakeLock")
        wakeLock?.acquire()
        Log.d("WakeLock", "WakeLock acquired")
    }
}

internal fun MainActivity.releaseWakeLock() {
    if (wakeLock != null) {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("WakeLock", "Error releasing wake lock", e)
        }
        wakeLock = null
        Log.d("WakeLock", "WakeLock released")
    }
}
