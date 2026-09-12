package com.sg.linuxgo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * SetupForegroundService
 *
 * Runs the Bootstrap installation fully in the background as a foreground service.
 * This allows the user to close/minimize the app while setup continues.
 *
 * Communication with MainActivity happens via LocalBroadcast:
 *  - Outbound (service → activity): BROADCAST_PROGRESS, BROADCAST_DOWNLOAD_PROGRESS,
 *      BROADCAST_CHOICE_REQUIRED, BROADCAST_INPUT_REQUIRED, BROADCAST_SUCCESS, BROADCAST_ERROR
 *  - Inbound  (activity → service): BROADCAST_HANDLE_CHOICE, BROADCAST_HANDLE_INPUT
 *
 * When the app is in the background, notifications display current progress.
 * When a user choice or input is required, a persistent "Action Required" notification
 * is shown with an "Open App" action button.
 */
class SetupForegroundService : Service() {

    companion object {
        const val TAG = "SetupForegroundService"

        // ── Notification channel & IDs ──────────────────────────────────────────
        const val CHANNEL_SETUP    = "pocketlinux_setup"
        const val NOTIF_PROGRESS   = 1001
        const val NOTIF_INPUT      = 1002
        /** Auto-cancel completion notification (shown after FGS progress is removed). */
        const val NOTIF_COMPLETE   = 1003

        // ── Intent actions ──────────────────────────────────────────────────────
        const val ACTION_START     = "com.sg.linuxgo.action.START_SETUP"
        const val ACTION_STOP      = "com.sg.linuxgo.action.STOP_SETUP"

        // ── LocalBroadcast: service → activity ──────────────────────────────────
        const val BROADCAST_PROGRESS          = "lm.broadcast.PROGRESS"
        const val BROADCAST_DOWNLOAD_PROGRESS = "lm.broadcast.DOWNLOAD_PROGRESS"
        const val BROADCAST_CHOICE_REQUIRED   = "lm.broadcast.CHOICE_REQUIRED"
        const val BROADCAST_INPUT_REQUIRED    = "lm.broadcast.INPUT_REQUIRED"
        const val BROADCAST_SUCCESS           = "lm.broadcast.SUCCESS"
        const val BROADCAST_ERROR             = "lm.broadcast.ERROR"
        /** Raw script output lines — streamed into the command output log verbatim */
        const val BROADCAST_LOG_LINE          = "lm.broadcast.LOG_LINE"

        // ── LocalBroadcast: activity → service ──────────────────────────────────
        const val BROADCAST_HANDLE_CHOICE     = "lm.broadcast.HANDLE_CHOICE"
        const val BROADCAST_HANDLE_INPUT      = "lm.broadcast.HANDLE_INPUT"

        // ── Intent extras ───────────────────────────────────────────────────────
        const val EXTRA_MESSAGE      = "message"
        const val EXTRA_STEP         = "step"
        const val EXTRA_CHOICES      = "choices"    // ArrayList<String> of "id|label"
        const val EXTRA_CHOICE_ID    = "choice_id"
        const val EXTRA_INPUT_TITLE  = "input_title"
        const val EXTRA_INPUT_HINT   = "input_hint"
        const val EXTRA_INPUT_VALUE  = "input_value"
        const val EXTRA_FILE_NAME    = "file_name"
        const val EXTRA_PROGRESS     = "progress"
        const val EXTRA_CURRENT      = "current"
        const val EXTRA_TOTAL        = "total"
        const val EXTRA_ERROR        = "error"
        const val EXTRA_CONTAINER_ID = "container_id"

        // Container config JSON passed from the wizard for preset-based installs
        const val EXTRA_CONTAINER_CONFIG = "container_config"
        @Volatile var pendingContainerConfigJson: String? = null

        // ── Static pending-state snapshot (survives receiver-registration races) ──
        // Bootstrap runs on a background thread. The first onChoiceRequired fires
        // immediately and the broadcast may be sent BEFORE MainActivity.onResume()
        // finishes registering its LocalBroadcast receiver, causing the prompt to
        // be silently dropped. Storing the state here lets MainActivity hydrate the
        // UI whenever it (re-)registers the receiver.
        @Volatile var pendingStep    : String?                  = null
        @Volatile var pendingChoices : ArrayList<String>?       = null   // "id|label" pairs
        @Volatile var pendingInputTitle: String?                = null
        @Volatile var pendingInputHint : String?                = null
        @Volatile var isRunningStatic  : Boolean                = false
        @Volatile var lastMessage      : String                 = "Starting setup…"
        /** Last overall install percent (0–100), for UI reconnect when app was closed. */
        @Volatile var lastProgressPercent: Int                  = -1
        /** Last download byte counts for card reconnect (image/rootfs). */
        @Volatile var lastDownloadCurrent: Long                 = -1L
        @Volatile var lastDownloadTotal: Long                   = -1L
        /** Set by MainActivity.onResume / onPause to suppress notifications while app is visible */
        @Volatile var isActivityVisible: Boolean                = false
        @Volatile var activeInstallingContainerId: String?      = null
        /**
         * Completion snapshot for UI reconnect when SUCCESS/ERROR LocalBroadcast was
         * missed (receiver unregistered in onPause). Survives service onDestroy;
         * consumed by MainActivity on resume.
         */
        @Volatile var lastCompleteSuccess: Boolean?             = null
        @Volatile var lastCompleteContainerId: String?          = null
        @Volatile var lastCompleteMessage: String               = ""

        // ── Install log buffer (survives app close / receiver unregister) ────────
        // LocalBroadcast is dropped while MainActivity is paused/destroyed. All
        // script output is kept here so the UI can hydrate when the user returns.
        private const val MAX_LOG_LINES = 2000
        private val logBuffer = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun clearLogBuffer() {
            synchronized(logBuffer) { logBuffer.clear() }
        }

        fun getLogSnapshot(): String {
            synchronized(logBuffer) {
                return logBuffer.joinToString("\n")
            }
        }

        fun appendLogLine(line: String) {
            if (SetupLogSupport.isInstallLogNoise(line)) return
            // Honor \r in-place progress updates (same semantics as the mini-log UI).
            // First progress frame arrives without \r (opens a row); later frames
            // arrive with leading \r and replace that row. Strip ANSI for snapshots.
            val hasCr = line.contains('\r')
            val withoutCr = line.substringAfterLast('\r')
            val clean = withoutCr
                .replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
                .replace(Regex("\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)"), "")
                .replace(Regex("\u001B."), "")
                .replace("\u001B", "")
                .replace("\r", "")
            // Skip empty noise after stripping control sequences.
            if (clean.isBlank()) return
            synchronized(logBuffer) {
                if (hasCr && logBuffer.isNotEmpty()) {
                    logBuffer[logBuffer.lastIndex] = clean
                } else {
                    logBuffer.add(clean)
                    while (logBuffer.size > MAX_LOG_LINES) {
                        logBuffer.removeAt(0)
                    }
                }
            }
        }

        /** Clear all pending-state. Call after the user satisfies a prompt. */
        fun clearPending() {
            pendingStep     = null
            pendingChoices  = null
            pendingInputTitle = null
            pendingInputHint  = null
        }

        // Helper: start the service from an Activity
        fun start(context: Context, containerConfigJson: String? = null) {
            pendingContainerConfigJson = containerConfigJson
            val intent = Intent(context, SetupForegroundService::class.java).apply {
                action = ACTION_START
                if (containerConfigJson != null) {
                    putExtra(EXTRA_CONTAINER_CONFIG, containerConfigJson)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // Helper: stop the service
        fun stop(context: Context) {
            // Clear static flags synchronously so the activity ignores late download
            // broadcasts from dying multi-thread workers before onDestroy runs.
            isRunningStatic = false
            activeInstallingContainerId = null
            lastDownloadCurrent = -1L
            lastDownloadTotal = -1L
            lastProgressPercent = -1
            lastMessage = "Starting setup…"
            // User abort — do not leave a stale "complete" snapshot for onResume.
            lastCompleteSuccess = null
            lastCompleteContainerId = null
            lastCompleteMessage = ""
            context.stopService(Intent(context, SetupForegroundService::class.java))
        }

        /** Consume completion snapshot after UI has applied it. */
        fun clearLastComplete() {
            lastCompleteSuccess = null
            lastCompleteContainerId = null
            lastCompleteMessage = ""
        }
    }

    // ── Instance state ───────────────────────────────────────────────────────────
    private var bootstrap: Bootstrap? = null
    private var isRunning = false
    private var activeContainerId: String? = null
    private var lastProgressMessage = "Initializing…"
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    /** Last start intent (holds EXTRA_CONTAINER_CONFIG across redelivery). */
    private var lastStartIntent: Intent? = null
    /** Periodically re-acquires the WakeLock to prevent Android from releasing it */
    private val wakeLockRenewalHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wakeLockRenewalInterval = 30L * 60 * 1000 // 30 minutes
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Caps SystemUI notification traffic. Multi-thread download can emit hundreds of
     * progress events/sec; posting each freezes the status-bar clock on Samsung One UI.
     */
    private val notifUpdater = ThrottledNotificationUpdater(
        minIntervalMs = 600L,
        maxIntervalMs = 1500L
    ) { message, progress, deterministic ->
        publishProgressNotificationNow(message, progress, deterministic)
    }

    /** Caps LocalBroadcast download ticks so the container card stays smooth without flooding UI. */
    private val cardProgressBroadcaster = ThrottledProgressBroadcaster(minIntervalMs = 120L) {
            fileName, progress, current, total ->
        broadcast(Intent(BROADCAST_DOWNLOAD_PROGRESS).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_CURRENT, current)
            putExtra(EXTRA_TOTAL, total)
            activeContainerId?.let { putExtra(EXTRA_CONTAINER_ID, it) }
        })
    }

    // BroadcastReceiver for handling choices/input from the Activity
    private val inputReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BROADCAST_HANDLE_CHOICE -> {
                    val choiceId = intent.getStringExtra(EXTRA_CHOICE_ID) ?: return
                    Log.d(TAG, "Received choice: $choiceId")
                    // Clear cached pending state (multi-select "done" also clears)
                    if (choiceId == "done" || pendingChoices?.none { it.startsWith("done|") } == true) {
                        clearPending()
                    }
                    bootstrap?.handleChoice(choiceId)
                    // Revert notification back to progress mode (keep overall %)
                    repostLastProgressNotification()
                }
                BROADCAST_HANDLE_INPUT -> {
                    val value = intent.getStringExtra(EXTRA_INPUT_VALUE) ?: return
                    Log.d(TAG, "Received input: $value")
                    clearPending()
                    bootstrap?.handleInput(value)
                    repostLastProgressNotification()
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // FGS contract first — before WakeLock / Wi‑Fi lock (Crash D deadline).
        createNotificationChannel()
        try {
            startForeground(
                NOTIF_PROGRESS,
                NotificationCompat.Builder(this, CHANNEL_SETUP)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("PocketLinux Setup")
                    .setContentText("Starting…")
                    .setOngoing(true)
                    .setSilent(true)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .setProgress(100, 0, true)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "onCreate startForeground failed", e)
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::InstallationLock").apply {
                acquire(180 * 60 * 1000L /* 180 minutes max — KDE on Debian can take up to 2h on slow devices */)
            }
            // Schedule periodic WakeLock renewal every 30 minutes
            // Android may silently release long-held WakeLocks; this keeps the CPU alive
            scheduleWakeLockRenewal()
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock after FGS promote: ${e.message}")
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG::WifiLock").apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock after FGS promote: ${e.message}")
        }

