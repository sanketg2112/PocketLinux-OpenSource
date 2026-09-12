package com.sg.linuxgo.ui.screens

import android.content.SharedPreferences
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.utils.performClickHaptic

/**
 * Appearance block: same structure as themesettings.html, sized to match
 * the denser settings cards (SettingsGroupCard / SettingsSectionLabel).
 */
@Composable
internal fun ThemeAppearanceSection(
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    isDarkTheme: Boolean,
    selectedAccentHex: String,
    onSelectedAccentHexChange: (String) -> Unit,
    accentColor: Color,
    sharedPrefs: SharedPreferences
) {
    val view = LocalView.current
    val trackBg = if (isDarkTheme) Color(0xFF2A2A2E) else Color(0xFFE8E8ED)
    val titleColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = settingsRowDividerColor(isDarkTheme)

    val selectedTrack = accentColor.copy(alpha = if (isDarkTheme) 0.28f else 0.18f)
    val selectedLabel = if (isDarkTheme) {
        if (accentColor.luminance() > 0.45f) accentColor else Color(
            red = (accentColor.red * 0.55f + 0.45f).coerceIn(0f, 1f),
            green = (accentColor.green * 0.55f + 0.45f).coerceIn(0f, 1f),
            blue = (accentColor.blue * 0.55f + 0.45f).coerceIn(0f, 1f),
            alpha = 1f
        )
    } else {
        accentColor
    }

    SettingsSectionLabel("APPEARANCE")

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Theme",
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ThemeSegmentedControl(
                currentTheme = currentTheme,
                onThemeChange = { theme ->
                    view.performClickHaptic()
                    onThemeChange(theme)
                },
                trackBg = trackBg,
                selectedTrack = selectedTrack,
                selectedLabel = selectedLabel,
                unselectedLabel = secondaryColor
            )

            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(dividerColor)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Accent",
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            AccentColorRow(
                selectedAccentHex = selectedAccentHex,
                onSelect = { hex ->
                    view.performClickHaptic()
                    onSelectedAccentHexChange(hex)
                    sharedPrefs.edit().putString("pocketlinux_accent", hex).apply()
                    onThemeChange(currentTheme)
                }
            )
        }
    }
}

@Composable
private fun ThemeSegmentedControl(
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    trackBg: Color,
    selectedTrack: Color,
    selectedLabel: Color,
    unselectedLabel: Color
) {
    val options = listOf(
        ThemeOption("system", "System", Icons.Default.Smartphone),
        ThemeOption("dark", "Dark", Icons.Default.DarkMode),
        ThemeOption("light", "Light", Icons.Default.LightMode)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(trackBg)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        options.forEach { opt ->
            val selected = currentTheme.equals(opt.id, ignoreCase = true)
            val bg by animateColorAsState(
                targetValue = if (selected) selectedTrack else Color.Transparent,
                animationSpec = tween(180),
                label = "themeSegBg"
            )
            val fg by animateColorAsState(
                targetValue = if (selected) selectedLabel else unselectedLabel,
                animationSpec = tween(180),
                label = "themeSegFg"
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onThemeChange(opt.id) }
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = opt.icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = opt.label,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Default,
                    maxLines = 1
                )
            }
        }
    }
}

private data class ThemeOption(
    val id: String,
    val label: String,
    val icon: ImageVector
)

/** Featured accents from the mockup, then the rest of the app palette. */
internal val THEME_ACCENT_COLORS: List<String> = listOf(
    "#5C94FF",
    "#4CAF50",
    "#9C27B0",
    "#00BCD4",
    "#FFC107",
    "#2196F3",
    "#6EB0BA",
    "#4ADE80",
    "#BD93F9",
    "#2AA198",
    "#A6E22E",
    "#00C853",
    "#2D88FF",
    "#88C0D0",
    "#61AFEF",
    "#FE8019",
    "#A277FF",
    "#FF007F",
    "#FF5E7E",
    "#48CAE4",
    "#81C784",
    "#7AA2F7",
    "#EBBCB2",
    "#F92AAD",
    "#E0A96D",
    "#CCFF00",
    "#84A0C6",
    "#E91E63",
    "#FF5722",
    "#009688"
)

@Composable
private fun AccentColorRow(
    selectedAccentHex: String,
    onSelect: (String) -> Unit
) {
    val selectedNorm = selectedAccentHex.trim().uppercase()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        THEME_ACCENT_COLORS.forEach { hex ->
            val colorVal = runCatching {
                Color(android.graphics.Color.parseColor(hex))
            }.getOrElse { Color.Gray }
            val selected = selectedNorm.equals(hex, ignoreCase = true)
            SolidAccentCircle(
                color = colorVal,
                selected = selected,
                onClick = { onSelect(hex) }
            )
        }
    }
}

@Composable
private fun SolidAccentCircle(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val checkColor = if (color.luminance() > 0.55f) Color(0xFF141414) else Color.White
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = checkColor,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

