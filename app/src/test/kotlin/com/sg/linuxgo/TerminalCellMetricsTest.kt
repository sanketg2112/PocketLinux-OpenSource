package com.sg.linuxgo

import com.sg.linuxgo.ui.components.glyphDrawTransform
import com.sg.linuxgo.ui.components.latinMonospaceAdvancePx
import com.sg.linuxgo.ui.components.snapTerminalCellWidthPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalCellMetricsTest {

    @Test
    fun latinAdvanceIgnoresWideBoxDrawingOutliers() {
        val advances = mapOf(
            "M" to 10f,
            "W" to 10f,
            "0" to 10f,
            "@" to 10f,
            "x" to 10f,
            " " to 10f,
            "MMMMMMMMMM" to 100f
        )
        val latin = latinMonospaceAdvancePx { advances[it] ?: 20f }
        assertEquals(10f, latin, 0.001f)
        assertEquals(10f, snapTerminalCellWidthPx(latin), 0.001f)
    }

    @Test
    fun snapCellWidthCeilsToWholePixels() {
        assertEquals(1f, snapTerminalCellWidthPx(0f), 0.001f)
        assertEquals(11f, snapTerminalCellWidthPx(10.1f), 0.001f)
        assertEquals(12f, snapTerminalCellWidthPx(12f), 0.001f)
    }

    @Test
    fun wideGlyphScalesToCellInsteadOfWideningGrid() {
        val fit = glyphDrawTransform(glyphWidthPx = 20f, cellWidthPx = 10f, cells = 1)
        assertEquals(0f, fit.offsetX, 0.001f)
        assertEquals(0.5f, fit.scaleX, 0.001f)
    }

    @Test
    fun narrowGlyphIsCenteredInCell() {
        val t = glyphDrawTransform(glyphWidthPx = 8f, cellWidthPx = 10f, cells = 1)
        assertEquals(1f, t.offsetX, 0.001f)
        assertEquals(1f, t.scaleX, 0.001f)
    }

    @Test
    fun doubleWidthCjkUsesBothCells() {
        val t = glyphDrawTransform(glyphWidthPx = 18f, cellWidthPx = 10f, cells = 2)
        assertEquals(1f, t.offsetX, 0.001f)
        assertEquals(1f, t.scaleX, 0.001f)
        val overflow = glyphDrawTransform(glyphWidthPx = 24f, cellWidthPx = 10f, cells = 2)
        assertEquals(0f, overflow.offsetX, 0.001f)
        assertEquals(20f / 24f, overflow.scaleX, 0.001f)
    }

    @Test
    fun pickBestFontPrefersMonoRegularOverPropo() {
        val files = listOf(
            File("/fonts/HackNerdFontPropo-Regular.ttf"),
            File("/fonts/HackNerdFont-Regular.ttf"),
            File("/fonts/HackNerdFontMono-Regular.ttf"),
            File("/fonts/HackNerdFontMono-Italic.ttf")
        )
        val picked = GuestTerminalAppearance.pickBestFontFile(files, "Hack Nerd Font")
        assertEquals("HackNerdFontMono-Regular.ttf", picked!!.name)
    }

    @Test
    fun pickBestFontHonorsExplicitPropoHint() {
        val files = listOf(
            File("/fonts/HackNerdFontMono-Regular.ttf"),
            File("/fonts/HackNerdFontPropo-Regular.ttf")
        )
        val picked = GuestTerminalAppearance.pickBestFontFile(files, "Hack Nerd Font Propo")
        assertEquals("HackNerdFontPropo-Regular.ttf", picked!!.name)
    }

    @Test
    fun scorePenalizesCjkAndEmojiUnlessHinted() {
        val mono = GuestTerminalAppearance.scoreGuestFontFile(
            "NotoSansMono-Regular.ttf",
            "Noto Sans Mono"
        )
        val cjk = GuestTerminalAppearance.scoreGuestFontFile(
            "NotoSansMonoCJK-Regular.ttf",
            "Noto Sans Mono"
        )
        assertTrue(mono > cjk)
    }
}
