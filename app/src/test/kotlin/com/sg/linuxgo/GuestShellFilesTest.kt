package com.sg.linuxgo

import com.sg.linuxgo.util.GuestShellFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestShellFilesTest {

    private val start = "# PocketLinux identity (managed — do not remove)"
    private val end = "# PocketLinux identity end"
    private val block = """
        $start
        [ -r /etc/profile.d/pocketlinux-identity.sh ] && . /etc/profile.d/pocketlinux-identity.sh
        $end
    """.trimIndent()

    @Test
    fun upsertPrependsWhenMissingSoUserThemeAfterSurvives() {
        val existing = """
            # user theme
            PS1='fancy> '
            alias ll='ls -la'
        """.trimIndent()
        val out = GuestShellFiles.upsertManagedBlock(
            existing, start, end, block, prependIfMissing = true
        )
        assertTrue(out.indexOf(start) < out.indexOf("PS1='fancy> '"))
        assertTrue(out.contains("alias ll='ls -la'"))
        assertTrue(out.contains(end))
    }

    @Test
    fun upsertDoesNotDeleteContentAfterManagedBlock() {
        val existing = """
            export FOO=1
            $start
            old managed line
            # theme installer after managed block
            PS1='THEME> '
            source ~/oh-my-bash.sh
        """.trimIndent()
        val out = GuestShellFiles.upsertManagedBlock(
            existing, start, end, block, prependIfMissing = true
        )
        assertTrue(out.contains("export FOO=1"))
        assertTrue("theme after block must survive", out.contains("PS1='THEME> '"))
        assertTrue(out.contains("source ~/oh-my-bash.sh"))
        assertTrue(out.contains(end))
        // Old buggy substringBefore would drop these:
        assertFalse(out.trimEnd().endsWith("old managed line"))
    }

    @Test
    fun upsertWithEndMarkerKeepsAfter() {
        val existing = """
            before
            $start
            old body
            $end
            after theme
        """.trimIndent()
        val out = GuestShellFiles.upsertManagedBlock(existing, start, end, block)
        assertTrue(out.contains("before"))
        assertTrue(out.contains("after theme"))
        assertTrue(out.contains(end))
        assertEquals(1, out.split(start).size - 1)
    }

    @Test
    fun legacySubstringBeforeBugWouldHaveDroppedTheme() {
        // Document the old failure mode for regression clarity.
        val marker = start
        val existing = "PS1=user\n$marker\nsource id\nPS1=theme\n"
        val oldBuggy = existing.substringBefore(marker).trimEnd() + "\n" + block
        assertFalse(oldBuggy.contains("PS1=theme"))
        val fixed = GuestShellFiles.upsertManagedBlock(existing, start, end, block)
        assertTrue(fixed.contains("PS1=theme"))
    }

    @Test
    fun stripManagedBlockRemovesPocketLinuxFooterKeepsStarship() {
        val existing = """
            alias ll='ls -la'
            $start
            [ -r /etc/profile.d/pocketlinux-identity.sh ] && . /etc/profile.d/pocketlinux-identity.sh
            $end
            eval "\$(starship init bash)"
        """.trimIndent()
        val out = GuestShellFiles.stripManagedBlock(existing, start, end)
        assertFalse(out.contains(start))
        assertFalse(out.contains("pocketlinux-identity"))
        assertTrue(out.contains("alias ll='ls -la'"))
        assertTrue(out.contains("starship init bash"))
    }
}
