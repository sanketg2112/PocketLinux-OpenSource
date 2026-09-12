package com.sg.linuxgo

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors
import com.sg.linuxgo.util.ExtractionUtils

class Bootstrap(internal val context: Context) {
    internal val TAG = "Bootstrap"

    private val downloadOps by lazy {
        com.sg.linuxgo.bootstrap.BootstrapDownloadOps(
            downloadOverallProgress = { filePercent -> downloadOverallProgress(filePercent) },
            downloadBandStart = { downloadBandStart }
        )
    }

    internal fun fileSha256(file: File): String = downloadOps.fileSha256(file)
    internal fun downloadFileMultiThreaded(urlString: String, outputFile: File, callback: BootstrapCallback) =
        downloadOps.downloadFileMultiThreaded(urlString, outputFile, callback)
    internal fun downloadFile(urlString: String, outputFile: File, callback: BootstrapCallback? = null) =
        downloadOps.downloadFile(urlString, outputFile, callback)
    internal val executor = Executors.newSingleThreadExecutor()
    @Volatile internal var activeRunner: ProotRunner? = null

    enum class SetupStep {
        IDLE,
        SELECT_INSTALL_MODE,
        SELECT_DISTRO,
        SELECT_DE,
        SELECT_STYLE,
        SELECT_WM,
        CONFIGURE_USER,
        HARDWARE_ACCEL,
        SELECT_GPU_DRIVER,
        SELECT_OPENGL_BACKEND,
        SELECT_SHELL,
        SELECT_ZSH_THEME,
        SELECT_FONT,
        SELECT_SOFTWARE,
        CHECK_COMPATIBILITY,
        SELECT_REGION,
        SELECT_MIRROR,
        INSTALLING,
        ERROR,
        SUCCESS
    }

    data class Choice(val id: String, val label: String, val description: String = "")

    var installMode: String = "advanced"  // "lightweight" | "heavy" | "advanced"

    var selectedDistro: String = "debian"
        internal set
    var selectedDE: String = "xfce4"
        internal set
    var selectedStyle: String = "0"
        internal set
    var selectedWM: String = "none"
        internal set

    /** Guest identity for terminal/GUI. Prefer non-root; default matches golden images. */
    var username: String = "PocketLinux"
        internal set
    var hardwareAccel: Boolean = false
        get() {
            val cid = containerPreset?.id
            if (cid != null) {
                val prefs = context.getSharedPreferences("container_${cid}_settings", Context.MODE_PRIVATE)
                if (prefs.contains("hw_accel")) {
                    return prefs.getBoolean("hw_accel", false)
                }
                return isHardwareAccelSupported()
            }
            
            val prefs = context.getSharedPreferences("pocket_linux_settings", Context.MODE_PRIVATE)
            return if (prefs.contains("hw_accel")) {
                prefs.getBoolean("hw_accel", false)
            } else {
                field
            }
        }
        internal set
    var selectedGpuDriver: String = "freedreno"
        internal set
    var selectedOpenglBackend: String = "native"
        internal set
    var selectedShell: String = "bash"
        internal set
    var selectedZshTheme: String = "p10k"
        internal set
    var selectedFont: String = "default"
        internal set
    var selectedGuiMode: String = "x11"
        internal set
    var selectedSoftware: List<String> = emptyList()
        internal set
    var selectedMirror: String = "https://dl-cdn.alpinelinux.org/alpine"
        internal set
    var installRecommends: Boolean = false
        internal set
    var selectedRegion: String = "Global"
        internal set
    internal var selectedTimezone: String = "UTC"

    // ── Checkpoint system for resumable installation ──────────────────────────
    // Prebuilt image install (default path) overall progress bands:
    //   Fetch catalog ..........  0 → 3
    //   Download image .........  3 → 70
    //   Verify checksum ........ 70 → 75
    //   Extract / restore ...... 75 → 95
    //   Finalize ............... 95 → 100
    //
    // Legacy live package install (fallback) bands:
    //   Download rootfs ........  0 → 35
    //   Extract rootfs ......... 35 → 48
    //   DNS / prep ............. 48 → 50
    //   Install script (8 ph.) . 50 → 95
    //   Finalize ............... 95 → 99
    //   Complete ............... 100
    enum class InstallPhase(val progress: Int, val label: String) {
        NOT_STARTED(0, "Not started"),
        DOWNLOAD_ROOTFS(0, "Downloading rootfs"),
        EXTRACT_ROOTFS(35, "Extracting rootfs"),
        SETUP_DNS(48, "Configuring DNS"),
        RUN_INSTALL_SCRIPT(50, "Running install script"),
        INSTALL_SOFTWARE(90, "Installing software"),
        FINALIZE(95, "Finalizing"),
        COMPLETE(100, "Complete")
    }

