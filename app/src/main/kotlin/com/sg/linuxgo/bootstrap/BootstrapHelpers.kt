package com.sg.linuxgo.bootstrap

/**
 * Pure helpers extracted from [com.sg.linuxgo.Bootstrap] for testability
 * and to keep Bootstrap under the 1000-line project limit.
 */

/** Map a 0–1 fraction into an overall progress band [start, end]. */
fun overallProgress(start: Int, end: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    return (start + (end - start) * f).toInt().coerceIn(0, 100)
}

/** Software (llvmpipe) GPU env exports embedded into pocketlinux-launch. */
const val SOFTWARE_GPU_ENV: String =
    "export LIBGL_ALWAYS_SOFTWARE=1\n" +
    "export GALLIUM_DRIVER=llvmpipe\n" +
    "export MESA_LOADER_DRIVER_OVERRIDE=swrast\n" +
    "export MESA_DEBUG=silent\n" +
    "export MOZ_GL_ALWAYS_SOFTWARE=1\n" +
    "export MOZ_WEBGL_FORCE_OPENGL=1\n"

/**
 * Resolve the primary desktop/session start command for pocketlinux-launch.
 * Arch + XFCE + X11 uses the mini session (avoids Firefox session restore OOM).
 */
fun resolveStartCmd(
    isArchRootfs: Boolean,
    selectedGuiMode: String,
    selectedDE: String,
    selectedWM: String
): String {
    if (selectedDE == "hyprland") {
        return "Hyprland"
    }
    val archXfceMini = isArchRootfs && selectedGuiMode != "wayland" &&
        (selectedDE == "xfce4" || selectedDE == "none" || selectedDE.isBlank())
    return when {
        archXfceMini -> "/usr/local/bin/pocketlinux-xfce-session"
        selectedDE != "none" -> when (selectedDE) {
            "xfce4" -> if (selectedGuiMode == "wayland") "startxfce4 --wayland" else "startxfce4"
            "lxqt" -> "lxqt-session"
            "mate" -> "mate-session"
            "kde" -> if (selectedGuiMode == "wayland") "startplasma-wayland" else "startplasma-x11"
            "ubuntu-de" -> "gnome-session --disable-acceleration-check"
            else -> "startxfce4"
        }
        selectedWM != "none" -> when (selectedWM) {
            "openbox" -> "openbox-session"
            "awesome" -> "awesome"
            "i3" -> "i3"
            "ubuntu-wm" -> "mutter"
            else -> selectedWM
        }
        else -> if (isArchRootfs && selectedGuiMode != "wayland") {
            "/usr/local/bin/pocketlinux-xfce-session"
        } else {
            "startxfce4"
        }
    }
}

/** Fallback session command when the primary start command is unavailable. */
fun resolveStartCmdFallback(
    isArchRootfs: Boolean,
    selectedGuiMode: String,
    selectedDE: String,
    selectedWM: String
): String {
    if (selectedDE == "hyprland") {
        return "Hyprland"
    }
    val archXfceMini = isArchRootfs && selectedGuiMode != "wayland" &&
        (selectedDE == "xfce4" || selectedDE == "none" || selectedDE.isBlank())
    return when {
        archXfceMini || (isArchRootfs && selectedGuiMode != "wayland") ->
            "/usr/local/bin/pocketlinux-xfce-session"
        selectedDE != "none" -> when (selectedDE) {
            "xfce4" -> "startxfce4"
            "lxqt" -> "lxqt-session"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            "ubuntu-de" -> "gnome-session --disable-acceleration-check"
            else -> "startxfce4"
        }
        selectedWM != "none" -> when (selectedWM) {
            "openbox" -> "openbox-session"
            "awesome" -> "awesome"
            "i3" -> "i3"
            "ubuntu-wm" -> "mutter"
            else -> selectedWM
        }
        else -> "startxfce4"
    }
}

fun guestHomeDir(username: String): String =
    if (username == "root") "/root" else "/home/$username"
