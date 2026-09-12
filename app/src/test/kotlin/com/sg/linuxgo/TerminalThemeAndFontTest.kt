package com.sg.linuxgo

import androidx.compose.ui.graphics.toArgb
import com.sg.linuxgo.ui.screens.TerminalTheme
import com.sg.linuxgo.ui.theme.TerminalFontOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalThemeAndFontTest {

    @Test
    fun themeFromIdKnownAndFallback() {
        assertEquals(TerminalTheme.DRACULA, TerminalTheme.fromId("dracula"))
        assertEquals(TerminalTheme.ONE_DARK, TerminalTheme.fromId("one_dark"))
        assertEquals(TerminalTheme.DEFAULT, TerminalTheme.fromId("nope"))
        assertEquals(TerminalTheme.DEFAULT, TerminalTheme.fromId("default"))
    }

    @Test
    fun allThemesHaveStableIdsAndReadableContrastHints() {
        TerminalTheme.values().forEach { theme ->
            assertTrue(theme.id.isNotBlank())
            assertTrue(theme.themeName.isNotBlank())
            // Light theme is marked light; dark themes are not.
            if (theme == TerminalTheme.LIGHT) {
                assertTrue(theme.isLight)
            } else {
                assertFalse(theme.isLight)
            }
        }
    }

    @Test
    fun fontFromIdKnownAndFallback() {
        assertEquals(TerminalFontOption.FIRA_CODE, TerminalFontOption.fromId("fira_code"))
        assertEquals(TerminalFontOption.SYSTEM_MONOSPACE, TerminalFontOption.fromId("system_monospace"))
        assertEquals(TerminalFontOption.MESLO, TerminalFontOption.fromId("meslo"))
        assertEquals(TerminalFontOption.MESLO, TerminalFontOption.fromId("unknown"))
        assertEquals(TerminalFontOption.JETBRAINS_MONO_NF, TerminalFontOption.fromId("jetbrains_mono_nf"))
        assertEquals(TerminalFontOption.HACK_NF, TerminalFontOption.fromId("hack_nf"))
        assertEquals(null, TerminalFontOption.SYSTEM_MONOSPACE.assetPath)
        assertTrue(TerminalFontOption.MESLO.assetPath!!.endsWith(".ttf"))
        assertTrue(TerminalFontOption.JETBRAINS_MONO.assetPath!!.endsWith(".ttf"))
        assertTrue(TerminalFontOption.MESLO.isNerdFont)
        assertTrue(TerminalFontOption.FIRA_CODE_NF.isNerdFont)
        assertFalse(TerminalFontOption.ROBOTO_MONO.isNerdFont)
    }

    @Test
    fun everyThemeIdHasMatchingAnsiPalette() {
        TerminalTheme.values().forEach { theme ->
            val pal = TerminalThemePalettes.ansi16(theme.id)
            assertEquals(theme.id, 16, pal.size)
        }
    }

    @Test
    fun tabAndStatusChromeUseCanvasBackground() {
        TerminalTheme.values().forEach { theme ->
            assertEquals(
                theme.backgroundColor.toArgb(),
                ThemeUiSupport.terminalBackgroundArgb(theme.id)
            )
            assertEquals(
                theme.backgroundColor.toArgb(),
                ThemeUiSupport.terminalTabBarArgb(theme.id)
            )
        }
    }

    @Test
    fun extraNerdFontAssetsAreNamed() {
        assertTrue(TerminalFontOption.FIRA_CODE_NF.assetPath!!.contains("FiraCode"))
        assertTrue(TerminalFontOption.HACK_NF.assetPath!!.contains("Hack"))
        assertTrue(TerminalFontOption.CASKAYDIA_COVE_NF.assetPath!!.contains("Caskaydia"))
        assertTrue(TerminalFontOption.JETBRAINS_MONO_NF.assetPath!!.contains("JetBrainsMono"))
    }
}
