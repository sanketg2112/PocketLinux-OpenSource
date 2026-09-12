package com.sg.linuxgo

/**
 * Per-cell SGR effects stored as a bitset on [TerminalEmulator.ScreenLine].
 */
object TerminalCellStyle {
    const val BOLD = 1
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINE = 1 shl 3
    const val BLINK = 1 shl 4
    const val INVERSE = 1 shl 5
    const val INVISIBLE = 1 shl 6
    const val STRIKETHROUGH = 1 shl 7

    const val NONE = 0

    fun has(effects: Int, bit: Int): Boolean = (effects and bit) != 0

    fun set(effects: Int, bit: Int): Int = effects or bit

    fun clear(effects: Int, bit: Int): Int = effects and bit.inv()

    /**
     * Apply a single SGR attribute code to [effects]. Returns -1 if [code] is not an effect
     * (caller should treat it as a color / unknown).
     */
    fun applyAttribute(effects: Int, code: Int): Int {
        return when (code) {
            0 -> NONE
            1 -> set(effects, BOLD)
            2 -> set(effects, DIM)
            3 -> set(effects, ITALIC)
            4 -> set(effects, UNDERLINE)
            5, 6 -> set(effects, BLINK)
            7 -> set(effects, INVERSE)
            8 -> set(effects, INVISIBLE)
            9 -> set(effects, STRIKETHROUGH)
            21, 22 -> clear(clear(effects, BOLD), DIM)
            23 -> clear(effects, ITALIC)
            24 -> clear(effects, UNDERLINE)
            25 -> clear(effects, BLINK)
            27 -> clear(effects, INVERSE)
            28 -> clear(effects, INVISIBLE)
            29 -> clear(effects, STRIKETHROUGH)
            else -> -1
        }
    }

    fun applyInverse(fg: Int, bg: Int, canvasBg: Int): Pair<Int, Int> {
        val back = if (bg == 0) canvasBg else bg
        return back to fg
    }
}

enum class TerminalCursorShape {
    BLOCK,
    UNDERLINE,
    BAR
}
