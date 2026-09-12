package com.sg.linuxgo

/**
 * Pure helpers for debug-only desktop-environment conversion
 * (XFCE ↔ MATE ↔ LXQt) on an already-installed golden rootfs.
 */
object DesktopDeConversion {

    /** App DE ids offered by the one-click converter. */
    val SUPPORTED_TARGETS: List<String> = listOf("xfce4", "mate", "lxqt")

    const val ASSET_SCRIPT = "convert_desktop_de.sh"
    const val GUEST_SCRIPT_PATH = "/root/convert_desktop_de.sh"

    fun normalizeDe(raw: String?): String {
        val d = raw?.trim()?.lowercase().orEmpty()
        return when {
            d.isBlank() -> ""
            d == "xfce" || d == "xfce4" || d.contains("xfce") -> "xfce4"
            d == "mate" || d.contains("mate") -> "mate"
            d == "lxqt" || d.contains("lxqt") -> "lxqt"
            d == "hyprland" || d.contains("hypr") -> "hyprland"
            else -> d
        }
    }

    fun isSupportedTarget(de: String): Boolean =
        normalizeDe(de) in SUPPORTED_TARGETS

    fun defaultWmForDe(de: String): String = when (normalizeDe(de)) {
        "mate" -> "marco"
        "lxqt" -> "openbox"
        "xfce4" -> "none"
        else -> "none"
    }

    fun labelForDe(de: String): String = ContainerConfig.deLabel(normalizeDe(de))

    /**
     * Display name after convert. Prefer "Distro / DE" when the previous name
     * looked auto-generated; otherwise keep the user's custom name.
     */
    fun suggestedName(distro: String, targetDe: String, currentName: String): String {
        val distroLabel = ContainerConfig.distroLabel(distro)
        val deLabel = labelForDe(targetDe)
        val standard = "$distroLabel / $deLabel"
        val name = currentName.trim()
        if (name.isBlank()) return standard
        val looksAuto = name.contains('/') ||
            name.contains('+') ||
            name.equals(distroLabel, ignoreCase = true) ||
            SUPPORTED_TARGETS.any { t ->
                name.contains(ContainerConfig.deLabel(t), ignoreCase = true)
            }
        return if (looksAuto) standard else name
    }

    fun sameDe(current: String?, target: String): Boolean =
        normalizeDe(current) == normalizeDe(target) && normalizeDe(target).isNotBlank()

    /** Fragment expected in pocketlinux-launch / start_cmd for [de]. */
    fun expectedStartCmd(de: String): String = when (normalizeDe(de)) {
        "mate" -> "mate-session"
        "lxqt" -> "lxqt-session"
        "xfce4" -> "startxfce4"
        "hyprland" -> "Hyprland"
        else -> normalizeDe(de)
    }

    /**
     * True when [launchScript] body is clearly aimed at [de].
     * Kotlin bakes startCmd into the line: `Starting <cmd> on $DISPLAY…`
     * (case patterns still mention other DEs, so do not use bare contains).
     */
    fun launchScriptMatchesDe(launchScript: String, de: String): Boolean {
        val d = normalizeDe(de)
        if (d.isBlank() || launchScript.isBlank()) return false
        val body = launchScript
        return when (d) {
            "mate" -> body.contains("Starting mate-session")
            "lxqt" ->
                body.contains("Starting lxqt-session") ||
                    body.contains("Starting startlxqt")
            "xfce4" ->
                body.contains("Starting startxfce4") ||
                    body.contains("Starting /usr/local/bin/pocketlinux-xfce-session") ||
                    (
                        body.contains("Starting pocketlinux-xfce-session") ||
                            (
                                body.contains("__PL_X11_CMD=/usr/local/bin/pocketlinux-xfce-session") &&
                                    !body.contains("Starting mate-session") &&
                                    !body.contains("Starting lxqt-session")
                                )
                        )
            else -> true
        }
    }

    /** Guest stamp path written by convert script / host after rewrite. */
    const val GUEST_DESKTOP_DE_REL = "var/lib/pocketlinux/desktop_de"
    const val GUEST_START_CMD_REL = "var/lib/pocketlinux/start_cmd"
}
