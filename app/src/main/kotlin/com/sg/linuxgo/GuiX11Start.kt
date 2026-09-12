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

/** Native X11 window start and tryX11Connect. */

fun GuiSessionManager.startNativeX11Window() {
    // Session still running after DeX disconnect / activity recreate — reattach UI only.
    val displayStillUp = try {
        isProcessRunningCall(":x11")
    } catch (_: Exception) {
        false
    }
    val alreadyConnected = try {
        LorieView.connected()
    } catch (_: Exception) {
        false
    }
    if (isX11Started || displayStillUp || alreadyConnected) {
        onLog(
            if (isX11Started) "X11 window already active — showing desktop"
            else "X11 still running after display change — reattaching"
        )
        markSessionLive()
        // Show desktop immediately — do not re-run cold start / kill the live session.
        viewDelegate.setGuiLayoutVisible(true)
        setFullscreen()
        tryX11Connect()
        activity.runOnUiThread {
            try {
                val lorieView = viewDelegate.getLorieView()
                lorieView.visibility = View.VISIBLE
                lorieView.requestFocus()
                lorieView.triggerCallback()
            } catch (_: Exception) {
            }
            // setGuiLayoutVisible shows the boot overlay; dismiss for reattach.
            viewDelegate.hideLoadingOverlay()
            viewDelegate.onSessionStarted()
        }
        return
    }
    clearUserStopForNewSession()
    isX11Started = true
    x11AutoRestartAttempted = false
    x11DisconnectHandled = false
    isRecovering = false
    waitingForDesktop = true
    resetBootingPhase()
    // Version tag so logs prove which APK recovery/PA/FF profile is active
    onLog("GUI engine: recovery-v5 · launch_script_v44 · intentional-logout · host-pulse · firefox_proot_v7 · mini_session_v9 · alpine-tawcroot · kgsl-overlay")
    
    viewDelegate.setGuiLayoutVisible(true)
    
    // Enter immersive fullscreen mode
    setFullscreen()
    loadingHandler.postDelayed(fullscreenRunnable, 500)

    applyBootingStatus()
    viewDelegate.getLorieView().visibility = View.GONE

    // 2. Perform heavy setup in a background thread
    Thread {
        try {
            val rootFsDir = getActiveRootFsDir()
            prepareGuiRootfs(rootFsDir)
            // Arch often ships without host-readable XKB → Lorie never binds X0.
            // Install xkeyboard-config via proot before starting the server.
            ensureArchX11HostDeps(rootFsDir)
            stopX11Session()

            // Host PulseAudio (AAudio) — must be up before guest apps connect
            val paOk = HostPulseAudioServer.ensureRunning(activity) { onLog(it) }
            if (!paOk) {
                onLog("! Host PulseAudio failed — guest audio may be silent")
            }

            // Clear stale X11 server log from previous sessions
            File(activity.cacheDir, "x11_server.log").delete()

            val activeContainerId = activeContainerIdProvider()
            val hostTmpDir = createHostTmpDir(activeContainerId)
            hostTmpDirForSession = hostTmpDir
            // Clear any stale desktop-ready marker from a previous session
            File(hostTmpDir, "pocketlinux-desktop-ready").delete()
            val runner = ProotRunner(activity, { onLog(it) }, ProotBinary.Purpose.DESKTOP)

            val resStr = getSessionResolution()
            val engine = ContainerRestoreEngine(activity)
            val sessionUser = bootstrap.resolveUsernameForRootfs(rootFsDir)
            val sessionHome = if (sessionUser == "root") "/root" else "/home/$sessionUser"
            // IMPORTANT: desktop must stay under proot -0 (root caps) so apt/dpkg
            // work inside XFCE (Firefox install, etc.). Identity is presentation
            // only (USER/HOME/XDG + whoami wrappers) — same model as Terminal.
            // proot -i was tried for getuid cosmetics but breaks package installs.
            engine.applySessionIdentity(rootFsDir, sessionUser)
            onLog("Session identity: user=$sessionUser home=$sessionHome (euid stays 0 for apt)")
            val isArchRootfs = com.sg.linuxgo.gui.isArchRootfs(rootFsDir)
            // Bind GPU when prefs ask for a HW backend (Freedreno/Zink/Auto), not only
            // the legacy hw_accel flag — otherwise Freedreno env has no /dev/kgsl-3d0.
            // Arch+X11: Auto stays unbound (OOM guard). Explicit Freedreno/Zink/Panfrost
            // bind GPU so glmark2 can match the selected driver.
            val configuredGpu = bootstrap.getConfiguredGpuDriverMode(rootFsDir)
            val resolvedGpu = bootstrap.resolveGpuDriverModeForRootfs(rootFsDir, configuredGpu)
            val explicitHwMode = com.sg.linuxgo.bootstrap.isHardwareGpuDriverMode(configuredGpu)
            val wantHwGpu = bootstrap.wantsHardwareGpuDrivers(configuredGpu)
            val bindGpu = wantHwGpu && (!isArchRootfs || explicitHwMode)
            if (isArchRootfs && wantHwGpu && !explicitHwMode) {
                onLog("Arch X11: GPU device bind disabled for Auto (OOM guard; software GL)")
            } else if (isArchRootfs && explicitHwMode) {
                onLog("Arch X11: GPU bind enabled for explicit driver mode $configuredGpu")
            }
            onLog("GPU mode: configured=$configuredGpu resolved=$resolvedGpu bindGpu=$bindGpu")
            // Force guest env rewrite + lfdevs kgsl overlay every desktop start.
            // Stock Debian only has libdril stubs — must download real Android kgsl.
            // Overlay progress is shown in the GUI log (can take 1–3 min on first run).
            var hasRealKgsl = false
            try {
                if (explicitHwMode &&
                    (resolvedGpu == "adreno_freedreno" || resolvedGpu == "adreno_zink")
                ) {
                    activity.runOnUiThread {
                        viewDelegate.showLoadingStatus("GPU: downloading Mesa drivers…")
                    }
                }
                bootstrap.setupGpuEnvConfig(rootFsDir, configuredGpu, onGpuLog = { onLog(it) })
                hasRealKgsl = com.sg.linuxgo.bootstrap.rootfsHasKgslDri(rootFsDir)
                onLog(
                    if (hasRealKgsl) "GPU: real kgsl_dri.so present (native Freedreno path)"
                    else "! GPU: real kgsl still MISSING after overlay attempt — software GL this session"
                )
            } catch (e: Exception) {
                onLog("! setupGpuEnvConfig: ${e.message}")
            }
            val runtimeHint = try {
                if (ProotBinary.resolve(activity, ProotBinary.Purpose.DESKTOP).useTawcroot) {
                    "tawcroot"
                } else {
                    "proot"
                }
            } catch (_: Exception) {
                "proot"
            }
            // Alpine + tawcroot: copy host static apk into rootfs (no file binds).
            if (runtimeHint == "tawcroot" && TawcrootAlpineSupport.isAlpineRootfs(rootFsDir)) {
                val staged = TawcrootAlpineSupport.stageApkStatic(activity, rootFsDir)
                if (staged != null) {
                    onLog("Alpine tawcroot: staged apk.static for guest package ops")
                }
            }
            // Inject GPU into proot env -i. If Freedreno was requested but real kgsl is
            // still missing, force software env so we never ship MESA_LOADER=kgsl+stub→zink.
            val effectiveGpu = when {
                resolvedGpu == "adreno_freedreno" && !hasRealKgsl -> "llvmpipe"
                else -> resolvedGpu
            }
            if (effectiveGpu != resolvedGpu) {
                onLog("GPU: env forced $resolvedGpu → $effectiveGpu (no real kgsl module)")
            }
            val gpuEnvPairs = com.sg.linuxgo.bootstrap.gpuGuestEnvPairs(effectiveGpu)
            val extraEnv = buildMap {
                put("POCKETLINUX_RES", resStr)
                put("POCKETLINUX_SCALE", (selectedScalePct / 100f).toString())
                put("POCKETLINUX_HARDWARE_ACCEL", bindGpu.toString())
                put("USER", sessionUser)
                put("LOGNAME", sessionUser)
                put("HOME", sessionHome)
                put("PWD", sessionHome)
                put("POCKETLINUX_USERNAME", sessionUser)
                put("XDG_CONFIG_HOME", "$sessionHome/.config")
                put("XDG_DATA_HOME", "$sessionHome/.local/share")
                put("XDG_CACHE_HOME", "$sessionHome/.cache")
                put("XDG_RUNTIME_DIR", "/tmp/runtime-$sessionUser")
                // Ensure DISPLAY is present even if launch script is a stub / old
                put("DISPLAY", ":0")
                put("XDG_SESSION_TYPE", "x11")
                // Host PulseAudio (AAudio) — same port as Wayland
                put("PULSE_SERVER", "tcp:127.0.0.1:${HostPulseAudioServer.TCP_PORT}")
                // Reduce glycin usage; bwrap shim covers absolute glycin-svg paths
                put("GLYCIN_DISABLE", "1")
                put("GSK_RENDERER", "cairo")
                put(
                    "POCKETLINUX_MINI_SESSION",
                    if (isArchRootfs || runtimeHint == "tawcroot") "1" else "0"
                )
                put("POCKETLINUX_RUNTIME", runtimeHint)
                putAll(gpuEnvPairs)
            }

            onLog("Starting embedded X11 server at $resStr...")
            launchX11Service(hostTmpDir, rootFsDir, resStr)
            // PRoot guests require the filesystem socket TMPDIR/.X11-unix/X0
            // (abstract @/tmp/.X11-unix/X0 is invisible inside proot).
            var x11Ready = waitForX11Ready(hostTmpDir, timeoutMs = 10_000L)
            if (!x11Ready) {
                // Do not re-init X in-process (FORCE_RESTART) — that path SIGSEGVs in
                // libXlorie dixAllocatePrivates / dixRegisterPrivateKey on many devices.
                onLog("X0 missing — cold-restarting :x11 process…")
                coldRestartX11Service(hostTmpDir, rootFsDir, resStr)
                x11Ready = waitForX11Ready(hostTmpDir, timeoutMs = 12_000L)
            }
            if (!x11Ready) {
                onLog("! X11 filesystem socket never appeared — not starting desktop")
                onLog("  Arch fix (Terminal): pacman -S --needed xkeyboard-config")
                onLog("  then: ls -la /usr/share/X11/xkb /usr/share/xkeyboard-config-2")
                onLog("  if X11/xkb is absolute: ln -sfn ../xkeyboard-config-2 /usr/share/X11/xkb")
                onLog("  Force-stop the app and relaunch GUI. Check logcat for LorieNative.")
                waitingForDesktop = false
                isX11Started = false
                activity.runOnUiThread {
                    viewDelegate.showLoadingStatus("X11 failed — fix XKB (see log)")
                }
                return@Thread
            }

            onLog("Starting desktop as $sessionUser (HOME=$sessionHome; root caps for apt/dpkg)…")
            x11SessionProcess = runner.launchPersistent(
                rootFsDir.absolutePath,
                "/usr/local/bin/pocketlinux-launch",
                hostTmpDir.absolutePath,
                extraEnv,
                bindGpuDevices = bindGpu,
                workingDir = sessionHome,
                // null → proot -0 so dpkg/apt have superuser privilege in GUI too
                changeId = null
            )

            if (x11SessionProcess == null) {
                onLog("! Failed to start X11 desktop session")
            } else {
                activeContainerId?.let { updateSessionNotificationCall(it, 0) }
                // Monitor the guest process for unexpected death
                startX11SessionDeathMonitor()
            }

            // Start polling for connection
            tryX11Connect()
            viewDelegate.onSessionStarted()
            
            // Poll X11 server logs
            Thread {
                val logFile = File(activity.cacheDir, "x11_server.log")
                var lastPos = 0L
                while (viewDelegate.isGuiVisible()) {
                    if (logFile.exists()) {
                        logFile.inputStream().use { input ->
                            input.skip(lastPos)
                            input.bufferedReader().use { reader ->
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    onLog("[X11-SVR] ${line ?: ""}")
                                    lastPos = logFile.length()
                                }
                            }
                        }
                    }
                    Thread.sleep(1000)
                }
            }.start()

            // Start heartbeat monitor to detect dead sessions
            startX11HeartbeatMonitor()
            // Panel Log Out writes /tmp/pocketlinux-session-ended (all distros)
            startLogoutMarkerPoller()
            
            viewDelegate.startStatsPoller()
        } catch (e: Exception) {
            Log.e("GuiSessionManager", "Error in Native X11 startup", e)
            onLog("! Error in Native X11 startup: ${e.message}")
        }
    }.start()
}

