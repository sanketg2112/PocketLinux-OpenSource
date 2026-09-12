package com.sg.linuxgo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import java.io.File
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageButton
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceManager
import android.content.res.Configuration
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.card.MaterialCardView
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.os.Build
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.tabs.TabLayout
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioAttributes
import com.sg.linuxgo.x11.MainActivity as X11Activity
import com.sg.linuxgo.x11.LorieView
import com.sg.linuxgo.x11.ICmdEntryInterface
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import java.net.Socket
import java.io.InputStream
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowInsets
import android.view.PointerIcon
import android.widget.HorizontalScrollView
import android.view.WindowInsetsController
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import com.sg.linuxgo.x11.input.InputStub
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.view.inputmethod.InputMethodManager
import android.view.ViewTreeObserver
import android.graphics.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sg.linuxgo.util.getSafeBoolean
import com.sg.linuxgo.util.getSafeFloat
import com.sg.linuxgo.util.getSafeString
import android.view.Gravity
import android.widget.PopupWindow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.sg.linuxgo.ui.theme.Cyan
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.sg.linuxgo.ui.screens.MainScreen
import com.sg.linuxgo.ui.screens.MainScreenActions
import com.sg.linuxgo.ui.screens.ChecklistItemState
import com.sg.linuxgo.ui.components.ContainerCardStats
import com.sg.linuxgo.ui.theme.Cyan
import android.content.ClipData
import android.content.ClipboardManager
import android.view.ViewGroup

enum class Screen {
    HOME, SETUP, GUI, TERMINAL, SETTINGS
}

class MainActivity : RegistryAppCompatActivity(), ContainerCardController.ContainerActionListener {

