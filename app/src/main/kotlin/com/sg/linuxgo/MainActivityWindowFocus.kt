package com.sg.linuxgo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.sg.linuxgo.x11.LorieView
import java.io.File

/** Window focus */

internal fun MainActivity.handleOnWindowFocusChanged(hasFocus: Boolean) {
    callSuperOnWindowFocusChanged(hasFocus)
    if (!hasFocus || !isLateInit_guiContainer()) {
        updatePointerCapture()
        return
    }
    val guiVisible = guiContainer.visibility == View.VISIBLE
    val terminalVisible = isLateInit_terminalView() && terminalView.visibility == View.VISIBLE
    if (guiVisible) {
        // Immersive mode is GUI-only.
        setFullscreen()
        // Immersive/fullscreen focus changes can dismiss PopupWindows — re-show X11 pill.
        if (!isDeXMode()) {
            window.decorView.post {
                if (!isFinishing && !isDestroyed && guiContainer.visibility == View.VISIBLE) {
                    showSoftKeyboardAndKeybar()
                }
            }
        }
    } else if (terminalVisible) {
        // Terminal must keep the status bar visible and accept soft keyboard input.
        resetFullscreen()
        ensureTerminalKeyboardReady()
    }
    updatePointerCapture()
}
