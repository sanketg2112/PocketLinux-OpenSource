package com.sg.linuxgo.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Controls the home-tab feedback strip that replaces package tips after the
 * first user-stopped GUI/terminal session.
 *
 * Visibility: tips dismissed AND first session stopped AND strip not dismissed.
 * Dismissed permanently after submit or the strip close (X) control.
 */
object HomeFeedbackStripPrefs {
    const val PREF_FIRST_SESSION_STOPPED = "home_feedback_first_session_stopped"
    const val PREF_STRIP_DISMISSED = "home_feedback_strip_dismissed"

    fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun hasStoppedFirstSession(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_FIRST_SESSION_STOPPED, false)

    fun isStripDismissed(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_STRIP_DISMISSED, false)

    fun shouldShow(
        tipsHintsDismissed: Boolean,
        prefs: SharedPreferences
    ): Boolean {
        if (!tipsHintsDismissed) return false
        if (!hasStoppedFirstSession(prefs)) return false
        if (isStripDismissed(prefs)) return false
        return true
    }

    fun markFirstSessionStopped(context: Context) {
        prefs(context).edit().putBoolean(PREF_FIRST_SESSION_STOPPED, true).apply()
    }

    fun markStripDismissed(context: Context) {
        prefs(context).edit().putBoolean(PREF_STRIP_DISMISSED, true).apply()
    }

    fun markStripDismissed(prefs: SharedPreferences) {
        prefs.edit().putBoolean(PREF_STRIP_DISMISSED, true).apply()
    }
}
