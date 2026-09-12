package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCellStyleTest {

    @Test
    fun applyAndClearAttributes() {
        var fx = TerminalCellStyle.NONE
        fx = TerminalCellStyle.applyAttribute(fx, 1)
        assertTrue(TerminalCellStyle.has(fx, TerminalCellStyle.BOLD))
        fx = TerminalCellStyle.applyAttribute(fx, 4)
        assertTrue(TerminalCellStyle.has(fx, TerminalCellStyle.UNDERLINE))
        fx = TerminalCellStyle.applyAttribute(fx, 22)
        assertFalse(TerminalCellStyle.has(fx, TerminalCellStyle.BOLD))
        assertTrue(TerminalCellStyle.has(fx, TerminalCellStyle.UNDERLINE))
        fx = TerminalCellStyle.applyAttribute(fx, 0)
        assertEquals(TerminalCellStyle.NONE, fx)
    }

    @Test
    fun unknownCodeReturnsMinusOne() {
        assertEquals(-1, TerminalCellStyle.applyAttribute(0, 31))
    }
}
