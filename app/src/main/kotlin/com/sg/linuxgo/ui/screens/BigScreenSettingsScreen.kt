package com.sg.linuxgo.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.preference.PreferenceManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.sg.linuxgo.ui.sheets.SwitchSettingRow
import com.sg.linuxgo.ui.theme.BackgroundDark
import com.sg.linuxgo.ui.theme.BackgroundLight
import com.sg.linuxgo.ui.theme.DividerLight
import com.sg.linuxgo.ui.theme.Magenta
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.util.DeviceNetworkInfo

/**
 * Global Big screen (scrcpy) settings: enable flag, device IP, and copyable commands.
 */
@Composable
fun BigScreenSettingsScreen(
    isDarkTheme: Boolean,
    onDismiss: () -> Unit,
    onSettingsSaved: () -> Unit = {}
) {
    val context = LocalContext.current
    val sharedPrefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val uriHandler = LocalUriHandler.current

    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) Color.White.copy(alpha = 0.08f) else DividerLight
    val background = if (isDarkTheme) BackgroundDark else BackgroundLight
    val codeBg = if (isDarkTheme) Color(0xFF16161A) else Color(0xFFF2F2F5)

    val accentHex = remember { sharedPrefs.getString("pocketlinux_accent", "#2196F3") ?: "#2196F3" }
    val accentColor = remember(accentHex) {
        try {
            Color(android.graphics.Color.parseColor(accentHex))
        } catch (_: Exception) {
            Magenta
        }
    }

    var enabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("big_screen_scrcpy_enabled", false))
    }
    var helpDialogInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    val deviceIp = remember { DeviceNetworkInfo.ipv4Address(context) }

    fun saveEnabled(value: Boolean) {
        enabled = value
        sharedPrefs.edit().putBoolean("big_screen_scrcpy_enabled", value).apply()
        if (value) {
            // Soft nudge: keep screen on helps scrcpy sessions stay useful.
            sharedPrefs.edit().putBoolean("keepScreenOn", true).apply()
        }
        onSettingsSaved()
    }

    if (helpDialogInfo != null) {
        AlertDialog(
            onDismissRequest = { helpDialogInfo = null },
            title = {
                Text(
                    text = helpDialogInfo!!.first,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textPrimary
                )
            },
            text = {
                Text(
                    text = helpDialogInfo!!.second,
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    color = textPrimary
                )
            },
            confirmButton = {
                TextButton(onClick = { helpDialogInfo = null }) {
                    Text("OK", fontFamily = FontFamily.Default, color = accentColor)
                }
            },
            containerColor = background
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = textPrimary
                    )
                }
                Text(
                    text = "Big screen (scrcpy)",
                    fontFamily = FontFamily.Default,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )
            }

            HorizontalDivider(thickness = 1.dp, color = dividerColor)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = ScrcpySetupGuide.introSummary,
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = textSecondary,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                SwitchSettingRow(
                    title = "Enable big screen mode",
                    summary = "Mark that you use scrcpy; enables keep-screen-on nudge",
                    description = "Does not install software on the PC. Copy a command below after installing official scrcpy on your computer.",
                    checked = enabled,
                    onCheckedChange = { saveEnabled(it) },
                    onInfoClick = {
                        helpDialogInfo = "Big screen mode" to
                            "Turns on the big-screen companion feature. You still install official scrcpy and ADB on your computer. " +
                            "Enable “Big screen ready” on each container you want optimized for landscape / PC use. " +
                            "Use the recommended PC commands on this page for USB or Wi‑Fi."
                    },
                    accentColor = accentColor,
                    isDarkTheme = isDarkTheme
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "THIS PHONE",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 0.1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = if (deviceIp != null) {
                        "Wi‑Fi IP: $deviceIp"
                    } else {
                        "Wi‑Fi IP: not available (connect to Wi‑Fi)"
                    },
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    color = textPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (deviceIp != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyClip(context, deviceIp)
                                Toast.makeText(context, "IP copied", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Copy IP", color = accentColor, fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val cmd = ScrcpySetupGuide.cmdAdbConnect(deviceIp)
                                copyClip(context, cmd)
                                Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Copy adb connect", color = accentColor, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "RECOMMENDED ON PC",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 0.1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                BigScreenCommandCard(
                    label = "USB — virtual display",
                    command = ScrcpySetupGuide.CMD_VIRTUAL_DISPLAY,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    codeBg = codeBg,
                    border = dividerColor,
                    accentColor = accentColor,
                    context = context
                )
                Spacer(modifier = Modifier.height(10.dp))
                BigScreenCommandCard(
                    label = "Wi‑Fi — after USB once",
                    command = ScrcpySetupGuide.CMD_VIRTUAL_DISPLAY_WIFI,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    codeBg = codeBg,
                    border = dividerColor,
                    accentColor = accentColor,
                    context = context
                )
                Spacer(modifier = Modifier.height(10.dp))
                BigScreenCommandCard(
                    label = "Simple mirror",
                    command = ScrcpySetupGuide.CMD_SIMPLE,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    codeBg = codeBg,
                    border = dividerColor,
                    accentColor = accentColor,
                    context = context
                )

                Spacer(modifier = Modifier.height(20.dp))

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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Official scrcpy on GitHub",
                        color = accentColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Also enable “Big screen ready” in each container’s settings for landscape-friendly sessions.",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    color = textSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(8.dp))
                ScrcpySetupGuide.limitations.forEach { tip ->
                    Text(
                        text = "• $tip",
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = textSecondary,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun BigScreenCommandCard(
    label: String,
    command: String,
    textPrimary: Color,
    textSecondary: Color,
    codeBg: Color,
    border: Color,
    accentColor: Color,
    context: Context
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = command,
                color = textPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    copyClip(context, command)
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
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

private fun copyClip(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("scrcpy", text))
}
