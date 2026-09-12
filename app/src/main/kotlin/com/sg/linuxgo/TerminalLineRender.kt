package com.sg.linuxgo

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Cell → styled line without going through a full-screen Spannable first.
 */
object TerminalLineRender {

    fun toAnnotated(
        line: TerminalEmulator.ScreenLine,
        defaultFg: Int,
        reverseVideo: Boolean = false,
        canvasBg: Int = 0
    ): AnnotatedString {
        val text = StringBuilder(line.size)
        val cellToStr = IntArray(line.size + 1)
        for (i in 0 until line.size) {
            cellToStr[i] = text.length
            val cp = line.chars[i]
            when {
                cp > 0 -> {
                    if (Character.isBmpCodePoint(cp)) text.append(cp.toChar())
                    else {
                        text.append(Character.highSurrogate(cp))
                        text.append(Character.lowSurrogate(cp))
                    }
                }
                cp == TerminalEmulator.EMPTY -> text.append(' ')
            }
        }
        cellToStr[line.size] = text.length
        val raw = text.toString()
        return buildAnnotatedString {
            append(raw)
            var x = 0
            while (x < line.size) {
                val fg0 = line.fgColors[x]
                val bg0 = line.bgColors[x]
                val fx = line.effectAt(x)
                var run = 1
                while (
                    x + run < line.size &&
                    line.fgColors[x + run] == fg0 &&
                    line.bgColors[x + run] == bg0 &&
                    line.effectAt(x + run) == fx
                ) {
                    run++
                }
                val start = cellToStr[x]
                val end = cellToStr[x + run]
                if (start < end && !TerminalCellStyle.has(fx, TerminalCellStyle.INVISIBLE)) {
                    var fg = fg0
                    var bg = bg0
                    val inverse = reverseVideo xor TerminalCellStyle.has(fx, TerminalCellStyle.INVERSE)
                    if (inverse) {
                        val swapped = TerminalCellStyle.applyInverse(fg, bg, canvasBg)
                        fg = swapped.first
                        bg = swapped.second
                    }
                    if (TerminalCellStyle.has(fx, TerminalCellStyle.DIM)) {
                        fg = dimArgb(fg)
                    }
                    val decorations = mutableListOf<TextDecoration>()
                    if (TerminalCellStyle.has(fx, TerminalCellStyle.UNDERLINE)) {
                        decorations += TextDecoration.Underline
                    }
                    if (TerminalCellStyle.has(fx, TerminalCellStyle.STRIKETHROUGH)) {
                        decorations += TextDecoration.LineThrough
                    }
                    addStyle(
                        SpanStyle(
                            color = if (fg != defaultFg && fg != 0) Color(fg) else Color.Unspecified,
                            background = if (bg != 0) Color(bg) else Color.Unspecified,
                            fontWeight = if (TerminalCellStyle.has(fx, TerminalCellStyle.BOLD)) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            fontStyle = if (TerminalCellStyle.has(fx, TerminalCellStyle.ITALIC)) {
                                FontStyle.Italic
                            } else {
                                FontStyle.Normal
                            },
                            textDecoration = if (decorations.isEmpty()) {
                                null
                            } else {
                                TextDecoration.combine(decorations)
                            }
                        ),
                        start,
                        end
                    )
                }
                x += run
            }
        }
    }

    fun toSpannable(
        line: TerminalEmulator.ScreenLine,
        defaultFg: Int,
        reverseVideo: Boolean = false,
        canvasBg: Int = 0
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val cellToStr = IntArray(line.size + 1)
        for (i in 0 until line.size) {
            cellToStr[i] = sb.length
            val cp = line.chars[i]
            when {
                cp > 0 -> {
                    if (Character.isBmpCodePoint(cp)) sb.append(cp.toChar())
                    else {
                        sb.append(Character.highSurrogate(cp))
                        sb.append(Character.lowSurrogate(cp))
                    }
                }
                cp == TerminalEmulator.EMPTY -> sb.append(' ')
            }
        }
        cellToStr[line.size] = sb.length
        var x = 0
        while (x < line.size) {
            val fg0 = line.fgColors[x]
            val bg0 = line.bgColors[x]
            val fx = line.effectAt(x)
            var run = 1
            while (
                x + run < line.size &&
                line.fgColors[x + run] == fg0 &&
                line.bgColors[x + run] == bg0 &&
                line.effectAt(x + run) == fx
            ) {
                run++
            }
            val start = cellToStr[x]
            val end = cellToStr[x + run]
            if (start < end && !TerminalCellStyle.has(fx, TerminalCellStyle.INVISIBLE)) {
                var fg = fg0
                var bg = bg0
                val inverse = reverseVideo xor TerminalCellStyle.has(fx, TerminalCellStyle.INVERSE)
                if (inverse) {
                    val swapped = TerminalCellStyle.applyInverse(fg, bg, canvasBg)
                    fg = swapped.first
                    bg = swapped.second
                }
                if (TerminalCellStyle.has(fx, TerminalCellStyle.DIM)) fg = dimArgb(fg)
                if (fg != defaultFg) {
                    sb.setSpan(ForegroundColorSpan(fg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (bg != 0) {
                    sb.setSpan(BackgroundColorSpan(bg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (TerminalCellStyle.has(fx, TerminalCellStyle.BOLD) ||
                    TerminalCellStyle.has(fx, TerminalCellStyle.ITALIC)
                ) {
                    val style = when {
                        TerminalCellStyle.has(fx, TerminalCellStyle.BOLD) &&
                            TerminalCellStyle.has(fx, TerminalCellStyle.ITALIC) -> Typeface.BOLD_ITALIC
                        TerminalCellStyle.has(fx, TerminalCellStyle.BOLD) -> Typeface.BOLD
                        else -> Typeface.ITALIC
                    }
                    sb.setSpan(StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (TerminalCellStyle.has(fx, TerminalCellStyle.UNDERLINE)) {
                    sb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (TerminalCellStyle.has(fx, TerminalCellStyle.STRIKETHROUGH)) {
                    sb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            x += run
        }
        return sb
    }

    fun transcriptText(lines: List<AnnotatedString>): String {
        if (lines.isEmpty()) return ""
        return buildString(lines.size * 40) {
            for (i in lines.indices) {
                if (i > 0) append('\n')
                append(lines[i].text.trimEnd())
            }
        }
    }

    fun selectionText(
        lines: List<AnnotatedString>,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int
    ): String {
        if (lines.isEmpty()) return ""
        var aL = startLine.coerceIn(0, lines.lastIndex)
        var bL = endLine.coerceIn(0, lines.lastIndex)
        var aC = startCol.coerceAtLeast(0)
        var bC = endCol.coerceAtLeast(0)
        if (aL > bL || (aL == bL && aC > bC)) {
            val tl = aL; aL = bL; bL = tl
            val tc = aC; aC = bC; bC = tc
        }
        return buildString {
            for (i in aL..bL) {
                val t = lines[i].text
                val from = if (i == aL) aC.coerceIn(0, t.length) else 0
                val to = if (i == bL) bC.coerceIn(0, t.length) else t.length
                if (from < to) append(t.substring(from, to).trimEnd())
                if (i < bL) append('\n')
            }
        }
    }

    private fun dimArgb(argb: Int): Int {
        val r = ((argb shr 16) and 0xFF) * 2 / 3
        val g = ((argb shr 8) and 0xFF) * 2 / 3
        val b = (argb and 0xFF) * 2 / 3
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
