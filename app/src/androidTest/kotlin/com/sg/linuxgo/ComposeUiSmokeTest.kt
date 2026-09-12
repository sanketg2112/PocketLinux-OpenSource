package com.sg.linuxgo

import android.preference.PreferenceManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sg.linuxgo.ui.screens.HomeScreen
import com.sg.linuxgo.ui.sheets.GlobalSettingsSheet
import com.sg.linuxgo.ui.sheets.NewContainerSheet
import com.sg.linuxgo.ui.sheets.PREF_LEGACY_PACKAGE_INSTALL
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI smoke for isolated screens (no full install / proot).
 */
@RunWith(AndroidJUnit4::class)
class ComposeUiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun prefs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Avoid network catalog fetch in NewContainerSheet
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_LEGACY_PACKAGE_INSTALL, true)
            .commit()
    }

    @Test
    fun homeEmptyStateShowsCreateContainer() {
        var addClicks = 0
        composeRule.setContent {
            MaterialTheme {
                HomeScreen(
                    onAddContainer = { addClicks++ },
                    containerCards = {},
                    hasContainers = false
                )
            }
        }
        composeRule.onNodeWithText("Create environment").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assert(addClicks == 1)
    }

    @Test
    fun globalSettingsOutputSectionShowsTitleAndBack() {
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                GlobalSettingsSheet(
                    onDismiss = { dismissed = true },
                    onSettingsSaved = {},
                    isDarkTheme = true,
                    section = "output"
                )
            }
        }
        composeRule.onNodeWithText("Output settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assert(dismissed)
    }

    @Test
    fun globalSettingsKeyboardSectionShowsTitle() {
        composeRule.setContent {
            MaterialTheme {
                GlobalSettingsSheet(
                    onDismiss = {},
                    onSettingsSaved = {},
                    isDarkTheme = true,
                    section = "keyboard"
                )
            }
        }
        composeRule.onNodeWithText("Keyboard settings").assertIsDisplayed()
    }

    @Test
    fun newContainerSheetShowsHeader() {
        composeRule.setContent {
            MaterialTheme {
                NewContainerSheet(
                    onContainerCreated = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New environment").assertIsDisplayed()
    }

    @Test
    fun experimentalSettingsShowsTawcThanks() {
        composeRule.setContent {
            MaterialTheme {
                com.sg.linuxgo.ui.screens.ExperimentalSettingsScreen(
                    isDarkTheme = true,
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithText("Experimental settings").assertIsDisplayed()
        composeRule.onNodeWithText("THANKS").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("tawc (Wayland)").assertIsDisplayed()
        composeRule.onNodeWithText("tawc / tawcroot (Systrap Runtime)").assertIsDisplayed()
    }
}