    /**
     * When true (default), install downloads the official prebuilt container image
     * from [ContainerImageCatalog] and restores it — no live package install.
     * Flipped off via Settings → 5× App Version → "Use Legacy Package Install" (debug only).
     * Release builds always use prebuilt container images.
     */
    var usePrebuiltImageInstall: Boolean
        get() {
            if (!FeatureGates.legacyPackageInstallAllowed()) return true
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val legacy = prefs.getBoolean(
                com.sg.linuxgo.ui.sheets.PREF_LEGACY_PACKAGE_INSTALL,
                false
            )
            return !legacy
        }
        set(value) {
            if (!FeatureGates.legacyPackageInstallAllowed()) return
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(
                    com.sg.linuxgo.ui.sheets.PREF_LEGACY_PACKAGE_INSTALL,
                    !value
                )
                .apply()
        }

    /** Map a 0–1 fraction into an overall progress band [start, end]. */
    internal fun overallProgress(start: Int, end: Int, fraction: Float): Int =
        com.sg.linuxgo.bootstrap.overallProgress(start, end, fraction)

    /**
     * Active download progress band for [downloadFile] / [downloadFileMultiThreaded].
     * Prebuilt image install uses 3→70; legacy package install uses 0→35.
     * Set before downloading so notification + container-card bars share one overall %.
     */
    internal var downloadBandStart: Int = InstallPhase.DOWNLOAD_ROOTFS.progress
    internal var downloadBandEnd: Int = InstallPhase.EXTRACT_ROOTFS.progress

    /** Map raw file-download percent (0–100) into the active install-method download band. */
    internal fun downloadOverallProgress(filePercent: Int): Int =
        overallProgress(downloadBandStart, downloadBandEnd, filePercent / 100f)

    internal fun setDownloadProgressBand(start: Int, end: Int) {
        downloadBandStart = start.coerceIn(0, 100)
        downloadBandEnd = end.coerceIn(downloadBandStart, 100)
    }

    internal fun resetDownloadProgressBandToLegacy() {
        setDownloadProgressBand(
            InstallPhase.DOWNLOAD_ROOTFS.progress,
            InstallPhase.EXTRACT_ROOTFS.progress
        )
    }

    private fun getCheckpointPrefs() =
        context.getSharedPreferences("install_checkpoints", Context.MODE_PRIVATE)

    /** Save installation checkpoint so we can resume after interruption */
    fun saveCheckpoint(containerId: String, phase: InstallPhase) {
        getCheckpointPrefs().edit()
            .putString("checkpoint_$containerId", phase.name)
            .apply()
        Log.d(TAG, "Checkpoint saved: $containerId -> ${phase.name}")
    }

    /** Get the last completed phase for a container */
    fun getCheckpoint(containerId: String): InstallPhase {
        val name = getCheckpointPrefs().getString("checkpoint_$containerId", null)
            ?: return InstallPhase.NOT_STARTED
        return try {
            InstallPhase.valueOf(name)
        } catch (_: Exception) {
            InstallPhase.NOT_STARTED
        }
    }

    /** Clear checkpoint after successful installation */
    fun clearCheckpoint(containerId: String) {
        getCheckpointPrefs().edit()
            .remove("checkpoint_$containerId")
            .apply()
    }

    // Detect architecture for correct downloads
    private val ABI = android.os.Build.SUPPORTED_ABIS[0]

    private val REPO_OWNER = "sabamdarif"
    private val REPO_NAME = "termux-desktop"
    private val REPO_BRANCH_MAIN = "main"
    private val REPO_SETUP_FILE_BRANCH = "setup-files"
    private val REPO_SETUP_FILES_FOLDER = "setup-files"
    private val REPO_RAW_URL = "https://raw.githubusercontent.com/sabamdarif/termux-desktop"
    private val MAX_DOWNLOAD_RETRIES = 5
    private val MAX_INSTALL_RETRIES = 5
    private val DOWNLOAD_TIMEOUT = 15

    // PROOT_URL removed: PRoot is now bundled natively bypassing Android W^X.

