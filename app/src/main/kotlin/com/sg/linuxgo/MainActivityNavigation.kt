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

/** Navigation */

/**
 * Leave the terminal UI for Home without killing PTYs or clearing tabs.
 * Used by system Back and the tab-bar Home control so sessions stay resumable.
 *
 * Compose cards need **both** [MainViewModel.isShellSessionActive] and a matching
 * [MainViewModel.activeContainerIdState] — otherwise they keep "Open terminal"
 * and hide the RAM graph.
 */
internal fun MainActivity.leaveTerminalToHome() {
    runOnUiThread {
        // Hide soft keyboard so Home is usable immediately.
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (isLateInit_etTerminalInput()) {
                etTerminalInput.clearFocus()
                imm.hideSoftInputFromWindow(etTerminalInput.windowToken, 0)
            }
            if (isLateInit_mainRoot()) {
                imm.hideSoftInputFromWindow(mainRoot.windowToken, 0)
            }
            window.currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {
        }

        val shellLive = !isUserSessionStopInProgress() && terminalSessions.isNotEmpty()
        // Bind container id before any snapshot so Resume + RAM attach to the right card.
        val boundId = if (shellLive) {
            ensureActiveContainerIdForTerminalSessions()
        } else {
            activeContainerId
        }

        if (shellLive && boundId != null) {
            isTerminalActiveState = true
            // Set ViewModel flags *before* navigation so first Home frame is correct.
            viewModel.isShellSessionActive = true
            viewModel.activeContainerIdState = boundId
            try {
                updateSessionNotification(boundId, 0)
            } catch (_: Exception) {
            }
        }

        switchToHomeTab()

        // Re-assert after home switch (updateAdapterActiveState must not undo this).
        if (!isUserSessionStopInProgress() && terminalSessions.isNotEmpty()) {
            val cid = ensureActiveContainerIdForTerminalSessions() ?: boundId
            isTerminalActiveState = true
            viewModel.isShellSessionActive = true
            if (cid != null) {
                viewModel.activeContainerIdState = cid
                if (activeContainerId != cid) {
                    setActiveContainerId(cid)
                }
                try {
                    updateSessionNotification(cid, 0)
                } catch (_: Exception) {
                }
                if (isLateInit_containerAdapter()) {
                    // shellActive=true drives ViewModel flags via controller override.
                    containerAdapter.setActiveContainer(
                        cid,
                        viewModel.isGuiSessionActive,
                        true
                    )
                }
            }
            // Keep poller measuring RAM for this session.
            try {
                startStatsPoller()
            } catch (_: Exception) {
            }
        }
    }
}

internal fun MainActivity.switchToHomeTab() {
    runOnUiThread {
        try {
            // Ensure views are available
            if (isLateInit_setupView()) {
                // Preserve terminal sessions — only hide the terminal surface.
                val hadLiveTerminal = terminalSessions.any { it.isRunning } ||
                    terminalSessions.isNotEmpty()
                setupView.visibility = View.GONE
                guiContainer.visibility = View.GONE
                terminalView.visibility = View.GONE
                homeContainer.visibility = View.GONE
                btnPrimaryAction.visibility = View.GONE
                tabLayout.visibility = View.GONE
                footerAction.visibility = View.GONE
                if (isLateInit_guiKeyBar()) {
                    guiKeyBar.visibility = View.GONE
                }
                guiBackPressState = 0  // Reset GUI back-press state machine
                hidePillPopup()
                if (isLateInit_specialKeysScroll()) {
                    specialKeysScroll.visibility = View.GONE
                }
                currentScreen = Screen.HOME
                // Heal READY state if install finished but card still PENDING
                if (!SetupForegroundService.isRunningStatic) {
                    containerManager.reconcileInstalledFlags()
                }
                containerConfigs = containerManager.getContainers()
                if (isLateInit_containerAdapter()) {
                    containerAdapter.updateContainers(containerConfigs)
                }
                // After DeX disconnect / background, rebind active id before card snapshot.
                // Skipped automatically while user Stop is in progress.
                rehydrateActiveSessionState()
                // Refresh RESUME GUI / Stop on cards while session stays alive in background.
                updateAdapterActiveState()

                // Terminal-only sessions can miss process probes briefly; keep Resume
                // while in-memory tabs still exist (id + shell flag for Compose cards).
                if (hadLiveTerminal && !isUserSessionStopInProgress() &&
                    terminalSessions.isNotEmpty()
                ) {
                    val cid = ensureActiveContainerIdForTerminalSessions()
                    viewModel.isShellSessionActive = true
                    isTerminalActiveState = true
                    if (cid != null) {
                        viewModel.activeContainerIdState = cid
                        if (isLateInit_containerAdapter()) {
                            containerAdapter.setActiveContainer(
                                cid,
                                viewModel.isGuiSessionActive,
                                true
                            )
                        }
                    }
                }
                
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                resetFullscreen()
                updateStatusBarColorAndIcons()

                val isLive = !isUserSessionStopInProgress() &&
                    (viewModel.isGuiSessionActive || viewModel.isShellSessionActive ||
                        hasLiveSessionEvidence() ||
                        terminalSessions.any { it.isRunning } ||
                        terminalSessions.isNotEmpty())

                if (!isLive) {
                    clearSessionNotification()
                } else {
                    // Keep the "session running" notification while detached from UI.
                    activeContainerId?.let { cid ->
                        try {
                            updateSessionNotification(cid, 0)
                        } catch (_: Exception) {
                        }
                    }
                }
                // Keep stats poller running whenever any container is installed so
                // storage bars appear right after first install (not only during a session).
                if (containerManager.getContainers().any { it.isInstalled }) {
                    startStatsPoller()
                } else if (!isLive) {
                    stopStatsPoller()
                }
            }
        } catch (e: Throwable) {
            Log.e("MainActivity", "switchToHomeTab error", e)
        }
    }
}

