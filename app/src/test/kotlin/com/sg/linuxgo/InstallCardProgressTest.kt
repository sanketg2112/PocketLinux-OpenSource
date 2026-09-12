package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallCardProgressTest {

    @Test
    fun stripInlinePercentRemovesPercentFromStatusLines() {
        assertEquals(
            "Downloading (v1): 320/780 MB",
            InstallCardProgress.stripInlinePercent("Downloading (v1): 42% · 320/780 MB")
        )
        assertEquals(
            "Installing (v1)",
            InstallCardProgress.stripInlinePercent("Installing (v1): 76%")
        )
        assertEquals("plain", InstallCardProgress.stripInlinePercent("plain"))
    }

    @Test
    fun formatDownloadSizeShowsMbPairs() {
        val oneMb = 1024L * 1024L
        assertEquals(
            "1.0/2.0 MB",
            InstallCardProgress.formatDownloadSize(oneMb, 2 * oneMb)
        )
        assertEquals(
            "100/200 MB",
            InstallCardProgress.formatDownloadSize(100 * oneMb, 200 * oneMb)
        )
        assertEquals(
            "50 MB",
            InstallCardProgress.formatDownloadSize(50 * oneMb, -1L)
        )
        assertEquals("", InstallCardProgress.formatDownloadSize(100, 1000))
    }

    @Test
    fun showsDownloadSizeRejectsInstallPhases() {
        assertFalse(InstallCardProgress.showsDownloadSize("Installing packages", 100L, 50L))
        assertFalse(InstallCardProgress.showsDownloadSize("Extracting rootfs", 999_999_999L, 1L))
        assertFalse(InstallCardProgress.showsDownloadSize("System Setup", 10L, 1L))
        assertTrue(
            InstallCardProgress.showsDownloadSize(
                "Downloading (v1)",
                10L * 1024 * 1024,
                1L
            )
        )
        assertTrue(
            InstallCardProgress.showsDownloadSize(
                "debian-xfce.tar.gz",
                50L * 1024 * 1024,
                1L
            )
        )
    }

    @Test
    fun percentIsMonotonicOnDownloadProgress() {
        val p = InstallCardProgress()
        p.applyDownloadProgress("Downloading (v1)", 20, 20_000_000, 100_000_000)
        assertEquals(20, p.percent)
        p.applyDownloadProgress("Downloading (v1)", 10, 10_000_000, 100_000_000)
        assertEquals(20, p.percent) // never go backwards
        p.applyDownloadProgress("Downloading (v1)", 45, 45_000_000, 100_000_000)
        assertEquals(45, p.percent)
    }

    @Test
    fun buildMessagePrefersDownloadingLine() {
        val p = InstallCardProgress()
        p.phase = "Phase 2/8: Packages"
        p.percent = 33
        p.downloading = "Downloading (v1): 100/200 MB"
        val (msg, pct) = p.buildMessageAndPercent()
        assertEquals("Downloading (v1): 100/200 MB", msg)
        assertEquals(33, pct)
    }

    @Test
    fun applyHighLevelMessageParsesPhaseDots() {
        val p = InstallCardProgress()
        p.applyHighLevelMessage("Phase 3/8: Configure: still working...")
        assertEquals("Phase 3/8", p.phase)
        assertEquals("...", p.dots)
        assertEquals("", p.downloading)
    }

    @Test
    fun hydrateFromServiceStripsStorageSuffixAndPercent() {
        val p = InstallCardProgress()
        p.hydrateFromService(12, "[debian] Downloading (v1): 40% · 10/20 MB | Storage: 5GB free")
        assertEquals(12, p.percent)
        assertFalse(p.downloading.contains("%"))
        assertTrue(p.downloading.contains("MB") || p.downloading.contains("Downloading"))
    }

    @Test
    fun resetClearsAllFields() {
        val p = InstallCardProgress()
        p.percent = 90
        p.phase = "Almost"
        p.downloading = "x"
        p.dots = ".."
        p.downloadCurrentBytes = 1
        p.downloadTotalBytes = 2
        p.reset()
        assertEquals("Starting setup...", p.phase)
        assertEquals(-1, p.percent)
        assertEquals("", p.downloading)
        assertEquals(-1L, p.downloadCurrentBytes)
    }
}
