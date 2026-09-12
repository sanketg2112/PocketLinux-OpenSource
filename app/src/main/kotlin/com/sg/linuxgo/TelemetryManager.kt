package com.sg.linuxgo

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import androidx.preference.PreferenceManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Off-device reporting for PocketLinux.
 *
 * The app does **not** send automatic usage, session, purchase, or crash pings.
 * The only events posted to remote reporting are user-submitted [EVENT_CRASH_REPORT]
 * and [EVENT_USER_FEEDBACK]. Both include a small device envelope
 * (install id, app version, Android version, device model, distro if known).
 * Crash reports also attach RAM, storage, CPU, and GPU/SoC fields.
 *
 * Uncaught exceptions still queue a local pending report so the user can
 * choose Send on the next launch. Desktop deaths use [classifyDistroCrash]
 * only to decide whether to prompt — nothing is uploaded unless they send.
 */
object TelemetryManager {
    private const val TAG = "TelemetryManager"
    private const val PREF_INSTALLATION_ID = "telemetry_installation_id"

    const val EVENT_CRASH_REPORT = "crash_report"
    const val EVENT_USER_FEEDBACK = "user_feedback"

    /** Used only as a log label on [com.sg.linuxgo.terminateSession]. */
    const val REASON_USER_STOP = "user_stop"

    /**
     * Distro/desktop session death reasons (in-app prompt classification).
     * [DISTRO_REASON_BACKGROUND_NO_NOTIFICATION] is expected when the user
     * backgrounds the app without notification permission (no FGS keep-alive).
     */
    const val DISTRO_REASON_APP_IN_FOREGROUND = "app_in_foreground"
    const val DISTRO_REASON_BACKGROUND_NO_NOTIFICATION = "background_no_notification"
    const val DISTRO_REASON_BACKGROUND_WITH_NOTIFICATION = "background_with_notification"

    private val executor = Executors.newSingleThreadExecutor()
    private var isCrashHandlerRegistered = false
    private val lifecycleRegistered = AtomicBoolean(false)
    /** Started activities in this process (UI in foreground when > 0). */
    private val startedActivities = AtomicInteger(0)

    /** True when the last [trackEvent] was dropped as not user-submitted. */
    @Volatile
    internal var lastTrackSkipped: Boolean = false
        private set
    @Volatile
    internal var lastTrackedEventType: String? = null
        private set
    /** Keys in the last built payload (top-level + metadata), for unit tests. */
    @Volatile
    internal var lastPayloadKeys: Set<String> = emptySet()
        private set

    /** Clears install id and test counters (unit tests only). */
    @Synchronized
    internal fun resetForTests(context: Context) {
        prefs(context).edit().remove(PREF_INSTALLATION_ID).commit()
        lastTrackSkipped = false
        lastTrackedEventType = null
        lastPayloadKeys = emptySet()
    }

    /**
     * Only crash reports and feedback leave the device.
     * [context] is unused; kept so call sites can pass it uniformly.
     */
    internal fun shouldSendEvent(
        @Suppress("UNUSED_PARAMETER") context: Context,
        eventType: String
    ): Boolean {
        return eventType == EVENT_CRASH_REPORT || eventType == EVENT_USER_FEEDBACK
    }

