package com.sg.linuxgo.ui.components

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import com.sg.linuxgo.ui.theme.GlyphCellAlign
import com.sg.linuxgo.ui.theme.TerminalFontOption
import com.sg.linuxgo.ui.theme.TerminalNerdFont
import kotlin.math.ceil
import kotlin.math.max

/**
 * Cell metrics for a monospaced terminal grid.
 *
 * Width is the Latin single-cell advance (what letters actually use), snapped
 * to whole pixels so columns stay aligned. Box-drawing / nerd-font glyphs that
 * are wider than that advance are scaled into the cell at draw time — they
 * must not inflate letter spacing for every font.
 */
data class TerminalCellMetrics(
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val fontSizePx: Float,
    val baselinePx: Float,
    val typeface: Typeface,
    /** 0 = regular … 1 = bold (stroke / bold face). */
    val boldness: Float = 0f,
    /** Horizontal scale applied to glyphs and cell width (1 = natural). */
    val widthScale: Float = 1f,
    /** Nerd Font symbols used when the primary face lacks an icon glyph. */
    val nerdFallback: Typeface? = null
)

/** How a glyph is placed inside its cell(s). [scaleX] < 1 fits a too-wide glyph. */
data class GlyphDrawTransform(
    val offsetX: Float,
    val scaleX: Float
)

/**
 * Measure a stable monospaced cell size for the given font + size.
 *
 * Grid width follows Latin letters, not the max of TUI / icon glyphs. Guest
 * fonts (xfce4-terminal Match GUI) often have double-wide box-drawing or
 * nerd icons; using those as the cell width made every letter look spaced out.
 */
fun measureTerminalCellMetrics(
    context: Context,
    fontId: String,
    fontSizePx: Float,
    fontFilePath: String? = null,
    fontBoldness: Float = 0f,
    fontWidthScale: Float = 1f
): TerminalCellMetrics {
    val boldness = clampFontBoldness(fontBoldness)
    val widthScale = clampFontWidthScale(fontWidthScale)
    val baseTypeface = resolveTerminalTypeface(context, fontId, fontFilePath)
    val typeface = applyFontBoldness(baseTypeface, boldness)
    // Metrics paint has no fake-bold stroke — stroke inflates measureText and
    // would stretch the whole grid when boldness is up. Drawing still strokes.
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = fontSizePx
        textScaleX = widthScale
        isSubpixelText = false
        isLinearText = true
        hinting = Paint.HINTING_OFF
        isFakeBoldText = false
        style = Paint.Style.FILL
        strokeWidth = 0f
    }

    val cellWidthPx = snapTerminalCellWidthPx(latinMonospaceAdvancePx { paint.measureText(it) })

    val fm = paint.fontMetrics
    // Ignore extra leading, then snap to whole pixels so LazyColumn rows and
    // the TUI canvas share integer boundaries (fractional heights leave 1px seams).
    // Block/box glyphs are drawn geometrically to fill that snapped cell.
    val rawHeightPx = fm.descent - fm.ascent
    var cellHeightPx = if (rawHeightPx < 1f) {
        max(1f, fontSizePx * 1.2f)
    } else {
        ceil(rawHeightPx.toDouble()).toFloat()
    }
    val extra = (cellHeightPx - rawHeightPx).coerceAtLeast(0f)
    var baselinePx = -fm.ascent + extra * 0.5f
    if (baselinePx <= 0f) {
        baselinePx = fontSizePx * 0.8f
    }

    return TerminalCellMetrics(
        cellWidthPx = cellWidthPx,
        cellHeightPx = cellHeightPx,
        fontSizePx = fontSizePx,
        baselinePx = baselinePx,
        typeface = typeface,
        boldness = boldness,
        widthScale = widthScale,
        nerdFallback = resolveNerdFallbackTypeface(context)
    )
}

/**
 * Typical single-cell advance from Latin letters only.
 * Wide box-drawing / blocks / nerd icons are ignored here on purpose.
 */
fun latinMonospaceAdvancePx(measure: (String) -> Float): Float {
    val probes = charArrayOf('M', 'W', '0', '@', 'x', ' ')
    var advance = 0f
    for (ch in probes) {
        advance = max(advance, measure(ch.toString()))
    }
    return max(advance, measure("MMMMMMMMMM") / 10f)
}

fun snapTerminalCellWidthPx(advancePx: Float): Float =
    max(1f, ceil(advancePx.toDouble()).toFloat())

