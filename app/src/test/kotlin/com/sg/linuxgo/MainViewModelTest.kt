package com.sg.linuxgo

import android.app.Application
import org.junit.Assert.*
import org.junit.Test

class MainViewModelTest {

    @Test
    fun testMainViewModelInitialization() {
        val dummyApplication = Application()
        val viewModel = MainViewModel(dummyApplication)

        // Verify default home screen
        assertEquals(Screen.HOME, viewModel.currentScreen)

        // Onboarding is decided in init from prefs (dummy Application → not completed → true)
        // Production Application with onboarding_completed=true leaves this false.
        // Do not require a specific value on a bare Application() double.

        // Verify setup states default to false/empty
        assertFalse(viewModel.setupWelcomeVisible)
        assertTrue(viewModel.setupChecklistItems.isEmpty())

        // Verify terminal tab state defaults
        assertEquals(0, viewModel.terminalActiveTabIndex)
        assertTrue(viewModel.terminalSessionsState.isEmpty())

        // Verify keyboard controls are initially inactive
        assertFalse(viewModel.isCtrlActive)
        assertFalse(viewModel.isAltActive)
    }

    @Test
    fun requestExperimentalSettingsOpensForEveryone() {
        val viewModel = MainViewModel(Application())
        viewModel.requestExperimentalSettings()
        assertTrue(viewModel.showExperimentalSettingsState)
    }
}
