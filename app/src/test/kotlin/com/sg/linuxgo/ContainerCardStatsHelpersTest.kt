package com.sg.linuxgo

import com.sg.linuxgo.ui.components.RAM_HISTORY_MAX_POINTS
import com.sg.linuxgo.ui.components.appendRamHistorySample
import com.sg.linuxgo.ui.components.formatStorageUsedCompact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerCardStatsHelpersTest {

    @Test
    fun formatStorageUsedCompact_megabytes() {
        assertEquals("0MB", formatStorageUsedCompact(0))
        assertEquals("512MB", formatStorageUsedCompact(512))
        assertEquals("1023MB", formatStorageUsedCompact(1023))
    }

    @Test
    fun formatStorageUsedCompact_gigabytes() {
        assertEquals("1GB", formatStorageUsedCompact(1024))
        assertEquals("1.5GB", formatStorageUsedCompact(1536))
        // 1.78 * 1024 ≈ 1822.72 → 1823 MB rounds to 1.8GB (one decimal)
        assertEquals("1.8GB", formatStorageUsedCompact(1823))
        assertEquals("2GB", formatStorageUsedCompact(2048))
    }

    @Test
    fun appendRamHistorySample_clearsWhenIdle() {
        assertEquals(emptyList<Int>(), appendRamHistorySample(listOf(100, 200), 0))
        assertEquals(emptyList<Int>(), appendRamHistorySample(listOf(100), -1))
    }

    @Test
    fun appendRamHistorySample_appendsAndCaps() {
        val growing = appendRamHistorySample(listOf(10, 20), 30)
        assertEquals(listOf(10, 20, 30), growing)

        val base = (1..RAM_HISTORY_MAX_POINTS).toList()
        val capped = appendRamHistorySample(base, 999)
        assertEquals(RAM_HISTORY_MAX_POINTS, capped.size)
        assertEquals(999, capped.last())
        assertEquals(2, capped.first()) // dropped oldest (1)
    }

    @Test
    fun appendRamHistorySample_fromEmpty() {
        assertEquals(listOf(42), appendRamHistorySample(emptyList(), 42))
        assertTrue(appendRamHistorySample(emptyList(), 0).isEmpty())
    }
}
