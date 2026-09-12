package com.sg.linuxgo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.sg.linuxgo.ui.components.ContainerCardStats
import com.sg.linuxgo.ui.components.appendRamHistorySample
import com.sg.linuxgo.ui.screens.MainScreen
import com.sg.linuxgo.ui.screens.MainScreenActions

/**
 * onCreate wiring extracted from [MainActivity] to keep the activity class slim.
 */
internal fun MainActivity.performOnCreateSetup() {
    val activity = this
        
        if (BackupRestoreService.isRunning) {
            isTaskInProgress = true
            backupRestoreRunningState = true
            backupRestoreMessageState = "An operation is currently running..."
            backupRestoreProgressState = 0f
        }
        
        migrateLegacyPreferencesIfNeeded()
        FeatureGates.applyDefaultWaylandGuiToContainers(this)

        // Queue a local crash-report prompt on uncaught exceptions (nothing is uploaded
        // unless the user taps Send).
        TelemetryManager.registerUncaughtExceptionHandler(this)
        // Channel must exist before any startForegroundService (Crash D / session keep-alive).
        SessionKeepAliveService.ensureChannel(this)

        initDummyViews()

        mainRoot = requireView(R.id.mainRoot)

        // Real window content (not registry) — required for IME / layout listeners.
        findViewById<View>(android.R.id.content)?.viewTreeObserver?.addOnGlobalLayoutListener {
            possiblyResizeChildOfContent()
        }
        
        // Keep screen active during usage of app (default: true)
        applyKeepScreenOnPreference()

        // Initialize UI components
        setupView = requireView(R.id.setupView)
        terminalView = requireView(R.id.terminalView)
        guiLoadingOverlay = requireView(R.id.guiLoadingOverlay)
        guiContainer = requireView(R.id.guiContainer)
        // Add a tiny invisible EditText directly inside guiContainer as a reliable IME anchor.
        // LorieView cannot host an InputConnection, so we need a real EditText in the live
        // view hierarchy to reliably show the soft keyboard in GUI mode.
        guiImeAnchor = EditText(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0f
            isFocusable = true
            isFocusableInTouchMode = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = null
        }
        guiContainer.addView(guiImeAnchor)
        homeContainer = requireView(R.id.homeContainer)
        checklistContainer = requireView(R.id.checklistContainer)
        step1 = requireView(R.id.step1)
        step2 = requireView(R.id.step2)
        step3 = requireView(R.id.step3)
        step4 = requireView(R.id.step4)
        step5 = requireView(R.id.step5)
        step6 = requireView(R.id.step6)
        btnToggleLogs = requireView(R.id.btnToggleLogs)
        btnRestoreSetup = requireView(R.id.btnRestoreSetup)
        btnRestoreSetup.setOnClickListener { confirmRestore() }
        metricsCard = requireView(R.id.metricsCard)
        logCardView = requireView(R.id.logCardView)
        tvInstallPath = requireView(R.id.tvInstallPath)
        welcomeContainer = requireView(R.id.welcomeContainer)
        footerAction = requireView(R.id.footerAction)

        lorieView = requireView(R.id.lorieView)

        // GUI key bar + FAB must be bound before any listener uses them.
        fabKeyboard = requireView(R.id.fabKeyboard)
        fabKeyboard.visibility = View.GONE
        fabKeyboard.setOnClickListener { toggleSoftKeyboard() }
        guiKeyBar = requireView(R.id.guiKeyBar)

        btnGuiHome = requireView(R.id.btnGuiHome)
        btnGuiHome.visibility = View.GONE
        btnGuiHome.setOnClickListener {
            // Close GUI mode and go home
            tabLayout.getTabAt(0)?.select()
            switchToHomeTab()
            
            // Ensure keyboard and bar are hidden
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (isLateInit_mainRoot()) {
                imm.hideSoftInputFromWindow(mainRoot.windowToken, 0)
            }
            val lp = guiKeyBar.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.bottomMargin = 0
                guiKeyBar.layoutParams = lp
            }
            guiKeyBar.visibility = View.GONE
        }
        
        GuiLorieWiring.wireTouchAndPointer(lorieView, guiInputHandler)
        GuiLorieWiring.wireHoverScrollAndKeys(
            context = this,
            lorieView = lorieView,
            guiScaleX = { guiScaleX },
            guiScaleY = { guiScaleY },
            onDoubleEscHome = {
                tabLayout.getTabAt(0)?.select()
                switchToHomeTab()
                GuiLorieWiring.hideKeyboardAndKeybar(
                    this,
                    mainRoot,
                    guiKeyBar
                )
            }
        )
        GuiLorieWiring.wireSurfaceCallback(lorieView) { sx, sy ->
            guiScaleX = sx
            guiScaleY = sy
        }

        val x11Filter = IntentFilter("com.sg.linuxgo.x11.CmdEntryPoint.ACTION_START")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(x11Receiver, x11Filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(x11Receiver, x11Filter)
        }

        // Register terminate-from-notification receiver
        val terminateFilter = IntentFilter(MainActivity.ACTION_TERMINATE_SESSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(terminateReceiver, terminateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(terminateReceiver, terminateFilter)
        }

        // Multi-container setup
        containerManager = ContainerManager(this)
        containerManager.migrateLegacyIfNeeded()

        // Bootstrap must exist before guiSessionManager / terminalController are first accessed
        // (e.g. via updateAdapterActiveState → isX11SessionAlive).
        bootstrap = Bootstrap(this)

        // Initialize Terminal Session Manager callbacks
        terminalSessionManager.onSessionsChanged = {
            terminalSessionsState = terminalSessions.map { it.title }
            updateAdapterActiveState()
        }
        terminalSessionManager.onActiveSessionChanged = { index ->
            terminalActiveTabIndex = index
        }

        val prefsState = getSharedPreferences("pocket_linux_state", Context.MODE_PRIVATE)
        val restoredCid = prefsState.getString("active_container_id", null)
            ?: try {
                SessionKeepAliveService.activeContainerId
            } catch (_: Exception) {
                null
            }
        if (restoredCid != null) {
            // Keep id when guest, display server, or session FGS is still up (DeX / recreate).
            val live = isGuestRuntimeRunning() ||
                isDisplayServerRunning() ||
                try {
                    SessionKeepAliveService.isRunning || SessionLifecycleGate.isAllowed(this)
                } catch (_: Exception) {
                    false
                }
            if (live) {
                setActiveContainerId(restoredCid)
                Log.i("MainActivity", "Restored activeContainerId from state: $activeContainerId")
            } else {
                prefsState.edit().remove("active_container_id").apply()
                Log.i(
                    "MainActivity",
                    "Active container ID found in state but no live session evidence. Cleared."
                )
            }
        }
        wasInDeXMode = isDeXMode()

        rvContainers = requireView(R.id.rvContainers)
        containerConfigs = containerManager.getContainers()
        containerAdapter = object : ContainerCardController(containerManager.getContainers(), this) {
            override fun updateContainers(newContainers: List<ContainerConfig>) {
                super.updateContainers(newContainers)
                updateContainerConfigsState(newContainers)
            }
            override fun setInstallingContainer(containerId: String?) {
                super.setInstallingContainer(containerId)
                updateInstallingContainerIdState(containerId)
                // Always wipe card progress so abort→retry never mixes old MB/% with the new run.
                resetCardState()
                if (containerId == null) {
                    updateInstallProgressMessageState("")
                    updateInstallProgressPercentState(0)
                } else {
                    updateInstallProgressMessageState("Preparing...")
                    updateInstallProgressPercentState(0)
                }
            }
            override fun updateInstallProgress(message: String, percent: Int) {
                super.updateInstallProgress(message, percent)
                updateInstallProgressMessageState(message)
                // Use clamped (monotonic) percent from the controller
                updateInstallProgressPercentState(getInstallProgressPercent())
            }
            override fun setActiveContainer(containerId: String?, guiActive: Boolean, shellActive: Boolean) {
                super.setActiveContainer(containerId, guiActive, shellActive)
                // Drive Compose Resume/Stop flags from the same snapshot the adapter uses.
                viewModel.isGuiSessionActive = guiActive
                viewModel.isShellSessionActive = shellActive
                // Non-null: bind which container is live. Null means "not live" — do NOT
                // wipe activeContainerIdState (that made cards show "Open terminal" after
                // Back/Home even while PTYs were still running). Full clear is setActiveContainerId(null).
                if (containerId != null) {
                    updateActiveContainerIdState(containerId)
                }
            }
            override fun updateStats(containerId: String, storageUsedMB: Int, storageTotalMB: Int, ramUsedMB: Int, ramTotalMB: Int, systemUsedStorageMB: Int, systemUsedRamMB: Int) {
                super.updateStats(containerId, storageUsedMB, storageTotalMB, ramUsedMB, ramTotalMB, systemUsedStorageMB, systemUsedRamMB)
                val currentStats = containerStatsState.toMutableMap()
                val previous = currentStats[containerId]
                currentStats[containerId] = ContainerCardStats(
                    storageUsedMB = storageUsedMB,
                    storageTotalMB = storageTotalMB,
                    ramUsedMB = ramUsedMB,
                    ramTotalMB = ramTotalMB,
                    systemUsedStorageMB = systemUsedStorageMB,
                    systemUsedRamMB = systemUsedRamMB,
                    ramHistoryMB = appendRamHistorySample(
                        history = previous?.ramHistoryMB.orEmpty(),
                        sampleMb = ramUsedMB
                    )
                )
                updateContainerStatsState(currentStats)
            }
        }
        rvContainers.layoutManager = LinearLayoutManager(this)
        loadCachedStats()
        updateAdapterActiveState()
        btnAddContainer = requireView(R.id.btnAddContainer)
        btnAddContainer.setOnClickListener { showNewContainerWizard() }

        tvStepDescription = requireView(R.id.tvStepDescription)
        tvProgressStatus = requireView(R.id.tvProgressStatus)
        tvMiniLog = requireView(R.id.tvMiniLog)
        tvMiniLog.setText("", android.widget.TextView.BufferType.EDITABLE)
        miniLogScroll = requireView(R.id.miniLogScroll)
        progressBar = requireView(R.id.progressBar)
        tvTerminalOutput = requireView(R.id.tvTerminalOutput)
        etTerminalInput = requireView(R.id.etTerminalInput)
        tvTerminalOutput.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = true
            override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
            override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: android.view.ActionMode?) {
                // After copy/selection, return focus to the IME host (not the output TextView).
                ensureTerminalKeyboardReady()
            }
        }
        terminalCursor = requireView(R.id.terminalCursor)
        btnPrimaryAction = requireView(R.id.btnPrimaryAction)
        tabLayout = requireView(R.id.tabLayout)
        terminalScroll = requireView(R.id.terminalScroll)
        specialKeysScroll = requireView(R.id.specialKeysScroll)
        btnCopyLog = requireView(R.id.btnCopyLog)
        btnDownloadLog = requireView(R.id.btnDownloadLog)
        btnFullscreenLog = requireView(R.id.btnFullscreenLog)
        promptContainer = requireView(R.id.promptContainer)

        tabLayoutTerminal = requireView(R.id.tabLayoutTerminal)
        btnNewSession = requireView(R.id.btnNewSession)
        btnCloseSession = requireView(R.id.btnCloseSession)

        tabLayoutTerminal.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                tab?.let {
                    val index = it.position
                    if (index >= 0 && index < terminalSessions.size) {
                        activeSessionIndex = index
                        terminalActiveTabIndex = index
                        updateTerminalUi()
                    }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        btnNewSession.setOnClickListener {
            addNewTerminalSession()
        }

        btnCloseSession.setOnClickListener {
            closeActiveTerminalSession()
        }

        // Pinch-to-zoom gesture listener
        scaleGestureDetector = android.view.ScaleGestureDetector(activity, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                try {
                    val scaleFactor = detector.scaleFactor
                    if (scaleFactor.isNaN() || scaleFactor.isInfinite()) return false
                    val currentSize = tvTerminalOutput.textSize / resources.displayMetrics.scaledDensity
                    var newSize = currentSize * scaleFactor
                    newSize = newSize.coerceIn(4f, 30f)
                    tvTerminalOutput.textSize = newSize
                    
                    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this@performOnCreateSetup)
                    sharedPrefs.edit().putFloat("terminal_font_size", newSize).apply()
                    
                    recalculateActiveSessionSize()
                } catch (e: Exception) {
                    // Prevent crash during rapid zoom gestures
                }
                return true
            }
        })

        // Scroll vs Tap gesture keyboard listener
        val gestureDetector = android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                ensureTerminalKeyboardReady()
                return true
            }
        })

        val touchListener = android.view.View.OnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            val res = if (scaleGestureDetector.isInProgress) {
                true
            } else {
                v.onTouchEvent(event)
            }
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                tvTerminalOutput.postDelayed({
                    if (!tvTerminalOutput.hasSelection()) {
                        ensureTerminalKeyboardReady()
                    }
                }, 100)
            }
            res
        }

        terminalScroll.setOnTouchListener(touchListener)
        tvTerminalOutput.setOnTouchListener(touchListener)

        terminalScroll.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = (right - left) != (oldRight - oldLeft)
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (widthChanged || heightChanged) {
                recalculateActiveSessionSize()
                scrollCursorIntoView(force = true)
            }
        }

        tvTerminalOutput.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (heightChanged) {
                scrollCursorIntoView(force = false)
            }
        }

        // Native text selection is enabled; standard long-press handles text selection natively.
        
        miniLogScroll.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    lastMiniLogInteractionTime = System.currentTimeMillis()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    lastMiniLogInteractionTime = System.currentTimeMillis()
                }
            }
            false
        }

        miniLogScroll.setOnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            if (isProgrammaticScroll) {
                isProgrammaticScroll = false
                return@setOnScrollChangeListener
            }
            val view = v as ScrollView
            val child = view.getChildAt(0)
            if (child != null) {
                val diff = child.bottom - (view.height + view.scrollY)
                if (diff <= 15) {
                    lastMiniLogInteractionTime = 0L
                } else {
                    lastMiniLogInteractionTime = System.currentTimeMillis()
                }
            }
        }
        
        btnFullscreenLog.setOnClickListener {
            isMiniLogFullscreen = !isMiniLogFullscreen
            val lp = miniLogScroll.layoutParams
            if (isMiniLogFullscreen) {
                lp.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                btnFullscreenLog.setIconResource(R.drawable.ic_fullscreen_exit)
            } else {
                val density = resources.displayMetrics.density
                lp.height = (400 * density).toInt()
                btnFullscreenLog.setIconResource(R.drawable.ic_fullscreen)
            }
            miniLogScroll.layoutParams = lp
        }

        val btnBackup: MaterialButton = requireView(R.id.btnBackup)
        val btnRestore: MaterialButton = requireView(R.id.btnRestore)
        btnBackup.setOnClickListener { confirmBackup() }
        btnRestore.setOnClickListener { confirmRestore() }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> switchToHomeTab()
                    1 -> switchToSetupTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        btnCopyLog.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Setup Log", tvMiniLog.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        downloadLogLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let {
                try {
                    contentResolver.openOutputStream(it)?.use { os ->
                        os.write(tvMiniLog.text.toString().toByteArray())
                    }
                    Toast.makeText(this, "Log saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to save log", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnDownloadLog.setOnClickListener {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            downloadLogLauncher.launch("linux_mobile_setup_$timestamp.txt")
        }

        // GUI/Shell launch is handled by Compose card callbacks.
        
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val onTerminal = terminalView.visibility == View.VISIBLE ||
                    currentScreen == Screen.TERMINAL
                if (onTerminal) {
                    // Detach to Home; keep PTYs/tabs so Resume terminal works.
                    tabLayout.getTabAt(0)?.select()
                    leaveTerminalToHome()
                    return
                }

                if (guiContainer.visibility == View.VISIBLE || currentScreen == Screen.GUI) {
                    // Do nothing when back is pressed in GUI mode of container
                    return
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        
        btnToggleLogs.setOnClickListener {
            if (logCardView.visibility == View.VISIBLE) {
                logCardView.visibility = View.GONE
                btnToggleLogs.text = "More Information ▼"
            } else {
                logCardView.visibility = View.VISIBLE
                btnToggleLogs.text = "Hide Information ▲"
                miniLogScroll.post { miniLogScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        
        loadSettings()

        bootstrap.overrideGuiMode(selectedGuiMode)

        when {
            SetupForegroundService.isRunningStatic -> {
                welcomeContainer.visibility = View.GONE
                btnPrimaryAction.isEnabled = false
                switchToHomeTab()
                hydrateInstallStateFromService()
                logToMini("Reconnected to running setup service…")
            }
            // SUCCESS while activity was dead — apply before home setup so cards are READY.
            SetupForegroundService.lastCompleteSuccess == true -> {
                welcomeContainer.visibility = View.GONE
                switchToHomeTab()
                hydrateInstallCompletionFromService()
            }
            containerManager.isAnyContainerInstalled() -> {
                // If containers are installed, initialize home/dashboard directly
                isSetupComplete = true
                isTaskInProgress = false
                
                welcomeContainer.visibility = View.GONE
                tvStepDescription.visibility = View.GONE
                tvProgressStatus.visibility = View.GONE
                btnPrimaryAction.visibility = View.GONE
                progressBar.visibility = View.GONE
                checklistContainer.visibility = View.GONE
                btnToggleLogs.visibility = View.VISIBLE
                logCardView.visibility = View.GONE
                metricsCard.visibility = View.VISIBLE
                footerAction.visibility = View.GONE
                tabLayout.visibility = View.VISIBLE
                
                containerAdapter.updateContainers(containerManager.getContainers())
                // ViewModel may still hold a pre-destroy installing id; clear so cards
                // show Launch / Terminal instead of a frozen progress bar.
                containerAdapter.setInstallingContainer(null)
                installingContainerIdState = null
                switchToHomeTab()
            }
            else -> {
                // Skip welcome screen and show main dashboard directly
                welcomeContainer.visibility = View.GONE
                tabLayout.visibility = View.VISIBLE
                switchToHomeTab()
                
                // Hide other setup-related elements
                btnPrimaryAction.visibility = View.GONE
                progressBar.visibility = View.GONE
                tvProgressStatus.visibility = View.GONE
                checklistContainer.visibility = View.GONE
            }
        }

        btnPrimaryAction.setOnClickListener {
            welcomeContainer.visibility = View.GONE
            showNewContainerWizard(autoInstall = true)
        }
        
        setupTerminalInput()
        startStatsPoller()
        setContent {
            MainScreen(
                context = this,
                viewModel = viewModel,
                terminalSessions = terminalSessions,
                sessionThemes = sessionThemes,
                promptContainer = promptContainer,
                lorieView = lorieView,
                guiImeAnchor = guiImeAnchor,
                terminalScroll = terminalScroll,
                etTerminalInput = etTerminalInput,
                actions = MainScreenActions(
                    onAddContainer = {
                        showNewContainerWizardState = true
                    },
                    onLaunchGui = { onLaunchGui(it) },
                    onLaunchShell = { onLaunchShell(it) },
                    onInstall = { onInstall(it) },
                    onSettings = { onSettings(it) },
                    onViewLogs = { returnToContainerId ->
                        logsReturnToContainerId = returnToContainerId
                        switchToSetupTab()
                    },
                    onTerminate = { onTerminate(it) },
                    onAbortInstall = { onAbortInstall(it) },
                    onDelete = { onDelete(it) },
                    onDeleteConfirmed = { handleOnDeleteConfirmed(it) },
                    onBackupClick = { confirmBackup() },
                    onRestoreClick = { confirmRestore() },
                    onToggleSetupLogs = {
                        if (logCardView.visibility == View.VISIBLE) {
                            logCardView.visibility = View.GONE
                        } else {
                            logCardView.visibility = View.VISIBLE
                            miniLogScroll.post { miniLogScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    },
                    onDownloadSetupLog = {
                        val timestamp = java.text.SimpleDateFormat(
                            "yyyyMMdd_HHmmss",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date())
                        downloadLogLauncher.launch("linux_mobile_setup_$timestamp.txt")
                    },
                    onCopySetupLog = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Setup Log", tvMiniLog.text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onKeyboardToggle = { toggleSoftKeyboard() },
                    onGoHomeFromGui = {
                        tabLayout.getTabAt(0)?.select()
                        switchToHomeTab()
                    },
                    onGoHomeFromTerminal = {
                        tabLayout.getTabAt(0)?.select()
                        leaveTerminalToHome()
                    },
                    onGuiKeyPressed = { onGuiKeyPressed(it) },
                    onTerminalKeyPressed = { onTerminalKeyPressed(it) },
                    onRequestTerminalKeyboard = { ensureTerminalKeyboardReady() },
                    onHideTerminalKeyboard = { hideTerminalImeNow() },
                    onSetTerminalImeSuppressed = { setTerminalImeSuppressed(it) },
                    onTerminalGridSizeChanged = { rows, cols, charW, charH ->
                        terminalController.resizeActiveSessionFromGrid(rows, cols, charW, charH)
                    },
                    onTerminalMouseEvent = { col, row, button, isRelease, isMotion ->
                        terminalSessions.getOrNull(activeSessionIndex)
                            ?.writeMouseEvent(col, row, button, isRelease, isMotion)
                    },
                    onTerminalScrollSteps = { steps, col, row ->
                        terminalSessions.getOrNull(activeSessionIndex)
                            ?.writeScrollSteps(steps, col, row)
                    },
                    onNewTerminalSession = { addNewTerminalSession() },
                    onCloseTerminalSession = { closeActiveTerminalSession() },
                    onCloseTerminalSessionAt = { closeTerminalSessionAt(it) },
                    onDuplicateTerminalSession = { duplicateTerminalSession(it) },
                    onRenameTerminalSession = { index, name -> renameTerminalSession(index, name) },
                    onTerminalTabSelected = { index ->
                        tabLayoutTerminal.getTabAt(index)?.select()
                    },
                    onTerminalThemeSelected = { themeId ->
                        terminalController.applyAppThemeToSessions(themeId)
                        updateStatusBarColorAndIcons()
                        applyTerminalThemeToLegacyViews()
                    },
                    onNavigate = { screen ->
                        when (screen) {
                            Screen.HOME -> switchToHomeTab()
                            Screen.SETUP -> switchToSetupTab()
                            Screen.SETTINGS -> switchToSettingsTab()
                            else -> {}
                        }
                    },
                    onContainerCreated = { config ->
                        showNewContainerWizardState = false
                        getSharedPreferences("container_${config.id}_settings", Context.MODE_PRIVATE)
                            .edit()
                            .putString("gui_mode", config.guiMode)
                            .apply()
                        containerAdapter.updateContainers(containerManager.getContainers())
                        btnAddContainer.visibility =
                            if (containerManager.canAddMore()) View.VISIBLE else View.GONE
                        setActiveContainerId(config.id)
                        bootstrap.applyContainerPreset(config)
                        containerAdapter.setInstallingContainer(config.id)
                        startFullBootstrap()
                    },
                    onContainerWizardDismissed = { onContainerWizardDismissed() },
                    onGlobalSettingsSaved = { onGlobalSettingsSaved() },
                    onContainerSettingsSaved = {
                        loadSettings()
                        // Display scale lives in Lorie prefs; refresh framebuffer if GUI is open
                        onGlobalSettingsSaved()
                    },
                    onTerminalPrefsChanged = { applyTerminalThemeToLegacyViews() },
                    onStatusBarThemeRefresh = {
                        updateStatusBarColorAndIcons()
                        applyTerminalThemeToLegacyViews()
                    }
                )
            )
        }
}
