package com.sg.linuxgo

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sg.linuxgo.ui.components.SpecialKeysBar
import com.sg.linuxgo.ui.theme.Cyan
import kotlin.math.abs
import kotlin.math.roundToInt

/** Char-to-key mapping, IME helpers, special keys, DeX. */

internal fun WaylandActivity.getKeyCodeAndScanCode(c: Char): Triple<Int, Int, Boolean>? {
    val isShift = c.isUpperCase() || "!@#$%^&*()_+{}|:\"<>?~".contains(c)
    val lowerChar = c.lowercaseChar()
    val (keyCode, scanCode) = when (lowerChar) {
        'a' -> Pair(KeyEvent.KEYCODE_A, 30)
        'b' -> Pair(KeyEvent.KEYCODE_B, 48)
        'c' -> Pair(KeyEvent.KEYCODE_C, 46)
        'd' -> Pair(KeyEvent.KEYCODE_D, 32)
        'e' -> Pair(KeyEvent.KEYCODE_E, 18)
        'f' -> Pair(KeyEvent.KEYCODE_F, 33)
        'g' -> Pair(KeyEvent.KEYCODE_G, 34)
        'h' -> Pair(KeyEvent.KEYCODE_H, 35)
        'i' -> Pair(KeyEvent.KEYCODE_I, 23)
        'j' -> Pair(KeyEvent.KEYCODE_J, 36)
        'k' -> Pair(KeyEvent.KEYCODE_K, 37)
        'l' -> Pair(KeyEvent.KEYCODE_L, 38)
        'm' -> Pair(KeyEvent.KEYCODE_M, 50)
        'n' -> Pair(KeyEvent.KEYCODE_N, 49)
        'o' -> Pair(KeyEvent.KEYCODE_O, 24)
        'p' -> Pair(KeyEvent.KEYCODE_P, 25)
        'q' -> Pair(KeyEvent.KEYCODE_Q, 16)
        'r' -> Pair(KeyEvent.KEYCODE_R, 19)
        's' -> Pair(KeyEvent.KEYCODE_S, 31)
        't' -> Pair(KeyEvent.KEYCODE_T, 20)
        'u' -> Pair(KeyEvent.KEYCODE_U, 22)
        'v' -> Pair(KeyEvent.KEYCODE_V, 47)
        'w' -> Pair(KeyEvent.KEYCODE_W, 17)
        'x' -> Pair(KeyEvent.KEYCODE_X, 45)
        'y' -> Pair(KeyEvent.KEYCODE_Y, 21)
        'z' -> Pair(KeyEvent.KEYCODE_Z, 44)
        '1', '!' -> Pair(KeyEvent.KEYCODE_1, 2)
        '2', '@' -> Pair(KeyEvent.KEYCODE_2, 3)
        '3', '#' -> Pair(KeyEvent.KEYCODE_3, 4)
        '4', '$' -> Pair(KeyEvent.KEYCODE_4, 5)
        '5', '%' -> Pair(KeyEvent.KEYCODE_5, 6)
        '6', '^' -> Pair(KeyEvent.KEYCODE_6, 7)
        '7', '&' -> Pair(KeyEvent.KEYCODE_7, 8)
        '8', '*' -> Pair(KeyEvent.KEYCODE_8, 9)
        '9', '(' -> Pair(KeyEvent.KEYCODE_9, 10)
        '0', ')' -> Pair(KeyEvent.KEYCODE_0, 11)
        '-', '_' -> Pair(KeyEvent.KEYCODE_MINUS, 12)
        '=', '+' -> Pair(KeyEvent.KEYCODE_EQUALS, 13)
        '\n' -> Pair(KeyEvent.KEYCODE_ENTER, 28)
        '\t' -> Pair(KeyEvent.KEYCODE_TAB, 15)
        ' ' -> Pair(KeyEvent.KEYCODE_SPACE, 57)
        '[', '{' -> Pair(KeyEvent.KEYCODE_LEFT_BRACKET, 26)
        ']', '}' -> Pair(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        '\\', '|' -> Pair(KeyEvent.KEYCODE_BACKSLASH, 43)
        ';', ':' -> Pair(KeyEvent.KEYCODE_SEMICOLON, 39)
        '\'', '"' -> Pair(KeyEvent.KEYCODE_APOSTROPHE, 40)
        ',', '<' -> Pair(KeyEvent.KEYCODE_COMMA, 51)
        '.', '>' -> Pair(KeyEvent.KEYCODE_PERIOD, 52)
        '/', '?' -> Pair(KeyEvent.KEYCODE_SLASH, 53)
        '`', '~' -> Pair(KeyEvent.KEYCODE_GRAVE, 41)
        else -> return null
    }
    return Triple(keyCode, scanCode, isShift)
}

internal fun WaylandActivity.injectKeyEvent(action: Int, keyCode: Int, scanCode: Int) {
    val now = SystemClock.uptimeMillis()
    try {
        val success = accessibilityService.nativeOnKeyEvent(action, keyCode, scanCode, now)
        android.util.Log.d("WaylandActivity", "injectKeyEvent: action=$action, keyCode=$keyCode, scanCode=$scanCode, success=$success")
    } catch (e: UnsatisfiedLinkError) {
        android.util.Log.e("WaylandActivity", "injectKeyEvent UnsatisfiedLinkError", e)
        window.decorView.dispatchKeyEvent(KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode))
    }
}

