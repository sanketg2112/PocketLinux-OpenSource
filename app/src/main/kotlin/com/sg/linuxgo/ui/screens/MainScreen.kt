package com.sg.linuxgo.ui.screens

import android.content.Context
import androidx.preference.PreferenceManager
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sg.linuxgo.ContainerConfig
import com.sg.linuxgo.FeatureGates
import com.sg.linuxgo.MainViewModel
import com.sg.linuxgo.R
import com.sg.linuxgo.Screen
import com.sg.linuxgo.TerminalSession
import com.sg.linuxgo.ui.components.ContainerCardsGrid
import com.sg.linuxgo.ui.components.clampFontWidthScale
import com.sg.linuxgo.ui.onboarding.DistroPackageTips
import com.sg.linuxgo.ui.onboarding.DistroTipsChooserSheet
import com.sg.linuxgo.ui.onboarding.DistroTipsSheet
import com.sg.linuxgo.ui.onboarding.LearnMoreScreen
import com.sg.linuxgo.ui.onboarding.OnboardingScreen
import com.sg.linuxgo.ui.sheets.ContainerSettingsSheet
import com.sg.linuxgo.ui.sheets.FeedbackAndSuggestionsSheet
import com.sg.linuxgo.ui.sheets.GlobalSettingsSheet
import com.sg.linuxgo.ui.sheets.NewContainerSheet
import com.sg.linuxgo.ui.theme.PocketLinuxTheme
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.utils.performClickHaptic
import com.sg.linuxgo.util.HomeFeedbackStripPrefs
import com.sg.linuxgo.x11.LorieView

/**
 * Root Compose layout for [com.sg.linuxgo.MainActivity].
 * Owns theme observation, tab content, bottom navigation, and sheet overlays.
 */
data class MainScreenActions(
    val onAddContainer: () -> Unit,
    val onLaunchGui: (ContainerConfig) -> Unit,
    val onLaunchShell: (ContainerConfig) -> Unit,
    val onInstall: (ContainerConfig) -> Unit,
    val onSettings: (ContainerConfig) -> Unit,
    /** Open install/container logs. Pass container id when returning to settings on back. */
    val onViewLogs: (returnToContainerId: String?) -> Unit,
    val onTerminate: (ContainerConfig) -> Unit,
    val onAbortInstall: (ContainerConfig) -> Unit,
    /** Card menu delete — shows app confirm dialog. */
    val onDelete: (ContainerConfig) -> Unit,
    /** Settings sheet already confirmed (type “delete”) — delete immediately. */
    val onDeleteConfirmed: (ContainerConfig) -> Unit = onDelete,
    val onBackupClick: () -> Unit,
    val onRestoreClick: () -> Unit,
    val onToggleSetupLogs: () -> Unit,
    val onDownloadSetupLog: () -> Unit,
    val onCopySetupLog: () -> Unit,
    val onKeyboardToggle: () -> Unit,
    val onGoHomeFromGui: () -> Unit,
    /** Terminal tab-bar Home / system back: detach without killing sessions. */
    val onGoHomeFromTerminal: () -> Unit = {},
    val onGuiKeyPressed: (String) -> Unit,
    val onTerminalKeyPressed: (String) -> Unit,
    val onRequestTerminalKeyboard: () -> Unit = {},
    val onHideTerminalKeyboard: () -> Unit = {},
    val onSetTerminalImeSuppressed: (Boolean) -> Unit = {},
    val onTerminalGridSizeChanged: (rows: Int, cols: Int, charWidthPx: Int, charHeightPx: Int) -> Unit =
        { _, _, _, _ -> },
    val onTerminalMouseEvent: (col: Int, row: Int, button: Int, isRelease: Boolean, isMotion: Boolean) -> Unit =
        { _, _, _, _, _ -> },
    val onTerminalScrollSteps: (steps: Int, col: Int, row: Int) -> Unit = { _, _, _ -> },
    val onNewTerminalSession: () -> Unit,
    val onCloseTerminalSession: () -> Unit,
    val onCloseTerminalSessionAt: (Int) -> Unit,
    val onDuplicateTerminalSession: (Int) -> Unit,
    val onRenameTerminalSession: (Int, String) -> Unit,
    val onTerminalTabSelected: (Int) -> Unit,
    val onTerminalThemeSelected: (String) -> Unit,
    val onNavigate: (Screen) -> Unit,
    val onContainerCreated: (ContainerConfig) -> Unit,
    val onContainerWizardDismissed: () -> Unit,
    val onGlobalSettingsSaved: () -> Unit,
    val onContainerSettingsSaved: () -> Unit,
    val onTerminalPrefsChanged: () -> Unit,
    val onStatusBarThemeRefresh: () -> Unit
)