    internal val ROOTFS_URLS = mapOf(
        "alpine" to when {
            ABI.contains("arm64") || ABI.contains("aarch64") -> "https://github.com/termux/proot-distro/releases/download/v4.30.1/alpine-aarch64-pd-v4.30.1.tar.xz"
            ABI.contains("v7") || ABI.contains("arm") -> "https://github.com/termux/proot-distro/releases/download/v4.30.1/alpine-arm-pd-v4.30.1.tar.xz"
            ABI.contains("x86_64") -> "https://github.com/termux/proot-distro/releases/download/v4.30.1/alpine-x86_64-pd-v4.30.1.tar.xz"
            else -> "https://github.com/termux/proot-distro/releases/download/v4.30.1/alpine-i686-pd-v4.30.1.tar.xz"
        },
        "debian" to "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz",
        "ubuntu" to "https://github.com/termux/proot-distro/releases/download/v4.30.1/ubuntu-questing-aarch64-pd-v4.30.1.tar.xz",
        // Official Kali NetHunter minimal rootfs (top-level kali-arm64/ → strip-components=1)
        "kali" to "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz",
        "archlinux" to "https://github.com/termux/proot-distro/releases/download/v4.34.2/archlinux-aarch64-pd-v4.34.2.tar.xz",
        "fedora" to "https://github.com/termux/proot-distro/releases/download/v4.31.0/fedora-aarch64-pd-v4.31.0.tar.xz",
        "void" to "https://github.com/termux/proot-distro/releases/download/v4.29.0/void-aarch64-pd-v4.29.0.tar.xz",
        "opensuse" to "https://github.com/termux/proot-distro/releases/download/v4.31.0/opensuse-aarch64-pd-v4.31.0.tar.xz",
        "artix" to "https://github.com/termux/proot-distro/releases/download/v4.29.0/artix-aarch64-pd-v4.29.0.tar.xz"
    )

    internal fun getRootfsUrl(): String = ROOTFS_URLS[selectedDistro] ?: ROOTFS_URLS["alpine"]!!

    interface BootstrapCallback {
        fun onProgress(message: String)
        fun onDownloadProgress(fileName: String, progress: Int, current: Long, total: Long)
        fun onChoiceRequired(step: SetupStep, choices: List<Choice>)
        fun onInputRequired(step: SetupStep, title: String, hint: String)
        fun onSuccess()
        fun onError(error: String)
        /** Raw line from a running PRoot/shell command — streamed to the command output log.
         *  Does NOT update the notification or top-level progress status. */
        fun onLogLine(line: String) = onProgress(line) // default: fall back to onProgress
    }

    internal var currentStep = SetupStep.IDLE
    internal val diskPollerRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    internal var diskPoller: Thread? = null

    fun stopDiskPoller() {
        diskPollerRunning.set(false)
        diskPoller?.interrupt()
        diskPoller = null
    }

    fun abort() {
        try {
            executor.shutdownNow()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to shutdown executor", e)
        }
        stopDiskPoller()
        try {
            activeRunner?.destroyActiveProcess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy active runner process", e)
        }
        activeRunner = null
    }

    fun handleChoice(choiceId: String) {
        when (currentStep) {
            SetupStep.SELECT_INSTALL_MODE -> installMode = choiceId
            SetupStep.SELECT_DISTRO      -> selectedDistro = choiceId
            SetupStep.SELECT_DE          -> {
                selectedDE = choiceId
                when (selectedDE) {
                    "lxqt" -> selectedWM = "openbox"
                    "xfce4" -> selectedWM = "xfwm4"
                    "mate" -> selectedWM = "marco"
                    "kde" -> selectedWM = "kwin"
                    else -> selectedWM = "none"
                }
            }
            SetupStep.SELECT_STYLE       -> selectedStyle = choiceId
            SetupStep.SELECT_WM         -> selectedWM = choiceId
            SetupStep.HARDWARE_ACCEL    -> hardwareAccel = choiceId == "yes"
            SetupStep.SELECT_GPU_DRIVER -> selectedGpuDriver = choiceId
            SetupStep.SELECT_OPENGL_BACKEND -> selectedOpenglBackend = choiceId
            SetupStep.SELECT_SHELL      -> selectedShell = choiceId
            SetupStep.SELECT_ZSH_THEME  -> selectedZshTheme = choiceId
            SetupStep.SELECT_FONT       -> selectedFont = choiceId
            SetupStep.SELECT_REGION     -> selectedRegion = choiceId
            SetupStep.SELECT_MIRROR     -> selectedMirror = choiceId
            SetupStep.SELECT_SOFTWARE   -> {
                if (choiceId == "done") {
                    synchronized(this) { (this as java.lang.Object).notifyAll() }
                    return
                }
                if (selectedSoftware.contains(choiceId)) {
                    selectedSoftware = selectedSoftware.filter { it != choiceId }
                } else {
                    selectedSoftware = selectedSoftware + choiceId
                }
                return // Don't notify yet, wait for "done"
            }
            else -> {}
        }
        synchronized(this) { (this as java.lang.Object).notifyAll() }
    }
    
