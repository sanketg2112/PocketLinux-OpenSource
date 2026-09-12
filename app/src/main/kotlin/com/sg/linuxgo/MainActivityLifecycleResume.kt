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

/** onResume */

internal fun MainActivity.handleOnResume() {
    callSuperOnResume()
    applyKeepScreenOnPreference()

    usageCheckHandler.removeCallbacks(usageCheckRunnable)
    usageCheckHandler.postDelayed(usageCheckRunnable, 5000)

    // Register the receiver while the Activity is visible
    val filter = IntentFilter().apply {
        addAction(SetupForegroundService.BROADCAST_PROGRESS)
        addAction(SetupForegroundService.BROADCAST_DOWNLOAD_PROGRESS)
        addAction(SetupForegroundService.BROADCAST_CHOICE_REQUIRED)
        addAction(SetupForegroundService.BROADCAST_INPUT_REQUIRED)
        addAction(SetupForegroundService.BROADCAST_SUCCESS)
        addAction(SetupForegroundService.BROADCAST_ERROR)
        addAction(SetupForegroundService.BROADCAST_LOG_LINE)
        addAction(BackupRestoreService.BROADCAST_START)
        addAction(BackupRestoreService.BROADCAST_PROGRESS)
        addAction(BackupRestoreService.BROADCAST_COMPLETE)
    }
    LocalBroadcastManager.getInstance(this).registerReceiver(setupReceiver, filter)
    // Tell the service the app is visible — suppress input-required notifications
    SetupForegroundService.isActivityVisible = true

    // After DeX disconnect / process recreate, rebind active container + Resume card.
    // No-op while user Stop is in progress (avoids resurrecting a just-stopped session).
    try {
        rehydrateActiveSessionState()
        updateAdapterActiveState()
    } catch (e: Exception) {
        Log.w("MainActivity", "Session rehydrate on resume: ${e.message}")
    }

    // Reconnect X11 surface after background / DeX display move (surface was destroyed).
    try {
        if (!isUserSessionStopInProgress() &&
            (guiSessionManager.isX11SessionAlive() ||
                guiSessionManager.isX11Started ||
                isDisplayServerRunning() ||
                (activeContainerId != null && isProcessRunning(":x11")))
        ) {
            guiSessionManager.markSessionLive()
            if (isLateInit_lorieView() && !com.sg.linuxgo.x11.LorieView.connected()) {
                tryX11Connect()
            }
            // Refresh surface when returning from another app or phone after DeX.
            if (isLateInit_lorieView() && isLateInit_guiContainer() &&
                guiContainer.visibility == View.VISIBLE
            ) {
                lorieView.triggerCallback()
            }
        }
    } catch (e: Exception) {
        Log.w("MainActivity", "X11 reconnect on resume: ${e.message}")
    }

    // Hydrate backup/restore UI if progress broadcasts were missed while paused.
    hydrateBackupRestoreStateFromService()

    // Synchronize installing container state + missed logs on resume.
    // Prefer the completion snapshot (SUCCESS/ERROR missed while paused) so the
    // card never stays stuck on a frozen progress % after install finished.
    if (hydrateInstallCompletionFromService()) {
        // Completion applied; nothing else to do for install UI.
    } else if (SetupForegroundService.isRunningStatic) {
        hydrateInstallStateFromService()
        // Arch hang recovery: guest install finished (desktop present) but service
        // never got onSuccess because proot never exited. Promote the card to READY
        // so the user is not stuck on INSTALLING forever after kill/reopen.
        if (containerManager.reconcileInstalledFlags()) {
            containerAdapter.updateContainers(containerManager.getContainers())
            // Keep progress UI if the service is genuinely still installing;
            // only clear when every installing container is now marked installed.
            val stillInstalling = SetupForegroundService.activeInstallingContainerId
                ?.let { id -> containerManager.getContainer(id)?.isInstalled != true } == true
            if (!stillInstalling) {
                containerAdapter.setInstallingContainer(null)
                installingContainerIdState = null
            }
        }
    } else {
        // Service stopped. Reconcile disk → READY, and always clear installing UI
        // if the card still thinks a run is in progress (markInstalled may already
        // have flipped isInstalled so reconcile returns false).
        val reconciled = containerManager.reconcileInstalledFlags()
        val installingId = if (isLateInit_containerAdapter()) {
            containerAdapter.getInstallingContainerId() ?: installingContainerIdState
        } else {
            installingContainerIdState
        }
        val installingDone = installingId != null &&
            containerManager.getContainer(installingId)?.isInstalled == true
        if (reconciled || installingDone) {
            if (isLateInit_containerAdapter()) {
                containerAdapter.updateContainers(containerManager.getContainers())
                containerAdapter.setInstallingContainer(null)
            }
            installingContainerIdState = null
            if (installingDone) {
                // Full home transition if SUCCESS was missed and snapshot was lost
                // (e.g. process partially torn down) but prefs already say installed.
                onSetupComplete(installingId)
            }
        } else if (isLateInit_containerAdapter()) {
            // Still refresh list so isInstalled from prefs is reflected in Compose
            containerAdapter.updateContainers(containerManager.getContainers())
            // Service gone and container not installed — drop stale installing UI
            // so the user is not stuck on a frozen % after a killed install.
            if (installingId != null) {
                containerAdapter.setInstallingContainer(null)
                installingContainerIdState = null
            }
        }
    }

    // ── Hydrate UI from the service's static pending-state ──────────────────
    // The service may have already sent the first onChoiceRequired broadcast
    // BEFORE this receiver was registered (race condition on first launch).
    // Reading the @Volatile static fields guarantees we always show the right prompt.
    val svcStep    = SetupForegroundService.pendingStep
    val svcChoices = SetupForegroundService.pendingChoices
    val svcTitle   = SetupForegroundService.pendingInputTitle
    val svcHint    = SetupForegroundService.pendingInputHint

    if (svcStep != null) {
        val step = try { Bootstrap.SetupStep.valueOf(svcStep) }
                   catch (_: Exception) { null }
        if (step != null) {
            if (svcChoices != null) {
                val choices = svcChoices.map { raw ->
                    val parts = raw.split("|", limit = 2)
                    Bootstrap.Choice(parts[0], parts.getOrElse(1) { parts[0] })
                }
                pendingStep = step; pendingChoices = choices
                showChoicePrompt(step, choices)
            } else if (svcHint != null) {
                pendingStep = step; pendingInputHint = svcHint
                showInputPrompt(step, svcTitle ?: getStepTitle(step), svcHint)
            }
        }
    } else {
        // Fall back to locally-cached pending state (e.g. after screen rotation)
        val step    = pendingStep
        val choices = pendingChoices
        val hint    = pendingInputHint
        if (step != null) {
            if (choices != null) showChoicePrompt(step, choices)
            else if (hint != null) showInputPrompt(step, getStepTitle(step), hint)
        }
    }

    // After app_crash (or a previous distro crash the user dismissed), ask once to submit logs.
    window.decorView.postDelayed({
        if (!isFinishing) CrashReportCoordinator.maybeShowPrompt(this)
    }, 900L)
}
