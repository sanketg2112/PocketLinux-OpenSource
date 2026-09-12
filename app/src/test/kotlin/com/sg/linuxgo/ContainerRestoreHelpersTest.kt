package com.sg.linuxgo

import com.sg.linuxgo.restore.detectDistroFromName
import com.sg.linuxgo.restore.detectDistroFromRootfs
import com.sg.linuxgo.restore.isOurPackageWrapper
import com.sg.linuxgo.restore.isOurSudoShim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ContainerRestoreHelpersTest {

    @Test
    fun detectDistroFromNameKeywords() {
        assertEquals("archlinux", detectDistroFromName("Arch_Linux_backup.tar.gz"))
        assertEquals("debian", detectDistroFromName("my-debian-lab"))
        assertEquals("kali", detectDistroFromName("Kali_NetHunter_backup"))
        assertEquals("ubuntu", detectDistroFromName("Ubuntu Desktop"))
        assertEquals("alpine", detectDistroFromName("alpine-xfce"))
        assertEquals("fedora", detectDistroFromName("fedora-xfce-backup"))
        assertEquals("void", detectDistroFromName("Void_Linux.tar.gz"))
        assertEquals("opensuse", detectDistroFromName("openSUSE-Leap"))
        assertEquals("artix", detectDistroFromName("Artix_backup"))
        assertEquals("archlinux", detectDistroFromName("Arch_Hyprland_backup"))
        assertNull(detectDistroFromName("random-name"))
    }

    @Test
    fun detectDistroFromRootfsReadsOsRelease() {
        val root = createTempDirectory("cre-rootfs").toFile()
        try {
            val etc = File(root, "etc").apply { mkdirs() }
            File(etc, "os-release").writeText("ID=arch\nNAME=\"Arch Linux\"\n")
            assertEquals("archlinux", detectDistroFromRootfs(root))

            File(etc, "os-release").writeText("ID=debian\n")
            assertEquals("debian", detectDistroFromRootfs(root))

            File(etc, "os-release").writeText("ID=ubuntu\n")
            assertEquals("ubuntu", detectDistroFromRootfs(root))

            File(etc, "os-release").writeText("ID=kali\nID_LIKE=debian\n")
            assertEquals("kali", detectDistroFromRootfs(root))

            File(etc, "os-release").writeText("ID=alpine\n")
            assertEquals("alpine", detectDistroFromRootfs(root))

            File(etc, "os-release").writeText("ID=artix\nID_LIKE=arch\n")
            assertEquals("artix", detectDistroFromRootfs(root))

            File(etc, "os-release").writeText("ID=fedora\n")
            assertEquals("fedora", detectDistroFromRootfs(root))

            File(etc, "os-release").writeText("ID=void\n")
            assertEquals("void", detectDistroFromRootfs(root))

            File(etc, "os-release").writeText("ID=opensuse-leap\nID_LIKE=\"suse opensuse\"\n")
            assertEquals("opensuse", detectDistroFromRootfs(root))

        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectDistroFromRootfsFallbackWhenMissing() {
        val root = createTempDirectory("cre-empty").toFile()
        try {
            assertEquals("alpine", detectDistroFromRootfs(root))
            assertEquals("debian", detectDistroFromRootfs(root, fallback = "debian"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun isOurPackageWrapperMarker() {
        val f = File.createTempFile("pkg", ".sh")
        try {
            f.writeText("#!/bin/sh\n# preload hardlink shim for proot\nexec real \"$@\"\n")
            assertTrue(isOurPackageWrapper(f))
            f.writeText("#!/bin/sh\nexec /usr/bin/apt-get.real \"$@\"\n")
            assertFalse(isOurPackageWrapper(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun isOurSudoShimMarkers() {
        val f = File.createTempFile("sudo", ".sh")
        try {
            f.writeText("#!/bin/sh\n# PocketLinux: under Android PRoot\n# NO_NEW_PRIVS\nexec \"$@\"\n")
            assertTrue(isOurSudoShim(f))
            f.writeText("#!/bin/sh\n# real sudo stub\n")
            assertFalse(isOurSudoShim(f))
            f.writeText("something proot -0 related\n")
            assertTrue(isOurSudoShim(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun isValidXkbTreeRequiresStructure() {
        val root = createTempDirectory("xkb").toFile()
        try {
            assertFalse(ContainerRestoreEngine.isValidXkbTree(root))
            val rules = File(root, "rules").apply { mkdirs() }
            assertTrue(ContainerRestoreEngine.isValidXkbTree(root))
            rules.delete()
            File(root, "symbols").mkdirs()
            assertTrue(ContainerRestoreEngine.isValidXkbTree(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun buildGuestPathPrependsExistingLocalBins() {
        val root = createTempDirectory("guest-path").toFile()
        try {
            val homeHost = File(root, "home/alice/.local/bin").apply { mkdirs() }
            File(homeHost, "tool").writeText("x")
            val path = ContainerRestoreEngine.buildGuestPath(root, "/home/alice")
            assertTrue(path.startsWith("/home/alice/.local/bin:"))
            assertTrue(path.contains("/usr/bin"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun buildGuestPathSystemOnlyWhenNoLocalBins() {
        val root = createTempDirectory("guest-path2").toFile()
        try {
            val path = ContainerRestoreEngine.buildGuestPath(root, "/home/nobody")
            assertEquals(
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                path
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /** Alpine panel needs exo-open --launch TerminalEmulator when package "exo" is missing. */
    @Test
    fun exoOpenShimLaunchesTerminalEmulator() {
        val script = buildExoOpenShimScript()
        assertTrue(script.startsWith("#!/bin/sh"))
        assertTrue(script.contains("--launch"))
        assertTrue(script.contains("TerminalEmulator"))
        assertTrue(script.contains("xfce4-terminal"))
        assertTrue(script.contains("FileManager"))
        assertTrue(script.contains("PocketLinux"))
    }

    @Test
    fun pocketLinuxPromptScriptUsesUsernameNotHostname() {
        val script = buildPocketLinuxPromptScript("PocketLinux")
        assertTrue(script.contains("PS1="))
        assertTrue(script.contains("PocketLinux") || script.contains("__pl_u"))
        // Must not rely on bash \\h (proot → localhost)
        assertFalse(
            "prompt must not use hostname-only \\h form as primary identity",
            script.contains("PS1='\\h:") || script.contains("PS1=\"\\h:")
        )
        assertTrue(script.contains("STARSHIP_SHELL") || script.contains("POCKETLINUX_KEEP_PS1"))
    }

    @Test
    fun patchAlpineStockProfileReplacesHostnameOnlyPs1() {
        val root = createTempDirectory("alpine-ps1").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/alpine-release").writeText("3.20.0\n")
            File(root, "etc/profile").writeText(
                """
                export PATH="/usr/bin"
                if [ -n "${'$'}BASH_VERSION" -o "${'$'}BB_ASH_VERSION" ]; then
                PS1='\h:\w\$ '
                elif [ -n "${'$'}ZSH_VERSION" ]; then
                PS1='%m:%~%# '
                fi
                """.trimIndent() + "\n"
            )
            assertTrue(patchAlpineStockProfilePrompt(root, "PocketLinux"))
            val text = File(root, "etc/profile").readText()
            assertTrue(text.contains("PocketLinux"))
            assertFalse(text.contains("PS1='\\h:\\w\\$ '"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun patchAlpineStockProfileNoOpOnDebian() {
        val root = createTempDirectory("debian-ps1").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/os-release").writeText("ID=debian\n")
            File(root, "etc/profile").writeText("PS1='\\u@\\h:\\w\\$ '\n")
            assertFalse(patchAlpineStockProfilePrompt(root, "PocketLinux"))
            assertEquals("PS1='\\u@\\h:\\w\\$ '\n", File(root, "etc/profile").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
