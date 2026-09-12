package com.sg.linuxgo

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupPasswordStoreTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BackupPasswordStore.clear(context)
    }

    @Test
    fun putThenTakeReturnsPasswordOnce() {
        val token = BackupPasswordStore.put(context, "correct horse")
        assertEquals("correct horse", BackupPasswordStore.take(context, token))
        assertNull(BackupPasswordStore.take(context, token))
    }

    @Test
    fun takeMissingTokenIsNull() {
        assertNull(BackupPasswordStore.take(context, null))
        assertNull(BackupPasswordStore.take(context, "missing-token"))
    }

    @Test
    fun putReplacesPreviousToken() {
        val first = BackupPasswordStore.put(context, "old")
        val second = BackupPasswordStore.put(context, "new")
        assertNull(BackupPasswordStore.take(context, first))
        assertEquals("new", BackupPasswordStore.take(context, second))
    }
}