/**
 * Place [glyphWidthPx] in [cells] columns of [cellWidthPx].
 * Too-wide glyphs (box drawing, nerd icons) scale to the slot instead of
 * forcing a wider grid.
 */
fun glyphDrawTransform(
    glyphWidthPx: Float,
    cellWidthPx: Float,
    cells: Int,
    align: GlyphCellAlign = GlyphCellAlign.CENTER
): GlyphDrawTransform {
    val slot = cellWidthPx * cells.coerceAtLeast(1)
    if (slot <= 0f || glyphWidthPx <= 0f) return GlyphDrawTransform(0f, 1f)
    if (glyphWidthPx > slot) {
        return GlyphDrawTransform(offsetX = 0f, scaleX = slot / glyphWidthPx)
    }
    val offset = when (align) {
        GlyphCellAlign.START -> 0f
        GlyphCellAlign.END -> slot - glyphWidthPx
        GlyphCellAlign.CENTER -> (slot - glyphWidthPx) / 2f
    }
    return GlyphDrawTransform(offsetX = offset, scaleX = 1f)
}

/** Apply boldness: bold typeface at high values. */
fun applyFontBoldness(base: Typeface, boldness: Float): Typeface {
    val b = clampFontBoldness(boldness)
    if (b < 0.55f) return base
    return try {
        Typeface.create(base, Typeface.BOLD)
    } catch (_: Exception) {
        base
    }
}

fun applyFakeBoldStroke(paint: Paint, boldness: Float, fontSizePx: Float) {
    val b = clampFontBoldness(boldness)
    if (b <= 0.05f) {
        paint.isFakeBoldText = false
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        return
    }
    paint.isFakeBoldText = b >= 0.35f
    if (b >= 0.2f) {
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = (b * fontSizePx * 0.045f).coerceIn(0.2f, 2.5f)
    } else {
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
    }
}

fun clampFontBoldness(v: Float): Float = v.coerceIn(0f, 1f)

const val TERMINAL_FONT_WIDTH_MIN = 0.30f
const val TERMINAL_FONT_WIDTH_MAX = 1.4f

fun clampFontWidthScale(v: Float): Float = v.coerceIn(TERMINAL_FONT_WIDTH_MIN, TERMINAL_FONT_WIDTH_MAX)

/** Cached asset typefaces — createFromAsset is expensive and was hit on every pinch frame. */
private val typefaceCache = mutableMapOf<String, Typeface>()

fun resolveTerminalTypeface(
    context: Context,
    fontId: String,
    fontFilePath: String? = null
): Typeface {
    if (!fontFilePath.isNullOrBlank()) {
        val key = "file:$fontFilePath"
        typefaceCache[key]?.let { return it }
        val fromFile = try {
            val f = java.io.File(fontFilePath)
            if (f.isFile) Typeface.createFromFile(f) else null
        } catch (_: Exception) {
            null
        }
        if (fromFile != null) {
            typefaceCache[key] = fromFile
            return fromFile
        }
    }
    typefaceCache[fontId]?.let { return it }
    val option = TerminalFontOption.fromId(fontId)
    val path = option.assetPath
    val typeface = try {
        when {
            path != null && assetPathExists(context, path) ->
                Typeface.createFromAsset(context.assets, path)
            option.isNerdFont ->
                Typeface.createFromAsset(context.assets, TerminalNerdFont.MESLO_ASSET)
            path != null ->
                Typeface.createFromAsset(context.assets, path)
            else -> Typeface.MONOSPACE
        }
    } catch (_: Exception) {
        try {
            Typeface.createFromAsset(context.assets, TerminalNerdFont.MESLO_ASSET)
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }
    typefaceCache[fontId] = typeface
    return typeface
}

fun resolveNerdFallbackTypeface(context: Context): Typeface {
    typefaceCache["nerd-fallback"]?.let { return it }
    val path = TerminalNerdFont.fallbackAssetPath { assetPathExists(context, it) }
    val typeface = try {
        Typeface.createFromAsset(context.assets, path)
    } catch (_: Exception) {
        Typeface.MONOSPACE
    }
    typefaceCache["nerd-fallback"] = typeface
    return typeface
}

internal fun assetPathExists(context: Context, path: String): Boolean {
    return try {
        context.assets.open(path).use { true }
    } catch (_: Exception) {
        false
    }
}
