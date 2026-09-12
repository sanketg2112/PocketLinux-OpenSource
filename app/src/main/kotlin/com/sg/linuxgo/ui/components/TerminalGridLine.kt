package com.sg.linuxgo.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.sg.linuxgo.TerminalEmulator
import com.sg.linuxgo.ui.theme.TerminalNerdFont
import com.sg.linuxgo.ui.theme.glyphCellAlign

/**
 * Renders one terminal row on a fixed character grid.
 *
 * Unlike Compose [androidx.compose.material3.Text], every code point is placed at
 * `col * cellWidth`, so panel borders (│) stay perfectly vertical even when the
 * line has many color spans (lazygit / btop / htop) or a small font size.
 *
 * [canvasBackground] is painted first so erased cells (spaces) fully replace any
 * previous glyphs — skipping space draws without a clear left ghost letters after
 * backspace in TUI input fields.
 */
@Composable
fun TerminalGridLine(
    text: AnnotatedString,
    metrics: TerminalCellMetrics,
    defaultColor: Color,
    modifier: Modifier = Modifier,
    canvasBackground: Color = Color.Transparent,
    /** Bumps force-redraw when StateFlow would otherwise treat frames as equal. */
    frameSeq: Long = 0L
) {
    val density = LocalDensity.current
    val cellHeightDp = with(density) { metrics.cellHeightPx.toDp() }
    val textPaint = remember(
        metrics.typeface,
        metrics.fontSizePx,
        metrics.boldness,
        metrics.widthScale
    ) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = metrics.typeface
            textSize = metrics.fontSizePx
            textScaleX = clampFontWidthScale(metrics.widthScale)
            isSubpixelText = false
            isLinearText = true
            hinting = Paint.HINTING_OFF
            applyFakeBoldStroke(this, metrics.boldness, metrics.fontSizePx)
        }
    }
    val bgPaint = remember {
        Paint().apply { style = Paint.Style.FILL }
    }
    val nerdPaint = remember(textPaint, metrics.nerdFallback) {
        Paint(textPaint).apply {
            metrics.nerdFallback?.let { typeface = it }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(cellHeightDp)
    ) {
        // Read frameSeq so Compose invalidates this draw when only cells changed.
        @Suppress("UNUSED_EXPRESSION")
        frameSeq

        val cellW = metrics.cellWidthPx
        val cellH = metrics.cellHeightPx
        val baseline = metrics.baselinePx

        // Compose-managed clear (participates in layer invalidation; native-only clears
        // can leave previous glyphs on some devices after backspace).
        if (canvasBackground.alpha > 0f) {
            drawRect(color = canvasBackground, size = Size(size.width, cellH))
        }

        val content = text.text
        if (content.isEmpty()) return@Canvas

        var i = 0
        var col = 0

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            while (i < content.length) {
                val cp = Character.codePointAt(content, i)
                val charCount = Character.charCount(cp)
                val cells = glyphCellWidth(cp)
                if (cells > 0) {
                    val fg = foregroundAt(text, i, defaultColor)
                    val bg = backgroundAt(text, i)
                    val left = col * cellW
                    val right = (col + cells) * cellW
                    val cellBg = if (bg != null && bg != Color.Unspecified && bg.alpha > 0f) {
                        bg
                    } else {
                        canvasBackground
                    }
                    // Always paint the cell solid first so a space truly erases the prior glyph.
                    if (cellBg.alpha > 0f) {
                        bgPaint.color = cellBg.toArgb()
                        native.drawRect(left, 0f, right, cellH, bgPaint)
                    }

                    if (cp != ' '.code) {
                        val ch = content.substring(i, i + charCount)
                        val paint = paintForGlyph(textPaint, nerdPaint, ch, cp)
                        paint.color = fg.toArgb()
                        val save = native.save()
                        native.clipRect(left, 0f, right, cellH)
                        bgPaint.color = fg.toArgb()
                        drawCellGlyph(
                            native,
                            paint,
                            bgPaint,
                            ch,
                            left,
                            0f,
                            baseline,
                            cellW,
                            cellH,
                            cells,
                            cp
                        )
                        if (isUnderlined(text, i)) {
                            val underlineY = (baseline + 2f).coerceAtMost(cellH - 1f)
                            bgPaint.color = fg.toArgb()
                            native.drawRect(left, underlineY, right, underlineY + 1.5f, bgPaint)
                        }
                        native.restoreToCount(save)
                    }
                    col += cells
                }
                i += charCount
            }
        }
    }
}

