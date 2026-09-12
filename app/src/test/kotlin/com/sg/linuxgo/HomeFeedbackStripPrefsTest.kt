package com.sg.linuxgo

import com.sg.linuxgo.util.HomeFeedbackStripPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedbackStripPrefsTest {

    @Test
    fun shouldShowRequiresAllThreeGates() {
        val prefs = InMemorySharedPreferences()
        assertFalse(HomeFeedbackStripPrefs.shouldShow(tipsHintsDismissed = false, prefs = prefs))
        assertFalse(HomeFeedbackStripPrefs.shouldShow(tipsHintsDismissed = true, prefs = prefs))

        prefs.edit().putBoolean(HomeFeedbackStripPrefs.PREF_FIRST_SESSION_STOPPED, true).commit()
        assertTrue(HomeFeedbackStripPrefs.shouldShow(tipsHintsDismissed = true, prefs = prefs))

        prefs.edit().putBoolean(HomeFeedbackStripPrefs.PREF_STRIP_DISMISSED, true).commit()
        assertFalse(HomeFeedbackStripPrefs.shouldShow(tipsHintsDismissed = true, prefs = prefs))
    }

    @Test
    fun markStripDismissedViaPrefs() {
        val prefs = InMemorySharedPreferences()
        assertFalse(HomeFeedbackStripPrefs.isStripDismissed(prefs))
        HomeFeedbackStripPrefs.markStripDismissed(prefs)
        assertTrue(HomeFeedbackStripPrefs.isStripDismissed(prefs))
    }
}
