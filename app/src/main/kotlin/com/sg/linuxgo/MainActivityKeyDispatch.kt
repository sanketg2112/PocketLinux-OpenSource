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

/** Key dispatch */

internal fun MainActivity.handleDispatchKeyEvent(event: KeyEvent): Boolean {
    val isGuiVisible = isLateInit_guiContainer() && guiContainer.visibility == View.VISIBLE
    val isTerminalVisible = isLateInit_terminalView() && terminalView.visibility == View.VISIBLE
    
    if (isGuiVisible || isTerminalVisible) {
        val keyCode = event.keyCode
        
        // Double ESC press detection to Go Home / Exit Fullscreen
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastEscPressTime < 500) {
                btnGuiHome.visibility = View.GONE
                resetFullscreen()
                tabLayout.getTabAt(0)?.select()
                if (isTerminalVisible) {
                    // Keep terminal sessions resumable (same as system Back / Home chip).
                    leaveTerminalToHome()
                } else {
                    switchToHomeTab()
                }
                lastEscPressTime = 0L
                return true
            }
            lastEscPressTime = currentTime
        }
        
        // If in GUI mode and lorieView is visible, route key events directly to lorieView
        if (isGuiVisible && isLateInit_lorieView() && lorieView.visibility == View.VISIBLE) {
            val currentFocus = currentFocus
            // Do not intercept if focus is on a text view outside lorieView
            if (currentFocus != null && currentFocus !is LorieView) {
                return callSuperDispatchKeyEvent(event)
            }
            
            // Allow volume and back keys to propagate normally
            if (keyCode == KeyEvent.KEYCODE_BACK || 
                keyCode == KeyEvent.KEYCODE_VOLUME_UP || 
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return callSuperDispatchKeyEvent(event)
            }
            
            val pressed = event.action == KeyEvent.ACTION_DOWN
            lorieView.sendKeyEvent(0, keyCode, pressed)
            return true
        }

        // Terminal mode: keep the IME host focused so hardware / external keyboards
        // deliver KeyEvents to TerminalImeEditText (soft keyboard uses InputConnection).
        if (isTerminalVisible && isLateInit_etTerminalInput()) {
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return callSuperDispatchKeyEvent(event)
            }
            if (!etTerminalInput.hasFocus()) {
                etTerminalInput.requestFocus()
            }
            // If focus is elsewhere (Compose chrome), still feed hardware keys to PTY.
            val focus = currentFocus
            if (focus !== etTerminalInput && etTerminalInput is TerminalImeEditText) {
                if ((etTerminalInput as TerminalImeEditText).handleHardwareKey(event)) {
                    return true
                }
            }
        }
    }
    
    return callSuperDispatchKeyEvent(event)
}
