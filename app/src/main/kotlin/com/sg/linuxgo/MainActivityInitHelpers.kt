package com.sg.linuxgo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.sg.linuxgo.x11.LorieView
import java.io.File

/** Init helpers */

internal fun MainActivity.migrateLegacyPreferencesIfNeeded() {
    val legacyPrefs = getSharedPreferences("linux_go_settings", Context.MODE_PRIVATE)
    if (legacyPrefs.all.isEmpty()) return
    val newPrefs = getSharedPreferences("pocket_linux_settings", Context.MODE_PRIVATE)
    val editor = newPrefs.edit()
    legacyPrefs.all.forEach { (key, value) ->
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
        }
    }
    editor.apply()
    legacyPrefs.edit().clear().apply()
}

internal fun MainActivity.initDummyViews() {
    val created = DummyViewFactory.create(
        context = this,
        onScreenChange = { currentScreen = it },
        onGuiLoadingVisible = { guiLoadingVisible = it },
        onGuiHomeButtonVisible = { guiHomeButtonVisible = it },
        onGuiKeyBarVisible = { guiKeyBarVisible = it },
        onGuiLoadingStatus = { guiLoadingStatusText = it },
        onSetupChecklistItems = { setupChecklistItems = it },
        getSetupChecklistItems = { setupChecklistItems },
        onSetupLogToggleText = { setupLogToggleText = it },
        onSetupRestoreVisible = { setupRestoreSetupVisible = it },
        onSetupMetricsVisible = { setupMetricsVisible = it },
        onSetupLogCardVisible = { setupLogCardVisible = it },
        onSetupInstallPath = { setupInstallPath = it },
        onSetupWelcomeVisible = { setupWelcomeVisible = it },
        onSetupStepDescText = { setupStepDescText = it },
        onSetupStepDescVisible = { setupStepDescVisible = it },
        onSetupProgressStatusText = { setupProgressStatusText = it },
        onSetupProgressStatusVisible = { setupProgressStatusVisible = it },
        onSetupLogText = { setupLogText = it },
        onSetupProgressIndeterminate = { setupProgressIndeterminate = it },
        onSetupProgress = { setupProgress = it },
        onSetupProgressVisible = { setupProgressVisible = it }
    )
    viewRegistry.clear()
    viewRegistry.putAll(created)
}
