package com.sg.linuxgo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.PointerIcon
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import com.sg.linuxgo.x11.ICmdEntryInterface
import com.sg.linuxgo.x11.LorieView
import com.sg.linuxgo.x11.Prefs
import com.sg.linuxgo.util.getSafeBoolean
import com.sg.linuxgo.util.getSafeFloat
import com.sg.linuxgo.util.getSafeString
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** How long to keep the boot overlay after the guest ready marker. */
internal fun desktopReadySettleMs(markerContent: String): Long {
    val c = markerContent.lowercase()
    return when {
        "timeout" in c -> 800L
        "xfce" in c -> 2_200L
        else -> 1_500L
    }
}

/** X11 disconnect recovery, desktop-ready poller, death/heartbeat monitors. */

internal fun GuiSessionManager.startDesktopReadyPoller() {
    if (!waitingForDesktop) return
    desktopReadyPoller?.interrupt()
    desktopReadyPoller = Thread {
        val marker = File(hostTmpDirForSession ?: return@Thread, "pocketlinux-desktop-ready")
        // MATE can take longer; still hard-cap so users never hang on "waiting for panel".
        val deadline = System.currentTimeMillis() + 55_000L
        val startedAt = System.currentTimeMillis()
        var lastStatus = 0L
        try {
            while (waitingForDesktop && System.currentTimeMillis() < deadline) {
                // Progressive loading copy: ~4s → starting desktop, ~12s → almost ready.
                val elapsed = System.currentTimeMillis() - startedAt
                val desiredPhase = when {
                    elapsed >= 12_000L -> 2
                    elapsed >= 4_000L -> 1
                    else -> 0
                }
                if (desiredPhase > bootPhaseIndex) {
                    activity.runOnUiThread {
                        while (bootPhaseIndex < desiredPhase) advanceBootingPhase()
                    }
                }
                if (marker.isFile && marker.length() > 0L) {
                    val content = try {
                        marker.readText().trim()
                    } catch (_: Exception) {
                        "ready"
                    }
                    val settleMs = desktopReadySettleMs(content)
                    onLog("✓ Desktop ready (marker=$content) — settling ${settleMs}ms for GUI paint")
                    activity.runOnUiThread {
                        bootPhaseIndex = 2
                        applyBootingStatus()
                    }
                    try {
                        Thread.sleep(settleMs)
                    } catch (_: InterruptedException) {
                        finishLoadingOverlay()
                        return@Thread
                    }
                    if (waitingForDesktop) {
                        onLog("✓ Settled — dismissing loading screen")
                        finishLoadingOverlay()
                    }
                    return@Thread
                }
                val now = System.currentTimeMillis()
                if (now - lastStatus > 4_000L) {
                    onLog("… waiting for desktop panel (xfce4-panel / mate-panel / lxqt-panel)")
                    lastStatus = now
                }
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            if (waitingForDesktop) {
                onLog("⚠ Desktop ready timeout — showing GUI (panel may still be starting)")
                try {
                    Thread.sleep(800L)
                } catch (_: InterruptedException) {
                }
                finishLoadingOverlay()
            }
        } catch (e: Exception) {
            Log.w("GuiSessionManager", "desktop ready poller: ${e.message}")
            finishLoadingOverlay()
        }
    }.apply {
        isDaemon = true
        name = "desktop-ready-poller"
        start()
    }
}

internal fun GuiSessionManager.finishLoadingOverlay() {
    if (!waitingForDesktop) return
    waitingForDesktop = false
    activity.runOnUiThread {
        viewDelegate.hideLoadingOverlay()
    }
}

fun GuiSessionManager.handleX11Disconnect() {
    // User Stop / notification terminate: destroyForcibly + pkill -9 → exit 137.
    // Never treat that as OOM or open the crash-report prompt.
    val userStop = userRequestedTeardown ||
        try {
            SessionLifecycleGate.isUserStopInProgress(activity)
        } catch (_: Exception) {
            false
        }
    if (userStop) {
        Log.i("GuiSessionManager", "handleX11Disconnect ignored: user-requested session stop")
        onLog("… Session stopped by user (ignore process exit)")
        return
    }
    // Clean logout (exit 0) — even if GUI already hidden, clear session + home cards.
    if (lastSessionExitCode == 0) {
        activity.runOnUiThread { gracefullyExitCleanLogout() }
        return
    }
    // FORCE_RESTART / teardown can re-fire binder death — ignore while cleaning up.
    if (isRecovering) {
        onLog("… X11 binder reset during teardown (expected, ignored)")
        Log.i("GuiSessionManager", "handleX11Disconnect ignored: isRecovering")
        return
    }
    // Death monitor + heartbeat + binder can all fire for one death — handle once.
    if (x11DisconnectHandled) {
        Log.d("GuiSessionManager", "handleX11Disconnect ignored: already handled")
        return
    }
    x11DisconnectHandled = true
    isRecovering = true

    // Binder/heartbeat often fire before waitFor(); sample real exit if already dead,
    // or wait briefly so we do not report -1 when the child already returned 137.
    if (lastSessionExitCode == -1) {
        resolveSessionExitCodeBestEffort()
        // Race: death monitor may have just recorded clean logout.
        if (lastSessionExitCode == 0) {
            isRecovering = false
            x11DisconnectHandled = false
            activity.runOnUiThread { gracefullyExitCleanLogout() }
            return
        }
    }

    Log.w(
        "GuiSessionManager",
        "handleX11Disconnect: X11/session died guiVisible=${viewDelegate.isGuiVisible()}"
    )
    val crashClass = TelemetryManager.classifyDistroCrash(activity)
    val exitCode = lastSessionExitCode
    Log.w(
        "GuiSessionManager",
        "distro_crash class reason=${crashClass.crashReason} app_state=${crashClass.appState} " +
            "expected=${crashClass.expected} exit=$exitCode notif=${crashClass.notificationPermission}"
    )
    if (exitCode == 137) {
        onLog("! Desktop session killed by Android (exit 137 = SIGKILL / often low memory). No auto-recovery.")
    } else {
        onLog("! Display/session died (exit $exitCode). No auto-recovery — returning home.")
    }

    // No aggressive auto-restart (FORCE_RESTART + full DE relaunch often OOM-loops).
    // Show reason + suggestions via crash prompt; user restarts when ready.
    gracefullyExitDeadGui()
}

/**
 * Recover after Lorie binder death, session SIGKILL, or heartbeat failure.
 * - If filesystem socket is gone: force-restart native X (Looper is still alive).
 * - Always restart guest desktop with full env (DISPLAY, GLYCIN, …).
 * - Re-arm audio bridge (previous receiver may have given up).
 */
internal fun GuiSessionManager.recoverX11Session() {
    isRecovering = true
    try {
        recoverX11SessionInner()
    } finally {
        // Keep isRecovering briefly so late binder-death callbacks are still ignored
        Thread {
            try {
                Thread.sleep(3_000)
            } catch (_: InterruptedException) {
            }
            isRecovering = false
        }.start()
    }
}

internal fun GuiSessionManager.recoverX11SessionInner() {
    val rootFsDir = getActiveRootFsDir()
    val containerId = activeContainerIdProvider()
    val hostTmpDir = File(activity.cacheDir, "container_${containerId ?: "legacy"}_tmp")
    if (!hostTmpDir.exists()) hostTmpDir.mkdirs()
    val isArch = File(rootFsDir, "etc/arch-release").isFile

    val sock = File(hostTmpDir, ".X11-unix/X0")
    val sockAlive = try {
        sock.exists() || sock.isFile ||
            File(hostTmpDir, ".X11-unix").listFiles()?.any { it.name.startsWith("X") } == true
    } catch (_: Exception) {
        false
    }
    val clientConnected = try {
        LorieView.connected()
    } catch (_: Exception) {
        false
    }

    // After OOM (137) force X restart. If only the guest died and client is fine,
    // skip FORCE_RESTART — it drops the binder and used to abort recovery.
    val forceXRestart = lastSessionExitCode == 137 || !sockAlive || !clientConnected

    onLog(
        "Recovery: socket=${if (sockAlive) "up" else "missing"} " +
            "client=${if (clientConnected) "up" else "down"} " +
            "lastExit=$lastSessionExitCode forceX=$forceXRestart"
    )

    // Re-arm host audio (cleanup may have killed libpulseaudio_exec)
    HostPulseAudioServer.ensureRunning(activity) { onLog(it) }
    hostTmpDirForSession = hostTmpDir
    File(hostTmpDir, "pocketlinux-desktop-ready").delete()
    waitingForDesktop = true
    activity.runOnUiThread {
        applyBootingStatus()
        viewDelegate.setGuiLayoutVisible(true)
    }

    // Kill leftover guest desktop first so it cannot race a new X server
    try {
        x11SessionProcess?.destroyForcibly()
    } catch (_: Exception) {
    }
    x11SessionProcess = null
    try {
        Runtime.getRuntime().exec(
            arrayOf(
                "pkill", "-9", "-f",
                "startxfce4|xfce4-session|xfce4-panel|xfwm4|xfdesktop|xfsettingsd|pcmanfm|firefox|chromium"
            )
        ).waitFor()
    } catch (_: Exception) {
    }

    if (forceXRestart) {
        // Cold process restart only — in-process FORCE_RESTART causes libXlorie SIGSEGV.
        coldRestartX11Service(hostTmpDir, rootFsDir)
        waitForX11Ready(hostTmpDir, timeoutMs = 15_000L)
        val sockAfter = File(hostTmpDir, ".X11-unix/X0")
        val anyX = File(hostTmpDir, ".X11-unix").listFiles()?.any { it.name.startsWith("X") } == true
        if (!sockAfter.exists() && !anyX) {
            onLog("! X11 socket still missing after cold restart — recovery unlikely")
        }
    } else {
        onLog("X11 still usable — restarting desktop session only")
    }

    onLog("Restarting desktop session...")
    val runner = ProotRunner(activity, { onLog(it) }, ProotBinary.Purpose.DESKTOP)
    val resStr = getSessionResolution()
    val engine = ContainerRestoreEngine(activity)
    val sessionUser = bootstrap.resolveUsernameForRootfs(rootFsDir)
    val sessionHome = if (sessionUser == "root") "/root" else "/home/$sessionUser"
    engine.applySessionIdentity(rootFsDir, sessionUser)
    // Arch X11: never bind GPU devices — kgsl + Firefox + DE OOMs the proot tree.
    val configuredGpu = bootstrap.getConfiguredGpuDriverMode()
    val explicitHwMode = com.sg.linuxgo.bootstrap.isHardwareGpuDriverMode(configuredGpu)
    val bindGpu = bootstrap.wantsHardwareGpuDrivers() && (!isArch || explicitHwMode)
    val extraEnv = mapOf(
        "POCKETLINUX_RES" to resStr,
        "POCKETLINUX_SCALE" to (selectedScalePct / 100f).toString(),
        "POCKETLINUX_HARDWARE_ACCEL" to bindGpu.toString(),
        "USER" to sessionUser,
        "LOGNAME" to sessionUser,
        "HOME" to sessionHome,
        "PWD" to sessionHome,
        "POCKETLINUX_USERNAME" to sessionUser,
        "XDG_CONFIG_HOME" to "$sessionHome/.config",
        "XDG_DATA_HOME" to "$sessionHome/.local/share",
        "XDG_CACHE_HOME" to "$sessionHome/.cache",
        "XDG_RUNTIME_DIR" to "/tmp/runtime-$sessionUser",
        "DISPLAY" to ":0",
        "XDG_SESSION_TYPE" to "x11",
        "PULSE_SERVER" to "tcp:127.0.0.1:${HostPulseAudioServer.TCP_PORT}",
        "GLYCIN_DISABLE" to "1",
        "GSK_RENDERER" to "cairo",
        "POCKETLINUX_MINI_SESSION" to if (isArch) "1" else "0"
    )
    x11SessionProcess = runner.launchPersistent(
        rootFsDir.absolutePath,
        "/usr/local/bin/pocketlinux-launch",
        hostTmpDir.absolutePath,
        extraEnv,
        bindGpuDevices = bindGpu,
        workingDir = sessionHome,
        changeId = null
    )

    if (x11SessionProcess != null) {
        startX11SessionDeathMonitor()
        startX11HeartbeatMonitor()
        startLogoutMarkerPoller()
        Thread {
            try {
                Thread.sleep(20_000)
                if (try {
                        x11SessionProcess?.isAlive == true
                    } catch (_: Throwable) {
                        false
                    }
                ) {
                    x11AutoRestartAttempted = false
                    onLog("Recovery hold: session stable 20s — arming further auto-restart")
                }
            } catch (_: InterruptedException) {
            }
        }.start()
    }

    tryX11Connect()
    activity.runOnUiThread {
        try {
            viewDelegate.startX11AudioBridge()
        } catch (e: Exception) {
            Log.w("GuiSessionManager", "Audio re-bridge: ${e.message}")
        }
    }
    onLog("✓ X11 auto-restart initiated")
}

/**
 * Desktop Log Out / clean guest exit (exit 0): return to Home without crash UI.
 * Wallpaper-only stuck sessions after panel logout use this path once proot exits.
 */
fun GuiSessionManager.gracefullyExitCleanLogout() {
    val userStop = userRequestedTeardown ||
        try {
            SessionLifecycleGate.isUserStopInProgress(activity)
        } catch (_: Exception) {
            false
        }
    if (userStop) {
        Log.i("GuiSessionManager", "gracefullyExitCleanLogout: user stop already in progress")
        waitingForDesktop = false
        desktopReadyPoller?.interrupt()
        desktopReadyPoller = null
        viewDelegate.onSessionTerminated()
        isRecovering = false
        return
    }
    // Death monitor + heartbeat can both fire; only tear down once.
    if (x11DisconnectHandled) {
        Log.d("GuiSessionManager", "gracefullyExitCleanLogout ignored: already handled")
        return
    }
    x11DisconnectHandled = true
    isRecovering = true
    waitingForDesktop = false
    desktopReadyPoller?.interrupt()
    desktopReadyPoller = null
    onLog("… Logged out of desktop — returning home")
    try {
        CrashReportCoordinator.clearPending(activity)
        (activity as? MainActivity)?.viewModel?.showCrashReportPrompt = false
    } catch (_: Exception) {
    }
    // Hide GUI + switch home (terminateSession → switchToHomeTab).
    viewDelegate.onSessionTerminated()
    isRecovering = false
}

fun GuiSessionManager.gracefullyExitDeadGui() {
    val userStop = userRequestedTeardown ||
        try {
            SessionLifecycleGate.isUserStopInProgress(activity)
        } catch (_: Exception) {
            false
        }
    if (userStop) {
        // Intentional Stop already tore down UI / telemetry via terminateSession.
        Log.i("GuiSessionManager", "gracefullyExitDeadGui: user stop — no crash prompt")
        waitingForDesktop = false
        desktopReadyPoller?.interrupt()
        desktopReadyPoller = null
        try {
            CrashReportCoordinator.clearPending(activity)
            (activity as? MainActivity)?.viewModel?.showCrashReportPrompt = false
        } catch (_: Exception) {
        }
        viewDelegate.onSessionTerminated()
        isRecovering = false
        return
    }

    // Clean logout / session exit 0 — no crash toast or report.
    if (lastSessionExitCode == 0) {
        gracefullyExitCleanLogout()
        return
    }

    val exitCode = lastSessionExitCode
    val availMb = try {
        readDeviceAvailRamMb(activity)
    } catch (_: Exception) {
        -1
    }
    val actuallyLowRam = lowRamPhaseFor(availMb) != LowRamPillPhase.None
    Toast.makeText(
        activity,
        DistroCrashMessages.toastForExit(exitCode, actuallyLowRam),
        Toast.LENGTH_LONG,
    ).show()
    waitingForDesktop = false
    desktopReadyPoller?.interrupt()
    desktopReadyPoller = null

    val cid = activeContainerIdProvider()
    val container = cid?.let { containerManager.getContainer(it) }
    val sessionLogs = try {
        (activity as? MainActivity)?.setupLogText
    } catch (_: Exception) {
        null
    }
    // Don't prompt users for expected background deaths (no notification keep-alive).
    val crashClass = TelemetryManager.classifyDistroCrash(activity)
    if (crashClass.expected) {
        Log.i(
            "GuiSessionManager",
            "Skipping crash prompt — expected distro_crash (${crashClass.crashReason})"
        )
    } else {
        val inFg = crashClass.crashReason == TelemetryManager.DISTRO_REASON_APP_IN_FOREGROUND
        val distroLabel = container?.name ?: container?.distro
        CrashReportCoordinator.queuePending(
            activity,
            CrashReportCoordinator.PendingReport(
                kind = "distro_crash",
                activeDistro = container?.distro,
                exitCode = exitCode.takeIf { it != -1 },
                containerName = container?.name,
                guiMode = selectedGuiMode,
                recoveryAttempted = false,
                exceptionClass = crashClass.crashReason,
                exceptionMessage = crashClass.appState,
                stacktraceSnippet = null,
                sessionLogs = sessionLogs,
                summary = DistroCrashMessages.summaryForExit(exitCode, inFg),
                userExplanation = DistroCrashMessages.explanationForExit(exitCode, distroLabel),
                queuedAtMs = System.currentTimeMillis()
            )
        )
    }

    viewDelegate.onSessionTerminated()
    isRecovering = false
    // Prompt after session tear-down so the dialog is not buried under the GUI layer.
    activity.runOnUiThread {
        activity.window?.decorView?.postDelayed({
            CrashReportCoordinator.maybeShowPrompt(activity)
        }, 600L)
    }
}

/**
 * Prefer a real proot exit code over the default -1 when the process is already gone.
 * Must stay non-blocking (may run on the UI thread via binder death).
 */
internal fun GuiSessionManager.resolveSessionExitCodeBestEffort() {
    val process = x11SessionProcess ?: return
    try {
        if (!process.isAlive) {
            lastSessionExitCode = process.exitValue()
        }
    } catch (_: Exception) {
    }
}

internal fun GuiSessionManager.startX11SessionDeathMonitor() {
    x11SessionMonitorThread?.interrupt()
    x11SessionMonitorThread = Thread {
        try {
            val process = x11SessionProcess ?: return@Thread
            val exitCode = process.waitFor()
            lastSessionExitCode = exitCode
            val userStop = userRequestedTeardown ||
                try {
                    SessionLifecycleGate.isUserStopInProgress(activity)
                } catch (_: Exception) {
                    false
                }
            Log.w(
                "GuiSessionManager",
                "x11SessionProcess exited with code $exitCode userStop=$userStop"
            )
            if (userStop) {
                // Expected: terminateSession / notification Stop uses SIGKILL (often 137).
                onLog("… Desktop session ended after user stop (code $exitCode)")
                return@Thread
            }
            // Marker may win even when proot exit code is non-zero (race / late kill).
            val logoutMarker = try {
                val f = hostTmpDirForSession?.let { File(it, "pocketlinux-session-ended") }
                if (f != null && f.isFile) f.readText() else ""
            } catch (_: Exception) {
                ""
            }
            val intentionalLogout = exitCode == 0 || isIntentionalDesktopLogoutMarker(logoutMarker)
            // Always tear down Android GUI state when the guest exits — even if the
            // user already left Home, cards/notification must not stay "session live".
            // Exit 0 or user_logout marker → Home without crash prompt.
            if (intentionalLogout) {
                lastSessionExitCode = 0
                onLog("… Desktop session ended cleanly (logout / exit $exitCode)")
                activity.runOnUiThread { gracefullyExitCleanLogout() }
                return@Thread
            }
            onLog("! Desktop session process exited (code $exitCode)")
            if (exitCode == 137) {
                onLog("! Exit 137 = SIGKILL (Android OOM / low memory). PocketLinux did not kill the desktop.")
            }
            activity.runOnUiThread { handleX11Disconnect() }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e("GuiSessionManager", "X11 session monitor error", e)
        }
    }.apply {
        isDaemon = true
        name = "x11-session-death-monitor"
        start()
    }
}

internal fun GuiSessionManager.startX11HeartbeatMonitor() {
    Thread {
        try {
            while (true) {
                Thread.sleep(5000)

                if (!viewDelegate.isGuiVisible()) {
                    break
                }

                val processAlive = try { x11SessionProcess?.isAlive == true } catch (_: Throwable) { false }

                // Guest proot gone: X may still paint the last wallpaper frame.
                // Do not require Lorie disconnect — that left users stuck on wallpaper.
                if (!processAlive) {
                    resolveSessionExitCodeBestEffort()
                    Log.w(
                        "GuiSessionManager",
                        "Heartbeat: guest session process dead (exit=$lastSessionExitCode) " +
                            "lorieConnected=${try { LorieView.connected() } catch (_: Exception) { false }}"
                    )
                    onLog("! Heartbeat: desktop session process ended")
                    activity.runOnUiThread {
                        if (lastSessionExitCode == 0) {
                            gracefullyExitCleanLogout()
                        } else {
                            handleX11Disconnect()
                        }
                    }
                    break
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e("GuiSessionManager", "X11 heartbeat monitor error", e)
        }
    }.apply {
        isDaemon = true
        name = "x11-heartbeat-monitor"
        start()
    }
}

/**
 * Guest writes `/tmp/pocketlinux-session-ended` with **user_logout** only when the
 * user chooses Log Out (host bind = hostTmpDir). Fast poll → Home immediately.
 * Panel crash / empty marker / other content is ignored (must not false-trigger).
 */
internal fun GuiSessionManager.startLogoutMarkerPoller() {
    val hostTmp = hostTmpDirForSession ?: return
    Thread {
        val marker = File(hostTmp, "pocketlinux-session-ended")
        try {
            try {
                marker.delete()
            } catch (_: Exception) {
            }
            while (true) {
                if (!viewDelegate.isGuiVisible()) break
                if (x11DisconnectHandled || userRequestedTeardown) break
                if (marker.isFile && marker.length() > 0L) {
                    val content = try {
                        marker.readText()
                    } catch (_: Exception) {
                        ""
                    }
                    if (isIntentionalDesktopLogoutMarker(content)) {
                        Log.i("GuiSessionManager", "intentional user_logout marker — Home now")
                        onLog("… Logged out of desktop — returning home")
                        lastSessionExitCode = 0
                        activity.runOnUiThread { gracefullyExitCleanLogout() }
                        break
                    }
                    // Stale/unknown marker — ignore (do not treat as logout)
                }
                try {
                    // ~150ms: quick Home once user confirms Log Out
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w("GuiSessionManager", "logout marker poller: ${e.message}")
        }
    }.apply {
        isDaemon = true
        name = "desktop-logout-marker-poller"
        start()
    }
}

