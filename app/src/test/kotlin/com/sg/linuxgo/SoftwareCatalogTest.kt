package com.sg.linuxgo

import com.sg.linuxgo.ui.components.SoftwareCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareCatalogTest {

    @Test
    fun defaultCatalogueHasUniqueIdsAndKnownApps() {
        val cat = SoftwareCatalog.defaultCatalogue()
        assertTrue(cat.size >= 10)
        val ids = cat.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("firefox", "chromium", "neovim", "vlc")))
        assertTrue(cat.all { it.label.isNotBlank() })
    }
}
