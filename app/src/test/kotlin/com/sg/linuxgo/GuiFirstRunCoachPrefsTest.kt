package com.sg.linuxgo

import com.sg.linuxgo.ui.components.GuiFirstRunCoachPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GuiFirstRunCoachPrefsTest {

    @Test
    fun shouldShowThenMarkShown() {
        val context = RuntimeEnvironment.getApplication()
        // Isolate prefs key for this test process
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove(GuiFirstRunCoachPrefs.PREF_SHOWN).commit()

        assertTrue(GuiFirstRunCoachPrefs.shouldShow(context))
        GuiFirstRunCoachPrefs.markShown(context)
        assertFalse(GuiFirstRunCoachPrefs.shouldShow(context))
        assertEquals(true, prefs.getBoolean(GuiFirstRunCoachPrefs.PREF_SHOWN, false))
    }
}
