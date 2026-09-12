package com.sg.linuxgo.ui.scrcpy

/**
 * Static copy and commands for the in-app scrcpy / big-screen setup guide.
 * Walks users from ADB drivers → install scrcpy → connect PocketLinux.
 * Official client: https://github.com/Genymobile/scrcpy
 */
object ScrcpySetupGuide {

    /** Application id for this build (debug uses `.debug` suffix). */
    val PACKAGE_ID: String get() = com.sg.linuxgo.BuildConfig.APPLICATION_ID
    const val OFFICIAL_REPO = "https://github.com/Genymobile/scrcpy"
    const val DOC_LINUX = "https://github.com/Genymobile/scrcpy/blob/master/doc/linux.md"
    const val DOC_WINDOWS = "https://github.com/Genymobile/scrcpy/blob/master/doc/windows.md"
    const val DOC_MACOS = "https://github.com/Genymobile/scrcpy/blob/master/doc/macos.md"
    const val DOC_CONNECTION = "https://github.com/Genymobile/scrcpy/blob/master/doc/connection.md"
    const val PLATFORM_TOOLS =
        "https://developer.android.com/tools/releases/platform-tools"

    /** Virtual display + start PocketLinux — best first-class experience. */
    val CMD_VIRTUAL_DISPLAY =
        "scrcpy --new-display=1920x1080 --start-app=$PACKAGE_ID --flex-display --keep-active"

    val CMD_VIRTUAL_DISPLAY_WIFI =
        "scrcpy --tcpip --new-display=1920x1080 --start-app=$PACKAGE_ID --flex-display --keep-active"

    val CMD_SIMPLE = "scrcpy"

    val CMD_ADB_DEVICES = "adb devices"

    val CMD_ADB_TCPIP = "adb tcpip 5555"

    fun cmdAdbConnect(ip: String): String =
        "adb connect ${ip.trim()}:5555"

    data class GuideCommand(val label: String, val command: String)

    data class GuideStep(
        val title: String,
        val body: String,
        val commands: List<GuideCommand> = emptyList(),
        val linkLabel: String? = null,
        val linkUrl: String? = null
    )

    data class PlatformInstall(
        val id: String,
        val title: String,
        val steps: List<String>,
        val commands: List<GuideCommand>,
        val docUrl: String
    )

    val introSummary =
        "Show PocketLinux’s Linux desktop on your Mac, Windows, or Linux PC as one window. " +
            "You install free tools on the computer (ADB + scrcpy), enable USB or wireless debugging " +
            "on this phone, then run a command. The PC client is official scrcpy — nothing extra " +
            "is installed into PocketLinux."