    fun finalizeSoftware() {
        synchronized(this) { (this as java.lang.Object).notifyAll() }
    }

    fun handleInput(input: String) {
        if (currentStep == SetupStep.CONFIGURE_USER) {
            val trimmed = input.trim()
            username = when {
                trimmed.isEmpty() || trimmed == "root" -> "PocketLinux"
                else -> trimmed
            }
        }
        synchronized(this) { (this as java.lang.Object).notifyAll() }
    }

    // ── Presets ──────────────────────────────────────────────────────────────────

    /** Lightweight: minimal Debian + XFCE, bash, no extras, no GPU accel */
    internal fun applyLightweightPreset(callback: BootstrapCallback) {
        selectedDistro       = "debian"
        selectedDE           = "xfce4"
        selectedStyle        = "0"
        selectedWM           = "none"
        username             = "PocketLinux"
        hardwareAccel        = false
        selectedGpuDriver    = "freedreno"
        selectedOpenglBackend= "native"
        selectedShell        = "bash"
        selectedZshTheme     = "p10k"
        selectedFont         = "default"
        selectedGuiMode      = "x11"
        selectedSoftware     = emptyList()
        selectedRegion       = "Global"
        selectedMirror       = "http://deb.debian.org/debian"
        callback.onProgress("All defaults applied — proceeding to install...")
    }

    /** Heavy/Full: Debian + XFCE style 1, zsh+p10k, Meslo Nerd Font, GPU accel, common apps */
    internal fun applyHeavyPreset(callback: BootstrapCallback) {
        selectedDistro       = "debian"
        selectedDE           = "xfce4"
        selectedStyle        = "1"
        selectedWM           = "none"
        username             = "PocketLinux"
        hardwareAccel        = true
        selectedGpuDriver    = "freedreno"
        selectedOpenglBackend= "native"
        selectedShell        = "zsh"
        selectedZshTheme     = "p10k"
        selectedFont         = "meslo"
        selectedGuiMode      = "x11"
        selectedSoftware     = listOf("chromium", "vlc", "geany", "neovim")
        selectedRegion       = "Global"
        selectedMirror       = "http://deb.debian.org/debian"
        callback.onProgress("[Heavy] Full preset applied — proceeding to install...")
    }

    /**
     * Pre-fill all wizard selections from a ContainerConfig.
     * Called BEFORE startInstallation() so the bootstrap skips the interactive
     * wizard entirely and installs exactly what the user chose in the
     * New Container bottom-sheet wizard.
     */
    internal var containerPreset: ContainerConfig? = null
        internal set

    fun applyContainerPreset(config: ContainerConfig) {
        containerPreset     = config
        selectedDistro      = config.distro
        selectedDE          = config.de
        selectedWM          = config.wm
        selectedSoftware    = config.software
        // Install/bind HW drivers when user picked a HW backend, or Auto on Vulkan devices.
        // "auto" alone used to force software and left glmark2 stuck on llvmpipe/zink.
        hardwareAccel = wantsHardwareGpuDrivers(getConfiguredGpuDriverMode())
        // Sensible defaults for the remaining settings
        installMode         = "lightweight"   // skip advanced wizard
        selectedStyle       = "0"
        // Keep non-root identities; empty/"root" from old defaults becomes PocketLinux
        // until setupDisplayConfig re-resolves from the real rootfs.
        username            = config.username.trim().takeIf { it.isNotEmpty() && it != "root" }
            ?: "PocketLinux"
        selectedShell       = "bash"
        selectedZshTheme    = "p10k"
        selectedFont        = "default"
        // Release never runs Wayland even if container prefs still say wayland.
        selectedGuiMode     = FeatureGates.effectiveGuiMode(context, config.guiMode)
        selectedRegion      = "Global"
        selectedMirror      = when (selectedDistro) {
            "debian" -> "http://deb.debian.org/debian"
            "ubuntu" -> "http://ports.ubuntu.com/ubuntu-ports"
            "kali" -> "http://http.kali.org/kali"
            "archlinux" -> ArchPacmanSecurity.DEFAULT_MIRROR_BASE
            "artix" -> ArchPacmanSecurity.ARTIX_DEFAULT_MIRROR_BASE
            "fedora" -> "https://mirrors.fedoraproject.org"
            "void" -> VoidXbpsRepos.DEFAULT_REPO
            "opensuse" -> "https://download.opensuse.org"
            else -> "https://dl-cdn.alpinelinux.org/alpine"
        }
    }

    fun overrideGuiMode(guiMode: String) {
        selectedGuiMode = FeatureGates.effectiveGuiMode(context, guiMode)
    }

