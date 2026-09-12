package com.sg.linuxgo

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuiFrameHealthTest {

    private fun lines(vararg rows: String): List<AnnotatedString> =
        rows.map { AnnotatedString(it) }

    private fun richTui(rows: Int, cols: Int, fill: String = "X"): PublishedTuiFrame {
        val row = fill.repeat(cols)
        return PublishedTuiFrame(
            lines = List(rows) { AnnotatedString(row) },
            rows = rows,
            cols = cols,
            isAlternateBuffer = true
        )
    }

    @Test
    fun firstFrameIsNeverCollapsed() {
        val next = lines("btop++", "CPU 80%")
        assertFalse(
            TuiFrameHealth.isCollapsedOrSunk(
                previous = null,
                newLines = next,
                newRows = 2,
                newCols = 20
            )
        )
    }

    @Test
    fun sparseWipeIsCollapsed() {
        val prev = richTui(rows = 24, cols = 40)
        val wiped = List(24) { AnnotatedString(" ".repeat(40)) }
        assertTrue(
            TuiFrameHealth.isCollapsedOrSunk(
                previous = prev,
                newLines = wiped,
                newRows = 24,
                newCols = 40
            )
        )
    }

    @Test
    fun fullRedrawIsAccepted() {
        val prev = richTui(rows = 24, cols = 20, fill = "A")
        val next = richTui(rows = 24, cols = 20, fill = "B")
        assertFalse(
            TuiFrameHealth.isCollapsedOrSunk(
                previous = prev,
                newLines = next.lines,
                newRows = 24,
                newCols = 20
            )
        )
    }

    @Test
    fun growLeavesEmptyTail_isSunk() {
        val prev = richTui(rows = 12, cols = 20)
        val grown = prev.lines + List(12) { AnnotatedString(" ".repeat(20)) }
        assertTrue(TuiFrameHealth.isSunkAfterGrow(prev, grown, newRows = 24, newCols = 20))
        assertTrue(
            TuiFrameHealth.isCollapsedOrSunk(
                previous = prev,
                newLines = grown,
                newRows = 24,
                newCols = 20
            )
        )
    }

    @Test
    fun shrinkIsNotSunk() {
        val prev = richTui(rows = 24, cols = 20)
        val cropped = prev.lines.take(12)
        assertFalse(TuiFrameHealth.isSunkAfterGrow(prev, cropped, newRows = 12, newCols = 20))
    }

    @Test
    fun healthyGrowRedrawIsNotSunk() {
        val prev = richTui(rows = 12, cols = 20, fill = "A")
        val next = richTui(rows = 24, cols = 20, fill = "B")
        assertFalse(TuiFrameHealth.isSunkAfterGrow(prev, next.lines, newRows = 24, newCols = 20))
        assertFalse(
            TuiFrameHealth.isCollapsedOrSunk(
                previous = prev,
                newLines = next.lines,
                newRows = 24,
                newCols = 20
            )
        )
    }

    @Test
    fun primaryScreenNeverUsesHold() {
        val prev = PublishedTuiFrame(
            lines = lines("prompt$ "),
            rows = 24,
            cols = 80,
            isAlternateBuffer = false
        )
        val next = lines(" ")
        assertFalse(
            TuiFrameHealth.isCollapsedOrSunk(
                previous = prev,
                newLines = next,
                newRows = 24,
                newCols = 80
            )
        )
    }
}
