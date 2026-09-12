package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCharWidthTest {

    @Test
    fun asciiIsNarrow() {
        assertEquals(1, TerminalCharWidth.charWidth('A'.code))
        assertEquals(1, TerminalCharWidth.charWidth(' '.code))
        assertEquals(1, TerminalCharWidth.charWidth('9'.code))
    }

    @Test
    fun controlsAreZeroWidth() {
        assertEquals(0, TerminalCharWidth.charWidth(0))
        assertEquals(0, TerminalCharWidth.charWidth(0x07)) // BEL
        assertEquals(0, TerminalCharWidth.charWidth(0x1B)) // ESC
        assertEquals(0, TerminalCharWidth.charWidth(0x9B)) // CSI (C1)
    }

    @Test
    fun cjkAndFullwidthAreWide() {
        assertEquals(2, TerminalCharWidth.charWidth('中'.code))
        assertEquals(2, TerminalCharWidth.charWidth('あ'.code))
        assertEquals(2, TerminalCharWidth.charWidth(0xFF21)) // fullwidth A
        assertEquals(2, TerminalCharWidth.charWidth(0xAC00)) // Hangul
    }

    @Test
    fun combiningMarksAreZeroWidth() {
        assertEquals(0, TerminalCharWidth.charWidth(0x0301)) // combining acute
        assertEquals(0, TerminalCharWidth.charWidth(0x200B)) // zero-width space
        assertEquals(0, TerminalCharWidth.charWidth(0x200D)) // ZWJ
    }

    @Test
    fun emojiAreWide() {
        assertEquals(2, TerminalCharWidth.charWidth(0x1F600)) // grinning face
        assertEquals(2, TerminalCharWidth.charWidth(0x1F680)) // rocket
    }
}
