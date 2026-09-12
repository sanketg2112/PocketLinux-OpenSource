package com.sg.linuxgo

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Soft keyboard / keybar helpers for GUI and terminal modes.
 *
 * Prefer bound lateinit fields over [MainActivity.findViewById] so keyboard paths
 * never depend on registry timing (Crash A).
 */
private fun MainActivity.safeMainRoot(): View? {
    if (!isLateInit_mainRoot()) return viewRegistry[R.id.mainRoot]
    return mainRoot
}

internal fun MainActivity.showSoftKeyboardAndKeybar() {
    if (!isLateInit_guiContainer() || !isLateInit_lorieView()) return
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val mainRoot = safeMainRoot() ?: return

    if (guiContainer.visibility == View.VISIBLE) {
        // On GUI launch, do NOT show keyboard or extra keys by default.
        // User will trigger them via the edge pill.
        lorieView.requestFocus()
        lorieView.requestFocusFromTouch()

        // Ensure keyboard and keybar are hidden on start
        try {
            guiKeyBar.visibility = View.GONE
        } catch (_: UninitializedPropertyAccessException) {
            // guiKeyBar not bound yet
        }
        if (isLateInit_btnGuiHome()) {
            btnGuiHome.visibility = View.GONE
        }
        imm.hideSoftInputFromWindow(mainRoot.windowToken, 0)

        if (isDeXMode()) {
            if (!shownDexToast) {
                shownDexToast = true
                Toast.makeText(this, "press esc twice to go to home tab", Toast.LENGTH_LONG).show()
            }
        } else {
            // Post so the decor view is attached after fullscreen/layout settles.
            window.decorView.post {
                if (!isFinishing && !isDestroyed &&
                    isLateInit_guiContainer() && guiContainer.visibility == View.VISIBLE
                ) {
                    pillPopupController.showPillPopup()
                }
            }
        }
    }
}

internal fun MainActivity.showGuiKeyboardAndKeybar() {
    if (!isLateInit_guiContainer() || !isLateInit_lorieView()) return
    if (guiContainer.visibility != View.VISIBLE) return
    guiBackPressState = 1

    val mainRoot = safeMainRoot() ?: return
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
    val showAdditionalKbd = sharedPrefs.getBoolean("showAdditionalKbd", true)
    val activityWindow = window
    // LorieView hosts a real InputConnection that forwards commitText/keys to X11.
    // Focusing a dummy EditText steals IME from LorieView and typing appears broken
    // until the user taps the desktop again (which re-focuses LorieView).
    val imeTarget = lorieView

    if (isLateInit_btnGuiHome()) {
        btnGuiHome.visibility = View.GONE
    }

    if (showAdditionalKbd && isLateInit_guiKeyBar()) {
        guiKeyBar.visibility = View.VISIBLE
        guiKeyBar.bringToFront()

        // Set bottom padding from current IME insets if available,
        // so the bar is positioned correctly immediately
        val insets = ViewCompat.getRootWindowInsets(mainRoot)
        val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())
        if (ime != null && ime.bottom > 0 && !sharedPrefs.getBoolean("Reseed", false)) {
            guiKeyBarBottomPaddingDp = pxToDp(ime.bottom)
        }
    } else if (isLateInit_guiKeyBar()) {
        guiKeyBar.visibility = View.GONE
    }

    // Drop any leftover focus on the unused 1×1 anchor so IME binds to LorieView.
    if (isLateInit_guiImeAnchor()) {
        guiImeAnchor.clearFocus()
    }

    imeTarget.isFocusable = true
    imeTarget.isFocusableInTouchMode = true
    imeTarget.requestFocus()
    imeTarget.requestFocusFromTouch()

    fun requestIme() {
        if (isFinishing || isDestroyed) return
        if (!isLateInit_guiContainer() || guiContainer.visibility != View.VISIBLE) return
        imeTarget.requestFocus()
        // Refresh InputConnection so soft keyboard attaches after focus settle.
        imm.restartInput(imeTarget)
        WindowInsetsControllerCompat(activityWindow, imeTarget).show(WindowInsetsCompat.Type.ime())
        if (!imm.showSoftInput(imeTarget, InputMethodManager.SHOW_IMPLICIT)) {
            if (!imm.showSoftInput(imeTarget, InputMethodManager.SHOW_FORCED)) {
                @Suppress("DEPRECATION")
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            }
        }
    }

    imeTarget.post { requestIme() }
    mainRoot.postDelayed({ requestIme() }, 120)
    mainRoot.postDelayed({ requestIme() }, 300)
}

