package com.sg.linuxgo

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LinuxIsolationTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun newInstallDoesNotBindWithoutChoiceOrPermission() {
        assertFalse(LinuxIsolation.hasExplicitBindChoice(context))
        assertFalse(LinuxIsolation.shouldBindPhoneStorage(context))
        assertNull(LinuxIsolation.phoneStorageBindSpec(context))
    }

    @Test
    fun explicitOffNeverBinds() {
        LinuxIsolation.setBindPhoneStorage(context, false)
        assertTrue(LinuxIsolation.hasExplicitBindChoice(context))
        assertFalse(LinuxIsolation.isBindPrefEnabled(context))
        assertFalse(LinuxIsolation.shouldBindPhoneStorage(context))
    }

    @Test
    fun explicitOnStillRequiresStorageAccess() {
        LinuxIsolation.setBindPhoneStorage(context, true)
        assertTrue(LinuxIsolation.isBindPrefEnabled(context))
        // Robolectric default: WRITE_EXTERNAL_STORAGE not granted.
        assertFalse(LinuxIsolation.hasStorageAccess(context))
        assertFalse(LinuxIsolation.shouldBindPhoneStorage(context))
        assertNull(LinuxIsolation.phoneStorageBindSpec(context))
    }
}
