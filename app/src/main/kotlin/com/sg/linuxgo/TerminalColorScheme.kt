package com.sg.linuxgo

/**
 * Full terminal color model: 16 ANSI colors + default fg/bg/cursor.
 * Used by the app emulator for SGR and by "Match GUI terminal" mode.
 */
data class TerminalColorScheme(
    /** Indices 0–15 (normal 0–7, bright 8–15). */
    val colors: IntArray,
    val defaultFg: Int,
    /** Canvas / default background (opaque ARGB). */
    val defaultBg: Int,
    val cursorColor: Int,
    /** Font family name from xfce4-terminal (without size), if any. */
    val fontName: String? = null,
    /** Point size from terminalrc FontName, if parsed. */
    val fontSizePt: Float? = null,
    val source: Source = Source.BUILTIN
) {
    enum class Source {
        BUILTIN,
        XFCE_TERMINALRC,
        APP_THEME,
        OSC_DYNAMIC
    }

    init {
        require(colors.size == 16) { "ANSI palette must have 16 colors, got ${colors.size}" }
    }

    fun color(index: Int): Int {
        if (index !in 0..15) return defaultFg
        return colors[index]
    }

    fun withPaletteColor(index: Int, argb: Int): TerminalColorScheme {
        if (index !in 0..15) return this
        val next = colors.copyOf()
        next[index] = argb
        return copy(colors = next, source = Source.OSC_DYNAMIC)
    }

    fun withDefaultFg(argb: Int): TerminalColorScheme =
        copy(defaultFg = argb, source = Source.OSC_DYNAMIC)

    fun withDefaultBg(argb: Int): TerminalColorScheme =
        copy(defaultBg = argb, source = Source.OSC_DYNAMIC)

    fun withCursor(argb: Int): TerminalColorScheme =
        copy(cursorColor = argb, source = Source.OSC_DYNAMIC)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalColorScheme) return false
        return colors.contentEquals(other.colors) &&
            defaultFg == other.defaultFg &&
            defaultBg == other.defaultBg &&
            cursorColor == other.cursorColor &&
            fontName == other.fontName &&
            fontSizePt == other.fontSizePt &&
            source == other.source
    }

    override fun hashCode(): Int {
        var result = colors.contentHashCode()
        result = 31 * result + defaultFg
        result = 31 * result + defaultBg
        result = 31 * result + cursorColor
        result = 31 * result + (fontName?.hashCode() ?: 0)
        result = 31 * result + (fontSizePt?.hashCode() ?: 0)
        result = 31 * result + source.hashCode()
        return result
    }

    companion object {
        /** Classic One Dark–style defaults (historical PocketLinux palette). */
        fun oneDark(): TerminalColorScheme {
            val c = intArrayOf(
                TerminalColorMath.parseHexRgb("#1E222A")!!,
                TerminalColorMath.parseHexRgb("#E06C75")!!,
                TerminalColorMath.parseHexRgb("#98C379")!!,
                TerminalColorMath.parseHexRgb("#E5C07B")!!,
                TerminalColorMath.parseHexRgb("#61AFEF")!!,
                TerminalColorMath.parseHexRgb("#C678DD")!!,
                TerminalColorMath.parseHexRgb("#56B6C2")!!,
                TerminalColorMath.parseHexRgb("#ABB2BF")!!,
                TerminalColorMath.parseHexRgb("#5C6370")!!,
                TerminalColorMath.parseHexRgb("#E06C75")!!,
                TerminalColorMath.parseHexRgb("#98C379")!!,
                TerminalColorMath.parseHexRgb("#E5C07B")!!,
                TerminalColorMath.parseHexRgb("#61AFEF")!!,
                TerminalColorMath.parseHexRgb("#C678DD")!!,
                TerminalColorMath.parseHexRgb("#56B6C2")!!,
                TerminalColorMath.parseHexRgb("#FFFFFF")!!
            )
            return TerminalColorScheme(
                colors = c,
                defaultFg = c[7],
                defaultBg = TerminalColorMath.parseHexRgb("#0A0C0F")!!,
                cursorColor = TerminalColorMath.parseHexRgb("#6EB0BA")!!,
                source = Source.BUILTIN
            )
        }

        /**
         * App theme chrome plus the matching 16-color ANSI palette so TUI apps
         * (htop, vim, lazygit) follow the selected terminal theme.
         */
        fun fromAppChrome(
            bgArgb: Int,
            fgArgb: Int,
            cursorArgb: Int,
            themeId: String = "default"
        ): TerminalColorScheme {
            val colors = TerminalThemePalettes.ansi16(themeId)
            if (TerminalThemePalettes.isLightTheme(themeId)) {
                // COLORFGBG 0;15 — TUI "white" fills match the light canvas.
                colors[15] = bgArgb
            } else {
                // Dark: ANSI black fills (htop / vim default bg) match the canvas.
                colors[0] = bgArgb
            }
            colors[7] = fgArgb
            return TerminalColorScheme(
                colors = colors,
                defaultFg = fgArgb,
                defaultBg = bgArgb,
                cursorColor = cursorArgb,
                source = Source.APP_THEME
            )
        }
    }
}

