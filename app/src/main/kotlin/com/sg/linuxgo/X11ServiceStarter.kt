package com.sg.linuxgo

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts [X11Service] in `:x11` without an unmet `startForegroundService` deadline
 * on the main process. See [X11ServiceStartPolicy].
 */
object X11ServiceStarter {
    private const val TAG = "X11ServiceStarter"

    /**
     * @return the Context API used (`startService` or `startForegroundService`)
     * so GUI logs can show which path ran.
     */
    fun start(context: Context, intent: Intent): String {
        val appCtx = context.applicationContext
        SessionKeepAliveService.ensureChannel(appCtx)
        val sdk = Build.VERSION.SDK_INT
        val visible = isActivityWindowVisible(context)
        val running = isX11ProcessRunning(appCtx)
        val useFgs = X11ServiceStartPolicy.useStartForegroundService(
            sdkInt = sdk,
            activityWindowVisible = visible,
            x11ProcessRunning = running,
        )
        val mode = if (useFgs) "startForegroundService" else "startService"
        Log.i(TAG, "start X11Service via $mode (sdk=$sdk visible=$visible proc=$running)")
        try {
            if (useFgs) {
                if (sdk >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } else if (sdk >= Build.VERSION_CODES.O) {
                try {
                    appCtx.startService(intent)
                } catch (e: Exception) {
                    // App may have backgrounded between the visibility check and start
                    // (or OEM treats worker-thread startService as background).
                    Log.w(TAG, "startService failed (${e.message}) — falling back to startForegroundService")
                    appCtx.startForegroundService(intent)
                    return "startForegroundService"
                }
            } else {
                appCtx.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start X11Service via $mode", e)
            throw e
        }
        return mode
    }

    fun isActivityWindowVisible(context: Context): Boolean {
        val act = context as? Activity ?: return false
        val visibility = try {
            act.window?.decorView?.windowVisibility
        } catch (_: Exception) {
            null
        }
        val destroyed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) act.isDestroyed else false
        } catch (_: Exception) {
            false
        }
        val finishing = try {
            act.isFinishing
        } catch (_: Exception) {
            false
        }
        return X11ServiceStartPolicy.isActivityWindowVisible(
            isFinishing = finishing,
            isDestroyed = destroyed,
            windowVisibility = visibility,
        )
    }

    fun isX11ProcessRunning(context: Context): Boolean {
        val name = "${context.packageName}:x11"
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            am.runningAppProcesses?.any { it.processName == name } == true
        } catch (_: Exception) {
            false
        }
    }
}
