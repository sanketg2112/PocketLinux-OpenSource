package com.sg.linuxgo

import android.content.Context

/**
 * Cross-process gate for session foreground services ([SessionKeepAliveService], [X11Service]).
 *
 * When the user terminates a session (notification or in-app Stop), we clear this flag
 * *before* stopping/killing processes so a sticky service restart cannot repost the
 * session notification.
 *
 * Also tracks **user-initiated stop** so death monitors do not treat [Process.destroyForcibly]
 * / pkill SIGKILL (exit 137) as an unexpected OOM [distro_crash] or show a crash-report prompt.
 */
object SessionLifecycleGate {
    private const val PREFS = "pocket_linux_state"
    private const val KEY_FGS_ALLOWED = "session_fgs_allowed"
    private const val KEY_USER_STOP = "session_user_stop_in_progress"

    /** Must be true for keep-alive / X11 FGS to stay in the foreground. */
    fun isAllowed(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FGS_ALLOWED, false)
    }

    /**
     * Enable or disable session FGS. Uses [SharedPreferences.Editor.commit] so the
     * `:x11` process sees the value immediately.
     */
    fun setAllowed(context: Context, allowed: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FGS_ALLOWED, allowed)
            .commit()
    }

    /**
     * True while the user (or notification) is tearing down a session.
     * Death monitors must skip crash telemetry / prompts when this is set.
     */
    fun isUserStopInProgress(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USER_STOP, false)
    }

    /**
     * Mark intentional session stop **before** killing proot/X11 so exit 137 is not
     * mislabeled as Android OOM. Cleared when a new session is allowed / starts.
     */
    fun setUserStopInProgress(context: Context, inProgress: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USER_STOP, inProgress)
            .commit()
    }
}