internal fun MainActivity.terminateSession(
    endReason: String = TelemetryManager.REASON_USER_STOP,
    showToast: Boolean = true
) {
    Log.i("MainActivity", "terminateSession() requested reason=$endReason")

    // Mark intentional stop *before* killing processes. destroyForcibly/pkill -9
    // yields exit 137 (SIGKILL), which the death monitor must not treat as OOM.
    try {
        guiSessionManager.beginUserTeardown()
    } catch (t: Throwable) {
        Log.w("MainActivity", "beginUserTeardown failed: ${t.message}")
        try {
            SessionLifecycleGate.setUserStopInProgress(this, true)
        } catch (_: Exception) {
        }
    }

    // 1. Perform OS-level aggressive cleanup
    SessionTerminateReceiver.performCleanup(this)
    
    // 2. Clear Java-side process references
    try {
        audioReceiver?.stop()
        audioReceiver = null
        try {
            HostPulseAudioServer.shutdown()
        } catch (_: Exception) {
        }
    } catch (_: Throwable) {}
    
    try {
        guiSessionManager.destroy()
    } catch (_: Throwable) {}

    try {
        closePty()
    } catch (_: Throwable) {}

    // 3. Reset UI state (keep user-stop flags — rehydrate must not resurrect session)
    guiSessionManager.resetSessionState()
    isTerminalActiveState = false
    viewModel.isGuiSessionActive = false
    viewModel.isShellSessionActive = false
    setActiveContainerId(null)
    // Win races with sticky FGS / stats poller rehydrate.
    try {
        SessionLifecycleGate.setUserStopInProgress(this, true)
        SessionLifecycleGate.setAllowed(this, false)
    } catch (_: Exception) {
    }
    
    try {
        clearSessionNotification()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        switchToHomeTab()
        // Keep storage bars updating after stop (switchToHomeTab restarts poller if needed).
        if (containerManager.getContainers().any { it.isInstalled }) {
            startStatsPoller()
        }
        // Force idle card state (do not rehydrate a just-stopped session).
        viewModel.isGuiSessionActive = false
        viewModel.isShellSessionActive = false
        if (isLateInit_containerAdapter()) {
            containerAdapter.setActiveContainer(null, false, false)
        }
        updateAdapterActiveState()
    } catch (t: Throwable) {
        Log.e("MainActivity", "Error switching to home tab", t)
        viewModel.isGuiSessionActive = false
        viewModel.isShellSessionActive = false
        updateAdapterActiveState()
    }
    
    if (showToast) {
        Toast.makeText(this, "Session Terminated", Toast.LENGTH_SHORT).show()
    }
}