    fun getConfiguredGpuDriverMode(rootfsHint: File? = null): String {
        val presetId = containerPreset?.id
            ?: rootfsHint?.let { containerIdFromRootfsPath(it) }
        val prefsName = if (presetId != null) "container_${presetId}_settings" else "pocket_linux_settings"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val raw = prefs.getString("gpu_driver_mode", "auto") ?: "auto"
        return com.sg.linuxgo.bootstrap.normalizeGpuDriverModeId(raw)
    }

    /** .../files/containers/<id>/rootfs → container id, else null. */
    private fun containerIdFromRootfsPath(rootfs: File): String? {
        val name = rootfs.name
        val parent = rootfs.parentFile ?: return null
        return if (name == "rootfs" && parent.parentFile?.name == "containers") {
            parent.name
        } else {
            null
        }
    }

    /**
     * Whether install scripts should pull Mesa/Turnip overlays and proot should bind GPU nodes.
     * Explicit llvmpipe / auto → no (auto is software). Explicit Freedreno/Zink/Panfrost →
     * yes when the device supports accel.
     */
    fun wantsHardwareGpuDrivers(configuredMode: String = getConfiguredGpuDriverMode()): Boolean {
        if (!isHardwareAccelSupported()) return false
        return when (configuredMode.trim()) {
            "llvmpipe", "auto", "" -> false
            else -> com.sg.linuxgo.bootstrap.isHardwareGpuDriverMode(configuredMode)
        }
    }

    /** Expand prefs mode ("auto" → llvmpipe). Explicit HW modes pass through. */
    fun resolveGpuDriverModeForRootfs(
        rootfs: File = getRootfsDir(),
        configuredMode: String = getConfiguredGpuDriverMode(rootfs)
    ): String = com.sg.linuxgo.bootstrap.resolveGpuDriverMode(configuredMode, rootfs)

    fun startInstallation(callback: BootstrapCallback) {
        executeStartInstallation(callback)
    }

