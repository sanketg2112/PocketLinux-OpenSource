package com.sg.linuxgo

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Display configuration, user identity resolution, and launcher generation
 * delegates extracted from [Bootstrap] to keep file sizes within project limits.
 */
internal fun Bootstrap.executeResolveUsernameForRootfs(rootfs: File): String {
    val engine = ContainerRestoreEngine(context)
    val containerId = containerPreset?.id
        ?: rootfs.parentFile?.name?.takeIf {
            rootfs.name == "rootfs" && rootfs.parentFile?.parentFile?.name == "containers"
        }
    if (containerId != null) {
        val cm = ContainerManager(context)
        // Sync + read so SharedPreferences matches /home + passwd
        val synced = engine.syncContainerUsername(rootfs, containerId, cm)
        if (synced.isNotBlank()) {
            username = synced
            return synced
        }
    }
    val detected = engine.detectGuestUsername(rootfs)
    if (!detected.isNullOrBlank()) {
        username = detected
        return detected
    }
    if (username.isNotBlank() && username != "root") return username
    username = "PocketLinux"
    return username
}

/**
 * Rewrite guest launch helpers (pocketlinux-launch, etc.).
 * Prefer [rootfsOverride] when the active container is known (GUI start path),
 * since [containerPreset] is often null outside install/restore.
 */
