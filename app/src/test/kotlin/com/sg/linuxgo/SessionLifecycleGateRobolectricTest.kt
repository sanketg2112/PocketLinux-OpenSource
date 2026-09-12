package com.sg.linuxgo

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionLifecycleGateRobolectricTest {

    @Test
    fun setAllowedPersistsAcrossReads() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionLifecycleGate.setAllowed(context, false)
        assertFalse(SessionLifecycleGate.isAllowed(context))

        SessionLifecycleGate.setAllowed(context, true)
        assertTrue(SessionLifecycleGate.isAllowed(context))

        SessionLifecycleGate.setAllowed(context, false)
        assertFalse(SessionLifecycleGate.isAllowed(context))
    }

    @Test
    fun userStopInProgressPersistsAcrossReads() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionLifecycleGate.setUserStopInProgress(context, false)
        assertFalse(SessionLifecycleGate.isUserStopInProgress(context))

        SessionLifecycleGate.setUserStopInProgress(context, true)
        assertTrue(SessionLifecycleGate.isUserStopInProgress(context))

        SessionLifecycleGate.setUserStopInProgress(context, false)
        assertFalse(SessionLifecycleGate.isUserStopInProgress(context))
    }
}
