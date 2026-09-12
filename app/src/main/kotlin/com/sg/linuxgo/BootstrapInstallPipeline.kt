package com.sg.linuxgo

import android.util.Log
import java.io.File

/**
 * Prebuilt-image and legacy package install pipelines for [Bootstrap].
 */
internal fun Bootstrap.performPrebuiltImageInstall(
    callback: Bootstrap.BootstrapCallback,
    rootfsDir: File,
    containerId: String
) {
    callback.onProgress("Fetching container catalog…")
    callback.onDownloadProgress("Catalog", 2, 2, 100)

    val manifest = try {
        ContainerImageCatalog.fetch()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to fetch image catalog", e)
        throw Exception("Could not reach container catalog: ${e.message}")
    }

    val image = ContainerImageCatalog.selectImage(
        manifest,
        preferredDistro = selectedDistro,
        preferredDesktop = selectedDE
    )
    // Tag used in card progress status: "Downloading (v1.0.1): 320/780 MB" (% is bar-only)
    val versionLabel = image.tag.ifBlank {
        if (image.version.isNotBlank()) "v${image.version.removePrefix("v")}" else ""
    }
    fun phaseLabel(phase: String): String =
        if (versionLabel.isNotBlank()) "$phase ($versionLabel)" else phase

    callback.onProgress(
        "Installing ${image.distro.ifBlank { "Linux" }} ${image.desktop} $versionLabel" +
            " (${image.sizeBytes / (1024 * 1024)} MB)"
    )
    callback.onLogLine(
        "Catalog image: ${image.filename} $versionLabel sha256=${image.sha256.take(12)}…"
    )

    // Free space: need compressed size + decrypted copy + ~2× for extract workspace
    val needBytes = if (image.sizeBytes > 0) image.sizeBytes * 4 else 2L * 1024 * 1024 * 1024
    val usable = context.filesDir.usableSpace
    if (usable in 1 until needBytes) {
        val needMb = needBytes / (1024 * 1024)
        val haveMb = usable / (1024 * 1024)
        throw Exception("Not enough free storage. Need ~${needMb} MB, have ${haveMb} MB.")
    }

    val cacheDir = File(context.filesDir, "rootfs_cache").apply { mkdirs() }
    val cacheFile = File(cacheDir, image.filename.ifBlank { "container-${image.tag}.tar.gz" })

    val shaOk = cacheFile.exists() &&
        image.sha256.isNotBlank() &&
        fileSha256(cacheFile).equals(image.sha256, ignoreCase = true)

    if (shaOk) {
        callback.onProgress("✓ Using cached image $versionLabel (${cacheFile.length() / (1024 * 1024)} MB)")
        callback.onDownloadProgress(phaseLabel("Image cached"), 70, cacheFile.length(), cacheFile.length())
    } else {
        if (cacheFile.exists()) {
            callback.onProgress("Cached image outdated — re-downloading $versionLabel…")
            cacheFile.delete()
        }
        saveCheckpoint(containerId, Bootstrap.InstallPhase.DOWNLOAD_ROOTFS)
        callback.onProgress("Downloading container image $versionLabel…")
        // Prebuilt download occupies 3→70% of overall install progress (not legacy 0→35).
        // downloadFile* report overall % via downloadOverallProgress once the band is set,
        // so notification and container-card bars stay in lockstep.
        setDownloadProgressBand(3, 70)
        val relabel = object : Bootstrap.BootstrapCallback by callback {
            override fun onDownloadProgress(
                fileName: String,
                progress: Int,
                current: Long,
                total: Long
            ) {
                // progress is already overall in the prebuilt download band
                callback.onDownloadProgress(phaseLabel("Downloading"), progress, current, total)
            }
        }
        try {
            downloadFileMultiThreaded(image.url, cacheFile, relabel)
        } finally {
            resetDownloadProgressBandToLegacy()
        }
        callback.onDownloadProgress(phaseLabel("Downloading"), 70, cacheFile.length(), cacheFile.length())
    }

    // Verify checksum
    if (image.sha256.isNotBlank()) {
        callback.onProgress("Verifying image $versionLabel…")
        callback.onDownloadProgress(phaseLabel("Verifying"), 72, 72, 100)
        val actual = fileSha256(cacheFile)
        if (!actual.equals(image.sha256, ignoreCase = true)) {
            cacheFile.delete()
            throw Exception(
                "Image checksum mismatch. Expected ${image.sha256.take(12)}…, got ${actual.take(12)}… — please retry."
            )
        }
        callback.onProgress("✓ Image verified $versionLabel")
        callback.onDownloadProgress(phaseLabel("Verifying"), 75, 75, 100)
    }

    saveCheckpoint(containerId, Bootstrap.InstallPhase.EXTRACT_ROOTFS)

    // Same restore engine as Settings → Restore (toybox symlink + tar extract + validate)
    val engine = ContainerRestoreEngine(context)
    val containerManager = ContainerManager(context)

    if (BackupCrypto.isEncryptedName(cacheFile.name) || BackupCrypto.isEncryptedFile(cacheFile)) {
        throw Exception(
            "Encrypted catalog images are not supported in this build. Use an unencrypted .tar.gz image."
        )
    }

    callback.onProgress("Installing container $versionLabel…")
    // current/total = -1: install phase is not a file download — card must not show MB.
    callback.onDownloadProgress(phaseLabel("Installing"), 76, -1L, -1L)
    engine.extractBackupIntoRootfs(
        archive = cacheFile,
        rootfsDir = rootfsDir,
        onProgress = { fraction, message ->
            val overall = overallProgress(76, 94, fraction)
            // User-facing label: "Installing (vX.Y.Z)", not "Restoring"
            callback.onDownloadProgress(phaseLabel("Installing"), overall, -1L, -1L)
            if (fraction == 0f || fraction >= 1f ||
                message.startsWith("Preparing") ||
                message.startsWith("Installing")
            ) {
                callback.onProgress(message)
            }
        },
        isCancelled = { Thread.currentThread().isInterrupted }
    )

    callback.onProgress("Finishing setup…")
    val ok = engine.validateAndFix(rootfsDir, containerId, containerManager) { line ->
        callback.onLogLine(line)
        if (line.startsWith("!") || line.startsWith("✓") || line.startsWith("⚠")) {
            callback.onProgress(line)
        }
    }
    if (!ok) {
        throw Exception("Installed image failed validation. Please retry install.")
    }

    // Catalog golden images from GitHub: do NOT import bake-host gpu_driver_mode /
    // hw_accel. Production never runs install_desktop_*.sh for end users — only
    // extract + finalize. GPU is rewritten here and again on settings/GUI start.
    engine.applyEmbeddedMetadata(
        rootfsDir,
        containerId,
        containerManager,
        importGpuSettings = false
    ) { line -> callback.onLogLine(line) }

    // Ground-truth username from /home + passwd (do not keep "root" placeholders)
    val resolvedUser = engine.syncContainerUsername(
        rootfsDir, containerId, containerManager,
        onLog = { callback.onLogLine(it) }
    )
    username = resolvedUser

    // Android PRoot cannot use real setuid sudo (NO_NEW_PRIVS) — install a compatible shim
    try {
        engine.installProotHelpers(rootfsDir) { callback.onLogLine(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Proot helpers: ${e.message}")
    }

    saveCheckpoint(containerId, Bootstrap.InstallPhase.FINALIZE)
    callback.onProgress("Finalizing…")
    callback.onDownloadProgress("Finalizing", 95, 95, 100)

    try {
        setupDNS(rootfsDir)
    } catch (e: Exception) {
        Log.w(TAG, "DNS setup after restore: ${e.message}")
    }

    // Drop tarball GPU/launch stamps so first session cannot keep a zink/llvmpipe
    // bake-in from the GitHub image when prefs say Freedreno (or vice versa).
    try {
        val pl = File(rootfsDir, "var/lib/pocketlinux")
        listOf(
            "gpu_driver_mode",
            "launch_script_v28", "launch_script_v27", "launch_script_v26", "launch_script_v25",
            "launch_script_v24", "launch_script_v23", "launch_script_v22", "launch_script_v21",
            "launch_script_v20", "launch_script_v19", "launch_script_v18", "launch_script_v17"
        ).forEach { name -> File(pl, name).delete() }
        File(rootfsDir, "etc/profile.d/linuxgo-gpu.sh").delete()
    } catch (e: Exception) {
        Log.w(TAG, "clear image GPU stamps: ${e.message}")
    }

    // Re-apply container config (with synced username) so launch scripts match
    val finalConfig = containerManager.getContainer(containerId)
    if (finalConfig != null) {
        applyContainerPreset(finalConfig)
    } else {
        containerPreset?.let { applyContainerPreset(it.copy(username = resolvedUser)) }
    }
    // Always rewrite pocketlinux-launch + /etc/environment + pocketlinux-gpu.sh from
    // local prefs (default auto → probe dri in the restored rootfs). This is the
    // production GPU path for every distro shipped as a prebuilt image.
    try {
        val mode = getConfiguredGpuDriverMode()
        setupGpuEnvConfig(rootfsDir, mode)
        setupDisplayConfig(rootfsDir)
        callback.onLogLine("GPU finalize: mode=$mode → ${resolveGpuDriverModeForRootfs(rootfsDir, mode)}")
    } catch (e: Exception) {
        Log.w(TAG, "setupDisplayConfig after prebuilt restore: ${e.message}")
    }

    val launchScript = File(rootfsDir, "root/launch.sh")
    if (!launchScript.exists()) {
        try {
            File(rootfsDir, "root").mkdirs()
            launchScript.writeText("#!/bin/sh\nexec /usr/local/bin/pocketlinux-launch \"\$@\"\n")
            launchScript.setExecutable(true, false)
            callback.onProgress("✓ Created launch script")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write launch.sh", e)
        }
    }

    clearCheckpoint(containerId)
    val completedContainerId = containerId
    containerPreset = null
    callback.onDownloadProgress("Complete", 100, 100, 100)
    callback.onProgress("✓ Install complete (container $completedContainerId)")
    callback.onSuccess()
}

/** Legacy path: minimal rootfs + live package install under PRoot. */
internal fun Bootstrap.performLegacyPackageInstall(
    callback: Bootstrap.BootstrapCallback,
    rootfsDir: File,
    containerId: String
) {
    val lastPhase = getCheckpoint(containerId)
    // Alpine bin/sh is guest-absolute symlink to /bin/busybox — host exists() is false
    val hasExtractedRootfs = com.sg.linuxgo.util.TarHardlinkSafeExtract.hasGuestShell(rootfsDir) ||
        File(rootfsDir, "etc/os-release").exists()

    if (lastPhase.ordinal < Bootstrap.InstallPhase.EXTRACT_ROOTFS.ordinal && !hasExtractedRootfs) {
        val containerManager = ContainerManager(context)
        val isXz = getRootfsUrl().endsWith(".xz")

        val cachedTar = containerManager.getCachedRootfs(selectedDistro)
        val rootfsTar: File

        if (cachedTar != null) {
            callback.onProgress("✓ Found cached $selectedDistro rootfs (${cachedTar.length() / (1024*1024)} MB)")
            callback.onDownloadProgress(
                "rootfs (cached)",
                Bootstrap.InstallPhase.EXTRACT_ROOTFS.progress,
                cachedTar.length(),
                cachedTar.length()
            )
            rootfsTar = cachedTar
        } else {
            val cachePath = containerManager.getRootfsCachePath(selectedDistro, isXz)
            callback.onProgress("Downloading $selectedDistro rootfs...")
            saveCheckpoint(containerId, Bootstrap.InstallPhase.DOWNLOAD_ROOTFS)
            downloadFileMultiThreaded(getRootfsUrl(), cachePath, callback)
            rootfsTar = cachePath
        }

        saveCheckpoint(containerId, Bootstrap.InstallPhase.EXTRACT_ROOTFS)

        callback.onProgress("Extracting rootfs (this may take a while)...")
        callback.onDownloadProgress(
            "Extracting rootfs",
            Bootstrap.InstallPhase.EXTRACT_ROOTFS.progress,
            0,
            100
        )
        if (rootfsTar.name.endsWith(".xz")) {
            extractTarXz(rootfsTar, rootfsDir, callback)
        } else {
            extractTarGz(rootfsTar, rootfsDir, callback)
        }
        callback.onDownloadProgress(
            "Extracting rootfs",
            Bootstrap.InstallPhase.SETUP_DNS.progress,
            100,
            100
        )

        saveCheckpoint(containerId, Bootstrap.InstallPhase.SETUP_DNS)
    } else if (hasExtractedRootfs) {
        callback.onProgress("✓ Rootfs already extracted — resuming from checkpoint")
        callback.onDownloadProgress("Resume", Bootstrap.InstallPhase.SETUP_DNS.progress, 48, 100)
    }

    if (lastPhase.ordinal < Bootstrap.InstallPhase.RUN_INSTALL_SCRIPT.ordinal) {
        setupDNS(rootfsDir)
        saveCheckpoint(containerId, Bootstrap.InstallPhase.RUN_INSTALL_SCRIPT)
    }

    setupGUI(callback)
}
