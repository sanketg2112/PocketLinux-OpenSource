package com.sg.linuxgo

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Legacy package-install GUI/desktop setup script runner (extracted from Bootstrap).
 */
internal fun Bootstrap.executeSetupGUI(callback: Bootstrap.BootstrapCallback) {
    executor.execute {
        try {
            val rootfs = getRootfsDir()
            val rootfsPath = rootfs.absolutePath
            val isDebianBased = selectedDistro == "debian" ||
                selectedDistro == "ubuntu" ||
                selectedDistro == "kali"
            val rootDir = File(rootfs, "root")
            rootDir.mkdirs()
            // Always install X11 desktop packages. Wayland guest deps (labwc, Xwayland,
            // swaybg, …) are installed on first Wayland session via ensureWaylandGuestDeps
            // when experimental Wayland is enabled — no separate wayland install scripts.
            val scriptName = "install_desktop_x11_${selectedDistro}.sh"
            val installScript = File(rootDir, scriptName)

            val containerId = containerPreset?.id ?: "legacy"
            
            try {
                context.assets.open(scriptName).use { input ->
                    FileOutputStream(installScript).use { output ->
                        input.copyTo(output)
                    }
                }
                installScript.setExecutable(true, false)
            } catch (e: Exception) {
                val msg = "Legacy install script $scriptName not available (legacy installation is only supported in debug builds): ${e.message}"
                Log.e(TAG, msg, e)
                throw IllegalStateException(msg, e)
            }

            // ── Deploy pre-compiled link_shim.so from APK assets ─────────────────
            // link_shim.so is a prebuilt aarch64 binary bundled in APK assets
            // using the Android NDK and bundled as an asset. This avoids needing
            // gcc/binutils inside PRoot (which fails due to hardlink() syscall blocks).
            // The install script's Phase 5 just verifies it's present.
            try {
                val shimLibDir = File(rootfs, "usr/lib")
                shimLibDir.mkdirs()
                val shimDest = File(shimLibDir, "link_shim.so")
                context.assets.open("link_shim.so").use { input ->
                    FileOutputStream(shimDest).use { output -> input.copyTo(output) }
                }
                shimDest.setExecutable(false, false) // world-readable shared lib
                Log.d(TAG, "link_shim.so deployed to ${shimDest.absolutePath}")
                callback.onProgress("✓ link_shim.so deployed from bundle")
                try {
                    val xbpsShim = File(shimLibDir, "xbps_extract_shim.so")
                    context.assets.open("xbps_extract_shim.so").use { input ->
                        FileOutputStream(xbpsShim).use { output -> input.copyTo(output) }
                    }
                    xbpsShim.setReadable(true, false)
                    xbpsShim.setExecutable(true, false)
                    callback.onProgress("✓ xbps_extract_shim.so deployed from bundle")
                } catch (e2: Exception) {
                    Log.w(TAG, "xbps_extract_shim.so not in assets: ${e2.message}")
                }
                try {
                    val mat = File(rootfs, "usr/local/bin/pocketlinux-xbps-materialize")
                    mat.parentFile?.mkdirs()
                    context.assets.open("pocketlinux_xbps_materialize.py").use { input ->
                        FileOutputStream(mat).use { output -> input.copyTo(output) }
                    }
                    mat.setReadable(true, false)
                    mat.setExecutable(true, false)
                    callback.onProgress("✓ xbps soname copy helper deployed")
                } catch (e3: Exception) {
                    Log.w(TAG, "pocketlinux_xbps_materialize.py not in assets: ${e3.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "link_shim.so not in assets (will be skipped): ${e.message}")
                callback.onProgress("⚠ link_shim.so not bundled — Xvnc lock flag will use fallback")
            }

            try {
                val spoofDest = File(rootfs, "usr/lib/pocketlinux_uid_spoof.so")
                spoofDest.parentFile?.mkdirs()
                context.assets.open("pocketlinux_uid_spoof.so").use { input ->
                    FileOutputStream(spoofDest).use { output -> input.copyTo(output) }
                }
                spoofDest.setReadable(true, false)
                spoofDest.setExecutable(true, false)
                Log.d(TAG, "pocketlinux_uid_spoof.so deployed to ${spoofDest.absolutePath}")
                callback.onProgress("✓ pocketlinux_uid_spoof.so deployed from bundle")
            } catch (e: Exception) {
                Log.w(TAG, "pocketlinux_uid_spoof.so not in assets: ${e.message}")
            }

            // Install-time wallpaper only: seed before guest script so phase7/skel can copy
            // into backgrounds/ + xfce4/backdrops; setupDisplayConfig re-seeds after install.
            try {
                ContainerRestoreEngine(context).deployWallpaper(rootfs) { msg ->
                    callback.onProgress(msg)
                }
                File(rootfs, "var/lib/pocketlinux").mkdirs()
                File(rootfs, "var/lib/pocketlinux/wallpaper_backdrops_v1").writeText("ok\n")
            } catch (e: Exception) {
                Log.w(TAG, "deployWallpaper before install: ${e.message}")
            }

            val apps = selectedSoftware.filter { it != "done" }.joinToString(" ")

            // Environment variables the install script reads
            val scriptEnv = mapOf(
                "POCKETLINUX_DISTRO" to selectedDistro,
                "POCKETLINUX_DE" to selectedDE,
                "POCKETLINUX_WM" to selectedWM,
                "POCKETLINUX_STYLE" to selectedStyle,
                "POCKETLINUX_GUI_MODE" to selectedGuiMode,
                "POCKETLINUX_HARDWARE_ACCEL" to wantsHardwareGpuDrivers().toString(),
                "POCKETLINUX_MIRROR" to selectedMirror,
                "POCKETLINUX_SOFTWARE" to apps,
                "POCKETLINUX_USERNAME" to username,
                "POCKETLINUX_RECOMMENDS" to installRecommends.toString()
            )

            // Alpine needs host static apk (W^X). Classic proot: bind libapk.so.
            // tawcroot: directory binds only — copy libapk into the rootfs instead.
            val setupRuntime = ProotBinary.resolve(context, ProotBinary.Purpose.SETUP)
            val extraBinds = if (selectedDistro == "alpine" && !setupRuntime.useTawcroot) {
                val apkPath = File(context.applicationInfo.nativeLibraryDir, "libapk.so").absolutePath
                mapOf(apkPath to "/sbin/apk.static")
            } else {
                emptyMap()
            }
            if (selectedDistro == "alpine" && setupRuntime.useTawcroot) {
                val staged = TawcrootAlpineSupport.stageApkStatic(context, rootfs)
                if (staged != null) {
                    callback.onProgress("Alpine: staged host apk.static for tawcroot")
                } else {
                    callback.onProgress("WARNING: Alpine tawcroot: could not stage apk.static")
                }
            }

            callback.onProgress("Executing native setup script...")
            callback.onDownloadProgress("System Setup", Bootstrap.InstallPhase.RUN_INSTALL_SCRIPT.progress, 0, 100)
            saveCheckpoint(containerId, Bootstrap.InstallPhase.RUN_INSTALL_SCRIPT)

            val currentPhaseMessage = java.util.concurrent.atomic.AtomicReference("System Setup")
            val currentPercent = java.util.concurrent.atomic.AtomicInteger(Bootstrap.InstallPhase.RUN_INSTALL_SCRIPT.progress)

            // ── Activity poller: animate status dots while script runs ──
            // Percent only advances on install-script phase markers (monotonic).
            stopDiskPoller()
            diskPollerRunning.set(true)
            diskPoller = Thread {
                var dotCount = 0
                try {
                    while (diskPollerRunning.get()) {
                        Thread.sleep(1500)
                        dotCount = (dotCount % 3) + 1
                        val dots = ".".repeat(dotCount)
                        callback.onProgress("${currentPhaseMessage.get()}$dots")
                        callback.onDownloadProgress(
                            "System Setup",
                            currentPercent.get(),
                            currentPercent.get().toLong(),
                            100L
                        )
                    }
                } catch (_: InterruptedException) {
                } finally {
                    diskPollerRunning.set(false)
                }
            }
            diskPoller?.isDaemon = true
            diskPoller?.start()

            // Install script spans 50% → 95% across 8 phases (never hits 100 until Complete).
            val scriptStart = Bootstrap.InstallPhase.RUN_INSTALL_SCRIPT.progress
            val scriptEnd = Bootstrap.InstallPhase.FINALIZE.progress
            val updatePercent: (Int) -> Unit = { phaseNum ->
                val pct = overallProgress(scriptStart, scriptEnd, phaseNum / 8f)
                currentPercent.set(pct)
                callback.onDownloadProgress("System Setup", pct, pct.toLong(), 100L)
            }

            val phaseTrackingLogger: (String) -> Unit = { line ->
                // Detect phase markers from optimized install script (8 phases)
                if (!line.contains("done") && !line.contains("SKIPPED")) {
                    when {
                        line.contains("=== Phase 1:") -> {
                            currentPhaseMessage.set("Phase 1/8: System Update")
                            updatePercent(1)
                            callback.onProgress(currentPhaseMessage.get())
                        }
                        line.contains("=== Phase 2:") -> {
                            currentPhaseMessage.set("Phase 2/8: Installing All Packages")
                            updatePercent(2)
                            callback.onProgress(currentPhaseMessage.get())
                        }
                        line.contains("=== Phase 3:") -> {
                            currentPhaseMessage.set("Phase 3/8: GPU Drivers")
                            updatePercent(3)
                            callback.onProgress(currentPhaseMessage.get())
                        }
                        line.contains("=== Phase 4:") -> {
                            currentPhaseMessage.set("Phase 4/8: Theme")
                            updatePercent(4)
                            callback.onProgress(currentPhaseMessage.get())
                        }
                        line.contains("=== Phase 5:") -> {
                            currentPhaseMessage.set("Phase 5/8: GUI Display")
                            updatePercent(5)
                            callback.onProgress(currentPhaseMessage.get())
                        }
                        line.contains("=== Phase 6:") -> {
                            currentPhaseMessage.set("Phase 6/8: link() Shim")
                            updatePercent(6)
                            callback.onProgress(currentPhaseMessage.get())
                        }
                        line.contains("=== Phase 7:") -> {
                            currentPhaseMessage.set("Phase 7/8: Configs & Caches")
                            updatePercent(7)
                            callback.onProgress(currentPhaseMessage.get())
                        }
                        line.contains("=== Phase 8:") -> {
                            currentPhaseMessage.set("Phase 8/8: User Setup")
                            updatePercent(8) // 95% — finalize still follows
                            callback.onProgress(currentPhaseMessage.get())
                        }
                    }
                }
                if (line.contains("=== Install complete")) {
                    callback.onProgress("✓ Install script completed")
                }
                // Detect checkpoint markers for resume tracking
                if (line.startsWith("CHECKPOINT:")) {
                    Log.d(TAG, "Script checkpoint: ${line.substringAfter("CHECKPOINT:")}")
                }
                
                // Filter out internal tracking lines from UI logs
                if (!line.startsWith("LINUXGO_DISK_USED:") && !line.startsWith("CHECKPOINT:")) {
                    callback.onLogLine(line)
                }
            }

            val runner = ProotRunner(context, phaseTrackingLogger, ProotBinary.Purpose.SETUP)
            activeRunner = runner
            try {
                // Execute the setup script via ProotRunner with LINUXGO_* env vars
                runner.executeSetupScript(rootfsPath, "/root/$scriptName", scriptEnv, extraBinds)
            } finally {
                activeRunner = null
            }

            // Stop disk poller now that script is done
            stopDiskPoller()

            saveCheckpoint(containerId, Bootstrap.InstallPhase.INSTALL_SOFTWARE)

            // Software installation skipped (Barebones GUI)

            saveCheckpoint(containerId, Bootstrap.InstallPhase.FINALIZE)
            callback.onDownloadProgress("Finalizing", Bootstrap.InstallPhase.FINALIZE.progress, 95, 100)
            callback.onProgress("Finalizing desktop launchers...")

            try {
                setupDisplayConfig()
            } catch (e: Exception) {
                // Never leave the card stuck on INSTALLING if only launcher write failed.
                Log.e(TAG, "setupDisplayConfig failed after install script", e)
                callback.onProgress("⚠ Launcher setup warning: ${e.message}")
            }
            // Arch absolute xkb symlink breaks host Lorie/Wayland — fix before first GUI.
            try {
                val xkb = ContainerRestoreEngine.fixAndResolveXkbConfigRoot(rootfs) {
                    callback.onProgress(it)
                }
                if (xkb != null) {
                    callback.onProgress("✓ XKB ready for host compositor: $xkb")
                } else {
                    callback.onProgress("⚠ XKB config not found — GUI may fail until fixed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "XKB fix after install: ${e.message}")
            }
            // Ensure the READY probe (root/launch.sh) exists before we clear the preset
            // and fire onSuccess — otherwise the home card stays PENDING forever.
            val launchScript = File(rootfs, "root/launch.sh")
            if (!launchScript.exists()) {
                Log.w(TAG, "launch.sh missing after setupDisplayConfig — rewriting")
                try {
                    setupDisplayConfig()
                } catch (e: Exception) {
                    Log.e(TAG, "Retry setupDisplayConfig failed", e)
                }
            }
            if (!launchScript.exists()) {
                // Last-resort stub so ContainerManager.isContainerInstalled() returns true
                try {
                    rootDir.mkdirs()
                    launchScript.writeText("#!/bin/sh\nexec /usr/local/bin/pocketlinux-launch \"\$@\"\n")
                    launchScript.setExecutable(true, false)
                    Log.w(TAG, "Wrote minimal launch.sh stub at ${launchScript.absolutePath}")
                    callback.onProgress("✓ Wrote minimal launch.sh")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write launch.sh stub", e)
                }
            }
            clearCheckpoint(containerId)
            val completedContainerId = containerId
            containerPreset = null  // Reset now that all work is complete
            callback.onDownloadProgress("Complete", Bootstrap.InstallPhase.COMPLETE.progress, 100, 100)
            callback.onProgress("✓ Install complete (container $completedContainerId)")
            // Always notify success if the guest install script finished — UI must leave
            // the INSTALLING card state (Arch was hanging here when proot never exited).
            callback.onSuccess()
        } catch (e: Exception) {
            stopDiskPoller()
            Log.e(TAG, "Setup Error", e)
            callback.onError(e.message ?: "Unknown error")
        }
    }
}

/** Calculate directory size in MB (non-recursive walk for speed) */
private fun getDirSizeMB(dir: File): Long {
    var totalSize = 0L
    try {
        val stack = java.util.Stack<File>()
        stack.push(dir)
        var fileCount = 0
        while (stack.isNotEmpty()) {
            val f = stack.pop()
            if (f.isDirectory) {
                f.listFiles()?.forEach { stack.push(it) }
            } else {
                totalSize += f.length()
                fileCount++
                // Sample every 1000 files to avoid spending too long
                if (fileCount > 10000) break
            }
        }
    } catch (_: Exception) {}
    return totalSize / (1024 * 1024)
}

/**
 * Resolve the guest username for [rootfs] so launch scripts never silently
 * fall back to Bootstrap's default (historically "root") when a real user
 * exists in the image / container config.
 */
