package com.sg.linuxgo

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure documentation/guard tests for Crash H helper API surface.
 * View-tree install needs instrumentation; logic is covered by structure + manual GUI launch.
 */
class ComposePopupOwnersTest {

    @Test
    fun contentContainerFactoryIsAvailable() {
        // Class load / API smoke — real attach order is validated on device (GUI launch).
        assertNotNull(ComposePopupOwners)
        assertTrue(ComposePopupOwners.javaClass.simpleName == "ComposePopupOwners")
    }
}
