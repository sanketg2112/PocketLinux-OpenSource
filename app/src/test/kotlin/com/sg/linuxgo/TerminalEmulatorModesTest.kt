package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DEC private modes via CSI sequences (cover TerminalModes through public write path).
 */
class TerminalEmulatorModesTest {

    private fun emu() = TerminalEmulator(rows = 8, cols = 40)

    @Test
    fun mouseReportingModesEnableAndDisable() {
        val e = emu()
        assertFalse(e.isMouseReportingEnabled)
        e.write("\u001B[?1000h")
        assertTrue(e.isMouseReportingEnabled)
        e.write("\u001B[?1006h")
        assertTrue(e.mouseSgrMode)
        e.write("\u001B[?1000l")
        assertFalse(e.isMouseReportingEnabled)
    }

    @Test
    fun alternateBufferAndScrollMode() {
        val e = emu()
        e.write("hello")
        e.write("\u001B[?1049h")
        assertTrue(e.isAlternateBuffer)
        e.write("\u001B[?1007h")
        assertTrue(e.alternateScrollMode)
        e.write("\u001B[?1049l")
        assertFalse(e.isAlternateBuffer)
        e.write("\u001B[?1007l")
        assertFalse(e.alternateScrollMode)
    }

    @Test
    fun cursorVisibilityMode() {
        val e = emu()
        e.write("\u001B[?25l")
        assertFalse(e.isCursorVisible)
        e.write("\u001B[?25h")
        assertTrue(e.isCursorVisible)
    }

    @Test
    fun applicationCursorAndBracketedPaste() {
        val e = emu()
        assertFalse(e.applicationCursorKeys)
        assertFalse(e.bracketedPaste)
        e.write("\u001B[?1h")
        e.write("\u001B[?2004h")
        assertTrue(e.applicationCursorKeys)
        assertTrue(e.bracketedPaste)
        e.write("\u001B[?1l")
        e.write("\u001B[?2004l")
        assertFalse(e.applicationCursorKeys)
        assertFalse(e.bracketedPaste)
    }

    @Test
    fun insertModeShiftsCells() {
        val e = emu()
        e.write("AB")
        e.write("\u001B[1G")
        e.write("\u001B[4h")
        e.write("X")
        assertEquals("XAB", e.getLineText(0).trimEnd())
    }

    @Test
    fun boldSgrIsStoredOnCell() {
        val e = emu()
        e.write("\u001B[1mZ")
        assertTrue(TerminalCellStyle.has(e.getActiveLine(0).effectAt(0), TerminalCellStyle.BOLD))
        e.write("\u001B[0m")
        e.write("\u001B[2GY")
        assertFalse(TerminalCellStyle.has(e.getActiveLine(0).effectAt(1), TerminalCellStyle.BOLD))
    }
}