internal fun MainActivity.isAnySessionRunning(): Boolean {
    // After user Stop, never report live (poller must not repost notification).
    if (isUserSessionStopInProgress()) return false
    rehydrateActiveSessionState()
    val isWaylandAlive = isAppProcessSuffixRunning(":wayland") || isProcessRunning(":wayland")
    val isX11Alive = guiSessionManager.isX11SessionAlive() ||
        guiSessionManager.isX11Started ||
        isAppProcessSuffixRunning(":x11") ||
        isProcessRunning(":x11") ||
        isWaylandAlive
    val isTerminalAlive = terminalSessions.any { it.isRunning } ||
        terminalSessions.isNotEmpty() ||
        (activeContainerId != null && isGuestRuntimeRunning() && !isX11Alive && !SetupForegroundService.isRunningStatic)
    return isX11Alive || isTerminalAlive
}

internal fun MainActivity.updateAdapterActiveState() {
    // Avoid touching guiSessionManager before bootstrap is ready (lazy manager needs it).
    if (!isLateInit_bootstrap() || !isLateInit_containerManager()) return

    val userStop = isUserSessionStopInProgress()
    if (userStop) {
        viewModel.isGuiSessionActive = false
        viewModel.isShellSessionActive = false
        isTerminalActiveState = false
        if (isLateInit_containerAdapter()) {
            // Flags only — do not wipe activeContainerIdState (override ignores null id).
            containerAdapter.setActiveContainer(null, false, false)
        }
        return
    }

    // DeX disconnect / activity recreate can drop activeContainerId while processes stay up.
    rehydrateActiveSessionState()
    // Terminal tabs on Home still need a bound container id for Resume + RAM.
    if (terminalSessions.isNotEmpty()) {
        ensureActiveContainerIdForTerminalSessions()
    }

    val isWaylandAlive = isAppProcessSuffixRunning(":wayland") || isProcessRunning(":wayland")
    val displayServerRunning = isAppProcessSuffixRunning(":x11") || isProcessRunning(":x11")
    val terminalTabsLive = terminalSessions.any { it.isRunning } || terminalSessions.isNotEmpty()
    val snapshot = computeSessionActiveSnapshot(
        isX11Started = guiSessionManager.isX11Started,
        isX11SessionAlive = guiSessionManager.isX11SessionAlive(),
        lorieConnected = isLateInit_lorieView() && com.sg.linuxgo.x11.LorieView.connected(),
        displayServerRunning = displayServerRunning,
        waylandRunning = isWaylandAlive,
        terminalSessionsRunning = terminalTabsLive,
        guestRuntimeRunning = isGuestRuntimeRunning(),
        hasActiveContainerId = activeContainerId != null,
        isInstalling = SetupForegroundService.isRunningStatic,
        userStopInProgress = false
    )
    val isX11Alive = snapshot.isGuiActive
    // In-memory tabs count as a resumable shell session even if a PTY probe
    // briefly fails while the user is on Home.
    val isTerminalAlive = snapshot.isShellActive || terminalTabsLive
    isTerminalActiveState = isTerminalAlive

    // Drive Compose home cards (RESUME GUI / Stop) via ViewModel snapshot state.
    viewModel.isGuiSessionActive = isX11Alive
    viewModel.isShellSessionActive = isTerminalAlive

    if (isLateInit_containerAdapter()) {
        val activeId = activeContainerId
        if (activeId != null && (isX11Alive || isTerminalAlive)) {
            // Always pass the real container id so Compose activeContainerIdState stays set.
            containerAdapter.setActiveContainer(activeId, isX11Alive, isTerminalAlive)
            // Belt-and-suspenders: override only updates id when non-null.
            if (viewModel.activeContainerIdState != activeId) {
                viewModel.activeContainerIdState = activeId
            }
        } else if (isTerminalAlive && activeId == null) {
            // Recover id so we never leave isShellSessionActive=true with null activeId.
            val recovered = ensureActiveContainerIdForTerminalSessions()
            if (recovered != null) {
                containerAdapter.setActiveContainer(recovered, isX11Alive, true)
                viewModel.activeContainerIdState = recovered
                viewModel.isShellSessionActive = true
            } else {
                containerAdapter.setActiveContainer(null, isX11Alive, false)
                viewModel.isShellSessionActive = false
            }
        } else {
            // Idle: clear live flags only (null id does not wipe ViewModel container id).
            containerAdapter.setActiveContainer(null, false, false)
            viewModel.isGuiSessionActive = false
            viewModel.isShellSessionActive = false
        }
    }
}

