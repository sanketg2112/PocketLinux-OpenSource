package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ArchPacmanSecurityTest {

    @Test
    fun detectsNeverSigLevel() {
        val conf = """
            [options]
            SigLevel = Never
            LocalFileSigLevel = Never
        """.trimIndent()
        assertTrue(ArchPacmanSecurity.signaturesDisabled(conf))
    }

    @Test
    fun preferSignedLeavesNeverAlone() {
        val conf = "[options]\nSigLevel = Never\n"
        val (next, changed) = ArchPacmanSecurity.preferSignedConf(conf)
        assertFalse(changed)
        assertEquals(conf, next)
    }

    @Test
    fun preferSignedRewritesRequiredLine() {
        val conf = "[options]\nSigLevel = Optional TrustAll\n"
        val (next, changed) = ArchPacmanSecurity.preferSignedConf(conf)
        assertTrue(changed)
        assertTrue(next.contains(ArchPacmanSecurity.SIGNED_SIGLEVEL))
        assertFalse(ArchPacmanSecurity.signaturesDisabled(next))
    }

    @Test
    fun upgradesHttpVerifiedRegionalMirrors() {
        val list = "Server = http://fl.us.mirror.archlinuxarm.org/\$arch/\$repo\n"
        val next = ArchPacmanSecurity.rewriteMirrorsHttps(list)
        assertTrue(next.startsWith("Server = https://fl.us.mirror.archlinuxarm.org/"))
        assertFalse(next.contains("http://"))
    }

    @Test
    fun defaultMirrorlistIsVerifiedHttpsOnly() {
        val list = ArchPacmanSecurity.defaultHttpsMirrorlist()
        assertTrue(list.contains("https://fl.us.mirror.archlinuxarm.org"))
        assertTrue(list.contains("https://ca.us.mirror.archlinuxarm.org"))
        assertTrue(list.contains("https://de3.mirror.archlinuxarm.org"))
        assertFalse(list.contains("http://"))
        assertFalse(list.contains("://mirror.archlinuxarm.org"))
        assertFalse(list.contains("il.us.mirror.archlinuxarm.org"))
        for (line in list.lineSequence()) {
            if (line.startsWith("Server")) {
                assertTrue(line.contains("https://"))
            }
        }
    }

    @Test
    fun dropsGeoIpHttpsInsteadOfRewritingToBrokenCert() {
        val list = "Server = https://mirror.archlinuxarm.org/\$arch/\$repo\n"
        val next = ArchPacmanSecurity.rewriteMirrorsHttps(list)
        assertFalse(next.contains("://mirror.archlinuxarm.org"))
        assertTrue(next.contains("https://fl.us.mirror.archlinuxarm.org"))
        assertEquals(ArchPacmanSecurity.defaultHttpsMirrorlist(), next)
    }

    @Test
    fun officialHttpGeoIpBecomesVerifiedHttpsList() {
        val list = "Server = http://mirror.archlinuxarm.org/\$arch/\$repo\n"
        val next = ArchPacmanSecurity.rewriteMirrorsHttps(list)
        assertFalse(next.contains("://mirror.archlinuxarm.org"))
        assertFalse(next.contains("http://"))
        assertEquals(ArchPacmanSecurity.defaultHttpsMirrorlist(), next)
    }

    @Test
    fun keepsVerifiedRegionalWhenDroppingGeoIpAndDeadHosts() {
        val list = """
            Server = https://fl.us.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
            Server = https://il.us.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
            Server = https://mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
            Server = https://nz.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo
        """.trimIndent() + "\n"
        val next = ArchPacmanSecurity.rewriteMirrorsHttps(list)
        assertTrue(next.contains("https://fl.us.mirror.archlinuxarm.org"))
        assertFalse(next.contains("://mirror.archlinuxarm.org"))
        assertFalse(next.contains("il.us.mirror"))
        assertFalse(next.contains("nz.mirror"))
    }

    @Test
    fun leavesHttpOnOfficialHostWithMismatchedHttpsCert() {
        val list = "Server = http://de.mirror.archlinuxarm.org/\$arch/\$repo\n"
        val next = ArchPacmanSecurity.rewriteMirrorsHttps(list)
        assertTrue(next.contains("http://de.mirror.archlinuxarm.org"))
        assertFalse(next.contains("https://de.mirror.archlinuxarm.org"))
    }

    @Test
    fun dropsHttpsOnHostWithMismatchedCert() {
        val list = "Server = https://de.mirror.archlinuxarm.org/\$arch/\$repo\n"
        val next = ArchPacmanSecurity.rewriteMirrorsHttps(list)
        assertFalse(next.contains("de.mirror.archlinuxarm.org"))
        assertEquals(ArchPacmanSecurity.defaultHttpsMirrorlist(), next)
    }

    @Test
    fun detectsBrokenGeoIpUrl() {
        assertTrue(ArchPacmanSecurity.isBrokenGeoIpMirrorUrl("https://mirror.archlinuxarm.org"))
        assertTrue(ArchPacmanSecurity.isBrokenGeoIpMirrorUrl("http://mirror.archlinuxarm.org/\$arch/\$repo"))
        assertFalse(ArchPacmanSecurity.isBrokenGeoIpMirrorUrl(ArchPacmanSecurity.DEFAULT_MIRROR_BASE))
    }

    @Test
    fun applySecureMirrorlistRepairsExistingArchRootfs() {
        val root = createTempDirectory("arch-mirrors").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/arch-release").writeText("")
            val mirror = File(root, "etc/pacman.d/mirrorlist")
            mirror.parentFile?.mkdirs()
            mirror.writeText("Server = https://mirror.archlinuxarm.org/\$arch/\$repo\n")
            assertTrue(ArchPacmanSecurity.applySecureMirrorlist(root))
            val next = mirror.readText()
            assertFalse(next.contains("://mirror.archlinuxarm.org"))
            assertTrue(next.contains("https://fl.us.mirror.archlinuxarm.org"))
            assertFalse(ArchPacmanSecurity.applySecureMirrorlist(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applySecureMirrorlistSkipsNonArchRootfs() {
        val root = createTempDirectory("debian-mirrors").toFile()
        try {
            File(root, "etc").mkdirs()
            assertFalse(ArchPacmanSecurity.applySecureMirrorlist(root))
            assertFalse(File(root, "etc/pacman.d/mirrorlist").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun artixDefaultMirrorlistUsesRepoOsArchNotAlarm() {
        val list = ArchPacmanSecurity.defaultArtixHttpsMirrorlist()
        assertTrue(list.contains(ArchPacmanSecurity.ARTIX_DEFAULT_MIRROR_BASE))
        assertTrue(list.contains("\$repo/os/\$arch"))
        assertFalse(list.contains("archlinuxarm"))
        assertFalse(list.contains("\$arch/\$repo"))
        assertTrue(list.contains("https://repo.armtixlinux.org/"))
    }

    @Test
    fun looksArtixRootfsFromOsReleaseAndRepos() {
        val root = createTempDirectory("artix-id").toFile()
        try {
            assertFalse(ArchPacmanSecurity.looksArtixRootfs(root))
            val etc = File(root, "etc").apply { mkdirs() }
            File(etc, "os-release").writeText("ID=artix\nID_LIKE=arch\n")
            assertTrue(ArchPacmanSecurity.looksArtixRootfs(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applySecureMirrorlistRewritesArtixAlarmListToArmtix() {
        val root = createTempDirectory("artix-mirrors").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/artix-release").writeText("")
            File(root, "etc/pacman.conf").writeText("[system]\n[world]\n")
            val mirror = File(root, "etc/pacman.d/mirrorlist")
            mirror.parentFile?.mkdirs()
            mirror.writeText("Server = https://fl.us.mirror.archlinuxarm.org/\$arch/\$repo\n")
            assertTrue(ArchPacmanSecurity.applySecureMirrorlist(root))
            val next = mirror.readText()
            assertFalse(next.contains("archlinuxarm"))
            assertTrue(next.contains("armtix.artixlinux.org"))
            assertTrue(next.contains("\$repo/os/\$arch"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applySecureMirrorlistKeepsArmtixMirrors() {
        val root = createTempDirectory("artix-keep").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/artix-release").writeText("")
            val mirror = File(root, "etc/pacman.d/mirrorlist")
            mirror.parentFile?.mkdirs()
            val shipped = ArchPacmanSecurity.defaultArtixHttpsMirrorlist()
            mirror.writeText(shipped)
            assertFalse(ArchPacmanSecurity.applySecureMirrorlist(root))
            assertEquals(shipped, mirror.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun warnsWhenMarkerOrNeverPresent() {
        val root = createTempDirectory("arch-sec").toFile()
        try {
            assertFalse(ArchPacmanSecurity.shouldWarnUser(root))
            val etc = File(root, "etc").apply { mkdirs() }
            File(etc, "pacman.conf").writeText("[options]\nSigLevel = Never\n")
            assertTrue(ArchPacmanSecurity.shouldWarnUser(root))
            File(etc, "pacman.conf").writeText("[options]\nSigLevel = Required DatabaseOptional\n")
            assertFalse(ArchPacmanSecurity.shouldWarnUser(root))
            ArchPacmanSecurity.writeUnsignedMarker(root)
            assertTrue(ArchPacmanSecurity.shouldWarnUser(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
