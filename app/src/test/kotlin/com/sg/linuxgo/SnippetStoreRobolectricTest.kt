package com.sg.linuxgo

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SnippetStoreRobolectricTest {

    @Test
    fun loadDefaultsThenSaveRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Clear any prior test data
        context.getSharedPreferences("PocketLinuxSnippetPrefs", 0).edit().clear().commit()

        val defaults = SnippetStore.loadSnippets(context)
        assertTrue(defaults.size >= 5)
        assertTrue(defaults.any { it.id == "snip_htop" })

        val custom = SnippetEntity(id = "snip_test", title = "Echo", command = "echo hi")
        SnippetStore.saveSnippets(context, listOf(custom) + defaults.take(2))
        val loaded = SnippetStore.loadSnippets(context)
        assertEquals("snip_test", loaded.first().id)
        assertEquals("echo hi", loaded.first().command)
    }

    @Test
    fun addAndDeleteSnippet() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("PocketLinuxSnippetPrefs", 0).edit().clear().commit()
        SnippetStore.saveSnippets(context, SnippetStore.defaultSnippets())

        val added = SnippetStore.addSnippet(context, "Hello", "echo hello", "")
        assertTrue(SnippetStore.loadSnippets(context).any { it.id == added.id })

        SnippetStore.deleteSnippet(context, added.id)
        assertTrue(SnippetStore.loadSnippets(context).none { it.id == added.id })
    }
}
