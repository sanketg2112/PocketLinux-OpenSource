package com.sg.linuxgo

import com.sg.linuxgo.bootstrap.buildWaylandSessionScript
import com.sg.linuxgo.bootstrap.writeWaylandDisplayExtras
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WaylandThemeSyncTest {

    @Test
    fun sessionScriptDoesNotFallbackEmptyIconToPocketLinux() {
        val script = buildWaylandSessionScript("startxfce4")
        assertFalse(script.contains("gtk-icon-theme-name=\${ICON:-PocketLinux}"))
        assertFalse(script.contains("gtk-icon-theme-name=${'$'}{ICON:-PocketLinux}"))
        assertTrue(script.contains("[ -n ") && script.contains("gtk-icon-theme-name="))
        assertTrue(script.contains("LAST_KEY"))
    }

    @Test
    fun sessionScriptParses() {
        val out = File("/tmp/pocketlinux-wayland-session.sh")
        out.writeText(buildWaylandSessionScript("startxfce4"))
        val p = ProcessBuilder("sh", "-n", out.absolutePath).redirectErrorStream(true).start()
        val err = p.inputStream.bufferedReader().readText()
        assertTrue("syntax error:\n$err", p.waitFor() == 0)
    }

    @Test
    fun labwcAutostartDoesNotRestartPanel() {
        val root = File("/tmp/pl-wayland-theme-sync").apply {
            deleteRecursively()
            mkdirs()
        }
        val bin = File(root, "usr/local/bin").apply { mkdirs() }
        writeWaylandDisplayExtras(root, "PocketLinux", 100, bin)
        val autostart = File(root, "home/PocketLinux/.config/xfce4/labwc/autostart")
        assertTrue(autostart.isFile)
        val text = autostart.readText()
        assertFalse(text.contains("xfce4-panel --restart"))
        assertFalse(text.contains("xfsettingsd --replace"))
        assertFalse(text.contains("xfdesktop --reload"))
        assertFalse(text.contains("ICON:-PocketLinux"))
        assertTrue(text.contains("LAST_KEY"))
        val p = ProcessBuilder("sh", "-n", autostart.absolutePath).redirectErrorStream(true).start()
        val err = p.inputStream.bufferedReader().readText()
        assertTrue("autostart syntax:\n$err", p.waitFor() == 0)
    }
}
