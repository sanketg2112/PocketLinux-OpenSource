package com.sg.linuxgo

import com.sg.linuxgo.bootstrap.buildPocketLinuxLaunchScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Intentional Log Out only — panel crash must not look like logout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DesktopLogoutWatchdogTest {

    @Test
    fun launchScriptRequiresUserLogoutMarkerNotPanelGone() {
        val script = buildPocketLinuxLaunchScript(
            username = "user",
            homeDir = "/home/user",
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            startCmd = "startxfce4",
            gpuEnv = "export LIBGL_ALWAYS_SOFTWARE=1\n"
        )
        assertTrue(script.contains("logout-watchdog-v3") || script.contains("logout-watchdog-v2"))
        assertTrue(script.contains("user_logout"))
        assertTrue(script.contains("SESSION_PID="))
        // GPU env must reach the DE (Freedreno / glmark2)
        assertTrue(script.contains("MESA_LOADER_DRIVER_OVERRIDE"))
        // Must not end session just because panel disappeared
        assertFalse(script.contains("panel gone"))
        assertFalse(script.contains("SEEN_PANEL"))
    }

    @Test
    fun intentionalLogoutMarkerHelper() {
        assertTrue(isIntentionalDesktopLogoutMarker("user_logout"))
        assertTrue(isIntentionalDesktopLogoutMarker("user_logout\n"))
        assertFalse(isIntentionalDesktopLogoutMarker("logout"))
        assertFalse(isIntentionalDesktopLogoutMarker(""))
        assertFalse(isIntentionalDesktopLogoutMarker(null))
        assertFalse(isIntentionalDesktopLogoutMarker("timeout"))
    }

    @Test
    fun desktopLogoutHelpersWriteUserLogout() {
        val ctx = RuntimeEnvironment.getApplication()
        val rootfs = File(ctx.filesDir, "test-rootfs-logout-v2").apply {
            deleteRecursively()
            mkdirs()
        }
        ContainerRestoreEngine(ctx).ensureDesktopLogoutHelpers(rootfs) { }

        val logout = File(rootfs, "usr/local/bin/pocketlinux-logout")
        val sessionLogout = File(rootfs, "usr/local/bin/xfce4-session-logout")
        assertTrue(logout.isFile)
        assertTrue(sessionLogout.isFile)
        assertTrue(logout.readText().contains("user_logout"))
        assertTrue(sessionLogout.readText().contains("user_logout"))
        // Marker written before teardown
        val body = logout.readText()
        assertTrue(body.indexOf("user_logout") < body.indexOf("pkill"))
    }

    @Test
    fun desktopLogoutHelpersWrapUsrBinSessionLogout() {
        val ctx = RuntimeEnvironment.getApplication()
        val rootfs = File(ctx.filesDir, "test-rootfs-logout-v3").apply {
            deleteRecursively()
            mkdirs()
        }
        // Simulate distro binary that panel invokes via absolute path
        val usrBin = File(rootfs, "usr/bin").apply { mkdirs() }
        val real = File(usrBin, "xfce4-session-logout")
        real.writeText("#!/bin/sh\n# real distro logout\nexit 0\n")
        real.setExecutable(true)

        ContainerRestoreEngine(ctx).ensureDesktopLogoutHelpers(rootfs) { }

        val wrapped = File(usrBin, "xfce4-session-logout")
        val backup = File(usrBin, "xfce4-session-logout.real")
        assertTrue(wrapped.isFile)
        assertTrue(wrapped.readText().contains("PocketLinux"))
        assertTrue(wrapped.readText().contains("user_logout"))
        assertTrue(backup.isFile)
        assertTrue(backup.readText().contains("real distro logout"))
        assertTrue(File(rootfs, "var/lib/pocketlinux/logout_helpers_v3").isFile)
    }
}
