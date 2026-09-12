package com.sg.linuxgo
/**
 * East-Asian / Unicode display width for a single code point (columns in the cell grid).
 * 0 = non-spacing / control, 1 = narrow, 2 = wide (CJK fullwidth, etc.).
 */
object TerminalCharWidth {
    @JvmStatic
    fun charWidth(codePoint: Int): Int {
        if (codePoint <= 0) return 0
        if (codePoint < 0x20 || (codePoint in 0x7F..0x9F)) return 0
        if (codePoint == 0x7F || codePoint == 0xAD) return 1

        val block = Character.UnicodeBlock.of(codePoint)
        if (block == null) return 1

        return when {
            // Zero-width characters
            codePoint in 0x200B..0x200F ||
                codePoint in 0x2028..0x202E ||
                codePoint in 0x2060..0x2064 ||
                codePoint in 0xFE00..0xFE0F ||
                codePoint in 0xFE20..0xFE2F ||
                codePoint == 0x00AD ||
                codePoint in 0x0300..0x036F ||
                codePoint in 0x1DC0..0x1DFF ||
                codePoint in 0x20D0..0x20FF ||
                codePoint in 0xFE30..0xFE4F ||
                codePoint in 0x1F1E0..0x1F1FF -> 0

            // Combining marks / variation selectors beyond the ranges above
            codePoint in 0x0483..0x0489 ||
                codePoint in 0x0591..0x05BD ||
                codePoint in 0x0610..0x061A ||
                codePoint in 0x064B..0x065F ||
                codePoint in 0x06D6..0x06ED ||
                codePoint in 0x0730..0x074A ||
                codePoint in 0x07A6..0x07B0 ||
                codePoint in 0x0816..0x082D ||
                codePoint in 0x0859..0x085B ||
                codePoint in 0x08D3..0x08FF ||
                codePoint in 0x135D..0x135F ||
                codePoint in 0x180B..0x180D ||
                codePoint in 0x18A9..0x18A9 ||
                codePoint in 0x1939..0x193B ||
                codePoint == 0x200D ||
                codePoint in 0x302A..0x302D ||
                codePoint in 0x3099..0x309A ||
                codePoint in 0xA66F..0xA67D ||
                codePoint in 0xA69E..0xA69F ||
                codePoint in 0xA6F0..0xA6F1 ||
                codePoint in 0xA8E0..0xA8F1 ||
                codePoint in 0xFE00..0xFE0F ||
                codePoint in 0xE0100..0xE01EF -> 0

            // Fullwidth / wide characters (CJK, Hangul, emoji presentation)
            codePoint in 0x1100..0x115F ||
                codePoint == 0x2329 || codePoint == 0x232A ||
                codePoint in 0x2E80..0x303E ||
                codePoint in 0x3040..0x33BF ||
                codePoint in 0x3400..0x4DBF ||
                codePoint in 0x4E00..0x9FFF ||
                codePoint in 0xA000..0xA4CF ||
                codePoint in 0xAC00..0xD7AF ||
                codePoint in 0xF900..0xFAFF ||
                codePoint in 0xFE10..0xFE19 ||
                codePoint in 0xFE30..0xFE6B ||
                codePoint in 0xFF01..0xFF60 ||
                codePoint in 0xFFE0..0xFFE6 ||
                codePoint in 0x1F300..0x1F64F ||
                codePoint in 0x1F680..0x1F6FF ||
                codePoint in 0x1F900..0x1F9FF ||
                codePoint in 0x1FA70..0x1FAFF ||
                codePoint in 0x20000..0x2FFFD ||
                codePoint in 0x30000..0x3FFFD -> 2

            // Everything else is single width
            else -> 1
        }
    }
}