/**
 * Full-screen TUI grid as a **single** Canvas. Avoids per-line Compose nodes so keyboard
 * resize / frame updates don't thrash layout (stable surface redraw).
 *
 * [canvasBackground] fills the entire grid before glyphs so backspace/erase in TUI apps
 * (Grok Build, btop inputs, etc.) never leaves ghost characters from a previous frame.
 *
 * When [fitToViewport] is true (layout size ≠ committed PTY size — IME mid-flight),
 * the committed [gridCols]×[gridRows] frame is scaled to fill the viewport so the
 * keyboard motion is continuous without mid-animation SIGWINCH. When false (settled),
 * cells are painted 1:1 and top-aligned — no stretch bounce.
 * [extraScaleX]/[extraScaleY] layer pinch zoom on top.
 */
@Composable
fun TerminalTuiGrid(
    lines: List<AnnotatedString>,
    metrics: TerminalCellMetrics,
    defaultColor: Color,
    cursorX: Int,
    cursorY: Int,
    cursorVisible: Boolean,
    cursorColor: Color,
    modifier: Modifier = Modifier.fillMaxSize(),
    canvasBackground: Color = Color.Transparent,
    /** Monotonic generation from the ViewModel — must change every emulator frame. */
    frameSeq: Long = 0L,
    /** Logical columns of the committed PTY grid (for fit-scale). */
    gridCols: Int = 0,
    /** Logical rows of the committed PTY grid (for fit-scale). */
    gridRows: Int = 0,
    /** Pinch / optical zoom on top of layout fit-scale. */
    extraScaleX: Float = 1f,
    extraScaleY: Float = 1f,
    /**
     * True while the live viewport row/col count differs from the committed PTY
     * (keyboard animating, pinch pending). False when settled for crisp 1:1 cells.
     */
    fitToViewport: Boolean = false,
    /** Screen-space X of column 0 so leftover width is split left/right. */
    originX: Float = 0f
) {
    val textPaint = remember(
        metrics.typeface,
        metrics.fontSizePx,
        metrics.boldness,
        metrics.widthScale
    ) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = metrics.typeface
            textSize = metrics.fontSizePx
            textScaleX = clampFontWidthScale(metrics.widthScale)
            isSubpixelText = false
            isLinearText = true
            hinting = Paint.HINTING_OFF
            applyFakeBoldStroke(this, metrics.boldness, metrics.fontSizePx)
        }
    }
    val bgPaint = remember {
        Paint().apply { style = Paint.Style.FILL }
    }
    val cursorPaint = remember {
        Paint().apply { style = Paint.Style.FILL }
    }
    val nerdPaint = remember(textPaint, metrics.nerdFallback) {
        Paint(textPaint).apply {
            metrics.nerdFallback?.let { typeface = it }
        }
    }

    // Fill the parent viewport (not lines×cellHeight). During IME animation the
    // fitted row count changes every frame; a fixed fillMaxSize surface only
    // invalidates draw, not layout, which keeps open/close smooth.
    Canvas(
        modifier = modifier
    ) {
        // Ensure draw re-runs when the emulator publishes a new frame with equal-looking text.
        @Suppress("UNUSED_EXPRESSION")
        frameSeq
        @Suppress("UNUSED_EXPRESSION")
        extraScaleX
        @Suppress("UNUSED_EXPRESSION")
        extraScaleY
        @Suppress("UNUSED_EXPRESSION")
        fitToViewport
        @Suppress("UNUSED_EXPRESSION")
        originX

        val cellW = metrics.cellWidthPx
        val cellH = metrics.cellHeightPx
        val baseline = metrics.baselinePx
        val logicalCols = when {
            gridCols > 0 -> gridCols
            cellW > 0f -> (size.width / cellW).toInt().coerceAtLeast(1)
            else -> 1
        }
        val logicalRows = when {
            gridRows > 0 -> gridRows
            cellH > 0f -> (size.height / cellH).toInt().coerceAtLeast(1)
            else -> 1
        }
        // Draw at most one logical screen. If the line list is oversized (stale
        // frame after rapid resize), keep the *tail* — the head is residual ghost
        // rows the TUI no longer owns.
        val lineOffset = if (lines.size > logicalRows) lines.size - logicalRows else 0
        val drawRows = minOf(lines.size - lineOffset, logicalRows).coerceAtLeast(0)

        // Fit-scale only while layout ≠ PTY (IME motion). Use committed grid size so
        // the freeze frame does not bounce when line count briefly differs.
        // Settled: 1:1 cells + pinch only — no continuous stretch (that felt like bounce).
        val scaleX: Float
        val scaleY: Float
        if (fitToViewport && cellW > 0f && cellH > 0f) {
            val srcW = logicalCols.coerceAtLeast(1) * cellW
            val srcH = logicalRows.coerceAtLeast(1) * cellH
            val fitX = if (srcW > 0f) size.width / srcW else 1f
            val fitY = if (srcH > 0f) size.height / srcH else 1f
            scaleX = (fitX * extraScaleX).coerceIn(0.15f, 4f)
            scaleY = (fitY * extraScaleY).coerceIn(0.15f, 4f)
        } else {
            scaleX = extraScaleX.coerceIn(0.15f, 4f)
            scaleY = extraScaleY.coerceIn(0.15f, 4f)
        }
        val needsScale = scaleX != 1f || scaleY != 1f

        // Compose-layer wipe of the whole TUI surface (not native-only).
        if (canvasBackground.alpha > 0f) {
            drawRect(color = canvasBackground, size = Size(size.width, size.height))
        }

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val layerSave = native.save()
            if (originX != 0f) native.translate(originX, 0f)
            if (needsScale) native.scale(scaleX, scaleY)
            for (row in 0 until drawRows) {
                val text = lines[row + lineOffset]
                val content = text.text
                val rowTop = row * cellH
                val rowBottom = rowTop + cellH
                var i = 0
                var col = 0
                while (i < content.length) {
                    val cp = Character.codePointAt(content, i)
                    val charCount = Character.charCount(cp)
                    val cells = glyphCellWidth(cp)
                    if (cells > 0) {
                        val fg = foregroundAt(text, i, defaultColor)
                        val bg = backgroundAt(text, i)
                        val left = col * cellW
                        val right = (col + cells) * cellW
                        val cellBg = if (bg != null && bg != Color.Unspecified && bg.alpha > 0f) {
                            bg
                        } else {
                            canvasBackground
                        }
                        // Solid cell fill every frame — spaces erase prior ink completely.
                        if (cellBg.alpha > 0f) {
                            bgPaint.color = cellBg.toArgb()
                            native.drawRect(left, rowTop, right, rowBottom, bgPaint)
                        }
                        if (cp != ' '.code) {
                            val ch = content.substring(i, i + charCount)
                            val paint = paintForGlyph(textPaint, nerdPaint, ch, cp)
                            paint.color = fg.toArgb()
                            val save = native.save()
                            native.clipRect(left, rowTop, right, rowBottom)
                            bgPaint.color = fg.toArgb()
                            drawCellGlyph(
                                native,
                                paint,
                                bgPaint,
                                ch,
                                left,
                                rowTop,
                                rowTop + baseline,
                                cellW,
                                cellH,
                                cells,
                                cp
                            )
                            if (isUnderlined(text, i)) {
                                val underlineY = rowTop + (baseline + 2f).coerceAtMost(cellH - 1f)
                                bgPaint.color = fg.toArgb()
                                native.drawRect(left, underlineY, right, underlineY + 1.5f, bgPaint)
                            }
                            native.restoreToCount(save)
                        }
                        col += cells
                    }
                    i += charCount
                }
            }

            // cursorY is an index into [lines]; map through lineOffset when we dropped
            // residual head rows so the caret stays on the live TUI cell.
            val drawCursorY = cursorY - lineOffset
            if (cursorVisible && drawCursorY in 0 until drawRows && cursorX >= 0) {
                // Clamp so wrap-pending (cursorX == cols) still draws on the last cell
                // instead of vanishing off the right edge — that made backspace look like
                // the cursor skipped over the last character.
                val maxCol = (logicalCols - 1).coerceAtLeast(0)
                val cx = cursorX.coerceIn(0, maxCol)
                cursorPaint.color = cursorColor.copy(alpha = 0.8f).toArgb()
                native.drawRect(
                    cx * cellW,
                    drawCursorY * cellH,
                    (cx + 1) * cellW,
                    (drawCursorY + 1) * cellH,
                    cursorPaint
                )
            }
            native.restoreToCount(layerSave)
        }
    }
}