internal fun MainActivity.loadSettings() {
    guiSessionManager.loadSettings()
}

internal fun MainActivity.switchToGUIWindow() {
    guiSessionManager.switchToGUIWindow()
}

internal fun MainActivity.getActiveRootFsDir(): java.io.File {
    return guiSessionManager.getActiveRootFsDir()
}


internal fun MainActivity.switchToTerminalWindow() {
    setupView.visibility = View.GONE
    guiContainer.visibility = View.GONE
    homeContainer.visibility = View.GONE
    
    terminalView.visibility = View.VISIBLE
    currentScreen = Screen.TERMINAL
    // Terminal is never immersive — status bar always stays visible.
    resetFullscreen()
    updateStatusBarColorAndIcons()
    applyTerminalThemeToLegacyViews()
    btnPrimaryAction.visibility = View.GONE
    footerAction.visibility = View.GONE
    tabLayout.visibility = View.GONE
    if (isLateInit_guiKeyBar()) {
        guiKeyBar.visibility = View.GONE
    }
    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
    if (isLateInit_specialKeysScroll()) {
        val showAdditionalKbd = sharedPrefs.getBoolean("showAdditionalKbd", true)
        specialKeysScroll.visibility = if (showAdditionalKbd) View.VISIBLE else View.GONE
    }
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    
    // Resume existing tabs when present; only spawn when none left.
    if (terminalSessions.isEmpty()) {
        startTerminalProcess()
    }
    // Shell session is live (new or resumed) — home cards show Resume when detached.
    isTerminalActiveState = true
    viewModel.isShellSessionActive = true
    try {
        updateAdapterActiveState()
    } catch (_: Exception) {
    }
    activeContainerId?.let { cid ->
        try {
            updateSessionNotification(cid, 0)
        } catch (_: Exception) {
        }
    }
    ensureTerminalKeyboardReady()
}

