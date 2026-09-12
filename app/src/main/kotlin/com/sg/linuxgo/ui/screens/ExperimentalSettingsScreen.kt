package com.sg.linuxgo.ui.screens

import androidx.preference.PreferenceManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.sg.linuxgo.ui.sheets.ExperimentalTawcrootBenchSection
import com.sg.linuxgo.ui.sheets.PillSelectionGroup
import com.sg.linuxgo.ui.sheets.SwitchSettingRow
import com.sg.linuxgo.ui.theme.BackgroundDark
import com.sg.linuxgo.ui.theme.BackgroundLight
import com.sg.linuxgo.ui.theme.DividerDark
import com.sg.linuxgo.ui.theme.DividerLight
import com.sg.linuxgo.ui.theme.Magenta
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.theme.Yellow

/**
 * Full-page Experimental settings (separate from display/keyboard sheets).
 */
@Composable
fun ExperimentalSettingsScreen(
    isDarkTheme: Boolean,
    onDismiss: () -> Unit,
    onSettingsSaved: () -> Unit = {}
) {
    val context = LocalContext.current
    val sharedPrefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) Color.White.copy(alpha = 0.08f) else DividerLight
    val background = if (isDarkTheme) BackgroundDark else BackgroundLight

    val accentHex = remember { sharedPrefs.getString("pocketlinux_accent", "#2196F3") ?: "#2196F3" }
    val accentColor = remember(accentHex) {
        try {
            Color(android.graphics.Color.parseColor(accentHex))
        } catch (_: Exception) {
            Magenta
        }
    }

    var waylandEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("enableWaylandSupport", false))
    }
    var gfxBackend by remember {
        mutableStateOf(com.sg.linuxgo.TawcWaylandCompat.graphicsBackend(context))
    }
    var helpDialogInfo by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun saveWayland(enabled: Boolean) {
        waylandEnabled = enabled
        sharedPrefs.edit().putBoolean("enableWaylandSupport", enabled).apply()
        // First enable: graphics backend defaults to libhybris (unset pref).
        gfxBackend = com.sg.linuxgo.TawcWaylandCompat.graphicsBackend(context)
        if (enabled) {
            com.sg.linuxgo.FeatureGates.applyDefaultWaylandGuiToContainers(context)
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
                    text = "Experimental settings",
                    fontFamily = FontFamily.Default,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Yellow.copy(alpha = 0.18f))
                        .border(1.dp, Yellow.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "UNSTABLE",
                        fontFamily = FontFamily.Default,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Yellow,
                        letterSpacing = 0.06.sp,
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = dividerColor)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                ExperimentalUnstableWarning(
                    isDarkTheme = isDarkTheme,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Text(
                    text = "DISPLAY",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 0.1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                SwitchSettingRow(
                    title = "Enable Wayland Support",
                    summary = "Container cards default to Wayland",
                    description = "When enabled, each container card shows Wayland as the GUI server. Pick libhybris, Mesa, or CPU below. The card GPU driver picker is disabled while a container is on Wayland.",
                    checked = waylandEnabled,
                    onCheckedChange = { saveWayland(it) },
                    onInfoClick = {
                        helpDialogInfo = "Enable Wayland Support" to
                            "Turns on Wayland for container cards (default). Each card can still switch back to X11. Graphics backend (libhybris / Mesa / CPU) only applies to Wayland sessions. The container GPU driver picker is disabled in Wayland mode."
                    },
                    accentColor = accentColor,
                    isDarkTheme = isDarkTheme
                )

                if (waylandEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "WAYLAND / TAWC COMPAT",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        letterSpacing = 0.1.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Inspired by github.com/wmww/tawc. Applies only when a container uses Wayland. " +
                            "X11 (Lorie) path is unchanged.",
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = textSecondary,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    var gtk3Menus by remember {
                        mutableStateOf(
                            com.sg.linuxgo.TawcWaylandCompat.isGtk3MenusWorkaroundEnabled(context)
                        )
                    }
                    SwitchSettingRow(
                        title = "GTK3 broken menus workaround",
                        summary = if (gtk3Menus) {
                            "On — spoof pointer enter on each window"
                        } else {
                            "Off"
                        },
                        description = "Spoof a pointer briefly entering each window, allows GTK3 menus to work correctly (tawc).",
                        checked = gtk3Menus,
                        onCheckedChange = { on ->
                            gtk3Menus = on
                            com.sg.linuxgo.TawcWaylandCompat.setGtk3MenusWorkaroundEnabled(context, on)
                            onSettingsSaved()
                        },
                        onInfoClick = {
                            helpDialogInfo = "GTK3 broken menus workaround" to
                                "From tawc: on touch-first seats, GTK3 menubars can open the leftmost " +
                                "item instead of the tapped one when server-side decorations put the " +
                                "menubar at (0,0). The compositor briefly sends wl_pointer enter/leave " +
                                "at the center of each new window. Restart the Wayland session after changing."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Graphics backend (Wayland)",
                        fontFamily = FontFamily.Default,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textPrimary,
                    )
                    Text(
                        text = "Default is libhybris (phone GPU). Mesa uses the container GPU " +
                            "driver picker. CPU forces llvmpipe. Restart the Wayland session after changing.",
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    PillSelectionGroup(
                        labels = listOf("libhybris", "Mesa", "CPU"),
                        values = listOf(
                            com.sg.linuxgo.TawcWaylandCompat.GFX_HYBRIS,
                            com.sg.linuxgo.TawcWaylandCompat.GFX_MESA,
                            com.sg.linuxgo.TawcWaylandCompat.GFX_CPU,
                        ),
                        selectedValue = gfxBackend,
                        onValueChange = { id ->
                            gfxBackend = id
                            com.sg.linuxgo.TawcWaylandCompat.setGraphicsBackend(context, id)
                            onSettingsSaved()
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme,
                        pillsPerRow = 3,
                    )
                    Text(
                        text = "Active: ${com.sg.linuxgo.TawcWaylandCompat.graphicsBackendLabel(gfxBackend)}.",
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = textSecondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    )
                    Text(
                        text = "XWayland: labwc still starts distro Xwayland; session waits for " +
                            "/tmp/.X11-unix and prefers GDK/Qt/SDL Wayland with X11 fallback.",
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                ExperimentalTawcrootBenchSection(
                    accentColor = accentColor,
                    isDarkTheme = isDarkTheme,
                    onHelp = { title, body -> helpDialogInfo = title to body }
                )

                Spacer(modifier = Modifier.height(20.dp))
                ExperimentalThanksSection(
                    accentColor = accentColor,
                    textSecondary = textSecondary,
                    isDarkTheme = isDarkTheme,
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ExperimentalUnstableWarning(
    isDarkTheme: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    modifier: Modifier = Modifier,
) {
    val cardBg = if (isDarkTheme) {
        Color(0xFF2A2410)
    } else {
        Color(0xFFFFF8E1)
    }
    val border = Yellow.copy(alpha = if (isDarkTheme) 0.45f else 0.55f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Yellow,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Unstable · use with care",
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "These options are experimental and may crash sessions, break graphics, " +
                "or change between releases. Prefer defaults unless you know you need them. " +
                "Restart open terminal or desktop sessions after changing runtime options.",
            fontFamily = FontFamily.Default,
            fontSize = 12.sp,
            color = textSecondary,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun ExperimentalThanksSection(
    accentColor: Color,
    textSecondary: Color,
    isDarkTheme: Boolean,
) {
    Text(
        text = "THANKS",
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = accentColor,
        letterSpacing = 0.1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Text(
        text = "Open-source projects used by these experimental tools.",
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        color = textSecondary,
        modifier = Modifier.padding(bottom = 10.dp)
    )
    SettingsGroupCard(modifier = Modifier.padding(bottom = 0.dp)) {
        ExperimentalThanksRow(
            title = "tawc (Wayland)",
            subtitle = "Wayland compatibility",
            url = "https://github.com/wmww/tawc",
            accentColor = accentColor,
            isDarkTheme = isDarkTheme,
            showDivider = true,
        )
        ExperimentalThanksRow(
            title = "tawc / tawcroot (Systrap Runtime)",
            subtitle = "Performance runtime",
            url = "https://github.com/wmww/tawc",
            accentColor = accentColor,
            isDarkTheme = isDarkTheme,
            showDivider = false,
        )
    }
}

@Composable
private fun ExperimentalThanksRow(
    title: String,
    subtitle: String,
    url: String,
    accentColor: Color,
    isDarkTheme: Boolean,
    showDivider: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    try {
                        uriHandler.openUri(url)
                    } catch (_: Exception) {
                    }
                }
                .padding(vertical = 10.dp)
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = accentColor,
            )
            Text(
                text = subtitle,
                fontFamily = FontFamily.Default,
                fontSize = 11.sp,
                color = if (isDarkTheme) TextSecondary else TextLightSecondary,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = settingsRowDividerColor(isDarkTheme),
            )
        }
    }
}
