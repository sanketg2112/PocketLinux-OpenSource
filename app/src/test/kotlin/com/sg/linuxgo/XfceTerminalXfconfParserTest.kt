package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XfceTerminalXfconfParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <channel name="xfce4-terminal" version="1.0">
          <property name="color-foreground" type="string" value="#f8f8f2"/>
          <property name="color-background" type="string" value="#282a36"/>
          <property name="color-cursor" type="string" value="#bd93f9"/>
          <property name="font-name" type="string" value="MesloLGS NF 13"/>
          <property name="misc-cursor-blinks" type="bool" value="true"/>
          <property name="misc-cursor-shape" type="string" value="TERMINAL_CURSOR_SHAPE_UNDERLINE"/>
        </channel>
    """.trimIndent()

    @Test
    fun parse_xfconfXml() {
        val p = XfceTerminalXfconfParser.parse(sample)
        assertEquals(TerminalColorMath.parseHexRgb("#282a36"), p.colorBackground)
        assertEquals(TerminalColorMath.parseHexRgb("#f8f8f2"), p.colorForeground)
        assertEquals("MesloLGS NF", p.fontFamily)
        assertEquals(13f, p.fontSizePt!!, 0.01f)
        assertTrue(p.cursorBlinks)
        assertEquals(TerminalCursorShape.UNDERLINE, p.cursorShape)
    }

    @Test
    fun xfconfToRcKey_knownNames() {
        assertEquals("ColorForeground", XfceTerminalXfconfParser.xfconfToRcKey("color-foreground"))
        assertEquals("FontName", XfceTerminalXfconfParser.xfconfToRcKey("font-name"))
        assertNotNull(XfceTerminalXfconfParser.xfconfToRcKey("unknown-key"))
    }
}
