package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuestTerminalLaunchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun detectPrompt_starshipToml() {
        val rootfs = tmp.newFolder("rootfs")
        val home = File(rootfs, "home/alice/.config")
        home.mkdirs()
        File(home, "starship.toml").writeText("[character]\n")
        val prompt = GuestTerminalLaunch.detectPrompt(rootfs, "alice")
        assertEquals(GuestTerminalLaunch.PromptTheme.STARSHIP, prompt.theme)
        assertTrue(prompt.starshipToml!!.isFile)
    }

    @Test
    fun extraEnv_setsXdgAndStarshipConfig() {
        val rootfs = tmp.newFolder("rootfs2")
        val home = File(rootfs, "home/bob/.config")
        home.mkdirs()
        val toml = File(home, "starship.toml")
        toml.writeText("x=1\n")
        val prompt = GuestTerminalLaunch.detectPrompt(rootfs, "bob")
        val env = GuestTerminalLaunch.extraEnvPairs(
            rootfs, "bob", "/home/bob", "/bin/bash", prompt
        )
        assertTrue(env.any { it == "SHELL=/bin/bash" })
        assertTrue(env.any { it.startsWith("XDG_CONFIG_HOME=") })
        assertTrue(env.any { it.startsWith("STARSHIP_CONFIG=") })
        assertTrue(env.any { it == "POCKETLINUX_KEEP_PS1=1" })
    }

    @Test
    fun hostFileToGuestPath_stripsRootfsPrefix() {
        val rootfs = tmp.newFolder("rootfs3")
        val f = File(rootfs, "home/u/.config/starship.toml")
        f.parentFile!!.mkdirs()
        f.writeText("a")
        assertEquals(
            "/home/u/.config/starship.toml",
            GuestTerminalLaunch.hostFileToGuestPath(rootfs, f)
        )
    }
}
