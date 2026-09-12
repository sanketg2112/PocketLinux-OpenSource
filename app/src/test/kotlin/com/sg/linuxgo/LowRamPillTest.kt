package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowRamPillTest {

    @Test
    fun phase_thresholds() {
        assertEquals(LowRamPillPhase.None, lowRamPhaseFor(501))
        assertEquals(LowRamPillPhase.None, lowRamPhaseFor(800))
        assertEquals(LowRamPillPhase.Warning, lowRamPhaseFor(500))
        assertEquals(LowRamPillPhase.Warning, lowRamPhaseFor(251))
        assertEquals(LowRamPillPhase.Critical, lowRamPhaseFor(250))
        assertEquals(LowRamPillPhase.Critical, lowRamPhaseFor(0))
        assertEquals(LowRamPillPhase.None, lowRamPhaseFor(-1))
    }

    @Test
    fun chip_visibility_dismissable_for_warning_and_critical() {
        assertFalse(lowRamShowsChip(LowRamPillPhase.None, dismissedWarning = false))
        assertTrue(lowRamShowsChip(LowRamPillPhase.Warning, dismissedWarning = false))
        assertFalse(lowRamShowsChip(LowRamPillPhase.Warning, dismissedWarning = true))
        assertTrue(lowRamShowsChip(LowRamPillPhase.Critical, dismissedWarning = false))
        assertFalse(lowRamShowsChip(LowRamPillPhase.Critical, dismissedWarning = true))
    }

    @Test
    fun expanded_menu_width_includes_ram_chip() {
        assertTrue(expandedPillMenuWidthDp(includeRamChip = true) > expandedPillMenuWidthDp(false))
    }

    @Test
    fun effective_phase_takes_worst() {
        assertEquals(
            SessionLimitPillPhase.None,
            effectivePillAlertPhase(LowRamPillPhase.None),
        )
        assertEquals(
            SessionLimitPillPhase.Warning,
            effectivePillAlertPhase(LowRamPillPhase.Warning),
        )
        assertEquals(
            SessionLimitPillPhase.Critical,
            effectivePillAlertPhase(LowRamPillPhase.Critical),
        )
        assertEquals(
            SessionLimitPillPhase.Critical,
            effectivePillAlertPhase(SessionLimitPillPhase.Warning, LowRamPillPhase.Critical),
        )
    }

    @Test
    fun format_avail_ram() {
        assertEquals("500 MB", formatAvailRamShort(500))
        assertEquals("1.0 GB", formatAvailRamShort(1024))
    }

    @Test
    fun simulate_values_map_to_expected_phases() {
        // Mirrors readAvailRamMbForPill inject amounts
        assertEquals(LowRamPillPhase.Warning, lowRamPhaseFor(LOW_RAM_WARN_MB - 50))
        assertEquals(LowRamPillPhase.Critical, lowRamPhaseFor(LOW_RAM_CRITICAL_MB - 50))
    }

    @Test
    fun alert_chip_pinned() {
        assertFalse(pillAlertChipPinned(LowRamPillPhase.None, false))
        assertTrue(pillAlertChipPinned(LowRamPillPhase.Warning, false))
        assertFalse(pillAlertChipPinned(LowRamPillPhase.Warning, true))
        assertTrue(pillAlertChipPinned(LowRamPillPhase.Critical, false))
        assertFalse(pillAlertChipPinned(LowRamPillPhase.Critical, true))
    }

    @Test
    fun loading_hint_only_when_ram_is_actually_low() {
        assertNull(lowRamLoadingHint(LowRamPillPhase.None, 4096))
        assertNull(lowRamLoadingHint(LowRamPillPhase.Warning, -1))
        val warn = lowRamLoadingHint(LowRamPillPhase.Warning, 400)
        assertTrue(warn!!.contains("400 MB"))
        assertFalse(warn.contains("cannot", ignoreCase = true))
        val crit = lowRamLoadingHint(LowRamPillPhase.Critical, 180)
        assertTrue(crit!!.contains("180 MB"))
    }

    @Test
    fun meminfo_available_beats_underreported_activity_manager() {
        val meminfo = """
            MemTotal:       12123456 kB
            MemFree:         1234567 kB
            MemAvailable:    4194304 kB
            Buffers:          123456 kB
        """.trimIndent()
        assertEquals(4194304L, parseMemAvailableKb(meminfo))
        assertEquals(4096, parseMemAvailableMb(meminfo))
        // OEM availMem of 380 MB must not win over 4 GB MemAvailable.
        assertEquals(4096, combineFreeRamMb(4096, 380))
        assertEquals(LowRamPillPhase.None, lowRamPhaseFor(combineFreeRamMb(4096, 380)))
        assertEquals(380, combineFreeRamMb(-1, 380))
        assertEquals(-1, combineFreeRamMb(-1, -1))
        assertNull(parseMemAvailableKb("MemTotal: 1000 kB\n"))
    }
}
