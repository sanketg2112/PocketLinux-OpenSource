package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuestTerminalAppearanceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun themeSource_defaultsToMatchGui() {
        assertEquals(
            GuestTerminalAppearance.SOURCE_MATCH_GUI,
            GuestTerminalAppearance.themeSource(null)
        )
        assertTrue(GuestTerminalAppearance.isMatchGui(null))
    }

    @Test
    fun detectShell_prefersPasswdZsh() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/zsh").writeText("#!/bin/zsh")
        File(rootfs, "bin/bash").writeText("#!/bin/bash")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText(
            "root:x:0:0:root:/root:/bin/bash\n" +
                "alice:x:1000:1000:Alice:/home/alice:/bin/zsh\n"
        )
        assertEquals(
            GuestTerminalAppearance.Shell.ZSH,
            GuestTerminalAppearance.detectShell(rootfs, "alice")
        )
    }

    @Test
    fun detectShell_zshrcImpliesZshWhenBinaryPresent() {
        val rootfs = tmp.newFolder("rootfs2")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/zsh").writeText("z")
        File(rootfs, "bin/bash").writeText("b")
        File(rootfs, "home/bob").mkdirs()
        File(rootfs, "home/bob/.zshrc").writeText("export ZSH=1\n")
        assertEquals(
            GuestTerminalAppearance.Shell.ZSH,
            GuestTerminalAppearance.detectShell(rootfs, "bob")
        )
    }

    @Test
    fun detectShell_fallsBackToBash() {
        val rootfs = tmp.newFolder("rootfs3")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("b")
        assertEquals(
            GuestTerminalAppearance.Shell.BASH,
            GuestTerminalAppearance.detectShell(rootfs, "PocketLinux")
        )
    }

    @Test
    fun findTerminalRc_prefersUserHome() {
        val rootfs = tmp.newFolder("rootfs4")
        val userRc = File(rootfs, "home/u/.config/xfce4/terminal/terminalrc")
        userRc.parentFile!!.mkdirs()
        userRc.writeText("ColorForeground=#ffffff\n")
        val rootRc = File(rootfs, "root/.config/xfce4/terminal/terminalrc")
        rootRc.parentFile!!.mkdirs()
        rootRc.writeText("ColorForeground=#000000\n")
        val found = GuestTerminalAppearance.findTerminalRc(rootfs, "u")
        assertEquals(userRc.absolutePath, found!!.absolutePath)
    }

    @Test
    fun resolve_matchGui_readsTerminalrc() {
        val rootfs = tmp.newFolder("rootfs5")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("b")
        val rc = File(rootfs, "home/pl/.config/xfce4/terminal/terminalrc")
        rc.parentFile!!.mkdirs()
        rc.writeText(
            """
            [Configuration]
            ColorForeground=#f8f8f2
            ColorBackground=#282a36
            ColorCursor=#bd93f9
            ColorPalette=#21222c;#ff5555;#50fa7b;#f1fa8c;#bd93f9;#ff79c6;#8be9fd;#f8f8f2;#6272a4;#ff6e6e;#69ff94;#ffffa5;#d6acff;#ff92df;#a4ffff;#ffffff
            FontName=MesloLGS NF 11
            """.trimIndent()
        )
        val fonts = File(rootfs, "usr/share/fonts/truetype")
        fonts.mkdirs()
        val fontFile = File(fonts, "MesloLGS-NF-Regular.ttf")
        fontFile.writeBytes(ByteArray(4))

        val app = TerminalColorScheme.fromAppChrome(
            TerminalColorMath.packRgb(1, 1, 1),
            TerminalColorMath.packRgb(2, 2, 2),
            TerminalColorMath.packRgb(3, 3, 3)
        )
        val resolved = GuestTerminalAppearance.resolve(rootfs, "pl", null, app)
        assertTrue(resolved.matchedGui)
        assertEquals(TerminalColorMath.parseHexRgb("#282a36"), resolved.scheme.defaultBg)
        assertEquals(TerminalColorMath.parseHexRgb("#f8f8f2"), resolved.scheme.defaultFg)
        assertEquals(fontFile.absolutePath, resolved.fontFile!!.absolutePath)
        assertEquals(GuestTerminalAppearance.Shell.BASH, resolved.shell)
    }

    @Test
    fun resolve_appThemeIgnoresTerminalrc() {
        val rootfs = tmp.newFolder("rootfsAppTheme")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("b")
        val rc = File(rootfs, "home/pl/.config/xfce4/terminal/terminalrc")
        rc.parentFile!!.mkdirs()
        rc.writeText(
            """
            [Configuration]
            ColorForeground=#f8f8f2
            ColorBackground=#282a36
            ColorPalette=#21222c;#ff5555;#50fa7b;#f1fa8c;#bd93f9;#ff79c6;#8be9fd;#f8f8f2;#6272a4;#ff6e6e;#69ff94;#ffffa5;#d6acff;#ff92df;#a4ffff;#ffffff
            """.trimIndent()
        )
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString(
                GuestTerminalAppearance.PREF_THEME_SOURCE,
                GuestTerminalAppearance.SOURCE_APP
            )
            .apply()
        val nordBg = TerminalColorMath.packRgb(46, 52, 64)
        val nordFg = TerminalColorMath.packRgb(216, 222, 233)
        val app = TerminalColorScheme.fromAppChrome(nordBg, nordFg, nordFg, "nord")
        val resolved = GuestTerminalAppearance.resolve(rootfs, "pl", prefs, app)
        assertFalse(resolved.matchedGui)
        assertEquals(nordBg, resolved.scheme.defaultBg)
        assertEquals(nordFg, resolved.scheme.defaultFg)
        assertEquals(TerminalColorScheme.Source.APP_THEME, resolved.scheme.source)
    }

    @Test
    fun resolve_noRc_notMatchedGui() {
        val rootfs = tmp.newFolder("rootfs6")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("b")
        val app = TerminalColorScheme.oneDark()
        val resolved = GuestTerminalAppearance.resolve(rootfs, "x", null, app)
        assertFalse(resolved.matchedGui)
        assertNull(resolved.fontFile)
    }

    @Test
    fun resolveShellBinary_picksExisting() {
        val rootfs = tmp.newFolder("rootfs7")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/zsh").writeText("z")
        assertEquals(
            "/usr/bin/zsh",
            GuestTerminalAppearance.resolveShellBinary(rootfs, GuestTerminalAppearance.Shell.ZSH)
        )
    }

    @Test
    fun readPasswdShell_parses() {
        val rootfs = tmp.newFolder("rootfs8")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText("me:x:1000:1000::/home/me:/usr/bin/fish\n")
        assertEquals("/usr/bin/fish", GuestTerminalAppearance.readPasswdShell(rootfs, "me"))
        assertNull(GuestTerminalAppearance.readPasswdShell(rootfs, "other"))
    }

    @Test
    fun resolve_matchGui_readsXfconfXml() {
        val rootfs = tmp.newFolder("rootfsXfconf")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("b")
        val xml = File(
            rootfs,
            "home/pl/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml"
        )
        xml.parentFile!!.mkdirs()
        xml.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <channel name="xfce4-terminal" version="1.0">
              <property name="color-foreground" type="string" value="#eeeeee"/>
              <property name="color-background" type="string" value="#111111"/>
              <property name="font-name" type="string" value="JetBrains Mono 14"/>
            </channel>
            """.trimIndent()
        )
        val fonts = File(rootfs, "usr/share/fonts/truetype")
        fonts.mkdirs()
        val fontFile = File(fonts, "JetBrainsMono-Regular.ttf")
        fontFile.writeBytes(ByteArray(4))
        val app = TerminalColorScheme.oneDark()
        val resolved = GuestTerminalAppearance.resolve(rootfs, "pl", null, app)
        assertTrue(resolved.matchedGui)
        assertEquals(TerminalColorMath.parseHexRgb("#111111"), resolved.scheme.defaultBg)
        assertEquals(14f, resolved.scheme.fontSizePt!!, 0.01f)
        assertEquals(fontFile.absolutePath, resolved.fontFile!!.absolutePath)
    }

    @Test
    fun detectShell_prefersPasswdFish() {
        val rootfs = tmp.newFolder("rootfsFish")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/fish").writeText("f")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("b")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText("sam:x:1000:1000::/home/sam:/usr/bin/fish\n")
        assertEquals(
            GuestTerminalAppearance.Shell.FISH,
            GuestTerminalAppearance.detectShell(rootfs, "sam")
        )
    }

    @Test
    fun findFontFile_prefersMonoRegularWhenFamilyHasVariants() {
        val rootfs = tmp.newFolder("rootfs9")
        val fonts = File(rootfs, "usr/share/fonts/truetype")
        fonts.mkdirs()
        File(fonts, "HackNerdFontPropo-Regular.ttf").writeBytes(ByteArray(4))
        val mono = File(fonts, "HackNerdFontMono-Regular.ttf")
        mono.writeBytes(ByteArray(4))
        File(fonts, "HackNerdFont-Regular.ttf").writeBytes(ByteArray(4))
        val found = GuestTerminalAppearance.findFontFile(rootfs, "Hack Nerd Font")
        assertEquals(mono.absolutePath, found!!.absolutePath)
    }
}
