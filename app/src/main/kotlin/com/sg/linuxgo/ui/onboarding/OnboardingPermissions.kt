package com.sg.linuxgo.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sg.linuxgo.LinuxIsolation
import com.sg.linuxgo.TelemetryManager
import com.sg.linuxgo.ui.utils.performClickHaptic
import com.sg.linuxgo.ui.utils.performWarningShakeHaptic

/**
 * Copy for the permissions step: notifications keep the session FGS alive;
 * storage is optional for mounting/sharing files.
 */
object OnboardingPermissionCopy {
    const val TITLE = "A couple of permissions"
    const val BODY =
        "Both are optional — but notifications matter a lot for a stable desktop session."

    const val NOTIF_TITLE = "Notifications"
    const val NOTIF_WHY =
        "PocketLinux shows a quiet “session running” notification so Android does not kill " +
            "your Linux desktop or terminal when you switch apps. Without this, sessions often die " +
            "in the background."
    const val NOTIF_SKIP_REMINDER =
        "Notifications help keep your Linux session alive in the background. You can enable them later in Settings."

    const val STORAGE_TITLE = "Storage access"
    const val STORAGE_WHY =
        "Optional. Needed to save backups to shared storage, and to mount phone files at /sdcard " +
            "if you turn that on. Software you install in Linux can then read photos and downloads. " +
            "Linux still works without it."
}

/**
 * @return true if POST_NOTIFICATIONS is granted or not required on this API level.
 */
fun isNotificationPermissionGranted(context: android.content.Context): Boolean {
    return TelemetryManager.notificationPermissionStatus(context) != "denied"
}

fun isStoragePermissionGranted(context: android.content.Context): Boolean {
    return TelemetryManager.storagePermissionStatus(context) == "granted"
}

/**
 * Cards explaining why notification + storage are requested during onboarding.
 */
@Composable
fun OnboardingPermissionCards(
    accentColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBg: Color,
    cardBorder: Color,
    notificationGranted: Boolean,
    storageGranted: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PermissionExplainCard(
            icon = Icons.Default.Notifications,
            title = OnboardingPermissionCopy.NOTIF_TITLE,
            body = OnboardingPermissionCopy.NOTIF_WHY,
            statusLabel = if (notificationGranted) "On" else "Recommended",
            statusOk = notificationGranted,
            accentColor = accentColor,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            cardBg = cardBg,
            cardBorder = cardBorder
        )
        PermissionExplainCard(
            icon = Icons.Default.Folder,
            title = OnboardingPermissionCopy.STORAGE_TITLE,
            body = OnboardingPermissionCopy.STORAGE_WHY,
            statusLabel = if (storageGranted) "On" else "Optional",
            statusOk = storageGranted,
            accentColor = accentColor,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            cardBg = cardBg,
            cardBorder = cardBorder
        )
    }
}

@Composable
private fun PermissionExplainCard(
    icon: ImageVector,
    title: String,
    body: String,
    statusLabel: String,
    statusOk: Boolean,
    accentColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBg: Color,
    cardBorder: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Default,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = statusLabel,
                color = if (statusOk) accentColor else textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Default
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            color = textSecondary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Default,
            lineHeight = 18.sp
        )
    }
}

/**
 * Drives optional permission requests for the onboarding permissions page.
 * Call [requestRecommendedThen] from Agree and continue. Not now declines Terms
 * and stays on onboarding — it does not call [skipWithReminder].
 *
 * @param onContinueAfterAllow called after the system dialog(s) when the user chose
 *   “Agree and continue” (typically finish onboarding + open create container).
 * @param onSkipFinished finish onboarding without creating a container
 *   (legacy skip helper; Not now no longer uses it).
 */
