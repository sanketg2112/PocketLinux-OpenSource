package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OSC 4 / 10 / 11 / 12 color updates on [TerminalEmulator].
 */
class TerminalOscPaletteTest {

    @Test
    fun osc4_setsPaletteEntry() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        val before = e.colorScheme.color(1)
        e.write("\u001B]4;1;#ff0000\u0007")
        val after = e.colorScheme.color(1)
        assertNotEquals(before, after)
        assertEquals(0xFF, TerminalColorMath.red(after))
        assertEquals(0x00, TerminalColorMath.green(after))
        assertEquals(0x00, TerminalColorMath.blue(after))
    }

    @Test
    fun osc10_setsDefaultFg() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        e.write("\u001B]10;#112233\u0007")
        assertEquals(TerminalColorMath.parseHexRgb("#112233"), e.colorScheme.defaultFg)
        assertEquals(e.colorScheme.defaultFg, e.currentFgColor)
    }

    @Test
    fun osc11_setsDefaultBg() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        e.write("\u001B]11;rgb:aa/bb/cc\u0007")
        assertEquals(TerminalColorMath.parseHexRgb("#aabbcc"), e.colorScheme.defaultBg)
    }

    @Test
    fun osc12_setsCursor() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        e.write("\u001B]12;#00ff00\u001B\\")
        assertEquals(TerminalColorMath.parseHexRgb("#00ff00"), e.colorScheme.cursorColor)
    }

    @Test
    fun applyColorScheme_updatesDefaultFgWhenTracking() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        val custom = TerminalColorScheme.oneDark().withDefaultFg(TerminalColorMath.packRgb(5, 6, 7))
        e.applyColorScheme(custom)
        assertEquals(custom.defaultFg, e.currentFgColor)
        assertEquals(custom, e.colorScheme)
    }

    @Test
    fun sgrUsesAppliedScheme() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        val red = TerminalColorMath.packRgb(200, 10, 10)
        e.applyColorScheme(TerminalColorScheme.oneDark().withPaletteColor(1, red))
        e.write("\u001B[31mX")
        // Cell 0,0 should be red from custom palette
        val line = e.getActiveLine(0)
        assertEquals(red, line.fgColors[0])
    }

    @Test
    fun applyColorScheme_remintsExistingAnsiCells() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        val firstRed = TerminalColorMath.packRgb(200, 10, 10)
        val secondRed = TerminalColorMath.packRgb(10, 200, 10)
        e.applyColorScheme(TerminalColorScheme.oneDark().withPaletteColor(1, firstRed))
        e.write("\u001B[31mX")
        assertEquals(firstRed, e.getActiveLine(0).fgColors[0])
        e.applyColorScheme(e.colorScheme.withPaletteColor(1, secondRed))
        assertEquals(secondRed, e.getActiveLine(0).fgColors[0])
    }

    @Test
    fun osc11_queryReportsThemeBackground() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        val bg = TerminalColorMath.packRgb(40, 42, 54)
        e.applyColorScheme(TerminalColorScheme.fromAppChrome(bg, 0xFFE5E9F0.toInt(), bg, "dracula"))
        var reply: String? = null
        e.onResponse = { reply = it }
        e.write("\u001B]11;?\u0007")
        assertEquals(TerminalOscColor.oscReply("11", TerminalOscColor.rgbColonSpec(bg)), reply)
    }

    @Test
    fun altBufferEnterRestoresAppThemeAfterOscOverride() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        val bg = TerminalColorMath.packRgb(40, 42, 54)
        val fg = 0xFFF8F8F2.toInt()
        e.applyColorScheme(TerminalColorScheme.fromAppChrome(bg, fg, bg, "dracula"))
        e.write("\u001B]11;#000000\u0007")
        e.write("\u001B]4;1;#00ff00\u0007")
        assertEquals(TerminalColorMath.parseHexRgb("#000000"), e.colorScheme.defaultBg)
        assertEquals(TerminalColorMath.parseHexRgb("#00ff00"), e.colorScheme.color(1))
        e.write("\u001B[?1049h")
        assertTrue(e.isAlternateBuffer)
        assertEquals(bg, e.colorScheme.defaultBg)
        assertEquals(TerminalColorMath.parseHexRgb("#FF5555"), e.colorScheme.color(1))
        e.write("\u001B]11;#111111\u0007")
        e.write("\u001B[?1049l")
        assertFalse(e.isAlternateBuffer)
        assertEquals(bg, e.colorScheme.defaultBg)
    }

    @Test
    fun osc4_queryReportsThemePaletteRed() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        val red = TerminalColorMath.parseHexRgb("#FF5555")!!
        e.applyColorScheme(
            TerminalColorScheme.fromAppChrome(
                TerminalColorMath.packRgb(40, 42, 54),
                0xFFF8F8F2.toInt(),
                red,
                "dracula"
            )
        )
        var reply: String? = null
        e.onResponse = { reply = it }
        e.write("\u001B]4;1;?\u0007")
        assertEquals(TerminalOscColor.oscReply("4", "1;${TerminalOscColor.rgbColonSpec(red)}"), reply)
    }
}