/** Focus the hidden IME EditText and request the soft keyboard for terminal typing. */
internal fun MainActivity.ensureTerminalKeyboardReady() {
    if (!isLateInit_etTerminalInput()) return
    val showToken = terminalIme.beginShow() ?: return
    // Resize layout for IME so terminal content stays usable while typing.
    // Do NOT use ALWAYS_VISIBLE / restartInput on every call — that interrupts mid-word typing.
    window.setSoftInputMode(
        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    )
    etTerminalInput.isFocusable = true
    etTerminalInput.isFocusableInTouchMode = true
    etTerminalInput.isEnabled = true
    etTerminalInput.visibility = View.VISIBLE
    etTerminalInput.showSoftInputOnFocus = true

    // Output TextView must never hold focus (EDITABLE/selectable path turns it grey
    // and steals the soft keyboard after the first echoed character).
    if (isLateInit_tvTerminalOutput()) {
        tvTerminalOutput.isFocusable = false
        tvTerminalOutput.isFocusableInTouchMode = false
        if (tvTerminalOutput.hasFocus()) {
            tvTerminalOutput.clearFocus()
        }
    }

    fun requestIme(force: Boolean) {
        if (!terminalIme.isCurrentShow(showToken)) return
        if (isFinishing || isDestroyed) return
        if (!etTerminalInput.isAttachedToWindow) return
        if (!etTerminalInput.isFocusable) return
        val alreadyFocused = etTerminalInput.hasFocus()
        if (!alreadyFocused) {
            // Only reset buffer when taking focus — mid-session setText restarts IME.
            (etTerminalInput as? TerminalImeEditText)?.stabilizeBuffer()
            if (!etTerminalInput.requestFocus()) return
            etTerminalInput.requestFocusFromTouch()
        }
        if (!terminalIme.isCurrentShow(showToken)) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val flags = if (force) {
            InputMethodManager.SHOW_FORCED
        } else {
            InputMethodManager.SHOW_IMPLICIT
        }
        val shown = imm.showSoftInput(etTerminalInput, flags)
        if (!shown && terminalIme.isCurrentShow(showToken) && etTerminalInput.hasFocus()) {
            WindowInsetsControllerCompat(window, etTerminalInput)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    // Immediate attempt once layout can attach the Compose AndroidView host.
    etTerminalInput.post { requestIme(force = false) }
    // Compose reparent / focus settle retries (common failure window on first open).
    etTerminalInput.postDelayed({ requestIme(force = true) }, 120)
    etTerminalInput.postDelayed({ requestIme(force = true) }, 350)
}

/** Keep the terminal IME down (appearance / snippet overlays). */
internal fun MainActivity.setTerminalImeSuppressed(suppressed: Boolean) {
    if (suppressed) {
        if (!terminalIme.suppressed) {
            terminalIme.suppress()
        } else {
            terminalIme.invalidateShows()
        }
        hideTerminalImeNow()
    } else if (terminalIme.suppressed) {
        terminalIme.release()
    }
}

internal fun MainActivity.hideTerminalImeNow() {
    if (!isLateInit_etTerminalInput()) return
    terminalIme.invalidateShows()
    window.setSoftInputMode(
        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    )
    etTerminalInput.showSoftInputOnFocus = false
    etTerminalInput.clearFocus()
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(etTerminalInput.windowToken, 0)
    window.currentFocus?.let { v -> imm.hideSoftInputFromWindow(v.windowToken, 0) }
    WindowInsetsControllerCompat(window, etTerminalInput).hide(WindowInsetsCompat.Type.ime())
}

internal fun MainActivity.switchToSetupTab() {
    guiContainer.visibility = View.GONE
    terminalView.visibility = View.GONE
    homeContainer.visibility = View.GONE
    
    setupView.visibility = View.VISIBLE
    currentScreen = Screen.SETUP
    updateStatusBarColorAndIcons()
    if (isLateInit_guiKeyBar()) {
        guiKeyBar.visibility = View.GONE
    }
    if (isLateInit_specialKeysScroll()) {
        specialKeysScroll.visibility = View.GONE
    }
    if (isSetupComplete && !isTaskInProgress) {
        tvStepDescription.visibility = View.GONE
        progressBar.visibility = View.GONE
        tvProgressStatus.visibility = View.GONE
        checklistContainer.visibility = View.GONE
        metricsCard.visibility = View.VISIBLE
        btnRestoreSetup.visibility = View.GONE
        btnToggleLogs.visibility = View.VISIBLE
        logCardView.visibility = View.GONE // Toggleable
        btnPrimaryAction.visibility = View.GONE
        footerAction.visibility = View.GONE
    } else if (isTaskInProgress) {
        // Task (Backup/Restore/Install) is running
        tvStepDescription.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        tvProgressStatus.visibility = View.VISIBLE
        metricsCard.visibility = View.GONE
        btnRestoreSetup.visibility = View.GONE
        btnToggleLogs.visibility = View.VISIBLE
        logCardView.visibility = View.VISIBLE
        btnPrimaryAction.visibility = View.GONE
        footerAction.visibility = View.GONE
    } else {
        metricsCard.visibility = View.GONE
        btnRestoreSetup.visibility = View.VISIBLE
        btnPrimaryAction.visibility = btnPrimaryAction.visibility
        footerAction.visibility = View.VISIBLE
    }
    // Edge-to-edge: show system bars without deprecated systemUiVisibility flags.
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).show(
        WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    )
}

internal fun MainActivity.switchToSettingsTab() {
    runOnUiThread {
        try {
            if (isLateInit_setupView()) {
                setupView.visibility = View.GONE
                guiContainer.visibility = View.GONE
                terminalView.visibility = View.GONE
                homeContainer.visibility = View.GONE
                btnPrimaryAction.visibility = View.GONE
                tabLayout.visibility = View.GONE
                footerAction.visibility = View.GONE
                if (isLateInit_guiKeyBar()) {
                    guiKeyBar.visibility = View.GONE
                }
                if (isLateInit_specialKeysScroll()) {
                    specialKeysScroll.visibility = View.GONE
                }
                
                
                currentScreen = Screen.SETTINGS
                
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                resetFullscreen()
                updateStatusBarColorAndIcons()
            }
        } catch (e: Throwable) {
            Log.e("MainActivity", "switchToSettingsTab error", e)
        }
    }
}

