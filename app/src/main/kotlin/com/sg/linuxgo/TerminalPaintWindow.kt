package com.sg.linuxgo

import androidx.compose.ui.text.AnnotatedString

/**
 * Horizontal origin that splits leftover viewport width equally on both sides
 * of a [cols]×[cellWidthPx] TUI grid. Negative when the grid is wider than the
 * viewport (pinch zoom) so overflow clips evenly too.
 */
internal fun terminalGridHorizontalOrigin(
    viewportWidthPx: Float,
    cols: Int,
    cellWidthPx: Float
): Float {
    if (cols <= 0 || cellWidthPx <= 0f) return 0f
    return (viewportWidthPx - cols * cellWidthPx) / 2f
}

/**
 * Result of computing which lines / cursor row to paint for the live viewport.
 */
internal data class TerminalPaintWindow(
    val allLines: List<AnnotatedString>,
    val paintLines: List<AnnotatedString>,
    val paintCursorX: Int,
    val paintCursorY: Int,
    val tuiMode: Boolean,
    val screenRows: Int
)

/**
 * Window the live screen into the fitted row count so the cursor stays visible
 * when the layout is shorter than the emulator grid (IME / key bar).
 *
 * For TUI / alt-screen, pass [fittedRows] >= [committedRows] (or the screen row
 * count itself) so the full committed frame is painted and the canvas fit-scales
 * into the live viewport — clipping here would fight that path.
 */
internal fun computeTerminalPaintWindow(
    lines: List<AnnotatedString>,
    committedRows: Int,
    committedCols: Int,
    cursorX: Int,
    cursorLine: Int,
    isAlternateBuffer: Boolean,
    fittedRows: Int
): TerminalPaintWindow {
    val screenRows = committedRows.coerceAtLeast(1)
    val liveScreen: List<AnnotatedString> = when {
        lines.isEmpty() -> emptyList()
        lines.size > screenRows -> lines.takeLast(screenRows)
        else -> lines
    }
    val liveCursorY = if (isAlternateBuffer) {
        cursorLine.coerceIn(0, (liveScreen.size - 1).coerceAtLeast(0))
    } else {
        (cursorLine - (lines.size - liveScreen.size))
            .coerceIn(0, (liveScreen.size - 1).coerceAtLeast(0))
    }
    val tuiMode = isAlternateBuffer
    val paintCount = if (tuiMode) {
        minOf(liveScreen.size, screenRows).coerceAtLeast(0)
    } else {
        minOf(liveScreen.size, fittedRows.coerceAtLeast(1)).coerceAtLeast(0)
    }
    val (paintLines, paintCursorY) = if (paintCount <= 0) {
        emptyList<AnnotatedString>() to 0
    } else if (liveScreen.size <= paintCount) {
        liveScreen to liveCursorY
    } else if (tuiMode) {
        liveScreen.takeLast(paintCount) to liveCursorY.coerceIn(0, paintCount - 1)
    } else {
        val cursorRow = liveCursorY.coerceIn(0, liveScreen.lastIndex)
        val maxStart = liveScreen.size - paintCount
        val start = (cursorRow - (paintCount * 2 / 3)).coerceIn(0, maxStart)
        liveScreen.subList(start, start + paintCount) to (cursorRow - start)
    }
    val paintCursorX = cursorX.coerceIn(0, (committedCols - 1).coerceAtLeast(0))
    return TerminalPaintWindow(
        allLines = lines,
        paintLines = paintLines,
        paintCursorX = paintCursorX,
        paintCursorY = paintCursorY,
        tuiMode = tuiMode,
        screenRows = screenRows
    )
}

/**
 * Bottom space under the extra-keys strip, in the same units as the IME / nav
 * inset (typically Dp px). Expanded panel owns its own height, so return 0 then.
 */
internal fun terminalImeBottomSpace(
    showExpandedOverlay: Boolean,
    holdKeybarBottom: Boolean,
    lastImeHeight: Float,
    currentImeHeight: Float,
    navBarHeight: Float
): Float {
    val nav = navBarHeight.coerceAtLeast(0f)
    return when {
        showExpandedOverlay -> 0f
        holdKeybarBottom -> lastImeHeight.coerceAtLeast(nav)
        // Never drop below the nav bar while the IME is closing. Using a raw
        // IME height of 1..nav-1 overshoots the final rest position, then the
        // spacer snaps back up to nav — the “text comes down then goes up”.
        else -> maxOf(currentImeHeight, nav)
    }
}

