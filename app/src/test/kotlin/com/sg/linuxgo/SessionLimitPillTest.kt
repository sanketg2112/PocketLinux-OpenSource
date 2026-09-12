package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLimitPillTest {

    @Test
    fun phaseThresholds() {
        assertEquals(SessionLimitPillPhase.None, sessionLimitPhaseFor(-1L))
        assertEquals(SessionLimitPillPhase.None, sessionLimitPhaseFor(6 * 60 * 1000L))
        assertEquals(SessionLimitPillPhase.Warning, sessionLimitPhaseFor(5 * 60 * 1000L))
        assertEquals(SessionLimitPillPhase.Warning, sessionLimitPhaseFor(90_000L))
        assertEquals(SessionLimitPillPhase.Critical, sessionLimitPhaseFor(60_000L))
        assertEquals(SessionLimitPillPhase.Critical, sessionLimitPhaseFor(0L))
    }

    @Test
    fun formatCountdown() {
        assertEquals("5:00", formatSessionCountdown(5 * 60 * 1000L))
        assertEquals("1:05", formatSessionCountdown(65_000L))
        assertEquals("0:00", formatSessionCountdown(-100L))
        assertEquals("0:09", formatSessionCountdown(9_500L))
    }

    @Test
    fun showsCountdownRespectsDismissForWarningOnly() {
        assertFalse(sessionLimitShowsCountdown(SessionLimitPillPhase.None, false))
        assertTrue(sessionLimitShowsCountdown(SessionLimitPillPhase.Warning, false))
        assertFalse(sessionLimitShowsCountdown(SessionLimitPillPhase.Warning, true))
        assertTrue(sessionLimitShowsCountdown(SessionLimitPillPhase.Critical, true))
    }

    @Test
    fun pillWidthDependsOnExpandedAndPhase() {
        assertEquals(160, sessionLimitPillWidthDp(menuExpanded = true, showCountdown = true, phase = SessionLimitPillPhase.Critical))
        assertEquals(96, sessionLimitPillWidthDp(menuExpanded = false, showCountdown = false, phase = SessionLimitPillPhase.Warning))
        assertEquals(156, sessionLimitPillWidthDp(menuExpanded = false, showCountdown = true, phase = SessionLimitPillPhase.Warning))
        assertEquals(188, sessionLimitPillWidthDp(menuExpanded = false, showCountdown = true, phase = SessionLimitPillPhase.Critical))
    }
}
