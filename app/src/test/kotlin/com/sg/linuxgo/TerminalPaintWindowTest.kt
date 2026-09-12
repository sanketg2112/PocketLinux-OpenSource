package com.sg.linuxgo

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPaintWindowTest {

    private fun lines(rows: Int, cols: Int, markRow: Int = -1): List<AnnotatedString> {
        return List(rows) { r ->
            AnnotatedString(if (r == markRow) "x".repeat(cols) else " ".repeat(cols))
        }
    }

    @Test
    fun tuiHorizontalOriginSplitsLeftoverEvenly() {
        assertEquals(10f, terminalGridHorizontalOrigin(100f, 10, 8f), 0.01f)
        assertEquals(0f, terminalGridHorizontalOrigin(80f, 10, 8f), 0.01f)
        assertEquals(-8f, terminalGridHorizontalOrigin(80f, 12, 8f), 0.01f)
        assertEquals(0f, terminalGridHorizontalOrigin(100f, 0, 8f), 0.01f)
        assertEquals(0f, terminalGridHorizontalOrigin(100f, 10, 0f), 0.01f)
    }

    @Test
    fun tuiPaintWindowUsesScreenLocalCursor() {
        val paint = computeTerminalPaintWindow(
            lines = lines(rows = 4, cols = 8, markRow = 2),
            committedRows = 4,
            committedCols = 8,
            cursorX = 3,
            cursorLine = 2,
            isAlternateBuffer = true,
            fittedRows = 4
        )
        assertTrue(paint.tuiMode)
        assertEquals(3, paint.paintCursorX)
        assertEquals(2, paint.paintCursorY)
        assertEquals(4, paint.paintLines.size)
    }

    @Test
    fun wrapPendingCursorStaysOnLastCell() {
        val paint = computeTerminalPaintWindow(
            lines = lines(rows = 4, cols = 8),
            committedRows = 4,
            committedCols = 8,
            cursorX = 8,
            cursorLine = 2,
            isAlternateBuffer = true,
            fittedRows = 4
        )
        assertEquals(7, paint.paintCursorX)
        assertEquals(2, paint.paintCursorY)
    }

    @Test
    fun tuiKeepsCommittedRowsWhenLayoutIsShorter() {
        val paint = computeTerminalPaintWindow(
            lines = lines(rows = 24, cols = 80, markRow = 0),
            committedRows = 24,
            committedCols = 80,
            cursorX = 0,
            cursorLine = 0,
            isAlternateBuffer = true,
            fittedRows = 12
        )
        assertEquals(24, paint.paintLines.size)
        assertEquals(24, paint.screenRows)
    }

    @Test
    fun primaryWindowKeepsCursorVisibleWhenShorter() {
        val paint = computeTerminalPaintWindow(
            lines = lines(rows = 10, cols = 8, markRow = 9),
            committedRows = 10,
            committedCols = 8,
            cursorX = 1,
            cursorLine = 9,
            isAlternateBuffer = false,
            fittedRows = 4
        )
        assertFalse(paint.tuiMode)
        assertEquals(4, paint.paintLines.size)
        assertTrue(paint.paintCursorY in 0..3)
    }

    @Test
    fun imeBottomSpaceHoldsLastHeightDuringKeyboardSwap() {
        assertEquals(0f, terminalImeBottomSpace(true, false, 280f, 0f, 48f), 0.01f)
        assertEquals(280f, terminalImeBottomSpace(false, true, 280f, 40f, 48f), 0.01f)
        assertEquals(300f, terminalImeBottomSpace(false, false, 280f, 300f, 48f), 0.01f)
        assertEquals(48f, terminalImeBottomSpace(false, false, 280f, 0f, 48f), 0.01f)
    }

    @Test
    fun imeBottomSpaceNeverDropsBelowNavWhileClosing() {
        // Last IME frames are often 1..nav-1 px; using those overshoots, then
        // snapping to nav makes the prompt jump back up.
        assertEquals(48f, terminalImeBottomSpace(false, false, 280f, 20f, 48f), 0.01f)
        assertEquals(48f, terminalImeBottomSpace(false, false, 280f, 47f, 48f), 0.01f)
        assertEquals(48f, terminalImeBottomSpace(false, true, 20f, 10f, 48f), 0.01f)
    }

    @Test
    fun stickScrollKeepsCursorOnLastVisibleRow() {
        // 24 lines, cursor on 23, 12 visible → first index 12 so row 23 is last.
        assertEquals(12, terminalStickScrollIndex(23, 24, 12))
        assertEquals(0, terminalStickScrollIndex(5, 6, 24))
        assertEquals(0, terminalStickScrollIndex(0, 0, 12))
        assertEquals(0, terminalStickScrollIndex(0, 10, 12))
    }

    @Test
    fun viewportAtBottomRequiresLastItemOnBottomEdge() {
        assertTrue(terminalViewportIsAtBottom(10, 9, 200, 20, 220))
        assertFalse(terminalViewportIsAtBottom(10, 9, 0, 20, 220))
        assertFalse(terminalViewportIsAtBottom(10, 8, 200, 20, 220))
        assertTrue(terminalViewportIsAtBottom(0, 0, 0, 0, 0))
    }

    @Test
    fun anyUserScrollUnsticksFollowOutput() {
        assertTrue(terminalUserScrollUnsticks(1f))
        assertTrue(terminalUserScrollUnsticks(-4f))
        assertFalse(terminalUserScrollUnsticks(0f))
    }

    @Test
    fun followOutputPausesWhileUserIsDragging() {
        assertTrue(terminalShouldFollowOutput(stickToBottom = true, userScrollInProgress = false))
        assertFalse(terminalShouldFollowOutput(stickToBottom = true, userScrollInProgress = true))
        assertFalse(terminalShouldFollowOutput(stickToBottom = false, userScrollInProgress = false))
        assertFalse(
            terminalShouldFollowOutput(
                stickToBottom = true,
                userScrollInProgress = false,
                ptyRowsChanged = true
            )
        )
        assertFalse(
            terminalShouldFollowOutput(
                stickToBottom = true,
                userScrollInProgress = false,
                geometryQuiet = false
            )
        )
        assertFalse(
            terminalShouldFollowOutput(
                stickToBottom = true,
                userScrollInProgress = false,
                cursorOffscreen = false
            )
        )
    }

    @Test
    fun cursorOffscreenDetectsHiddenCaret() {
        assertFalse(terminalCursorOffscreen(5, firstVisibleLine = 0, visibleRows = 12))
        assertTrue(terminalCursorOffscreen(20, firstVisibleLine = 0, visibleRows = 12))
        assertTrue(terminalCursorOffscreen(2, firstVisibleLine = 10, visibleRows = 12))
    }

    @Test
    fun imeViewportGrowAndShrink() {
        assertTrue(terminalImeViewportGrew(12, 24))
        assertFalse(terminalImeViewportGrew(24, 12))
        assertTrue(terminalImeViewportShrunk(24, 12))
        assertFalse(terminalImeViewportShrunk(12, 24))
        assertFalse(terminalImeViewportGrew(0, 24))
    }

    @Test
    fun keyboardDoesNotMoveTextAlreadyAtTop() {
        assertFalse(
            terminalCursorHiddenByViewportShrink(
                cursorLine = 0,
                firstVisibleLine = 0,
                oldVisibleRows = 24,
                newVisibleRows = 12
            )
        )
    }

    @Test
    fun keyboardCloseNeverHidesCursor() {
        assertFalse(
            terminalCursorHiddenByViewportShrink(
                cursorLine = 23,
                firstVisibleLine = 12,
                oldVisibleRows = 12,
                newVisibleRows = 24
            )
        )
    }

    @Test
    fun keyboardOpenRevealsCoveredPromptWithOneBlankLine() {
        assertTrue(
            terminalCursorHiddenByViewportShrink(
                cursorLine = 23,
                firstVisibleLine = 0,
                oldVisibleRows = 24,
                newVisibleRows = 12
            )
        )
        assertEquals(
            13,
            terminalCursorRevealScrollIndex(
                cursorLine = 23,
                lineCount = 24,
                visibleRows = 12,
                emptyLinesBelow = 1
            )
        )
    }

    @Test
    fun keyboardOpenLeavesHistoryUnmovedWhenPromptIsOffscreen() {
        assertFalse(
            terminalCursorHiddenByViewportShrink(
                cursorLine = 80,
                firstVisibleLine = 0,
                oldVisibleRows = 24,
                newVisibleRows = 12
            )
        )
    }

    @Test
    fun restickOnlyAfterLeavingThenReturningToBottom() {
        assertFalse(
            terminalShouldRestickToBottom(
                isAtBottom = true,
                userScrollInProgress = false,
                hasLeftBottom = false
            )
        )
        assertTrue(
            terminalShouldRestickToBottom(
                isAtBottom = true,
                userScrollInProgress = false,
                hasLeftBottom = true
            )
        )
        assertFalse(
            terminalShouldRestickToBottom(
                isAtBottom = true,
                userScrollInProgress = true,
                hasLeftBottom = true
            )
        )
        assertFalse(
            terminalShouldRestickToBottom(
                isAtBottom = false,
                userScrollInProgress = false,
                hasLeftBottom = true
            )
        )
    }
}