/** Draw [ch] in its cell; scale X when the glyph is wider than the slot. */
private fun drawCellGlyph(
    native: android.graphics.Canvas,
    textPaint: Paint,
    fillPaint: Paint,
    ch: String,
    left: Float,
    rowTop: Float,
    baselineY: Float,
    cellW: Float,
    cellH: Float,
    cells: Int,
    codePoint: Int = 0
) {
    val slotW = cellW * cells.coerceAtLeast(1)
    if (TerminalBlockGlyphs.draw(native, fillPaint, codePoint, left, rowTop, slotW, cellH)) {
        return
    }
    val glyphW = textPaint.measureText(ch)
    val t = glyphDrawTransform(glyphW, cellW, cells, glyphCellAlign(codePoint))
    if (t.scaleX != 1f) {
        val save = native.save()
        native.translate(left, 0f)
        native.scale(t.scaleX, 1f)
        native.drawText(ch, 0f, baselineY, textPaint)
        native.restoreToCount(save)
    } else {
        native.drawText(ch, left + t.offsetX, baselineY, textPaint)
    }
}

internal fun paintForGlyph(primary: Paint, fallback: Paint, ch: String, codePoint: Int): Paint {
    if (!TerminalNerdFont.isNerdIconRange(codePoint)) return primary
    return try {
        if (primary.hasGlyph(ch)) primary else fallback
    } catch (_: Throwable) {
        fallback
    }
}