@Composable
fun MainScreen(
    context: Context,
    viewModel: MainViewModel,
    terminalSessions: List<TerminalSession>,
    sessionThemes: MutableMap<String, String>,
    promptContainer: LinearLayout,
    lorieView: LorieView,
    guiImeAnchor: EditText,
    terminalScroll: ScrollView,
    etTerminalInput: EditText,
    actions: MainScreenActions
) {
    val sharedPrefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var appThemeState by remember {
        mutableStateOf(sharedPrefs.getString("pocketlinux_theme", "system") ?: "system")
    }
    var accentHexState by remember {
        mutableStateOf(sharedPrefs.getString("pocketlinux_accent", "#2196F3") ?: "#2196F3")
    }
    var terminalThemeState by remember {
        mutableStateOf(sharedPrefs.getString("terminal_theme", "default") ?: "default")
    }
    var terminalFontFamilyState by remember {
        mutableStateOf(sharedPrefs.getString("terminal_font_family", "meslo") ?: "meslo")
    }
    var terminalFontSizeState by remember {
        mutableFloatStateOf(sharedPrefs.getFloat("terminal_font_size", 12f))
    }
    var terminalFontBoldnessState by remember {
        mutableFloatStateOf(sharedPrefs.getFloat("terminal_font_boldness", 0f))
    }
    var terminalFontWidthState by remember {
        mutableFloatStateOf(sharedPrefs.getFloat("terminal_font_width", 1f))
    }
    var terminalThemeSourceState by remember {
        mutableStateOf(
            sharedPrefs.getString(
                com.sg.linuxgo.GuestTerminalAppearance.PREF_THEME_SOURCE,
                com.sg.linuxgo.GuestTerminalAppearance.SOURCE_MATCH_GUI
            ) ?: com.sg.linuxgo.GuestTerminalAppearance.SOURCE_MATCH_GUI
        )
    }

    DisposableEffect(sharedPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "pocketlinux_theme" -> {
                    appThemeState = sharedPrefs.getString("pocketlinux_theme", "system") ?: "system"
                }
                "pocketlinux_accent" -> {
                    accentHexState = sharedPrefs.getString("pocketlinux_accent", "#2196F3") ?: "#2196F3"
                }
                "terminal_theme" -> {
                    terminalThemeState = sharedPrefs.getString("terminal_theme", "default") ?: "default"
                    actions.onStatusBarThemeRefresh()
                    actions.onTerminalPrefsChanged()
                }
                "terminal_font_family" -> {
                    terminalFontFamilyState =
                        sharedPrefs.getString("terminal_font_family", "meslo") ?: "meslo"
                    actions.onTerminalPrefsChanged()
                }
                "terminal_font_size" -> {
                    terminalFontSizeState = sharedPrefs.getFloat("terminal_font_size", 12f)
                    actions.onTerminalPrefsChanged()
                }
                "terminal_font_boldness" -> {
                    terminalFontBoldnessState = sharedPrefs.getFloat("terminal_font_boldness", 0f)
                    actions.onTerminalPrefsChanged()
                }
                "terminal_font_width" -> {
                    terminalFontWidthState = sharedPrefs.getFloat("terminal_font_width", 1f)
                    actions.onTerminalPrefsChanged()
                }
                com.sg.linuxgo.GuestTerminalAppearance.PREF_THEME_SOURCE -> {
                    terminalThemeSourceState = sharedPrefs.getString(
                        com.sg.linuxgo.GuestTerminalAppearance.PREF_THEME_SOURCE,
                        com.sg.linuxgo.GuestTerminalAppearance.SOURCE_MATCH_GUI
                    ) ?: com.sg.linuxgo.GuestTerminalAppearance.SOURCE_MATCH_GUI
                    actions.onStatusBarThemeRefresh()
                    actions.onTerminalPrefsChanged()
                }
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val isDarkTheme = when (appThemeState) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val accentColor = remember(accentHexState) {
        try {
            Color(android.graphics.Color.parseColor(accentHexState))
        } catch (_: Exception) {
            com.sg.linuxgo.ui.theme.Magenta
        }
    }

    // Force observation of ViewModel state for recomposition.
    val currentScreen = viewModel.currentScreen
    val containerConfigs = viewModel.containerConfigs
    val installingId = viewModel.installingContainerIdState
    val activeId = viewModel.activeContainerIdState
    val isGuiSessionActive = viewModel.isGuiSessionActive
    val isShellSessionActive = viewModel.isShellSessionActive
    val progressMsg = viewModel.installProgressMessageState
    val progressPct = viewModel.installProgressPercentState
    val statsMap = viewModel.containerStatsState
    val setupWelcomeVisible = viewModel.setupWelcomeVisible
    val setupStepDescText = viewModel.setupStepDescText
    val setupStepDescVisible = viewModel.setupStepDescVisible
    val setupProgress = viewModel.setupProgress
    val setupProgressIndeterminate = viewModel.setupProgressIndeterminate
    val setupProgressVisible = viewModel.setupProgressVisible
    val setupProgressStatusText = viewModel.setupProgressStatusText
    val setupProgressStatusVisible = viewModel.setupProgressStatusVisible
    val setupChecklistVisible = viewModel.setupChecklistVisible
    val setupChecklistItems = viewModel.setupChecklistItems
    val setupMetricsVisible = viewModel.setupMetricsVisible
    val setupInstallPath = viewModel.setupInstallPath
    val setupStorageStatus = viewModel.setupStorageStatus
    val setupRestoreSetupVisible = viewModel.setupRestoreSetupVisible
    val setupLogToggleText = viewModel.setupLogToggleText
    val setupLogCardVisible = viewModel.setupLogCardVisible
    val setupIsLogFullscreen = viewModel.setupIsLogFullscreen
    val setupLogText = viewModel.setupLogText
    val guiLoadingVisible = viewModel.guiLoadingVisible
    val guiLoadingStatusText = viewModel.guiLoadingStatusText
    val guiKeyBarVisible = viewModel.guiKeyBarVisible
    val guiKeyBarBottomPaddingDp = viewModel.guiKeyBarBottomPaddingDp
    val terminalActiveTabIndex = viewModel.terminalActiveTabIndex
    val terminalSessionsState = viewModel.terminalSessionsState
    val terminalRenderGeneration = viewModel.terminalRenderGeneration
    val isCtrlActive = viewModel.isCtrlActive
    val isAltActive = viewModel.isAltActive
    val isShiftActive = viewModel.isShiftActive
    val isGuiCtrlActive = viewModel.isGuiCtrlActive
    val isGuiAltActive = viewModel.isGuiAltActive
    val isGuiShiftActive = viewModel.isGuiShiftActive
    val backupRestoreRunningState = viewModel.backupRestoreRunningState
    val backupRestoreProgressState = viewModel.backupRestoreProgressState
    val backupRestoreMessageState = viewModel.backupRestoreMessageState
    val showNewContainerWizardState = viewModel.showNewContainerWizardState
    val showGlobalSettingsState = viewModel.showGlobalSettingsState
    val globalSettingsSectionState = viewModel.globalSettingsSectionState
    val showContainerSettingsForId = viewModel.showContainerSettingsForId
    val logsReturnToContainerId = viewModel.logsReturnToContainerId
    val showOnboardingState = viewModel.showOnboardingState
    val showLearnMoreState = viewModel.showLearnMoreState
    var coverBottomNav by remember { mutableStateOf(false) }
    val showExperimentalSettingsState = viewModel.showExperimentalSettingsState
    val showDistroTipsForDistro = viewModel.showDistroTipsForDistro
    val showDistroTipsChooserState = viewModel.showDistroTipsChooserState

    // Bumps when tips are dismissed so the bottom tips bar hides permanently.
    var packageTipsHintEpoch by remember { mutableStateOf(0) }
    val packageTipsHintsDismissed = remember(packageTipsHintEpoch, sharedPrefs) {
        DistroPackageTips.areHintsDismissed(sharedPrefs)
    }
    // Home feedback strip: after tips are gone + first user-stopped session.
    val homeFeedbackStripEpoch = viewModel.homeFeedbackStripEpoch
    val showHomeFeedbackStrip = remember(
        packageTipsHintsDismissed,
        homeFeedbackStripEpoch,
        sharedPrefs
    ) {
        HomeFeedbackStripPrefs.shouldShow(
            tipsHintsDismissed = packageTipsHintsDismissed,
            prefs = sharedPrefs
        )
    }
    var showHomeFeedbackSheet by remember { mutableStateOf(false) }

    // Onboarding flag is set in MainViewModel.init from prefs (no LaunchedEffect flash).

    fun completeOnboarding() {
        sharedPrefs.edit().putBoolean(DistroPackageTips.PREF_ONBOARDING_COMPLETED, true).apply()
        viewModel.showOnboardingState = false
    }

    fun dismissPackageTips(distroId: String) {
        DistroPackageTips.markTipsDismissed(sharedPrefs, distroId)
        packageTipsHintEpoch++
        viewModel.showDistroTipsForDistro = null
        viewModel.distroTipsTerminalContainerId = null
    }

    // System back from logs: restore container settings when logs were opened from there.
    BackHandler(enabled = currentScreen == Screen.SETUP) {
        val returnId = viewModel.logsReturnToContainerId
        viewModel.logsReturnToContainerId = null
        actions.onNavigate(Screen.HOME)
        if (returnId != null) {
            viewModel.showContainerSettingsForId = returnId
        }
    }

    // Terminal: Back detaches to Home and keeps PTYs/tabs for Resume.
    BackHandler(enabled = currentScreen == Screen.TERMINAL) {
        actions.onGoHomeFromTerminal()
    }

    PocketLinuxTheme(darkTheme = isDarkTheme, accentColor = accentColor) {
        // Surface fills the full window (including under system bars) so edge-to-edge
        // looks correct. Content below applies statusBarsPadding so chrome stays clear
        // of the status bar — except immersive GUI, which owns the full surface.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Fresh install: only onboarding — never compose Home underneath for a frame.
                if (showOnboardingState) {
                    OnboardingScreen(
                        isDarkTheme = isDarkTheme,
                        accentColor = accentColor,
                        onFinished = { completeOnboarding() },
                        onCreateContainer = {
                            completeOnboarding()
                            actions.onAddContainer()
                        }
                    )
                } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Terminal paints under the status bar with the canvas color.
                    val contentNeedsStatusPad =
                        currentScreen != Screen.GUI && currentScreen != Screen.TERMINAL
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(
                                if (contentNeedsStatusPad) {
                                    Modifier.statusBarsPadding()
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        when (currentScreen) {
                            Screen.HOME -> {
                                HomeScreen(
                                    onAddContainer = actions.onAddContainer,
                                    hasContainers = containerConfigs.isNotEmpty(),
                                    // Sticky bar above bottom nav; tips first, then feedback strip.
                                    onShowTips = if (!packageTipsHintsDismissed) {
                                        {
                                            openPackageTipsForInstalled(
                                                containers = containerConfigs,
                                                viewModel = viewModel
                                            )
                                        }
                                    } else null,
                                    onDismissTips = if (!packageTipsHintsDismissed) {
                                        {
                                            // Dismiss without opening — still never show again.
                                            sharedPrefs.edit()
                                                .putBoolean(
                                                    DistroPackageTips.PREF_PACKAGE_TIPS_HINT_DISMISSED,
                                                    true
                                                )
                                                .apply()
                                            packageTipsHintEpoch++
                                        }
                                    } else null,
                                    onShowFeedback = if (showHomeFeedbackStrip) {
                                        { showHomeFeedbackSheet = true }
                                    } else null,
                                    onDismissFeedback = if (showHomeFeedbackStrip) {
                                        {
                                            HomeFeedbackStripPrefs.markStripDismissed(sharedPrefs)
                                            viewModel.homeFeedbackStripEpoch++
                                        }
                                    } else null,
                                    containerCards = {
                                        // While install-complete dialog is up, ignore card
                                        // Launch desktop / terminal — prevents a tap on the
                                        // card (or through the dialog) from starting GUI while
                                        // the user opens Tips.
                                        val blockCardSessionLaunch =
                                            viewModel.installCompleteContainerId != null
                                        ContainerCardsGrid(
                                            containers = containerConfigs,
                                            installingId = installingId,
                                            activeId = activeId,
                                            isGuiSessionActive = isGuiSessionActive,
                                            isShellSessionActive = isShellSessionActive,
                                            installProgressMessage = progressMsg,
                                            installProgressPercent = progressPct,
                                            statsMap = statsMap,
                                            accentColor = MaterialTheme.colorScheme.primary,
                                            isDarkTheme = isDarkTheme,
                                            onAddContainer = actions.onAddContainer,
                                            onLaunchGui = {
                                                if (!blockCardSessionLaunch) actions.onLaunchGui(it)
                                            },
                                            onLaunchShell = {
                                                if (!blockCardSessionLaunch) actions.onLaunchShell(it)
                                            },
                                            onInstall = { actions.onInstall(it) },
                                            onSettings = { actions.onSettings(it) },
                                            onViewLogs = { actions.onViewLogs(null) },
                                            onTerminate = { actions.onTerminate(it) },
                                            onAbortInstall = { actions.onAbortInstall(it) }
                                        )
                                    }
                                )
                            }
                            Screen.SETUP -> {
                                SetupScreen(
                                    welcomeVisible = setupWelcomeVisible,
                                    stepDescriptionText = setupStepDescText,
                                    stepDescriptionVisible = setupStepDescVisible,
                                    progress = setupProgress,
                                    progressIndeterminate = setupProgressIndeterminate,
                                    progressVisible = setupProgressVisible,
                                    progressStatusText = setupProgressStatusText,
                                    progressStatusVisible = setupProgressStatusVisible,
                                    checklistVisible = setupChecklistVisible,
                                    checklistItems = setupChecklistItems,
                                    promptContent = {
                                        AndroidView(
                                            factory = {
                                                val parent = promptContainer.parent as? ViewGroup
                                                parent?.removeView(promptContainer)
                                                promptContainer
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    metricsCardVisible = setupMetricsVisible,
                                    installPath = setupInstallPath,
                                    storageStatus = setupStorageStatus,
                                    onBackupClick = actions.onBackupClick,
                                    onRestoreClick = actions.onRestoreClick,
                                    restoreSetupVisible = setupRestoreSetupVisible,
                                    onRestoreSetupClick = actions.onRestoreClick,
                                    logToggleText = setupLogToggleText,
                                    onToggleLogsClick = actions.onToggleSetupLogs,
                                    logCardVisible = setupLogCardVisible,
                                    isLogFullscreen = setupIsLogFullscreen,
                                    logText = setupLogText,
                                    onDownloadLogClick = actions.onDownloadSetupLog,
                                    onFullscreenLogClick = {
                                        viewModel.setupIsLogFullscreen = !viewModel.setupIsLogFullscreen
                                    },
                                    onCopyLogClick = actions.onCopySetupLog
                                )
                            }
                            Screen.GUI -> {
                                GuiScreen(
                                    showLoading = guiLoadingVisible,
                                    showKeyBar = guiKeyBarVisible,
                                    keyBarBottomPadding = guiKeyBarBottomPaddingDp,
                                    showHomeButton = false,
                                    loadingStatusText = guiLoadingStatusText,
                                    loadingRamHint = viewModel.guiLoadingRamHint,
                                    onKeyboardToggle = actions.onKeyboardToggle,
                                    onGoHome = actions.onGoHomeFromGui,
                                    onKeyPressed = actions.onGuiKeyPressed,
                                    lorieViewFactory = {
                                        val parent = lorieView.parent as? ViewGroup
                                        parent?.removeView(lorieView)
                                        lorieView
                                    },
                                    imeAnchorFactory = {
                                        val parent = guiImeAnchor.parent as? ViewGroup
                                        parent?.removeView(guiImeAnchor)
                                        guiImeAnchor
                                    },
                                    ctrlActive = isGuiCtrlActive,
                                    altActive = isGuiAltActive,
                                    shiftActive = isGuiShiftActive,
                                    reseed = sharedPrefs.getBoolean("Reseed", false),
                                    showFirstRunCoach = viewModel.showGuiFirstRunCoach,
                                    onDismissFirstRunCoach = {
                                        viewModel.showGuiFirstRunCoach = false
                                    }
                                )
                            }
                            Screen.TERMINAL -> {
                                val activeSession = terminalSessions.getOrNull(terminalActiveTabIndex)
                                // Observe generation so Compose re-reads session.renderedState each frame.
                                @Suppress("UNUSED_EXPRESSION")
                                terminalRenderGeneration
                                val guestFontPt = activeSession?.appearance?.scheme?.fontSizePt
                                androidx.compose.runtime.LaunchedEffect(
                                    terminalThemeSourceState,
                                    guestFontPt,
                                    activeSession?.appearance?.matchedGui
                                ) {
                                    if (terminalThemeSourceState ==
                                        com.sg.linuxgo.GuestTerminalAppearance.SOURCE_MATCH_GUI &&
                                        activeSession?.appearance?.matchedGui == true
                                    ) {
                                        val pt = guestFontPt
                                        if (pt != null && pt in 4f..30f) {
                                            terminalFontSizeState = pt
                                        }
                                    }
                                }
                                val rendered = activeSession?.renderedState
                                val activeTheme = if (activeSession != null) {
                                    sessionThemes[activeSession.id] ?: terminalThemeState
                                } else {
                                    terminalThemeState
                                }
                                TerminalScreen(
                                    tabs = terminalSessionsState,
                                    activeTabIndex = terminalActiveTabIndex,
                                    terminalScrollView = terminalScroll,
                                    terminalInputView = etTerminalInput,
                                    terminalLines = rendered?.lines ?: emptyList(),
                                    cursorX = rendered?.cursorX ?: 0,
                                    cursorLine = rendered?.cursorLine ?: 0,
                                    cursorVisible = rendered?.isCursorVisible ?: true,
                                    isAlternateBuffer = rendered?.isAlternateBuffer ?: false,
                                    isMouseReportingEnabled = rendered?.isMouseReportingEnabled ?: false,
                                    renderGeneration = terminalRenderGeneration,
                                    onGoHome = actions.onGoHomeFromTerminal,
                                    onNewSession = actions.onNewTerminalSession,
                                    onCloseSession = actions.onCloseTerminalSession,
                                    onCloseSessionAt = actions.onCloseTerminalSessionAt,
                                    onDuplicateSession = actions.onDuplicateTerminalSession,
                                    onRenameSession = actions.onRenameTerminalSession,
                                    onTabSelected = actions.onTerminalTabSelected,
                                    onKeyPressed = actions.onTerminalKeyPressed,
                                    ctrlActive = isCtrlActive,
                                    altActive = isAltActive,
                                    shiftActive = isShiftActive,
                                    terminalTheme = activeTheme,
                                    onThemeSelected = { newTheme ->
                                        // Always persist + apply (even if no session yet) so preview/select works.
                                        sharedPrefs.edit().putString("terminal_theme", newTheme).apply()
                                        terminalThemeState = newTheme
                                        if (activeSession != null) {
                                            sessionThemes[activeSession.id] = newTheme
                                        }
                                        actions.onTerminalThemeSelected(newTheme)
                                    },
                                    terminalFontFamily = terminalFontFamilyState,
                                    terminalFontSize = terminalFontSizeState,
                                    onFontFamilySelected = { newFont ->
                                        sharedPrefs.edit().putString("terminal_font_family", newFont).apply()
                                    },
                                    onFontSizeSelected = { newSize ->
                                        // Update Compose state immediately so pinch-end does not
                                        // flash back to the old size while prefs/listener catch up.
                                        val size = newSize.coerceIn(4f, 30f)
                                        terminalFontSizeState = size
                                        sharedPrefs.edit().putFloat("terminal_font_size", size).apply()
                                    },
                                    terminalFontBoldness = terminalFontBoldnessState,
                                    onFontBoldnessSelected = { v ->
                                        sharedPrefs.edit()
                                            .putFloat("terminal_font_boldness", v.coerceIn(0f, 1f))
                                            .apply()
                                    },
                                    terminalFontWidth = terminalFontWidthState,
                                    onFontWidthSelected = { v ->
                                        sharedPrefs.edit()
                                            .putFloat("terminal_font_width", clampFontWidthScale(v))
                                            .apply()
                                    },
                                    terminalThemeSource = terminalThemeSourceState,
                                    onThemeSourceSelected = { source ->
                                        sharedPrefs.edit()
                                            .putString(
                                                com.sg.linuxgo.GuestTerminalAppearance.PREF_THEME_SOURCE,
                                                source
                                            )
                                            .apply()
                                        terminalThemeSourceState = source
                                        actions.onTerminalThemeSelected(activeTheme)
                                    },
                                    schemeBackgroundArgb = activeSession?.let { s ->
                                        if (terminalThemeSourceState ==
                                            com.sg.linuxgo.GuestTerminalAppearance.SOURCE_MATCH_GUI
                                        ) {
                                            s.emulator.colorScheme.defaultBg
                                        } else {
                                            null
                                        }
                                    },
                                    schemeTextArgb = activeSession?.let { s ->
                                        if (terminalThemeSourceState ==
                                            com.sg.linuxgo.GuestTerminalAppearance.SOURCE_MATCH_GUI
                                        ) {
                                            s.emulator.colorScheme.defaultFg
                                        } else null
                                    },
                                    schemeCursorArgb = activeSession?.let { s ->
                                        if (terminalThemeSourceState ==
                                            com.sg.linuxgo.GuestTerminalAppearance.SOURCE_MATCH_GUI
                                        ) {
                                            s.emulator.colorScheme.cursorColor
                                        } else null
                                    },
                                    matchGuiActive = activeSession?.appearance?.matchedGui == true,
                                    guestFontPath = activeSession?.appearance?.fontFile?.absolutePath,
                                    onRequestKeyboard = actions.onRequestTerminalKeyboard,
                                    onHideKeyboard = actions.onHideTerminalKeyboard,
                                    onSetImeSuppressed = actions.onSetTerminalImeSuppressed,
                                    onGridSizeChanged = actions.onTerminalGridSizeChanged,
                                    onMouseEvent = actions.onTerminalMouseEvent,
                                    onScrollSteps = actions.onTerminalScrollSteps,
                                    screenRows = rendered?.screenRows ?: 0,
                                    screenCols = rendered?.screenCols ?: 0
                                )
                            }
                            Screen.SETTINGS -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (installingId != null) {
                                        InstallProgressChip(
                                            percent = progressPct,
                                            onClick = { actions.onNavigate(Screen.HOME) }
                                        )
                                    }
                                    SettingsScreen(
                                        onBackupClick = actions.onBackupClick,
                                        onRestoreClick = actions.onRestoreClick,
                                        onThemeChange = { newTheme ->
                                            sharedPrefs.edit().putString("pocketlinux_theme", newTheme).apply()
                                            appThemeState = newTheme
                                        },
                                        currentTheme = appThemeState,
                                        isDarkTheme = isDarkTheme,
                                        onConfigureClick = { section ->
                                            when (section) {
                                                "experimental" -> {
                                                    viewModel.requestExperimentalSettings()
                                                }
                                                "big_screen" -> viewModel.showBigScreenSettingsState = true
                                                else -> {
                                                    viewModel.globalSettingsSectionState = section
                                                    viewModel.showGlobalSettingsState = true
                                                }
                                            }
                                        },
                                        onShowLearnMore = {
                                            viewModel.showLearnMoreState = true
                                        },
                                        onCoverBottomNav = { coverBottomNav = it },
                                        isBackupRestoreRunning = backupRestoreRunningState,
                                        backupRestoreProgress = backupRestoreProgressState,
                                        backupRestoreMessage = backupRestoreMessageState,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    if (!coverBottomNav && (
                        currentScreen == Screen.HOME ||
                        currentScreen == Screen.SETUP ||
                        currentScreen == Screen.SETTINGS
                    )) {
                        BottomNavigationBar(
                            currentScreen = currentScreen,
                            onNavigate = actions.onNavigate,
                            isDarkTheme = isDarkTheme
                        )
                    }
                }

                if (showNewContainerWizardState) {
                    NewContainerSheet(
                        onContainerCreated = actions.onContainerCreated,
                        onDismiss = {
                            viewModel.showNewContainerWizardState = false
                            actions.onContainerWizardDismissed()
                        },
                    )
                }

                if (showGlobalSettingsState) {
                    BackHandler { viewModel.showGlobalSettingsState = false }
                    GlobalSettingsSheet(
                        onDismiss = { viewModel.showGlobalSettingsState = false },
                        onSettingsSaved = actions.onGlobalSettingsSaved,
                        isDarkTheme = isDarkTheme,
                        section = globalSettingsSectionState
                    )
                }

                if (showContainerSettingsForId != null) {
                    ContainerSettingsSheet(
                        containerId = showContainerSettingsForId,
                        onDismiss = { viewModel.showContainerSettingsForId = null },
                        onDelete = {
                            val cid = viewModel.showContainerSettingsForId
                            viewModel.showContainerSettingsForId = null
                            if (cid != null) {
                                val container = containerConfigs.find { it.id == cid }
                                if (container != null) {
                                    // Sheet already typed-confirm; do not open a second dialog
                                    // or call removeContainer again (concurrent delete crash).
                                    actions.onDeleteConfirmed(container)
                                }
                            }
                        },
                        onViewLogs = { cid -> actions.onViewLogs(cid) },
                        onSettingsSaved = actions.onContainerSettingsSaved,
                        isDarkTheme = isDarkTheme
                    )
                }

                if (showLearnMoreState) {
                    LearnMoreScreen(
                        isDarkTheme = isDarkTheme,
                        accentColor = accentColor,
                        onDismiss = { viewModel.showLearnMoreState = false },
                        onOpenPackageTips = {
                            viewModel.showLearnMoreState = false
                            openPackageTipsForInstalled(
                                containers = containerConfigs,
                                viewModel = viewModel
                            )
                        }
                    )
                }

                if (showExperimentalSettingsState && FeatureGates.experimentalSettingsVisible()) {
                    BackHandler { viewModel.showExperimentalSettingsState = false }
                    ExperimentalSettingsScreen(
                        isDarkTheme = isDarkTheme,
                        onDismiss = { viewModel.showExperimentalSettingsState = false },
                        onSettingsSaved = actions.onGlobalSettingsSaved
                    )
                }

                if (viewModel.showBigScreenSettingsState) {
                    BackHandler { viewModel.showBigScreenSettingsState = false }
                    BigScreenSettingsScreen(
                        isDarkTheme = isDarkTheme,
                        onDismiss = { viewModel.showBigScreenSettingsState = false },
                        onSettingsSaved = actions.onGlobalSettingsSaved
                    )
                }

                if (showDistroTipsChooserState) {
                    val installedDistros = containerConfigs
                        .filter { it.isInstalled }
                        .map { it.distro }
                        .distinct()
                    DistroTipsChooserSheet(
                        isDarkTheme = isDarkTheme,
                        accentColor = accentColor,
                        availableDistroIds = installedDistros.ifEmpty { null },
                        onSelectDistro = { distroId ->
                            viewModel.showDistroTipsChooserState = false
                            // Prefer a container of the chosen distro for Open Terminal.
                            val match = containerConfigs.firstOrNull {
                                it.isInstalled &&
                                    DistroPackageTips.forDistro(it.distro).distroId ==
                                    DistroPackageTips.forDistro(distroId).distroId
                            }
                            viewModel.distroTipsTerminalContainerId = match?.id
                            viewModel.showDistroTipsForDistro = distroId
                        },
                        onDismiss = { viewModel.showDistroTipsChooserState = false }
                    )
                }

                val tipsDistro = showDistroTipsForDistro
                if (tipsDistro != null) {
                    DistroTipsSheet(
                        distroId = tipsDistro,
                        isDarkTheme = isDarkTheme,
                        accentColor = accentColor,
                        onDismiss = { dismissPackageTips(tipsDistro) },
                        onOpenTerminal = viewModel.distroTipsTerminalContainerId?.let { tipCid ->
                            {
                                // Sheet calls onDismiss first (hides inline hints), then opens Terminal.
                                val container = containerConfigs.find { it.id == tipCid }
                                if (container != null) {
                                    actions.onLaunchShell(container)
                                }
                            }
                        }
                    )
                }

                if (showHomeFeedbackSheet) {
                    FeedbackAndSuggestionsSheet(
                        onDismiss = { showHomeFeedbackSheet = false },
                        isDarkTheme = isDarkTheme,
                        onSubmitted = {
                            // Strip stays gone after any successful submit.
                            viewModel.homeFeedbackStripEpoch++
                        }
                    )
                }

                MainScreenOverlayDialogs(
                    context = context,
                    viewModel = viewModel,
                    containerConfigs = containerConfigs,
                    isDarkTheme = isDarkTheme,
                    onLaunchGui = actions.onLaunchGui,
                    onLaunchShell = actions.onLaunchShell,
                    onViewLogs = actions.onViewLogs,
                )
                } // end !showOnboardingState
            }
        }
    }
}

@Composable
private fun InstallProgressChip(
    percent: Int,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val label = if (percent in 0..100) {
        "Install in progress · $percent% · tap to return"
    } else {
        "Install in progress · tap to return"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Default
        )
    }
}

/**
 * Opens package tips for installed containers only.
 * One installed distro → that guide. Several → chooser limited to those distros.
 * None → chooser with primary Debian / Arch options.
 */
private fun openPackageTipsForInstalled(
    containers: List<ContainerConfig>,
    viewModel: MainViewModel
) {
    val installed = containers.filter { it.isInstalled }
    when {
        installed.isEmpty() -> {
            viewModel.distroTipsTerminalContainerId = null
            viewModel.showDistroTipsChooserState = true
        }
        installed.size == 1 ||
            installed.map { DistroPackageTips.forDistro(it.distro).distroId }.distinct().size == 1 -> {
            val target = installed.first()
            viewModel.distroTipsTerminalContainerId = target.id
            viewModel.showDistroTipsForDistro = target.distro
        }
        else -> {
            // Multiple different distros installed — pick among those only.
            viewModel.distroTipsTerminalContainerId = installed.first().id
            viewModel.showDistroTipsChooserState = true
        }
    }
}

@Composable
fun BottomNavigationBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    isDarkTheme: Boolean
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val view = LocalView.current

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        NavigationBarItem(
            selected = currentScreen == Screen.HOME,
            onClick = {
                view.performClickHaptic()
                onNavigate(Screen.HOME)
            },
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_home),
                    contentDescription = "Home",
                    modifier = Modifier.size(24.dp)
                )
            },
            label = {
                Text(
                    text = "Home",
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = selectedColor,
                selectedTextColor = selectedColor,
                unselectedIconColor = unselectedColor,
                unselectedTextColor = unselectedColor,
                indicatorColor = selectedColor.copy(alpha = 0.15f)
            )
        )

        NavigationBarItem(
            selected = currentScreen == Screen.SETTINGS,
            onClick = {
                view.performClickHaptic()
                onNavigate(Screen.SETTINGS)
            },
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_settings_gear),
                    contentDescription = "Settings",
                    modifier = Modifier.size(24.dp)
                )
            },
            label = {
                Text(
                    text = "Settings",
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = selectedColor,
                selectedTextColor = selectedColor,
                unselectedIconColor = unselectedColor,
                unselectedTextColor = unselectedColor,
                indicatorColor = selectedColor.copy(alpha = 0.15f)
            )
        )
    }
}
