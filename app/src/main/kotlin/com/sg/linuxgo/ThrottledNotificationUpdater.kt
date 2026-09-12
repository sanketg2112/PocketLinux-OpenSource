package com.sg.linuxgo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Rate-limits [startForeground] / [NotificationManager.notify] calls.
 *
 * Multi-thread downloads can fire hundreds of progress events per second. Pushing
 * every update into SystemUI freezes the status-bar clock on some devices
 * (notably Samsung One UI / S25). This keeps the *latest* message + percent and
 * posts at most every [minIntervalMs], while always flushing on force / 100%.
 */
class ThrottledNotificationUpdater(
    private val minIntervalMs: Long = 500L,
    private val maxIntervalMs: Long = 1500L,
    private val onPost: (message: String, progress: Int, deterministic: Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable { flush(force = true) }

    @Volatile private var pendingMessage: String = ""
    @Volatile private var pendingProgress: Int = 0
    @Volatile private var pendingDeterministic: Boolean = false
    @Volatile private var hasPending: Boolean = false

    private var lastPostMs: Long = 0L
    private var lastPostedProgress: Int = Int.MIN_VALUE
    private var lastPostedMessageKey: String = ""

    /**
     * Queue an update. [force] posts immediately (completion, errors, phase jumps).
     */
    @Synchronized
    fun update(
        message: String,
        progress: Int,
        deterministic: Boolean = true,
        force: Boolean = false
    ) {
        pendingMessage = message
        pendingProgress = progress.coerceIn(0, 100)
        pendingDeterministic = deterministic
        hasPending = true

        if (force || progress >= 100 || lastPostMs == 0L) {
            handler.removeCallbacks(flushRunnable)
            flush(force = true)
            return
        }

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastPostMs
        val msgKey = messageKey(message)
        val pctChanged = pendingProgress != lastPostedProgress
        val msgChanged = msgKey != lastPostedMessageKey

        when {
            elapsed >= minIntervalMs && (pctChanged || msgChanged) -> {
                handler.removeCallbacks(flushRunnable)
                flush(force = true)
            }
            elapsed >= maxIntervalMs -> {
                handler.removeCallbacks(flushRunnable)
                flush(force = true)
            }
            pctChanged || msgChanged -> {
                // Coalesce until min interval elapses, then post latest snapshot.
                val delay = (minIntervalMs - elapsed).coerceAtLeast(50L)
                handler.removeCallbacks(flushRunnable)
                handler.postDelayed(flushRunnable, delay)
            }
        }
    }

    /** Push any pending update now (e.g. service stopping). */
    @Synchronized
    fun flush(force: Boolean = false) {
        if (!hasPending && !force) return
        if (!hasPending) return
        val msg = pendingMessage
        val progress = pendingProgress
        val det = pendingDeterministic
        hasPending = false
        lastPostMs = SystemClock.elapsedRealtime()
        lastPostedProgress = progress
        lastPostedMessageKey = messageKey(msg)
        try {
            onPost(msg, progress, det)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun reset() {
        handler.removeCallbacks(flushRunnable)
        hasPending = false
        lastPostMs = 0L
        lastPostedProgress = Int.MIN_VALUE
        lastPostedMessageKey = ""
        pendingMessage = ""
        pendingProgress = 0
        pendingDeterministic = false
    }

    @Synchronized
    fun cancelPending() {
        handler.removeCallbacks(flushRunnable)
        hasPending = false
    }

    /** Strip timestamps / trailing % so we don't re-post every second for the same phase. */
    private fun messageKey(message: String): String {
        var s = message
        if (s.startsWith("[") && s.contains("] ")) {
            s = s.substringAfter("] ")
        }
        // "Downloading (v1.0): 42%" and "Downloading (v1.0): 43%" are same phase
        if (s.contains(':') && s.substringAfterLast(':').trim().endsWith("%")) {
            s = s.substringBeforeLast(':').trim()
        }
        return s.trimEnd('.', '…', ' ')
    }
}

/**
 * Throttles LocalBroadcast progress to the in-app card so the main thread
 * is not flooded. Always delivers force / 100% / ≥1% steps after [minIntervalMs].
 */
class ThrottledProgressBroadcaster(
    private val minIntervalMs: Long = 100L,
    private val onBroadcast: (fileName: String, progress: Int, current: Long, total: Long) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable { flush() }

    @Volatile private var pendingFileName: String = ""
    @Volatile private var pendingProgress: Int = 0
    @Volatile private var pendingCurrent: Long = 0L
    @Volatile private var pendingTotal: Long = -1L
    @Volatile private var hasPending: Boolean = false

    private var lastBroadcastMs: Long = 0L
    private var lastBroadcastProgress: Int = Int.MIN_VALUE

    @Synchronized
    fun update(fileName: String, progress: Int, current: Long, total: Long, force: Boolean = false) {
        pendingFileName = fileName
        pendingProgress = progress.coerceIn(0, 100)
        pendingCurrent = current
        pendingTotal = total
        hasPending = true

        if (force || progress >= 100 || lastBroadcastMs == 0L) {
            handler.removeCallbacks(flushRunnable)
            flush()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastBroadcastMs
        val jumped = pendingProgress - lastBroadcastProgress >= 1

        if (elapsed >= minIntervalMs && jumped) {
            handler.removeCallbacks(flushRunnable)
            flush()
        } else if (jumped || elapsed >= minIntervalMs) {
            val delay = (minIntervalMs - elapsed).coerceAtLeast(20L)
            handler.removeCallbacks(flushRunnable)
            handler.postDelayed(flushRunnable, delay)
        }
    }

    @Synchronized
    fun flush() {
        if (!hasPending) return
        hasPending = false
        lastBroadcastMs = SystemClock.elapsedRealtime()
        lastBroadcastProgress = pendingProgress
        try {
            onBroadcast(pendingFileName, pendingProgress, pendingCurrent, pendingTotal)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun reset() {
        handler.removeCallbacks(flushRunnable)
        hasPending = false
        pendingFileName = ""
        pendingProgress = 0
        pendingCurrent = 0L
        pendingTotal = -1L
        lastBroadcastMs = 0L
        lastBroadcastProgress = Int.MIN_VALUE
    }
}
