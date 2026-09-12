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

/** GUI launch */

/**
 * Start GUI immediately. A tight free-RAM reading only annotates the loading
 * overlay — it never blocks launch or shows a toast/dialog.
 */
internal fun MainActivity.proceedLaunchGui(container: ContainerConfig) {
    guiSessionManager.clearUserStopForNewSession()
    setActiveContainerId(container.id)
    bootstrap.applyContainerPreset(container)
    if (com.sg.linuxgo.ui.components.GuiFirstRunCoachPrefs.shouldShow(this)) {
        viewModel.showGuiFirstRunCoach = true
    }
    switchToGUIWindow()
}

/**
 * Always continue launch. If free RAM is genuinely tight, stash a loading-screen
 * hint — never a dialog, toast, or cancel path.
 */
internal fun MainActivity.maybeWarnLowRamThen(onContinue: () -> Unit) {
    try {
        val availMb = readAvailRamMbForPill(this)
        viewModel.guiLoadingRamHint = lowRamLoadingHint(lowRamPhaseFor(availMb), availMb)
    } catch (_: Exception) {
        viewModel.guiLoadingRamHint = null
    }
    onContinue()
}

internal fun MainActivity.handleOnLaunchGui(container: ContainerConfig) {
    // Release builds never resume Wayland (feature gated); treat stored mode as x11.
    val waylandOk = FeatureGates.isWaylandEnabled(this)
    val effectiveMode = FeatureGates.effectiveGuiMode(this, selectedGuiMode)
    rehydrateActiveSessionState()
    val isWaylandAlive = waylandOk && effectiveMode == "wayland" &&
        (activeContainerId == container.id || activeContainerId == null) &&
        (isAppProcessSuffixRunning(":wayland") || isProcessRunning(":wayland"))
    val displayAlive = isAppProcessSuffixRunning(":x11") || isProcessRunning(":x11")
    val keepAliveForThis = try {
        (SessionKeepAliveService.isRunning || SessionLifecycleGate.isAllowed(this)) &&
            (activeContainerId == container.id ||
                SessionKeepAliveService.activeContainerId == container.id ||
                getSharedPreferences("pocket_linux_state", Context.MODE_PRIVATE)
                    .getString("active_container_id", null) == container.id)
    } catch (_: Exception) {
        false
    }
    val isX11Alive = effectiveMode == "x11" &&
        (guiSessionManager.isX11SessionAlive() ||
            guiSessionManager.isX11Started ||
            (activeContainerId == container.id && displayAlive) ||
            (displayAlive && keepAliveForThis) ||
            keepAliveForThis)

    // Bind id before resume if we only had keep-alive / prefs evidence.
    val desktopLive = isX11Alive || isWaylandAlive
    val sameContainer = activeContainerId == null || activeContainerId == container.id
    val exclusive = exclusiveSessionConflict(
        launchingDesktop = true,
        desktopLive = desktopLive && sameContainer,
        terminalLive = terminalSessions.isNotEmpty() && sameContainer
    )
    if (exclusive != ExclusiveSessionConflict.NONE) {
        confirmStopOtherSessionThen(exclusive, container) {
            maybeWarnLowRamThen { proceedLaunchGui(container) }
        }
        return
    }

    if (desktopLive) {
        // Explicit user Resume — clear Stop flags so reattach is allowed.
        guiSessionManager.clearUserStopForNewSession()
        if (activeContainerId != container.id) {
            setActiveContainerId(container.id)
        }
        guiSessionManager.markSessionLive()
    }

    if (activeContainerId == container.id && desktopLive) {
        if (waylandOk && effectiveMode == "wayland") {
            try {
                val resStr = guiSessionManager.getSessionResolution()
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
                val activityCtx = this
                val intent = Intent(this, WaylandActivity::class.java).apply {
                    // Same task as MainActivity — do not NEW_TASK (second window).
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra("POCKETLINUX_RES", resStr)
                    putExtra("POCKETLINUX_SCALE", (selectedScalePct / 100f).toString())
                    putExtra("POCKETLINUX_HARDWARE_ACCEL", (isLateInit_bootstrap() && bootstrap.wantsHardwareGpuDrivers()).toString())
                    putExtra("GUI_MODE", "wayland")
                    putExtra("POCKETLINUX_TOUCH_MODE", sharedPrefs.getString("touchMode", "1") ?: "1")
                    putExtra("POCKETLINUX_POINTER_SPEED", sharedPrefs.getInt("capturedPointerSpeedFactor", 100).toString())
                    putExtra("POCKETLINUX_SCALE_TOUCHPAD", sharedPrefs.getBoolean("scaleTouchpad", true).toString())
                    for ((k, v) in TawcWaylandCompat.compositorEnvExtras(activityCtx)) {
                        putExtra(k, v)
                    }
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to resume Wayland activity, starting fresh", e)
                setActiveContainerId(container.id)
                bootstrap.applyContainerPreset(container)
                switchToGUIWindow()
            }
            return
        }

        // Resume GUI (phone after DeX, or return from home while session stays up)
        setupView.visibility = View.GONE
        homeContainer.visibility = View.GONE
        terminalView.visibility = View.GONE
        tabLayout.visibility = View.GONE
        footerAction.visibility = View.GONE
        
        guiContainer.visibility = View.VISIBLE
        btnGuiHome.visibility = View.GONE
        setFullscreen()
        
        // Re-connect to X11 if surface was lost (common on DeX disconnect)
        if (!LorieView.connected()) {
            tryX11Connect()
        } else if (isLateInit_lorieView()) {
            lorieView.triggerCallback()
        }
        showSoftKeyboardAndKeybar()
        updateAdapterActiveState()
        return
    }

    // ── Single container enforcement ──
    // If another container is already running, prompt user to terminate first
    if (isAnySessionRunning() && activeContainerId != null && activeContainerId != container.id) {
        val runningName = containerManager.getContainer(activeContainerId!!)?.name ?: "Unknown"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Container Already Running")
            .setMessage("'$runningName' is currently running. Only one container can run at a time.\n\nTerminate it and launch '${container.name}'?")
            .setPositiveButton("Terminate & Launch") { _, _ ->
                terminateSession()
                maybeWarnLowRamThen { proceedLaunchGui(container) }
            }
            .setNegativeButton("Cancel", null)
            .show()
        return
    }

    maybeWarnLowRamThen { proceedLaunchGui(container) }
}

internal fun MainActivity.handleOnLaunchShell(container: ContainerConfig) {
    rehydrateActiveSessionState()
    val sameContainer = activeContainerId == null || activeContainerId == container.id
    // Prefer resuming in-memory tabs for this container (Back / Home detach).
    val hasResumableTabs = terminalSessions.isNotEmpty() &&
        (terminalSessions.any { it.isRunning } || activeContainerId == container.id)
    if (hasResumableTabs && sameContainer) {
        if (activeContainerId != container.id) {
            setActiveContainerId(container.id)
        }
        switchToTerminalWindow()
        return
    }

    val waylandOk = FeatureGates.isWaylandEnabled(this)
    val effectiveMode = FeatureGates.effectiveGuiMode(this, selectedGuiMode)
    val isWaylandAlive = waylandOk && effectiveMode == "wayland" &&
        isAppProcessSuffixRunning(":wayland")
    val displayAlive = isAppProcessSuffixRunning(":x11")
    val isX11Alive = exclusiveDesktopEvidence(
        x11SessionAlive = guiSessionManager.isX11SessionAlive(),
        x11ProcessAlive = displayAlive,
        waylandProcessAlive = isWaylandAlive
    )
    val desktopLive = isX11Alive
    val exclusive = exclusiveSessionConflict(
        launchingDesktop = false,
        desktopLive = desktopLive && sameContainer,
        terminalLive = terminalSessions.isNotEmpty() && sameContainer,
        resumingSameMode = false
    )
    if (exclusive != ExclusiveSessionConflict.NONE) {
        confirmStopOtherSessionThen(exclusive, container) {
            guiSessionManager.clearUserStopForNewSession()
            setActiveContainerId(container.id)
            bootstrap.applyContainerPreset(container)
            switchToTerminalWindow()
        }
        return
    }
    val isTerminalAlive = hasResumableTabs ||
        terminalSessions.any { it.isRunning } ||
        (activeContainerId == container.id && isGuestRuntimeRunning() && !isX11Alive && !SetupForegroundService.isRunningStatic)

    if (activeContainerId == container.id && isTerminalAlive) {
        // Resume existing tabs — do not closePty.
        switchToTerminalWindow()
        return
    }

    // ── Single container enforcement ──
    if (isAnySessionRunning() && activeContainerId != null && activeContainerId != container.id) {
        val runningName = containerManager.getContainer(activeContainerId!!)?.name ?: "Unknown"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Container Already Running")
            .setMessage("'$runningName' is currently running. Only one container can run at a time.\n\nTerminate it and launch '${container.name}' shell?")
            .setPositiveButton("Terminate & Launch") { _, _ ->
                terminateSession()
                setActiveContainerId(container.id)
                bootstrap.applyContainerPreset(container)
                switchToTerminalWindow()
            }
            .setNegativeButton("Cancel", null)
            .show()
        return
    }

    guiSessionManager.clearUserStopForNewSession()
    setActiveContainerId(container.id)
    bootstrap.applyContainerPreset(container)
    // Only reset when starting a fresh shell (no resumable tabs for this container).
    if (terminalSessions.isNotEmpty()) {
        closePty()
    }
    switchToTerminalWindow()
}

/**
 * Ask before tearing down the other mode (desktop vs in-app terminal).
 * Confirm always stops the current session, then [onConfirm] starts the new one.
 */
internal fun MainActivity.confirmStopOtherSessionThen(
    conflict: ExclusiveSessionConflict,
    container: ContainerConfig,
    onConfirm: () -> Unit
) {
    val prompt = exclusiveSessionPrompt(conflict, container.name) ?: run {
        onConfirm()
        return
    }
    viewModel.requestConfirm(
        com.sg.linuxgo.ui.components.AppConfirmRequest(
            title = prompt.title,
            message = prompt.message,
            confirmLabel = prompt.confirmLabel,
            dismissLabel = prompt.dismissLabel,
            destructive = true,
            onConfirm = {
                viewModel.clearConfirm()
                terminateSession(showToast = false)
                onConfirm()
            },
            onDismiss = { viewModel.clearConfirm() }
        )
    )
}

