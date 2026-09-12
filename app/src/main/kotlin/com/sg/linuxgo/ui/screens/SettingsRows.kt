package com.sg.linuxgo.ui.screens

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
import androidx.compose.ui.platform.LocalView
import com.sg.linuxgo.ui.utils.performClickHaptic
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.R
import com.sg.linuxgo.ui.theme.*

/** Subtle row separators: soft in light mode, very muted in dark mode. */
internal fun settingsRowDividerColor(isDarkTheme: Boolean): Color =
    if (isDarkTheme) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f)

@Composable
internal fun SettingsSectionLabel(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.6.sp,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp, top = 4.dp)
    )
}

@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    isDarkTheme: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = title,
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else {
                Text(
                    text = "›",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = settingsRowDividerColor(isDarkTheme)
            )
        }
    }
}

@Composable
internal fun PermissionRowItem(
    title: String,
    subtitle: String,
    granted: Boolean,
    onGrantClick: () -> Unit,
    onRevokeClick: () -> Unit,
    accentColor: Color,
    showDivider: Boolean = false,
    isDarkTheme: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp)
            ) {
                Text(
                    text = title,
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            if (granted) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor)
                        .clickable { onRevokeClick() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ON",
                        fontFamily = FontFamily.Default,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                        .clickable { onGrantClick() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "GRANT",
                        fontFamily = FontFamily.Default,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = settingsRowDividerColor(isDarkTheme)
            )
        }
    }
}

/** @deprecated Prefer [SettingsNavRow] for compact settings lists. Kept for any external callers. */
@Composable
internal fun CategoryConfigCard(
    title: String,
    description: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    SettingsGroupCard {
        SettingsNavRow(
            title = title,
            subtitle = description,
            onClick = onClick,
            showDivider = false
        )
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true,
    isDarkTheme: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .clickable { onCheckedChange(!checked) }
            ) {
                Text(
                    text = title,
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            val view = LocalView.current
            Switch(
                checked = checked,
                onCheckedChange = { newChecked ->
                    view.performClickHaptic()
                    onCheckedChange(newChecked)
                }
            )
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = settingsRowDividerColor(isDarkTheme)
            )
        }
    }
}

@Composable
internal fun SwitchSettingRow(
    title: String,
    summary: String? = null,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfoClick: (() -> Unit)? = null,
    isDarkTheme: Boolean
) {
    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) DividerDark else DividerLight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = textPrimaryColor
                )
                if (description != null && onInfoClick != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = onInfoClick,
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = "Info $title",
                            tint = textSecondaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (summary != null) {
                Text(
                    text = summary,
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    color = textSecondaryColor,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        val view = LocalView.current
        Switch(
            checked = checked,
            onCheckedChange = { newChecked ->
                view.performClickHaptic()
                onCheckedChange(newChecked)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextWhite,
                checkedTrackColor = Magenta,
                uncheckedThumbColor = textSecondaryColor,
                uncheckedTrackColor = dividerColor,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
internal fun SliderSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onInfoClick: () -> Unit,
    isDarkTheme: Boolean
) {
    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) DividerDark else DividerLight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.Default,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondaryColor,
                letterSpacing = 0.05.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(16.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_info),
                    contentDescription = "Info $title",
                    tint = textSecondaryColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${value.toInt()}%",
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimaryColor
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        val view = LocalView.current
        var lastIntVal by remember { mutableIntStateOf(value.toInt()) }

        Slider(
            value = value,
            onValueChange = { newVal ->
                if (newVal.toInt() != lastIntVal) {
                    lastIntVal = newVal.toInt()
                    view.performClickHaptic()
                }
                onValueChange(newVal)
            },
            onValueChangeFinished = {
                view.performClickHaptic()
                onValueChangeFinished()
            },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Magenta,
                activeTrackColor = Magenta,
                inactiveTrackColor = dividerColor
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