@Composable
fun rememberOnboardingPermissionActions(
    onContinueAfterAllow: () -> Unit,
    onSkipFinished: () -> Unit
): OnboardingPermissionActions {
    val context = LocalContext.current
    val view = LocalView.current
    var notificationGranted by remember {
        mutableStateOf(isNotificationPermissionGranted(context))
    }
    var storageGranted by remember {
        mutableStateOf(isStoragePermissionGranted(context))
    }
    var pendingAfterNotif by remember { mutableStateOf<(() -> Unit)?>(null) }
    // When true, completion uses onContinueAfterAllow; otherwise onSkipFinished.
    var preferCreateContainer by remember { mutableStateOf(false) }

    fun complete(
        notificationDecision: String,
        storageDecision: String,
        skipReminded: Boolean
    ) {
        finishOnboardingPermissions(
            context = context,
            storageDecision = storageDecision,
            onFinished = if (preferCreateContainer) onContinueAfterAllow else onSkipFinished
        )
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted || isNotificationPermissionGranted(context)
        val next = pendingAfterNotif
        pendingAfterNotif = null
        next?.invoke()
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        storageGranted = isStoragePermissionGranted(context)
        complete(
            notificationDecision = when {
                notificationGranted -> "granted"
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "not_required"
                else -> "denied"
            },
            storageDecision = if (storageGranted) "granted" else "denied",
            skipReminded = false
        )
    }

    fun refresh() {
        notificationGranted = isNotificationPermissionGranted(context)
        storageGranted = isStoragePermissionGranted(context)
    }

    fun afterNotificationPrompt() {
        if (storageGranted) {
            complete(
                notificationDecision = when {
                    notificationGranted -> "granted"
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "not_required"
                    else -> "denied"
                },
                storageDecision = "granted",
                skipReminded = false
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Storage is optional; do not force All-files Settings here.
            complete(
                notificationDecision = when {
                    notificationGranted -> "granted"
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "not_required"
                    else -> "denied"
                },
                storageDecision = "not_prompted",
                skipReminded = false
            )
        } else {
            (context as? Activity)?.let {
                legacyStorageLauncher.launch(
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    )
                )
            } ?: complete(
                notificationDecision = if (notificationGranted) "granted" else "denied",
                storageDecision = "not_prompted",
                skipReminded = false
            )
        }
    }

    return OnboardingPermissionActions(
        notificationGranted = notificationGranted,
        storageGranted = storageGranted,
        refresh = { refresh() },
        requestRecommendedThen = {
            view.performClickHaptic()
            preferCreateContainer = true
            refresh()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
                pendingAfterNotif = { afterNotificationPrompt() }
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                afterNotificationPrompt()
            }
        },
        skipWithReminder = {
            preferCreateContainer = false
            refresh()
            val notifOk = notificationGranted
            if (!notifOk) {
                view.performWarningShakeHaptic()
                Toast.makeText(
                    context,
                    OnboardingPermissionCopy.NOTIF_SKIP_REMINDER,
                    Toast.LENGTH_LONG
                ).show()
            } else {
                view.performClickHaptic()
            }
            complete(
                notificationDecision = when {
                    notifOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "granted"
                    notifOk -> "not_required"
                    else -> "skipped"
                },
                storageDecision = when {
                    storageGranted -> "granted"
                    else -> "skipped"
                },
                skipReminded = !notifOk
            )
        },
        openStorageSettings = {
            view.performClickHaptic()
            openStoragePermissionSettings(context)
        }
    )
}

data class OnboardingPermissionActions(
    val notificationGranted: Boolean,
    val storageGranted: Boolean,
    val refresh: () -> Unit,
    val requestRecommendedThen: () -> Unit,
    val skipWithReminder: () -> Unit,
    val openStorageSettings: () -> Unit
)

private fun finishOnboardingPermissions(
    context: android.content.Context,
    storageDecision: String,
    onFinished: () -> Unit
) {
    when (storageDecision) {
        "granted" -> LinuxIsolation.setBindPhoneStorage(context, true)
        "skipped", "denied" -> LinuxIsolation.setBindPhoneStorage(context, false)
    }
    onFinished()
}

private fun openStoragePermissionSettings(context: android.content.Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    } catch (_: Exception) {
        Toast.makeText(context, "Open Settings → Apps → PocketLinux → Permissions", Toast.LENGTH_LONG).show()
    }
}

/** True when package has been granted WRITE_EXTERNAL_STORAGE (pre-R). */
fun hasLegacyStoragePermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
}

/** True when MANAGE_EXTERNAL_STORAGE is held (R+). */
fun hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        false
    }
}