    internal fun fetchStylesForDE(de: String): List<Choice> {
        val styles = mutableListOf(Choice("0", "Stock (Default)"))
        try {
            // In a real app, you would fetch the .md file and parse it.
            // For now, we provide common styles from the repository.
            when (de) {
                "xfce4" -> {
                    styles.add(Choice("1", "XFCE Style 1 (Classic)"))
                    styles.add(Choice("2", "XFCE Style 2 (Modern)"))
                }
                "lxqt" -> {
                    styles.add(Choice("1", "LXQt Style 1"))
                    styles.add(Choice("2", "LXQt Style 2"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch styles", e)
        }
        return styles
    }

    fun isHardwareAccelSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 1)
    }

    internal fun checkDeviceRequirements(): String {
        val usableSpace = context.filesDir.usableSpace / (1024 * 1024 * 1024)
        val totalSpace = context.filesDir.totalSpace / (1024 * 1024 * 1024)
        
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val availRam = memInfo.availMem / (1024 * 1024)
        val totalRam = memInfo.totalMem / (1024 * 1024)
        
        val kernel = System.getProperty("os.version") ?: "Unknown"
        val arch = android.os.Build.SUPPORTED_ABIS[0]
        val androidVer = android.os.Build.VERSION.RELEASE
        
        val status = StringBuilder()
        status.append("\n--------------------------------\n")
        status.append("Pocket Linux System Info\n")
        status.append("--------------------------------\n")
        status.append("OS: Android $androidVer\n")
        status.append("Kernel: $kernel\n")
        status.append("Arch: $arch\n")
        status.append("CPU: $arch (Compatible)\n")
        status.append("RAM: ${availRam}MB / ${totalRam}MB\n")
        status.append("Disk: ${usableSpace}GB / ${totalSpace}GB\n")
        status.append("--------------------------------")
        
        if (usableSpace < 2) {
            throw Exception("Low Storage: You need at least 2GB free. (Current: ${usableSpace}GB)")
        }
        
        return status.toString()
    }

    internal fun checkStorageSpace() {
        val usableSpace = context.filesDir.usableSpace
        val requiredSpace = 1024L * 1024L * 1024L // 1GB
        if (usableSpace < requiredSpace) {
            throw Exception("Insufficient storage space. Required: 1GB, Available: ${usableSpace / (1024 * 1024)}MB")
        }
    }

    internal fun waitForInput() {
        synchronized(this) { (this as java.lang.Object).wait() }
    }

    /** Resolve the rootfs directory — container-specific path if a preset is active */

    internal suspend fun runProotCommand(command: String, callback: BootstrapCallback): Int {
        val rootfsPath = getRootfsDir().absolutePath
        val runner = ProotRunner(context, { callback.onProgress(it) }, ProotBinary.Purpose.SETUP)
        activeRunner = runner
        try {
            return runner.executeCommand(rootfsPath, command)
        } finally {
            activeRunner = null
        }
    }

    internal suspend fun runProotCommandOutput(command: String, callback: BootstrapCallback): String {
        val rootfsPath = getRootfsDir().absolutePath
        val runner = ProotRunner(context, { /* No-op */ }, ProotBinary.Purpose.SETUP)
        activeRunner = runner
        try {
            return runner.runWithOutput(rootfsPath, command)
        } finally {
            activeRunner = null
        }
    }

    internal fun getRootfsDir(): File {
        val preset = containerPreset
        return if (preset != null) {
            File(context.filesDir, "containers/${preset.id}/rootfs")
        } else {
            File(context.filesDir, "rootfs")
        }
    }

    internal fun performInstall(callback: BootstrapCallback) {
        try {
            val bundledProot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
            val rootfsDir = getRootfsDir()
            if (!rootfsDir.exists()) rootfsDir.mkdirs()

            // Detect Timezone (Alignment with distro-container-setup: set_distro_time_zone)
            selectedTimezone = try {
                val p = Runtime.getRuntime().exec(arrayOf("getprop", "persist.sys.timezone"))
                p.inputStream.bufferedReader().readText().trim()
            } catch (e: Exception) { "UTC" }
            callback.onProgress("System context: Timezone set to $selectedTimezone")
            Log.d(TAG, "PRoot integrated via: ${bundledProot.absolutePath}")

            val toyboxFile = File(context.applicationInfo.nativeLibraryDir, "libtoybox.so")
            Log.d(TAG, "Using bundled Toybox: " + toyboxFile.absolutePath)
            Log.d(TAG, "Toybox exists: " + toyboxFile.exists())
            Log.d(TAG, "Toybox executable: " + toyboxFile.canExecute())

            val containerId = containerPreset?.id ?: "legacy"

            // ── Default path: official prebuilt container image ──
            if (usePrebuiltImageInstall || !FeatureGates.legacyPackageInstallAllowed()) {
                performPrebuiltImageInstall(callback, rootfsDir, containerId)
                return
            }

            performLegacyPackageInstall(callback, rootfsDir, containerId)
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap failed", e)
            callback.onError(e.message ?: "Unknown error")
        }
    }

    /**
     * Download the golden container image from the public GitHub catalog and
     * restore it into [rootfsDir]. User-facing UX is a single progress bar.
     */
    fun setupDNS(rootfs: File) {
        val etc = File(rootfs, "etc")
        if (!etc.exists()) etc.mkdirs()
        
        // 1. resolv.conf for internet access
        val resolvConf = File(etc, "resolv.conf")
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        
        // 2. hosts for localhost resolution (CRITICAL for websockify on Ubuntu/Debian)
        val hosts = File(etc, "hosts")
        hosts.writeText("127.0.0.1   localhost\n::1         localhost\n")
    }

    /**
     * @param onGpuLog optional sink for overlay progress (GUI log). Defaults to logcat.
     * @return resolved GPU mode after overlay attempt (may still be HW mode even if
     *   overlay failed — callers should check [com.sg.linuxgo.bootstrap.rootfsHasKgslDri]).
     */
    fun setupGpuEnvConfig(
        rootfs: File = getRootfsDir(),
        configuredMode: String = getConfiguredGpuDriverMode(rootfs),
        onGpuLog: ((String) -> Unit)? = null
    ): String {
        val etc = File(rootfs, "etc")
        if (!etc.exists()) etc.mkdirs()
        // Resolve "auto" → llvmpipe (software). Explicit HW modes pass through.
        // Normalize legacy "freedreno"/"zink" ids so they never fall through to software
        // while leaving a prior Zink bake-in active.
        val gpuDriverMode = resolveGpuDriverModeForRootfs(rootfs, configuredMode)
        val logGpu: (String) -> Unit = { msg ->
            Log.i(TAG, msg)
            try {
                onGpuLog?.invoke(msg)
            } catch (_: Exception) {
            }
        }

        // Kali/Alpine/Debian golden images lack real Android kgsl_dri.so (only libdril
        // stubs). Pull lfdevs Mesa when user picks Adreno HW.
        // Skip network on main thread (settings UI) — GUI launch does this on a worker.
        if ((gpuDriverMode == "adreno_freedreno" || gpuDriverMode == "adreno_zink") &&
            android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
        ) {
            try {
                com.sg.linuxgo.bootstrap.ensureHardwareGpuMesaOverlay(
                    rootfs,
                    gpuDriverMode,
                    onLog = logGpu
                )
            } catch (e: Exception) {
                Log.w(TAG, "ensureHardwareGpuMesaOverlay: ${e.message}")
                logGpu("! GPU: Mesa overlay exception: ${e.message}")
            }
        } else if (gpuDriverMode == "adreno_freedreno" || gpuDriverMode == "adreno_zink") {
            logGpu("GPU: skip overlay download on main thread (will run at desktop start)")
        }

        val staleGpuEnv = Regex(
            """^\s*(export\s+)?(MESA_LOADER_DRIVER_OVERRIDE|GALLIUM_DRIVER|TU_DEBUG|VK_ICD_FILENAMES|MESA_DEBUG|PROOT_L_MT|ZINK_DESCRIPTORS|ZINK_DEBUG|MESA_EXTENSION_OVERRIDE|MESA_VK_WSI_PRESENT_MODE|LIBGL_ALWAYS_SOFTWARE|MESA_SHADER_CACHE_DISABLE|MESA_SHADER_CACHE_MAX_SIZE|LIBGL_DRIVERS_PATH)\s*=.*$"""
        )

        fun scrubEnvFile(file: File) {
            if (!file.exists() || !file.isFile) return
            val cleaned = file.readLines()
                .filterNot { line ->
                    val trimmed = line.trim().trimEnd('\r')
                    staleGpuEnv.matches(trimmed) ||
                        trimmed.contains("GL_EXT_shader_texture_lod") ||
                        // Residual empty assignments also break Freedreno (Mesa treats as set).
                        trimmed.matches(Regex("""^(export\s+)?(GALLIUM_DRIVER|ZINK_DESCRIPTORS|ZINK_DEBUG)\s*=\s*$"""))
                }
            file.writeText(cleaned.joinToString("\n") + "\n")
        }

        val environmentFile = File(etc, "environment")
        scrubEnvFile(environmentFile)
        environmentFile.appendText(
            gpuEnvironmentFileContent(gpuDriverMode)
        )

        // Scrub systemd/user environment.d drop-ins that may re-export GALLIUM_DRIVER=zink.
        listOf(
            File(etc, "environment.d"),
            File(rootfs, "usr/lib/environment.d"),
            File(rootfs, "root/.config/environment.d")
        ).forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".conf") }?.forEach { conf ->
                scrubEnvFile(conf)
            }
        }
        // Home users' environment.d
        File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.forEach { home ->
            val envd = File(home, ".config/environment.d")
            if (envd.isDirectory) {
                envd.listFiles()?.filter { it.isFile && it.name.endsWith(".conf") }?.forEach { conf ->
                    scrubEnvFile(conf)
                }
            }
            scrubEnvFile(File(home, ".xsessionrc"))
            scrubEnvFile(File(home, ".xprofile"))
        }
        scrubEnvFile(File(rootfs, "root/.xsessionrc"))
        scrubEnvFile(File(rootfs, "root/.xprofile"))

