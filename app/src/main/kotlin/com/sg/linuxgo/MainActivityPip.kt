package com.sg.linuxgo

import android.content.res.Configuration
import androidx.preference.PreferenceManager
import android.view.View

/** PIP (X11 / MainActivity) — shared checks live in [PipHelpers]. */

internal fun MainActivity.handleOnUserLeaveHint() {
    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
    val pipEnabled = sharedPrefs.getBoolean("PIP", true)
    if (isLateInit_guiContainer() && guiContainer.visibility == View.VISIBLE && pipEnabled) {
        safeEnterPictureInPicture(logTag = "MainActivity")
    }
}

internal fun MainActivity.handleOnPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration
) {
    callSuperOnPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    if (isInPictureInPictureMode) {
        supportActionBar?.hide()
    } else {
        setFullscreen()
    }
}
