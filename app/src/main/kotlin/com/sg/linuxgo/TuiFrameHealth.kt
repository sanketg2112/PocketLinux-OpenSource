package com.sg.linuxgo

import androidx.compose.ui.text.AnnotatedString

/** Last healthy TUI snapshot kept so sparse / sunk resize frames are not painted. */
internal data class PublishedTuiFrame(
    val lines: List<AnnotatedString>,
    val rows: Int,
    val cols: Int,
    val isAlternateBuffer: Boolean,
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val isCursorVisible: Boolean = true
)

/**
 * Detects the two TUI paint failures that look like a “broken” full-screen app:
 * a wipe/sparse redraw replacing a rich screen, and a grown grid that still has
 * the old UI in the top rows with empty space (or a new redraw) shoved below.
 */
internal object TuiFrameHealth {

    fun nonSpaceCells(lines: List<AnnotatedString>): Int {
        var n = 0
        for (line in lines) {
            val text = line.text
            for (i in text.indices) {
                val ch = text[i]
                if (ch != ' ' && ch != '\n' && ch != '\t') n++
            }
        }
        return n
    }

    /**
     * True when [newLines] should not replace [previous] on screen.
     *
     * Local alt-buffer resize copies old rows at the top and adds empty rows at
     * the bottom. Publishing that frame is the “stale top half / live UI sunk
     * into the bottom” bug after keyboard or app-switch. Sparse wipes (clear,
     * TUI ED) are the blank flash while the app redraws.
     */
    fun isCollapsedOrSunk(
        previous: PublishedTuiFrame?,
        newLines: List<AnnotatedString>,
        newRows: Int,
        newCols: Int
    ): Boolean {
        if (previous == null || !previous.isAlternateBuffer) return false
        if (isSunkAfterGrow(previous, newLines, newRows, newCols)) return true
        val oldN = nonSpaceCells(previous.lines)
        if (oldN < 12) return false
        val newN = nonSpaceCells(newLines)
        return newN * 4 < oldN
    }

    /**
     * Grown grid whose extra bottom rows are empty while the previous TUI still
     * occupies the top — the live app has not redrawn into the new height yet.
     */
    fun isSunkAfterGrow(
        previous: PublishedTuiFrame,
        newLines: List<AnnotatedString>,
        newRows: Int,
        newCols: Int
    ): Boolean {
        if (!previous.isAlternateBuffer) return false
        if (newRows <= previous.rows) return false
        if (newCols != previous.cols && newCols < 1) return false
        val extra = newRows - previous.rows
        if (extra < 2 || newLines.size < previous.rows + 1) return false
        val tail = if (newLines.size >= extra) {
            newLines.takeLast(extra.coerceAtMost(newLines.size))
        } else {
            emptyList()
        }
        val headCount = previous.rows.coerceAtMost(newLines.size)
        val head = newLines.take(headCount)
        val tailN = nonSpaceCells(tail)
        val headN = nonSpaceCells(head)
        return tailN <= 2 && headN >= 12
    }
}
