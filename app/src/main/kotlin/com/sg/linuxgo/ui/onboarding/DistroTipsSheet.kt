package com.sg.linuxgo.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.theme.DividerDark
import com.sg.linuxgo.ui.theme.DividerLight
import com.sg.linuxgo.ui.theme.SurfaceDark
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.utils.performClickHaptic

/**
 * Bottom sheet with short, copyable package-manager tips for a distro.
 * Height-capped so it never covers the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistroTipsSheet(
    distroId: String,
    isDarkTheme: Boolean,
    accentColor: Color,
    titleOverride: String? = null,
    onDismiss: () -> Unit,
    @Suppress("UNUSED_PARAMETER")
    onOpenTerminal: (() -> Unit)? = null
) {
    val guide = remember(distroId) { DistroPackageTips.forDistro(distroId) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val view = LocalView.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp

    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val codeBg = if (isDarkTheme) Color(0xFF141418) else Color(0xFFF4F4F7)
    val cardBorder = if (isDarkTheme) DividerDark else DividerLight
    val sheetBg = if (isDarkTheme) SurfaceDark else MaterialTheme.colorScheme.surface
    val handleColor = if (isDarkTheme) Color(0xFF3A3A42) else Color(0xFFC8C8D0)
    val chipBg = guide.accent.copy(alpha = if (isDarkTheme) 0.18f else 0.12f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBg,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        tonalElevation = 0.dp,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
        ) {
            // ── Header (fixed) ──────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp, bottom = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(handleColor)
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = titleOverride ?: "Get started with ${guide.displayName}",
                            color = textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Default,
                            lineHeight = 24.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(chipBg)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = guide.packageManager,
                                    color = guide.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                text = guide.displayName,
                                color = textSecondary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Default
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            view.performClickHaptic()
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDarkTheme) Color(0xFF2A2A30) else Color(0xFFF0F0F4)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = guide.summary,
                    color = textSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default,
                    lineHeight = 18.sp
                )
            }

            HorizontalDivider(
                color = cardBorder.copy(alpha = 0.7f),
                thickness = 1.dp,
                modifier = Modifier.padding(top = 12.dp)
            )

            // ── Scrollable body ─────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 8.dp)
            ) {
                SectionLabel(text = "Stability (important)", color = textSecondary)
                Spacer(modifier = Modifier.height(10.dp))
                AndroidStabilityTipCard(
                    isDarkTheme = isDarkTheme,
                    accentColor = accentColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                Spacer(modifier = Modifier.height(18.dp))
                SectionLabel(text = "Commands", color = textSecondary)
                Spacer(modifier = Modifier.height(10.dp))

                guide.commands.forEachIndexed { index, cmd ->
                    CommandRow(
                        label = cmd.label,
                        command = cmd.command,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        codeBg = codeBg,
                        border = cardBorder,
                        accentColor = guide.accent,
                        onCopy = {
                            copyToClipboard(context, cmd.command)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    )
                    if (index < guide.commands.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                SectionLabel(text = "Good to know", color = textSecondary)
                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(codeBg)
                        .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    guide.extraTips.forEach { tip ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp, end = 10.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(guide.accent)
                            )
                            Text(
                                text = tip,
                                color = textPrimary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Default,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // ── Footer actions (fixed) ──────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                TextButton(
                    onClick = {
                        view.performClickHaptic()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = "Got it",
                        color = textSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Default
                    )
                }
            }
        }
    }
}

/**
 * Compact chooser when the user opens tips without a single target distro.
 * Pass [availableDistroIds] to limit options (e.g. only installed Arch/Debian).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistroTipsChooserSheet(
    isDarkTheme: Boolean,
    accentColor: Color,
    availableDistroIds: List<String>? = null,
    onSelectDistro: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val cardBorder = if (isDarkTheme) DividerDark else DividerLight
    val cardBg = if (isDarkTheme) Color(0xFF1C1C20) else Color(0xFFF7F7F9)
    val sheetBg = if (isDarkTheme) SurfaceDark else MaterialTheme.colorScheme.surface
    val handleColor = if (isDarkTheme) Color(0xFF3A3A42) else Color(0xFFC8C8D0)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val view = LocalView.current

    val guides = remember(availableDistroIds) {
        DistroPackageTips.guidesFor(availableDistroIds)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBg,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        tonalElevation = 0.dp,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(handleColor)
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Package tips",
                        color = textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Default
                    )
                    Text(
                        text = if (availableDistroIds.isNullOrEmpty()) {
                            "Pick a distro for install commands"
                        } else {
                            "Tips for your installed container${if (guides.size > 1) "s" else ""}"
                        },
                        color = textSecondary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Default,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                IconButton(
                    onClick = {
                        view.performClickHaptic()
                        onDismiss()
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isDarkTheme) Color(0xFF2A2A30) else Color(0xFFF0F0F4)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            guides.forEach { guide ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(cardBg)
                        .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
                        .clickable {
                            view.performClickHaptic()
                            onSelectDistro(guide.distroId)
                        }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(guide.accent.copy(alpha = if (isDarkTheme) 0.2f else 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(guide.accent)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = guide.displayName,
                            color = textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Default
                        )
                        Text(
                            text = guide.packageManager,
                            color = guide.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "›",
                        color = textSecondary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Light
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        fontFamily = FontFamily.Default
    )
}

@Composable
private fun CommandRow(
    label: String,
    command: String,
    textPrimary: Color,
    textSecondary: Color,
    codeBg: Color,
    border: Color,
    accentColor: Color,
    onCopy: () -> Unit
) {
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(codeBg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 10.dp)
    ) {
        Text(
            text = label,
            color = textSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Default,
            modifier = Modifier.padding(bottom = 6.dp, end = 8.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = command,
                color = textPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    view.performClickHaptic()
                    onCopy()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy command",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("command", text))
}
