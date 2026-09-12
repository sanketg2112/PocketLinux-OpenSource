package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class X11ServiceStartPolicyTest {

    @Test
    fun visibleGuiOnAndroid11DoesNotUseForegroundServiceStart() {
        // Infinix Hot 10 / API 30 field crash: startForegroundService + slow :x11 spawn.
        assertFalse(
            X11ServiceStartPolicy.useStartForegroundService(
                sdkInt = 30,
                activityWindowVisible = true,
                x11ProcessRunning = false,
            )
        )
    }

    @Test
    fun backgroundColdStartOnOPlusUsesForegroundServiceStart() {
        assertTrue(
            X11ServiceStartPolicy.useStartForegroundService(
                sdkInt = 30,
                activityWindowVisible = false,
                x11ProcessRunning = false,
            )
        )
        assertTrue(
            X11ServiceStartPolicy.useStartForegroundService(
                sdkInt = 34,
                activityWindowVisible = false,
                x11ProcessRunning = false,
            )
        )
    }

    @Test
    fun alreadyRunningProcessNeverUsesForegroundServiceStart() {
        assertFalse(
            X11ServiceStartPolicy.useStartForegroundService(
                sdkInt = 30,
                activityWindowVisible = false,
                x11ProcessRunning = true,
            )
        )
        assertFalse(
            X11ServiceStartPolicy.useStartForegroundService(
                sdkInt = 34,
                activityWindowVisible = true,
                x11ProcessRunning = true,
            )
        )
    }

    @Test
    fun preOAlwaysUsesStartService() {
        assertFalse(
            X11ServiceStartPolicy.useStartForegroundService(
                sdkInt = 25,
                activityWindowVisible = false,
                x11ProcessRunning = false,
            )
        )
    }

    @Test
    fun activityWindowVisibleRequiresLiveVisibleDecor() {
        assertTrue(
            X11ServiceStartPolicy.isActivityWindowVisible(
                isFinishing = false,
                isDestroyed = false,
                windowVisibility = X11ServiceStartPolicy.WINDOW_VISIBLE,
            )
        )
        assertFalse(
            X11ServiceStartPolicy.isActivityWindowVisible(
                isFinishing = true,
                isDestroyed = false,
                windowVisibility = X11ServiceStartPolicy.WINDOW_VISIBLE,
            )
        )
        assertFalse(
            X11ServiceStartPolicy.isActivityWindowVisible(
                isFinishing = false,
                isDestroyed = true,
                windowVisibility = X11ServiceStartPolicy.WINDOW_VISIBLE,
            )
        )
        assertFalse(
            X11ServiceStartPolicy.isActivityWindowVisible(
                isFinishing = false,
                isDestroyed = false,
                windowVisibility = 8, // View.GONE
            )
        )
        assertFalse(
            X11ServiceStartPolicy.isActivityWindowVisible(
                isFinishing = false,
                isDestroyed = false,
                windowVisibility = 4, // View.INVISIBLE
            )
        )
        assertTrue(
            X11ServiceStartPolicy.isActivityWindowVisible(
                isFinishing = false,
                isDestroyed = false,
                windowVisibility = null,
            )
        )
    }

    @Test
    fun windowVisibleConstantMatchesAndroidViewVisible() {
        assertEquals(0, X11ServiceStartPolicy.WINDOW_VISIBLE)
    }
}
