package com.sg.linuxgo

import android.preference.PreferenceManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sg.linuxgo.ui.onboarding.DistroPackageTips
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Device/emulator smoke: app launches without crashing and shows post-onboarding home UI.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    /** Prefs must be set before the activity rule launches MainActivity. */
    private val skipOnboardingRule = object : TestWatcher() {
        override fun starting(description: Description) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(DistroPackageTips.PREF_ONBOARDING_COMPLETED, true)
                .commit()
        }
    }

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(skipOnboardingRule).around(composeRule)

    @Test
    fun mainActivityLaunchesAndShowsHomeChrome() {
        composeRule.waitForIdle()
        val hasCreate = runCatching {
            composeRule.onNodeWithText("Create environment", substring = true).assertIsDisplayed()
        }.isSuccess
        val hasAdd = runCatching {
            composeRule.onNodeWithText("Add environment", substring = true).assertIsDisplayed()
        }.isSuccess
        val hasPocket = runCatching {
            composeRule.onNodeWithText("PocketLinux", substring = true).assertExists()
        }.isSuccess
        assert(hasCreate || hasAdd || hasPocket) {
            "Expected home CTA or PocketLinux chrome after launch"
        }
    }
}
