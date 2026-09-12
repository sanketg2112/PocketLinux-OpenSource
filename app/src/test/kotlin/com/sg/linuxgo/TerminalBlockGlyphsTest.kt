package com.sg.linuxgo

import com.sg.linuxgo.ui.components.TerminalBlockGlyphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBlockGlyphsTest {

    @Test
    fun fullBlockFillsEntireCell() {
        val fills = TerminalBlockGlyphs.fills(0x2588, 2f, 4f, 10f, 20f)
        assertNotNull(fills)
        assertEquals(1, fills!!.size)
        assertEquals(2f, fills[0].left, 0.001f)
        assertEquals(4f, fills[0].top, 0.001f)
        assertEquals(12f, fills[0].right, 0.001f)
        assertEquals(24f, fills[0].bottom, 0.001f)
    }

    @Test
    fun upperAndLowerHalvesMeetAtMidline() {
        val upper = TerminalBlockGlyphs.fills(0x2580, 0f, 0f, 8f, 16f)!!.single()
        val lower = TerminalBlockGlyphs.fills(0x2584, 0f, 0f, 8f, 16f)!!.single()
        assertEquals(0f, upper.top, 0.001f)
        assertEquals(8f, upper.bottom, 0.001f)
        assertEquals(8f, lower.top, 0.001f)
        assertEquals(16f, lower.bottom, 0.001f)
        assertEquals(8f, upper.right, 0.001f)
        assertEquals(8f, lower.right, 0.001f)
    }

    @Test
    fun lightHorizontalSpansFullWidth() {
        val fills = TerminalBlockGlyphs.fills(0x2500, 0f, 0f, 10f, 20f)!!
        assertEquals(0f, fills.minOf { it.left }, 0.001f)
        assertEquals(10f, fills.maxOf { it.right }, 0.001f)
    }

    @Test
    fun lightVerticalSpansFullHeight() {
        val fills = TerminalBlockGlyphs.fills(0x2502, 0f, 0f, 10f, 20f)!!
        assertEquals(0f, fills.minOf { it.top }, 0.001f)
        assertEquals(20f, fills.maxOf { it.bottom }, 0.001f)
    }

    @Test
    fun allBrailleDotsTileTheCell() {
        val fills = TerminalBlockGlyphs.fills(0x28FF, 0f, 0f, 8f, 16f)!!
        assertEquals(8, fills.size)
        assertEquals(0f, fills.minOf { it.left }, 0.001f)
        assertEquals(0f, fills.minOf { it.top }, 0.001f)
        assertEquals(8f, fills.maxOf { it.right }, 0.001f)
        assertEquals(16f, fills.maxOf { it.bottom }, 0.001f)
    }

    @Test
    fun lettersAreNotSpecial() {
        assertFalse(TerminalBlockGlyphs.isSpecial('A'.code))
        assertFalse(TerminalBlockGlyphs.isSpecial(0x20))
        assertNull(TerminalBlockGlyphs.fills('A'.code, 0f, 0f, 10f, 10f))
        assertTrue(TerminalBlockGlyphs.isSpecial(0x2588))
        assertTrue(TerminalBlockGlyphs.isSpecial(0x2502))
        assertTrue(TerminalBlockGlyphs.isSpecial(0x28FF))
    }
}
