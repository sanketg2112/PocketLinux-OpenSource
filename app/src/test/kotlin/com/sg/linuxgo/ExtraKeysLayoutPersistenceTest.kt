package com.sg.linuxgo

import com.sg.linuxgo.ui.components.ExtraKey
import com.sg.linuxgo.ui.components.ExtraKeysLayout
import com.sg.linuxgo.ui.components.defaultPanelKeys
import com.sg.linuxgo.ui.components.defaultTopKeys
import com.sg.linuxgo.ui.components.keysRevision
import com.sg.linuxgo.ui.components.loadExtraKeysLayout
import com.sg.linuxgo.ui.components.resetExtraKeysLayout
import com.sg.linuxgo.ui.components.saveExtraKeysLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysLayoutPersistenceTest {

    @Test
    fun loadDefaultsWhenEmpty() {
        val prefs = InMemorySharedPreferences()
        val layout = loadExtraKeysLayout(prefs)
        assertEquals(defaultTopKeys().map { it.keyName }, layout.topKeys.map { it.keyName })
        assertEquals(defaultPanelKeys().map { it.keyName }, layout.panelKeys.map { it.keyName })
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val prefs = InMemorySharedPreferences()
        val custom = ExtraKeysLayout(
            topKeys = listOf(
                ExtraKey("t1", "TAB", "TAB"),
                ExtraKey("t2", "ESC", "ESC")
            ),
            panelKeys = listOf(
                ExtraKey("p1", "^C", "^C"),
                ExtraKey("p2", "HOME", "HOME")
            )
        )
        val beforeRev = keysRevision(prefs)
        saveExtraKeysLayout(prefs, custom)
        assertTrue(keysRevision(prefs) >= beforeRev)

        val loaded = loadExtraKeysLayout(prefs)
        assertEquals(listOf("TAB", "ESC"), loaded.topKeys.map { it.keyName })
        assertEquals(listOf("^C", "HOME"), loaded.panelKeys.map { it.keyName })
    }

    @Test
    fun resetRestoresDefaults() {
        val prefs = InMemorySharedPreferences()
        saveExtraKeysLayout(
            prefs,
            ExtraKeysLayout(
                topKeys = listOf(ExtraKey("x", "X", "X")),
                panelKeys = listOf(ExtraKey("y", "Y", "Y"))
            )
        )
        val reset = resetExtraKeysLayout(prefs)
        assertEquals(defaultTopKeys().size, reset.topKeys.size)
        assertEquals(defaultPanelKeys().size, reset.panelKeys.size)
        val reloaded = loadExtraKeysLayout(prefs)
        assertEquals(defaultTopKeys().map { it.keyName }, reloaded.topKeys.map { it.keyName })
    }
}
