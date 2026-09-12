package com.sg.linuxgo

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Shared Picture-in-Picture helpers for MainActivity (X11) and WaylandActivity.
 * Never throws — enter PiP failures are logged and ignored.
 */

fun hasPipPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
        ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(),
            context.packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(),
            context.packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }
}

/**
 * Enter PiP only when the device/user/activity allow it.
 * Returns true if the system accepted the request.
 */
fun Activity.safeEnterPictureInPicture(logTag: String = "PiP"): Boolean {
    if (isFinishing) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed) return false
    // Config change mid-leave is a common IllegalStateException trigger.
    if (isChangingConfigurations) return false

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
        Log.d(logTag, "PiP not supported on this device")
        return false
    }

    if (!hasPipPermission(this)) {
        Log.d(logTag, "PiP permission not allowed — skipping")
        return false
    }

    // Already in PiP — calling enter again can throw IllegalStateException.
    if (isInPictureInPictureMode) {
        return true
    }

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        } else {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
            true
        }
    } catch (e: IllegalStateException) {
        // WaylandActivity.onUserLeaveHint → enterPictureInPictureMode can throw.
        Log.w(logTag, "enterPictureInPictureMode IllegalStateException: ${e.message}")
        false
    } catch (e: Exception) {
        Log.w(logTag, "enterPictureInPictureMode failed: ${e.message}")
        false
    }
}
