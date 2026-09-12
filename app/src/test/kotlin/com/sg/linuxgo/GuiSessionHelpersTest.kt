package com.sg.linuxgo

import com.sg.linuxgo.gui.DISPLAY_SCALE_PCT_MAX
import com.sg.linuxgo.gui.DISPLAY_SCALE_PCT_MIN
import com.sg.linuxgo.gui.clampDisplayScalePct
import com.sg.linuxgo.gui.computeScaledDisplaySize
import com.sg.linuxgo.gui.computeSessionResolution
import com.sg.linuxgo.gui.displayResolutionModeForScalePct
import com.sg.linuxgo.gui.fileLooksLikeOldBrowserBlock
import com.sg.linuxgo.gui.isArchRootfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class GuiSessionHelpersTest {

    @Test
    fun clampDisplayScalePctKeepsContinuousRange() {
        assertEquals(100, clampDisplayScalePct(100))
        assertEquals(150, clampDisplayScalePct(150))
        assertEquals(190, clampDisplayScalePct(190))
        assertEquals(DISPLAY_SCALE_PCT_MIN, clampDisplayScalePct(10))
        assertEquals(DISPLAY_SCALE_PCT_MAX, clampDisplayScalePct(999))
        assertEquals(DISPLAY_SCALE_PCT_MIN, clampDisplayScalePct(DISPLAY_SCALE_PCT_MIN))
        assertEquals(DISPLAY_SCALE_PCT_MAX, clampDisplayScalePct(DISPLAY_SCALE_PCT_MAX))
    }

    @Test
    fun displayScaleIsViewerOnlyModeAndLorieMath() {
        assertEquals("native", displayResolutionModeForScalePct(100))
        assertEquals("scaled", displayResolutionModeForScalePct(150))
        assertEquals("scaled", displayResolutionModeForScalePct(200))
        // Termux/Lorie: guest_size = surface * 100 / scale
        assertEquals(1080 to 2400, computeScaledDisplaySize(1080, 2400, 100))
        assertEquals(540 to 1200, computeScaledDisplaySize(1080, 2400, 200))
        assertEquals(720 to 1600, computeScaledDisplaySize(1080, 2400, 150))
    }

    @Test
    fun computeSessionResolutionCustomMode() {
        assertEquals(
            "1600x900",
            computeSessionResolution(
                autoRes = false,
                screenWidthPx = 1080,
                screenHeightPx = 2400,
                scalePct = 100,
                customWidth = 1600,
                customHeight = 900
            )
        )
    }

    @Test
    fun computeSessionResolutionAutoAppliesScaleAndCap() {
        assertEquals(
            "1080x1080",
            computeSessionResolution(true, 1080, 2400, 100)
        )
        assertEquals(
            "1920x1080",
            computeSessionResolution(true, 1080, 1920, 50)
        )
        assertEquals(
            "540x960",
            computeSessionResolution(true, 1080, 1920, 200)
        )
    }

    @Test
    fun fileLooksLikeOldBrowserBlockDetectsMarkers() {
        val f = File.createTempFile("xfce-session", ".sh")
        try {
            f.writeText("#!/bin/sh\n# block-heavy browsers\nexit 0\n")
            assertTrue(fileLooksLikeOldBrowserBlock(f))
            f.writeText("#!/bin/sh\n# browser block lifted\nexec startxfce4\n")
            assertTrue(fileLooksLikeOldBrowserBlock(f))
            f.writeText("#!/bin/sh\nexec startxfce4\n")
            assertFalse(fileLooksLikeOldBrowserBlock(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun isArchRootfsFromArchReleaseOrOsRelease() {
        val root = createTempDirectory("arch-root").toFile()
        try {
            assertFalse(isArchRootfs(root))
            File(root, "etc").mkdirs()
            File(root, "etc/arch-release").writeText("")
            assertTrue(isArchRootfs(root))
            File(root, "etc/arch-release").delete()
            File(root, "etc/os-release").writeText("ID=arch\n")
            assertTrue(isArchRootfs(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun isArchRootfsTreatsArtixAsArchFamily() {
        val root = createTempDirectory("artix-root").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/artix-release").writeText("")
            assertTrue(isArchRootfs(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun desktopReadySettleMsHoldsLongerForXfce() {
        assertEquals(800L, desktopReadySettleMs("timeout"))
        assertEquals(2_200L, desktopReadySettleMs("xfce"))
        assertEquals(1_500L, desktopReadySettleMs("panel"))
    }
}
