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

/** Theme */

internal fun MainActivity.updateStatusBarColorAndIcons() {
    runOnUiThread {
        val sessionBg = activeSessionCanvasBgArgb()
        ThemeUiSupport.updateStatusBarColorAndIcons(
            activity = this,
            mainRoot = if (isLateInit_mainRoot()) mainRoot else null,
            isTerminalVisible = isLateInit_terminalView() && terminalView.visibility == View.VISIBLE,
            activeTerminalTheme = getActiveTerminalTheme(),
            overrideTerminalBgArgb = sessionBg
        )
    }
}

internal fun MainActivity.applyTerminalThemeToLegacyViews() {
    runOnUiThread {
        if (!isLateInit_tvTerminalOutput() || !isLateInit_terminalScroll()) return@runOnUiThread
        val session = terminalSessions.getOrNull(activeSessionIndex)
        val scheme = session?.emulator?.colorScheme
        val matchGui = session?.appearance?.matchedGui == true ||
            GuestTerminalAppearance.isMatchGui(
                PreferenceManager.getDefaultSharedPreferences(this)
            )
        ThemeUiSupport.applyTerminalThemeToLegacyViews(
            activity = this,
            tvTerminalOutput = tvTerminalOutput,
            terminalScroll = terminalScroll,
            terminalCursor = if (isLateInit_terminalCursor()) terminalCursor else null,
            termThemeId = getActiveTerminalTheme(),
            onRecalculateSize = { recalculateActiveSessionSize() },
            overrideBgArgb = if (matchGui) scheme?.defaultBg else null,
            overrideTextArgb = if (matchGui) scheme?.defaultFg else null,
            overrideCursorArgb = if (matchGui) scheme?.cursorColor else null,
            guestFontPath = session?.appearance?.fontFile?.absolutePath
        )
    }
}

/** Guest / Match GUI canvas background for status bar when a session is active. */
internal fun MainActivity.activeSessionCanvasBgArgb(): Int? {
    val session = terminalSessions.getOrNull(activeSessionIndex) ?: return null
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    if (!GuestTerminalAppearance.isMatchGui(prefs) && session.appearance?.matchedGui != true) {
        return null
    }
    return session.emulator.colorScheme.defaultBg
}

internal fun MainActivity.getActiveTerminalTheme(): String {
    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
    val globalTheme = sharedPrefs.getString("terminal_theme", "default") ?: "default"
    if (activeSessionIndex < 0 || activeSessionIndex >= terminalSessions.size) {
        return globalTheme
    }
    val activeSession = terminalSessions[activeSessionIndex]
    return sessionThemes[activeSession.id] ?: globalTheme
}
