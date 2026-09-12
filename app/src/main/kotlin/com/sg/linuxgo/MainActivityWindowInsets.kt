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

/** IME resize */

internal fun MainActivity.possiblyResizeChildOfContent() {
    if (!hasWindowFocus()) return
    
    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
    val isFullscreen = sharedPrefs.getBoolean("fullscreen", true)
    val reseed = sharedPrefs.getBoolean("Reseed", false)
    
    val isGuiVisible = isLateInit_guiContainer() && guiContainer.visibility == View.VISIBLE
    val isTerminalVisible = isLateInit_terminalView() && terminalView.visibility == View.VISIBLE

    // GUI and Terminal screens are handled natively by Compose window insets
    // (imePadding / windowInsetsPadding), so skip the View-level resize to avoid double-resizing.
    val shouldResize = false
    if (!shouldResize) {
        // Reset to default layout height if not matching the conditions
        // Real window content only (never a registry dummy — Crash A / insets).
        val content = findViewById<FrameLayout>(android.R.id.content)
        if (content != null && content.childCount > 0) {
            val child = content.getChildAt(0)
            val lp = child.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && lp.height != FrameLayout.LayoutParams.MATCH_PARENT) {
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                child.requestLayout()
            }
        }
        usableHeightPrevious = 0
        return
    }

    val content = findViewById<FrameLayout>(android.R.id.content) ?: return
    if (content.childCount == 0) return
    val child = content.getChildAt(0)
    val lp = child.layoutParams as? FrameLayout.LayoutParams ?: return

    val usableHeightSansKeyboard = child.rootView.height

    val insets = ViewCompat.getRootWindowInsets(child)
    val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    val r = android.graphics.Rect()
    child.getWindowVisibleDisplayFrame(r)
    val fitsSystemWindows = !isFullscreen && !isTerminalVisible
    val usableHeightNow = if (fitsSystemWindows) r.bottom - r.top else r.bottom
    val heightFromFrame = usableHeightSansKeyboard - usableHeightNow

    val heightDifference = if (imeBottom > 0) imeBottom else heightFromFrame

    val newHeight = if (heightDifference > (usableHeightSansKeyboard / 4)) {
        usableHeightSansKeyboard - heightDifference
    } else {
        FrameLayout.LayoutParams.MATCH_PARENT
    }
    if (lp.height != newHeight) {
        lp.height = newHeight
        child.requestLayout()
    }
    usableHeightPrevious = usableHeightNow
}
