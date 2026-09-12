package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety-net tests for the VT/xterm emulator. These exist so mechanical
 * refactors (file splits under the 1000-line cap) cannot silently break
 * cursor motion, erase, SGR, mouse encoding, or basic text output.
 */
class TerminalEmulatorTest {

    private fun emu(rows: Int = 6, cols: Int = 20) = TerminalEmulator(rows = rows, cols = cols)

    private fun lineText(e: TerminalEmulator, row: Int): String =
        e.getLineText(row).trimEnd()

    @Test
    fun plainTextAdvancesCursorAndFillsCells() {
        val e = emu()
        e.write("hi")
        assertEquals(2, e.cursorX)
        assertEquals(0, e.cursorY)
        assertEquals("hi", lineText(e, 0))
    }

    @Test
    fun crLfMovesToNextLine() {
        val e = emu()
        e.write("ab\r\ncd")
        assertEquals("ab", lineText(e, 0))
        assertEquals("cd", lineText(e, 1))
        assertEquals(2, e.cursorX)
        assertEquals(1, e.cursorY)
    }

    @Test
    fun backspaceMovesCursorLeftWithoutDeleting() {
        val e = emu()
        e.write("xy")
        e.write("\b")
        assertEquals(1, e.cursorX)
        // Cell content is not erased by BS alone
        assertEquals("xy", lineText(e, 0))
    }

    @Test
    fun csiCupPositionsCursor() {
        val e = emu()
        e.write("\u001B[3;5H")
        assertEquals(4, e.cursorX) // 1-based col 5 → 0-based 4
        assertEquals(2, e.cursorY) // 1-based row 3 → 0-based 2
    }

    @Test
    fun csiCursorMotion() {
        val e = emu()
        e.write("\u001B[4;6H")
        e.write("\u001B[2A") // up 2
        assertEquals(1, e.cursorY)
        e.write("\u001B[3C") // forward 3
        assertEquals(8, e.cursorX)
        e.write("\u001B[1B") // down 1
        assertEquals(2, e.cursorY)
        e.write("\u001B[2D") // back 2
        assertEquals(6, e.cursorX)
    }

    @Test
    fun csiEraseInLineClearsFromCursor() {
        val e = emu()
        e.write("ABCDEF")
        e.write("\u001B[3G") // col 3 (0-based 2)
        e.write("\u001B[0K") // erase to end of line
        assertEquals("AB", lineText(e, 0))
    }

    @Test
    fun csiEraseDisplayClearsScreen() {
        val e = emu()
        e.write("hello\r\nworld")
        e.write("\u001B[2J")
        assertEquals("", lineText(e, 0))
        assertEquals("", lineText(e, 1))
    }

    @Test
    fun sgrDoesNotCrashAndKeepsText() {
        val e = emu()
        // bold + red FG + reset
        e.write("\u001B[1;31mRED\u001B[0m")
        assertEquals("RED", lineText(e, 0))
        assertEquals(3, e.cursorX)
    }

    @Test
    fun clearAllResetsCursorAndBuffer() {
        val e = emu()
        e.write("stuff\r\nmore")
        e.clearAll()
        assertEquals(0, e.cursorX)
        assertEquals(0, e.cursorY)
        assertEquals("", lineText(e, 0))
        assertEquals(0, e.getScrollbackSize())
    }

    @Test
    fun scrollOnLastLineCreatesScrollback() {
        val e = emu(rows = 3, cols = 10)
        e.write("L0\r\nL1\r\nL2\r\nL3")
        assertTrue(e.getScrollbackSize() >= 1)
        assertEquals("L3", lineText(e, 2).trimEnd())
    }

    @Test
    fun deviceStatusReportEmitsCursorPosition() {
        val e = emu()
        var response: String? = null
        e.onResponse = { response = it }
        e.write("\u001B[2;4H")
        e.write("\u001B[6n")
        assertEquals("\u001B[2;4R", response)
    }

    @Test
    fun mouseEventDefaultEncoding() {
        val e = emu()
        val seq = e.buildMouseEvent(col = 1, row = 1, button = 0, isRelease = false)
        // CSI M + (button+32) + (col+32) + (row+32)
        assertEquals("\u001B[M !!", seq)
    }

    @Test
    fun mouseEventSgrEncoding() {
        val e = emu()
        e.write("\u001B[?1006h") // SGR mouse mode
        val press = e.buildMouseEvent(col = 5, row = 3, button = 0, isRelease = false)
        val release = e.buildMouseEvent(col = 5, row = 3, button = 0, isRelease = true)
        assertEquals("\u001B[<0;5;3M", press)
        assertEquals("\u001B[<0;5;3m", release)
    }

    @Test
    fun alternateBufferToggle() {
        val e = emu()
        e.write("main")
        e.write("\u001B[?1049h")
        assertTrue(e.isAlternateBuffer)
        // Cursor is not reset on buffer switch; home first so text lands at (0,0).
        e.write("\u001B[H")
        e.write("alt")
        assertEquals("alt", lineText(e, 0))
        e.write("\u001B[?1049l")
        assertFalse(e.isAlternateBuffer)
        assertEquals("main", lineText(e, 0))
    }

    @Test
    fun charWidthAsciiIsOne() {
        assertEquals(1, TerminalEmulator.charWidth('A'.code))
        assertEquals(0, TerminalEmulator.charWidth(0))
        assertEquals(0, TerminalEmulator.charWidth('\n'.code))
    }
}
