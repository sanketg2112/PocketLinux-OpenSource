package com.sg.linuxgo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.View

/**
 * Handles LocalBroadcast events from [SetupForegroundService] and [BackupRestoreService].
 */
class SetupBroadcastHandler(
    private val activity: MainActivity
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            BackupRestoreService.BROADCAST_START -> {
                activity.isTaskInProgress = true
                activity.runOnUiThread {
                    activity.welcomeContainer.visibility = View.GONE
                    activity.applyBackupRestoreBanner(
                        BackupRestoreBanner.running(0, "Starting operation...")
                    )
                }
            }
            BackupRestoreService.BROADCAST_PROGRESS -> {
                val msg = intent.getStringExtra(BackupRestoreService.EXTRA_MESSAGE) ?: ""
                activity.runOnUiThread {
                    // Late extract-monitor ticks after complete must not reopen the bar.
                    if (!BackupRestoreBanner.acceptProgress(BackupRestoreService.isRunning)) return@runOnUiThread
                    if (msg.isNotBlank()) {
                        val parts = msg.split("|")
                        val cleanMsg = parts.getOrNull(0) ?: msg
                        val progressVal = parts.getOrNull(1)?.toIntOrNull() ?: 0

                        activity.logToMini(cleanMsg)
                        activity.applyBackupRestoreBanner(
                            BackupRestoreBanner.running(progressVal, cleanMsg)
                        )
                    }
                }
            }
            BackupRestoreService.BROADCAST_COMPLETE -> {
                val op = intent.getStringExtra(BackupRestoreService.EXTRA_OPERATION) ?: "backup"
                val success = intent.getBooleanExtra(BackupRestoreService.EXTRA_SUCCESS, false)
                val msg = intent.getStringExtra(BackupRestoreService.EXTRA_MESSAGE) ?: ""
                val restoredId = intent.getStringExtra(BackupRestoreService.EXTRA_CONTAINER_ID)
                    ?: BackupRestoreService.lastCompleteContainerId
                activity.isTaskInProgress = false
                // Consume static completion so onResume hydration does not re-fire.
                BackupRestoreService.lastCompleteSuccess = null
                BackupRestoreService.lastCompleteContainerId = null
                activity.runOnUiThread {
                    if (msg.isNotBlank()) {
                        activity.logToMini(if (success) "✓ $msg" else "! $msg")
                    }
                    if (op == "restore" && success) {
                        activity.containerAdapter.updateContainers(activity.containerManager.getContainers())
                        // Pass restored container id so Install Complete launches that card,
                        // not whichever container was previously active / first in the list.
                        activity.onSetupComplete(restoredId)
                    }
                    // Hide immediately — a leftover 100% chip is what users report
                    // after restore. Completion lives on the Install Complete dialog.
                    activity.applyBackupRestoreBanner(BackupRestoreBanner.Hidden)
                }
            }
            SetupForegroundService.BROADCAST_PROGRESS -> {
                // Ignore stale phase lines after abort (or from a dying service).
                if (!SetupForegroundService.isRunningStatic) return
                val installingId = activity.containerAdapter.getInstallingContainerId()
                if (installingId == null) return

                val msg = intent.getStringExtra(SetupForegroundService.EXTRA_MESSAGE) ?: return
                activity.lastPhaseMessage = msg
                activity.tvStepDescription.text = msg
                activity.updateChecklist(msg)

                val cleanMsg = if (msg.startsWith("[") && msg.contains("] ")) {
                    msg.substringAfter("] ")
                } else {
                    msg
                }
                if (SetupChecklistSupport.isLogWorthyProgressMessage(cleanMsg)) {
                    activity.logToMini(cleanMsg)
                }

                if (SetupChecklistSupport.isHighLevelProgressMessage(cleanMsg)) {
                    activity.installCardProgress.applyHighLevelMessage(cleanMsg)
                    activity.updateCardProgress()
                }
            }
            SetupForegroundService.BROADCAST_DOWNLOAD_PROGRESS -> {
                // Ignore late ticks from an aborted install so labels do not flicker
                // between old download sizes and the new run.
                if (!SetupForegroundService.isRunningStatic) return
                val installingId = activity.containerAdapter.getInstallingContainerId()
                if (installingId == null) return
                val eventCid = intent.getStringExtra(SetupForegroundService.EXTRA_CONTAINER_ID)
                if (eventCid != null && eventCid != installingId) return

                val fileName = intent.getStringExtra(SetupForegroundService.EXTRA_FILE_NAME) ?: ""
                val progress = intent.getIntExtra(SetupForegroundService.EXTRA_PROGRESS, 0)
                val current = intent.getLongExtra(SetupForegroundService.EXTRA_CURRENT, -1L)
                val total = intent.getLongExtra(SetupForegroundService.EXTRA_TOTAL, -1L)

                activity.progressBar.isIndeterminate = false
                activity.progressBar.progress = progress
                val sizePart = if (InstallCardProgress.showsDownloadSize(fileName, total, current)) {
                    InstallCardProgress.formatDownloadSize(current, total)
                } else {
                    ""
                }
                val statusText = if (sizePart.isNotEmpty()) {
                    "%s: %d%% · %s".format(fileName, progress, sizePart)
                } else {
                    "%s: %d%%".format(fileName, progress)
                }
                activity.tvProgressStatus.text = statusText
                activity.installCardProgress.applyDownloadProgress(fileName, progress, current, total)
                activity.updateCardProgress()
            }
            SetupForegroundService.BROADCAST_LOG_LINE -> {
                val line = intent.getStringExtra(SetupForegroundService.EXTRA_MESSAGE) ?: return
                activity.runOnUiThread {
                    activity.appendLogLineToMini(line)
                }
            }
            SetupForegroundService.BROADCAST_CHOICE_REQUIRED -> {
                val stepName = intent.getStringExtra(SetupForegroundService.EXTRA_STEP) ?: return
                val step = Bootstrap.SetupStep.valueOf(stepName)
                val rawChoices = intent.getStringArrayListExtra(SetupForegroundService.EXTRA_CHOICES) ?: return
                val choices = rawChoices.map { raw ->
                    val parts = raw.split("|", limit = 2)
                    Bootstrap.Choice(parts[0], parts.getOrElse(1) { parts[0] })
                }
                activity.pendingStep = step
                activity.pendingChoices = choices
                activity.showChoicePrompt(step, choices)
            }
            SetupForegroundService.BROADCAST_INPUT_REQUIRED -> {
                val stepName = intent.getStringExtra(SetupForegroundService.EXTRA_STEP) ?: return
                val step = Bootstrap.SetupStep.valueOf(stepName)
                val title = intent.getStringExtra(SetupForegroundService.EXTRA_INPUT_TITLE) ?: ""
                val hint = intent.getStringExtra(SetupForegroundService.EXTRA_INPUT_HINT) ?: ""
                activity.pendingStep = step
                activity.pendingInputHint = hint
                activity.showInputPrompt(step, title, hint)
            }
            SetupForegroundService.BROADCAST_SUCCESS -> {
                // Prefer intent id; fall back to installing state before we clear it.
                val completedId = intent.getStringExtra(SetupForegroundService.EXTRA_CONTAINER_ID)
                    ?: SetupForegroundService.lastCompleteContainerId
                    ?: activity.installingContainerIdState
                    ?: activity.containerAdapter.getInstallingContainerId()
                // Consume static completion so onResume hydration does not re-fire.
                SetupForegroundService.clearLastComplete()
                activity.runOnUiThread {
                    activity.updateChecklist("installation complete")
                    // Mark installed before clearing installing state so the card
                    // never flashes PENDING without Launch GUI / Terminal.
                    if (completedId != null) {
                        activity.containerManager.markInstalled(completedId)
                        activity.seedContainerStorageStats(completedId)
                        activity.startStatsPoller()
                        activity.statsPoller.requestImmediateStorageRefresh(completedId)
                    }
                    activity.containerManager.reconcileInstalledFlags()
                    activity.containerAdapter.updateContainers(activity.containerManager.getContainers())
                    // Resolve complete dialog target before wiping installing id.
                    activity.onSetupComplete(completedId)
                    activity.containerAdapter.setInstallingContainer(null)
                    activity.installingContainerIdState = null
                }
                activity.logToMini("✓ Installation complete. System ready.")
            }
            SetupForegroundService.BROADCAST_ERROR -> {
                val error = intent.getStringExtra(SetupForegroundService.EXTRA_ERROR) ?: "Unknown error"
                val deConvert = false
                // Consume static completion so onResume hydration does not re-fire.
                SetupForegroundService.clearLastComplete()
                activity.runOnUiThread {
                    activity.containerAdapter.setInstallingContainer(null)
                    activity.installingContainerIdState = null
                    activity.isTaskInProgress = false
                    activity.containerAdapter.updateContainers(activity.containerManager.getContainers())
                    if (deConvert) {
                        // Keep container; log + toast (do not treat as wiped install failure).
                        activity.logToMini("! $error")
                        activity.switchToHomeTab()
                        android.widget.Toast.makeText(
                            activity,
                            error.take(120),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        activity.switchToHomeTab()
                        activity.viewModel.installFailedMessage = error
                    }
                }
            }
        }
    }
}
