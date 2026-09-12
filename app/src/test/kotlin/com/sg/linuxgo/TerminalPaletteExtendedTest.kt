package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TerminalPaletteExtendedTest {

    @Test
    fun get256ColorCubeAndGrayRamp() {
        val scheme = TerminalColorScheme.oneDark()
        // Index 16 = first cube cell (0,0,0)
        val c16 = TerminalPalette.get256Color(16, scheme)
        val c231 = TerminalPalette.get256Color(231, scheme)
        val gray0 = TerminalPalette.get256Color(232, scheme)
        val grayLast = TerminalPalette.get256Color(255, scheme)
        assertNotEquals(c16, c231)
        assertNotEquals(gray0, grayLast)
        assertEquals(scheme.defaultFg, TerminalPalette.get256Color(-1, scheme))
        assertEquals(scheme.defaultFg, TerminalPalette.get256Color(999, scheme))
        assertEquals(scheme.color(5), TerminalPalette.get256Color(5, scheme))
    }

    @Test
    fun applySgrTrueColorAndReset() {
        val scheme = TerminalColorScheme.oneDark()
        val startFg = scheme.defaultFg
        val startBg = 0
        val (fgRgb, bg) = TerminalPalette.applySgr(
            listOf(38, 2, 10, 20, 30),
            startFg,
            startBg,
            scheme
        )
        assertEquals(0, bg)
        // packed RGB should not equal default white-ish unless coincidental
        assertNotEquals(startFg, fgRgb)

        val (fg256, _) = TerminalPalette.applySgr(
            listOf(38, 5, 196),
            startFg,
            startBg,
            scheme
        )
        assertEquals(TerminalPalette.get256Color(196, scheme), fg256)

        val (fgReset, bgReset) = TerminalPalette.applySgr(emptyList(), fg256, 0xFF000000.toInt(), scheme)
        assertEquals(scheme.defaultFg, fgReset)
        assertEquals(0, bgReset)

        val (bright, _) = TerminalPalette.applySgr(listOf(91), startFg, startBg, scheme)
        assertEquals(scheme.color(9), bright)
    }
}
