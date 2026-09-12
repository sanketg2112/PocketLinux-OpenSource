package com.sg.linuxgo

import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityEventCompat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crash J: Compose calls AccessibilityEventCompat.setAccessibilityDataSensitive
 * on API 34+. That must not throw if the framework method is missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityEventCompatGateTest {

    @Test
    fun setAccessibilityDataSensitiveDoesNotThrowOnApi34() {
        val event = AccessibilityEvent.obtain()
        AccessibilityEventCompat.setAccessibilityDataSensitive(event, true)
        AccessibilityEventCompat.setAccessibilityDataSensitive(event, false)
        AccessibilityEventCompat.isAccessibilityDataSensitive(event)
        event.recycle()
    }
}
