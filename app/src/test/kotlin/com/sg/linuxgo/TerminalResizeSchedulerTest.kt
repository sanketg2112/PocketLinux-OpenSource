package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalResizeSchedulerTest {

    @Test
    fun firstSizeAppliesImmediately() {
        var now = 1_000L
        val scheduler = TerminalResizeScheduler(settleMs = 200L, nowMs = { now })
        scheduler.submit("s1", 24, 80, 10, 20)
        assertTrue(scheduler.isVisualPending())
        assertEquals(0L, scheduler.remainingSettleMs())
        val applied = scheduler.takeSettled()
        assertNotNull(applied)
        assertEquals(24, applied!!.rows)
        assertEquals(80, applied.cols)
        assertFalse(scheduler.isVisualPending())
    }

    @Test
    fun midAnimationSizesAreNotAppliedUntilSettle() {
        var now = 1_000L
        val scheduler = TerminalResizeScheduler(settleMs = 200L, nowMs = { now })
        scheduler.submit("s1", 24, 80, 10, 20)
        scheduler.takeSettled()

        scheduler.submit("s1", 20, 80, 10, 20)
        now = 1_050L
        scheduler.submit("s1", 16, 80, 10, 20)
        now = 1_100L
        scheduler.submit("s1", 12, 80, 10, 20)
        assertTrue(scheduler.isVisualPending())
        assertNull(scheduler.takeSettled(now))

        now = 1_299L
        assertNull(scheduler.takeSettled(now))
        now = 1_300L
        val applied = scheduler.takeSettled(now)
        assertNotNull(applied)
        assertEquals(12, applied!!.rows)
        assertEquals(80, applied.cols)
    }

    @Test
    fun identicalSizeDoesNotRetrigger() {
        var now = 5_000L
        val scheduler = TerminalResizeScheduler(settleMs = 200L, nowMs = { now })
        scheduler.submit("s1", 24, 80, 10, 20)
        scheduler.takeSettled()
        scheduler.submit("s1", 24, 80, 10, 20)
        assertFalse(scheduler.isVisualPending())
        assertNull(scheduler.takeSettled())
    }

    @Test
    fun clampsMinimumGrid() {
        val scheduler = TerminalResizeScheduler(nowMs = { 0L })
        val size = scheduler.submit("s1", 1, 2, 0, 0)
        assertEquals(TerminalResizeScheduler.MIN_ROWS, size.rows)
        assertEquals(TerminalResizeScheduler.MIN_COLS, size.cols)
        assertEquals(1, size.charWidthPx)
        assertEquals(1, size.charHeightPx)
    }

    @Test
    fun sessionIdIsPartOfPendingSize() {
        var now = 0L
        val scheduler = TerminalResizeScheduler(settleMs = 200L, nowMs = { now })
        scheduler.submit("a", 24, 80, 10, 20)
        scheduler.takeSettled()
        scheduler.submit("b", 24, 80, 10, 20)
        assertTrue(scheduler.isVisualPending())
        now = 200L
        val applied = scheduler.takeSettled(now)
        assertEquals("b", applied?.sessionId)
    }
}
