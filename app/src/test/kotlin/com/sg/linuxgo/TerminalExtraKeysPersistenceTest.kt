package com.sg.linuxgo

import com.sg.linuxgo.ui.components.ADD_KEY_ID
import com.sg.linuxgo.ui.components.KEYS_PER_GROUP
import com.sg.linuxgo.ui.components.KEYS_PER_PANEL_ROW
import com.sg.linuxgo.ui.components.defaultPanelKeys
import com.sg.linuxgo.ui.components.defaultTopKeys
import com.sg.linuxgo.ui.components.extraKeyCatalog
import com.sg.linuxgo.ui.components.newCustomExtraKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExtraKeysPersistenceTest {

    @Test
    fun layoutConstants() {
        assertEquals(4, KEYS_PER_GROUP)
        assertEquals(8, KEYS_PER_PANEL_ROW)
        assertEquals(0, KEYS_PER_PANEL_ROW % KEYS_PER_GROUP)
    }

    @Test
    fun defaultTopKeysIncludeCoreModifiersAndActions() {
        val top = defaultTopKeys()
        val names = top.map { it.keyName }.toSet()
        assertTrue(names.containsAll(listOf("LEFT", "RIGHT", "UP", "DOWN", "CTRL", "ALT", "TAB", "ENTER", "ESC")))
        assertTrue(top.any { it.keyName == "SETTINGS_ACTION" })
        assertTrue(top.any { it.keyName == "SNIPPET_ACTION" })
        assertTrue(top.none { it.id == ADD_KEY_ID })
    }

    @Test
    fun defaultPanelKeysStartWithCommonCtrlCombos() {
        val panel = defaultPanelKeys()
        assertTrue(panel.size >= 12)
        assertEquals("^C", panel[0].keyName)
        assertEquals("^Z", panel[1].keyName)
        assertTrue(panel.any { it.keyName == "F12" })
        assertTrue(panel.none { it.id == ADD_KEY_ID })
    }

    @Test
    fun newCustomExtraKeyTruncatesLabelAndMarksCustom() {
        val key = newCustomExtraKey(label = "VERYLONG", keyName = "Ctrl+X")
        assertEquals(4, key.label.length)
        assertEquals("Ctrl+X", key.keyName)
        assertTrue(key.isCustom)
        assertTrue(key.id.startsWith("custom_"))
    }

    @Test
    fun catalogHasModifiersCtrlArrowsAndNav() {
        val catalog = extraKeyCatalog()
        val categories = catalog.map { it.category }.toSet()
        assertTrue(categories.containsAll(listOf("Modifiers", "Ctrl", "Arrows", "Navigation")))
        assertTrue(catalog.any { it.keyName == "CTRL" && it.isToggle })
        assertTrue(catalog.any { it.keyName == "^C" })
        assertTrue(catalog.any { it.keyName == "LEFT" && it.isArrow })
        assertFalse(catalog.isEmpty())
    }
}
