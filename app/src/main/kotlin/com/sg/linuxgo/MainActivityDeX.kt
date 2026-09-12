package com.sg.linuxgo

import android.content.res.Configuration
import android.util.Log
import android.view.View

/** DeX */

internal fun MainActivity.isDeXMode(): Boolean {
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

/** DeX rehydrate + edge-pill snap after any configuration change. */
internal fun MainActivity.handleMainConfigurationChanged() {
    try {
        handleDeXConfigurationChanged()
    } catch (e: Exception) {
        Log.w("MainActivity", "DeX config change: ${e.message}")
    }
    // PopupWindow keeps absolute coords across orientation; re-snap edge pill.
    window.decorView.post {
        if (!isFinishing && !isDestroyed) {
            pillPopupController.repositionForCurrentScreen()
        }
    }
}

/**
 * After leaving Samsung DeX the activity often stays alive (configChanges) but loses
 * surface / in-memory session flags while the container + notification keep running.
 * Rehydrate card Resume state and reattach the X11 surface on the phone display.
 */
internal fun MainActivity.handleDeXConfigurationChanged() {
    val nowDeX = isDeXMode()
    val leftDeX = wasInDeXMode && !nowDeX
    val enteredDeX = !wasInDeXMode && nowDeX
    wasInDeXMode = nowDeX

    if (!leftDeX && !enteredDeX) return

    Log.i(
        "MainActivity",
        if (leftDeX) "Left Samsung DeX — rehydrating session for phone"
        else "Entered Samsung DeX"
    )

    try {
        rehydrateActiveSessionState()
        updateAdapterActiveState()
    } catch (e: Exception) {
        Log.w("MainActivity", "DeX session rehydrate: ${e.message}")
    }

    if (!leftDeX) return

    // GUI was on the external DeX display: reattach Lorie surface for the phone panel.
    // Never reattach during user Stop.
    if (isUserSessionStopInProgress()) return
    try {
        val guiVisible = isLateInit_guiContainer() && guiContainer.visibility == View.VISIBLE
        val sessionLive = guiSessionManager.isX11Started ||
            isDisplayServerRunning() ||
            guiSessionManager.isX11SessionAlive()
        if (sessionLive) {
            guiSessionManager.markSessionLive()
            if (guiVisible) {
                tryX11Connect()
                if (isLateInit_lorieView()) {
                    lorieView.triggerCallback()
                }
                // Phone may need soft keyboard / edge pill again (hidden while on DeX).
                if (!isDeXMode()) {
                    window.decorView.post {
                        if (!isFinishing && !isDestroyed &&
                            isLateInit_guiContainer() &&
                            guiContainer.visibility == View.VISIBLE
                        ) {
                            showSoftKeyboardAndKeybar()
                        }
                    }
                }
            } else {
                // Home tab: ensure Resume desktop / Stop are bound to the live session.
                updateAdapterActiveState()
            }
        }
    } catch (e: Exception) {
        Log.w("MainActivity", "DeX leave GUI reattach: ${e.message}")
    }
}
