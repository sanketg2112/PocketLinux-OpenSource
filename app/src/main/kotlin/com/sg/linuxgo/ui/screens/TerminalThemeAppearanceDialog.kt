package com.sg.linuxgo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.components.TERMINAL_FONT_WIDTH_MAX
import com.sg.linuxgo.ui.components.TERMINAL_FONT_WIDTH_MIN
import com.sg.linuxgo.ui.utils.performClickHaptic
import com.sg.linuxgo.ui.theme.TerminalFontOption
import kotlinx.coroutines.withTimeoutOrNull

private val PanelBackground = Color(0xFF1E1E28)
private val PanelAccent = Color(0xFF78BCA3)
private val PanelMuted = Color(0xFFA0A0A0)
private val PanelTrack = Color(0xFF44444A)

@Composable
fun TerminalThemeAppearanceDialog(
    terminalTheme: String,
    terminalFontFamily: String,
    terminalFontSize: Float,
    terminalFontBoldness: Float = 0f,
    terminalFontWidth: Float = 1f,
    themeSource: String = "match_gui",
    matchGuiActive: Boolean = false,
    onDismiss: () -> Unit,
    onThemeSelected: (String) -> Unit,
    onFontFamilySelected: (String) -> Unit,
    onFontSizeSelected: (Float) -> Unit,
    onFontBoldnessSelected: (Float) -> Unit = {},
    onFontWidthSelected: (Float) -> Unit = {},
    onThemeSourceSelected: (String) -> Unit = {}
) {
    var fontDropdownExpanded by remember { mutableStateOf(false) }
    var isPreviewing by remember { mutableStateOf(false) }
    val viewConfiguration = LocalViewConfiguration.current
    val matchGui = themeSource == "match_gui"

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (isPreviewing) 0f else 0.5f))
            .clickable(enabled = !isPreviewing) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        val statusPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val maxSheetHeight = (maxHeight - statusPad).coerceAtLeast(200.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .alpha(if (isPreviewing) 0f else 1f)
                .focusProperties { canFocus = false }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = PanelBackground,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF3A3A42))
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Terminal Appearance",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeSourceSection(
                        matchGui = matchGui,
                        matchGuiActive = matchGuiActive,
                        onThemeSourceSelected = onThemeSourceSelected
                    )

                    FontFamilySection(
                        matchGui = matchGui,
                        terminalFontFamily = terminalFontFamily,
                        fontDropdownExpanded = fontDropdownExpanded,
                        onExpandDropdown = { fontDropdownExpanded = true },
                        onDismissDropdown = { fontDropdownExpanded = false },
                        onFontFamilySelected = { id ->
                            onFontFamilySelected(id)
                            fontDropdownExpanded = false
                        }
                    )

                    CompactFontSlider(
                        label = "Size",
                        valueLabel = "${terminalFontSize.toInt()} sp",
                        value = terminalFontSize,
                        valueRange = 4f..30f,
                        steps = 26,
                        hapticOnIntChange = true,
                        onValueChange = onFontSizeSelected
                    )
                    CompactFontSlider(
                        label = "Boldness",
                        valueLabel = when {
                            terminalFontBoldness < 0.2f -> "Reg"
                            terminalFontBoldness < 0.55f -> "Med"
                            else -> "Bold"
                        },
                        value = terminalFontBoldness,
                        valueRange = 0f..1f,
                        onValueChange = onFontBoldnessSelected
                    )
                    CompactFontSlider(
                        label = "Width",
                        valueLabel = String.format("%.0f%%", terminalFontWidth * 100f),
                        value = terminalFontWidth,
                        valueRange = TERMINAL_FONT_WIDTH_MIN..TERMINAL_FONT_WIDTH_MAX,
                        onValueChange = onFontWidthSelected
                    )
                    Text(
                        "Pinch the terminal to change size quickly.",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp
                    )
                }

                if (!matchGui) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Terminal Theme",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Note: long-press a theme to preview it on the terminal behind this panel. Tap to select.",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(top = 6.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TerminalTheme.values().forEach { theme ->
                            ThemeChoiceRow(
                                theme = theme,
                                selected = theme.id == terminalTheme,
                                viewConfiguration = viewConfiguration,
                                onThemeSelected = onThemeSelected,
                                onPreviewingChanged = { isPreviewing = it }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Done", color = PanelMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSourceSection(
    matchGui: Boolean,
    matchGuiActive: Boolean,
    onThemeSourceSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Theme source",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF2C2C2C))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ThemeSourceChip(
                label = "GUI terminal",
                selected = matchGui,
                modifier = Modifier.weight(1f),
                onClick = { onThemeSourceSelected("match_gui") }
            )
            ThemeSourceChip(
                label = "App theme",
                selected = !matchGui,
                modifier = Modifier.weight(1f),
                onClick = { onThemeSourceSelected("app") }
            )
        }
        Text(
            text = if (matchGui) {
                if (matchGuiActive) {
                    "Using xfce4-terminal colors, font, and cursor from the Linux desktop (terminalrc or xfconf). The same guest shell starts as on the desktop, so bash / zsh / fish, starship, and oh-my load from the container home."
                } else {
                    "GUI terminal is on. Open the desktop terminal once and set colors/fonts there — they apply here. bashrc / starship / oh-my still load from the guest home."
                }
            } else {
                "Using PocketLinux color presets below. The guest shell still loads bashrc, starship, and other prompt themes from the container."
            },
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun FontFamilySection(
    matchGui: Boolean,
    terminalFontFamily: String,
    fontDropdownExpanded: Boolean,
    onExpandDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    onFontFamilySelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (matchGui) "Fallback" else "Font",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(68.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                onClick = onExpandDropdown,
                color = Color(0xFF2A2A34),
                border = BorderStroke(1.dp, PanelTrack),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = TerminalFontOption.fromId(terminalFontFamily).displayName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
            DropdownMenu(
                expanded = fontDropdownExpanded,
                onDismissRequest = onDismissDropdown,
                modifier = Modifier
                    .background(PanelBackground)
                    .border(1.dp, PanelTrack, RoundedCornerShape(8.dp))
            ) {
                val context = LocalContext.current
                TerminalFontOption.values().filter { it.isListed(context) }.forEach { font ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                font.displayName,
                                color = if (terminalFontFamily == font.id) PanelAccent else Color.White,
                                fontWeight = if (terminalFontFamily == font.id) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = { onFontFamilySelected(font.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactFontSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    hapticOnIntChange: Boolean = false,
    onValueChange: (Float) -> Unit
) {
    val view = LocalView.current
    var lastInt by remember { mutableIntStateOf(value.toInt()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .focusProperties { canFocus = false },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(68.dp)
        )
        Slider(
            value = value,
            onValueChange = { newVal ->
                if (hapticOnIntChange && newVal.toInt() != lastInt) {
                    lastInt = newVal.toInt()
                    view.performClickHaptic()
                }
                onValueChange(newVal)
            },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .focusProperties { canFocus = false },
            colors = SliderDefaults.colors(
                thumbColor = PanelAccent,
                activeTrackColor = PanelAccent,
                inactiveTrackColor = PanelTrack
            )
        )
        Text(
            valueLabel,
            color = PanelAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp)
        )
    }
}

@Composable
private fun ThemeChoiceRow(
    theme: TerminalTheme,
    selected: Boolean,
    viewConfiguration: androidx.compose.ui.platform.ViewConfiguration,
    onThemeSelected: (String) -> Unit,
    onPreviewingChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF2A2A34) else Color.Transparent)
            .pointerInput(theme.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    val touchSlop = viewConfiguration.touchSlop
                    val pointerId = down.id
                    val downPos = down.position

                    val earlyEnd = withTimeoutOrNull(longPressTimeout) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: return@withTimeoutOrNull "cancel"
                            if (!change.pressed) {
                                return@withTimeoutOrNull "up"
                            }
                            if ((change.position - downPos).getDistance() > touchSlop) {
                                return@withTimeoutOrNull "scroll"
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        "cancel"
                    }

                    when (earlyEnd) {
                        null -> {
                            onThemeSelected(theme.id)
                            onPreviewingChanged(true)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                    if (change == null || !change.pressed) break
                                }
                            } finally {
                                onPreviewingChanged(false)
                            }
                        }
                        "up" -> {
                            onThemeSelected(theme.id)
                        }
                        else -> {}
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) theme.accentColor else PanelTrack)
            )
            Text(
                text = theme.themeName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(theme.backgroundColor)
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(theme.tabBarColor)
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(theme.accentColor)
            )
        }
    }
}

@Composable
private fun ThemeSourceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) Color(0xFF004A77) else Color.Transparent,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected) Color(0xFF78B9FF) else PanelMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
