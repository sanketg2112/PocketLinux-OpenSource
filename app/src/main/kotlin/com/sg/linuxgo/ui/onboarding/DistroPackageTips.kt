package com.sg.linuxgo.ui.onboarding

import androidx.compose.ui.graphics.Color
import com.sg.linuxgo.ui.theme.DistroAlpine
import com.sg.linuxgo.ui.theme.DistroArch
import com.sg.linuxgo.ui.theme.DistroDebian
import com.sg.linuxgo.ui.theme.DistroKali
import com.sg.linuxgo.ui.theme.DistroUbuntu

/**
 * Distro-specific package manager tips for first-time users.
 */
data class PackageCommand(
    val label: String,
    val command: String
)

data class DistroTipGuide(
    val distroId: String,
    val displayName: String,
    val packageManager: String,
    val accent: Color,
    val summary: String,
    val commands: List<PackageCommand>,
    val extraTips: List<String>
)

object DistroPackageTips {

    const val PREF_ONBOARDING_COMPLETED = "onboarding_completed"
    const val PREF_DISTRO_TIPS_PREFIX = "distro_tips_seen_"
    /** When true, inline tip links (card + home banner) stay hidden permanently. */
    const val PREF_PACKAGE_TIPS_HINT_DISMISSED = "package_tips_hint_dismissed"

    const val OPENCODE_INSTALL = "curl -fsSL https://opencode.ai/install | bash"

    fun tipsSeenKey(distroId: String): String = PREF_DISTRO_TIPS_PREFIX + normalize(distroId)

    fun hasSeenTips(prefs: android.content.SharedPreferences, distroId: String): Boolean =
        prefs.getBoolean(tipsSeenKey(distroId), false)

    fun areHintsDismissed(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(PREF_PACKAGE_TIPS_HINT_DISMISSED, false)

    fun markTipsDismissed(prefs: android.content.SharedPreferences, distroId: String) {
        prefs.edit()
            .putBoolean(tipsSeenKey(distroId), true)
            .putBoolean(PREF_PACKAGE_TIPS_HINT_DISMISSED, true)
            .apply()
    }

    fun forDistro(distroId: String): DistroTipGuide {
        return when (normalize(distroId)) {
            "archlinux", "arch" -> arch
            "ubuntu" -> ubuntu
            "kali" -> kali
            "alpine" -> alpine
            else -> debian
        }
    }

    fun allGuides(): List<DistroTipGuide> = listOf(debian, ubuntu, kali, arch, alpine)

    /** Primary images users install most often (chooser default when none installed). */
    fun primaryGuides(): List<DistroTipGuide> = listOf(debian, arch)

    /**
     * Guides for [distroIds] (normalized). Empty / null → [primaryGuides].
     * Dedupes (e.g. arch + archlinux) and preserves primary order.
     */
    fun guidesFor(distroIds: List<String>?): List<DistroTipGuide> {
        if (distroIds.isNullOrEmpty()) return primaryGuides()
        val wanted = distroIds.map { normalize(it) }.toSet()
        val matched = allGuides().filter { guide ->
            val id = normalize(guide.distroId)
            id in wanted ||
                (id == "archlinux" && ("arch" in wanted || "archlinux" in wanted)) ||
                (id == "debian" && "debian" in wanted)
        }
        return matched.ifEmpty { primaryGuides() }
    }

    private fun normalize(distroId: String): String =
        distroId.trim().lowercase().replace('_', '-')

    private val openCodeCommand = PackageCommand(
        label = "Install OpenCode (AI coding agent)",
        command = OPENCODE_INSTALL
    )

    private val debian = DistroTipGuide(
        distroId = "debian",
        displayName = "Debian",
        packageManager = "apt",
        accent = DistroDebian,
        summary = "Debian uses apt. Always update first, then install apps.",
        commands = listOf(
            PackageCommand("Update package lists", "sudo apt update"),
            PackageCommand("Install Firefox", "sudo apt install -y firefox"),
            openCodeCommand,
            PackageCommand("Search for a package", "apt search firefox"),
            PackageCommand("Remove a package", "sudo apt remove firefox")
        ),
        extraTips = listOf(
            "Copy a command above, paste it in Terminal, press Enter.",
            "GUI apps appear in the desktop menu after install (Launch GUI).",
            "Phone files appear at /sdcard only if you turned on Mount phone storage."
        )
    )

    private val ubuntu = DistroTipGuide(
        distroId = "ubuntu",
        displayName = "Ubuntu",
        packageManager = "apt",
        accent = DistroUbuntu,
        summary = "Ubuntu uses apt (same as Debian). Update, then install.",
        commands = listOf(
            PackageCommand("Update package lists", "sudo apt update"),
            PackageCommand("Install Firefox", "sudo apt install -y firefox"),
            openCodeCommand,
            PackageCommand("Search for a package", "apt search firefox")
        ),
        extraTips = listOf(
            "Copy a command above, paste it in Terminal, press Enter.",
            "After install, find apps in the desktop menu (Launch GUI).",
            "Phone files appear at /sdcard only if you turned on Mount phone storage."
        )
    )

    private val kali = DistroTipGuide(
        distroId = "kali",
        displayName = "Kali Linux",
        packageManager = "apt",
        accent = DistroKali,
        summary = "Kali uses apt (Debian family, rolling). Update, then install tools.",
        commands = listOf(
            PackageCommand("Update package lists", "sudo apt update"),
            PackageCommand("Full upgrade", "sudo apt full-upgrade -y"),
            PackageCommand("Install Firefox", "sudo apt install -y firefox-esr"),
            openCodeCommand,
            PackageCommand("Search for a package", "apt search nmap")
        ),
        extraTips = listOf(
            "Copy a command above, paste it in Terminal, press Enter.",
            "Kali is rolling — run apt update before large installs.",
            "Phone files appear at /sdcard only if you turned on Mount phone storage."
        )
    )

    private val arch = DistroTipGuide(
        distroId = "archlinux",
        displayName = "Arch Linux",
        packageManager = "pacman",
        accent = DistroArch,
        summary = "Arch uses pacman. Sync the databases, then install packages.",
        commands = listOf(
            PackageCommand("Update system", "sudo pacman -Syu"),
            PackageCommand("Install Firefox", "sudo pacman -S --noconfirm firefox"),
            openCodeCommand,
            PackageCommand("Search for a package", "pacman -Ss firefox"),
            PackageCommand("Remove a package", "sudo pacman -Rns firefox")
        ),
        extraTips = listOf(
            "Copy a command above, paste it in Terminal, press Enter.",
            "Arch is experimental under PRoot. If package signatures are off, the app will warn you — do not use that environment for anything important.",
            "If a package fails: sudo pacman -Syy then install again.",
            "Phone files appear at /sdcard only if you turned on Mount phone storage."
        )
    )

    private val alpine = DistroTipGuide(
        distroId = "alpine",
        displayName = "Alpine",
        packageManager = "apk",
        accent = DistroAlpine,
        summary = "Alpine uses apk — fast and lightweight.",
        commands = listOf(
            PackageCommand("Update package index", "sudo apk update"),
            PackageCommand("Install Firefox", "sudo apk add firefox"),
            openCodeCommand,
            PackageCommand("Search for a package", "apk search firefox")
        ),
        extraTips = listOf(
            "Copy a command above, paste it in Terminal, press Enter.",
            "Alpine packages stay small — good on limited storage.",
            "Phone files appear at /sdcard only if you turned on Mount phone storage."
        )
    )
}
