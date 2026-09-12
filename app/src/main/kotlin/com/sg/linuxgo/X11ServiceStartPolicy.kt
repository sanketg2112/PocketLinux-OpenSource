package com.sg.linuxgo

/**
 * How to start [X11Service] without killing the main process.
 *
 * [X11Service] runs in isolated process `:x11`. `Context.startForegroundService`
 * starts a 5s (API 26–30) / 10s (API 31+) timer against the **calling** process.
 * If zygote is slow to spawn `:x11` (low-RAM phones while proot/Debian is
 * booting), Android throws `RemoteServiceException` and kills the UI process
 * before `Service.startForeground` can run.
 *
 * When the GUI activity window is visible, `startService` + `startForeground`
 * in `onCreate` still promotes to FGS (desktop stays alive in the background)
 * without that timer. `startForegroundService` is only required when the app
 * is backgrounded and `:x11` is not already running.
 */
object X11ServiceStartPolicy {
    /** [android.view.View.VISIBLE] — kept numeric so JVM tests need no Android stub. */
    const val WINDOW_VISIBLE = 0

    fun isActivityWindowVisible(
        isFinishing: Boolean,
        isDestroyed: Boolean,
        windowVisibility: Int?,
    ): Boolean {
        if (isFinishing || isDestroyed) return false
        // Null = could not read the decor (still a live activity) — prefer startService.
        if (windowVisibility == null) return true
        return windowVisibility == WINDOW_VISIBLE
    }

    /**
     * True → caller must use `startForegroundService`.
     * False → `startService` (no FGS deadline).
     */
    fun useStartForegroundService(
        sdkInt: Int,
        activityWindowVisible: Boolean,
        x11ProcessRunning: Boolean,
    ): Boolean {
        if (sdkInt < 26) return false
        if (x11ProcessRunning) return false
        if (activityWindowVisible) return false
        return true
    }
}