internal fun Bootstrap.executeSetupDisplayConfig(rootfsOverride: File? = null) {
    val rootfs = rootfsOverride ?: getRootfsDir()
    val rootDir = File(rootfs, "root")

    // Ensure pocketlinux_uid_spoof.so is deployed
    try {
        val spoofDest = File(rootfs, "usr/lib/pocketlinux_uid_spoof.so")
        spoofDest.parentFile?.mkdirs()
        context.assets.open("pocketlinux_uid_spoof.so").use { input ->
            spoofDest.outputStream().use { output -> input.copyTo(output) }
        }
        spoofDest.setReadable(true, false)
        spoofDest.setExecutable(true, false)
        Log.d(TAG, "pocketlinux_uid_spoof.so deployed in setupDisplayConfig")
    } catch (e: Exception) {
        Log.w(TAG, "Failed to deploy pocketlinux_uid_spoof.so in setupDisplayConfig: ${e.message}")
    }

    // Ensure branded wallpaper is present under backgrounds/ and backgrounds/xfce/
    try {
        ContainerRestoreEngine(context).deployWallpaper(rootfs) { msg ->
            Log.d(TAG, msg)
        }
    } catch (e: Exception) {
        Log.w(TAG, "deployWallpaper in setupDisplayConfig: ${e.message}")
    }

    val sessionUser = resolveUsernameForRootfs(rootfs)
    username = sessionUser
    try {
        ContainerRestoreEngine(context).applySessionIdentity(rootfs, sessionUser)
    } catch (e: Exception) {
        Log.w(TAG, "applySessionIdentity: ${e.message}")
    }

    val presetId = containerPreset?.id
        ?: rootfs.parentFile?.name?.takeIf { rootfs.name == "rootfs" && rootfs.parentFile?.parentFile?.name == "containers" }
    val prefsName = if (presetId != null) "container_${presetId}_settings" else "pocket_linux_settings"
    val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    // Viewer zoom is Lorie displayScale only. Never bake scale into guest scripts.
    val scalePct = 100

    val isArchRootfs = com.sg.linuxgo.gui.isArchRootfs(rootfs)
    val startCmd = com.sg.linuxgo.bootstrap.resolveStartCmd(
        isArchRootfs = isArchRootfs,
        selectedGuiMode = selectedGuiMode,
        selectedDE = selectedDE,
        selectedWM = selectedWM
    )
    val startCmdFallback = com.sg.linuxgo.bootstrap.resolveStartCmdFallback(
        isArchRootfs = isArchRootfs,
        selectedGuiMode = selectedGuiMode,
        selectedDE = selectedDE,
        selectedWM = selectedWM
    )
    // startCmdFallback retained for parity with previous launcher variants / future use
    @Suppress("UNUSED_VARIABLE")
    val _startCmdFallback = startCmdFallback

    // Mini XFCE session: Arch (OOM) + all distros for tawcroot keep-alive.
    // Never regenerate when container is MATE/LXQt — would steal launch to XFCE.
    if (selectedDE == "xfce4" || selectedDE == "xfce" || selectedDE.isBlank()) {
        try {
            ContainerRestoreEngine(context).ensureArchXfceMiniSession(rootfs) { msg ->
                Log.d(TAG, msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureArchXfceMiniSession in setupDisplayConfig: ${e.message}")
        }
    }

    // Always embed the resolved mode script — do not gate on hardwareAccel alone.
    // Previously hardwareAccel=false forced SOFTWARE_GPU_ENV even when the user
    // picked Freedreno (glmark2 stayed on llvmpipe), and a stale Zink bake-in
    // could win when prefs lagged SharedPreferences.apply().
    val configuredGpu = getConfiguredGpuDriverMode(rootfs)
    val resolvedGpu = resolveGpuDriverModeForRootfs(rootfs, configuredGpu)
    setupGpuEnvConfig(rootfs, configuredGpu)
    val gpuEnv = com.sg.linuxgo.bootstrap.gpuProfileScript(resolvedGpu)

    val homeDir = com.sg.linuxgo.bootstrap.guestHomeDir(username)
    val launcherContent = com.sg.linuxgo.bootstrap.buildPocketLinuxLaunchScript(
        username = username,
        homeDir = homeDir,
        selectedGuiMode = selectedGuiMode,
        selectedDE = selectedDE,
        startCmd = startCmd,
        gpuEnv = gpuEnv
    )

    val usrLocalBin = File(rootfs, "usr/local/bin")
    if (!usrLocalBin.exists()) usrLocalBin.mkdirs()

    val resizeScriptFile = File(usrLocalBin, "pocketlinux-resize.py")
    resizeScriptFile.writeText(com.sg.linuxgo.bootstrap.buildPocketLinuxResizeScript())
    // world-readable; executed via python inside guest
    resizeScriptFile.setReadable(true, false)

    val waylandSessionScriptContent = com.sg.linuxgo.bootstrap.buildWaylandSessionScript(startCmd)

    if (selectedGuiMode == "wayland") {
        com.sg.linuxgo.bootstrap.writeWaylandDisplayExtras(
            rootfs = rootfs,
            username = username,
            scalePct = scalePct,
            usrLocalBin = usrLocalBin
        )
    }

    val waylandSessionFile = File(usrLocalBin, "pocketlinux-wayland-session")
    waylandSessionFile.writeText(waylandSessionScriptContent)
    waylandSessionFile.setExecutable(true, false)

    val launcher = File(usrLocalBin, "pocketlinux-launch")
    launcher.writeText(launcherContent)
    launcher.setExecutable(true, false)

    val legacyLauncher = File(rootDir, "launch.sh")
    legacyLauncher.writeText("#!/bin/sh\nexec /usr/local/bin/pocketlinux-launch\n")
    legacyLauncher.setExecutable(true, false)

    // Stamp only after launch embeds the same resolved GPU mode (existing installs).
    try {
        val stampDir = File(rootfs, "var/lib/pocketlinux")
        if (!stampDir.isDirectory) stampDir.mkdirs()
        File(stampDir, "gpu_driver_mode").writeText("$resolvedGpu\n")
        // DE stamp so GUI prep can detect convert / preset mismatch and force rewrite.
        if (selectedDE.isNotBlank()) {
            File(stampDir, "desktop_de").writeText("$selectedDE\n")
            File(stampDir, "launch_de").writeText("$selectedDE\n")
            File(stampDir, "start_cmd").writeText("$startCmd\n")
        }
    } catch (_: Exception) {
    }
}
