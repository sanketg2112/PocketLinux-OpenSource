package com.sg.linuxgo.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.LegalDocuments
import com.sg.linuxgo.PrivacyDisclosures
import com.sg.linuxgo.TelemetryManager
import com.sg.linuxgo.ui.legal.LegalDocumentScreen
import com.sg.linuxgo.ui.theme.BackgroundDark
import com.sg.linuxgo.ui.theme.BackgroundLight
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary

@Composable
fun DiagnosticsSettingsScreen(
    isDarkTheme: Boolean,
    accentColor: Color,
    onDismiss: () -> Unit,
    onCoverBottomNav: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val background = if (isDarkTheme) BackgroundDark else BackgroundLight
    val dividerColor = settingsRowDividerColor(isDarkTheme)

    var showDiagnosticsDetails by remember { mutableStateOf(false) }
    var installId by remember { mutableStateOf(TelemetryManager.getInstallationId(context)) }
    var showResetId by remember { mutableStateOf(false) }
    var openLegalDocument by remember { mutableStateOf<LegalDocuments.Kind?>(null) }

    BackHandler(enabled = openLegalDocument == null, onBack = onDismiss)

    DisposableEffect(Unit) {
        onDispose { onCoverBottomNav(false) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = background
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(background)
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(background)
                            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = textPrimary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = LegalDocuments.SETTINGS_PAGE_TITLE,
                                fontFamily = FontFamily.Default,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary,
                                lineHeight = 22.sp
                            )
                            Text(
                                text = LegalDocuments.SETTINGS_PAGE_HEADER_SUBTITLE,
                                fontFamily = FontFamily.Default,
                                fontSize = 12.sp,
                                color = textSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = dividerColor)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(background)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 16.dp)
                ) {
                    SettingsGroupCard {
                        SettingsNavRow(
                            title = LegalDocuments.TERMS_TITLE,
                            subtitle = LegalDocuments.TERMS_ROW_SUBTITLE,
                            onClick = {
                                onCoverBottomNav(true)
                                openLegalDocument = LegalDocuments.Kind.TERMS
                            },
                            isDarkTheme = isDarkTheme
                        )
                        SettingsNavRow(
                            title = LegalDocuments.PRIVACY_TITLE,
                            subtitle = LegalDocuments.PRIVACY_ROW_SUBTITLE,
                            onClick = {
                                onCoverBottomNav(true)
                                openLegalDocument = LegalDocuments.Kind.PRIVACY
                            },
                            isDarkTheme = isDarkTheme
                        )
                        OpenSourceCreditsBlock(
                            isDarkTheme = isDarkTheme,
                            accentColor = accentColor
                        )
                    }

                    SettingsSectionLabel(PrivacyDisclosures.SETTINGS_DATA_SECTION)
                    SettingsGroupCard {
                        SettingsNavRow(
                            title = PrivacyDisclosures.DETAILS_TITLE,
                            subtitle = "Only crash reports and feedback you send",
                            onClick = { showDiagnosticsDetails = true },
                            isDarkTheme = isDarkTheme
                        )
                        SettingsNavRow(
                            title = PrivacyDisclosures.INSTALL_ID_TITLE,
                            subtitle = installId,
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("install id", installId))
                                Toast.makeText(
                                    context,
                                    PrivacyDisclosures.INSTALL_ID_COPIED,
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            isDarkTheme = isDarkTheme
                        )
                        SettingsNavRow(
                            title = PrivacyDisclosures.RESET_ID_TITLE,
                            subtitle = PrivacyDisclosures.RESET_ID_SUBTITLE,
                            onClick = { showResetId = true },
                            isDarkTheme = isDarkTheme,
                            showDivider = false
                        )
                    }
                }
            }
        }

        openLegalDocument?.let { kind ->
            LegalDocumentScreen(
                kind = kind,
                isDarkTheme = isDarkTheme,
                onDismiss = {
                    openLegalDocument = null
                    onCoverBottomNav(false)
                }
            )
        }
    }

    if (showDiagnosticsDetails) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDetails = false },
            title = { Text(PrivacyDisclosures.DETAILS_TITLE) },
            text = { Text(PrivacyDisclosures.DETAILS_BODY) },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDetails = false }) {
                    Text("OK")
                }
            }
        )
    }
    if (showResetId) {
        AlertDialog(
            onDismissRequest = { showResetId = false },
            title = { Text(PrivacyDisclosures.RESET_ID_TITLE) },
            text = { Text(PrivacyDisclosures.RESET_ID_CONFIRM) },
            confirmButton = {
                TextButton(onClick = {
                    TelemetryManager.resetInstallIdentity(context)
                    installId = TelemetryManager.getInstallationId(context)
                    showResetId = false
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetId = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun OpenSourceCreditsBlock(
    isDarkTheme: Boolean,
    accentColor: Color
) {
    val uriHandler = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    SettingsNavRow(
        title = LegalDocuments.RESOURCES_TITLE,
        subtitle = if (expanded) {
            LegalDocuments.RESOURCES_HIDE_SUBTITLE
        } else {
            LegalDocuments.RESOURCES_SUBTITLE
        },
        onClick = { expanded = !expanded },
        isDarkTheme = isDarkTheme,
        trailing = {
            Text(
                text = if (expanded) "▲" else "▼",
                fontSize = 11.sp,
                color = accentColor,
                fontWeight = FontWeight.SemiBold
            )
        },
        showDivider = expanded
    )
    if (expanded) {
        Column(modifier = Modifier.padding(bottom = 4.dp)) {
            LegalDocuments.OPEN_SOURCE_CREDITS.forEachIndexed { index, credit ->
                Text(
                    text = credit.name,
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = accentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                uriHandler.openUri(credit.url)
                            } catch (_: Exception) {
                            }
                        }
                        .padding(vertical = 6.dp)
                )
                if (index < LegalDocuments.OPEN_SOURCE_CREDITS.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = settingsRowDividerColor(isDarkTheme)
                    )
                }
            }
        }
    }
}
