package com.sg.linuxgo

/**
 * Pure parser for xfce4-terminal `terminalrc` (INI-like key=value under [Configuration]).
 * No I/O — pass file text in, get [Parsed] out.
 */
object XfceTerminalRcParser {

    data class Parsed(
        val colorForeground: Int? = null,
        val colorBackground: Int? = null,
        val colorCursor: Int? = null,
        /** Exactly 16 colors when fully present; otherwise null. */
        val colorPalette: IntArray? = null,
        /** Font family without size, e.g. "MesloLGS NF". */
        val fontFamily: String? = null,
        /** Point size from FontName when present. */
        val fontSizePt: Float? = null,
        val cursorBlinks: Boolean = false,
        val cursorShape: TerminalCursorShape = TerminalCursorShape.BLOCK,
        val scrollingLines: Int? = null,
        val rawKeys: Map<String, String> = emptyMap()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parsed) return false
            return colorForeground == other.colorForeground &&
                colorBackground == other.colorBackground &&
                colorCursor == other.colorCursor &&
                ((colorPalette == null && other.colorPalette == null) ||
                    (colorPalette != null && other.colorPalette != null &&
                        colorPalette.contentEquals(other.colorPalette))) &&
                fontFamily == other.fontFamily &&
                fontSizePt == other.fontSizePt
        }

        override fun hashCode(): Int {
            var r = (colorForeground ?: 0)
            r = 31 * r + (colorBackground ?: 0)
            r = 31 * r + (colorCursor ?: 0)
            r = 31 * r + (colorPalette?.contentHashCode() ?: 0)
            r = 31 * r + (fontFamily?.hashCode() ?: 0)
            r = 31 * r + (fontSizePt?.hashCode() ?: 0)
            return r
        }
    }

    fun parse(text: String): Parsed {
        val keys = linkedMapOf<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key.isNotEmpty()) keys[key] = value
        }

        val fg = TerminalColorMath.parseColorSpec(keys["ColorForeground"])
            ?: TerminalColorMath.parseHexRgb(keys["ColorForeground"])
        val bg = TerminalColorMath.parseColorSpec(keys["ColorBackground"])
            ?: TerminalColorMath.parseHexRgb(keys["ColorBackground"])
        val cursor = TerminalColorMath.parseColorSpec(keys["ColorCursor"])
            ?: TerminalColorMath.parseHexRgb(keys["ColorCursor"])
            ?: TerminalColorMath.parseColorSpec(keys["ColorCursorForeground"])

        val palette = parsePalette(keys["ColorPalette"])
        val (family, size) = parseFontName(keys["FontName"])
        val blinks = parseBool(keys["MiscCursorBlinks"])
        val shape = parseCursorShape(keys["MiscCursorShape"])
        val scroll = keys["ScrollingLines"]?.toIntOrNull()

        return Parsed(
            colorForeground = fg,
            colorBackground = bg,
            colorCursor = cursor,
            colorPalette = palette,
            fontFamily = family,
            fontSizePt = size,
            cursorBlinks = blinks,
            cursorShape = shape,
            scrollingLines = scroll,
            rawKeys = keys.toMap()
        )
    }

    fun parseBool(raw: String?): Boolean {
        val v = raw?.trim()?.lowercase().orEmpty()
        return v == "true" || v == "1" || v == "yes"
    }

    fun parseCursorShape(raw: String?): TerminalCursorShape {
        val v = raw?.trim()?.uppercase().orEmpty()
        return when {
            v.contains("UNDERLINE") -> TerminalCursorShape.UNDERLINE
            v.contains("IBEAM") || v.contains("BAR") -> TerminalCursorShape.BAR
            else -> TerminalCursorShape.BLOCK
        }
    }

    /**
     * xfce ColorPalette: 16 colors separated by `;`, each `#rrggbb` or similar.
     */
    fun parsePalette(raw: String?): IntArray? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 16) return null
        val out = IntArray(16)
        for (i in 0 until 16) {
            val c = TerminalColorMath.parseColorSpec(parts[i])
                ?: TerminalColorMath.parseHexRgb(parts[i])
                ?: return null
            out[i] = c
        }
        return out
    }

    /**
     * FontName examples: `MesloLGS NF 12`, `Monospace 10`, `JetBrainsMono Nerd Font Mono 11`.
     * Last whitespace-separated token that is a number is the size.
     */
    fun parseFontName(raw: String?): Pair<String?, Float?> {
        if (raw.isNullOrBlank()) return null to null
        val tokens = raw.trim().split(Regex("\\s+"))
        if (tokens.isEmpty()) return null to null
        val last = tokens.last()
        val size = last.toFloatOrNull()
        return if (size != null && tokens.size >= 2) {
            tokens.dropLast(1).joinToString(" ") to size
        } else {
            raw.trim() to null
        }
    }

    /**
     * Build a [TerminalColorScheme], filling gaps from [base] (typically One Dark).
     */
    fun toColorScheme(parsed: Parsed, base: TerminalColorScheme = TerminalColorScheme.oneDark()): TerminalColorScheme {
        val colors = parsed.colorPalette?.copyOf() ?: base.colors.copyOf()
        val fg = parsed.colorForeground ?: base.defaultFg
        val bg = parsed.colorBackground ?: base.defaultBg
        val cursor = parsed.colorCursor ?: parsed.colorForeground ?: base.cursorColor
        return TerminalColorScheme(
            colors = colors,
            defaultFg = fg,
            defaultBg = bg,
            cursorColor = cursor,
            fontName = parsed.fontFamily,
            fontSizePt = parsed.fontSizePt,
            source = TerminalColorScheme.Source.XFCE_TERMINALRC
        )
    }
}