        val profileDir = File(etc, "profile.d")
        if (!profileDir.exists()) profileDir.mkdirs()
        // Drop legacy names that could re-export Zink after pocketlinux-gpu.sh.
        File(profileDir, "linuxgo-gpu.sh").delete()
        // Alphabetically after pocketlinux-gpu.sh would re-apply zink — scrub known bad names.
        profileDir.listFiles()?.filter { it.isFile && it.name.endsWith(".sh") }?.forEach { sh ->
            if (sh.name == "pocketlinux-gpu.sh") return@forEach
            try {
                val t = sh.readText()
                if (t.contains("GALLIUM_DRIVER=zink") || t.contains("MESA_LOADER_DRIVER_OVERRIDE=zink")) {
                    scrubEnvFile(sh)
                    // If the file was only GPU exports, remove it entirely after scrub if emptyish.
                    val left = sh.readText().trim()
                    if (left.isEmpty() || left.lines().all { it.isBlank() || it.trimStart().startsWith("#") }) {
                        sh.delete()
                    }
                }
            } catch (_: Exception) {
            }
        }
        val profileScript = File(profileDir, "pocketlinux-gpu.sh")
        profileScript.writeText(gpuProfileScript(gpuDriverMode))

        val sourceLine = "[ -r /etc/profile.d/pocketlinux-gpu.sh ] && . /etc/profile.d/pocketlinux-gpu.sh"
        // Prefer real home dirs; always include root and ensure it exists (Crash C).
        val homeDirs = linkedSetOf<File>()
        val rootHome = File(rootfs, "root")
        try {
            if (!rootHome.isDirectory) rootHome.mkdirs()
        } catch (_: Exception) {
        }
        if (rootHome.isDirectory) homeDirs.add(rootHome)
        val usersDir = File(rootfs, "home")
        usersDir.listFiles()?.filterTo(homeDirs) { it.isDirectory }
        for (home in homeDirs) {
            val bashrc = File(home, ".bashrc")
            if (bashrc.isFile) scrubEnvFile(bashrc)
            val current = com.sg.linuxgo.util.GuestShellFiles.readTextOrEmpty(bashrc)
                .replace(
                    Regex("""(?m)^[ \t]*\[[ \t]*-r[ \t]+/etc/profile\.d/linuxgo-gpu\.sh[ \t]*][ \t]*&&[ \t]*\.[ \t]+/etc/profile\.d/linuxgo-gpu\.sh[ \t]*$"""),
                    ""
                )
            if (!current.contains(sourceLine)) {
                com.sg.linuxgo.util.GuestShellFiles.writeTextSafe(
                    bashrc,
                    current.trimEnd() + "\n# PocketLinux GPU defaults\n$sourceLine\n"
                )
            } else if (bashrc.isFile) {
                com.sg.linuxgo.util.GuestShellFiles.writeTextSafe(bashrc, current)
            }

            val profile = File(home, ".profile")
            if (profile.isFile) scrubEnvFile(profile)
            val profileCurrent = com.sg.linuxgo.util.GuestShellFiles.readTextOrEmpty(profile)
                .replace(
                    Regex("""(?m)^[ \t]*\[[ \t]*-r[ \t]+/etc/profile\.d/linuxgo-gpu\.sh[ \t]*][ \t]*&&[ \t]*\.[ \t]+/etc/profile\.d/linuxgo-gpu\.sh[ \t]*$"""),
                    ""
                )
            if (!profileCurrent.contains(sourceLine)) {
                com.sg.linuxgo.util.GuestShellFiles.writeTextSafe(
                    profile,
                    profileCurrent.trimEnd() + "\n# PocketLinux GPU defaults\n$sourceLine\n"
                )
            } else if (profile.isFile) {
                com.sg.linuxgo.util.GuestShellFiles.writeTextSafe(profile, profileCurrent)
            }
        }

