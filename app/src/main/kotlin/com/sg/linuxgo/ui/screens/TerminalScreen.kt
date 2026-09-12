package com.sg.linuxgo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import com.sg.linuxgo.terminalImeBottomSpace
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import com.sg.linuxgo.ui.utils.performClickHaptic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.SnippetPromptCoordinator
import com.sg.linuxgo.SnippetPromptState
import com.sg.linuxgo.ui.components.SnippetVariablePromptDialog
import com.sg.linuxgo.TerminalClipboard
import com.sg.linuxgo.ui.components.TerminalExtraKeysBar
import com.sg.linuxgo.ui.components.TerminalGridPane
import com.sg.linuxgo.ui.components.TerminalSnippetSheet
import com.sg.linuxgo.ui.components.measureTerminalCellMetrics
import com.sg.linuxgo.ui.components.terminalCapturesTouch

enum class TerminalTheme(
    val id: String,
    val themeName: String,
    val backgroundColor: Color,
    val tabBarColor: Color,
    val textColor: Color,
    val accentColor: Color
) {
    // Brand default: classic black canvas + muted steel-teal accent (calm, readable, distinct from Nord).
    DEFAULT("default", "Terminon Night", Color(0xFF0A0C0F), Color(0xFF050608), Color(0xFFE5E9F0), Color(0xFF6EB0BA)),
    // Server-console palette — pure black surfaces + lime accent (#4ADE80).
    // Inspired by Podroid’s UI colors (https://github.com/ExTV/Podroid); display name is independent.
    PODROID("podroid", "Server Console", Color(0xFF0A0A0A), Color(0xFF141414), Color(0xFFEDEDED), Color(0xFF4ADE80)),
    DRACULA("dracula", "Dracula", Color(0xFF282A36), Color(0xFF1E1F29), Color(0xFFF8F8F2), Color(0xFFBD93F9)),
    SOLARIZED_DARK("solarized_dark", "Solarized Dark", Color(0xFF002B36), Color(0xFF073642), Color(0xFF839496), Color(0xFF2AA198)),
    MONOKAI("monokai", "Monokai", Color(0xFF272822), Color(0xFF1E1F1C), Color(0xFFF8F8F2), Color(0xFFA6E22E)),
    RETRO_GREEN("retro_green", "Retro Green", Color(0xFF000000), Color(0xFF1A1A1A), Color(0xFF00FF00), Color(0xFF00FF00)),
    LIGHT("light", "Light Accent", Color(0xFFF6F6F9), Color(0xFFEAEAEE), Color(0xFF1C1C1E), Color(0xFF2D88FF)),
    NORD("nord", "Nord", Color(0xFF2E3440), Color(0xFF242933), Color(0xFFD8DEE9), Color(0xFF88C0D0)),
    ONE_DARK("one_dark", "One Dark", Color(0xFF282C34), Color(0xFF21252B), Color(0xFFABB2BF), Color(0xFF61AFEF)),
    GRUVBOX("gruvbox", "Gruvbox", Color(0xFF282828), Color(0xFF1D2021), Color(0xFFEBDBB2), Color(0xFFFE8019)),
    AURA("aura", "Aura Purple", Color(0xFF15141B), Color(0xFF111015), Color(0xFFEDECEE), Color(0xFFA277FF)),
    CYBERPUNK("cyberpunk", "Cyberpunk", Color(0xFF0F0F1A), Color(0xFF080811), Color(0xFF00FFCC), Color(0xFFFF007F)),
    SUNSET("sunset", "Sunset Glow", Color(0xFF1E121E), Color(0xFF140C14), Color(0xFFFBE4FF), Color(0xFFFF5E7E)),
    DEEP_OCEAN("deep_ocean", "Deep Ocean", Color(0xFF0B132B), Color(0xFF070B19), Color(0xFFE2E8F0), Color(0xFF48CAE4)),
    FOREST_MOSS("forest_moss", "Forest Moss", Color(0xFF131E17), Color(0xFF0D1410), Color(0xFFE8F5E9), Color(0xFF81C784)),
    TOKYO_NIGHT("tokyo_night", "Tokyo Night", Color(0xFF1A1B26), Color(0xFF16161E), Color(0xFFA9B1D6), Color(0xFF7AA2F7)),
    ROSE_PINE("rose_pine", "Rosé Pine", Color(0xFF191724), Color(0xFF12101A), Color(0xFFE0DEF4), Color(0xFFEBBCB2)),
    SYNTHWAVE("synthwave", "Synthwave", Color(0xFF2B213A), Color(0xFF20162B), Color(0xFFFFFFFF), Color(0xFFF92AAD)),
    ESPRESSO("espresso", "Espresso", Color(0xFF2D2424), Color(0xFF211A1A), Color(0xFFEFE8E8), Color(0xFFE0A96D)),
    CYBER_LIME("cyber_lime", "Cyber Lime", Color(0xFF050805), Color(0xFF000000), Color(0xFFEEFFEE), Color(0xFFCCFF00)),
    ICEBERG("iceberg", "Iceberg", Color(0xFF161821), Color(0xFF0F1117), Color(0xFFD2D4DE), Color(0xFF84A0C6));

    val isLight: Boolean
        get() = backgroundColor.luminance() > 0.5f

    /**
     * Lifted surface for cards / panels.
     * Uses a neutral white/black lift so cards clearly separate from the page,
     * then blends a little theme ink so surfaces stay on-palette.
     */
    val elevatedColor: Color
        get() {
            val neutral = if (isLight) {
                lerp(backgroundColor, Color.Black, 0.07f)
            } else {
                lerp(backgroundColor, Color.White, 0.12f)
            }
            return lerp(neutral, textColor, if (isLight) 0.03f else 0.06f)
        }

    /**
     * Primary ink for app chrome (hosts, settings, dialogs).
     * Keeps the theme’s hue family but boosts contrast when the terminal
     * [textColor] is intentionally soft (e.g. Solarized).
     * Terminal rendering continues to use [textColor] directly.
     */
    val uiTextColor: Color
        get() {
            val lum = textColor.luminance()
            return if (isLight) {
                // Soft greys → push toward black for readable labels.
                if (lum > 0.18f) lerp(textColor, Color.Black, 0.32f) else textColor
            } else {
                // Dim terminal greys → push toward white for readable UI labels.
                if (lum < 0.72f) lerp(textColor, Color.White, 0.38f) else textColor
            }
        }

    /** Secondary / meta text — muted but still legible on [backgroundColor] and [elevatedColor]. */
    val mutedTextColor: Color
        get() = lerp(uiTextColor, backgroundColor, 0.26f)

    /**
     * Darker inset within elevated cards (session rows, nested strips).
     * Distinct from both the page background and the card face.
     */
    val insetColor: Color
        get() = if (isLight) {
            lerp(elevatedColor, Color.Black, 0.05f)
        } else {
            lerp(elevatedColor, Color.Black, 0.28f)
        }

    /** Borders that read as edges without competing with content. */
    val borderColor: Color
        get() = if (isLight) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.14f)

    /** Readable ink on top of [accentColor] buttons. */
    val onAccentColor: Color
        get() = if (accentColor.luminance() > 0.55f) Color(0xFF121416) else Color(0xFFF6F6F9)

    fun toColorScheme(): ColorScheme {
        val onAccent = onAccentColor
        val onBg = uiTextColor
        // Fixed warning amber (not theme-dependent) so alerts stay readable on every palette.
        val warning = Color(0xFFD9A760)
        return if (isLight) {
            lightColorScheme(
                primary = accentColor,
                onPrimary = onAccent,
                secondary = accentColor,
                onSecondary = onAccent,
                tertiary = warning,
                onTertiary = Color(0xFF121416),
                background = backgroundColor,
                onBackground = onBg,
                surface = tabBarColor,
                onSurface = onBg,
                surfaceVariant = elevatedColor,
                onSurfaceVariant = mutedTextColor,
                outline = borderColor
            )
        } else {
            darkColorScheme(
                primary = accentColor,
                onPrimary = onAccent,
                secondary = accentColor,
                onSecondary = onAccent,
                tertiary = warning,
                onTertiary = Color(0xFF121416),
                background = backgroundColor,
                onBackground = onBg,
                surface = tabBarColor,
                onSurface = onBg,
                surfaceVariant = elevatedColor,
                onSurfaceVariant = mutedTextColor,
                outline = borderColor
            )
        }
    }

    companion object {
        fun fromId(id: String): TerminalTheme {
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    tabs: List<String> = emptyList(),
    activeTabIndex: Int = 0,
    /** @deprecated TextView path removed; kept for call-site compatibility. */
    terminalScrollView: android.view.View? = null,
    terminalInputView: android.view.View? = null,
    /** Terminon-style per-line grid content (empty when no session). */
    terminalLines: List<AnnotatedString> = emptyList(),
    cursorX: Int = 0,
    cursorLine: Int = 0,
    cursorVisible: Boolean = true,
    isAlternateBuffer: Boolean = false,
    isMouseReportingEnabled: Boolean = false,
    renderGeneration: Int = 0,
    /** Left of Tab 1: back arrow + Home — detach to home without killing sessions. */
    onGoHome: () -> Unit = {},
    onNewSession: () -> Unit = {},
    onCloseSession: () -> Unit = {},
    onCloseSessionAt: (Int) -> Unit = {},
    onDuplicateSession: (Int) -> Unit = {},
    onRenameSession: (Int, String) -> Unit = { _, _ -> },
    onTabSelected: (Int) -> Unit = {},
    onKeyPressed: (keyName: String) -> Unit = {},
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    shiftActive: Boolean = false,
    terminalTheme: String = "default",
    onThemeSelected: (String) -> Unit = {},
    terminalFontFamily: String = "meslo",
    terminalFontSize: Float = 12f,
    onFontFamilySelected: (String) -> Unit = {},
    onFontSizeSelected: (Float) -> Unit = {},
    /** 0 = regular … 1 = bold. */
    terminalFontBoldness: Float = 0f,
    onFontBoldnessSelected: (Float) -> Unit = {},
    /** Horizontal glyph/cell scale (0.30–1.4, default 1). */
    terminalFontWidth: Float = 1f,
    onFontWidthSelected: (Float) -> Unit = {},
    /** match_gui (default) or app — drives appearance dialog. */
    terminalThemeSource: String = "match_gui",
    onThemeSourceSelected: (String) -> Unit = {},
    /** When Match GUI resolved a guest palette, override canvas colors. */
    schemeBackgroundArgb: Int? = null,
    schemeTextArgb: Int? = null,
    schemeCursorArgb: Int? = null,
    matchGuiActive: Boolean = false,
    /** Absolute path to guest .ttf/.otf when Match GUI found a Nerd Font. */
    guestFontPath: String? = null,
    onRequestKeyboard: () -> Unit = {},
    onHideKeyboard: () -> Unit = {},
    onSetImeSuppressed: (Boolean) -> Unit = {},
    onGridSizeChanged: (rows: Int, cols: Int, charWidthPx: Int, charHeightPx: Int) -> Unit = { _, _, _, _ -> },
    onMouseEvent: (col: Int, row: Int, button: Int, isRelease: Boolean, isMotion: Boolean) -> Unit =
        { _, _, _, _, _ -> },
    onScrollSteps: (steps: Int, col: Int, row: Int) -> Unit = { _, _, _ -> },
    /** Committed PTY rows from the live session (0 = unknown). */
    screenRows: Int = 0,
    /** Committed PTY cols from the live session (0 = unknown). */
    screenCols: Int = 0
) {
    val tabListState = rememberLazyListState()
    val gridListState = rememberLazyListState()
    val baseTheme = remember(terminalTheme) { TerminalTheme.fromId(terminalTheme) }
    val canvasBackground = schemeBackgroundArgb?.let { Color(it) } ?: baseTheme.backgroundColor
    val canvasText = schemeTextArgb?.let { Color(it) } ?: baseTheme.textColor
    val canvasCursor = schemeCursorArgb?.let { Color(it) } ?: baseTheme.accentColor
    // Tabs + status-bar chrome use the same fill as the canvas (no darker strip).
    val canvasTabBar = canvasBackground
    var themeDialogOpen by remember { mutableStateOf(false) }
    var snippetDialogOpen by remember { mutableStateOf(false) }
    var activeSnippetPrompt by remember { mutableStateOf<SnippetPromptState?>(null) }
    val snippetCoordinator = remember {
        SnippetPromptCoordinator(
            injectCommand = { cmd ->
                cmd.forEach { char -> onKeyPressed(char.toString()) }
            }
        )
    }
    var isPreviewing by remember { mutableStateOf(false) }
    var showExpandedOverlay by remember { mutableStateOf(false) }
    var softKeyboardDesired by remember { mutableStateOf(false) }
    var holdKeybarBottom by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val currentImeHeightDp = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarHeightDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var lastImeHeightDp by remember { mutableStateOf(260.dp) }
    LaunchedEffect(currentImeHeightDp, holdKeybarBottom, showExpandedOverlay) {
        if (holdKeybarBottom) {
            if (currentImeHeightDp >= lastImeHeightDp - 4.dp) {
                lastImeHeightDp = currentImeHeightDp
                holdKeybarBottom = false
            }
        } else if (currentImeHeightDp > 100.dp && !showExpandedOverlay) {
            lastImeHeightDp = currentImeHeightDp
        }
    }
    LaunchedEffect(holdKeybarBottom) {
        if (holdKeybarBottom) {
            kotlinx.coroutines.delay(1500)
            holdKeybarBottom = false
        }
    }
    var wasImeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (wasImeVisible && !imeVisible) {
            kotlinx.coroutines.delay(80)
            softKeyboardDesired = false
            holdKeybarBottom = false
        }
        wasImeVisible = imeVisible
    }
    val overlaysBlockIme = themeDialogOpen || snippetDialogOpen
    DisposableEffect(overlaysBlockIme) {
        onSetImeSuppressed(overlaysBlockIme)
        onDispose {
            if (overlaysBlockIme) onSetImeSuppressed(false)
        }
    }

    val fontSizePx = with(density) { terminalFontSize.sp.toPx() }
    val cellMetrics = remember(
        terminalFontFamily,
        fontSizePx,
        context,
        guestFontPath,
        terminalFontBoldness,
        terminalFontWidth
    ) {
        measureTerminalCellMetrics(
            context = context,
            fontId = terminalFontFamily,
            fontSizePx = fontSizePx,
            fontFilePath = guestFontPath,
            fontBoldness = terminalFontBoldness,
            fontWidthScale = terminalFontWidth
        )
    }
    val viewConfiguration = LocalViewConfiguration.current
    val capturesTouch = terminalCapturesTouch(isMouseReportingEnabled, isAlternateBuffer)

    LaunchedEffect(tabs.size, activeTabIndex) {
        if (tabs.isNotEmpty() && activeTabIndex in tabs.indices) {
            tabListState.animateScrollToItem(activeTabIndex)
        }
    }

    val imeBottomSpace = with(density) {
        terminalImeBottomSpace(
            showExpandedOverlay = showExpandedOverlay,
            holdKeybarBottom = holdKeybarBottom,
            lastImeHeight = lastImeHeightDp.toPx(),
            currentImeHeight = currentImeHeightDp.toPx(),
            navBarHeight = navBarHeightDp.toPx()
        ).toDp()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(canvasBackground)
        ) {
            // Tab Bar: Home (left of Tab 1) + session pills + chrome actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(canvasTabBar)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home — back arrow + label; keeps terminal sessions running.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            view.performClickHaptic()
                            onHideKeyboard()
                            keyboardController?.hide()
                            onGoHome()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Home",
                        tint = canvasText.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Home",
                        color = canvasText.copy(alpha = 0.85f),
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Scrollable pill tabs
                LazyRow(
                    state = tabListState,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(tabs) { index, title ->
                        val isSelected = activeTabIndex == index
                        val bgColor = if (isSelected) canvasCursor.copy(alpha = 0.2f) else canvasTabBar
                        val textColor = if (isSelected) canvasCursor else canvasText.copy(alpha = 0.6f)

                        Box(contentAlignment = Alignment.CenterEnd) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bgColor)
                                    .clickable {
                                        view.performClickHaptic()
                                        onTabSelected(index)
                                    }
                                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = title,
                                    color = textColor,
                                    fontFamily = FontFamily.Default,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )

                                var menuExpanded by remember { mutableStateOf(false) }
                                var renaming by remember { mutableStateOf(false) }
                                var renameText by remember { mutableStateOf(title) }

                                IconButton(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = textColor.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = {
                                            view.performClickHaptic()
                                            menuExpanded = false
                                            renameText = title
                                            renaming = true
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = null,
                                                tint = canvasText.copy(alpha = 0.8f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Duplicate") },
                                        onClick = {
                                            view.performClickHaptic()
                                            menuExpanded = false
                                            onDuplicateSession(index)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = null,
                                                tint = canvasText.copy(alpha = 0.8f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Change Theme") },
                                        onClick = {
                                            view.performClickHaptic()
                                            menuExpanded = false
                                            onSetImeSuppressed(true)
                                            themeDialogOpen = true
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Palette,
                                                contentDescription = null,
                                                tint = canvasText.copy(alpha = 0.8f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Close") },
                                        onClick = {
                                            view.performClickHaptic()
                                            menuExpanded = false
                                            onCloseSessionAt(index)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = null,
                                                tint = canvasText.copy(alpha = 0.8f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }

                                // Rename dialog
                                if (renaming) {
                                    RenameDialog(
                                        initialName = renameText,
                                        theme = baseTheme,
                                        onConfirm = { newName ->
                                            renaming = false
                                            onRenameSession(index, newName)
                                        },
                                        onDismiss = { renaming = false }
                                    )
                                }
                            }
                        }
                    }
                }

                // Add Session Button
                IconButton(
                    onClick = {
                        view.performClickHaptic()
                        onNewSession()
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Session",
                        tint = canvasCursor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            TerminalGridPane(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(canvasBackground),
                terminalLines = terminalLines,
                cursorX = cursorX,
                cursorLine = cursorLine,
                cursorVisible = cursorVisible,
                isAlternateBuffer = isAlternateBuffer,
                isMouseReportingEnabled = isMouseReportingEnabled,
                renderGeneration = renderGeneration,
                cellMetrics = cellMetrics,
                canvasBackground = canvasBackground,
                canvasText = canvasText,
                canvasCursor = canvasCursor,
                terminalFontSize = terminalFontSize,
                terminalInputView = terminalInputView,
                suppressIme = themeDialogOpen || snippetDialogOpen,
                gridListState = gridListState,
                viewConfiguration = viewConfiguration,
                capturesTouch = capturesTouch,
                onGridSizeChanged = onGridSizeChanged,
                onMouseEvent = onMouseEvent,
                onScrollSteps = onScrollSteps,
                onFontSizeSelected = onFontSizeSelected,
                onPaste = {
                    val payload = TerminalClipboard.pastePayload(context, false)
                    if (payload.isNotEmpty()) onKeyPressed(payload)
                },
                screenRows = screenRows,
                screenCols = screenCols
            )

            TerminalExtraKeysBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(canvasBackground),
                onKeyPressed = { keyName ->
                    when (keyName) {
                        "SETTINGS_ACTION" -> {
                            onSetImeSuppressed(true)
                            themeDialogOpen = true
                        }
                        "SNIPPET_ACTION", "MACRO_ACTION" -> snippetDialogOpen = true
                        else -> onKeyPressed(keyName)
                    }
                },
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
                isKeyboardVisible = imeVisible,
                softKeyboardDesired = softKeyboardDesired,
                showExpandedOverlay = showExpandedOverlay,
                expandedPanelHeight = lastImeHeightDp.coerceAtLeast(180.dp),
                onExpandedOverlayChanged = { expanded ->
                    if (expanded && currentImeHeightDp > 100.dp) {
                        lastImeHeightDp = currentImeHeightDp
                    }
                    showExpandedOverlay = expanded
                },
                onShowKeyboard = {
                    if (showExpandedOverlay) {
                        holdKeybarBottom = true
                        showExpandedOverlay = false
                    }
                    softKeyboardDesired = true
                    onRequestKeyboard()
                    keyboardController?.show()
                },
                onHideKeyboard = {
                    softKeyboardDesired = false
                    holdKeybarBottom = false
                    onHideKeyboard()
                    keyboardController?.hide()
                },
                accentColor = canvasCursor,
                textColor = canvasText.copy(alpha = 0.75f),
                backgroundColor = canvasBackground
            )

            if (imeBottomSpace > 0.dp) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(imeBottomSpace)
                        .background(canvasBackground)
                )
            }
        }

        if (themeDialogOpen) {
            TerminalThemeAppearanceDialog(
                terminalTheme = terminalTheme,
                terminalFontFamily = terminalFontFamily,
                terminalFontSize = terminalFontSize,
                terminalFontBoldness = terminalFontBoldness,
                terminalFontWidth = terminalFontWidth,
                themeSource = terminalThemeSource,
                matchGuiActive = matchGuiActive,
                onDismiss = { themeDialogOpen = false },
                onThemeSelected = onThemeSelected,
                onFontFamilySelected = onFontFamilySelected,
                onFontSizeSelected = onFontSizeSelected,
                onFontBoldnessSelected = onFontBoldnessSelected,
                onFontWidthSelected = onFontWidthSelected,
                onThemeSourceSelected = onThemeSourceSelected
            )
        }
        if (snippetDialogOpen) {
            TerminalSnippetSheet(
                onDismiss = { snippetDialogOpen = false },
                onExecuteSnippet = { snippet ->
                    snippetCoordinator.executeSnippet(snippet)
                    activeSnippetPrompt = snippetCoordinator.activeSnippetPrompt.value
                },
                accentColor = canvasCursor,
                textColor = canvasText.copy(alpha = 0.75f),
                backgroundColor = canvasBackground
            )
        }

        activeSnippetPrompt?.let { promptState ->
            SnippetVariablePromptDialog(
                promptState = promptState,
                onDismiss = {
                    snippetCoordinator.cancelSnippetPrompt()
                    activeSnippetPrompt = null
                },
                onSubmit = { values ->
                    snippetCoordinator.submitSnippetVariables(values)
                    activeSnippetPrompt = null
                },
                accentColor = canvasCursor,
                textColor = canvasText.copy(alpha = 0.75f),
                backgroundColor = canvasBackground
            )
        }
    }
}
