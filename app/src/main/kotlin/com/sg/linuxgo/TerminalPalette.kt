package com.sg.linuxgo

/**
 * xterm-style 16-color palette helpers, 256-color cube, and SGR color application.
 * Pure helpers — palette values come from [TerminalColorScheme] (Match GUI or built-in).
 */
object TerminalPalette {
    /** Built-in One Dark–style scheme (historical default). */
    val ONE_DARK: TerminalColorScheme = TerminalColorScheme.oneDark()

    // Compat re-exports used by TerminalEmulator companion / tests.
    val PALETTE_BLACK get() = ONE_DARK.color(0)
    val PALETTE_RED get() = ONE_DARK.color(1)
    val PALETTE_GREEN get() = ONE_DARK.color(2)
    val PALETTE_YELLOW get() = ONE_DARK.color(3)
    val PALETTE_BLUE get() = ONE_DARK.color(4)
    val PALETTE_MAGENTA get() = ONE_DARK.color(5)
    val PALETTE_CYAN get() = ONE_DARK.color(6)
    val PALETTE_WHITE get() = ONE_DARK.color(7)

    val PALETTE_BRIGHT_BLACK get() = ONE_DARK.color(8)
    val PALETTE_BRIGHT_RED get() = ONE_DARK.color(9)
    val PALETTE_BRIGHT_GREEN get() = ONE_DARK.color(10)
    val PALETTE_BRIGHT_YELLOW get() = ONE_DARK.color(11)
    val PALETTE_BRIGHT_BLUE get() = ONE_DARK.color(12)
    val PALETTE_BRIGHT_MAGENTA get() = ONE_DARK.color(13)
    val PALETTE_BRIGHT_CYAN get() = ONE_DARK.color(14)
    val PALETTE_BRIGHT_WHITE get() = ONE_DARK.color(15)

    val DEFAULT_FG get() = ONE_DARK.defaultFg

    fun get256Color(index: Int, scheme: TerminalColorScheme = ONE_DARK): Int {
        if (index < 0 || index > 255) return scheme.defaultFg
        if (index < 16) return scheme.color(index)
        if (index in 16..231) {
            var val256 = index - 16
            val r = (val256 / 36) * 51
            val256 %= 36
            val g = (val256 / 6) * 51
            val b = (val256 % 6) * 51
            return TerminalColorMath.packRgb(r, g, b)
        }
        val gray = (index - 232) * 10 + 8
        return TerminalColorMath.packRgb(gray, gray, gray)
    }

    /**
     * Apply CSI SGR (`m`) parameters to the current fg/bg pair.
     * Empty params reset to defaults (SGR 0).
     * Default background is [android.graphics.Color.TRANSPARENT] so the UI canvas shows through.
     */
    fun applySgr(
        params: List<Int>,
        currentFg: Int,
        currentBg: Int,
        scheme: TerminalColorScheme = ONE_DARK
    ): Pair<Int, Int> {
        val transparent = 0 // Color.TRANSPARENT
        if (params.isEmpty()) {
            return scheme.defaultFg to transparent
        }

        var fg = currentFg
        var bg = currentBg
        var idx = 0
        while (idx < params.size) {
            when (val p = params[idx]) {
                0 -> {
                    fg = scheme.defaultFg
                    bg = transparent
                }
                30 -> fg = scheme.color(0)
                31 -> fg = scheme.color(1)
                32 -> fg = scheme.color(2)
                33 -> fg = scheme.color(3)
                34 -> fg = scheme.color(4)
                35 -> fg = scheme.color(5)
                36 -> fg = scheme.color(6)
                37 -> fg = scheme.color(7)
                38 -> { // Foreground Extended (256 color or RGB)
                    if (idx + 2 < params.size && params[idx + 1] == 5) {
                        fg = get256Color(params[idx + 2], scheme)
                        idx += 2
                    } else if (idx + 4 < params.size && params[idx + 1] == 2) {
                        fg = TerminalColorMath.packRgb(
                            params[idx + 2].coerceIn(0, 255),
                            params[idx + 3].coerceIn(0, 255),
                            params[idx + 4].coerceIn(0, 255)
                        )
                        idx += 4
                    }
                }
                39 -> fg = scheme.defaultFg
                40 -> bg = scheme.color(0)
                41 -> bg = scheme.color(1)
                42 -> bg = scheme.color(2)
                43 -> bg = scheme.color(3)
                44 -> bg = scheme.color(4)
                45 -> bg = scheme.color(5)
                46 -> bg = scheme.color(6)
                47 -> bg = scheme.color(7)
                48 -> { // Background Extended (256 color or RGB)
                    if (idx + 2 < params.size && params[idx + 1] == 5) {
                        bg = get256Color(params[idx + 2], scheme)
                        idx += 2
                    } else if (idx + 4 < params.size && params[idx + 1] == 2) {
                        bg = TerminalColorMath.packRgb(
                            params[idx + 2].coerceIn(0, 255),
                            params[idx + 3].coerceIn(0, 255),
                            params[idx + 4].coerceIn(0, 255)
                        )
                        idx += 4
                    }
                }
                49 -> bg = transparent
                90 -> fg = scheme.color(8)
                91 -> fg = scheme.color(9)
                92 -> fg = scheme.color(10)
                93 -> fg = scheme.color(11)
                94 -> fg = scheme.color(12)
                95 -> fg = scheme.color(13)
                96 -> fg = scheme.color(14)
                97 -> fg = scheme.color(15)
                100 -> bg = scheme.color(8)
                101 -> bg = scheme.color(9)
                102 -> bg = scheme.color(10)
                103 -> bg = scheme.color(11)
                104 -> bg = scheme.color(12)
                105 -> bg = scheme.color(13)
                106 -> bg = scheme.color(14)
                107 -> bg = scheme.color(15)
                else -> { /* ignore unknown SGR */ }
            }
            idx++
        }
        return fg to bg
    }
}