    // Lateinit probes + super bridges for extension files
    internal fun isLateInit_bootstrap(): Boolean = ::bootstrap.isInitialized
    internal fun isLateInit_btnGuiHome(): Boolean = ::btnGuiHome.isInitialized
    internal fun isLateInit_containerAdapter(): Boolean = ::containerAdapter.isInitialized
    internal fun isLateInit_containerManager(): Boolean = ::containerManager.isInitialized
    internal fun isLateInit_etTerminalInput(): Boolean = ::etTerminalInput.isInitialized
    internal fun isLateInit_guiContainer(): Boolean = ::guiContainer.isInitialized
    internal fun isLateInit_guiImeAnchor(): Boolean = ::guiImeAnchor.isInitialized
    internal fun isLateInit_guiKeyBar(): Boolean = ::guiKeyBar.isInitialized
    internal fun isLateInit_lorieView(): Boolean = ::lorieView.isInitialized
    internal fun isLateInit_mainRoot(): Boolean = ::mainRoot.isInitialized
    internal fun isLateInit_miniLogScroll(): Boolean = ::miniLogScroll.isInitialized
    internal fun isLateInit_setupView(): Boolean = ::setupView.isInitialized
    internal fun isLateInit_specialKeysScroll(): Boolean = ::specialKeysScroll.isInitialized
    internal fun isLateInit_terminalCursor(): Boolean = ::terminalCursor.isInitialized
    internal fun isLateInit_terminalScroll(): Boolean = ::terminalScroll.isInitialized
    internal fun isLateInit_terminalView(): Boolean = ::terminalView.isInitialized
    internal fun isLateInit_tvMiniLog(): Boolean = ::tvMiniLog.isInitialized
    internal fun isLateInit_tvTerminalOutput(): Boolean = ::tvTerminalOutput.isInitialized
    internal fun isLateInit_logCardView(): Boolean = ::logCardView.isInitialized
    internal fun isLateInit_btnToggleLogs(): Boolean = ::btnToggleLogs.isInitialized
    internal fun callSuperDispatchKeyEvent(event: KeyEvent): Boolean = super.dispatchKeyEvent(event)
    internal fun callSuperOnResume() { super.onResume() }
    internal fun callSuperOnWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus) }
    internal fun callSuperOnPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }
    fun refreshContainers() { performRefreshContainers() }
    fun onGlobalSettingsSaved() { performOnGlobalSettingsSaved() }


    internal val viewRegistry = HashMap<Int, View>()


    // ── Compose State Variables ───────────────────────────────────────────
    private fun <T> bindState(getter: () -> T, setter: (T) -> Unit) = object : kotlin.properties.ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = getter()
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = setter(value)
    }

    internal var currentScreen: Screen by bindState({ viewModel.currentScreen }, { viewModel.currentScreen = it })
    internal var containerConfigs: List<ContainerConfig> by bindState({ viewModel.containerConfigs }, { viewModel.containerConfigs = it })
    internal var activeContainerIdState: String? by bindState({ viewModel.activeContainerIdState }, { viewModel.activeContainerIdState = it })
    internal var installingContainerIdState: String? by bindState({ viewModel.installingContainerIdState }, { viewModel.installingContainerIdState = it })
    internal var installProgressMessageState: String by bindState({ viewModel.installProgressMessageState }, { viewModel.installProgressMessageState = it })
    internal var installProgressPercentState: Int by bindState({ viewModel.installProgressPercentState }, { viewModel.installProgressPercentState = it })
    internal var containerStatsState: Map<String, ContainerCardStats> by bindState({ viewModel.containerStatsState }, { viewModel.containerStatsState = it })
    
    // Explicit setters for delegated Compose states to bypass inner class compilation tracking issues
    internal fun updateContainerConfigsState(newContainers: List<ContainerConfig>) {
        runOnUiThread {
            containerConfigs = newContainers
        }
    }
    
    internal fun updateInstallingContainerIdState(containerId: String?) {
        runOnUiThread {
            installingContainerIdState = containerId
        }
    }
    
    internal fun updateInstallProgressMessageState(msg: String) {
        runOnUiThread {
            installProgressMessageState = msg
        }
    }
    
    internal fun updateInstallProgressPercentState(pct: Int) {
        runOnUiThread {
            installProgressPercentState = pct
        }
    }
    
    internal fun updateActiveContainerIdState(containerId: String?) {
        runOnUiThread {
            activeContainerIdState = containerId
        }
    }
    
    internal fun updateContainerStatsState(stats: Map<String, ContainerCardStats>) {
        runOnUiThread {
            containerStatsState = stats
        }
    }

    // Container installation card progress tracking helpers
    internal val installCardProgress = InstallCardProgress()

    internal fun resetCardState() {
        installCardProgress.reset()
    }

    internal fun updateCardProgress() {
        val (msg, pct) = installCardProgress.buildMessageAndPercent()
        installProgressMessageState = msg
        installProgressPercentState = pct
        // Keep adapter monotonic state in sync (hydrate / multi-source updates).
        if (::containerAdapter.isInitialized) {
            containerAdapter.updateInstallProgress(msg, pct)
        }
    }


    /**
     * Rebuild backup/restore progress UI from service statics.
     * LocalBroadcast is unregistered in [onPause], so progress/complete can be missed.
     */

    // Setup screen states
    internal var setupWelcomeVisible: Boolean by bindState({ viewModel.setupWelcomeVisible }, { viewModel.setupWelcomeVisible = it })
    internal var setupStepDescText: String by bindState({ viewModel.setupStepDescText }, { viewModel.setupStepDescText = it })
    internal var setupStepDescVisible: Boolean by bindState({ viewModel.setupStepDescVisible }, { viewModel.setupStepDescVisible = it })
    internal var setupProgress: Float by bindState({ viewModel.setupProgress }, { viewModel.setupProgress = it })
    internal var setupProgressIndeterminate: Boolean by bindState({ viewModel.setupProgressIndeterminate }, { viewModel.setupProgressIndeterminate = it })
    internal var setupProgressVisible: Boolean by bindState({ viewModel.setupProgressVisible }, { viewModel.setupProgressVisible = it })
    internal var setupProgressStatusText: String by bindState({ viewModel.setupProgressStatusText }, { viewModel.setupProgressStatusText = it })
    internal var setupProgressStatusVisible: Boolean by bindState({ viewModel.setupProgressStatusVisible }, { viewModel.setupProgressStatusVisible = it })
    internal var setupChecklistVisible: Boolean by bindState({ viewModel.setupChecklistVisible }, { viewModel.setupChecklistVisible = it })
    internal var setupChecklistItems: List<ChecklistItemState> by bindState({ viewModel.setupChecklistItems }, { viewModel.setupChecklistItems = it })
    internal var setupMetricsVisible: Boolean by bindState({ viewModel.setupMetricsVisible }, { viewModel.setupMetricsVisible = it })
    internal var setupInstallPath: String by bindState({ viewModel.setupInstallPath }, { viewModel.setupInstallPath = it })
    internal var setupStorageStatus: String by bindState({ viewModel.setupStorageStatus }, { viewModel.setupStorageStatus = it })
    internal var setupRestoreSetupVisible: Boolean by bindState({ viewModel.setupRestoreSetupVisible }, { viewModel.setupRestoreSetupVisible = it })
    internal var setupLogToggleText: String by bindState({ viewModel.setupLogToggleText }, { viewModel.setupLogToggleText = it })
    internal var setupLogCardVisible: Boolean by bindState({ viewModel.setupLogCardVisible }, { viewModel.setupLogCardVisible = it })
    internal var setupIsLogFullscreen: Boolean by bindState({ viewModel.setupIsLogFullscreen }, { viewModel.setupIsLogFullscreen = it })
    internal var setupLogText: String by bindState({ viewModel.setupLogText }, { viewModel.setupLogText = it })

    // GUI screen states
    internal var guiLoadingVisible: Boolean by bindState({ viewModel.guiLoadingVisible }, { viewModel.guiLoadingVisible = it })
    internal var guiLoadingStatusText: String by bindState({ viewModel.guiLoadingStatusText }, { viewModel.guiLoadingStatusText = it })
    internal var guiKeyBarVisible: Boolean by bindState({ viewModel.guiKeyBarVisible }, { viewModel.guiKeyBarVisible = it })
    internal var guiKeyBarBottomPaddingDp: Int by bindState({ viewModel.guiKeyBarBottomPaddingDp }, { viewModel.guiKeyBarBottomPaddingDp = it })
    internal var guiHomeButtonVisible: Boolean by bindState({ viewModel.guiHomeButtonVisible }, { viewModel.guiHomeButtonVisible = it })
    // Back-press state machine for GUI mode (0=hidden, 1=keyboard+keybar, 2=hidden after back)
    internal var guiBackPressState = 0

    // Terminal screen states
    internal var terminalActiveTabIndex: Int by bindState({ viewModel.terminalActiveTabIndex }, { viewModel.terminalActiveTabIndex = it })
    internal var terminalSessionsState: List<String> by bindState({ viewModel.terminalSessionsState }, { viewModel.terminalSessionsState = it })
    internal val sessionThemes = mutableStateMapOf<String, String>()

    // Backup/Restore progress states
    internal var backupRestoreRunningState: Boolean by bindState({ viewModel.backupRestoreRunningState }, { viewModel.backupRestoreRunningState = it })
    internal var backupRestoreProgressState: Float by bindState({ viewModel.backupRestoreProgressState }, { viewModel.backupRestoreProgressState = it })
    internal var backupRestoreMessageState: String by bindState({ viewModel.backupRestoreMessageState }, { viewModel.backupRestoreMessageState = it })

    // Dialog / Sheet states
    internal var showNewContainerWizardState: Boolean by bindState({ viewModel.showNewContainerWizardState }, { viewModel.showNewContainerWizardState = it })
    internal var showGlobalSettingsState: Boolean by bindState({ viewModel.showGlobalSettingsState }, { viewModel.showGlobalSettingsState = it })
    internal var globalSettingsSectionState: String? by bindState({ viewModel.globalSettingsSectionState }, { viewModel.globalSettingsSectionState = it })
    internal var showContainerSettingsForId: String? by bindState({ viewModel.showContainerSettingsForId }, { viewModel.showContainerSettingsForId = it })
    internal var logsReturnToContainerId: String? by bindState({ viewModel.logsReturnToContainerId }, { viewModel.logsReturnToContainerId = it })

    /**
     * Bridge for [RegistryAppCompatActivity]: serve Compose dummy/registry views.
     * Never register [android.R.id.content] — that must be the real window content
     * so insets / IME / child layout work. Missing ids return null (Java @Nullable),
     * which is what framework and IME code expect (Crash A).
     */
    override fun findRegisteredView(id: Int): View? {
        if (id == android.R.id.content) return null
        return viewRegistry[id]
    }

    /**
     * Init-time / app wiring: required views must exist (usually in [viewRegistry]).
     * Prefer this over non-null asserts on [findViewById] so missing ids throw a clear
     * error instead of the old Kotlin `findViewById(...) must not be null` NPE.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : View> requireView(id: Int): T {
        val registered = viewRegistry[id]
        if (registered != null) return registered as T
        val fromHierarchy = findViewById<View>(id)
        if (fromHierarchy != null) return fromHierarchy as T
        error("Required view missing id=0x${Integer.toHexString(id)}")
    }

    internal var lastEscPressTime = 0L


    internal lateinit var setupView: ScrollView
    internal lateinit var terminalView: LinearLayout
    internal lateinit var guiLoadingOverlay: View
    internal lateinit var guiContainer: FrameLayout
    internal lateinit var guiImeAnchor: EditText  // Invisible 1x1 EditText used as IME anchor in GUI mode
    internal lateinit var lorieView: LorieView
    internal var x11Service: ICmdEntryInterface? = null
    internal lateinit var homeContainer: ScrollView
    internal lateinit var checklistContainer: LinearLayout
    internal lateinit var step1: TextView
    internal lateinit var step2: TextView
    internal lateinit var step3: TextView
    internal lateinit var step4: TextView
    internal lateinit var step5: TextView
    internal lateinit var step6: TextView
    internal lateinit var btnToggleLogs: MaterialButton
    internal lateinit var btnRestoreSetup: MaterialButton
    internal lateinit var metricsCard: MaterialCardView
    internal var lastPhaseMessage: String = ""
    internal lateinit var logCardView: MaterialCardView
    internal lateinit var tvInstallPath: TextView
    internal lateinit var welcomeContainer: View
    internal lateinit var footerAction: View

    // Multi-container support
    internal lateinit var containerManager: ContainerManager
    internal val sessionNotificationManager by lazy { SessionNotificationManager(this, containerManager) }
    internal val backupRestoreController: BackupRestoreController by lazy {
        BackupRestoreController(
            activity = this,
            containerManager = containerManager,
            containerAdapterProvider = { if (::containerAdapter.isInitialized) containerAdapter else null },
            pickBackupLauncher = pickBackupLauncher,
            onLogToMini = { msg -> logToMini(msg) },
            onSetupCompleteCall = { onSetupComplete() }
        )
    }
    internal val guiSessionManager: GuiSessionManager by lazy {
        GuiSessionManager(
            activity = this,
            containerManager = containerManager,
            bootstrap = bootstrap,
            activeContainerIdProvider = { activeContainerId },
            updateSessionNotificationCall = { cid, ram -> updateSessionNotification(cid, ram) },
            isProcessRunningCall = { name -> isProcessRunning(name) },
            onLog = { msg -> logToMini(msg) },
            viewDelegate = object : GuiViewDelegate {
                override fun setGuiLayoutVisible(visible: Boolean) {
                    runOnUiThread {
                        if (visible) {
                            setupView.visibility = View.GONE
                            homeContainer.visibility = View.GONE
                            terminalView.visibility = View.GONE
                            tabLayout.visibility = View.GONE
                            footerAction.visibility = View.GONE
                            
                            guiContainer.visibility = View.VISIBLE
                            btnGuiHome.visibility = View.GONE
                            guiLoadingOverlay.visibility = View.VISIBLE
                            guiContainer.requestFocus()
                        } else {
                            switchToHomeTab()
                        }
                    }
                }
                override fun showLoadingStatus(status: String) {
                    runOnUiThread {
                        findViewById<TextView>(R.id.tvGuiLoadingStatus)?.text = status
                    }
                }
                override fun hideLoadingOverlay() {
                    runOnUiThread {
                        guiLoadingOverlay.visibility = View.GONE
                        // X11 ready: show the edge pill (keyboard + home), matching pre-refactor behavior.
                        showSoftKeyboardAndKeybar()
                    }
                }
                override fun isGuiVisible(): Boolean {
                    return ::guiContainer.isInitialized && guiContainer.visibility == View.VISIBLE
                }
                override fun getLorieView(): LorieView {
                    return lorieView
                }
                override fun onSessionStarted() {
                    runOnUiThread {
                        updateAdapterActiveState()
                        // Ensure pill is shown whenever an X11 session becomes active
                        // (also covers resume / already-connected paths).
                        if (::guiContainer.isInitialized && guiContainer.visibility == View.VISIBLE) {
                            showSoftKeyboardAndKeybar()
                        }
                    }
                }
                override fun startX11AudioBridge() {
                    // Host PulseAudio (AAudio + native-protocol-tcp :14713). Guest apps
                    // connect via PULSE_SERVER=tcp:127.0.0.1:14713 — no simple-protocol bridge.
                    Thread {
                        HostPulseAudioServer.ensureRunning(this@MainActivity) { msg -> logToMini(msg) }
                    }.apply {
                        isDaemon = true
                        name = "host-pa-bridge"
                        start()
                    }
                    // Stop legacy AudioTrack receiver if any previous session left it running
                    try {
                        audioReceiver?.stop()
                    } catch (_: Exception) {
                    }
                    audioReceiver = null
                }
                override fun onSessionTerminated() {
                    runOnUiThread {
                        terminateSession()
                    }
                }
                override fun acquireWakeLock() {
                    this@MainActivity.acquireWakeLock()
                }
                override fun releaseWakeLock() {
                    this@MainActivity.releaseWakeLock()
                }
                override fun startStatsPoller() {
                    this@MainActivity.startStatsPoller()
                }
                override fun stopStatsPoller() {
                    this@MainActivity.stopStatsPoller()
                }
            }
        )
    }
    internal lateinit var containerAdapter: ContainerCardController
    internal lateinit var rvContainers: RecyclerView
    internal lateinit var btnAddContainer: View
    @Volatile private var _activeContainerId: String? = null   // Container currently running X11/Wayland/terminal
    internal val activeContainerId: String? get() = _activeContainerId
    
    // Low free-RAM pill poller — 5s normally; 1s while a low-RAM chip is active
    internal val usageCheckHandler = Handler(Looper.getMainLooper())
    internal val usageCheckRunnable = object : Runnable {
        override fun run() {
            val isUsingContainer = ::guiContainer.isInitialized && guiContainer.visibility == View.VISIBLE

            if (!isUsingContainer) {
                if (viewModel.lowRamPhase != LowRamPillPhase.None) {
                    clearLowRamPillState()
                    pillPopupController.updatePillPopupSize(viewModel.menuExpanded)
                }
                usageCheckHandler.postDelayed(this, 5000)
                return
            }

            // Low free-RAM pill only while GUI is on-screen (warn, never limit).
            val lowRamActive = applyLowRamPillState(readAvailRamMbForPill(this@MainActivity))
            val delayMs = if (lowRamActive) 1000L else 5000L
            usageCheckHandler.postDelayed(this, delayMs)
        }
    }

    internal val selectedGuiMode: String
        get() = guiSessionManager.selectedGuiMode
    internal val selectedOrientation: String
        get() = guiSessionManager.selectedOrientation
    internal val selectedScalePct: Int
        get() = guiSessionManager.selectedScalePct
    internal val isX11Started: Boolean
        get() = guiSessionManager.isX11Started
    internal var isTerminalActiveState by mutableStateOf(false)
    internal lateinit var tvStepDescription: TextView
    internal lateinit var tvProgressStatus: TextView
    internal lateinit var tvMiniLog: TextView
    internal lateinit var progressBar: LinearProgressIndicator
    internal lateinit var tvTerminalOutput: TextView
    internal lateinit var etTerminalInput: EditText
    internal val terminalIme = TerminalImeCoordinator()
    internal lateinit var terminalCursor: View
    internal var isResettingTerminalInputText = false
    internal lateinit var btnPrimaryAction: MaterialButton
    internal lateinit var tabLayout: TabLayout
    internal lateinit var btnGuiHome: ImageButton
    internal lateinit var terminalScroll: ScrollView
    internal lateinit var specialKeysScroll: View
    internal fun isSpecialKeysScrollInitialized(): Boolean = ::specialKeysScroll.isInitialized
    internal lateinit var btnCopyLog: MaterialButton
    internal lateinit var btnDownloadLog: MaterialButton
    internal lateinit var btnFullscreenLog: MaterialButton
    internal var lastMiniLogInteractionTime = 0L
    internal var isProgrammaticScroll = false
    internal var isMiniLogFullscreen = false
    internal lateinit var promptContainer: LinearLayout

    // Bootstrap is now managed by SetupForegroundService; we keep a reference only
    // to relay handleChoice / handleInput back to the service.
    internal lateinit var bootstrap: Bootstrap
    internal val viewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
    }
    internal var audioReceiver: PulseAudioReceiver? = null
    internal val terminalBridge = TerminalBridge()
    internal val terminalSessionManager = TerminalSessionManager()
    internal val terminalController by lazy {
        TerminalController(
            context = this,
            containerManager = containerManager,
            bootstrap = bootstrap,
            terminalBridge = terminalBridge,
            terminalSessionManager = terminalSessionManager,
            activeContainerIdProvider = { activeContainerId },
            onSessionSelected = { session ->
                updateStatusBarColorAndIcons()
                applyTerminalThemeToLegacyViews()
            },
            viewDelegate = object : TerminalViewDelegate {
                override fun getTerminalOutputView(): TextView = tvTerminalOutput
                override fun getTerminalScrollView(): android.widget.ScrollView = terminalScroll
                override fun getTerminalCursorView(): android.view.View = terminalCursor
                override fun getTabLayoutTerminal(): TabLayout = tabLayoutTerminal
                override fun getSessionThemes(): MutableMap<String, String> = sessionThemes
                override fun runOnUiThread(action: () -> Unit) = this@MainActivity.runOnUiThread(action)
                override fun acquireWakeLock() = this@MainActivity.acquireWakeLock()
                override fun releaseWakeLock() = this@MainActivity.releaseWakeLock()
                override fun switchToHomeTab() = this@MainActivity.switchToHomeTab()
                override fun updateSessionNotification(containerId: String, ram: Int) {
                    this@MainActivity.updateSessionNotification(containerId, ram)
                }
                override fun notifyTerminalOutputChanged() {
                    // Drive Compose recomposition for the grid renderer.
                    viewModel.terminalRenderGeneration++
                }
            }
        )
    }
    internal val terminalSessions get() = terminalController.terminalSessions
    internal var activeSessionIndex: Int
        get() = terminalController.activeSessionIndex
        set(value) {
            terminalSessionManager.selectSession(value)
        }
    internal lateinit var tabLayoutTerminal: TabLayout
    internal lateinit var btnNewSession: MaterialButton
    internal lateinit var btnCloseSession: MaterialButton
    internal var wakeLock: android.os.PowerManager.WakeLock? = null
    internal lateinit var scaleGestureDetector: android.view.ScaleGestureDetector
    internal var isSetupComplete = false
    internal var isTaskInProgress = false

    // Track whether setup service is actively waiting for user input
    internal var pendingStep: Bootstrap.SetupStep? = null
    internal var pendingChoices: List<Bootstrap.Choice>? = null
    internal var pendingInputHint: String? = null

    // Native X11 Interaction State
    internal var guiScaleX = 1.0f
    internal var guiScaleY = 1.0f
    internal val guiInputHandler: GuiInputHandler by lazy {
        GuiInputHandler(
            context = this,
            lorieView = lorieView,
            guiScaleXProvider = { guiScaleX },
            guiScaleYProvider = { guiScaleY }
        )
    }
    internal lateinit var fabKeyboard: FloatingActionButton

    internal val pillPopupController by lazy {
        PillPopupController(this)
    }
    internal var isPillHidden: Boolean by bindState({ viewModel.isPillHidden }, { viewModel.isPillHidden = it })
    internal var menuExpanded: Boolean by bindState({ viewModel.menuExpanded }, { viewModel.menuExpanded = it })
    internal var shownDexToast = false
    /** Last known DeX mode for connect/disconnect rehydrate. */
    internal var wasInDeXMode = false
    internal var isDraggingPill: Boolean by bindState({ viewModel.isDraggingPill }, { viewModel.isDraggingPill = it })
    internal var currentDrawX: Float by bindState({ viewModel.currentDrawX }, { viewModel.currentDrawX = it })
    internal var currentDrawY: Float by bindState({ viewModel.currentDrawY }, { viewModel.currentDrawY = it })
    internal var isImeVisible: Boolean by bindState({ viewModel.isImeVisible }, { viewModel.isImeVisible = it })
    internal var wasHiddenOnDown = false

    internal val pillAutoHideHandler = Handler(Looper.getMainLooper())
    internal val pillAutoHideRunnable = Runnable {
        if (!menuExpanded && !isImeVisible && !isDraggingPill) {
            isPillHidden = true
            updatePillPopupSize(false)
        }
    }

    internal fun resetPillAutoHideTimer() {
        pillAutoHideHandler.removeCallbacks(pillAutoHideRunnable)
        pillAutoHideHandler.postDelayed(pillAutoHideRunnable, 5000)
    }

    internal lateinit var miniLogScroll: ScrollView
    internal var isCtrlActive: Boolean by bindState({ viewModel.isCtrlActive }, { viewModel.isCtrlActive = it })
    internal var isAltActive: Boolean by bindState({ viewModel.isAltActive }, { viewModel.isAltActive = it })
    internal var isShiftActive: Boolean by bindState({ viewModel.isShiftActive }, { viewModel.isShiftActive = it })
    internal var isFnActive: Boolean by bindState({ viewModel.isFnActive }, { viewModel.isFnActive = it })
    internal var isGuiCtrlActive: Boolean by bindState({ viewModel.isGuiCtrlActive }, { viewModel.isGuiCtrlActive = it })
    internal var isGuiAltActive: Boolean by bindState({ viewModel.isGuiAltActive }, { viewModel.isGuiAltActive = it })
    internal var isGuiShiftActive: Boolean by bindState({ viewModel.isGuiShiftActive }, { viewModel.isGuiShiftActive = it })
    internal var isGuiFnActive: Boolean by bindState({ viewModel.isGuiFnActive }, { viewModel.isGuiFnActive = it })
    internal lateinit var guiKeyBar: MaterialCardView

    
    internal lateinit var mainRoot: View

    // Stats Poller
    internal val statsPoller by lazy {
        StatsPoller(
            context = this,
            containerManager = containerManager,
            containerAdapterProvider = { if (::containerAdapter.isInitialized) containerAdapter else null },
            activeContainerIdProvider = { activeContainerId },
            isAnySessionRunningProvider = { isAnySessionRunning() },
            updateSessionNotificationCall = { cid, ram -> updateSessionNotification(cid, ram) },
            clearSessionNotificationCall = { clearSessionNotification() },
            updateAdapterActiveStateCall = { updateAdapterActiveState() },
            runOnUiThreadCall = { action -> runOnUiThread(action) }
        )
    }
    internal val isPollingStats: Boolean
        get() = statsPoller.isPollingStats

    // ── Terminate from notification ─────────────────────────────────────────
    companion object {
        const val ACTION_TERMINATE_SESSION = SessionNotificationManager.ACTION_TERMINATE_SESSION
    }

    internal val terminateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TERMINATE_SESSION) {
                runOnUiThread { terminateSession() }
            }
        }
    }

    internal fun updateChecklist(msg: String) {
        SetupChecklistSupport.updateChecklist(
            checklistContainer, step1, step2, step3, step4, step5, step6, msg
        )
    }

    // ── LocalBroadcast receiver: listens for events sent by SetupForegroundService & BackupRestoreService ──
    internal val setupReceiver = SetupBroadcastHandler(this)

    internal val pickBackupLauncher: ActivityResultLauncher<Array<String>> = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Restore always creates a new container — never wipes existing ones.
        uri?.let { backupRestoreController.performRestoreFromUri(it) }
    }

    internal lateinit var downloadLogLauncher: ActivityResultLauncher<String>

    internal val loadingHandler = Handler(Looper.getMainLooper())

    internal val x11Receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sg.linuxgo.x11.CmdEntryPoint.ACTION_START") {
                if (LorieView.connected()) return // Loop breaker: already connected
                logToMini("[X11] Received startup broadcast from server")
                val bundle = intent.getBundleExtra(null)
                val ibinder = bundle?.getBinder(null)
                if (ibinder != null) {
                    guiSessionManager.onNewX11Binder(ibinder)
                }
            }
        }
    }

    internal fun tryX11Connect() {
        guiSessionManager.tryX11Connect()
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleDispatchKeyEvent(event)
    override fun onResume() { handleOnResume() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { handleOnWindowFocusChanged(hasFocus) }
    override fun onUserLeaveHint() { handleOnUserLeaveHint() }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        handleOnPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }
    override fun onLaunchGui(container: ContainerConfig) { handleOnLaunchGui(container) }
    override fun onLaunchShell(container: ContainerConfig) { handleOnLaunchShell(container) }
    override fun onInstall(container: ContainerConfig) { handleOnInstall(container) }
    override fun onSettings(container: ContainerConfig) { handleOnSettings(container) }
    override fun onDelete(container: ContainerConfig) { handleOnDelete(container) }
    fun onDeleteConfirmed(container: ContainerConfig) { handleOnDeleteConfirmed(container) }
    override fun onTerminate(container: ContainerConfig) { handleOnTerminate(container) }
    override fun onAbortInstall(container: ContainerConfig) { handleOnAbortInstall(container) }

    /** DEBUG: start DE conversion FGS (logs + card progress; survives Home). */
    fun startDesktopDeConvert(containerId: String, targetDe: String) {
        handleStartDesktopDeConvert(containerId, targetDe)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent system bars on all API levels; content draws edge-to-edge.
        // Compose screens pad with statusBarsPadding so UI stays below the status bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        performOnCreateSetup()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleMainConfigurationChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("EXTRA_GO_HOME", false)) {
            runOnUiThread {
                tabLayout.getTabAt(0)?.select()
                switchToHomeTab()
            }
        }
    }


    override fun onPause() {
        super.onPause()
        usageCheckHandler.removeCallbacks(usageCheckRunnable)
        // App going to background — service notifications are now needed
        SetupForegroundService.isActivityVisible = false
        LocalBroadcastManager.getInstance(this).unregisterReceiver(setupReceiver)
        hidePillPopup()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(x11Receiver)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(terminateReceiver)
        } catch (e: Exception) {}
        audioReceiver?.stop()
        audioReceiver = null
        super.onDestroy()
        closePty()
    }

    internal fun startTerminalProcess() {
        terminalController.startTerminalProcess()
    }

    internal fun addNewTerminalSession() {
        terminalController.addNewTerminalSession()
    }

    internal fun closeActiveTerminalSession() {
        terminalController.closeActiveTerminalSession()
    }

    internal fun closeTerminalSessionAt(index: Int) {
        terminalController.closeTerminalSessionAt(index)
    }

    internal fun duplicateTerminalSession(index: Int) {
        terminalController.duplicateTerminalSession(index)
    }

    internal fun renameTerminalSession(index: Int, newName: String) {
        terminalController.renameTerminalSession(index, newName)
    }

    internal fun updateTerminalUi() {
        terminalController.updateTerminalUi()
    }

    internal fun scrollCursorIntoView(force: Boolean = false) {
        terminalController.scrollCursorIntoView(force)
    }

    internal fun recalculateActiveSessionSize() {
        terminalController.recalculateActiveSessionSize()
    }


    internal fun isInstalled(): Boolean {
        val proot = java.io.File(applicationInfo.nativeLibraryDir, "libproot.so")
        if (!proot.exists()) return false
        // Check if any container is installed (multi-container)
        return containerManager.isAnyContainerInstalled()
    }

    internal fun switchToGUI() {
        setupView.visibility = View.GONE
        terminalView.visibility = View.GONE
        homeContainer.visibility = View.GONE
        
        guiContainer.visibility = View.VISIBLE
        btnGuiHome.visibility = View.GONE
        btnPrimaryAction.visibility = View.GONE
        footerAction.visibility = View.GONE
        tabLayout.visibility = View.GONE
        
        setFullscreen()
    }

    internal fun stopX11Session() {
        guiSessionManager.stopX11Session()
    }


    internal fun closePty() {
        terminalController.closePty()
    }

    internal fun writeToTerminal(command: String) {
        TerminalInputSupport.writeToTerminal(
            command = command,
            sessions = terminalSessions,
            activeSessionIndex = activeSessionIndex,
            isShiftActive = { isShiftActive },
            setShiftActive = { isShiftActive = it },
            isFnActive = { isFnActive },
            setFnActive = { isFnActive = it },
            isCtrlActive = { isCtrlActive },
            setCtrlActive = { isCtrlActive = it },
            isAltActive = { isAltActive },
            setAltActive = { isAltActive = it },
            hideCursor = { terminalCursor.visibility = View.GONE },
            runOnUiThread = { action -> runOnUiThread(action) }
        )
    }

    internal fun confirmBackup() {
        backupRestoreController.confirmBackup()
    }

    internal fun confirmRestore() {
        backupRestoreController.confirmRestore()
    }

    internal fun performBackup(containerId: String, name: String) {
        backupRestoreController.performBackup(containerId, name)
    }

    internal fun performRestore(backupFile: java.io.File, originalName: String? = null) {
        backupRestoreController.performRestore(backupFile, originalName)
    }

    internal fun validateAndFixRestoredSystem(rootfs: java.io.File, containerId: String): Boolean {
        return backupRestoreController.validateAndFixRestoredSystem(rootfs, containerId)
    }

    internal fun performRestoreFromUri(uri: android.net.Uri) {
        backupRestoreController.performRestoreFromUri(uri)
    }

    fun onContainerWizardDismissed() {
        Log.d("LinuxGoDebug", "onContainerWizardDismissed() called. isTaskInProgress=$isTaskInProgress, containersCount=${containerManager.getContainers().size}")
        if (!isTaskInProgress && containerManager.getContainers().isEmpty()) {
            isSetupComplete = true
            onSetupComplete()
        }
    }

    internal fun showNewContainerWizard(autoInstall: Boolean = false) {
        showNewContainerWizardState = true
    }


    internal val fullscreenRunnable = object : Runnable {
        override fun run() {
            if (guiContainer.visibility == View.VISIBLE) {
                setFullscreen()
                loadingHandler.postDelayed(this, 1000)
            }
        }
    }
    internal fun setFullscreen() {
        guiSessionManager.setFullscreen()
    }

    internal fun resetFullscreen() {
        guiSessionManager.resetFullscreen()
    }

    internal fun updatePointerCapture() {
        guiSessionManager.updatePointerCapture()
    }

    internal var usableHeightPrevious = 0


    internal fun sendShortcut(keyCode: Int) {
        GuiKeySupport.sendShortcut(lorieView, keyCode)
    }

    internal fun sendGuiKey(keyCode: Int) {
        GuiKeySupport.sendGuiKey(lorieView, keyCode)
    }

    internal fun showPillPopup() {
        pillPopupController.showPillPopup()
    }

    internal fun updatePillPopupSize(expanded: Boolean) {
        pillPopupController.updatePillPopupSize(expanded)
    }

    internal fun hidePillPopup() {
        pillPopupController.hidePillPopup()
    }


    internal fun pxToDp(px: Int): Int {
        return (px / resources.displayMetrics.density).toInt().coerceAtLeast(0)
    }

    internal fun startStatsPoller() {
        statsPoller.startStatsPoller()
    }

    internal fun stopStatsPoller() {
        statsPoller.stopStatsPoller()
    }

    internal fun seedContainerStorageStats(containerId: String) {
        statsPoller.seedSystemStorageForContainer(containerId)
    }

    internal fun saveStatsToCache(containerId: String, storageUsedMB: Int, storageTotalMB: Int, ramUsedMB: Int, ramTotalMB: Int, systemUsedStorageMB: Int, systemUsedRamMB: Int) {
        statsPoller.saveStatsToCache(containerId, storageUsedMB, storageTotalMB, ramUsedMB, ramTotalMB, systemUsedStorageMB, systemUsedRamMB)
    }

    internal fun loadCachedStats() {
        statsPoller.loadCachedStats()
    }

    internal fun removeStatsFromCache(containerId: String) {
        statsPoller.removeStatsFromCache(containerId)
    }

    internal fun updateSessionNotification(containerId: String, ramUsedMB: Int) {
        sessionNotificationManager.updateSessionNotification(containerId, ramUsedMB, isPollingStats, activeContainerId)
    }

    internal fun clearSessionNotification() {
        sessionNotificationManager.clearSessionNotification()
    }

    internal fun isColorLight(color: Int): Boolean = ThemeUiSupport.isColorLight(color)


    internal fun setActiveContainerId(id: String?) {
        _activeContainerId = id
        activeContainerIdState = id
        val prefs = getSharedPreferences("pocket_linux_state", Context.MODE_PRIVATE)
        if (id != null) {
            prefs.edit().putString("active_container_id", id).apply()
        } else {
            prefs.edit().remove("active_container_id").apply()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val result = pillPopupController.dispatchTouchEvent(event) { super.dispatchTouchEvent(it) }
        if (result != null) return result
        return super.dispatchTouchEvent(event)
    }

}