/**
 * First LazyColumn index that keeps [cursorLine] on the last visible row.
 * [scrollToItem] pins that index to the *top* of the viewport, so scrolling
 * to the last line would yank the prompt to the top after keyboard close.
 */
internal fun terminalStickScrollIndex(
    cursorLine: Int,
    lineCount: Int,
    visibleRows: Int
): Int {
    if (lineCount <= 0) return 0
    val cursor = cursorLine.coerceIn(0, lineCount - 1)
    val visible = visibleRows.coerceAtLeast(1)
    return (cursor - visible + 1).coerceAtLeast(0)
}

/** True when the last line is actually sitting on the bottom edge of the viewport. */
internal fun terminalViewportIsAtBottom(
    totalItems: Int,
    lastVisibleIndex: Int,
    lastVisibleOffset: Int,
    lastVisibleSize: Int,
    viewportEndOffset: Int
): Boolean {
    if (totalItems <= 0) return true
    if (lastVisibleIndex < totalItems - 1) return false
    return lastVisibleOffset + lastVisibleSize >= viewportEndOffset - 2
}

/** Finger/mouse wheel moved the host list — leave follow-output until back at bottom. */
internal fun terminalUserScrollUnsticks(deltaY: Float): Boolean = deltaY != 0f

/**
 * Follow live output only while stuck to the bottom and the user is not dragging.
 * Slow drags used to keep follow-on (last line still “at bottom”) so new lines
 * yanked the viewport back down.
 */
internal fun terminalShouldFollowOutput(
    stickToBottom: Boolean,
    userScrollInProgress: Boolean,
    ptyRowsChanged: Boolean = false,
    geometryQuiet: Boolean = true,
    cursorOffscreen: Boolean = true
): Boolean = stickToBottom &&
    !userScrollInProgress &&
    !ptyRowsChanged &&
    geometryQuiet &&
    cursorOffscreen

internal fun terminalCursorOffscreen(
    cursorLine: Int,
    firstVisibleLine: Int,
    visibleRows: Int
): Boolean {
    if (visibleRows <= 0) return false
    return cursorLine < firstVisibleLine ||
        cursorLine >= firstVisibleLine + visibleRows
}

internal fun terminalImeViewportGrew(originRows: Int, settledRows: Int): Boolean =
    originRows > 0 && settledRows > originRows

internal fun terminalImeViewportShrunk(originRows: Int, settledRows: Int): Boolean =
    originRows > 0 && settledRows < originRows

/**
 * Keyboard opening should scroll only when the caret was on-screen and the
 * smaller view would cover it. History at the top must not jump. Closing
 * (newVisibleRows >= old) never moves the transcript.
 */
internal fun terminalCursorHiddenByViewportShrink(
    cursorLine: Int,
    firstVisibleLine: Int,
    oldVisibleRows: Int,
    newVisibleRows: Int,
    emptyLinesBelow: Int = 1
): Boolean {
    if (newVisibleRows >= oldVisibleRows) return false
    if (oldVisibleRows <= 0 || newVisibleRows <= 0) return false
    val wasVisible =
        cursorLine >= firstVisibleLine &&
            cursorLine < firstVisibleLine + oldVisibleRows
    if (!wasVisible) return false
    val lastPadded = firstVisibleLine + newVisibleRows - 1 - emptyLinesBelow.coerceAtLeast(0)
    return cursorLine > lastPadded
}

/** First index that puts [cursorLine] with [emptyLinesBelow] blank rows under it. */
internal fun terminalCursorRevealScrollIndex(
    cursorLine: Int,
    lineCount: Int,
    visibleRows: Int,
    emptyLinesBelow: Int = 1
): Int {
    if (lineCount <= 0) return 0
    val cursor = cursorLine.coerceIn(0, lineCount - 1)
    val visible = visibleRows.coerceAtLeast(1)
    val targetRow = (visible - 1 - emptyLinesBelow.coerceAtLeast(0)).coerceAtLeast(0)
    return (cursor - targetRow).coerceAtLeast(0)
}

/**
 * Re-enable follow-output only after the user has actually left the bottom
 * and later settled there again. Finger-up between a swipe and its fling still
 * looks “at bottom” for a frame — resticking then kills the fling and yanks
 * the prompt back down.
 */
internal fun terminalShouldRestickToBottom(
    isAtBottom: Boolean,
    userScrollInProgress: Boolean,
    hasLeftBottom: Boolean
): Boolean = hasLeftBottom && isAtBottom && !userScrollInProgress
