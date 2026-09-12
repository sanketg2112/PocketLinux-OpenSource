package com.sg.linuxgo

import android.content.Context
import android.util.Log
import java.net.URL
import java.util.concurrent.Executors

/**
 * Interactive install wizard + mirror benchmarking for [Bootstrap].
 */
internal fun Bootstrap.executeStartInstallation(callback: Bootstrap.BootstrapCallback) {
    executor.execute {
        try {
            // ── If container preset was applied (from New Container wizard), skip wizard ──
            if (containerPreset != null) {
                callback.onProgress("Container preset applied: ${selectedDistro} / ${selectedDE}")
                currentStep = Bootstrap.SetupStep.CHECK_COMPATIBILITY
                val requirements = checkDeviceRequirements()
                callback.onProgress(requirements)
                currentStep = Bootstrap.SetupStep.INSTALLING
                performInstall(callback)
                // NOTE: containerPreset is cleared in setupGUI() AFTER async work completes
                return@execute
            }

            // ── Step 0: Install mode picker ──────────────────────────────────
            currentStep = Bootstrap.SetupStep.SELECT_INSTALL_MODE
            callback.onChoiceRequired(currentStep, listOf(
                Bootstrap.Choice("lightweight", "Start Installation",  "Proceed with PocketLinux installation")
            ))
            waitForInput()

            when (installMode) {
                "lightweight" -> {
                    applyLightweightPreset(callback)
                    // Skip wizard — jump directly to compat check + install
                    currentStep = Bootstrap.SetupStep.CHECK_COMPATIBILITY
                    val requirements = checkDeviceRequirements()
                    callback.onProgress(requirements)
                    currentStep = Bootstrap.SetupStep.INSTALLING
                    performInstall(callback)
                    return@execute
                }
                "heavy" -> {
                    applyHeavyPreset(callback)
                    currentStep = Bootstrap.SetupStep.CHECK_COMPATIBILITY
                    val requirements = checkDeviceRequirements()
                    callback.onProgress(requirements)
                    currentStep = Bootstrap.SetupStep.INSTALLING
                    performInstall(callback)
                    return@execute
                }
                // "advanced" falls through to the full wizard below
            }

            // ── Advanced wizard ──────────────────────────────────────────────

            // 1. Distro Selection
            currentStep = Bootstrap.SetupStep.SELECT_DISTRO
            val distroChoices = listOf(
                Bootstrap.Choice("debian", "Debian (Stable / Large Package Base)"),
                Bootstrap.Choice("kali", "Kali Linux (Rolling / Security)"),
                Bootstrap.Choice("alpine", "Alpine"),
                Bootstrap.Choice("archlinux", "Arch Linux"),
                Bootstrap.Choice("ubuntu", "Ubuntu")
            )
            callback.onChoiceRequired(currentStep, distroChoices)
            waitForInput()

            // 2. Desktop Environment
            currentStep = Bootstrap.SetupStep.SELECT_DE
            val deChoices = listOf(
                Bootstrap.Choice("xfce4", "XFCE (Fast/Standard)"),
                Bootstrap.Choice("mate", "MATE (Classic)"),
                Bootstrap.Choice("lxqt", "LXQt (Very Lightweight)"),
                Bootstrap.Choice("kde", "KDE Plasma (Modern/Feature-Rich)"),
                Bootstrap.Choice("none", "No Desktop (Window Manager Only)")
            )
            callback.onChoiceRequired(currentStep, deChoices)
            waitForInput()

            if (selectedDE != "none") {
                currentStep = Bootstrap.SetupStep.SELECT_STYLE
                val styleChoices = fetchStylesForDE(selectedDE)
                callback.onChoiceRequired(currentStep, styleChoices)
                waitForInput()
            } else {
                currentStep = Bootstrap.SetupStep.SELECT_WM
                val wmChoices = listOf(
                    Bootstrap.Choice("openbox", "Openbox"),
                    Bootstrap.Choice("i3", "i3WM"),
                    Bootstrap.Choice("awesome", "Awesome"),
                    Bootstrap.Choice("bspwm", "Bspwm")
                )
                callback.onChoiceRequired(currentStep, wmChoices)
                waitForInput()
            }

            // 3. Configure User
            currentStep = Bootstrap.SetupStep.CONFIGURE_USER
            callback.onInputRequired(currentStep, "Configure User", "Enter username (default: PocketLinux)")
            waitForInput()
            if (username.isBlank() || username == "root") {
                username = "PocketLinux"
            }

            // Auto-detect Hardware Accel
            hardwareAccel = isHardwareAccelSupported()
            if (hardwareAccel) {
                selectedGpuDriver = "freedreno"
                selectedOpenglBackend = "native"
                callback.onProgress("✓ GPU Hardware Acceleration (Freedreno) supported & enabled")
            } else {
                callback.onProgress("⚠ Hardware Acceleration not supported, using Software Rendering")
            }

            // 4. Shell Selection
            currentStep = Bootstrap.SetupStep.SELECT_SHELL
            val shellChoices = listOf(
                Bootstrap.Choice("zsh", "Zsh + Oh-My-Zsh"),
                Bootstrap.Choice("bash", "Bash (Custom Prompt)"),
                Bootstrap.Choice("bash_ble", "Bash + ble.sh (Enhanced)"),
                Bootstrap.Choice("linuxgo_bash", "LinuxGo Default")
            )
            callback.onChoiceRequired(currentStep, shellChoices)
            waitForInput()

            if (selectedShell == "zsh") {
                currentStep = Bootstrap.SetupStep.SELECT_ZSH_THEME
                val zshChoices = listOf(
                    Bootstrap.Choice("p10k", "Powerlevel10k"),
                    Bootstrap.Choice("pure", "Pure"),
                    Bootstrap.Choice("robbyrussell", "Robbyrussell (Default)")
                )
                callback.onChoiceRequired(currentStep, zshChoices)
                waitForInput()
            }

            // 5. Font Selection
            currentStep = Bootstrap.SetupStep.SELECT_FONT
            val fontChoices = listOf(
                Bootstrap.Choice("meslo", "Meslo Nerd Font"),
                Bootstrap.Choice("default", "System Default"),
                Bootstrap.Choice("fira_code", "FiraCode Nerd Font"),
                Bootstrap.Choice("jetbrains_mono", "JetBrainsMono Nerd Font")
            )
            callback.onChoiceRequired(currentStep, fontChoices)
            waitForInput()



            // 7. Additional Software (Skipped - Barebones GUI)

            // 8. Check Storage & Compatibility
            currentStep = Bootstrap.SetupStep.CHECK_COMPATIBILITY
            val requirements = checkDeviceRequirements()
            callback.onChoiceRequired(currentStep, listOf(Bootstrap.Choice("proceed", "Proceed to Installation", requirements)))
            waitForInput()

            // 9. Select Region
            currentStep = Bootstrap.SetupStep.SELECT_REGION
            val regions = listOf(
                Bootstrap.Choice("Asia", "Asia"),
                Bootstrap.Choice("Europe", "Europe"),
                Bootstrap.Choice("NA", "North America"),
                Bootstrap.Choice("Global", "Global (Fastest)")
            )
            callback.onChoiceRequired(currentStep, regions)
            waitForInput()

            // 10. Auto-Benchmark & Select Mirror
            currentStep = Bootstrap.SetupStep.SELECT_MIRROR
            callback.onProgress("Benchmarking mirrors in $selectedRegion...")
            val mirrors = benchmarkMirrors(selectedRegion)
            val fastest = mirrors.firstOrNull { it.id != "https://dl-cdn.alpinelinux.org/alpine" } ?: mirrors.first()
            selectedMirror = fastest.id
            callback.onProgress("Fastest mirror found: ${fastest.label}")
            Thread.sleep(1000)

            // 11. Installer Phase
            currentStep = Bootstrap.SetupStep.INSTALLING
            performInstall(callback)

        } catch (e: Exception) {
            Log.e(TAG, "Process failed", e)
            callback.onError(e.message ?: "Unknown error")
        }
    }
}

