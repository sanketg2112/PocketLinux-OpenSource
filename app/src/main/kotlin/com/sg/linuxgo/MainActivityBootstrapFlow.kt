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

/** Bootstrap */

internal fun MainActivity.startFullBootstrap() {
    isTaskInProgress = true
    btnPrimaryAction.visibility = View.GONE
    welcomeContainer.visibility = View.GONE
    
    // Hide legacy setupView elements since we are showing the Home screen
    progressBar.visibility = View.GONE
    tvProgressStatus.visibility = View.GONE
    tvStepDescription.visibility = View.GONE
    checklistContainer.visibility = View.GONE
    pendingStep = null
    pendingChoices = null
    pendingInputHint = null

    metricsCard.visibility = View.GONE
    btnRestoreSetup.visibility = View.GONE
    btnToggleLogs.visibility = View.VISIBLE
    logCardView.visibility = View.GONE // Hidden by default, toggled by btnToggleLogs
    
    logToMini("Starting Advanced UI Setup Wizard (background-capable)...")

    // Request POST_NOTIFICATIONS permission on Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9001)
        }
    }

    // Delegate the entire install to the foreground service so it can
    // continue even when the user navigates away / closes the app.
    // Pass the active container's config so the service applies the preset
    val configJson = activeContainerId?.let { id ->
        containerManager.getContainer(id)?.toJson()?.toString()
    }
    SetupForegroundService.start(this, configJson)
    switchToHomeTab()
}

internal fun MainActivity.onSetupComplete(completedContainerId: String? = null) {
    Log.d("LinuxGoDebug", "onSetupComplete() called. isTaskInProgress=$isTaskInProgress id=$completedContainerId", Throwable())
    runOnUiThread {
        try {
            isSetupComplete = true
            isTaskInProgress = false
            updateChecklist("installation complete")
            
            // Visibility updates
            tvStepDescription.visibility = View.GONE
            tvProgressStatus.visibility = View.GONE
            btnPrimaryAction.visibility = View.GONE
            progressBar.visibility = View.GONE
            checklistContainer.visibility = View.GONE
            btnToggleLogs.visibility = View.VISIBLE
            logCardView.visibility = View.GONE
            metricsCard.visibility = View.VISIBLE
            footerAction.visibility = View.GONE
            tabLayout.visibility = View.VISIBLE

            // Resolve which container finished install/restore (prefer explicit id).
            // Do NOT fall back to getContainers().firstOrNull() — that launched the
            // first card when the newly installed/restored id was missing.
            val installingHint = installingContainerIdState
                ?: (if (isLateInit_containerAdapter()) containerAdapter.getInstallingContainerId() else null)
            val cid = InstallCompleteTarget.resolve(
                completedContainerId = completedContainerId,
                installingHint = installingHint,
                activeInstallingId = SetupForegroundService.activeInstallingContainerId,
                lastCompleteInstallId = SetupForegroundService.lastCompleteContainerId,
                lastCompleteRestoreId = BackupRestoreService.lastCompleteContainerId,
                activeContainerId = activeContainerId,
            )

            if (cid != null) {
                containerManager.markInstalled(cid)
                // Always bind selection to the container that just finished so
                // Install Complete → Launch Desktop/Terminal hits the right rootfs.
                setActiveContainerId(cid)
            }
            // Disk fallback: root/launch.sh present ⇒ READY even if id resolution failed.
            containerManager.reconcileInstalledFlags()

            // Refresh container adapter list so card displays action buttons (Launch GUI, Terminal)
            containerAdapter.updateContainers(containerManager.getContainers())
            containerAdapter.setInstallingContainer(null)

            // Seed storage bar immediately + ensure poller is running (home tab used to stop it).
            if (cid != null) {
                seedContainerStorageStats(cid)
            }
            startStatsPoller()
            statsPoller.requestImmediateStorageRefresh(cid)

            // Transition to Home tab
            if (tabLayout.tabCount > 0) {
                val tab = tabLayout.getTabAt(0)
                if (tabLayout.selectedTabPosition != 0) {
                    tab?.select() 
                } else {
                    switchToHomeTab()
                }
            } else {
                switchToHomeTab()
            }

            // Celebration + next actions (tips offered from the complete dialog).
            if (cid != null) {
                viewModel.installCompleteContainerId = cid
            }
        } catch (e: Throwable) {
            Log.e("MainActivity", "onSetupComplete error", e)
        }
    }
}

internal fun MainActivity.abortInstallation() {
    runOnUiThread {
        try {
            // Stop foreground service
            SetupForegroundService.stop(this)
            isTaskInProgress = false
            
            // Hide progress UI elements
            tvStepDescription.visibility = View.GONE
            tvProgressStatus.visibility = View.GONE
            progressBar.visibility = View.GONE
            checklistContainer.visibility = View.GONE
            btnToggleLogs.visibility = View.GONE
            logCardView.visibility = View.GONE
            
            // Clear adapter installing container
            containerAdapter.setInstallingContainer(null)
            
            // Delete and clean up the active container
            val cid = activeContainerId
            if (cid != null) {
                containerManager.removeContainer(cid)
                setActiveContainerId(null)
            }
            
            // Refresh containers list
            containerAdapter.updateContainers(containerManager.getContainers())
            btnAddContainer.visibility = if (containerManager.canAddMore()) View.VISIBLE else View.GONE
            
            switchToHomeTab()
            Toast.makeText(this, "Installation aborted & container cleaned up", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "abortInstallation error", e)
        }
    }
}

/**
 * DEBUG: start DE conversion as a foreground job (survives Home / settings dismiss).
 * Marks the container as "installing" so progress + View Logs use the setup log buffer.
 */
internal fun MainActivity.handleStartDesktopDeConvert(containerId: String, targetDe: String) {
    Toast.makeText(this, "DE conversion is not supported in open-source builds", Toast.LENGTH_SHORT).show()
}

