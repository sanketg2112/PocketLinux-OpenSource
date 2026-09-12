package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalImeCoordinatorTest {

    @Test
    fun suppressCancelsPendingShowToken() {
        val ime = TerminalImeCoordinator()
        val show = ime.beginShow()
        assertNotNull(show)
        ime.suppress()
        assertFalse(ime.isCurrentShow(show!!))
        assertTrue(ime.suppressed)
        assertNull(ime.beginShow())
    }

    @Test
    fun hideInvalidatesShowWithoutSuppressing() {
        val ime = TerminalImeCoordinator()
        val show = ime.beginShow()!!
        ime.invalidateShows()
        assertFalse(ime.isCurrentShow(show))
        assertFalse(ime.suppressed)
        assertNotNull(ime.beginShow())
    }

    @Test
    fun releaseAllowsShowAgain() {
        val ime = TerminalImeCoordinator()
        ime.suppress()
        assertNull(ime.beginShow())
        ime.release()
        val show = ime.beginShow()
        assertNotNull(show)
        assertTrue(ime.isCurrentShow(show!!))
    }

    @Test
    fun newerShowInvalidatesOlderToken() {
        val ime = TerminalImeCoordinator()
        val first = ime.beginShow()!!
        val second = ime.beginShow()!!
        assertFalse(ime.isCurrentShow(first))
        assertTrue(ime.isCurrentShow(second))
        assertEquals(2, ime.generation)
    }
}