        // Do NOT stamp gpu_driver_mode here. Terminal/GUI "cheap path" only calls this
        // helper; stamping would make prepareGuiRootfs skip rewriting pocketlinux-launch
        // and leave already-installed containers stuck on a baked Zink/llvmpipe launch.
        // setupDisplayConfig stamps after the full launch script rewrite.
        return gpuDriverMode
    }

    private fun gpuEnvironmentFileContent(driverMode: String): String =
        com.sg.linuxgo.bootstrap.gpuEnvironmentFileContent(driverMode)

    private fun gpuProfileScript(driverMode: String): String =
        com.sg.linuxgo.bootstrap.gpuProfileScript(driverMode)

    fun setupGUI(callback: BootstrapCallback) {
        executeSetupGUI(callback)
    }

    fun resolveUsernameForRootfs(rootfs: File): String =
        executeResolveUsernameForRootfs(rootfs)

    fun setupDisplayConfig(rootfsOverride: File? = null) {
        executeSetupDisplayConfig(rootfsOverride)
    }

    private val extractionUtils = ExtractionUtils(context)

    internal fun extractTarGz(tarGzFile: File, outputDir: File, callback: BootstrapCallback? = null) {
        extractionUtils.extractTarGz(tarGzFile, outputDir) { count ->
            reportExtractProgress(count, callback)
        }
    }

    internal fun extractTarXz(tarXzFile: File, outputDir: File, callback: BootstrapCallback? = null) {
        callback?.onProgress("XZ extraction (using Toybox)...")
        extractionUtils.extractTarXz(tarXzFile, outputDir) { count ->
            reportExtractProgress(count, callback)
        }
    }

    /** Soft overall progress during extract (35% → just under 48%). */
    private fun reportExtractProgress(count: Int, callback: BootstrapCallback?) {
        // Typical rootfs archives extract several thousand entries; cap below SETUP_DNS.
        val estimatedEntries = 6000f
        val fraction = (count / estimatedEntries).coerceIn(0f, 0.95f)
        val overall = overallProgress(
            InstallPhase.EXTRACT_ROOTFS.progress,
            InstallPhase.SETUP_DNS.progress,
            fraction
        )
        callback?.onDownloadProgress("Extracting rootfs", overall, count.toLong(), estimatedEntries.toLong())
        callback?.onProgress("Extracted $count files...")
    }
}
