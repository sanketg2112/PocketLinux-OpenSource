package com.sg.linuxgo

import android.app.Activity
import android.view.WindowManager
import androidx.preference.PreferenceManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KeepScreenOnRobolectricTest {

    @Test
    fun defaultKeepsScreenActive() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().clear().commit()

        activity.applyKeepScreenOnPreference()

        val isScreenOn = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        assertTrue("Expected FLAG_KEEP_SCREEN_ON to be set by default", isScreenOn)
    }

    @Test
    fun explicitTrueKeepsScreenActive() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().putBoolean("keepScreenOn", true).commit()

        activity.applyKeepScreenOnPreference()

        val isScreenOn = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        assertTrue("Expected FLAG_KEEP_SCREEN_ON to be set when keepScreenOn=true", isScreenOn)
    }

    @Test
    fun explicitFalseAllowsScreenSleep() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().putBoolean("keepScreenOn", false).commit()

        activity.applyKeepScreenOnPreference()

        val isScreenOn = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        assertFalse("Expected FLAG_KEEP_SCREEN_ON to be cleared when keepScreenOn=false", isScreenOn)
    }

    @Test
    fun dynamicPreferenceToggleUpdatesWindowFlag() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

        // Default: true
        prefs.edit().clear().commit()
        activity.applyKeepScreenOnPreference()
        assertTrue((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)

        // Turn OFF
        prefs.edit().putBoolean("keepScreenOn", false).commit()
        activity.applyKeepScreenOnPreference()
        assertFalse((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)

        // Turn back ON
        prefs.edit().putBoolean("keepScreenOn", true).commit()
        activity.applyKeepScreenOnPreference()
        assertTrue((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
    }
}
