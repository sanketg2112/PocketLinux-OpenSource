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

/** Settings refresh */

internal fun MainActivity.performOnGlobalSettingsSaved() {
    // Reload Termux X11 preferences on the active LorieView and re-measure
    // display scale (framebuffer size). Scale is display-only — no guest restart.
    if (isLateInit_lorieView()) {
        try {
            lorieView.reloadPreferences(com.sg.linuxgo.x11.Prefs(this))
            // triggerCallback → getDimensionsFromSettings → sendWindowChange
            lorieView.triggerCallback()
        } catch (e: Exception) {
            Log.w("MainActivity", "Lorie prefs reload after settings: ${e.message}")
        }
        // Re-apply fullscreen/window settings immediately in GUI mode
        if (isLateInit_guiContainer() && guiContainer.visibility == View.VISIBLE) {
            setFullscreen()
        }
    }
    updatePointerCapture()
    applyKeepScreenOnPreference()

    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
    val showIMEWhileExternalConnected = sharedPrefs.getBoolean("showIMEWhileExternalConnected", true)
    if (isExternalKeyboardConnected() && !showIMEWhileExternalConnected) {
        val root = if (isLateInit_mainRoot()) mainRoot else viewRegistry[R.id.mainRoot]
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        root?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        if (isLateInit_guiKeyBar()) {
            guiKeyBar.visibility = View.GONE
        }
        if (isLateInit_specialKeysScroll()) {
            specialKeysScroll.visibility = View.GONE
        }
    } else {
        val showAdditionalKbd = sharedPrefs.getBoolean("showAdditionalKbd", true)
        val root = if (isLateInit_mainRoot()) mainRoot else viewRegistry[R.id.mainRoot]
        val insets = root?.let { ViewCompat.getRootWindowInsets(it) }
        val isImeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true

        if (isLateInit_guiKeyBar() && isLateInit_guiContainer()) {
            if (!isImeVisible || guiContainer.visibility != View.VISIBLE) {
                guiKeyBarBottomPaddingDp = 0
                guiKeyBar.visibility = View.GONE
                if (isLateInit_btnGuiHome()) btnGuiHome.visibility = View.GONE
            } else {
                if (isLateInit_btnGuiHome()) btnGuiHome.visibility = View.GONE

                if (showAdditionalKbd) {
                    val reseed = sharedPrefs.getBoolean("Reseed", false)
                    val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())
                    guiKeyBarBottomPaddingDp = if (reseed || ime == null) 0 else pxToDp(ime.bottom)
                    guiKeyBar.visibility = View.VISIBLE
                    guiKeyBar.bringToFront()
                } else {
                    guiKeyBar.visibility = View.GONE
                }
            }
        }
        if (isLateInit_specialKeysScroll() && isLateInit_terminalView()) {
            if (!showAdditionalKbd || terminalView.visibility != View.VISIBLE) {
                specialKeysScroll.visibility = View.GONE
            } else {
                specialKeysScroll.visibility = View.VISIBLE
            }
        }
    }
}

internal fun MainActivity.performRefreshContainers() {
    containerAdapter.updateContainers(containerManager.getContainers())
}

