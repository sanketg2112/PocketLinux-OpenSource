package com.sg.linuxgo

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.sg.linuxgo.util.HomeFeedbackStripPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeFeedbackStripRobolectricTest {

    @Test
    fun markFirstSessionAndDismissViaContext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()

        val prefs = HomeFeedbackStripPrefs.prefs(context)
        assertFalse(HomeFeedbackStripPrefs.shouldShow(tipsHintsDismissed = true, prefs = prefs))

        HomeFeedbackStripPrefs.markFirstSessionStopped(context)
        assertTrue(HomeFeedbackStripPrefs.shouldShow(tipsHintsDismissed = true, prefs = prefs))

        HomeFeedbackStripPrefs.markStripDismissed(context)
        assertFalse(HomeFeedbackStripPrefs.shouldShow(tipsHintsDismissed = true, prefs = prefs))
    }
}
