package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreBannerTest {

    @Test
    fun hydrateHidesWhenServiceIdleEvenIfLastProgressLooksComplete() {
        val banner = BackupRestoreBanner.hydrate(
            serviceRunning = false,
            lastProgress = 100,
            lastMessage = "Restore complete — system ready (user: alpine)"
        )
        assertEquals(BackupRestoreBanner.Hidden, banner)
        assertFalse(banner.visible)
    }

    @Test
    fun hydrateShowsWhileServiceRunning() {
        val banner = BackupRestoreBanner.hydrate(
            serviceRunning = true,
            lastProgress = 72,
            lastMessage = "Installing… 1400 MB"
        )
        assertTrue(banner.visible)
        assertEquals(0.72f, banner.progress, 0.001f)
        assertEquals("Installing… 1400 MB", banner.message)
    }

    @Test
    fun rejectsProgressAfterServiceStops() {
        assertFalse(BackupRestoreBanner.acceptProgress(serviceRunning = false))
        assertTrue(BackupRestoreBanner.acceptProgress(serviceRunning = true))
    }

    @Test
    fun completeHidesTheSettingsBar() {
        assertEquals(BackupRestoreBanner.Hidden, BackupRestoreBanner.hydrate(false, 95, "✓ Installing complete"))
    }
}
