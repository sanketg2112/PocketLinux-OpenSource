package com.sg.linuxgo

import com.sg.linuxgo.ui.components.clampFontBoldness
import com.sg.linuxgo.ui.components.clampFontWidthScale
import com.sg.linuxgo.ui.components.scrollStepsFromDrag
import com.sg.linuxgo.ui.components.terminalCapturesTouch
import com.sg.linuxgo.ui.components.zoomFontSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTouchGesturesTest {

    @Test
    fun scrollStepsFromDrag_fingerDownMeansScrollUp() {
        val (steps, rem) = scrollStepsFromDrag(accumDy = 20f, stepPx = 10f)
        assertEquals(2, steps)
        assertEquals(0f, rem, 0.01f)
    }

    @Test
    fun scrollStepsFromDrag_fingerUpMeansScrollDown() {
        val (steps, rem) = scrollStepsFromDrag(accumDy = -15f, stepPx = 10f)
        assertEquals(-1, steps)
        assertEquals(-5f, rem, 0.01f)
    }

    @Test
    fun scrollStepsFromDrag_zeroStepPx() {
        val (steps, rem) = scrollStepsFromDrag(accumDy = 50f, stepPx = 0f)
        assertEquals(0, steps)
        assertEquals(50f, rem, 0.01f)
    }

    @Test
    fun zoomFontSize_clamps() {
        assertEquals(4f, zoomFontSize(12f, 0.1f), 0.01f)
        assertEquals(30f, zoomFontSize(12f, 10f), 0.01f)
        assertEquals(18f, zoomFontSize(12f, 1.5f), 0.01f)
    }

    @Test
    fun zoomFontSize_preservesContinuousValues() {
        // Pinch commit must keep fractional sp so end size matches optical zoom.
        val end = zoomFontSize(12f, 1.37f)
        assertEquals(12f * 1.37f, end, 0.001f)
        assertTrue(end > 12f && end < 30f)
    }

    @Test
    fun terminalCapturesTouch_altOrMouse() {
        assertTrue(terminalCapturesTouch(mouseReporting = true, alternateBuffer = false))
        assertTrue(terminalCapturesTouch(mouseReporting = false, alternateBuffer = true))
        assertFalse(terminalCapturesTouch(mouseReporting = false, alternateBuffer = false))
    }

    @Test
    fun fontBoldnessAndWidthClamps() {
        assertEquals(0f, clampFontBoldness(-1f), 0.001f)
        assertEquals(1f, clampFontBoldness(2f), 0.001f)
        assertEquals(0.30f, clampFontWidthScale(0.1f), 0.001f)
        assertEquals(0.30f, clampFontWidthScale(0.30f), 0.001f)
        assertEquals(1.4f, clampFontWidthScale(3f), 0.001f)
        assertEquals(1f, clampFontWidthScale(1f), 0.001f)
    }

    @Test
    fun mouseEncoder_scrollOnAltWithoutMouseMode() {
        val seq = TerminalMouseEncoder.buildScrollSequences(
            steps = 2,
            col = 5,
            row = 3,
            cols = 80,
            rows = 24,
            mouseReportingEnabled = false,
            alternateBuffer = true,
            alternateScrollMode = false,
            sgrMode = false,
            urxvtMode = false
        )
        // Two cursor-up sequences
        assertTrue(seq.contains("\u001B[A"))
        assertEquals(2, seq.split("\u001B[A").size - 1)
    }

    @Test
    fun mouseEncoder_wheelWhenMouseEnabled() {
        val seq = TerminalMouseEncoder.buildScrollSequences(
            steps = 1,
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
        assertTrue(seq.contains("<64;"))
    }
}
