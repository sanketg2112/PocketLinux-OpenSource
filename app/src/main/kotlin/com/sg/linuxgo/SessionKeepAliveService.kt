package com.sg.linuxgo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps Linux container sessions (GUI/terminal) alive
 * while the user switches to another app.
 *
 * Without this, Android can kill the main process and/or the `:x11` process
 * after the activity is backgrounded — resulting in a black screen when the
 * user returns.
 *
 * Critical: after [Context.startForegroundService], Android requires
 * [startForeground] within a few seconds or it kills the whole process with
 * RemoteServiceException (Crash D / user_crash.md — seen on OPPO and similar OEMs).
 * We therefore:
 *  1. Create the notification channel *before* startForegroundService
 *  2. Promote in [onCreate] with a minimal notification before any other work
 *  3. Fall back across notification builders and FGS type variants
 */
class SessionKeepAliveService : Service() {

    companion object {
        private const val TAG = "SessionKeepAlive"
        const val CHANNEL_ID = SessionNotificationManager.CHANNEL_ID
        const val NOTIFICATION_ID = SessionNotificationManager.NOTIFICATION_ID

        const val ACTION_START = "com.sg.linuxgo.action.SESSION_KEEPALIVE_START"
        const val ACTION_UPDATE = "com.sg.linuxgo.action.SESSION_KEEPALIVE_UPDATE"
        const val ACTION_STOP = "com.sg.linuxgo.action.SESSION_KEEPALIVE_STOP"

        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ICON_RES = "icon_res"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activeContainerId: String? = null
            private set

        /**
         * True between [start] scheduling startForegroundService and a successful
         * [startForeground] in onCreate/onStartCommand. Prevents stacked
         * startForegroundService calls (HONOR / strict OEM FGS deadline kills).
         */
        @Volatile
        private var startInFlight: Boolean = false

        /** Create the session channel early so FGS start never waits on it. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            try {
                val nm = context.applicationContext
                    .getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) != null) return
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Session Status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Linux desktop/terminal sessions running in the background"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.w(TAG, "ensureChannel failed: ${e.message}")
            }
        }

        fun start(context: Context, containerId: String, title: String, text: String, iconRes: Int) {
            val appCtx = context.applicationContext
            // Allow FGS before start — sticky restarts check this gate.
            // New session: clear intentional-stop flag so death monitors work again.
            SessionLifecycleGate.setUserStopInProgress(appCtx, false)
            SessionLifecycleGate.setAllowed(appCtx, true)
            // Already promoted: refresh via plain startService (no new FGS deadline).
            if (isRunning) {
                update(appCtx, containerId, title, text, iconRes)
                return
            }
            // Another startForegroundService is already racing the deadline — don't stack.
            if (startInFlight) {
                Log.i(TAG, "start skipped — FGS start already in flight")
                return
            }
            // Channel must exist before startForegroundService on strict OEMs.
            ensureChannel(appCtx)
            val intent = Intent(appCtx, SessionKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONTAINER_ID, containerId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_ICON_RES, iconRes)
            }
            val visible = X11ServiceStarter.isActivityWindowVisible(context)
            val useFgs = SessionKeepAliveStartPolicy.useStartForegroundService(Build.VERSION.SDK_INT, visible)
            val mode = if (useFgs) "startForegroundService" else "startService"
            Log.i(TAG, "start SessionKeepAliveService via $mode (sdk=${Build.VERSION.SDK_INT} visible=$visible)")
            try {
                startInFlight = true
                if (useFgs) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appCtx.startForegroundService(intent)
                    } else {
                        appCtx.startService(intent)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        appCtx.startService(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "startService failed (${e.message}) — falling back to startForegroundService")
                        appCtx.startForegroundService(intent)
                    }
                } else {
                    appCtx.startService(intent)
                }
            } catch (e: Exception) {
                startInFlight = false
                Log.e(TAG, "Failed to start keep-alive service via $mode", e)
            }
        }

        fun update(context: Context, containerId: String, title: String, text: String, iconRes: Int) {
            val appCtx = context.applicationContext
            // After user terminate the gate is false. If we still think we're running,
            // shut down. If not running, this is a new session — start() re-enables the gate.
            if (!SessionLifecycleGate.isAllowed(appCtx) && isRunning) {
                stop(appCtx)
                return
            }
            if (!isRunning) {
                // Don't start a second FGS while the first start is still promoting.
                if (startInFlight) {
                    Log.d(TAG, "update deferred — FGS start in flight")
                    return
                }
                start(context, containerId, title, text, iconRes)
                return
            }
            ensureChannel(appCtx)
            val intent = Intent(appCtx, SessionKeepAliveService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_CONTAINER_ID, containerId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_ICON_RES, iconRes)
            }
            try {
                // Prefer plain startService while already promoted (avoids extra FGS
                // timeout window).
                appCtx.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Update via startService failed; retrying as foreground start", e)
                isRunning = false
                start(context, containerId, title, text, iconRes)
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            // Deny before stop so any sticky restart immediately exits.
            SessionLifecycleGate.setAllowed(appCtx, false)
            try {
                val stopIntent = Intent(appCtx, SessionKeepAliveService::class.java).apply {
                    action = ACTION_STOP
                }
                try {
                    appCtx.startService(stopIntent)
                } catch (_: Exception) {
                }
                appCtx.stopService(Intent(appCtx, SessionKeepAliveService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop keep-alive service", e)
            }
            try {
                val nm = appCtx.getSystemService(NotificationManager::class.java)
                nm?.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTitle: String = "Linux Session is running"
    private var lastText: String = "Session is active"
    private var lastIconRes: Int = 0
    private var foregroundPromoted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // Promote in the absolute first milliseconds — HONOR/OPPO kill the process if
        // startForeground lags after startForegroundService. Channel create is cheap
        // and required for a valid notification on O+.
        ensureChannel(this)
        // Sticky restart after user terminate: promote briefly (FGS contract) then exit.
        if (!SessionLifecycleGate.isAllowed(this)) {
            Log.i(TAG, "onCreate: session not allowed — stopping immediately")
            runCatching { startForegroundCompat(buildMinimalNotification()) }
            startInFlight = false
            stopSelfInternal()
            return
        }
        // Urgent path: minimal notification only — no PendingIntent / action work yet.
        promoteToForeground(lastTitle, lastText, lastIconRes, urgent = true)
        startInFlight = false
        // Defer wake lock so it never competes with the FGS deadline.
        try {
            acquireWakeLock()
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock after promote: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SessionLifecycleGate.setAllowed(this, false)
                startInFlight = false
                stopSelfInternal()
                return START_NOT_STICKY
            }
        }

        // Pull extras before any heavy work so re-promote can use the right title.
        if (intent?.action == ACTION_START || intent?.action == ACTION_UPDATE || intent?.action == null) {
            val cid = intent?.getStringExtra(EXTRA_CONTAINER_ID)
            if (cid != null) activeContainerId = cid
            intent?.getStringExtra(EXTRA_TITLE)?.let { lastTitle = it }
            intent?.getStringExtra(EXTRA_TEXT)?.let { lastText = it }
            val icon = intent?.getIntExtra(EXTRA_ICON_RES, 0) ?: 0
            if (icon != 0) lastIconRes = icon
        }

        if (!SessionLifecycleGate.isAllowed(this)) {
            Log.i(TAG, "onStartCommand: session not allowed — stopping")
            if (!foregroundPromoted) {
                runCatching { startForegroundCompat(buildMinimalNotification()) }
            }
            startInFlight = false
            stopSelfInternal()
            return START_NOT_STICKY
        }

        // Always re-assert FGS before handling action payload (covers restart + null intent).
        // If already promoted (onCreate), only upgrade notification content — no second race.
        if (!foregroundPromoted) {
            ensureChannel(this)
            promoteToForeground(lastTitle, lastText, lastIconRes, urgent = true)
        } else if (intent?.action == ACTION_START || intent?.action == ACTION_UPDATE) {
            promoteToForeground(lastTitle, lastText, lastIconRes, urgent = false)
        }
        startInFlight = false

        // Sticky only while a real session is allowed; after terminate, never auto-restart.
        return if (SessionLifecycleGate.isAllowed(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not stop — session must survive swipe-away from Recents.
        Log.d(TAG, "Task removed — session keep-alive continues")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        foregroundPromoted = false
        activeContainerId = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG::SessionLock"
            ).apply {
                // Long-lived; renewed/released with the session
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed: ${e.message}")
        }
    }

    /**
     * Call [startForeground] with a real notification, then increasingly bare fallbacks.
     * Only marks [isRunning] after a successful promotion so callers re-use
     * startForegroundService if we never made it.
     *
     * @param urgent Prefer the absolute minimal notification first (onCreate path).
     */
    private fun promoteToForeground(title: String, text: String, iconRes: Int, urgent: Boolean) {
        val attempts: List<() -> Notification> = if (urgent && !foregroundPromoted) {
            listOf(
                { buildMinimalNotification() },
                { buildNotification(title, text, iconRes) }
            )
        } else {
            listOf(
                { buildNotification(title, text, iconRes) },
                { buildMinimalNotification() }
            )
        }

        for (builder in attempts) {
            val ok = runCatching {
                startForegroundCompat(builder())
            }.onFailure { e ->
                Log.e(TAG, "startForeground attempt failed", e)
            }.isSuccess
            if (ok) {
                foregroundPromoted = true
                isRunning = true
                return
            }
        }

        // Last ditch: system icon + empty strings, no PendingIntents.
        val lastDitch = runCatching {
            startForegroundCompat(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("Session")
                    .setOngoing(true)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .build()
            )
        }.onFailure { e ->
            Log.e(TAG, "startForeground(last-ditch) failed", e)
        }.isSuccess

        if (lastDitch) {
            foregroundPromoted = true
            isRunning = true
        } else {
            // Leave isRunning false so the next update() retries via startForegroundService.
            foregroundPromoted = false
            isRunning = false
            Log.e(TAG, "Unable to promote keep-alive to foreground — process may be killed by system")
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        // Plain startForeground FIRST so the FGS contract is satisfied even if
        // specialUse typing throws or is slow (HONOR / some API 34+ builds).
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "plain startForeground failed: ${e.message}")
            if (Build.VERSION.SDK_INT < 34) throw e
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Exception) {
                // Already in foreground via plain call — typed upgrade is best-effort.
                Log.w(TAG, "specialUse FGS upgrade failed (plain FGS may still hold): ${e.message}")
            }
        }
    }

    private fun stopSelfInternal() {
        isRunning = false
        foregroundPromoted = false
        startInFlight = false
        activeContainerId = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }

    /** Prefer launcher / system icons — wide logo PNGs break smallIcon on some OEMs. */
    private fun safeSmallIcon(iconRes: Int): Int {
        if (iconRes != 0 && iconRes != R.drawable.ic_alpine) {
            return iconRes
        }
        return try {
            applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync
        } catch (_: Exception) {
            android.R.drawable.stat_notify_sync
        }
    }

    private fun buildNotification(title: String, text: String, iconRes: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPi = PendingIntent.getActivity(this, 0, openIntent, flags)

        val terminateIntent = Intent(SessionNotificationManager.ACTION_TERMINATE_SESSION).apply {
            setPackage(packageName)
        }
        val terminatePi = PendingIntent.getBroadcast(this, 1, terminateIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(safeSmallIcon(iconRes))
            .setContentTitle(title.ifBlank { "Linux Session is running" })
            .setContentText(text.ifBlank { "Session is active" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Terminate",
                terminatePi
            )
            .build()
    }

    private fun buildMinimalNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Linux Session is running")
            .setContentText("Session is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