/** Cell columns consumed by a rendered code point (matches emulator grid). */
internal fun glyphCellWidth(codePoint: Int): Int {
    val w = TerminalEmulator.charWidth(codePoint)
    if (w > 0) return w
    // Spaces / EMPTY already expanded to ' ' by the emulator (width 1).
    // Zero-width / controls contribute nothing.
    return if (codePoint == ' '.code) 1 else 0
}

private fun foregroundAt(text: AnnotatedString, index: Int, default: Color): Color {
    // Last matching span wins (same as how Spanned applies).
    var color = default
    for (range in text.spanStyles) {
        if (index >= range.start && index < range.end) {
            val c = range.item.color
            if (c != Color.Unspecified) color = c
        }
    }
    return color
}

private fun backgroundAt(text: AnnotatedString, index: Int): Color? {
    var bg: Color? = null
    for (range in text.spanStyles) {
        if (index >= range.start && index < range.end) {
            val b = range.item.background
            if (b != Color.Unspecified) bg = b
        }
    }
    return bg
}

private fun isUnderlined(text: AnnotatedString, index: Int): Boolean {
    for (range in text.spanStyles) {
        if (index >= range.start && index < range.end) {
            val deco = range.item.textDecoration
            if (deco != null && deco.contains(TextDecoration.Underline)) return true
        }
    }
    return false
}
