package com.sg.linuxgo

/**
 * Determines whether to start [SessionKeepAliveService] via `startService` or `startForegroundService`.
 *
 * Calling `startForegroundService` starts a strict OS deadline (5s / 10s) that kills the calling
 * process with RemoteServiceException if the service does not call `startForeground` in time
 * (Crash D on OPPO, OnePlus, HONOR devices).
 *
 * When the activity is visible, `startService` can be used safely without the watchdog timer,
 * while `onCreate` still promotes the service to foreground so the session survives backgrounding.
 */
object SessionKeepAliveStartPolicy {

    fun useStartForegroundService(
        sdkInt: Int,
        activityWindowVisible: Boolean,
    ): Boolean {
        if (sdkInt < 26) return false
        if (activityWindowVisible) return false
        return true
    }
}