    val orderedSections: List<GuideStep> = listOf(
        GuideStep(
            title = "1. On this phone (once)",
            body = """
                1. Open Android Settings → About phone → tap Build number 7 times to unlock Developer options.
                2. Open Developer options → turn on USB debugging.
                3. (Recommended) Also enable Wireless debugging if you want Wi‑Fi later (Android 11+).
                4. On some brands (Xiaomi, etc.), also enable “USB debugging (Security settings)” so the PC mouse and keyboard work. Reboot if prompted.
                5. Leave PocketLinux installed; you will open it from the PC with scrcpy.
            """.trimIndent()
        ),
        GuideStep(
            title = "2. Install ADB on the computer",
            body = """
                Scrcpy talks to the phone through ADB (Android Debug Bridge). Install platform-tools so the adb command works.

                Windows: download Google Platform-Tools ZIP, unzip, and add the folder to PATH — or use the scrcpy Windows release which often bundles adb. If Windows asks for a USB driver, install Google USB Driver via Android Studio SDK Manager, or your phone maker’s USB driver (Samsung, etc.).

                macOS: install platform-tools (Homebrew: brew install android-platform-tools) or use the ZIP from Google.

                Linux: install android-tools-adb / android-tools (distro package) or Google platform-tools ZIP.

                Then plug the phone in with a data USB cable (not charge-only), unlock the phone, and accept “Allow USB debugging?”.
            """.trimIndent(),
            commands = listOf(
                GuideCommand("Check ADB sees the phone", CMD_ADB_DEVICES)
            ),
            linkLabel = "Google Platform-Tools",
            linkUrl = PLATFORM_TOOLS
        ),
        GuideStep(
            title = "3. Install scrcpy on the computer",
            body = """
                Install the official scrcpy app for your OS (not a random “scrcpy” APK website). Prefer a recent version that supports --new-display (scrcpy 2.x+).

                After install, open a terminal (or PowerShell / cmd) and type scrcpy -v to confirm it runs.
            """.trimIndent(),
            linkLabel = "scrcpy on GitHub",
            linkUrl = OFFICIAL_REPO
        ),
        GuideStep(
            title = "4. Connect over USB (easiest first time)",
            body = """
                1. Phone unlocked, USB cable to the PC, USB debugging allowed for this computer.
                2. On the PC run: adb devices — you should see a device as “device” (not “unauthorized”).
                3. Run the recommended PocketLinux command below. A desktop-sized window should open and PocketLinux starts on a virtual display.
                4. If the virtual-display flags fail (old scrcpy), run plain: scrcpy — then open PocketLinux on the phone yourself.
            """.trimIndent(),
            commands = listOf(
                GuideCommand("Check device", CMD_ADB_DEVICES),
                GuideCommand("Best for PocketLinux (USB)", CMD_VIRTUAL_DISPLAY),
                GuideCommand("Simple mirror (any scrcpy)", CMD_SIMPLE)
            )
        ),
        GuideStep(
            title = "5. Connect over Wi‑Fi",
            body = """
                Phone and PC must be on the same Wi‑Fi network.

                Easy path (scrcpy does the work): plug USB once, then run the Wi‑Fi command below. Scrcpy enables TCP/IP ADB and connects.

                Manual path:
                1. USB connected and authorized.
                2. adb tcpip 5555
                3. Unplug USB.
                4. adb connect YOUR_PHONE_IP:5555 (IP is shown on the Big screen settings page).
                5. Run scrcpy as usual (or the recommended command without needing USB).

                Android 11+ Wireless debugging: pair from Developer options if you prefer not to use USB; then adb connect to the IP and port shown on the phone.
            """.trimIndent(),
            commands = listOf(
                GuideCommand("Wi‑Fi auto (USB once)", CMD_VIRTUAL_DISPLAY_WIFI),
                GuideCommand("Enable ADB over TCP", CMD_ADB_TCPIP)
            ),
            linkLabel = "Connection docs",
            linkUrl = DOC_CONNECTION
        ),
        GuideStep(
            title = "6. Use PocketLinux on the big screen",
            body = """
                • Prefer the virtual-display command so the PC gets a 1920×1080 (or flex-sized) window instead of a tiny phone portrait.
                • Start a container desktop in PocketLinux if it did not auto-open.
                • Turn on “Big screen ready” in that container’s settings for landscape-friendly layout.
                • Mouse and keyboard on the PC control the session. Right-click ≈ Back, middle-click ≈ Home, Alt+f toggles fullscreen in scrcpy.
                • Audio may forward on Android 11+ depending on scrcpy version and device.
            """.trimIndent(),
            commands = listOf(
                GuideCommand("High quality (optional)", "scrcpy --new-display=1920x1080 --start-app=$PACKAGE_ID -x --keep-active --video-codec=h265 --max-fps=60")
            )
        ),
        GuideStep(
            title = "Troubleshooting",
            body = """
                • unauthorized / no device: unlock phone, revote USB debugging trust, try another cable/port.
                • Multiple devices: scrcpy -s SERIAL (serial from adb devices).
                • Mouse/keyboard do nothing: enable USB debugging (Security settings); reboot.
                • Virtual display empty: update scrcpy; always pass --start-app=$PACKAGE_ID; or use plain scrcpy and open the app on the phone.
                • Wi‑Fi fails: same LAN, disable VPN/client isolation on the router, check firewall on the PC.
                • This is one desktop window (the Linux DE), not separate OS windows per Linux app.
            """.trimIndent()
        )
    )