internal fun Bootstrap.benchmarkMirrors(region: String): List<Bootstrap.Choice> {
    val mirrorsToTest = when (selectedDistro) {
        "ubuntu" -> UBUNTU_MIRRORS
        "debian" -> DEBIAN_MIRRORS
        "kali" -> KALI_MIRRORS
        "archlinux" -> ARCH_MIRRORS
        else -> when (region) {
            "Asia" -> ASIA_MIRRORS
            "Europe" -> EUROPE_MIRRORS
            "NA" -> NA_MIRRORS
            "Oceania" -> OCEANIA_MIRRORS
            "Africa" -> AFRICA_MIRRORS
            "SA" -> SA_MIRRORS
            else -> ALL_TOP_MIRRORS
        }
    }

    val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    val testUrlPath = when (selectedDistro) {
        "ubuntu" -> "/dists/noble/main/binary-arm64/Packages.gz"
        "debian" -> "/dists/trixie/main/binary-arm64/Packages.gz"
        "kali" -> "/dists/kali-rolling/main/binary-arm64/Packages.gz"
        "archlinux" -> "/aarch64/core/core.db"
        else -> "/v3.20/main/aarch64/APKINDEX.tar.gz"
    }

    val benchmarkExecutor = Executors.newFixedThreadPool(8)
    val futures = mirrorsToTest.map { mirror ->
        benchmarkExecutor.submit {
            try {
                val start = System.currentTimeMillis()
                val conn = URL(mirror + testUrlPath).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.connect()
                if (conn.responseCode == 200) {
                    val duration = System.currentTimeMillis() - start
                    results.add(mirror to duration)
                }
            } catch (e: Exception) {
                // Ignore failures
            }
        }
    }
    
    // Wait up to 5 seconds for all tests to finish
    try {
        futures.forEach { try { it.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {} }
    } finally {
        benchmarkExecutor.shutdownNow()
    }
    
    val sorted = results.sortedBy { it.second }
    val defaultMirror = when (selectedDistro) {
        "ubuntu" -> "http://ports.ubuntu.com/ubuntu-ports"
        "debian" -> "http://deb.debian.org/debian"
        "kali" -> "http://http.kali.org/kali"
        "archlinux" -> ArchPacmanSecurity.DEFAULT_MIRROR_BASE
        else -> "https://dl-cdn.alpinelinux.org/alpine"
    }
    val defaultLabel = when (selectedDistro) {
        "ubuntu" -> "Ubuntu Ports (Global)"
        "debian" -> "Debian Main (Global)"
        "kali" -> "Kali Rolling (Global)"
        "archlinux" -> "Arch Linux ARM (Global)"
        else -> "Default (Global CDN)"
    }

    return sorted.map { (url, duration) -> 
        val label = url.removePrefix("https://").removePrefix("http://").split("/")[0]
        Bootstrap.Choice(url, label, "Response time: ${duration}ms")
    } + Bootstrap.Choice(defaultMirror, defaultLabel, "Fallback if tests fail")
}

private val UBUNTU_MIRRORS = listOf(
    "http://ports.ubuntu.com/ubuntu-ports",
    "http://mirrors.ustc.edu.cn/ubuntu-ports",
    "http://mirror.nju.edu.cn/ubuntu-ports",
    "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
)

private val DEBIAN_MIRRORS = listOf(
    "http://deb.debian.org/debian",
    "http://ftp.debian.org/debian",
    "http://mirrors.ustc.edu.cn/debian",
    "http://mirror.nju.edu.cn/debian"
)

private val KALI_MIRRORS = listOf(
    "http://http.kali.org/kali",
    "https://kali.download/kali",
    "http://mirrors.ocf.berkeley.edu/kali",
    "http://mirrors.ustc.edu.cn/kali",
    "http://mirrors.tuna.tsinghua.edu.cn/kali"
)

private val ARCH_MIRRORS = ArchPacmanSecurity.WIZARD_MIRROR_BASES

private val ASIA_MIRRORS = listOf(
    "https://mirror.nju.edu.cn/alpine",
    "https://mirrors.tuna.tsinghua.edu.cn/alpine",
    "https://mirrors.ustc.edu.cn/alpine",
    "https://mirror.aliyun.com/alpine",
    "https://ftp.kaist.ac.kr/alpine",
    "https://mirror.kuntau.com/alpine"
)

private val EUROPE_MIRRORS = listOf(
    "https://uk.alpinelinux.org/alpine",
    "https://mirror.leaseweb.com/alpine",
    "https://ftp.halifax.rwth-aachen.de/alpine",
    "https://mirrors.dotsrc.org/alpine"
)

private val NA_MIRRORS = listOf(
    "https://mirrors.edge.kernel.org/alpine",
    "https://dl-cdn.alpinelinux.org/alpine",
    "https://mirror.math.princeton.edu/pub/alpinelinux"
)

private val OCEANIA_MIRRORS = listOf(
    "https://mirror.intergrid.com.au/alpine",
    "https://mirror.aarnet.edu.au/pub/alpine"
)

private val AFRICA_MIRRORS = listOf(
    "https://mirror.marwan.ma/alpine",
    "https://alpine.mirror.liquidtelecom.com"
)

private val SA_MIRRORS = listOf(
    "https://mirror.pinguino.com.ar/alpine",
    "https://mirrors.uplo.cl/alpine"
)

private val ALL_TOP_MIRRORS = ASIA_MIRRORS.take(2) + EUROPE_MIRRORS.take(2) + NA_MIRRORS.take(2)
