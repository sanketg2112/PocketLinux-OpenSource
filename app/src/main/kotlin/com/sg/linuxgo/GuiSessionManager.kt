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
import com.sg.linuxgo.x11.ICmdEntryInterface
import com.sg.linuxgo.x11.LorieView
import com.sg.linuxgo.x11.Prefs
import com.sg.linuxgo.util.getSafeBoolean
import com.sg.linuxgo.util.getSafeFloat
import com.sg.linuxgo.util.getSafeString
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class GuiSessionManager(
    internal val activity: Activity,
    internal val containerManager: ContainerManager,
    internal val bootstrap: Bootstrap,
    internal val activeContainerIdProvider: () -> String?,
    internal val updateSessionNotificationCall: (String, Int) -> Unit,
    internal val isProcessRunningCall: (String) -> Boolean,
    internal val onLog: (String) -> Unit,
    internal val viewDelegate: GuiViewDelegate
) {
    var x11SessionProcess: Process? = null
        internal set
    var x11ServerProcess: Process? = null
        internal set
    internal var x11SessionMonitorThread: Thread? = null
    internal var x11AutoRestartAttempted = false
    /** Last guest proot exit code (137 = SIGKILL/OOM from Android). */
    @Volatile internal var lastSessionExitCode: Int = -1
    /**
     * True while tearing down / recovering so binder death does not re-enter
     * [handleX11Disconnect].
     */
    @Volatile internal var isRecovering = false
    /** One death incident (monitor + heartbeat + binder) → one handler path. */
    @Volatile internal var x11DisconnectHandled = false
    /**
     * In-memory mirror of [SessionLifecycleGate.isUserStopInProgress] for the death
     * monitor thread (avoids prefs races mid-waitFor).
     */
    @Volatile internal var userRequestedTeardown = false

    var isX11Started = false
        internal set

    fun resetSessionState() {
        isX11Started = false
        x11AutoRestartAttempted = false
        lastSessionExitCode = -1
        isRecovering = false
        x11DisconnectHandled = false
        // Keep userRequestedTeardown: intentional Stop must stay visible to late
        // death-monitor callbacks until the next intentional launch clears it.
    }

    /**
     * Re-mark in-memory session flags after Activity recreate / DeX display move
     * when the display server or guest is still running.
     *
     * Never undoes a user Stop: does **not** clear [SessionLifecycleGate] user-stop
     * or re-allow FGS (that resurrected sessions after Stop and caused false OOM toasts).
     * Callers that intentionally start/resume must [clearUserStopForNewSession] first.
     */
    fun markSessionLive() {
        val userStop = userRequestedTeardown ||
            try {
                SessionLifecycleGate.isUserStopInProgress(activity)
            } catch (_: Exception) {
                false
            }
        if (userStop) {
            Log.i("GuiSessionManager", "markSessionLive ignored: user stop in progress")
            return
        }
        isX11Started = true
        x11DisconnectHandled = false
        isRecovering = false
    }

    /**
     * Clear intentional-stop flags before a **new** GUI launch or explicit Resume.
     * Must not be used from rehydrate / stats pollers.
     */
    fun clearUserStopForNewSession() {
        userRequestedTeardown = false
        isRecovering = false
        x11DisconnectHandled = false
        try {
            SessionLifecycleGate.setUserStopInProgress(activity, false)
        } catch (_: Exception) {
        }
        try {
            SessionLifecycleGate.setAllowed(activity, true)
        } catch (_: Exception) {
        }
    }

    /**
     * Call **before** killing proot/X11 on user Stop / notification terminate.
     * Suppresses distro_crash telemetry and crash-report prompts for the expected SIGKILL.
     */
    fun beginUserTeardown() {
        userRequestedTeardown = true
        isRecovering = true
        x11DisconnectHandled = true
        waitingForDesktop = false
        try {
            desktopReadyPoller?.interrupt()
        } catch (_: Exception) {
        }
        desktopReadyPoller = null
        try {
            x11SessionMonitorThread?.interrupt()
        } catch (_: Exception) {
        }
        x11SessionMonitorThread = null
        try {
            SessionLifecycleGate.setUserStopInProgress(activity, true)
        } catch (_: Exception) {
        }
        // Drop any crash report already queued from a prior death race.
        try {
            CrashReportCoordinator.clearPending(activity)
            (activity as? MainActivity)?.viewModel?.showCrashReportPrompt = false
        } catch (_: Exception) {
        }
    }

    var selectedGuiMode = "x11"
        internal set
    var selectedOrientation = "native"
        internal set
    var selectedScalePct = 100
        internal set

    internal var x11Service: ICmdEntryInterface? = null

    internal val loadingHandler = Handler(Looper.getMainLooper())
    internal var hostTmpDirForSession: File? = null
    internal var desktopReadyPoller: Thread? = null
    @Volatile internal var waitingForDesktop = false

    /** Stable label for the loading overlay — progressive ladder while desktop boots. */
    internal var bootPhaseIndex: Int = 0
    private val bootPhaseLabels = listOf(
        "Starting display server",
        "Starting desktop",
        "Almost ready",
    )

    /** Stable label for the loading overlay — container card name, no animated dots. */
    internal fun resolveBootingLabel(): String {
        val id = activeContainerIdProvider()
        val name = id?.let { containerManager.getContainer(it)?.name }?.trim().orEmpty()
        val phase = bootPhaseLabels.getOrElse(bootPhaseIndex.coerceIn(0, bootPhaseLabels.lastIndex)) {
            "Starting desktop"
        }
        return if (name.isNotEmpty()) "$phase · $name" else phase
    }

    internal fun applyBootingStatus() {
        viewDelegate.showLoadingStatus(resolveBootingLabel())
    }

    /** Advance loading copy (called from desktop-ready poller / recovery). */
    internal fun advanceBootingPhase() {
        if (bootPhaseIndex < bootPhaseLabels.lastIndex) {
            bootPhaseIndex++
            applyBootingStatus()
        }
    }

    internal fun resetBootingPhase() {
        bootPhaseIndex = 0
    }

    internal val fullscreenRunnable = object : Runnable {
        override fun run() {
            if (viewDelegate.isGuiVisible()) {
                setFullscreen()
                loadingHandler.postDelayed(this, 1000)
            }
        }
    }

    fun isX11SessionAlive(): Boolean {
        return try { x11SessionProcess?.isAlive == true } catch (_: Throwable) { false }
    }

    fun onNewX11Binder(ibinder: IBinder) {
        x11Service = ICmdEntryInterface.Stub.asInterface(ibinder)
        try {
            x11Service?.asBinder()?.linkToDeath({
                x11Service = null
                onLog("! X11 server disconnected")
                activity.runOnUiThread { handleX11Disconnect() }
            }, 0)
            tryX11Connect()
        } catch (e: Exception) {
            onLog("! X11 connection setup failed: ${e.message}")
        }
    }

    fun loadSettings() {
        val cid = activeContainerIdProvider()
        val prefsName = if (cid != null) "container_${cid}_settings" else "pocket_linux_settings"
        val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        selectedGuiMode = FeatureGates.effectiveGuiMode(
            activity,
            prefs.getSafeString("gui_mode", FeatureGates.defaultGuiMode(activity))
        )
        selectedOrientation = prefs.getSafeString("orientation", "native") ?: "native"
        // Guest toolkit scale is always 1×. Viewer zoom is Lorie displayScale only
        // (Termux-style framebuffer scale) — never rewrite guest DPI/config.
        selectedScalePct = 100
        bootstrap.overrideGuiMode(selectedGuiMode)

        // Container “Big screen ready”: landscape-friendly + keep awake for scrcpy / PC.
        val bigScreenReady = prefs.getSafeBoolean("big_screen_ready", false)
        if (bigScreenReady) {
            if (selectedOrientation == "native") {
                selectedOrientation = "landscape"
            }
            PreferenceManager.getDefaultSharedPreferences(activity)
                .edit()
                .putBoolean("keepScreenOn", true)
                .apply()
            try {
                activity.applyKeepScreenOnPreference()
            } catch (_: Exception) {
            }
        }

        // Orientation lock only on compact phones. Large screens / multi-window stay free.
        applyOrientationPreference(selectedOrientation)
    }

    /**
     * Applies user orientation preference only on compact phone-class displays.
     * Tablets, foldables (unfolded), and multi-window stay unrestricted.
     */
    fun applyOrientationPreference(orientation: String) {
        val sw = activity.resources.configuration.smallestScreenWidthDp
        val multiWindow = activity.isInMultiWindowMode
        if (sw >= 600 || multiWindow) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        activity.requestedOrientation = when (orientation) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    fun switchToGUIWindow() {
        loadSettings()
        if (selectedGuiMode == "wayland" && FeatureGates.isWaylandEnabled(activity)) {
            startNativeWaylandWindow()
        } else {
            startNativeX11Window()
        }
    }

    fun startNativeWaylandWindow() {
        val activeContainerId = activeContainerIdProvider()
        val isWaylandRunning = activeContainerId != null && isProcessRunningCall(":wayland")
        if (isX11Started && !isWaylandRunning) {
            isX11Started = false
        }
        if (isX11Started) {
            onLog("Wayland/X11 window already active or starting.")
            return
        }
        isX11Started = true

        // Same full-screen boot UI as X11 while we prep packages / launch helpers
        activity.runOnUiThread {
            applyBootingStatus()
            viewDelegate.setGuiLayoutVisible(true)
            try {
                viewDelegate.getLorieView().visibility = View.GONE
            } catch (_: Exception) {
            }
        }

        Thread {
            try {
                val rootFsDir = getActiveRootFsDir()
                // Stale marker makes WaylandActivity dismiss loading instantly
                File(rootFsDir, "tmp/pocketlinux-desktop-ready").delete()
                prepareWaylandSession(rootFsDir)
            } catch (e: Exception) {
                Log.e("GuiSessionManager", "Failed to prepare Wayland session: ${e.message}", e)
                onLog("! Wayland session prep failed: ${e.message}")
            }

            activity.runOnUiThread {
                // 1. Prepare active symlink for the compositor (must exist before NativeActivity)
                try {
                    val activeSymlink = File(activity.filesDir, "containers/active_rootfs")
                    if (activeSymlink.exists() || Files.isSymbolicLink(activeSymlink.toPath())) {
                        activeSymlink.delete()
                    }
                    val targetPath = Paths.get(getActiveRootFsDir().absolutePath)
                    val linkPath = Paths.get(activeSymlink.absolutePath)
                    linkPath.parent?.toFile()?.mkdirs()
                    Files.createSymbolicLink(linkPath, targetPath)
                    onLog("Wayland active symlink set up: ${getActiveRootFsDir().name}")
                } catch (e: Exception) {
                    Log.e("GuiSessionManager", "Failed to create symlink: ${e.message}", e)
                    onLog("! Failed to prepare Wayland active symlink: ${e.message}")
                }

                // 2. Start NativeActivity (compositor + proot guest via localdesktop.toml)
                try {
                    val resStr = getSessionResolution()
                    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(activity)
                    val bootLabel = resolveBootingLabel()
                    val intent = Intent(activity, WaylandActivity::class.java).apply {
                        // Stay in MainActivity's task (same app window as X11 GUI).
                        // Do not use FLAG_ACTIVITY_NEW_TASK — that + old taskAffinity
                        // opened a second recents entry labeled "Wayland Desktop".
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        putExtra("POCKETLINUX_RES", resStr)
                        putExtra("POCKETLINUX_SCALE", (selectedScalePct / 100f).toString())
                        putExtra("POCKETLINUX_HARDWARE_ACCEL", bootstrap.wantsHardwareGpuDrivers().toString())
                        putExtra("GUI_MODE", "wayland")
                        putExtra("POCKETLINUX_TOUCH_MODE", sharedPrefs.getString("touchMode", "1") ?: "1")
                        putExtra("POCKETLINUX_POINTER_SPEED", sharedPrefs.getInt("capturedPointerSpeedFactor", 100).toString())
                        putExtra("POCKETLINUX_SCALE_TOUCHPAD", sharedPrefs.getBoolean("scaleTouchpad", true).toString())
                        putExtra("POCKETLINUX_BOOT_LABEL", bootLabel)
                        // tawc-compat (GTK3 menus prime + graphics pref) — Wayland only
                        for ((k, v) in TawcWaylandCompat.compositorEnvExtras(activity)) {
                            putExtra(k, v)
                        }
                        activeContainerId?.let { putExtra("CONTAINER_ID", it) }
                    }
                    activity.startActivity(intent)
                    onLog("Wayland Native compositor launched.")
                    activeContainerId?.let { updateSessionNotificationCall(it, 0) }
                    viewDelegate.acquireWakeLock()
                    viewDelegate.startStatsPoller()
                } catch (e: Exception) {
                    Log.e("GuiSessionManager", "Failed to start Wayland NativeActivity", e)
                    onLog("! Failed to start Wayland NativeActivity: ${e.message}")
                    isX11Started = false
                }

                viewDelegate.onSessionStarted()
            }
        }.start()
    }

    fun getActiveRootFsDir(): File {
        val containerId = activeContainerIdProvider()
        return if (containerId != null) {
            File(containerManager.getContainerRootfsPath(containerId))
        } else {
            File(activity.filesDir, "rootfs")
        }
    }

    /**
     * Install packages the **host** X server needs to read from the rootfs.
     * Arch: `xkeyboard-config` (XKB tree). Without it Lorie returns start()=false
     * and never creates filesystem socket X0 — PRoot desktops always fail.
     *
     * Also fills pulseaudio-utils when pactl is missing (audio tip in logs).
     * No-op when XKB already resolves or distro is not Arch.
     */
    fun createHostTmpDir(containerId: String?): File {
        val hostTmpDir = File(activity.cacheDir, "container_${containerId ?: "legacy"}_tmp")
        if (!hostTmpDir.exists()) hostTmpDir.mkdirs()
        hostTmpDir.setReadable(true, false)
        hostTmpDir.setWritable(true, false)
        hostTmpDir.setExecutable(true, false)
        prepareX11LockFiles(hostTmpDir)
        return hostTmpDir
    }

    fun launchX11Service(
        hostTmpDir: File,
        rootFsDir: File,
        resolution: String? = null,
        forceRestart: Boolean = false
    ) {
        // Prefer a host-readable XKB tree (fixes Arch absolute symlink).
        // Never pass a broken classic path — Lorie access() fails and never binds X0.
        val xkbRoot = ContainerRestoreEngine.fixAndResolveXkbConfigRoot(rootFsDir) { onLog(it) }
        if (xkbRoot == null) {
            onLog("! XKB_CONFIG_ROOT unavailable — X server will fail (Arch: pacman -S xkeyboard-config)")
        }
        val intent = Intent(activity, X11Service::class.java).apply {
            putExtra("args", arrayOf(":0", "-ac", "-noreset"))
            putExtra("TMPDIR", hostTmpDir.absolutePath)
            if (xkbRoot != null) putExtra("XKB_CONFIG_ROOT", xkbRoot)
            putExtra("CALLER", "GuiSessionManager.launchX11Service")
            // forceRestart kept for API compatibility but is a no-op path in X11Service
            // for safety — callers should use [coldRestartX11Service] instead.
            putExtra(X11Service.EXTRA_FORCE_RESTART, forceRestart)
        }
        onLog(
            "X11Service XKB_CONFIG_ROOT=${xkbRoot ?: "(unset)"} TMPDIR=${hostTmpDir.absolutePath}" +
                if (forceRestart) " FORCE_RESTART(deprecated)" else ""
        )
        // Allow session FGS (shared notification) before starting :x11 display process.
        // Clear user-stop so a prior terminate does not suppress real crash detection.
        SessionLifecycleGate.setUserStopInProgress(activity, false)
        userRequestedTeardown = false
        SessionLifecycleGate.setAllowed(activity, true)
        // Isolated :x11 + startForegroundService races the FGS deadline on slow
        // phones (Infinix API 30 RemoteServiceException). Starter uses startService
        // while the GUI is visible; X11Service still promotes to FGS in onCreate.
        try {
            val mode = X11ServiceStarter.start(activity, intent)
            onLog("X11Service started via $mode")
        } catch (e: Exception) {
            Log.e("GuiSessionManager", "Failed to start X11Service", e)
            onLog("! Failed to start X11Service: ${e.message}")
        }
    }

    /**
     * Kill the `:x11` process and start a fresh X11Service.
     *
     * In-process re-init (`CmdEntryPoint.restartXServer` / FORCE_RESTART) re-enters
     * dixRegisterPrivateKey / dixAllocatePrivates and can SIGSEGV.
     * Cold process restart is safer and does not block the user.
     */
    fun coldRestartX11Service(
        hostTmpDir: File,
        rootFsDir: File,
        resolution: String? = null
    ) {
        onLog("Cold-restarting :x11 process (safe path — avoids native re-init SIGSEGV)…")
        // Do NOT call X11Service.stop() here — that clears SessionLifecycleGate and can
        // tear down SessionKeepAlive mid-session. Only kill the display process.
        try {
            activity.stopService(Intent(activity, X11Service::class.java))
        } catch (e: Exception) {
            Log.w("GuiSessionManager", "stopService X11 during cold restart: ${e.message}")
        }
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val x11Name = "${activity.packageName}:x11"
            am.runningAppProcesses?.forEach { proc ->
                if (proc.processName == x11Name) {
                    Log.i("GuiSessionManager", "Killing $x11Name pid=${proc.pid}")
                    android.os.Process.killProcess(proc.pid)
                }
            }
        } catch (e: Exception) {
            Log.w("GuiSessionManager", "kill :x11 failed: ${e.message}")
        }
        try {
            Thread.sleep(450L)
        } catch (_: InterruptedException) {
        }
        prepareX11LockFiles(hostTmpDir)
        // Keep session FGS allowed — desktop recovery is mid-session.
        SessionLifecycleGate.setAllowed(activity, true)
        launchX11Service(hostTmpDir, rootFsDir, resolution, forceRestart = false)
    }

    fun stopX11Session() {
        try {
            SessionTerminateReceiver.performCleanup(activity)
        } catch (e: Exception) {
            Log.e("GuiSessionManager", "Failed to stop X11 desktop session", e)
        }
    }

    fun repairBinSh(rootFsDir: File) {
        val binSh = File(rootFsDir, "bin/sh")
        if (binSh.exists()) {
            val isLink = Files.isSymbolicLink(binSh.toPath())
            if (!isLink) {
                Log.w("GuiSessionManager", "/bin/sh is not a symlink. Replacing with toybox symlink to prevent PRoot issues.")
                try {
                    binSh.delete()
                    val target = Paths.get("toybox")
                    val link = Paths.get(binSh.absolutePath)
                    Files.createSymbolicLink(link, target)
                } catch (e: Exception) {
                    Log.e("GuiSessionManager", "Failed to repair /bin/sh symlink: ${e.message}")
                }
            }
        }
    }

    fun prepareX11LockFiles(hostTmpDir: File) {
        val displayUnix = File(hostTmpDir, ".X11-unix")
        if (!displayUnix.exists()) displayUnix.mkdirs()
        // World-writable sticky dir so the X server (separate :x11 process) can bind
        displayUnix.setReadable(true, false)
        displayUnix.setWritable(true, false)
        displayUnix.setExecutable(true, false)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "1777", displayUnix.absolutePath)).waitFor()
        } catch (_: Exception) { }
        val displayZeroLock = File(hostTmpDir, ".X11-unix/X0")
        val lockZero = File(hostTmpDir, ".X0-lock")
        if (displayZeroLock.exists()) displayZeroLock.delete()
        if (lockZero.exists()) lockZero.delete()
    }

    /**
     * Readiness gate before starting the guest desktop.
     *
     * PRoot guests **must** use the filesystem socket at TMPDIR/.X11-unix/X0
     * (bind-mounted as guest /tmp). Abstract `@/tmp/.X11-unix/X0` is not
     * reachable inside proot. Binder-only "ready" is not enough — if X0 never
     * appears (often broken XKB_CONFIG_ROOT on Arch), the desktop stays blank.
     *
     * @return true only when a filesystem Xn socket exists
     */
    fun waitForX11Ready(hostTmpDir: File, timeoutMs: Long = 8_000L): Boolean {
        val sock = File(hostTmpDir, ".X11-unix/X0")
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLog = 0L
        var binderNoted = false
        while (System.currentTimeMillis() < deadline) {
            if (sock.exists() || sock.isFile) {
                // Socket files may not report as exists() consistently on all
                // Android builds; also accept zero-length special files.
                onLog("✓ X11 filesystem socket ready: ${sock.absolutePath}")
                return true
            }
            // Also accept any Xn under .X11-unix
            val dir = File(hostTmpDir, ".X11-unix")
            val anySock = dir.listFiles()?.any { it.name.startsWith("X") } == true
            if (anySock) {
                onLog("✓ X11 socket present under ${dir.absolutePath}")
                return true
            }
            if (x11Service != null && !binderNoted) {
                binderNoted = true
                onLog("… X11 binder connected — waiting for filesystem socket (required for PRoot)")
            }
            val now = System.currentTimeMillis()
            if (now - lastLog > 1500) {
                onLog("… waiting for X11 socket at ${sock.absolutePath}")
                lastLog = now
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        if (sock.exists()) {
            onLog("✓ X11 filesystem socket ready: ${sock.absolutePath}")
            return true
        }
        onLog("⚠ X11 filesystem socket missing after ${timeoutMs}ms")
        val dir = File(hostTmpDir, ".X11-unix")
        if (dir.isDirectory) {
            val names = dir.list()?.joinToString(", ") ?: "(empty)"
            onLog("  .X11-unix contents: $names")
        } else {
            onLog("  .X11-unix directory missing under ${hostTmpDir.absolutePath}")
        }
        // Surface native server log (XKB errors often land here)
        try {
            val logFile = File(activity.cacheDir, "x11_server.log")
            if (logFile.isFile && logFile.length() > 0) {
                val tail = logFile.readText().lines().takeLast(20).joinToString("\n")
                onLog("—— x11_server.log (tail) ——\n$tail")
            }
        } catch (e: Exception) {
            Log.w("GuiSessionManager", "Could not read x11_server.log: ${e.message}")
        }
        if (x11Service != null) {
            onLog("⚠ Binder connected but no filesystem X0 — PRoot guest cannot use DISPLAY=:0")
        }
        return false
    }

    /** @deprecated use [waitForX11Ready] */
    fun waitForX11FilesystemSocket(hostTmpDir: File, timeoutMs: Long = 2_000L): Boolean {
        return waitForX11Ready(hostTmpDir, timeoutMs)
    }

    fun destroy() {
        x11SessionMonitorThread?.interrupt()
        x11SessionMonitorThread = null
        try {
            x11SessionProcess?.destroyForcibly()
            x11SessionProcess = null
        } catch (_: Throwable) {}
        try {
            x11ServerProcess?.destroyForcibly()
            x11ServerProcess = null
        } catch (_: Throwable) {}
    }
}
