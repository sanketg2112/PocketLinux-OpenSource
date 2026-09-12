package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalMouseEncoderTest {

    @Test
    fun defaultEncodingIsX10Style() {
        val seq = TerminalMouseEncoder.buildMouseEvent(
            col = 1,
            row = 1,
            button = 0,
            isRelease = false,
            isMotion = false,
            cols = 80,
            rows = 24,
            sgrMode = false,
            urxvtMode = false
        )
        assertEquals("\u001B[M !!", seq) // space=32+0, '!'=33 for col/row 1
    }

    @Test
    fun sgrEncodingUsesAngleBracketForm() {
        val press = TerminalMouseEncoder.buildMouseEvent(
            col = 10,
            row = 5,
            button = 0,
            isRelease = false,
            isMotion = false,
            cols = 80,
            rows = 24,
            sgrMode = true,
            urxvtMode = false
        )
        assertEquals("\u001B[<0;10;5M", press)

        val release = TerminalMouseEncoder.buildMouseEvent(
            col = 10,
            row = 5,
            button = 0,
            isRelease = true,
            isMotion = false,
            cols = 80,
            rows = 24,
            sgrMode = true,
            urxvtMode = false
        )
        assertEquals("\u001B[<0;10;5m", release)
    }

    @Test
    fun clampsCoordinatesToScreen() {
        val seq = TerminalMouseEncoder.buildMouseEvent(
            col = 999,
            row = 0,
            button = 0,
            isRelease = false,
            isMotion = false,
            cols = 80,
            rows = 24,
            sgrMode = true,
            urxvtMode = false
        )
        assertEquals("\u001B[<0;80;1M", seq)
    }

    @Test
    fun scrollZeroReturnsEmpty() {
        assertEquals(
            "",
            TerminalMouseEncoder.buildScrollSequences(
                steps = 0,
                col = 1,
                row = 1,
                cols = 80,
                rows = 24,
                mouseReportingEnabled = true,
                alternateBuffer = true,
                alternateScrollMode = false,
                sgrMode = true,
                urxvtMode = false
            )
        )
    }

    @Test
    fun scrollUpEmitsWheelAndCursorWhenMouseAndAlt() {
        val seq = TerminalMouseEncoder.buildScrollSequences(
            steps = 2,
            col = 3,
            row = 4,
            cols = 80,
            rows = 24,
            mouseReportingEnabled = true,
            alternateBuffer = true,
            alternateScrollMode = false,
            sgrMode = true,
            urxvtMode = false
        )
        // two wheel-up (64) + two CSI CUU
        val wheel = "\u001B[<64;3;4M"
        val up = "\u001B[A"
        assertTrue(seq.contains(wheel))
        assertEquals(2, seq.split(wheel).size - 1)
        assertEquals(2, seq.split(up).size - 1)
    }

    @Test
    fun scrollWithoutMouseStillEmitsArrows() {
        val seq = TerminalMouseEncoder.buildScrollSequences(
            steps = -1,
            col = 1,
            row = 1,
            cols = 80,
            rows = 24,
            mouseReportingEnabled = false,
            alternateBuffer = false,
            alternateScrollMode = false,
            sgrMode = false,
            urxvtMode = false
        )
        assertEquals("\u001B[B", seq)
    }

    @Test
    fun shouldForwardScrollWhenAnyModeActive() {
        assertFalse(
            TerminalMouseEncoder.shouldForwardScrollToApp(
                alternateBuffer = false,
                mouseReportingEnabled = false,
                alternateScrollMode = false
            )
        )
        assertTrue(
            TerminalMouseEncoder.shouldForwardScrollToApp(
                alternateBuffer = true,
                mouseReportingEnabled = false,
                alternateScrollMode = false
            )
        )
        assertTrue(
            TerminalMouseEncoder.shouldForwardScrollToApp(
                alternateBuffer = false,
                mouseReportingEnabled = true,
                alternateScrollMode = false
            )
        )
        assertTrue(
            TerminalMouseEncoder.shouldForwardScrollToApp(
                alternateBuffer = false,
                mouseReportingEnabled = false,
                alternateScrollMode = true
            )
        )
    }
}
