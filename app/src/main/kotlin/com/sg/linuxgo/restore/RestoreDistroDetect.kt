package com.sg.linuxgo.restore

import java.io.File

/**
 * Pure distro detection helpers (no Android Context).
 * Used by [com.sg.linuxgo.ContainerRestoreEngine] and unit tests.
 */

/** Guess app distro id from a backup filename / display string. */
fun detectDistroFromName(name: String): String? {
    val n = name.lowercase()
    return when {
        n.contains("artix") -> "artix"
        n.contains("arch") -> "archlinux"
        n.contains("kali") -> "kali"
        n.contains("debian") -> "debian"
        n.contains("ubuntu") -> "ubuntu"
        n.contains("alpine") -> "alpine"
        n.contains("fedora") -> "fedora"
        n.contains("void") -> "void"
        n.contains("suse") -> "opensuse"
        else -> null
    }
}

/** Detect distro from extracted rootfs /etc/os-release. */
fun detectDistroFromRootfs(rootfs: File, fallback: String = "alpine"): String {
    val osRelease = File(rootfs, "etc/os-release")
    if (!osRelease.isFile) return fallback
    return try {
        val content = osRelease.readText().lowercase()
        when {
            // Kali ID_LIKE=debian — check kali before debian
            content.contains("kali") -> "kali"
            content.contains("ubuntu") -> "ubuntu"
            content.contains("artix") -> "artix"
            content.contains("fedora") -> "fedora"
            content.contains("void") -> "void"
            content.contains("opensuse") || content.contains("suse") -> "opensuse"
            content.contains("debian") -> "debian"
            content.contains("alpine") -> "alpine"
            content.contains("arch") -> "archlinux"
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}
