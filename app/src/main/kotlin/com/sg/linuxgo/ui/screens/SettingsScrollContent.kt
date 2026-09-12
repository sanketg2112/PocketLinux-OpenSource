package com.sg.linuxgo.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.SharedPreferences
import com.sg.linuxgo.BuildConfig
import com.sg.linuxgo.FeatureGates
import com.sg.linuxgo.LegalDocuments
import com.sg.linuxgo.LinuxIsolation
import com.sg.linuxgo.MainActivity
import com.sg.linuxgo.ui.theme.*

@Composable
internal fun SettingsScrollContent(
    context: Context,
    sharedPrefs: SharedPreferences,
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    isBackupRestoreRunning: Boolean,
    backupRestoreProgress: Float,
    backupRestoreMessage: String,
    isStorageGranted: Boolean,
    isNotificationGranted: Boolean,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    isDarkTheme: Boolean,
    selectedAccentHex: String,
    onSelectedAccentHexChange: (String) -> Unit,
    accentColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    dividerColor: Color,
    strokeColor: Color,
    onConfigureClick: (String) -> Unit,
    onShowLearnMore: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onFeedbackClick: () -> Unit = {},
) {
    // —— Appearance (theme + accent) — UI matches themesettings.html mockup ——
    ThemeAppearanceSection(
        currentTheme = currentTheme,
        onThemeChange = onThemeChange,
        isDarkTheme = isDarkTheme,
        selectedAccentHex = selectedAccentHex,
        onSelectedAccentHexChange = onSelectedAccentHexChange,
        accentColor = accentColor,
        sharedPrefs = sharedPrefs
    )

    // —— Desktop & input ——
    SettingsSectionLabel("DESKTOP & INPUT")
    SettingsGroupCard {
        SettingsNavRow(
            title = "Output",
            subtitle = "Resolution, scale, orientation",
            onClick = { onConfigureClick("output") },
            isDarkTheme = isDarkTheme
        )
        SettingsNavRow(
            title = "Keyboard",
            subtitle = "Software keyboard & shortcuts",
            onClick = { onConfigureClick("keyboard") },
            isDarkTheme = isDarkTheme
        )
        SettingsNavRow(
            title = "Pointer",
            subtitle = "Touch, trackpad, mouse, stylus",
            onClick = { onConfigureClick("pointer") },
            isDarkTheme = isDarkTheme
        )
        SettingsNavRow(
            title = "Other",
            subtitle = "Clipboard & secondary display",
            onClick = { onConfigureClick("other") },
            isDarkTheme = isDarkTheme
        )
        SettingsNavRow(
            title = "Big Screen (scrcpy)",
            subtitle = "Use desktop on a PC over USB/Wi‑Fi",
            onClick = { onConfigureClick("big_screen") },
            showDivider = true,
            isDarkTheme = isDarkTheme
        )
        // Wayland / tawcroot
        if (FeatureGates.experimentalSettingsVisible()) {
            SettingsNavRow(
                title = "Experimental",
                subtitle = "Wayland tools and performance improvement tweaks",
                onClick = { onConfigureClick("experimental") },
                showDivider = false,
                isDarkTheme = isDarkTheme
            )
        }
    }

    // —— Data & permissions ——
    SettingsSectionLabel("DATA & PERMISSIONS")
    SettingsGroupCard {
        Text(
            text = "Backup & restore",
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        Text(
            text = "Backups go to shared storage as .tar.gz. They can contain SSH keys, browser data, and shell history. Set a password when you back up if you want them encrypted.",
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                OutlinedButton(
                    onClick = onBackupClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, dividerColor),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF68B2A0)
                    )
                ) {
                    Text(
                        text = "Backup",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedButton(
                    onClick = onRestoreClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, dividerColor),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF68B2A0)
                    )
                ) {
                    Text(
                        text = "Restore",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (isBackupRestoreRunning) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { backupRestoreProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    if (backupRestoreMessage.isNotEmpty()) {
                        Text(
                            text = backupRestoreMessage,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = settingsRowDividerColor(isDarkTheme),
            modifier = Modifier.padding(top = 10.dp)
        )
        var bindPhoneStorage by remember {
            mutableStateOf(LinuxIsolation.isBindPrefEnabled(context))
        }
        SettingsSwitchRow(
            title = "Mount phone storage in Linux",
            subtitle = if (bindPhoneStorage && isStorageGranted) {
                "On — Linux can read photos and downloads at /sdcard. Software you install inside can too."
            } else if (bindPhoneStorage) {
                "On, but All files access is off — nothing is mounted until you grant it."
            } else {
                "Off — Linux cannot see phone files. Needed separately from backups."
            },
            checked = bindPhoneStorage,
            onCheckedChange = { enabled ->
                LinuxIsolation.setBindPhoneStorage(context, enabled)
                bindPhoneStorage = enabled
            },
            isDarkTheme = isDarkTheme
        )
        PermissionRowItem(
            title = "All files access",
            subtitle = "Needed for backups and for mounting phone files",
            granted = isStorageGranted,
            onGrantClick = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        } catch (ex: Exception) {
                            Toast.makeText(context, "Could not request storage permission", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    (context as? android.app.Activity)?.let {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            it,
                            arrayOf(
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                android.Manifest.permission.READ_EXTERNAL_STORAGE
                            ),
                            1001
                        )
                    }
                }
            },
            onRevokeClick = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        } catch (ex: Exception) {
                            Toast.makeText(context, "Could not open permission settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            },
            accentColor = accentColor,
            showDivider = true,
            isDarkTheme = isDarkTheme
        )
        PermissionRowItem(
            title = "Notifications",
            subtitle = "Background & terminal status",
            granted = isNotificationGranted,
            onGrantClick = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    (context as? android.app.Activity)?.let {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            it,
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                            1002
                        )
                    }
                }
            },
            onRevokeClick = {
                val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            },
            accentColor = accentColor,
            showDivider = false,
            isDarkTheme = isDarkTheme
        )
    }

    // —— Help & about ——
    SettingsSectionLabel("HELP & ABOUT")
    SettingsGroupCard {
        SettingsNavRow(
            title = LegalDocuments.SETTINGS_PAGE_TITLE,
            subtitle = LegalDocuments.SETTINGS_PAGE_SUBTITLE,
            onClick = onShowDiagnostics,
            isDarkTheme = isDarkTheme
        )
        SettingsNavRow(
            title = "Learn more",
            subtitle = "Containers, desktop, terminal basics",
            onClick = onShowLearnMore,
            isDarkTheme = isDarkTheme
        )
        SettingsNavRow(
            title = "Feedback & suggestions",
            subtitle = "Rate the app or request a feature",
            onClick = onFeedbackClick,
            isDarkTheme = isDarkTheme
        )

        val versionName = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
            } catch (_: Exception) {
                "1.0"
            }
        }
        val versionTapInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        ) {
            Text(
                text = "About",
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Rootless Linux desktop on Android",
                fontFamily = FontFamily.Default,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp)
            )
            Text(
                text = "v$versionName",
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
}
