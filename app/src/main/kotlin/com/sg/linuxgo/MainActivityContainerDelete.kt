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

/** Delete/terminate/abort — Compose confirms via [MainViewModel.appConfirmRequest]. */

/**
 * Actually remove a container and refresh home UI.
 * Call only after the user has already confirmed (card dialog or settings type-to-delete).
 * Must not be invoked twice for the same id concurrently without [ContainerManager.safeDeleteRecursively].
 *
 * Always lands on [Screen.HOME] with remaining (or empty) cards. Do not flip
 * [setupView] visible — that Observable bridge sets [Screen.SETUP] (logs UI).
 */
internal fun MainActivity.performContainerDelete(container: ContainerConfig) {
    val deletedId = container.id
    if (deletedId == activeContainerId) terminateSession()
    containerManager.removeContainer(deletedId)
    removeStatsFromCache(deletedId)

    // Drop any UI that still pointed at the removed environment.
    if (logsReturnToContainerId == deletedId) {
        logsReturnToContainerId = null
    }
    if (viewModel.showContainerSettingsForId == deletedId) {
        viewModel.showContainerSettingsForId = null
    }
    if (installingContainerIdState == deletedId) {
        installingContainerIdState = null
        installProgressMessageState = ""
        installProgressPercentState = 0
    }
    if (viewModel.installCompleteContainerId == deletedId) {
        viewModel.installCompleteContainerId = null
    }

    val remaining = containerManager.getContainers()
    if (isLateInit_containerAdapter()) {
        containerAdapter.updateContainers(remaining)
    } else {
        updateContainerConfigsState(remaining)
    }
    try {
        btnAddContainer.visibility = if (containerManager.canAddMore()) View.VISIBLE else View.GONE
    } catch (_: Throwable) {
        // Legacy dummy views may not be ready; Compose list refresh is enough.
    }
    Toast.makeText(this, "Environment deleted", Toast.LENGTH_SHORT).show()
    if (remaining.isEmpty()) {
        // Home empty state (add-container CTA) owns this — never open Setup/logs.
        isSetupComplete = false
    }
    // Always return to Home so remaining/empty cards show (not logs/setup).
    switchToHomeTab()
}

internal fun MainActivity.handleOnDelete(container: ContainerConfig) {
    viewModel.requestConfirm(
        com.sg.linuxgo.ui.components.AppConfirmRequest(
            title = "Delete environment?",
            message = "This permanently deletes “${container.name}” and all its data. This cannot be undone.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            destructive = true,
            onConfirm = {
                viewModel.clearConfirm()
                performContainerDelete(container)
            },
            onDismiss = { viewModel.clearConfirm() },
        )
    )
}

/** Settings sheet already required typing “delete” — skip a second dialog. */
internal fun MainActivity.handleOnDeleteConfirmed(container: ContainerConfig) {
    performContainerDelete(container)
}

internal fun MainActivity.handleOnTerminate(container: ContainerConfig) {
    if (container.id != activeContainerId) return
    viewModel.requestConfirm(
        com.sg.linuxgo.ui.components.AppConfirmRequest(
            title = "Stop session?",
            message = "Ends the running desktop/terminal for “${container.name}”. You can launch again anytime.",
            confirmLabel = "Stop session",
            dismissLabel = "Keep running",
            destructive = true,
            onConfirm = {
                viewModel.clearConfirm()
                terminateSession()
                // Eligibility for home feedback strip (replaces tips after tips are gone).
                com.sg.linuxgo.util.HomeFeedbackStripPrefs.markFirstSessionStopped(this)
                viewModel.homeFeedbackStripEpoch++
            },
            onDismiss = { viewModel.clearConfirm() },
        )
    )
}

internal fun MainActivity.handleOnAbortInstall(container: ContainerConfig) {
    viewModel.requestConfirm(
        com.sg.linuxgo.ui.components.AppConfirmRequest(
            title = "Abort installation?",
            message = "Stops the install and cleans up temporary files for “${container.name}”. You can start again later.",
            confirmLabel = "Abort install",
            dismissLabel = "Keep installing",
            destructive = true,
            onConfirm = {
                viewModel.clearConfirm()
                abortInstallation()
            },
            onDismiss = { viewModel.clearConfirm() },
        )
    )
}