/**
 * Pure color parsing (no android.graphics dependency) so unit tests work under
 * `returnDefaultValues` without stubbed [android.graphics.Color].
 */
object TerminalColorMath {
    fun packRgb(r: Int, g: Int, b: Int): Int {
        val rr = r.coerceIn(0, 255)
        val gg = g.coerceIn(0, 255)
        val bb = b.coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    fun red(argb: Int): Int = (argb shr 16) and 0xFF
    fun green(argb: Int): Int = (argb shr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    /**
     * Parse `#RGB`, `#RRGGBB`, `#RRRRGGGGBBBB`, or bare hex without `#`.
     */
    fun parseHexRgb(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        if (s.startsWith("#")) s = s.substring(1)
        // Drop alpha if present (#AARRGGBB)
        if (s.length == 8 && s.all { it.isHex() }) {
            s = s.substring(2)
        }
        return when (s.length) {
            3 -> {
                if (!s.all { it.isHex() }) return null
                val r = s[0].digitToInt(16) * 17
                val g = s[1].digitToInt(16) * 17
                val b = s[2].digitToInt(16) * 17
                packRgb(r, g, b)
            }
            6 -> {
                if (!s.all { it.isHex() }) return null
                val r = s.substring(0, 2).toInt(16)
                val g = s.substring(2, 4).toInt(16)
                val b = s.substring(4, 6).toInt(16)
                packRgb(r, g, b)
            }
            12 -> {
                // X11 16-bit per channel (#rrrrggggbbbb) — use high byte
                if (!s.all { it.isHex() }) return null
                val r = s.substring(0, 4).toInt(16) shr 8
                val g = s.substring(4, 8).toInt(16) shr 8
                val b = s.substring(8, 12).toInt(16) shr 8
                packRgb(r, g, b)
            }
            else -> null
        }
    }

    /**
     * Parse OSC / xterm color specs: `#rrggbb`, `rgb:rr/gg/bb`, `rgbi:…` (ignored),
     * or a decimal index into a palette (not handled here).
     */
    fun parseColorSpec(spec: String?): Int? {
        if (spec.isNullOrBlank()) return null
        val s = spec.trim()
        if (s.equals("?", ignoreCase = true)) return null // query, not a set
        if (s.startsWith("#") || s.all { it.isHex() } && s.length in setOf(3, 6, 8, 12)) {
            return parseHexRgb(s)
        }
        if (s.startsWith("rgb:", ignoreCase = true)) {
            val body = s.substring(4)
            val parts = body.split('/')
            if (parts.size != 3) return null
            fun chan(p: String): Int? {
                val t = p.trim()
                if (t.isEmpty() || !t.all { it.isHex() }) return null
                val v = t.toInt(16)
                // xterm uses 1–4 hex digits; scale high bits to 0–255
                return when (t.length) {
                    1 -> v * 17
                    2 -> v
                    3 -> v shr 4
                    else -> (v shr 8).coerceIn(0, 255)
                }
            }
            val r = chan(parts[0]) ?: return null
            val g = chan(parts[1]) ?: return null
            val b = chan(parts[2]) ?: return null
            return packRgb(r, g, b)
        }
        return null
    }

    private fun Char.isHex(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
