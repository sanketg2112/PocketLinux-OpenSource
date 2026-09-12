package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yEventCompatGuardTest {

    @Test
    fun matchesSetAccessibilityDataSensitiveNsme() {
        val t = NoSuchMethodError(
            "No virtual method setAccessibilityDataSensitive(Z)V in class " +
                "Landroid/view/accessibility/AccessibilityEvent;"
        )
        assertTrue(A11yEventCompatGuard.isMissingAccessibilityDataSensitive(t))
    }

    @Test
    fun matchesWrappedCause() {
        val t = RuntimeException(
            "compose a11y",
            NoSuchMethodError("isAccessibilityDataSensitive"),
        )
        assertTrue(A11yEventCompatGuard.isMissingAccessibilityDataSensitive(t))
    }

    @Test
    fun ignoresOtherNsme() {
        assertFalse(
            A11yEventCompatGuard.isMissingAccessibilityDataSensitive(
                NoSuchMethodError("No virtual method foo()V")
            )
        )
        assertFalse(
            A11yEventCompatGuard.isMissingAccessibilityDataSensitive(
                IllegalStateException("boom")
            )
        )
    }
}