    val platformInstalls: List<PlatformInstall> = listOf(
        PlatformInstall(
            id = "windows",
            title = "Windows — ADB + scrcpy",
            steps = listOf(
                "Download the latest scrcpy Windows release from the official GitHub Releases page (zip). Unzip anywhere.",
                "If adb is not included or not on PATH: download Google Platform-Tools, unzip, add that folder to PATH.",
                "If the phone is not detected: install Google USB Driver (Android Studio SDK) or your OEM USB driver. Use a data cable.",
                "Open PowerShell or cmd in the scrcpy folder (or any terminal if scrcpy is on PATH).",
                "Plug phone → allow USB debugging → adb devices → run the PocketLinux command."
            ),
            commands = listOf(
                GuideCommand("List devices", CMD_ADB_DEVICES),
                GuideCommand("PocketLinux on PC", CMD_VIRTUAL_DISPLAY)
            ),
            docUrl = DOC_WINDOWS
        ),
        PlatformInstall(
            id = "macos",
            title = "macOS — ADB + scrcpy",
            steps = listOf(
                "Install Homebrew if needed, then: brew install scrcpy  (pulls scrcpy; install android-platform-tools if adb is missing).",
                "Or download scrcpy and platform-tools binaries from official docs/releases.",
                "Connect the phone with USB, unlock, trust this computer.",
                "In Terminal: adb devices, then the PocketLinux scrcpy command."
            ),
            commands = listOf(
                GuideCommand("Install scrcpy (Homebrew)", "brew install scrcpy"),
                GuideCommand("Install ADB (Homebrew)", "brew install android-platform-tools"),
                GuideCommand("PocketLinux on PC", CMD_VIRTUAL_DISPLAY)
            ),
            docUrl = DOC_MACOS
        ),
        PlatformInstall(
            id = "linux",
            title = "Linux — ADB + scrcpy",
            steps = listOf(
                "Install scrcpy from your distro (e.g. apt/dnf/pacman) or build from the official repo. Prefer a recent version.",
                "Install ADB: e.g. sudo apt install adb  or android-tools package; or Google platform-tools ZIP.",
                "udev rules: if the device is only visible as root, install android-udev rules or vendor rules, replug the phone.",
                "adb devices → scrcpy recommended command."
            ),
            commands = listOf(
                GuideCommand("Debian/Ubuntu ADB", "sudo apt install adb"),
                GuideCommand("Debian/Ubuntu scrcpy (if packaged)", "sudo apt install scrcpy"),
                GuideCommand("PocketLinux on PC", CMD_VIRTUAL_DISPLAY)
            ),
            docUrl = DOC_LINUX
        )
    )

    val quickCommands: List<GuideCommand> = listOf(
        GuideCommand("Best — USB virtual display", CMD_VIRTUAL_DISPLAY),
        GuideCommand("Best — Wi‑Fi (USB once)", CMD_VIRTUAL_DISPLAY_WIFI),
        GuideCommand("Simple phone mirror", CMD_SIMPLE),
        GuideCommand("List ADB devices", CMD_ADB_DEVICES)
    )

    val limitations = listOf(
        "One window shows the full Linux desktop (same surface as on the phone), not free-floating apps on the PC desktop.",
        "You must enable debugging yourself; PocketLinux cannot turn USB debugging on for you.",
        "Virtual display needs a recent scrcpy; older versions: use plain scrcpy and open PocketLinux on the device.",
        "Some OEMs need “USB debugging (Security settings)” for input injection."
    )
}