internal fun WaylandActivity.dispatchCharEvents(c: Char) {
    val mapping = getKeyCodeAndScanCode(c)
    if (mapping != null) {
        val (keyCode, scanCode, isShift) = mapping
        if (isShift) {
            injectKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 42)
        }
        
        injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, scanCode)
        injectKeyEvent(KeyEvent.ACTION_UP, keyCode, scanCode)
        
        if (isShift) {
            injectKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 42)
        }
    } else {
        val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = charMap.getEvents(charArrayOf(c))
        if (events != null) {
            for (event in events) {
                injectKeyEvent(event.action, event.keyCode, event.scanCode)
            }
        }
    }
}

internal fun WaylandActivity.showSoftKeyboard() {
    guiImeAnchor.setText("  ")
    guiImeAnchor.setSelection(2)
    guiImeAnchor.isFocusable = true
    guiImeAnchor.isFocusableInTouchMode = true
    guiImeAnchor.requestFocus()
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    // Use SHOW_FORCED to ensure keyboard appears even when NativeActivity surface has focus
    imm.showSoftInput(guiImeAnchor, InputMethodManager.SHOW_FORCED)
    // Retry after a short delay in case focus transfer isn't complete yet
    Handler(Looper.getMainLooper()).postDelayed({
        if (!isImeVisible && !isFinishing && !isDestroyed) {
            guiImeAnchor.setText("  ")
            guiImeAnchor.setSelection(2)
            guiImeAnchor.requestFocus()
            imm.showSoftInput(guiImeAnchor, InputMethodManager.SHOW_FORCED)
        }
    }, 200)
}

internal fun WaylandActivity.showSessionPausedOverlay() {
    isSessionPaused = true
    sessionPausedComposeView.visibility = View.VISIBLE
}

internal fun WaylandActivity.hideSessionPausedOverlay() {
    isSessionPaused = false
    sessionPausedComposeView.visibility = View.GONE
}

internal fun WaylandActivity.hideSoftKeyboard() {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    guiImeAnchor.clearFocus()
    window.decorView.requestFocus()
    imm.hideSoftInputFromWindow(guiImeAnchor.windowToken, 0)
    imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
    hideKeyBarPopup()
}

