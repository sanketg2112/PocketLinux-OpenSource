package com.sg.linuxgo

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayDeque

/** Post-restore structural validation. */

fun ContainerRestoreEngine.validateAndFix(
    rootfs: File,
    containerId: String,
    containerManager: ContainerManager,
    onLog: (String) -> Unit = {}
): Boolean {
    onLog("Validating restored system...")

    val etc = File(rootfs, "etc")
    // Arch/Debian usrmerge: /bin is often a symlink to usr/bin
    val binOk = File(rootfs, "bin").exists() || File(rootfs, "usr/bin").isDirectory
    // Alpine: bin/sh → /bin/busybox (guest-absolute); host File.exists() is false
    val shellOk = com.sg.linuxgo.util.TarHardlinkSafeExtract.hasGuestShell(rootfs)
    if (!etc.exists() || !binOk || !shellOk) {
        onLog("! Validation Failed: Restored image seems incomplete or corrupted.")
        return false
    }

    val dirsToCreate = listOf(
        "tmp", "root", "run/dbus", "var/lib/dbus", "home",
        "proc", "sys", "dev", "run"
    )
    dirsToCreate.forEach { path ->
        val dir = File(rootfs, path)
        if (!dir.exists()) dir.mkdirs()
    }
    ensureVarRunSymlink(rootfs)

    val config = containerManager.getContainer(containerId) ?: return true
    // Prefer filesystem detection so restore/install does not create a wrong home
    // when SharedPreferences still has a placeholder username.
    val username = detectGuestUsername(rootfs)
        ?.takeIf { it.isNotBlank() }
        ?: config.username.ifBlank { "PocketLinux" }
    if (username != config.username) {
        containerManager.updateContainer(config.copy(username = username))
        onLog("ℹ Detected guest user '$username' (config had '${config.username}')")
    }
    val homeDir = if (username == "root") File(rootfs, "root") else File(rootfs, "home/$username")

    if (!homeDir.exists()) {
        onLog("⚠ Home directory for '$username' missing. Creating it...")
        homeDir.mkdirs()
    }

    try {
        File(homeDir, ".ICEauthority").createNewFile()
        File(homeDir, ".Xauthority").createNewFile()
    } catch (_: Exception) {
    }

    val detectedDistro = detectDistroFromRootfs(rootfs, config.distro)
    if (detectedDistro != config.distro) {
        onLog("ℹ Detected distro: $detectedDistro (Updating config)")
        containerManager.updateContainer(config.copy(distro = detectedDistro))
    }

    val de = containerManager.getContainer(containerId)?.de ?: config.de
    if (de != "none") {
        val deBinary = when (de) {
            "xfce4", "xfce" -> "usr/bin/xfce4-session"
            "lxqt" -> "usr/bin/lxqt-session"
            "mate" -> "usr/bin/mate-session"
            else -> null
        }
        if (deBinary != null && !File(rootfs, deBinary).exists()) {
            onLog("⚠ Warning: Restored backup is missing $de binaries.")
        }
    }

    // Arch-specific post-restore (pacman SigLevel, xkb, sockets)
    if (detectedDistro == "archlinux") {
        prepareArchAfterRestore(rootfs, onLog)
    }

    onLog("✓ System validation passed.")
    return true
}

/**
 * Persist session identity into the guest rootfs so terminal/GUI always
 * present as [username] even though proot -0 keeps real uid 0 (needed for apt).
 *
 * Why this is required:
 *  - bash PS1 uses `\u` which is getpwuid(0) → "root" under proot -0
 *  - whoami / id -un also use getpwuid(geteuid())
 *  - XFCE and other desktop apps call getpwuid(0) for the session user
 *
 * Strategy (both must hold at once):
 *  1. Keep proot -0 so dpkg/apt have superuser privilege
 *  2. Make getpwuid(0) resolve to the guest name by inserting a uid-0
 *     passwd alias (first match wins) with HOME=/home/<user>
 *  3. Env + profile.d + whoami wrappers as belt-and-suspenders
 */
