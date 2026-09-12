package com.sg.linuxgo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sg.linuxgo.ui.theme.TerminalFontOption
import com.sg.linuxgo.ui.theme.TerminalNerdFont
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TerminalNerdFontAssetsTest {

    @Test
    fun extraNerdFontsAndSymbolsArePackaged() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val paths = listOf(
            TerminalNerdFont.MESLO_ASSET,
            TerminalNerdFont.SYMBOLS_ASSET,
            TerminalFontOption.JETBRAINS_MONO_NF.assetPath!!,
            TerminalFontOption.FIRA_CODE_NF.assetPath!!,
            TerminalFontOption.HACK_NF.assetPath!!,
            TerminalFontOption.CASKAYDIA_COVE_NF.assetPath!!
        )
        paths.forEach { path ->
            ctx.assets.open(path).use { stream ->
                assertTrue(path, stream.available() > 1000)
            }
        }
    }

    @Test
    fun extraNerdFamiliesAreListedWhenPackaged() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(TerminalFontOption.FIRA_CODE_NF.isListed(ctx))
        assertTrue(TerminalFontOption.HACK_NF.isListed(ctx))
        assertTrue(TerminalFontOption.CASKAYDIA_COVE_NF.isListed(ctx))
        assertTrue(TerminalFontOption.JETBRAINS_MONO_NF.isListed(ctx))
        assertTrue(TerminalFontOption.MESLO.isListed(ctx))
    }
}