        // Register inbound receiver
        val filter = android.content.IntentFilter().apply {
            addAction(BROADCAST_HANDLE_CHOICE)
            addAction(BROADCAST_HANDLE_INPUT)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(inputReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Drop flags immediately so the UI ignores in-flight download ticks
                // while onDestroy tears the service down.
                isRunning = false
                isRunningStatic = false
                activeInstallingContainerId = null
                lastDownloadCurrent = -1L
                lastDownloadTotal = -1L
                lastProgressPercent = -1
                notifUpdater.reset()
                cardProgressBroadcaster.reset()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (intent != null) lastStartIntent = intent
                // Keep companion pending in sync with redelivered intent extras.
                intent?.getStringExtra(EXTRA_CONTAINER_CONFIG)?.let { json ->
                    if (pendingContainerConfigJson == null) pendingContainerConfigJson = json
                }
                if (!isRunning) {
                    isRunning = true
                    isRunningStatic = true
                    lastProgressPercent = 0
                    lastDownloadCurrent = -1L
                    lastDownloadTotal = -1L
                    lastMessage = "Starting setup…"
                    lastProgressMessage = "Starting setup…"
                    lastCompleteSuccess = null
                    lastCompleteContainerId = null
                    lastCompleteMessage = ""
                    notifUpdater.reset()
                    cardProgressBroadcaster.reset()
                    clearLogBuffer()
                    appendLogLine("Starting setup…")
                    // Drop any leftover completion/input notifs from a prior run.
                    try {
                        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_COMPLETE)
                        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_INPUT)
                    } catch (_: Exception) {
                    }
                    // Promote to foreground immediately with an initial notification
                    publishProgressNotificationNow("Starting setup…", 0, deterministic = false)
                    // Small delay so MainActivity.onResume() can register its receiver
                    // before the first onChoiceRequired broadcast fires.
                    mainHandler.postDelayed({
                        beginBootstrap()
                    }, 400)
                }
            }
        }
        // We use START_REDELIVER_INTENT so Android restarts us with the last intent
        // if we are killed while setup is in progress.
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLockRenewalHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        notifUpdater.reset()
        cardProgressBroadcaster.reset()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(inputReceiver)
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        // Mark stopped before abort so late download-thread callbacks are ignored.
        isRunning = false
        isRunningStatic = false
        activeInstallingContainerId = null
        lastDownloadCurrent = -1L
        lastDownloadTotal = -1L
        lastProgressPercent = -1
        lastMessage = "Starting setup…"
        lastProgressMessage = "Initializing…"
        bootstrap?.stopDiskPoller()
        bootstrap?.abort()
        clearPending()
    }

    /**
     * Called when the user swipes the app away from Recent Apps.
     * We do NOT stop the service — installation must continue in the background.
     * Android will call onDestroy() separately if it actually needs to kill us.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do NOT call stopSelf() — let the foreground service continue
        Log.d(TAG, "Task removed (app swiped away) — installation continues in background")
    }

    /** Re-acquire the WakeLock periodically to prevent Android from releasing it */
    private fun scheduleWakeLockRenewal() {
        wakeLockRenewalHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    wakeLock?.let { wl ->
                        if (!wl.isHeld) {
                            wl.acquire(180 * 60 * 1000L)
                            Log.d(TAG, "WakeLock renewed")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "WakeLock renewal failed: ${e.message}")
                }
                if (isRunning) {
                    wakeLockRenewalHandler.postDelayed(this, wakeLockRenewalInterval)
                }
            }
        }, wakeLockRenewalInterval)
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Bootstrap
    // ────────────────────────────────────────────────────────────────────────────

    private fun beginBootstrap() {
        bootstrap = Bootstrap(this)

        // Apply container preset from companion cache or redelivered intent extra
        // (static pending is lost if the process is killed mid-install).
        val configJson = pendingContainerConfigJson
            ?: lastStartIntent?.getStringExtra(EXTRA_CONTAINER_CONFIG)
        if (configJson != null) {
            try {
                val json = org.json.JSONObject(configJson)
                val config = ContainerConfig.fromJson(json)
                activeContainerId = config.id
                activeInstallingContainerId = config.id
                bootstrap!!.applyContainerPreset(config)
                Log.d(TAG, "Container preset applied: ${config.distro} / ${config.de}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply container preset", e)
            }
            pendingContainerConfigJson = null
        }

        bootstrap!!.startInstallation(object : Bootstrap.BootstrapCallback {

            override fun onProgress(message: String) {
                if (!isRunning || !isRunningStatic) return
                // Stamp for mini-log only — never put a changing clock into the notification
                // (that alone thrash-updates SystemUI every second).
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val stamped = "[$timestamp] $message"
                val clean = stripTimestamp(message)

                // Animated dots ("Phase….") update log/card text, not the status-bar notif rate.
                val isAnimatedDots = clean.endsWith("...") || clean.endsWith("..") ||
                    (clean.endsWith(".") && clean.contains("Phase "))

                if (isLogWorthyProgress(message) && !isAnimatedDots) {
                    appendLogLine(stamped)
                }

                // Keep reconnect snapshot as clean phase text (card hydrates from this).
                if (!isAnimatedDots) {
                    lastProgressMessage = clean
                    // Don't overwrite a "Downloading: N%" style lastMessage with a long phase
                    // unless this is a real high-level step.
                    if (isLogWorthyProgress(message) || lastMessage.isBlank() ||
                        lastMessage.equals("Starting setup…", ignoreCase = true) ||
                        lastMessage.equals("Initializing…", ignoreCase = true)
                    ) {
                        lastMessage = clean
                    }
                }

                // Card/log broadcast always (cheap). Notification only on meaningful phases.
                broadcast(Intent(BROADCAST_PROGRESS).putExtra(EXTRA_MESSAGE, stamped))

                if (!isAnimatedDots && isLogWorthyProgress(message)) {
                    val pct = lastProgressPercent
                    if (pct in 0..100) {
                        queueProgressNotification(clean, pct, deterministic = true)
                    } else {
                        queueProgressNotification(clean, 0, deterministic = false)
                    }
                }
            }

            override fun onLogLine(line: String) {
                // Raw script output — always buffer (broadcasts are dropped when app is closed).
                // Do NOT update the notification (avoids flooding it with apk lines).
                if (SetupLogSupport.isInstallLogNoise(line)) return
                appendLogLine(line)
                broadcast(Intent(BROADCAST_LOG_LINE).putExtra(EXTRA_MESSAGE, line))
            }

            override fun onDownloadProgress(fileName: String, progress: Int, current: Long, total: Long) {
                // Drop ticks after abort/stop so a dying multi-thread download cannot
                // fight a fresh install's labels on the card.
                if (!isRunning || !isRunningStatic) return

                // Bootstrap already maps progress into the active install-method overall band
                // (prebuilt 3→70 download / 76→94 extract, or legacy 0→35 / 50→95 script).
                // Keep monotonic so multi-thread download races never reverse the bar.
                val incoming = progress.coerceIn(0, 100)
                val pct = if (lastProgressPercent in 0..100) {
                    maxOf(lastProgressPercent, incoming)
                } else {
                    incoming
                }
                lastProgressPercent = pct

                // Phase-style labels should keep human phase text on the notification so it
                // matches the container card. Real downloads keep "Name: N% · size".
                // Installing/extract use overall % only — never MB (totals are not file bytes).
                val isPhaseStyle = isPhaseStyleProgressLabel(fileName)
                val showSize = !isPhaseStyle &&
                    InstallCardProgress.showsDownloadSize(fileName, total, current)

                if (showSize) {
                    if (total > 0) {
                        lastDownloadCurrent = current.coerceAtLeast(0L)
                        lastDownloadTotal = total
                    } else if (current > 0) {
                        lastDownloadCurrent = current
                    }
                } else {
                    lastDownloadCurrent = -1L
                    lastDownloadTotal = -1L
                }

                val sizePart = if (showSize) {
                    InstallCardProgress.formatDownloadSize(
                        if (lastDownloadCurrent >= 0) lastDownloadCurrent else current.coerceAtLeast(0L),
                        if (lastDownloadTotal > 0) lastDownloadTotal else total
                    )
                } else {
                    ""
                }

                val msg = if (isPhaseStyle) {
                    val existing = stripTimestamp(lastProgressMessage)
                        .trimEnd('.', ' ')
                        .ifBlank {
                            stripTimestamp(lastMessage).trimEnd('.', ' ')
                        }
                    if (existing.isNotBlank() &&
                        !existing.equals("Initializing…", ignoreCase = true) &&
                        !existing.equals("Starting setup…", ignoreCase = true) &&
                        !existing.startsWith("System Setup")
                    ) {
                        existing
                    } else {
                        fileName
                    }
                } else if (sizePart.isNotEmpty()) {
                    // Same string the container card shows (includes download size).
                    "$fileName: $pct% · $sizePart"
                } else {
                    "$fileName: $pct%"
                }

                lastProgressMessage = msg
                // Keep lastMessage as the phase description for UI reconnect;
                // only overwrite with download-style text for real downloads.
                if (!isPhaseStyle) {
                    lastMessage = msg
                }

                // Notification + card use the same monotonic overall %.
                // Both are rate-limited so SystemUI / main thread stay healthy.
                val forceNotif = pct >= 100 || isPhaseStyle ||
                    fileName.startsWith("Downloading") && pct <= 3 ||
                    fileName.startsWith("Installing") && pct <= 76
                queueProgressNotification(msg, pct, deterministic = true, force = forceNotif)
                // When not showing size, broadcast -1 so UI clears any prior A/B MB.
                val outCurrent = if (showSize) current else -1L
                val outTotal = if (showSize) total else -1L
                cardProgressBroadcaster.update(
                    fileName, pct, outCurrent, outTotal,
                    force = forceNotif || pct >= 100 || !showSize
                )
            }

            override fun onChoiceRequired(step: Bootstrap.SetupStep, choices: List<Bootstrap.Choice>) {
                val stepName = step.name
                val choiceStrings = ArrayList(choices.map { "${it.id}|${it.label}" })

                // Cache state so MainActivity can hydrate even if broadcast was missed
                pendingStep     = stepName
                pendingChoices  = choiceStrings
                pendingInputTitle = null
                pendingInputHint  = null

                // Only show notification when app is NOT visible
                if (!isActivityVisible) {
                    postInputRequiredNotification("Action Required", "Setup needs your input — tap to open app")
                }

                broadcast(Intent(BROADCAST_CHOICE_REQUIRED).apply {
                    putExtra(EXTRA_STEP, stepName)
                    putStringArrayListExtra(EXTRA_CHOICES, choiceStrings)
                })
            }

            override fun onInputRequired(step: Bootstrap.SetupStep, title: String, hint: String) {
                // Cache state
                pendingStep      = step.name
                pendingChoices   = null
                pendingInputTitle = title
                pendingInputHint  = hint

                // Only show notification when app is NOT visible
                if (!isActivityVisible) {
                    postInputRequiredNotification("Input Required", title)
                }

                broadcast(Intent(BROADCAST_INPUT_REQUIRED).apply {
                    putExtra(EXTRA_STEP,        step.name)
                    putExtra(EXTRA_INPUT_TITLE, title)
                    putExtra(EXTRA_INPUT_HINT,  hint)
                })
            }

            override fun onSuccess() {
                val cid = activeInstallingContainerId ?: activeContainerId
                val containerManager = ContainerManager(this@SetupForegroundService)
                if (cid != null) {
                    containerManager.markInstalled(cid)
                } else {
                    // Fallback: any rootfs that has launch.sh but is still PENDING
                    containerManager.reconcileInstalledFlags()
                }
                clearPending()
                isRunningStatic = false
                val completedId = cid
                activeInstallingContainerId = null
                lastProgressPercent = 100
                lastMessage = "✓ Installation complete!"
                lastProgressMessage = lastMessage
                // Snapshot for UI if SUCCESS broadcast is missed while app is paused.
                lastCompleteSuccess = true
                lastCompleteContainerId = completedId
                lastCompleteMessage = "Installation complete. System ready."
                cardProgressBroadcaster.update("Complete", 100, 100L, 100L, force = true)
                broadcast(Intent(BROADCAST_SUCCESS).apply {
                    if (completedId != null) putExtra(EXTRA_CONTAINER_ID, completedId)
                })
                // Drop ongoing FGS progress. When the app is backgrounded/closed, leave a
                // tappable "Installation Complete" notification; foreground UI handles SUCCESS.
                if (!isActivityVisible) {
                    finishWithSuccessNotification(
                        title = "Installation Complete",
                        message = "PocketLinux setup finished. Tap to open and launch."
                    )
                } else {
                    dismissProgressNotificationOnly()
                }
                stopSelf()
            }

            override fun onError(error: String) {
                clearPending()
                isRunningStatic = false
                activeInstallingContainerId = null
                lastCompleteSuccess = false
                lastCompleteContainerId = null
                lastCompleteMessage = error
                notifUpdater.cancelPending()
                broadcast(Intent(BROADCAST_ERROR).putExtra(EXTRA_ERROR, error))
                // Remove ongoing progress notif; error notif uses a different id.
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION") stopForeground(true)
                    }
                    getSystemService(NotificationManager::class.java)?.cancel(NOTIF_PROGRESS)
                    getSystemService(NotificationManager::class.java)?.cancel(NOTIF_INPUT)
                } catch (_: Exception) {
                }
                postErrorNotification(error)
                stopSelf()
            }
        })
    }

    /**
     * Labels that advance overall % without a "Name: N%" status line
     * (must match [InstallCardProgress.applyDownloadProgress]).
     */
    private fun isPhaseStyleProgressLabel(fileName: String): Boolean {
        if (fileName == "System Setup" ||
            fileName == "Finalizing" ||
            fileName == "Complete" ||
            fileName == "Resume" ||
            fileName == "Catalog" ||
            fileName == "DE Convert"
        ) {
            return true
        }
        // Prebuilt path uses "Image cached (vX.Y.Z)" etc.
        return fileName.startsWith("Image cached") ||
            fileName.startsWith("Verifying")
    }

    /** Re-post last known overall progress (never reset bar to indeterminate 0%). */
    private fun repostLastProgressNotification() {
        val pct = lastProgressPercent
        val msg = stripTimestamp(lastProgressMessage).ifBlank { "Setup in progress…" }
        if (pct in 0..100) {
            queueProgressNotification(msg, pct, deterministic = true, force = true)
        } else {
            queueProgressNotification(msg, 0, deterministic = false, force = true)
        }
    }

    private fun stripTimestamp(message: String): String {
        return if (message.startsWith("[") && message.contains("] ")) {
            message.substringAfter("] ")
        } else {
            message
        }
    }

    private fun queueProgressNotification(
        message: String,
        progress: Int,
        deterministic: Boolean,
        force: Boolean = false
    ) {
        notifUpdater.update(
            message = stripTimestamp(message),
            progress = progress,
            deterministic = deterministic,
            force = force
        )
    }

    /** Drop ongoing FGS progress without posting a completion notification. */
    private fun dismissProgressNotificationOnly() {
        notifUpdater.cancelPending()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (_: Exception) {
        }
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIF_PROGRESS)
            getSystemService(NotificationManager::class.java)?.cancel(NOTIF_INPUT)
        } catch (_: Exception) {
        }
        mainHandler.postDelayed({
            try {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_PROGRESS)
            } catch (_: Exception) {
            }
        }, 800L)
    }

    /**
     * Drop the ongoing FGS progress notification, then post a user-dismissible
     * "Installation Complete" notification that stays in the shade.
     */
    private fun finishWithSuccessNotification(title: String, message: String) {
        dismissProgressNotificationOnly()
        // Post after remove so SystemUI does not treat it as the FGS notif.
        mainHandler.post {
            postSuccessNotification(title, message)
        }
    }

    /** High-level progress lines worth keeping in the install log (skip animated dots). */
    private fun isLogWorthyProgress(message: String): Boolean {
        val cleanMsg = if (message.startsWith("[") && message.contains("] ")) {
            message.substringAfter("] ")
        } else {
            message
        }
        return cleanMsg.startsWith("Starting setup") ||
            cleanMsg.startsWith("Container preset") ||
            cleanMsg.startsWith("Fetching container catalog") ||
            cleanMsg.startsWith("Installing ") ||
            cleanMsg.startsWith("Downloading container") ||
            cleanMsg.startsWith("Verifying image") ||
            cleanMsg.startsWith("Extracting container") ||
            cleanMsg.startsWith("Preparing container") ||
            cleanMsg.startsWith("Finalizing") ||
            (cleanMsg.startsWith("Downloading") && cleanMsg.contains("rootfs")) ||
            cleanMsg.startsWith("Extracting rootfs") ||
            cleanMsg.startsWith("Executing native setup script") ||
            cleanMsg.startsWith("Installing Package") ||
            cleanMsg.startsWith("✓ Successfully") ||
            cleanMsg.startsWith("⚠ Failed to install") ||
            (cleanMsg.contains("Phase ") && cleanMsg.contains("/8") && !cleanMsg.endsWith(".")) ||
            cleanMsg.contains("Benchmarking mirrors") ||
            cleanMsg.contains("Fastest mirror") ||
            cleanMsg.startsWith("✓ Install script completed") ||
            cleanMsg.startsWith("✓ Rootfs already") ||
            cleanMsg.startsWith("✓ Found cached") ||
            cleanMsg.startsWith("✓ Using cached image") ||
            cleanMsg.startsWith("✓ Image verified") ||
            cleanMsg.startsWith("✓ Container extracted") ||
            cleanMsg.startsWith("✓ Install complete") ||
            cleanMsg.contains("link_shim") ||
            cleanMsg.startsWith("XZ extraction")
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ────────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_SETUP,
                "Linux Setup",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Linux environment installation progress"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /** Intent that opens MainActivity and brings it to front */
    private fun mainActivityPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, openIntent, flags)
    }

    private fun buildProgressNotification(
        message: String,
        progress: Int = 0,
        deterministic: Boolean = false
    ): Notification {
        // Keep content short — no BigTextStyle on progress (less SystemUI work).
        val clean = stripTimestamp(message)
        return NotificationCompat.Builder(this, CHANNEL_SETUP)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PocketLinux Setup")
            .setContentText(clean)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(mainActivityPendingIntent())
            .setProgress(100, progress.coerceIn(0, 100), !deterministic)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Immediate SystemUI publish — only call from [ThrottledNotificationUpdater] or FGS start. */
    private fun publishProgressNotificationNow(
        message: String,
        progress: Int = 0,
        deterministic: Boolean = false
    ) {
        val notif = buildProgressNotification(message, progress, deterministic)
        try {
            startForeground(NOTIF_PROGRESS, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update progress notification", e)
        }
    }

    private fun postInputRequiredNotification(title: String, message: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_SETUP)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainActivityPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_edit,
                "Open App to Respond",
                mainActivityPendingIntent()
            )
            .build()

        try {
            val nm = NotificationManagerCompat.from(this)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                nm.areNotificationsEnabled()) {
                nm.notify(NOTIF_INPUT, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post input notification", e)
        }
    }

    private fun postSuccessNotification(title: String, message: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_SETUP)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainActivityPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_view,
                "Open App",
                mainActivityPendingIntent()
            )
            .build()

        try {
            val nm = NotificationManagerCompat.from(this)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                nm.areNotificationsEnabled()) {
                nm.notify(NOTIF_COMPLETE, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post success notification", e)
        }
    }

    private fun postErrorNotification(error: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_SETUP)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Setup Failed")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_rotate,
                "Open App to Retry",
                mainActivityPendingIntent()
            )
            .build()

        try {
            val nm = NotificationManagerCompat.from(this)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                nm.areNotificationsEnabled()) {
                nm.notify(NOTIF_COMPLETE, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post error notification", e)
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Utility
    // ────────────────────────────────────────────────────────────────────────────

    private fun broadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
