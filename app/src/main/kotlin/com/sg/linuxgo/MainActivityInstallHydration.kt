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

/** Hydrate install/backup */

internal fun MainActivity.applyBackupRestoreBanner(banner: BackupRestoreBanner) {
    backupRestoreRunningState = banner.visible
    backupRestoreProgressState = banner.progress
    backupRestoreMessageState = banner.message
}

internal fun MainActivity.hydrateBackupRestoreStateFromService() {
    applyBackupRestoreBanner(
        BackupRestoreBanner.hydrate(
            serviceRunning = BackupRestoreService.isRunning,
            lastProgress = BackupRestoreService.lastProgress,
            lastMessage = BackupRestoreService.lastMessage
        )
    )
    if (BackupRestoreService.isRunning) return

    val complete = BackupRestoreService.lastCompleteSuccess
    if (complete != null) {
        val op = BackupRestoreService.lastCompleteOperation ?: "backup"
        val msg = BackupRestoreService.lastCompleteMessage
        val restoredId = BackupRestoreService.lastCompleteContainerId
        if (msg.isNotBlank()) logToMini(if (complete) "✓ $msg" else "! $msg")
        if (op == "restore" && complete) {
            containerAdapter.updateContainers(containerManager.getContainers())
            onSetupComplete(restoredId)
        }
        // Consume so we don't re-apply on every resume.
        BackupRestoreService.lastCompleteSuccess = null
        BackupRestoreService.lastCompleteContainerId = null
    }
}

/**
 * Apply install completion if SUCCESS was missed while paused (LocalBroadcast
 * unregistered in onPause). Mirrors [hydrateBackupRestoreStateFromService].
 * @return true if a completion/error snapshot was consumed
 */
internal fun MainActivity.hydrateInstallCompletionFromService(): Boolean {
    val complete = SetupForegroundService.lastCompleteSuccess ?: return false
    val completedId = SetupForegroundService.lastCompleteContainerId
    val msg = SetupForegroundService.lastCompleteMessage
    // Consume immediately so a second resume does not re-fire tips/toasts.
    SetupForegroundService.clearLastComplete()

    if (complete) {
        if (msg.isNotBlank()) logToMini("✓ $msg")
        // Prefer snapshot id; keep installing id until onSetupComplete resolves the dialog target.
        val resolvedId = completedId
            ?: installingContainerIdState
            ?: containerAdapter.getInstallingContainerId()
        if (resolvedId != null) {
            containerManager.markInstalled(resolvedId)
            seedContainerStorageStats(resolvedId)
            startStatsPoller()
            statsPoller.requestImmediateStorageRefresh(resolvedId)
        }
        containerManager.reconcileInstalledFlags()
        containerAdapter.updateContainers(containerManager.getContainers())
        onSetupComplete(resolvedId)
        containerAdapter.setInstallingContainer(null)
        installingContainerIdState = null
        return true
    }

    // Failed while backgrounded — clear installing UI; error dialog only if we
    // still have a message (user may already have dismissed from a prior resume).
    if (msg.isNotBlank()) logToMini("! $msg")
    containerAdapter.setInstallingContainer(null)
    installingContainerIdState = null
    containerAdapter.updateContainers(containerManager.getContainers())
    return true
}

/**
 * Rebuild install log + progress UI from the foreground service buffer.
 * Needed when the app was closed/backgrounded and LocalBroadcast lines were missed.
 */
internal fun MainActivity.hydrateInstallStateFromService() {
    if (!SetupForegroundService.isRunningStatic) return

    val cid = SetupForegroundService.activeInstallingContainerId
        ?: containerManager.getContainers().find { !it.isInstalled }?.id
    if (cid != null) {
        setActiveContainerId(cid)
        // Avoid resetting progress to "Preparing..." on every resume
        if (containerAdapter.getInstallingContainerId() != cid) {
            containerAdapter.setInstallingContainer(cid)
        }
    }

    val msg = SetupForegroundService.lastMessage.ifBlank { "Setup in progress…" }
    installCardProgress.hydrateFromService(SetupForegroundService.lastProgressPercent, msg)
    // Re-apply size only for real file downloads (not install-phase percent totals).
    val cur = SetupForegroundService.lastDownloadCurrent
    val tot = SetupForegroundService.lastDownloadTotal
    if (InstallCardProgress.showsDownloadSize("Downloading", tot, cur) && (tot > 0 || cur > 0)) {
        installCardProgress.downloadCurrentBytes = cur
        installCardProgress.downloadTotalBytes = tot
        // Rebuild status from bytes so size is always MB and % is never in the status line.
        val sizePart = InstallCardProgress.formatDownloadSize(cur, tot)
        if (sizePart.isNotEmpty()) {
            val label = installCardProgress.downloading
                .ifBlank { msg }
                .let { InstallCardProgress.stripInlinePercent(it) }
                .substringBefore(':')
                .trim()
                .ifBlank { "Downloading" }
            installCardProgress.downloading = "$label: $sizePart"
        }
    } else {
        installCardProgress.downloadCurrentBytes = -1L
        installCardProgress.downloadTotalBytes = -1L
    }
    updateCardProgress()
    containerAdapter.updateInstallProgress(
        installProgressMessageState.ifBlank { msg },
        if (installCardProgress.percent in 0..100) installCardProgress.percent else -1
    )

    val snapshot = SetupForegroundService.getLogSnapshot()
    if (snapshot.isNotBlank() && isLateInit_tvMiniLog()) {
        // Replace (do not append) so we don't double-count lines already shown.
        tvMiniLog.text = snapshot
        setupLogText = snapshot
        if (isLateInit_miniLogScroll()) {
            miniLogScroll.post { miniLogScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
