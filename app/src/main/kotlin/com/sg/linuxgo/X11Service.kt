package com.sg.linuxgo

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sg.linuxgo.x11.CmdEntryPoint
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the Lorie/X11 native server in process `:x11`.
 *
 * Runs as a foreground service so Android does not reclaim this process while
 * the user is in another app (previously caused black screens on return).
 *
 * Uses the **same** notification id/channel as [SessionKeepAliveService] so the
 * user only sees one session notification (Terminate lives on that shared notif).
 */
class X11Service : Service() {
    companion object {
        /** Looper/native host thread is up (main() blocks on Looper.loop forever). */
        private val looperRunning = AtomicBoolean(false)

        const val EXTRA_FORCE_RESTART = "FORCE_RESTART"

        // Share with SessionKeepAlive so the shade shows a single session notification.
        private const val CHANNEL_ID = SessionNotificationManager.CHANNEL_ID
        private const val NOTIF_ID = SessionNotificationManager.NOTIFICATION_ID

        private const val TAG = "X11Service"

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            SessionLifecycleGate.setAllowed(appCtx, false)
            try {
                appCtx.stopService(Intent(appCtx, X11Service::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stopService failed: ${e.message}")
            }
            try {
                appCtx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Promote BEFORE the session gate or any other work. Isolated :x11 can
        // take seconds to spawn; if the caller used startForegroundService the
        // main process dies unless startForeground runs immediately.
        SessionKeepAliveService.ensureChannel(this)
        promoteToForeground(urgent = true)
        if (!SessionLifecycleGate.isAllowed(this)) {
            Log.i(TAG, "onCreate: session not allowed — stopping")
            stopAfterPromoted()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SessionLifecycleGate.isAllowed(this)) {
            Log.i(TAG, "onStartCommand: session not allowed — stopping")
            promoteThenStop()
            return START_NOT_STICKY
        }

        // Re-assert FGS on every start (required after process restart).
        promoteToForeground(urgent = false)

        val args = intent?.getStringArrayExtra("args") ?: arrayOf(":0", "-ac", "-noreset")
        val containerTmpDir = intent?.getStringExtra("TMPDIR")
        val caller = intent?.getStringExtra("CALLER") ?: "UNKNOWN"
        val forceRestart = intent?.getBooleanExtra(EXTRA_FORCE_RESTART, false) == true

        Log.i(TAG, "onStartCommand: CALLER=$caller, TMPDIR=$containerTmpDir forceRestart=$forceRestart")

        // Reject legacy paths that sabotaged the socket bridge
        if (containerTmpDir != null && containerTmpDir.contains("files/usr/tmp")) {
            Log.w(TAG, "BLOCKING LEGACY PATH: $containerTmpDir from $caller")
            return START_NOT_STICKY
        }

        if (containerTmpDir != null) {
            try {
                android.system.Os.setenv("TMPDIR", containerTmpDir, true)
                android.system.Os.setenv("LINUXGO_X11_OVERRIDE_PACKAGE", packageName, true)
                intent?.getStringExtra("XKB_CONFIG_ROOT")?.let {
                    android.system.Os.setenv("XKB_CONFIG_ROOT", it, true)
                }
                android.system.Os.setenv("LINUXGO_X11_DEBUG", "1", true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set environment", e)
            }
        }

        // First start: load native + Looper (main never returns).
        if (looperRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Starting X11 server native thread...")
            Thread {
                try {
                    val logFile = java.io.File(cacheDir, "x11_server.log")
                    val ps = java.io.PrintStream(java.io.FileOutputStream(logFile, true))
                    System.setOut(ps)
                    System.setErr(ps)

                    ps.println("X11Service: [INSTANCE_START] TMPDIR: $containerTmpDir")
                    ps.flush()

                    ps.println("X11Service: Loading native library...")
                    ps.flush()
                    try {
                        CmdEntryPoint.loadNative(containerTmpDir)
                    } catch (t: Throwable) {
                        ps.println("X11Service: loadNative FAILED: ${t.message}")
                        t.printStackTrace(ps)
                        ps.flush()
                    }

                    ps.println("X11Service: Starting X11 server main with args: ${args.joinToString(" ")}")
                    ps.flush()
                    try {
                        CmdEntryPoint.main(args)
                    } catch (t: Throwable) {
                        ps.println("X11Service: main FAILED: ${t.message}")
                        t.printStackTrace(ps)
                        ps.flush()
                    }
                    ps.println("X11Service: Thread finished")
                    ps.flush()
                    looperRunning.set(false)
                } catch (e: Exception) {
                    Log.e(TAG, "X11 server crashed", e)
                    looperRunning.set(false)
                }
            }.apply {
                name = "X11NativeServer"
                start()
            }
        } else if (forceRestart) {
            // LEGACY: in-process re-init of Xorg (dixRegisterPrivateKey / dixAllocatePrivates)
            // can SIGSEGV on libXlorie.so. Callers must cold-kill the :x11 process and
            // start a fresh service instead. Refuse re-init here.
            Log.w(
                TAG,
                "FORCE_RESTART ignored — in-process X re-init disabled (SIGSEGV risk). " +
                    "Cold-restart :x11 process from GuiSessionManager.coldRestartX11Service"
            )
            try {
                val logFile = java.io.File(cacheDir, "x11_server.log")
                java.io.FileOutputStream(logFile, true).bufferedWriter().use { w ->
                    w.appendLine(
                        "X11Service: [FORCE_RESTART] ignored (use cold process restart). " +
                            "TMPDIR=$containerTmpDir"
                    )
                }
            } catch (_: Exception) {
            }
        } else {
            Log.i(TAG, "X11 Looper already running, skipping main() (cold-restart process to recover)")
        }

        // Sticky only while session is allowed — user terminate must not respawn notif.
        return if (SessionLifecycleGate.isAllowed(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — X11 keep-alive continues")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: X11 service destroyed (Looper may still be running)")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun promoteThenStop() {
        // Satisfy FGS contract even when stopping immediately (sticky restart race).
        promoteToForeground(urgent = true)
        stopAfterPromoted()
    }

    private fun stopAfterPromoted() {
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

    /**
     * Promote with minimal notification first (urgent), then full shared session notif.
     * Plain [startForeground] first so the FGS deadline is met even if specialUse fails.
     */
    private fun promoteToForeground(urgent: Boolean) {
        val attempts: List<() -> Notification> = if (urgent) {
            listOf(::buildMinimalNotification, ::buildSharedSessionNotification)
        } else {
            listOf(::buildSharedSessionNotification, ::buildMinimalNotification)
        }
        for (builder in attempts) {
            val ok = runCatching { startForegroundCompat(builder()) }
                .onFailure { e -> Log.e(TAG, "startForeground attempt failed", e) }
                .isSuccess
            if (ok) return
        }
        Log.e(TAG, "Unable to promote X11Service to foreground — process may be killed")
    }

    private fun startForegroundCompat(notification: Notification) {
        // Plain startForeground FIRST (HONOR / API 34+ specialUse can be slow or throw).
        try {
            startForeground(NOTIF_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "plain startForeground failed: ${e.message}")
            if (Build.VERSION.SDK_INT < 34) throw e
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Exception) {
                Log.w(TAG, "specialUse FGS upgrade failed (plain FGS may still hold): ${e.message}")
            }
        }
    }

    private fun buildMinimalNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Session")
            .setContentText("Starting display…")
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Same id/channel/content shape as [SessionKeepAliveService] so the status bar
     * shows a single "session is running" notification with Terminate.
     */
    private fun buildSharedSessionNotification(): Notification {
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

        val icon = try {
            applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync
        } catch (_: Exception) {
            android.R.drawable.stat_notify_sync
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("Linux Session is running")
            .setContentText("Session is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
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
}