    @Synchronized
    fun getInstallationId(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        var id = prefs.getString(PREF_INSTALLATION_ID, null)
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(PREF_INSTALLATION_ID, id).apply()
        }
        return id
    }

    /**
     * Drops the local install id so the next report uses a new id.
     * Does not delete rows already stored on the server.
     */
    fun resetInstallIdentity(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(PREF_INSTALLATION_ID)
            .commit()
    }

    /**
     * Registers a global uncaught exception handler. Queues a user-facing
     * report prompt for the next launch — does not upload automatically.
     * Also registers process activity lifecycle so distro death prompts
     * can mark foreground vs background.
     */
    @Synchronized
    fun registerUncaughtExceptionHandler(context: Context) {
        registerAppLifecycle(context)
        A11yEventCompatGuard.installOnMainLooper()
        if (isCrashHandlerRegistered) return
        isCrashHandlerRegistered = true
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stack = Log.getStackTraceString(throwable).take(1200)
                CrashReportCoordinator.queuePending(
                    appContext,
                    CrashReportCoordinator.PendingReport(
                        kind = "app_crash",
                        activeDistro = null,
                        exitCode = null,
                        containerName = null,
                        guiMode = null,
                        recoveryAttempted = false,
                        exceptionClass = throwable.javaClass.name,
                        exceptionMessage = throwable.message ?: "No message",
                        stacktraceSnippet = stack,
                        sessionLogs = null,
                        summary = "The app hit an unexpected error (${throwable.javaClass.simpleName}).",
                        queuedAtMs = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue crash report", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Track whether any Activity in this process is started (UI foreground).
     * Used to classify desktop deaths as app_in_foreground vs background.
     */
    fun registerAppLifecycle(context: Context) {
        if (!lifecycleRegistered.compareAndSet(false, true)) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                startedActivities.updateAndGet { cur -> (cur - 1).coerceAtLeast(0) }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * True when the user is actively looking at an app Activity in this process.
     * Does not treat FGS-only importance as foreground.
     */
    fun isAppInForeground(context: Context): Boolean {
        if (startedActivities.get() > 0) return true
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            @Suppress("DEPRECATION")
            val procs = am.runningAppProcesses ?: return false
            val me = procs.firstOrNull { it.pid == Process.myPid() } ?: return false
            me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Classify a desktop/session death for the in-app crash prompt.
     *
     * - [DISTRO_REASON_APP_IN_FOREGROUND] — unexpected
     * - [DISTRO_REASON_BACKGROUND_NO_NOTIFICATION] — expected (no keep-alive)
     * - [DISTRO_REASON_BACKGROUND_WITH_NOTIFICATION] — unexpected-ish
     */
    fun classifyDistroCrash(context: Context): DistroCrashClass {
        val foreground = isAppInForeground(context)
        val notifPerm = notificationPermissionStatus(context)
        val notifEnabled = notificationsEnabled(context)
        val hasNotif = notifPerm != "denied" && notifEnabled
        val appState = if (foreground) "foreground" else "background"
        val reason = when {
            foreground -> DISTRO_REASON_APP_IN_FOREGROUND
            !hasNotif -> DISTRO_REASON_BACKGROUND_NO_NOTIFICATION
            else -> DISTRO_REASON_BACKGROUND_WITH_NOTIFICATION
        }
        val expected = reason == DISTRO_REASON_BACKGROUND_NO_NOTIFICATION
        return DistroCrashClass(
            appState = appState,
            crashReason = reason,
            expected = expected,
            crashSeverity = if (expected) "expected" else "unexpected",
            notificationPermission = notifPerm,
            notificationsEnabled = notifEnabled
        )
    }

    data class DistroCrashClass(
        val appState: String,
        val crashReason: String,
        val expected: Boolean,
        val crashSeverity: String,
        val notificationPermission: String,
        val notificationsEnabled: Boolean
    )

    /**
     * POST_NOTIFICATIONS (API 33+) plus channel-level enablement.
     * Values: "granted" | "denied" | "not_required"
     */
    fun notificationPermissionStatus(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                "granted"
            } else {
                "denied"
            }
        } else {
            "not_required"
        }
    }

    /** Whether the app can currently show notifications (permission + master switch). */
    fun notificationsEnabled(context: Context): Boolean {
        return try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (_: Exception) {
            notificationPermissionStatus(context) != "denied"
        }
    }

    /**
     * All-files / legacy external storage.
     * Values: "granted" | "denied" | "not_required"
     */
    fun storagePermissionStatus(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) "granted" else "denied"
            } else {
                if (ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    "granted"
                } else {
                    "denied"
                }
            }
        } catch (_: Exception) {
            "denied"
        }
    }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /**
     * Hardware snapshot attached only to [EVENT_CRASH_REPORT].
     * Does not create a GL context (unsafe at crash time).
     */
    internal fun crashHardwareDiagnostics(context: Context): JSONObject {
        val diag = JSONObject()
        try {
            val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (actMgr != null) {
                val memInfo = ActivityManager.MemoryInfo()
                actMgr.getMemoryInfo(memInfo)
                diag.put("total_ram_mb", memInfo.totalMem / (1024 * 1024))
                diag.put("avail_ram_mb", memInfo.availMem / (1024 * 1024))
                diag.put("low_memory", memInfo.lowMemory)
                try {
                    val gles = actMgr.deviceConfigurationInfo?.reqGlEsVersion ?: 0
                    if (gles > 0) {
                        val major = (gles shr 16) and 0xffff
                        val minor = gles and 0xffff
                        diag.put("gles_version", "$major.$minor")
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "crash RAM/GLES: ${e.message}")
        }
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            diag.put(
                "storage_free_mb",
                stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
            )
            diag.put(
                "storage_total_mb",
                stat.blockCountLong * stat.blockSizeLong / (1024 * 1024)
            )
        } catch (e: Exception) {
            Log.d(TAG, "crash storage: ${e.message}")
        }
        try {
            @Suppress("DEPRECATION")
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI
            diag.put("cpu_abi", abi)
            diag.put("cpu_abis", Build.SUPPORTED_ABIS.joinToString(","))
            diag.put("cpu_cores", Runtime.getRuntime().availableProcessors())
            diag.put("os_build", Build.DISPLAY)
            if (Build.HARDWARE.isNotBlank()) diag.put("hardware", Build.HARDWARE)
            if (Build.BOARD.isNotBlank()) diag.put("board", Build.BOARD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() }
                    ?.let { diag.put("soc_manufacturer", it) }
                Build.SOC_MODEL.takeIf { it.isNotBlank() }
                    ?.let { diag.put("soc_model", it) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "crash CPU: ${e.message}")
        }
        try {
            diag.put(
                "vulkan",
                context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
                )
            )
        } catch (_: Exception) {
        }
        try {
            val mode = prefs(context).getString("gpu_driver_mode", null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (mode != null) diag.put("gpu_driver_mode", mode)
        } catch (_: Exception) {
        }
        return diag
    }

    /**
     * POST a user-submitted event. Anything other than [EVENT_CRASH_REPORT]
     * or [EVENT_USER_FEEDBACK] is dropped.
     */
    fun trackEvent(
        context: Context,
        eventType: String,
        activeDistro: String? = null,
        metadata: String? = null
    ) {
        val appContext = context.applicationContext
        lastTrackedEventType = eventType
        if (!shouldSendEvent(appContext, eventType)) {
            lastTrackSkipped = true
            lastPayloadKeys = emptySet()
            Log.d(TAG, "Telemetry skipped (not user-submitted): $eventType")
            return
        }
        lastTrackSkipped = false
        val instId = getInstallationId(appContext)
        val appVersion = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val sdkInt = Build.VERSION.SDK_INT
        val metaObj = if (eventType == EVENT_CRASH_REPORT) {
            crashHardwareDiagnostics(appContext)
        } else {
            JSONObject()
        }
        if (!metadata.isNullOrBlank()) {
            try {
                val custom = JSONObject(metadata)
                val keys = custom.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    metaObj.put(k, custom.get(k))
                }
            } catch (_: Exception) {
                metaObj.put("details", metadata)
            }
        }

        val json = JSONObject().apply {
            put("installation_id", instId)
            put("event_type", eventType)
            put("app_version", appVersion)
            put("android_sdk", sdkInt)
            put("device_model", deviceModel)
            if (!activeDistro.isNullOrBlank()) {
                put("active_distro", activeDistro)
            }
            put("metadata", metaObj)
        }
        val keys = mutableSetOf<String>()
        json.keys().forEach { keys.add(it) }
        metaObj.keys().forEach { keys.add(it) }
        lastPayloadKeys = keys

        Log.d(TAG, "Telemetry event ($eventType) skipped (remote reporting disabled in open-source)")
    }
}
