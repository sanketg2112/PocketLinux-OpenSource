package com.sg.linuxgo.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import com.sg.linuxgo.ContainerConfig
import com.sg.linuxgo.CrashReportCoordinator
import com.sg.linuxgo.MainViewModel
import com.sg.linuxgo.ui.components.AppConfirmDialog
import com.sg.linuxgo.ui.components.CrashRecoveryDialog
import com.sg.linuxgo.ui.components.InstallCompleteDialog
import com.sg.linuxgo.ui.onboarding.DistroPackageTips

/**
 * Overlay dialogs owned by [MainScreen] — kept out of MainScreen.kt for the line-limit rule.
 */
@Composable
fun MainScreenOverlayDialogs(
    context: Context,
    viewModel: MainViewModel,
    containerConfigs: List<ContainerConfig>,
    isDarkTheme: Boolean,
    onLaunchGui: (ContainerConfig) -> Unit,
    onLaunchShell: (ContainerConfig) -> Unit,
    onViewLogs: (returnToContainerId: String?) -> Unit,
) {
    val confirm = viewModel.appConfirmRequest
    if (confirm != null) {
        AppConfirmDialog(request = confirm, isDarkTheme = isDarkTheme)
    }

    val installFail = viewModel.installFailedMessage
    if (installFail != null) {
        AppConfirmDialog(
            request = com.sg.linuxgo.ui.components.AppConfirmRequest(
                title = "Installation failed",
                message = installFail,
                confirmLabel = "View logs",
                dismissLabel = "OK",
                destructive = false,
                onConfirm = {
                    viewModel.installFailedMessage = null
                    onViewLogs(null)
                },
                onDismiss = { viewModel.installFailedMessage = null },
            ),
            isDarkTheme = isDarkTheme,
        )
    }

    val completeId = viewModel.installCompleteContainerId
    if (completeId != null) {
        // Resolve by id at composition and again at click time so we never launch a
        // stale/wrong card if the list refreshed after the dialog opened.
        val finished = containerConfigs.find { it.id == completeId }
            ?: viewModel.containerConfigs.find { it.id == completeId }
        val name = finished?.name ?: "Environment"
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val tipsCallback: (() -> Unit)? = if (finished != null &&
            !prefs.getBoolean(DistroPackageTips.tipsSeenKey(finished.distro), false)
        ) {
            {
                // One-shot: Tips must not race with Launch desktop (double-tap /
                // card under dialog / pending low-RAM continue).
                val id = viewModel.consumeInstallCompleteContainerId()
                if (id != null) {
                    val tipTarget = viewModel.containerConfigs.find { it.id == id } ?: finished
                    viewModel.distroTipsTerminalContainerId = tipTarget.id
                    viewModel.showDistroTipsForDistro = tipTarget.distro
                }
            }
        } else {
            null
        }
        InstallCompleteDialog(
            environmentName = name,
            onLaunchDesktop = {
                val id = viewModel.consumeInstallCompleteContainerId() ?: return@InstallCompleteDialog
                val target = viewModel.containerConfigs.find { it.id == id }
                    ?: finished?.takeIf { it.id == id }
                if (target != null) onLaunchGui(target)
            },
            onOpenTerminal = {
                val id = viewModel.consumeInstallCompleteContainerId() ?: return@InstallCompleteDialog
                val target = viewModel.containerConfigs.find { it.id == id }
                    ?: finished?.takeIf { it.id == id }
                if (target != null) onLaunchShell(target)
            },
            onShowTips = tipsCallback,
            onDismiss = { viewModel.consumeInstallCompleteContainerId() },
        )
    }

    if (viewModel.showCrashReportPrompt) {
        val pending = CrashReportCoordinator.peekPending(context)
        if (pending == null) {
            viewModel.showCrashReportPrompt = false
        } else {
            CrashReportCoordinator.markPromptVisible(true)
            CrashRecoveryDialog(
                pending = pending,
                onSendReport = { note ->
                    CrashReportCoordinator.submit(context, pending, note)
                    CrashReportCoordinator.markPromptVisible(false)
                    viewModel.showCrashReportPrompt = false
                    Toast.makeText(context, "Thanks — crash report sent", Toast.LENGTH_SHORT).show()
                },
                onDismiss = {
                    CrashReportCoordinator.clearPending(context)
                    CrashReportCoordinator.markPromptVisible(false)
                    viewModel.showCrashReportPrompt = false
                },
                onNeverAsk = {
                    CrashReportCoordinator.setNeverPrompt(context, true)
                    CrashReportCoordinator.clearPending(context)
                    CrashReportCoordinator.markPromptVisible(false)
                    viewModel.showCrashReportPrompt = false
                },
                onViewLogs = {
                    CrashReportCoordinator.clearPending(context)
                    CrashReportCoordinator.markPromptVisible(false)
                    viewModel.showCrashReportPrompt = false
                    onViewLogs(null)
                },
            )
        }
    }
}
