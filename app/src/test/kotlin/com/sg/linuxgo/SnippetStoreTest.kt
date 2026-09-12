package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetStoreTest {

    @Test
    fun defaultSnippetsAreNonEmptyWithStableIds() {
        val defaults = SnippetStore.defaultSnippets()
        assertTrue(defaults.size >= 5)
        assertTrue(defaults.all { it.id.startsWith("snip_") })
        assertTrue(defaults.any { it.command.contains("apt") })
        assertTrue(defaults.any { it.variablesString == "port" })
        // unique ids
        assertEquals(defaults.size, defaults.map { it.id }.toSet().size)
    }
}
