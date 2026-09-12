package com.sg.linuxgo

import com.sg.linuxgo.ui.components.glyphDrawTransform
import com.sg.linuxgo.ui.theme.GlyphCellAlign
import com.sg.linuxgo.ui.theme.TerminalNerdFont
import com.sg.linuxgo.ui.theme.glyphCellAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalNerdFontTest {

    @Test
    fun powerlineRoundedAndChevronAreSeparators() {
        assertTrue(TerminalNerdFont.isPowerlineSeparator(0xE0B0))
        assertTrue(TerminalNerdFont.isPowerlineSeparator(0xE0B4))
        assertTrue(TerminalNerdFont.isPowerlineSeparator(0xE0B6))
        assertFalse(TerminalNerdFont.isPowerlineSeparator('A'.code))
        assertFalse(TerminalNerdFont.isPowerlineSeparator(0x4E00))
    }

    @Test
    fun separatorAlignsToCellEdge() {
        assertEquals(GlyphCellAlign.START, glyphCellAlign(0xE0B0))
        assertEquals(GlyphCellAlign.START, glyphCellAlign(0xE0B4))
        assertEquals(GlyphCellAlign.END, glyphCellAlign(0xE0B2))
        assertEquals(GlyphCellAlign.END, glyphCellAlign(0xE0B6))
        assertEquals(GlyphCellAlign.CENTER, glyphCellAlign('x'.code))
    }

    @Test
    fun edgeAlignDoesNotCenter() {
        val start = glyphDrawTransform(8f, 10f, 1, GlyphCellAlign.START)
        assertEquals(0f, start.offsetX, 0.001f)
        val end = glyphDrawTransform(8f, 10f, 1, GlyphCellAlign.END)
        assertEquals(2f, end.offsetX, 0.001f)
    }

    @Test
    fun nerdIconRangeCoversPrivateUseAndPowerline() {
        assertTrue(TerminalNerdFont.isNerdIconRange(0xE0A0))
        assertTrue(TerminalNerdFont.isNerdIconRange(0xF067))
        assertTrue(TerminalNerdFont.isNerdIconRange(0xF0001))
        assertFalse(TerminalNerdFont.isNerdIconRange('M'.code))
        assertFalse(TerminalNerdFont.isNerdIconRange('中'.code))
    }

    @Test
    fun fallbackPrefersSymbolsWhenPresent() {
        assertEquals(
            TerminalNerdFont.SYMBOLS_ASSET,
            TerminalNerdFont.fallbackAssetPath { it == TerminalNerdFont.SYMBOLS_ASSET }
        )
        assertEquals(
            TerminalNerdFont.MESLO_ASSET,
            TerminalNerdFont.fallbackAssetPath { it == TerminalNerdFont.MESLO_ASSET }
        )
    }
}
