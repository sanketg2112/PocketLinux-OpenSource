package com.sg.linuxgo

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single Linux container installation.
 * Each container has its own rootfs directory, distro, desktop environment,
 * and installed software. Multiple containers can coexist simultaneously.
 */
data class ContainerConfig(
    val id: String,
    val distro: String,          // alpine/debian/ubuntu/kali/archlinux/fedora/void/opensuse/artix
    val de: String,              // "xfce4", "mate", "lxqt", "none"
    val wm: String,              // "openbox", "i3", "awesome", "none"
    val name: String,            // User-facing display name, e.g. "Alpine + MATE"
    val username: String,        // Non-root username
    val software: List<String>,  // Installed software IDs
    val createdAt: Long,         // Epoch millis
    val isInstalled: Boolean,    // true when setup is complete
    val installRecommends: Boolean = false, // true to install recommended packages
    val guiMode: String = "x11"  // "x11" or "wayland"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("distro", distro)
            put("de", de)
            put("wm", wm)
            put("name", name)
            put("username", username)
            put("software", JSONArray(software))
            put("createdAt", createdAt)
            put("isInstalled", isInstalled)
            put("installRecommends", installRecommends)
            put("guiMode", guiMode)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ContainerConfig {
            val softwareArray = json.optJSONArray("software") ?: JSONArray()
            val softwareList = mutableListOf<String>()
            for (i in 0 until softwareArray.length()) {
                softwareList.add(softwareArray.getString(i))
            }
            return ContainerConfig(
                id = json.getString("id"),
                distro = json.optString("distro", "alpine"),
                de = json.optString("de", "xfce4"),
                wm = json.optString("wm", "none"),
                name = json.optString("name", "Linux Container"),
                username = json.optString("username", "PocketLinux"),
                software = softwareList,
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                isInstalled = json.optBoolean("isInstalled", false),
                installRecommends = json.optBoolean("installRecommends", false),
                guiMode = json.optString("guiMode", "x11")
            )
        }

        /** Human-readable label for a desktop environment ID */
        fun deLabel(de: String): String = when (de) {
            "xfce4", "xfce" -> "XFCE"
            "mate" -> "MATE"
            "lxqt" -> "LXQt"
            "ubuntu-de" -> "Ubuntu Desktop"
            "ubuntu-wm" -> "Ubuntu WM"
            "openbox" -> "Openbox"
            "hyprland" -> "Hyprland"
            "none" -> "No Desktop"
            else -> de.uppercase()
        }

        /** Human-readable label for a distro ID */
        fun distroLabel(distro: String): String = when (distro) {
            "alpine" -> "Alpine"
            "debian" -> "Debian"
            "ubuntu" -> "Ubuntu"
            "kali" -> "Kali Linux"
            "archlinux" -> "Arch Linux"
            "fedora" -> "Fedora"
            "void" -> "Void Linux"
            "opensuse" -> "openSUSE"
            "artix" -> "Artix"
            else -> distro.replaceFirstChar { it.uppercase() }
        }

        /** Accent color for each distro */
        fun distroColor(distro: String): Int = when (distro) {
            "alpine" -> 0xFF00E5FF.toInt()   // Cyan
            "debian" -> 0xFFD63384.toInt()    // Magenta/Red
            "ubuntu" -> 0xFFE95420.toInt()    // Ubuntu Orange
            "kali" -> 0xFF2777FF.toInt()      // Kali Blue
            "archlinux" -> 0xFF1793D1.toInt() // Arch Blue
            "fedora" -> 0xFF3C6EB4.toInt()
            "void" -> 0xFF478061.toInt()
            "opensuse" -> 0xFF73BA25.toInt()
            "artix" -> 0xFF10A0CC.toInt()
            else -> 0xFF00E5FF.toInt()
        }
    }
}
