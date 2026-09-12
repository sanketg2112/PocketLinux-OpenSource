package com.sg.linuxgo

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.sg.linuxgo.ui.components.AppConfirmRequest
import com.sg.linuxgo.ui.components.ContainerCardStats
import com.sg.linuxgo.ui.onboarding.DistroPackageTips
import com.sg.linuxgo.ui.screens.ChecklistItemState

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Observable Views for Jetpack Compose Bridge ────────────────────────
    var currentScreen by mutableStateOf(Screen.HOME)
    var containerConfigs by mutableStateOf<List<ContainerConfig>>(emptyList())
    var activeContainerIdState by mutableStateOf<String?>(null)
    var installingContainerIdState by mutableStateOf<String?>(null)
    var installProgressMessageState by mutableStateOf("")
    var installProgressPercentState by mutableStateOf(0)
    var containerStatsState by mutableStateOf<Map<String, ContainerCardStats>>(emptyMap())

    /** True while a GUI (X11/Wayland) session is live for [activeContainerIdState]. */
    var isGuiSessionActive by mutableStateOf(false)
    /** True while a terminal/shell session is live for [activeContainerIdState]. */
    var isShellSessionActive by mutableStateOf(false)

    var setupWelcomeVisible by mutableStateOf(false)
    var setupStepDescText by mutableStateOf("")
    var setupStepDescVisible by mutableStateOf(false)
    var setupProgress by mutableStateOf(0f)
    var setupProgressIndeterminate by mutableStateOf(true)
    var setupProgressVisible by mutableStateOf(false)
    var setupProgressStatusText by mutableStateOf("")
    var setupProgressStatusVisible by mutableStateOf(false)
    var setupChecklistVisible by mutableStateOf(false)
    var setupChecklistItems by mutableStateOf<List<ChecklistItemState>>(emptyList())
    var setupMetricsVisible by mutableStateOf(false)
    var setupInstallPath by mutableStateOf("")
    var setupStorageStatus by mutableStateOf("")
    var setupRestoreSetupVisible by mutableStateOf(false)
    var setupLogToggleText by mutableStateOf("More Information ▼")
    var setupLogCardVisible by mutableStateOf(false)
    var setupIsLogFullscreen by mutableStateOf(false)
    var setupLogText by mutableStateOf("[READY] System offline.")

    var guiLoadingVisible by mutableStateOf(false)
    var guiLoadingStatusText by mutableStateOf("")
    /** Optional free-RAM note on the GUI loading overlay; never blocks launch. */
    var guiLoadingRamHint by mutableStateOf<String?>(null)
    var guiKeyBarVisible by mutableStateOf(false)
    var guiKeyBarBottomPaddingDp by mutableStateOf(0)
    var guiHomeButtonVisible by mutableStateOf(false)

    var terminalActiveTabIndex by mutableStateOf(0)
    var terminalSessionsState by mutableStateOf<List<String>>(emptyList())
    /** Incremented whenever a session publishes a new [TerminalSession.renderedState] frame. */
    var terminalRenderGeneration by mutableStateOf(0)

    var backupRestoreRunningState by mutableStateOf(false)
    var backupRestoreProgressState by mutableStateOf(0f)
    var backupRestoreMessageState by mutableStateOf("")

    var showNewContainerWizardState by mutableStateOf(false)
    var showGlobalSettingsState by mutableStateOf(false)
    var globalSettingsSectionState by mutableStateOf<String?>(null)
    var showContainerSettingsForId by mutableStateOf<String?>(null)
    /** When set, leaving the logs screen re-opens this container's settings sheet. */
    var logsReturnToContainerId by mutableStateOf<String?>(null)

    /**
     * Bumps when the home feedback strip eligibility changes (first session stop,
     * dismiss, or feedback submitted) so [MainScreen] re-reads prefs.
     */
    var homeFeedbackStripEpoch by mutableStateOf(0)

    /**
     * First-launch onboarding carousel ("Linux on your phone").
     * Initialized from prefs in [init] so the first Compose frame never flashes Home.
     */
    var showOnboardingState by mutableStateOf(false)
    /** Interactive Learn more explorer (Settings). */
    var showLearnMoreState by mutableStateOf(false)
    /** Full-page Experimental settings (Wayland, tawcroot). */
    var showExperimentalSettingsState by mutableStateOf(false)
    /** Full-page Big screen (scrcpy) companion settings. */
    var showBigScreenSettingsState by mutableStateOf(false)
    /**
     * When non-null, show package tips for this distro id (e.g. "debian").
     * Use [showDistroTipsChooserState] when the user should pick a distro first.
     */
    var showDistroTipsForDistro by mutableStateOf<String?>(null)
    var showDistroTipsChooserState by mutableStateOf(false)
    /** Optional container id to open Terminal from the tips sheet. */
    var distroTipsTerminalContainerId by mutableStateOf<String?>(null)

    var isPillHidden by mutableStateOf(true)
    var menuExpanded by mutableStateOf(false)
    var isDraggingPill by mutableStateOf(false)
    var currentDrawX by mutableStateOf(0f)
    var currentDrawY by mutableStateOf(0f)
    var isImeVisible by mutableStateOf(false)
    var isCtrlActive by mutableStateOf(false)
    var isAltActive by mutableStateOf(false)
    var isShiftActive by mutableStateOf(false)
    var isFnActive by mutableStateOf(false)
    var isGuiCtrlActive by mutableStateOf(false)
    var isGuiAltActive by mutableStateOf(false)
    var isGuiShiftActive by mutableStateOf(false)
    var isGuiFnActive by mutableStateOf(false)

    /** Bumped to trigger a short shake animation + haptic on the edge pill (low-RAM alerts). */
    var sessionShakeNonce by mutableStateOf(0)

    // ── Low free-RAM pill while GUI is active (≤500 MB warn / ≤250 MB critical) ─
    var lowRamPhase by mutableStateOf(LowRamPillPhase.None)
    var lowRamAvailMb by mutableStateOf(0)
    /** User dismissed the ≤500 MB chip (ignored once critical). */
    var lowRamWarningDismissed by mutableStateOf(false)
    /** Last low-RAM phase that already played its entrance shake. */
    var lowRamLastShakePhase by mutableStateOf(LowRamPillPhase.None)

    /**
     * After a successful install, Home shows [com.sg.linuxgo.ui.components.InstallCompleteDialog]
     * for this container id (next-action celebration).
     */
    var installCompleteContainerId by mutableStateOf<String?>(null)

    /**
     * Atomically claim the install-complete dialog for one next action
     * (desktop / terminal / tips / dismiss).
     *
     * @return the container id, or null if the dialog was already claimed.
     */
    fun consumeInstallCompleteContainerId(): String? {
        val id = installCompleteContainerId ?: return null
        installCompleteContainerId = null
        return id
    }

    /** Generic Compose confirm (delete / abort / stop session). */
    var appConfirmRequest by mutableStateOf<AppConfirmRequest?>(null)

    /** Install failure message shown as Compose confirm (OK only). */
    var installFailedMessage by mutableStateOf<String?>(null)

    /**
     * When true, [MainScreen] peeks [CrashReportCoordinator] and shows Compose crash UI.
     * Set by [CrashReportCoordinator.maybeShowPrompt] instead of AppCompat dialogs.
     */
    var showCrashReportPrompt by mutableStateOf(false)

    /** First GUI session coach mark (Compose overlay on GuiScreen). */
    var showGuiFirstRunCoach by mutableStateOf(false)

    fun requestExperimentalSettings() {
        if (!FeatureGates.experimentalSettingsVisible()) return
        showExperimentalSettingsState = true
    }

    fun requestConfirm(request: AppConfirmRequest) {
        appConfirmRequest = request
    }

    fun clearConfirm() {
        appConfirmRequest = null
    }

    init {
        // Read onboarding flag before first frame — never start false then flip true.
        showOnboardingState = try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(application)
            !prefs.getBoolean(DistroPackageTips.PREF_ONBOARDING_COMPLETED, false)
        } catch (_: Exception) {
            false
        }
    }
}
