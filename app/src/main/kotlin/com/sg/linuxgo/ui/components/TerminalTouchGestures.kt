package com.sg.linuxgo.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Unified terminal surface gestures:
 * - Two-finger pinch → font size (zoom)
 * - One-finger when mouse reporting → xterm mouse press/drag/release
 * - One-finger vertical drag on alt buffer / mouse apps → scroll into the PTY
 *
 * [toCell] maps a surface position to 1-based (col, row), or null if outside the grid.
 */
suspend fun PointerInputScope.detectTerminalSurfaceGestures(
    touchSlop: Float,
    cellHeightPx: () -> Float,
    fontSizeSp: () -> Float,
    mouseReporting: () -> Boolean,
    forwardScroll: () -> Boolean,
    toCell: (Offset) -> Pair<Int, Int>?,
    onMouseEvent: (col: Int, row: Int, button: Int, isRelease: Boolean, isMotion: Boolean) -> Unit,
    onScrollSteps: (steps: Int, col: Int, row: Int) -> Unit,
    /**
     * Live optical zoom factor relative to the font size at pinch start (1 = unchanged).
     * Called every frame during a two-finger pinch — must be cheap (no prefs / PTY resize).
     */
    onPinchScale: (scale: Float) -> Unit,
    /** Final font size (sp) when the pinch ends; commit prefs + grid here. */
    onPinchEnd: (finalSizeSp: Float) -> Unit,
    /** Long-press (no drag) at a cell — word select / context. */
    onLongPress: ((col: Int, row: Int) -> Unit)? = null,
    /** Long-press at a surface position (absolute coords, including scrollback). */
    onLongPressAt: ((Offset) -> Unit)? = null,
    /** Finger move after a long-press started a text selection. */
    onSelectionDragAt: ((Offset) -> Unit)? = null,
    /** Short tap (used to select a URL or dismiss selection). */
    onTapAt: ((Offset) -> Unit)? = null,
    hasSelection: () -> Boolean = { false },
    longPressTimeoutMs: Long = 400L
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startPos = down.position
        val startFont = fontSizeSp()

        var pinchMode = false
        var mouseMode = false
        var scrollMode = false
        var decided = false
        var pressSent = false
        var zoomProduct = 1f
        var accumDy = 0f
        var lastCol = -1
        var lastRow = -1
        var lastScrollCol = 1
        var lastScrollRow = 1
        var lastPos = startPos
        var maxDist = 0f
        var longPressFired = false
        val downTime = android.os.SystemClock.uptimeMillis()
        val selectionAllowed = { onLongPressAt != null || onLongPress != null }

        fun fireLongPress(pos: Offset) {
            longPressFired = true
            decided = true
            onLongPressAt?.invoke(pos)
            if (onLongPress != null) {
                toCell(pos)?.let { (c, r) -> onLongPress.invoke(c, r) }
            }
        }

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }

            if (pressed.isEmpty()) {
                // End of gesture
                if (!longPressFired &&
                    !pinchMode && !mouseMode && !scrollMode &&
                    maxDist < touchSlop
                ) {
                    val heldLong =
                        android.os.SystemClock.uptimeMillis() - downTime >= longPressTimeoutMs
                    if (heldLong && selectionAllowed() && !mouseReporting()) {
                        fireLongPress(startPos)
                    } else {
                        onTapAt?.invoke(startPos)
                    }
                }
                if (pinchMode) {
                    val finalSize = zoomFontSize(startFont, zoomProduct)
                    onPinchEnd(finalSize)
                    break
                }
                if (pressSent && mouseMode) {
                    val releasePos = event.changes.firstOrNull()?.position ?: lastPos
                    toCell(releasePos)?.let { (c, r) ->
                        onMouseEvent(c, r, 0, true, false)
                    } ?: run {
                        if (lastCol > 0) onMouseEvent(lastCol, lastRow, 0, true, false)
                    }
                } else if (
                    !scrollMode &&
                    mouseReporting() &&
                    maxDist < touchSlop
                ) {
                    // Tap → click
                    toCell(startPos)?.let { (c, r) ->
                        onMouseEvent(c, r, 0, false, false)
                        onMouseEvent(c, r, 0, true, false)
                    }
                }
                break
            }

            if (pressed.size >= 2) {
                if (!pinchMode) {
                    pinchMode = true
                    mouseMode = false
                    scrollMode = false
                    decided = true
                    // Optical zoom starts at 1; UI freezes PTY size until onPinchEnd.
                    onPinchScale(1f)
                }
                val zoomChange = event.calculateZoom()
                if (zoomChange != 1f) {
                    // Amplify a bit so small finger motion feels snappy (still continuous).
                    val amplified = 1f + (zoomChange - 1f) * 1.35f
                    zoomProduct = (zoomProduct * amplified).coerceIn(
                        4f / startFont.coerceAtLeast(0.1f),
                        30f / startFont.coerceAtLeast(0.1f)
                    )
                    onPinchScale(zoomProduct)
                }
                pressed.forEach { if (it.positionChanged()) it.consume() }
                continue
            }

            val change = pressed.first()
            val pos = change.position
            lastPos = pos
            if (
                !longPressFired &&
                !pinchMode && !decided &&
                selectionAllowed() &&
                !mouseReporting() &&
                maxDist < touchSlop &&
                android.os.SystemClock.uptimeMillis() - downTime >= longPressTimeoutMs
            ) {
                fireLongPress(startPos)
            }
            toCell(pos)?.let { (c, r) ->
                lastScrollCol = c
                lastScrollRow = r
            }
            val dist = distance(startPos, pos)
            if (dist > maxDist) maxDist = dist

            if (pinchMode) {
                // Second finger may have lifted; keep consuming until both up so we
                // still fire onPinchEnd once when pressed.isEmpty().
                change.consume()
                continue
            }

            if (longPressFired) {
                if (dist >= touchSlop) {
                    onSelectionDragAt?.invoke(pos)
                }
                change.consume()
                continue
            }

            if (hasSelection() && dist >= touchSlop) {
                // Freeze host scroll / mouse while a selection is showing so handles
                // do not slip. A new long-press (above) still replaces the range.
                change.consume()
                continue
            }

            if (!decided && dist >= touchSlop) {
                decided = true
                val dx = pos.x - startPos.x
                val dy = pos.y - startPos.y
                val vertical = abs(dy) >= abs(dx) * 1.1f
                when {
                    mouseReporting() && !(forwardScroll() && vertical && abs(dy) > abs(dx) * 1.5f) -> {
                        mouseMode = true
                    }
                    forwardScroll() && vertical -> {
                        scrollMode = true
                    }
                    mouseReporting() -> {
                        mouseMode = true
                    }
                    forwardScroll() -> {
                        scrollMode = true
                    }
                }
                if (mouseMode && !pressSent) {
                    toCell(startPos)?.let { (c, r) ->
                        onMouseEvent(c, r, 0, false, false)
                        lastCol = c
                        lastRow = r
                        pressSent = true
                    }
                }
            }

            if (mouseMode && mouseReporting()) {
                if (!pressSent) {
                    toCell(startPos)?.let { (c, r) ->
                        onMouseEvent(c, r, 0, false, false)
                        lastCol = c
                        lastRow = r
                        pressSent = true
                    }
                }
                toCell(pos)?.let { (c, r) ->
                    if (c != lastCol || r != lastRow) {
                        onMouseEvent(c, r, 0, false, true)
                        lastCol = c
                        lastRow = r
                    }
                }
                change.consume()
            } else if (scrollMode && forwardScroll()) {
                val deltaY = change.position.y - change.previousPosition.y
                accumDy += deltaY
                val cellH = cellHeightPx().coerceAtLeast(1f)
                val stepPx = cellH * 0.55f
                val (steps, remaining) = scrollStepsFromDrag(accumDy, stepPx)
                accumDy = remaining
                if (steps != 0) {
                    onScrollSteps(steps, lastScrollCol, lastScrollRow)
                }
                change.consume()
            } else {
                // Undecided micro-move or non-capturing — still consume on TUI surfaces
                // so LazyColumn does not steal the gesture after we disable user scroll.
                if (mouseReporting() || forwardScroll()) {
                    change.consume()
                }
            }
        }
    }
}

/** True when the terminal should capture touch for TUI / mouse instead of host scrollback. */
fun terminalCapturesTouch(mouseReporting: Boolean, alternateBuffer: Boolean): Boolean =
    mouseReporting || alternateBuffer

/**
 * Pure helper: map cumulative vertical drag (px) into integer scroll steps.
 * Positive [accumDy] (finger moved down) → positive steps (scroll up / older content).
 */
fun scrollStepsFromDrag(
    accumDy: Float,
    stepPx: Float
): Pair<Int, Float> {
    if (stepPx <= 0f) return 0 to accumDy
    var acc = accumDy
    var steps = 0
    while (acc <= -stepPx) {
        steps -= 1
        acc += stepPx
    }
    while (acc >= stepPx) {
        steps += 1
        acc -= stepPx
    }
    return steps to acc
}

/** Apply pinch zoom factor to a font size (sp), clamped. */
fun zoomFontSize(currentSp: Float, zoom: Float, minSp: Float = 4f, maxSp: Float = 30f): Float =
    (currentSp * zoom).coerceIn(minSp, maxSp)

private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
