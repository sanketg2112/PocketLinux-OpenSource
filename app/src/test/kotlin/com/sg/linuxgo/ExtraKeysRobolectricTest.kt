package com.sg.linuxgo

import androidx.test.core.app.ApplicationProvider
import com.sg.linuxgo.ui.components.ExtraKey
import com.sg.linuxgo.ui.components.ExtraKeysLayout
import com.sg.linuxgo.ui.components.defaultPanelKeys
import com.sg.linuxgo.ui.components.defaultTopKeys
import com.sg.linuxgo.ui.components.extraKeysPrefs
import com.sg.linuxgo.ui.components.keysRevision
import com.sg.linuxgo.ui.components.loadExtraKeysLayout
import com.sg.linuxgo.ui.components.resetExtraKeysLayout
import com.sg.linuxgo.ui.components.saveExtraKeysLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExtraKeysRobolectricTest {

    @Test
    fun realSharedPreferencesSaveLoadReset() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = extraKeysPrefs(context)
        prefs.edit().clear().commit()

        val defaults = loadExtraKeysLayout(prefs)
        assertEquals(defaultTopKeys().size, defaults.topKeys.size)
        assertEquals(defaultPanelKeys().size, defaults.panelKeys.size)

        val custom = ExtraKeysLayout(
            topKeys = listOf(ExtraKey("a", "TAB", "TAB"), ExtraKey("b", "ESC", "ESC")),
            panelKeys = listOf(ExtraKey("c", "^C", "^C"))
        )
        val revBefore = keysRevision(prefs)
        saveExtraKeysLayout(prefs, custom)
        assertTrue(keysRevision(prefs) >= revBefore)

        val loaded = loadExtraKeysLayout(prefs)
        assertEquals(listOf("TAB", "ESC"), loaded.topKeys.map { it.keyName })
        assertEquals(listOf("^C"), loaded.panelKeys.map { it.keyName })

        val reset = resetExtraKeysLayout(prefs)
        assertEquals(defaultTopKeys().map { it.keyName }, reset.topKeys.map { it.keyName })
    }
}