internal fun MainActivity.hideGuiKeyboardAndKeybar() {
    guiBackPressState = 0
    val mainRoot = safeMainRoot()
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    guiKeyBarBottomPaddingDp = 0
    try {
        if (isLateInit_guiKeyBar()) guiKeyBar.visibility = View.GONE
    } catch (_: UninitializedPropertyAccessException) {}
    if (isLateInit_btnGuiHome()) {
        btnGuiHome.visibility = View.GONE
    }

    if (isLateInit_guiImeAnchor()) {
        guiImeAnchor.clearFocus()
    }
    if (isLateInit_lorieView()) {
        lorieView.requestFocus()
        imm.hideSoftInputFromWindow(lorieView.windowToken, 0)
        WindowInsetsControllerCompat(window, lorieView).hide(WindowInsetsCompat.Type.ime())
    }

    if (mainRoot != null) {
        imm.hideSoftInputFromWindow(mainRoot.windowToken, 0)
        WindowInsetsControllerCompat(window, mainRoot).hide(WindowInsetsCompat.Type.ime())
    }

    setFullscreen()
    // After IME closes, ensure the edge pill is still available.
    if (!isDeXMode()) {
        window.decorView.post {
            if (!isFinishing && !isDestroyed) pillPopupController.showPillPopup()
        }
    }
}

internal fun MainActivity.toggleSoftKeyboard() {
    if (isFinishing || isDestroyed) return
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val mainRoot = safeMainRoot() ?: return

    val insets = ViewCompat.getRootWindowInsets(mainRoot)
    val isImeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true

    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
    val showIMEWhileExternalConnected = sharedPrefs.getBoolean("showIMEWhileExternalConnected", true)
    val externalConnected = isExternalKeyboardConnected()

    if (externalConnected && !showIMEWhileExternalConnected) {
        imm.hideSoftInputFromWindow(mainRoot.windowToken, 0)
        if (isLateInit_guiKeyBar()) guiKeyBar.visibility = View.GONE
        if (isSpecialKeysScrollInitialized()) {
            specialKeysScroll.visibility = View.GONE
        }
        setFullscreen()
        return
    }

    val isGuiVisible = isLateInit_guiContainer() && guiContainer.visibility == View.VISIBLE
    val isTerminalVisible = isLateInit_terminalView() && terminalView.visibility == View.VISIBLE

    if (isGuiVisible) {
        val keyBarVisible = isLateInit_guiKeyBar() && guiKeyBar.visibility == View.VISIBLE
        if (isImeVisible || keyBarVisible) {
            hideGuiKeyboardAndKeybar()
        } else {
            showGuiKeyboardAndKeybar()
        }
        return  // Skip setFullscreen() at the bottom — handled inline above
    } else if (isTerminalVisible) {
        if (isImeVisible) {
            imm.hideSoftInputFromWindow(mainRoot.windowToken, 0)
        } else {
            val showAdditionalKbd = sharedPrefs.getBoolean("showAdditionalKbd", true)
            if (showAdditionalKbd && isSpecialKeysScrollInitialized()) {
                specialKeysScroll.visibility = View.VISIBLE
            }
            ensureTerminalKeyboardReady()
        }
        // Never apply GUI immersive fullscreen while in terminal.
        resetFullscreen()
        return
    }

    // Default: only GUI uses immersive fullscreen helpers.
    if (isGuiVisible) {
        setFullscreen()
    }
}

internal fun MainActivity.isExternalKeyboardConnected(): Boolean {
    for (deviceId in android.view.InputDevice.getDeviceIds()) {
        val device = android.view.InputDevice.getDevice(deviceId) ?: continue
        val isAlphabetic = device.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC
        val isKeyBoard = device.supportsSource(android.view.InputDevice.SOURCE_KEYBOARD)
        val isExternalDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            device.isExternal
        } else {
            try {
                val method = android.view.InputDevice::class.java.getMethod("isExternal")
                method.invoke(device) as Boolean
            } catch (e: Exception) {
                false
            }
        }
        if (isKeyBoard && isAlphabetic && isExternalDevice) {
            return true
        }
    }
    return false
}
