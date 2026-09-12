package com.sg.linuxgo.ui.theme

/**
 * Nerd Font / Powerline helpers used by the terminal renderer.
 *
 * Full patched families live in [TerminalFontOption]. The symbols-only (or Meslo NF)
 * asset is always stacked as a fallback so non-patched picker fonts still draw
 * starship / powerline / file icons.
 */
object TerminalNerdFont {
    const val SYMBOLS_ASSET = "fonts/SymbolsNerdFontMono-Regular.ttf"
    const val MESLO_ASSET = "fonts/MesloLGS-NF-Regular.ttf"

    /**
     * Preferred fallback: symbols-only if bundled, else Meslo NF (already in assets).
     */
    fun fallbackAssetPath(assetExists: (String) -> Boolean): String =
        if (assetExists(SYMBOLS_ASSET)) SYMBOLS_ASSET else MESLO_ASSET

    /**
     * Powerline extra separators that must sit on the cell edge so rounded/chevron
     * pills weld to the previous colored segment.
     */
    fun isPowerlineSeparator(codePoint: Int): Boolean =
        codePoint in 0xE0B0..0xE0D4

    /** Separators whose filled side is on the left (flush to the previous cell). */
    fun isRightPointingSeparator(codePoint: Int): Boolean = when (codePoint) {
        0xE0B0, 0xE0B4, 0xE0B8, 0xE0BC, 0xE0C0, 0xE0C4,
        0xE0C6, 0xE0C8, 0xE0CC, 0xE0D0, 0xE0D2 -> true
        else -> false
    }

    /** Separators whose filled side is on the right (flush to the next cell). */
    fun isLeftPointingSeparator(codePoint: Int): Boolean = when (codePoint) {
        0xE0B2, 0xE0B6, 0xE0BA, 0xE0BE, 0xE0C2, 0xE0CA,
        0xE0CE, 0xE0D4 -> true
        else -> false
    }

    /**
     * Private-use / nerd ranges commonly used by starship, p10k, and ls icons.
     * Latin / CJK are excluded so fallback is only used for missing icon glyphs.
     */
    fun isNerdIconRange(codePoint: Int): Boolean {
        if (isPowerlineSeparator(codePoint)) return true
        return codePoint in 0xE000..0xF8FF ||
            codePoint in 0xF0000..0xF1FFF
    }
}

enum class GlyphCellAlign {
    START,
    CENTER,
    END
}

fun glyphCellAlign(codePoint: Int): GlyphCellAlign = when {
    TerminalNerdFont.isRightPointingSeparator(codePoint) -> GlyphCellAlign.START
    TerminalNerdFont.isLeftPointingSeparator(codePoint) -> GlyphCellAlign.END
    else -> GlyphCellAlign.CENTER
}
