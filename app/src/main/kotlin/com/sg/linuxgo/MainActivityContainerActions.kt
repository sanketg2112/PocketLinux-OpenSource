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

/** Container install/settings */

internal fun MainActivity.handleOnInstall(container: ContainerConfig) {
    setActiveContainerId(container.id)
    lastPhaseMessage = "" // Clear previous phase info for new install
    // Apply user selections from the container config to bootstrap
    bootstrap.applyContainerPreset(container)
    welcomeContainer.visibility = View.GONE
    // Show installing progress on the card
    containerAdapter.setInstallingContainer(container.id)
    startFullBootstrap()
}

internal fun MainActivity.handleOnSettings(container: ContainerConfig) {
    showContainerSettingsForId = container.id
}

