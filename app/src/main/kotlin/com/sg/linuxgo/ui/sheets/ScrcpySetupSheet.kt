package com.sg.linuxgo.ui.sheets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.scrcpy.ScrcpySetupGuide
import com.sg.linuxgo.ui.theme.DividerDark
import com.sg.linuxgo.ui.theme.DividerLight
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.util.DeviceNetworkInfo

/**
 * Full setup walkthrough: phone debugging → ADB drivers → install scrcpy → USB/Wi‑Fi → PocketLinux.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrcpySetupSheet(
    isDarkTheme: Boolean,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val codeBg = if (isDarkTheme) Color(0xFF16161A) else Color(0xFFF2F2F5)
    val cardBorder = if (isDarkTheme) DividerDark else DividerLight
    val sheetBg = MaterialTheme.colorScheme.surface
    val deviceIp = DeviceNetworkInfo.ipv4Address(context)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBg,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Set up big screen (scrcpy)",
                        color = textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Default
                    )
                    Text(
                        text = "ADB → scrcpy → PocketLinux on your PC",
                        color = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = textSecondary
                    )
                }
            }

            Text(
                text = ScrcpySetupGuide.introSummary,
                color = textSecondary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Default,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (deviceIp != null) {
                ScrcpyCommandRow(
                    label = "This phone’s Wi‑Fi IP (for adb connect)",
                    command = deviceIp,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    codeBg = codeBg,
                    border = cardBorder,
                    accentColor = accentColor,
                    onCopy = {
                        copyScrcpyClipboard(context, deviceIp)
                        Toast.makeText(context, "IP copied", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ScrcpyCommandRow(
                    label = "Connect ADB over Wi‑Fi (after adb tcpip 5555)",
                    command = ScrcpySetupGuide.cmdAdbConnect(deviceIp),
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    codeBg = codeBg,
                    border = cardBorder,
                    accentColor = accentColor,
                    onCopy = {
                        copyScrcpyClipboard(context, ScrcpySetupGuide.cmdAdbConnect(deviceIp))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Text(
                    text = "Wi‑Fi IP not available — connect to Wi‑Fi to see your address for wireless ADB.",
                    color = textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            Text(
                text = "QUICK COMMANDS",
                color = textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontFamily = FontFamily.Default,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            ScrcpySetupGuide.quickCommands.forEach { cmd ->
                ScrcpyCommandRow(
                    label = cmd.label,
                    command = cmd.command,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    codeBg = codeBg,
                    border = cardBorder,
                    accentColor = accentColor,
                    onCopy = {
                        copyScrcpyClipboard(context, cmd.command)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = cardBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "STEP BY STEP",
                color = textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontFamily = FontFamily.Default,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ScrcpySetupGuide.orderedSections.forEach { step ->
                Text(
                    text = step.title,
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Default,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = step.body,
                    color = textSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                step.commands.forEach { cmd ->
                    ScrcpyCommandRow(
                        label = cmd.label,
                        command = cmd.command,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        codeBg = codeBg,
                        border = cardBorder,
                        accentColor = accentColor,
                        onCopy = {
                            copyScrcpyClipboard(context, cmd.command)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (step.linkUrl != null && step.linkLabel != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                try {
                                    uriHandler.openUri(step.linkUrl)
                                } catch (_: Exception) {
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 0.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = step.linkLabel,
                            color = accentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Default
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "INSTALL BY PLATFORM",
                color = textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontFamily = FontFamily.Default,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ScrcpySetupGuide.platformInstalls.forEach { platform ->
                Text(
                    text = platform.title,
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Default,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                platform.steps.forEach { line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            color = accentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp, top = 1.dp)
                        )
                        Text(
                            text = line,
                            color = textSecondary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Default,
                            lineHeight = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                platform.commands.forEach { cmd ->
                    ScrcpyCommandRow(
                        label = cmd.label,
                        command = cmd.command,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        codeBg = codeBg,
                        border = cardBorder,
                        accentColor = accentColor,
                        onCopy = {
                            copyScrcpyClipboard(context, cmd.command)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            try {
                                uriHandler.openUri(platform.docUrl)
                            } catch (_: Exception) {
                            }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Official ${platform.id} install docs",
                        color = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Default
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "GOOD TO KNOW",
                color = textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontFamily = FontFamily.Default,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            ScrcpySetupGuide.limitations.forEach { tip ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "•",
                        color = accentColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp, top = 1.dp)
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

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        try {
                            uriHandler.openUri(ScrcpySetupGuide.OFFICIAL_REPO)
                        } catch (_: Exception) {
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Open scrcpy on GitHub",
                    color = accentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Default
                )
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Got it",
                    color = textSecondary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Default
                )
            }
        }
    }
}

@Composable
private fun ScrcpyCommandRow(
    label: String,
    command: String,
    textPrimary: Color,
    textSecondary: Color,
    codeBg: Color,
    border: Color,
    accentColor: Color,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(codeBg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = label,
            color = textSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Default,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = command,
                color = textPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun copyScrcpyClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("scrcpy", text))
}
