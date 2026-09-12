package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ArtixGlibcRepairTest {

    @Test
    fun repairNeededOnArtixWithoutStamp() {
        val root = createTempDirectory("artix-glibc").toFile()
        try {
            assertFalse(needsArtixGlibcRepair(root))
            File(root, "etc").mkdirs()
            File(root, "etc/artix-release").writeText("")
            assertTrue(needsArtixGlibcRepair(root))
            val stamp = File(root, ARTIX_GLIBC_REPAIR_STAMP)
            stamp.parentFile?.mkdirs()
            stamp.writeText("ok\n")
            assertFalse(needsArtixGlibcRepair(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repairSkippedOnDebian() {
        val root = createTempDirectory("debian-glibc").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/os-release").writeText("ID=debian\n")
            assertFalse(needsArtixGlibcRepair(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repairScriptUpgradesGlibcNotKernel() {
        val script = artixGlibcRepairScript()
        assertTrue(script.contains("glibc filesystem gcc-libs pcre2"))
        assertTrue(script.contains("--overwrite '*'"))
        assertFalse(script.contains("linux-aarch64"))
    }
}
