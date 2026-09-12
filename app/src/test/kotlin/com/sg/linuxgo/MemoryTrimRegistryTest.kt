package com.sg.linuxgo

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryTrimRegistryTest {

    @Before
    fun setUp() {
        MemoryTrimRegistry.clearListenersForTest()
    }

    @Test
    fun trimMemoryNotifiesRegisteredListeners() {
        val levels = mutableListOf<Int>()
        val listener = MemoryTrimRegistry.TrimListener { level ->
            levels.add(level)
        }
        MemoryTrimRegistry.register(listener)

        MemoryTrimRegistry.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        MemoryTrimRegistry.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

        assertEquals(listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_COMPLETE), levels)

        MemoryTrimRegistry.unregister(listener)
        MemoryTrimRegistry.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        // No new events after unregister
        assertEquals(2, levels.size)
    }

    @Test
    fun onLowMemoryTrimsWithCompleteLevel() {
        var receivedLevel = -1
        MemoryTrimRegistry.register { level ->
            receivedLevel = level
        }

        MemoryTrimRegistry.onLowMemory()
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, receivedLevel)
    }

    @Test
    fun terminalEmulatorTrimsScrollbackUnderLowRam() {
        val emulator = TerminalEmulator(rows = 10, cols = 40, maxScrollback = 100)
        // Feed 80 lines into emulator to fill scrollback
        for (i in 1..80) {
            emulator.write("Line $i\r\n")
        }
        assertTrue("Scrollback has lines before trim", emulator.scrollback.size > 50)

        // Trim under RUNNING_LOW
        emulator.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertTrue(
            "Scrollback reduced under memory pressure: ${emulator.scrollback.size}",
            emulator.scrollback.size <= 50
        )
    }
}