fun GuiSessionManager.tryX11Connect() {
    if (LorieView.connected()) {
        activity.runOnUiThread {
            val lorieView = viewDelegate.getLorieView()
            lorieView.visibility = View.VISIBLE
            lorieView.requestFocus()
            lorieView.requestFocusFromTouch()
            // Keep fancy loading overlay until XFCE/panel is actually painted
            lorieView.reloadPreferences(Prefs(activity))
            lorieView.triggerCallback()
            viewDelegate.startX11AudioBridge()
            viewDelegate.onSessionStarted()
            startDesktopReadyPoller()
        }
        return
    }
    
    if (x11Service == null) {
        LorieView.requestConnection()
        Handler(Looper.getMainLooper()).postDelayed({ tryX11Connect() }, 500)
        return
    }

    try {
        val pfd: ParcelFileDescriptor? = x11Service?.getXConnection()
        if (pfd != null) {
            onLog("[X11] Establishing connection...")
            LorieView.connect(pfd.detachFd())
            activity.runOnUiThread {
                val lorieView = viewDelegate.getLorieView()
                lorieView.reloadPreferences(Prefs(activity))
                lorieView.triggerCallback()
                lorieView.visibility = View.VISIBLE
                lorieView.requestFocus()
                lorieView.requestFocusFromTouch()
                // Do NOT hide loading yet — wait for xfce4-panel (desktop ready marker)
                viewDelegate.startX11AudioBridge()
                viewDelegate.onSessionStarted()
                startDesktopReadyPoller()
            }
        } else {
            Handler(Looper.getMainLooper()).postDelayed({ tryX11Connect() }, 500)
        }
    } catch (e: Exception) {
        onLog("! X11 connect error: ${e.message}")
        x11Service = null
        Handler(Looper.getMainLooper()).postDelayed({ tryX11Connect() }, 1000)
    }
}

/**
 * Keep the loading overlay until the guest writes /tmp/pocketlinux-desktop-ready
 * (bound to [hostTmpDirForSession]), which happens once xfce4-panel / mate-panel /
 * lxqt-panel is running — or after a timeout so users are never stuck forever.
 */
