package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChecklistSupportTest {

    @Test
    fun logWorthyProgressMessages() {
        assertTrue(SetupChecklistSupport.isLogWorthyProgressMessage("Starting setup..."))
        assertTrue(SetupChecklistSupport.isLogWorthyProgressMessage("Fetching container catalog"))
        assertTrue(SetupChecklistSupport.isLogWorthyProgressMessage("Downloading container image"))
        assertTrue(SetupChecklistSupport.isLogWorthyProgressMessage("Verifying image sha256"))
        assertTrue(SetupChecklistSupport.isLogWorthyProgressMessage("Extracting rootfs"))
        assertTrue(SetupChecklistSupport.isLogWorthyProgressMessage("✓ Install complete"))
        assertTrue(SetupChecklistSupport.isLogWorthyProgressMessage("Benchmarking mirrors…"))
        assertFalse(SetupChecklistSupport.isLogWorthyProgressMessage("curl: (6) could not resolve"))
        assertFalse(SetupChecklistSupport.isLogWorthyProgressMessage("  "))
    }

    @Test
    fun highLevelProgressMessages() {
        assertTrue(SetupChecklistSupport.isHighLevelProgressMessage("Phase 2/8: Download"))
        assertTrue(SetupChecklistSupport.isHighLevelProgressMessage("Starting setup"))
        assertTrue(SetupChecklistSupport.isHighLevelProgressMessage("Finalizing"))
        assertTrue(SetupChecklistSupport.isHighLevelProgressMessage("✓ Using cached image"))
        assertFalse(SetupChecklistSupport.isHighLevelProgressMessage("Get:1 http://deb.debian.org"))
        // Dotted phase lines are not "log worthy" for phase tick, but high-level may still match Phase /8
        assertTrue(SetupChecklistSupport.isHighLevelProgressMessage("Phase 3/8: Configure."))
    }
}
