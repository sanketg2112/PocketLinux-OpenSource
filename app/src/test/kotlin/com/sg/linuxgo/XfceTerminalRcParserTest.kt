package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XfceTerminalRcParserTest {

    private val sampleRc = """
        [Configuration]
        ColorForeground=#f8f8f2
        ColorBackground=#282a36
        ColorCursor=#bd93f9
        ColorPalette=#21222c;#ff5555;#50fa7b;#f1fa8c;#bd93f9;#ff79c6;#8be9fd;#f8f8f2;#6272a4;#ff6e6e;#69ff94;#ffffa5;#d6acff;#ff92df;#a4ffff;#ffffff
        FontName=MesloLGS NF 12
        MiscAlwaysShowTabs=FALSE
    """.trimIndent()

    @Test
    fun parse_draculaStyleTerminalrc() {
        val p = XfceTerminalRcParser.parse(sampleRc)
        assertNotNull(p.colorForeground)
        assertNotNull(p.colorBackground)
        assertNotNull(p.colorCursor)
        assertNotNull(p.colorPalette)
        assertEquals(16, p.colorPalette!!.size)
        assertEquals("MesloLGS NF", p.fontFamily)
        assertEquals(12f, p.fontSizePt!!, 0.01f)

        assertEquals(0xF8, TerminalColorMath.red(p.colorForeground!!))
        assertEquals(0x28, TerminalColorMath.red(p.colorBackground!!))
        // palette index 1 = red #ff5555
        assertEquals(0xFF, TerminalColorMath.red(p.colorPalette!![1]))
        assertEquals(0x55, TerminalColorMath.green(p.colorPalette!![1]))
    }

    @Test
    fun toColorScheme_fillsFromParsed() {
        val p = XfceTerminalRcParser.parse(sampleRc)
        val scheme = XfceTerminalRcParser.toColorScheme(p)
        assertEquals(p.colorBackground, scheme.defaultBg)
        assertEquals(p.colorForeground, scheme.defaultFg)
        assertEquals(p.colorCursor, scheme.cursorColor)
        assertEquals(p.colorPalette!![2], scheme.color(2))
        assertEquals("MesloLGS NF", scheme.fontName)
        assertEquals(12f, scheme.fontSizePt!!, 0.01f)
        assertEquals(TerminalColorScheme.Source.XFCE_TERMINALRC, scheme.source)
    }

    @Test
    fun parse_missingPalette_keepsBase() {
        val text = """
            [Configuration]
            ColorForeground=#ffffff
            ColorBackground=#000000
        """.trimIndent()
        val p = XfceTerminalRcParser.parse(text)
        assertNull(p.colorPalette)
        val base = TerminalColorScheme.oneDark()
        val scheme = XfceTerminalRcParser.toColorScheme(p, base)
        assertEquals(base.color(3), scheme.color(3))
        assertEquals(TerminalColorMath.parseHexRgb("#ffffff"), scheme.defaultFg)
        assertEquals(TerminalColorMath.parseHexRgb("#000000"), scheme.defaultBg)
    }

    @Test
    fun parseFontName_variants() {
        assertEquals("MesloLGS NF" to 12f, XfceTerminalRcParser.parseFontName("MesloLGS NF 12"))
        assertEquals("Monospace" to 10f, XfceTerminalRcParser.parseFontName("Monospace 10"))
        val (fam, size) = XfceTerminalRcParser.parseFontName("JetBrains Mono")
        assertEquals("JetBrains Mono", fam)
        assertNull(size)
    }

    @Test
    fun parsePalette_requiresSixteen() {
        assertNull(XfceTerminalRcParser.parsePalette("#000;#111"))
        val sixteen = (0 until 16).joinToString(";") { "#${"%02x".format(it)}${"%02x".format(it)}${"%02x".format(it)}" }
        val pal = XfceTerminalRcParser.parsePalette(sixteen)
        assertNotNull(pal)
        assertEquals(16, pal!!.size)
    }

    @Test
    fun parse_ignoresCommentsAndSections() {
        val text = """
            # comment
            [Configuration]
            ; another
            ColorForeground=#abcdef
            [Other]
            ColorBackground=#000000
        """.trimIndent()
        // Keys after another section still collected (simple parser)
        val p = XfceTerminalRcParser.parse(text)
        assertEquals(TerminalColorMath.parseHexRgb("#abcdef"), p.colorForeground)
        assertEquals(TerminalColorMath.parseHexRgb("#000000"), p.colorBackground)
    }

    @Test
    fun emptyText_returnsEmptyParsed() {
        val p = XfceTerminalRcParser.parse("")
        assertNull(p.colorForeground)
        assertTrue(p.rawKeys.isEmpty())
    }
}
