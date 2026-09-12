package com.sg.linuxgo

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan

object AnsiParser {

    /**
     * Advanced ANSI parser with 256-color support.
     */
    fun ansiToSpannable(text: String): CharSequence {
        if (text.isEmpty()) return text
        val sb = SpannableStringBuilder()
        var lastMatchEnd = 0
        var fgColor = Color.WHITE
        var bgColor = Color.TRANSPARENT
        
        // Extended pattern to match ANSI sequences
        val ansiPattern = Regex("\u001B\\[([\\d;]*)m")
        val matches = ansiPattern.findAll(text)
        
        for (match in matches) {
            // Text before the escape sequence
            val before = text.substring(lastMatchEnd, match.range.first)
            if (before.isNotEmpty()) {
                val start = sb.length
                sb.append(before)
                sb.setSpan(ForegroundColorSpan(fgColor), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (bgColor != Color.TRANSPARENT) {
                    sb.setSpan(BackgroundColorSpan(bgColor), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            
            // Parse ANSI parameters
            val params = match.groupValues[1].split(";")
            var i = 0
            while (i < params.size) {
                val p = params[i]
                when (p) {
                    "", "0" -> { fgColor = Color.WHITE; bgColor = Color.TRANSPARENT }
                    "30" -> fgColor = Color.BLACK
                    "31" -> fgColor = Color.RED
                    "32" -> fgColor = Color.GREEN
                    "33" -> fgColor = Color.YELLOW
                    "34" -> fgColor = Color.BLUE
                    "35" -> fgColor = Color.MAGENTA
                    "36" -> fgColor = Color.CYAN
                    "37" -> fgColor = Color.WHITE
                    "38" -> { // Foreground Extended
                        if (i + 2 < params.size && params[i+1] == "5") {
                            fgColor = get256Color(params[i+2].toIntOrNull() ?: 7)
                            i += 2
                        } else if (i + 4 < params.size && params[i+1] == "2") {
                            // RGB support: 38;2;R;G;B
                            fgColor = Color.rgb(
                                params[i+2].toIntOrNull() ?: 255,
                                params[i+3].toIntOrNull() ?: 255,
                                params[i+4].toIntOrNull() ?: 255
                            )
                            i += 4
                        }
                    }
                    "40" -> bgColor = Color.BLACK
                    "41" -> bgColor = Color.RED
                    "42" -> bgColor = Color.GREEN
                    "43" -> bgColor = Color.YELLOW
                    "44" -> bgColor = Color.BLUE
                    "45" -> bgColor = Color.MAGENTA
                    "46" -> bgColor = Color.CYAN
                    "47" -> bgColor = Color.WHITE
                    "48" -> { // Background Extended
                        if (i + 2 < params.size && params[i+1] == "5") {
                            bgColor = get256Color(params[i+2].toIntOrNull() ?: 0)
                            i += 2
                        } else if (i + 4 < params.size && params[i+1] == "2") {
                            bgColor = Color.rgb(
                                params[i+2].toIntOrNull() ?: 0,
                                params[i+3].toIntOrNull() ?: 0,
                                params[i+4].toIntOrNull() ?: 0
                            )
                            i += 4
                        }
                    }
                    "90" -> fgColor = Color.GRAY
                    "91" -> fgColor = Color.rgb(255, 100, 100)
                    "92" -> fgColor = Color.rgb(100, 255, 100)
                    "93" -> fgColor = Color.rgb(255, 255, 100)
                    "94" -> fgColor = Color.rgb(100, 100, 255)
                    "95" -> fgColor = Color.rgb(255, 100, 255)
                    "96" -> fgColor = Color.rgb(100, 255, 255)
                    "97" -> fgColor = Color.WHITE
                }
                i++
            }
            lastMatchEnd = match.range.last + 1
        }
        
        // Remaining text
        if (lastMatchEnd < text.length) {
            val remaining = text.substring(lastMatchEnd)
            val start = sb.length
            // Strip any other ANSI codes (cursor moves etc) we don't handle
            val cleanRemaining = remaining.replace(Regex("\u001B\\[[;\\d]*[A-Za-z]"), "")
                .replace("\u001B", "")
            sb.append(cleanRemaining)
            sb.setSpan(ForegroundColorSpan(fgColor), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bgColor != Color.TRANSPARENT) {
                sb.setSpan(BackgroundColorSpan(bgColor), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        
        return sb
    }

    private fun get256Color(index: Int): Int {
        if (index < 0 || index > 255) return Color.WHITE
        
        // 0-15: Standard and high-intensity colors
        if (index < 16) {
            return when (index) {
                0 -> Color.BLACK
                1 -> Color.RED
                2 -> Color.GREEN
                3 -> Color.YELLOW
                4 -> Color.BLUE
                5 -> Color.MAGENTA
                6 -> Color.CYAN
                7 -> Color.WHITE
                8 -> Color.GRAY
                9 -> Color.rgb(255, 85, 85)
                10 -> Color.rgb(85, 255, 85)
                11 -> Color.rgb(255, 255, 85)
                12 -> Color.rgb(85, 85, 255)
                13 -> Color.rgb(255, 85, 255)
                14 -> Color.rgb(85, 255, 255)
                15 -> Color.WHITE
                else -> Color.WHITE
            }
        }
        
        // 16-231: 6x6x6 color cube
        if (index < 232) {
            val i = index - 16
            val r = (i / 36) * 51
            val g = ((i % 36) / 6) * 51
            val b = (i % 6) * 51
            return Color.rgb(r, g, b)
        }
        
        // 232-255: Grayscale ramp
        val gray = (index - 232) * 10 + 8
        return Color.rgb(gray, gray, gray)
    }
}
