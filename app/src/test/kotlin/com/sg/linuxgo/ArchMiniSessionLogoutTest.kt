package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Arch mini-session: intentional user_logout only (no panel-gone false positive).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArchMiniSessionLogoutTest {

    @Test
    fun miniSessionOnlyEndsOnUserLogoutMarker() {
        val ctx = RuntimeEnvironment.getApplication()
        val rootfs = File(ctx.filesDir, "test-rootfs-mini-v2").apply {
            deleteRecursively()
            mkdirs()
        }
        val engine = ContainerRestoreEngine(ctx)
        engine.ensureArchXfceMiniSession(rootfs) { /* quiet */ }

        val mini = File(rootfs, "usr/local/bin/pocketlinux-xfce-session")
        assertTrue(mini.isFile)
        val miniText = mini.readText()
        assertTrue(miniText.contains("user_logout"))
        assertFalse(miniText.contains("panel gone"))
        assertTrue(miniText.contains("POCKETLINUX_MINI_SESSION=1"))
        assertTrue(miniText.contains("mini_session_v9"))
        assertTrue(miniText.contains("user_wp_only"))
        assertTrue(miniText.contains("_pl_wp_pick"))
        assertTrue(miniText.contains("__pl_xfce_stack_up"))
        assertTrue(miniText.contains("echo xfce"))
        assertTrue(miniText.contains("xfconfd"))
        assertTrue(miniText.contains("openbox"))
        assertTrue(miniText.contains("__pl_has"))
        assertTrue(miniText.contains("start round"))
        assertTrue(miniText.contains("xfce4-panel"))
        assertTrue(miniText.contains("__pl_user_wp") || miniText.contains("user prefs not overwritten"))
        assertTrue(miniText.contains("disable-wm-check") || miniText.contains("__pl_seed_panel"))
        assertTrue(miniText.contains("__PL_IS_ALPINE") || miniText.contains("alpine-release"))
        assertTrue(miniText.contains("apk.static") || miniText.contains("apk add openbox"))
    }
}
