package com.sg.linuxgo

import com.sg.linuxgo.bootstrap.buildPocketLinuxLaunchScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LaunchScriptSyntaxTest {
    @Test
    fun launchScriptsParseWithSh() {
        for ((de, cmd) in listOf(
            "mate" to "mate-session",
            "xfce4" to "startxfce4",
            "lxqt" to "lxqt-session",
            "hyprland" to "Hyprland",
        )) {
            val script = buildPocketLinuxLaunchScript(
                username = "PocketLinux",
                homeDir = "/home/PocketLinux",
                selectedGuiMode = "x11",
                selectedDE = de,
                startCmd = cmd,
                gpuEnv = "export LIBGL_ALWAYS_SOFTWARE=1\n"
            )
            val out = File("/tmp/pocketlinux-launch-$de.sh")
            out.writeText(script)
            val p = ProcessBuilder("sh", "-n", out.absolutePath).redirectErrorStream(true).start()
            val err = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            assertEquals("syntax error in $de launch script:\n$err", 0, code)
        }
    }

    @Test
    fun x11LaunchScriptSkipsBrandWallpaperWhenUserBackdropExists() {
        val script = buildPocketLinuxLaunchScript(
            username = "PocketLinux",
            homeDir = "/home/PocketLinux",
            selectedGuiMode = "x11",
            selectedDE = "hyprland",
            startCmd = "Hyprland",
            gpuEnv = "export LIBGL_ALWAYS_SOFTWARE=1\n"
        )
        assertFalse(script.contains("omarchy.jpg"))
        assertTrue("skips brand wp when user last-image exists", script.contains("user_wp_only"))
        assertTrue("hyprland ready probe", script.contains("pgrep -x Hyprland"))
    }

    @Test
    fun x11LaunchScriptUsesMiniSessionOnArtixRelease() {
        val script = buildPocketLinuxLaunchScript(
            username = "PocketLinux",
            homeDir = "/home/PocketLinux",
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            startCmd = "startxfce4",
            gpuEnv = "export LIBGL_ALWAYS_SOFTWARE=1\n"
        )
        assertTrue("artix-release mini session", script.contains("/etc/artix-release"))
        assertTrue("mini session binary", script.contains("pocketlinux-xfce-session"))
        assertTrue("user wallpaper only", script.contains("user_wp_only"))
        assertTrue("overlay waits for xfdesktop", script.contains("pgrep -x xfdesktop"))
        assertTrue("xfce ready marker", script.contains("echo xfce"))
    }

    @Test
    fun x11LaunchScriptHidesXfcePolkitAutostart() {
        val script = buildPocketLinuxLaunchScript(
            username = "PocketLinux",
            homeDir = "/home/PocketLinux",
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            startCmd = "startxfce4",
            gpuEnv = "export LIBGL_ALWAYS_SOFTWARE=1\n"
        )
        assertTrue("hides xfce-polkit XDG autostart", script.contains("xfce-polkit"))
        assertTrue("kills xfce-polkit if it already started", script.contains("pkill -9 -x xfce-polkit"))
        assertTrue(
            "restores Void xbps unpack wrapper if the package overwrote it",
            script.contains("unpack shim v"),
        )
    }
}
