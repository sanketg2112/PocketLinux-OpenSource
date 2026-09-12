package com.sg.linuxgo.ui.screens

import androidx.preference.PreferenceManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sg.linuxgo.ui.theme.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onThemeChange: (String) -> Unit,
    currentTheme: String,
    isDarkTheme: Boolean,
    onConfigureClick: (String) -> Unit,
    onShowLearnMore: () -> Unit = {},
    onCoverBottomNav: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    isBackupRestoreRunning: Boolean = false,
    backupRestoreProgress: Float = 0f,
    backupRestoreMessage: String = ""
) {
    val context = LocalContext.current
    val sharedPrefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }

    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) DividerDark else DividerLight
    val strokeColor = Color.Transparent

    val accentHex = remember { sharedPrefs.getString("pocketlinux_accent", "#2196F3") ?: "#2196F3" }
    var selectedAccentHex by remember { mutableStateOf(accentHex) }
    val accentColor = remember(selectedAccentHex) {
        try {
            Color(android.graphics.Color.parseColor(selectedAccentHex))
        } catch (e: Exception) {
            Magenta
        }
    }

    var isStorageGranted by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }

    var isNotificationGranted by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var showFeedbackSheet by remember { mutableStateOf(false) }
    var showDiagnosticsPage by remember { mutableStateOf(false) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isStorageGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }

                isNotificationGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsScrollContent(
                context = context,
                sharedPrefs = sharedPrefs,
                onBackupClick = onBackupClick,
                onRestoreClick = onRestoreClick,
                isBackupRestoreRunning = isBackupRestoreRunning,
                backupRestoreProgress = backupRestoreProgress,
                backupRestoreMessage = backupRestoreMessage,
                isStorageGranted = isStorageGranted,
                isNotificationGranted = isNotificationGranted,
                currentTheme = currentTheme,
                onThemeChange = onThemeChange,
                isDarkTheme = isDarkTheme,
                selectedAccentHex = selectedAccentHex,
                onSelectedAccentHexChange = { selectedAccentHex = it },
                accentColor = accentColor,
                textPrimaryColor = textPrimaryColor,
                textSecondaryColor = textSecondaryColor,
                dividerColor = dividerColor,
                strokeColor = strokeColor,
                onConfigureClick = onConfigureClick,
                onShowLearnMore = onShowLearnMore,
                onShowDiagnostics = { showDiagnosticsPage = true },
                onFeedbackClick = { showFeedbackSheet = true },
            )
        }

        if (showDiagnosticsPage) {
            DiagnosticsSettingsScreen(
                isDarkTheme = isDarkTheme,
                accentColor = accentColor,
                onDismiss = { showDiagnosticsPage = false },
                onCoverBottomNav = onCoverBottomNav
            )
        }

        if (showFeedbackSheet) {
            com.sg.linuxgo.ui.sheets.FeedbackAndSuggestionsSheet(
                onDismiss = { showFeedbackSheet = false },
                isDarkTheme = isDarkTheme,
                onSubmitted = {
                    // Keep home strip hidden if user submits from Settings instead.
                    com.sg.linuxgo.util.HomeFeedbackStripPrefs.markStripDismissed(context)
                }
            )
        }

    }
}
