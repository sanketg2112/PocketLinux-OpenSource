package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeepAliveStartPolicyTest {

    @Test
    fun visibleActivityWindowAvoidsForegroundServiceStart() {
        // Crash D on OPPO/OnePlus/HONOR: startForegroundService timer killed app.
        assertFalse(
            SessionKeepAliveStartPolicy.useStartForegroundService(
                sdkInt = 30,
                activityWindowVisible = true,
            )
        )
        assertFalse(
            SessionKeepAliveStartPolicy.useStartForegroundService(
                sdkInt = 34,
                activityWindowVisible = true,
            )
        )
    }

    @Test
    fun backgroundActivityUsesForegroundServiceStartOnOPlus() {
        assertTrue(
            SessionKeepAliveStartPolicy.useStartForegroundService(
                sdkInt = 26,
                activityWindowVisible = false,
            )
        )
        assertTrue(
            SessionKeepAliveStartPolicy.useStartForegroundService(
                sdkInt = 34,
                activityWindowVisible = false,
            )
        )
    }

    @Test
    fun preOAlwaysUsesStartService() {
        assertFalse(
            SessionKeepAliveStartPolicy.useStartForegroundService(
                sdkInt = 25,
                activityWindowVisible = false,
            )
        )
        assertFalse(
            SessionKeepAliveStartPolicy.useStartForegroundService(
                sdkInt = 21,
                activityWindowVisible = true,
            )
        )
    }
}