internal fun WaylandActivity.toggleSoftKeyboard() {
    if (isImeVisible) {
        hideSoftKeyboard()
    } else {
        showSoftKeyboard()
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  Special Key Bar Actions
// ─────────────────────────────────────────────────────────────────────────

internal fun WaylandActivity.onSpecialKeyPressed(keyName: String) {
    when (keyName) {
        "CTRL" -> isCtrlActive = !isCtrlActive
        "ALT" -> isAltActive = !isAltActive
        "SHIFT" -> isShiftActive = !isShiftActive
        "FN" -> isFnActive = !isFnActive
        "LEFT" -> sendKeyStroke(KeyEvent.KEYCODE_DPAD_LEFT)
        "UP" -> sendKeyStroke(KeyEvent.KEYCODE_DPAD_UP)
        "DOWN" -> sendKeyStroke(KeyEvent.KEYCODE_DPAD_DOWN)
        "RIGHT" -> sendKeyStroke(KeyEvent.KEYCODE_DPAD_RIGHT)
        "ENTER" -> sendKeyStroke(KeyEvent.KEYCODE_ENTER)
        "ESC" -> sendKeyStroke(KeyEvent.KEYCODE_ESCAPE)
        "TAB" -> sendKeyStroke(KeyEvent.KEYCODE_TAB)
        "HOME" -> sendKeyStroke(KeyEvent.KEYCODE_MOVE_HOME)
        "END" -> sendKeyStroke(KeyEvent.KEYCODE_MOVE_END)
        "PGUP" -> sendKeyStroke(KeyEvent.KEYCODE_PAGE_UP)
        "PGDN" -> sendKeyStroke(KeyEvent.KEYCODE_PAGE_DOWN)
        "INS" -> sendKeyStroke(KeyEvent.KEYCODE_INSERT)
        "DEL" -> sendKeyStroke(KeyEvent.KEYCODE_FORWARD_DEL)
        "COPY" -> {
            val wasCtrl = isCtrlActive
            isCtrlActive = true
            sendKeyStroke(KeyEvent.KEYCODE_C)
            isCtrlActive = wasCtrl
        }
        "PASTE" -> {
            val wasCtrl = isCtrlActive
            isCtrlActive = true
            sendKeyStroke(KeyEvent.KEYCODE_V)
            isCtrlActive = wasCtrl
        }
        "SAVE" -> {
            val wasCtrl = isCtrlActive
            isCtrlActive = true
            sendKeyStroke(KeyEvent.KEYCODE_S)
            isCtrlActive = wasCtrl
        }
    }
}
// ─────────────────────────────────────────────────────────────────────────
//  Popup Overlays Management
// ─────────────────────────────────────────────────────────────────────────

internal fun WaylandActivity.updateKeybarVisibility(keyboardHeight: Int = 0) {
    if (showLoadingOverlay) {
        hideKeyBarPopup()
        return
    }
    val showAdditionalKbd = try {
        sharedPrefs.getBoolean("showAdditionalKbd", true)
    } catch (e: Exception) { true }

    val lp = keybarAnchor.layoutParams as FrameLayout.LayoutParams
    if (lp.bottomMargin != keyboardHeight) {
        lp.bottomMargin = keyboardHeight
        keybarAnchor.layoutParams = lp
    }

    if (isImeVisible && showAdditionalKbd) {
        if (keybarPopup?.isShowing == true) {
            val density = resources.displayMetrics.density
            val heightPx = (48 * density).toInt()
            keybarPopup?.update(keybarAnchor, 0, -heightPx, WindowManager.LayoutParams.MATCH_PARENT, heightPx)
        } else {
            showKeyBarPopup()
        }
    } else {
        hideKeyBarPopup()
    }
}

internal fun WaylandActivity.isDeXMode(): Boolean {
    val config = resources.configuration
    try {
        val configClass = config.javaClass
        val semDesktopModeEnabled = configClass.getField("SEM_DESKTOP_MODE_ENABLED").getInt(config)
        val semDesktopModeEnabledValue = configClass.getField("semDesktopModeEnabled").getInt(config)
        if (semDesktopModeEnabled == semDesktopModeEnabledValue) return true
    } catch (_: Exception) {}

    val configStr = config.toString()
    if (configStr.contains("dexMode=") && !configStr.contains("dexMode=0")) {
        return true
    }

    if ((config.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_DESK) {
        return true
    }
    return false
}

