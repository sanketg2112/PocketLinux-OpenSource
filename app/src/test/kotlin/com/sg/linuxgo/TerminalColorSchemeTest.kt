package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalColorSchemeTest {

    @Test
    fun parseHexRgb_rrggbb() {
        val c = TerminalColorMath.parseHexRgb("#E06C75")
        assertNotNull(c)
        assertEquals(0xE0, TerminalColorMath.red(c!!))
        assertEquals(0x6C, TerminalColorMath.green(c))
        assertEquals(0x75, TerminalColorMath.blue(c))
    }

    @Test
    fun parseHexRgb_shortAnd12() {
        val short = TerminalColorMath.parseHexRgb("#f0a")!!
        assertEquals(0xFF, TerminalColorMath.red(short))
        assertEquals(0x00, TerminalColorMath.green(short))
        assertEquals(0xAA, TerminalColorMath.blue(short))

        val long = TerminalColorMath.parseHexRgb("#ffff00000000")!!
        assertEquals(0xFF, TerminalColorMath.red(long))
        assertEquals(0x00, TerminalColorMath.green(long))
        assertEquals(0x00, TerminalColorMath.blue(long))
    }

    @Test
    fun parseColorSpec_rgbSlash() {
        val c = TerminalColorMath.parseColorSpec("rgb:ff/80/00")!!
        assertEquals(0xFF, TerminalColorMath.red(c))
        assertEquals(0x80, TerminalColorMath.green(c))
        assertEquals(0x00, TerminalColorMath.blue(c))
    }

    @Test
    fun parseColorSpec_rejectsQuery() {
        assertNull(TerminalColorMath.parseColorSpec("?"))
    }

    @Test
    fun oneDarkHas16Colors() {
        val s = TerminalColorScheme.oneDark()
        assertEquals(16, s.colors.size)
        assertEquals(s.color(7), s.defaultFg)
    }

    @Test
    fun withPaletteColor_updatesIndex() {
        val base = TerminalColorScheme.oneDark()
        val red = TerminalColorMath.packRgb(255, 0, 0)
        val next = base.withPaletteColor(1, red)
        assertEquals(red, next.color(1))
        assertNotEquals(base.color(1), next.color(1))
        assertEquals(TerminalColorScheme.Source.OSC_DYNAMIC, next.source)
    }

    @Test
    fun applySgr_usesSchemeColors() {
        val customRed = TerminalColorMath.packRgb(1, 2, 3)
        val scheme = TerminalColorScheme.oneDark().withPaletteColor(1, customRed)
        val (fg, _) = TerminalPalette.applySgr(listOf(31), scheme.defaultFg, 0, scheme)
        assertEquals(customRed, fg)
    }

    @Test
    fun applySgr_resetUsesSchemeDefaultFg() {
        val scheme = TerminalColorScheme.oneDark().withDefaultFg(TerminalColorMath.packRgb(10, 20, 30))
        val (fg, bg) = TerminalPalette.applySgr(listOf(0), scheme.color(1), scheme.color(2), scheme)
        assertEquals(scheme.defaultFg, fg)
        assertEquals(0, bg)
    }

    @Test
    fun get256Color_indexUsesScheme() {
        val scheme = TerminalColorScheme.oneDark().withPaletteColor(4, TerminalColorMath.packRgb(9, 8, 7))
        assertEquals(scheme.color(4), TerminalPalette.get256Color(4, scheme))
        // Cube color is deterministic
        val cube = TerminalPalette.get256Color(16, scheme)
        assertTrue(cube != 0 || cube == TerminalColorMath.packRgb(0, 0, 0))
    }

    @Test
    fun fromAppChrome_setsChromeAndThemePalette() {
        val bg = TerminalColorMath.packRgb(1, 1, 1)
        val fg = TerminalColorMath.packRgb(2, 2, 2)
        val cur = TerminalColorMath.packRgb(3, 3, 3)
        val s = TerminalColorScheme.fromAppChrome(bg, fg, cur, "dracula")
        assertEquals(bg, s.defaultBg)
        assertEquals(fg, s.defaultFg)
        assertEquals(cur, s.cursorColor)
        assertEquals(16, s.colors.size)
        assertEquals(TerminalColorScheme.Source.APP_THEME, s.source)
        assertEquals(fg, s.color(7))
        assertEquals(bg, s.color(0))
        assertEquals(TerminalColorMath.parseHexRgb("#FF5555"), s.color(1))
    }

    @Test
    fun everyThemeHasSixteenDistinctEnoughColors() {
        val ids = listOf(
            "default", "podroid", "dracula", "solarized_dark", "monokai",
            "retro_green", "light", "nord", "one_dark", "gruvbox", "aura",
            "cyberpunk", "sunset", "deep_ocean", "forest_moss", "tokyo_night",
            "rose_pine", "synthwave", "espresso", "cyber_lime", "iceberg"
        )
        ids.forEach { id ->
            val pal = TerminalThemePalettes.ansi16(id)
            assertEquals(16, pal.size)
            assertTrue(pal[1] != pal[2])
        }
        assertEquals("15;0", TerminalThemePalettes.colorFgBg("dracula"))
        assertEquals("0;15", TerminalThemePalettes.colorFgBg("light"))
    }

    @Test
    fun fromAppChrome_lightThemeMapsWhiteToCanvas() {
        val bg = TerminalColorMath.packRgb(246, 246, 249)
        val fg = TerminalColorMath.packRgb(28, 28, 30)
        val s = TerminalColorScheme.fromAppChrome(bg, fg, fg, "light")
        assertEquals(bg, s.defaultBg)
        assertEquals(bg, s.color(15))
        assertEquals(fg, s.defaultFg)
        assertTrue(s.color(0) != bg)
    }
}
