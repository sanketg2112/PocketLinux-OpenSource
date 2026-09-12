package com.sg.linuxgo

import android.app.Activity
import android.content.Context
import androidx.preference.PreferenceManager
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

/**
 * User-prompted crash reports.
 *
 * When a desktop/app crash is detected we queue a pending report and ask the user
 * whether to send it. Nothing is uploaded unless they tap Send.
 *
 * For distro deaths the dialog leads with **what Android did** and optional
 * suggestions (free RAM, child-process setting) — not “PocketLinux killed it”.
 */
object CrashReportCoordinator {
    private const val TAG = "CrashReport"
    private const val PREF_PENDING_JSON = "pending_crash_report_json"
    private const val PREF_NEVER_PROMPT = "crash_report_never_prompt"
    private const val MAX_LOG_CHARS = 8000
    private const val MAX_USER_NOTE = 800

    @Volatile
    private var promptVisible = false

    data class PendingReport(
        val kind: String,
        val activeDistro: String?,
        val exitCode: Int?,
        val containerName: String?,
        val guiMode: String?,
        val recoveryAttempted: Boolean,
        val exceptionClass: String?,
        val exceptionMessage: String?,
        val stacktraceSnippet: String?,
        val sessionLogs: String?,
        val summary: String,
        val queuedAtMs: Long,
        /** Longer user-facing explanation (reason + suggestions). Optional. */
        val userExplanation: String? = null
    )

    fun neverPrompt(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_NEVER_PROMPT, false)
    }

    fun setNeverPrompt(context: Context, value: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_NEVER_PROMPT, value)
            .apply()
    }

    fun queuePending(context: Context, report: PendingReport) {
        if (neverPrompt(context)) {
            Log.d(TAG, "Skip queue — user opted out of crash prompts")
            return
        }
        val json = JSONObject().apply {
            put("kind", report.kind)
            put("active_distro", report.activeDistro ?: JSONObject.NULL)
            put("exit_code", report.exitCode ?: JSONObject.NULL)
            put("container_name", report.containerName ?: JSONObject.NULL)
            put("gui_mode", report.guiMode ?: JSONObject.NULL)
            put("recovery_attempted", report.recoveryAttempted)
            put("exception_class", report.exceptionClass ?: JSONObject.NULL)
            put("exception_message", report.exceptionMessage ?: JSONObject.NULL)
            put("stacktrace_snippet", report.stacktraceSnippet ?: JSONObject.NULL)
            put("session_logs", truncate(report.sessionLogs, MAX_LOG_CHARS) ?: JSONObject.NULL)
            put("summary", report.summary)
            put("user_explanation", report.userExplanation ?: JSONObject.NULL)
            put("queued_at_ms", report.queuedAtMs)
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_PENDING_JSON, json.toString())
            .apply()
        Log.i(TAG, "Queued pending crash report kind=${report.kind}")
    }

    fun peekPending(context: Context): PendingReport? {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_PENDING_JSON, null)
            ?: return null
        return try {
            val o = JSONObject(raw)
            PendingReport(
                kind = o.optString("kind", "unknown"),
                activeDistro = o.optString("active_distro").takeIf { it.isNotBlank() && it != "null" },
                exitCode = if (o.isNull("exit_code")) null else o.optInt("exit_code"),
                containerName = o.optString("container_name").takeIf { it.isNotBlank() && it != "null" },
                guiMode = o.optString("gui_mode").takeIf { it.isNotBlank() && it != "null" },
                recoveryAttempted = o.optBoolean("recovery_attempted", false),
                exceptionClass = o.optString("exception_class").takeIf { it.isNotBlank() && it != "null" },
                exceptionMessage = o.optString("exception_message").takeIf { it.isNotBlank() && it != "null" },
                stacktraceSnippet = o.optString("stacktrace_snippet").takeIf { it.isNotBlank() && it != "null" },
                sessionLogs = o.optString("session_logs").takeIf { it.isNotBlank() && it != "null" },
                summary = o.optString("summary", "Crash detected"),
                queuedAtMs = o.optLong("queued_at_ms", System.currentTimeMillis()),
                userExplanation = o.optString("user_explanation").takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Bad pending crash JSON", e)
            clearPending(context)
            null
        }
    }

    fun clearPending(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(PREF_PENDING_JSON)
            .apply()
    }

    fun submit(
        context: Context,
        pending: PendingReport,
        userNote: String?,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val meta = JSONObject().apply {
            put("kind", pending.kind)
            put("summary", pending.summary)
            put("user_note", userNote?.trim()?.take(MAX_USER_NOTE).orEmpty())
            put("user_submitted", true)
            pending.exitCode?.let { put("exit_code", it) }
            pending.containerName?.let { put("container_name", it) }
            pending.guiMode?.let { put("gui_mode", it) }
            put("recovery_attempted", pending.recoveryAttempted)
            pending.exceptionClass?.let { put("exception_class", it) }
            pending.exceptionMessage?.let { put("exception_message", it) }
            pending.stacktraceSnippet?.let { put("stacktrace_snippet", it.take(1200)) }
            pending.sessionLogs?.let { put("session_logs", truncate(it, MAX_LOG_CHARS)) }
            put("queued_at_ms", pending.queuedAtMs)
            put("submitted_at_ms", System.currentTimeMillis())
        }
        TelemetryManager.trackEvent(
            context = context,
            eventType = "crash_report",
            activeDistro = pending.activeDistro,
            metadata = meta.toString()
        )
        clearPending(context)
        Log.i(TAG, "Submitted crash_report kind=${pending.kind}")
        onDone?.invoke(true)
    }

    /**
     * Request a one-shot crash dialog if a pending report exists.
     * Prefer Compose UI on [MainActivity] via [MainViewModel.showCrashReportPrompt];
     * otherwise fall back to AppCompat (non-Compose hosts).
     */
    fun maybeShowPrompt(activity: Activity) {
        if (activity.isFinishing) return
        if (neverPrompt(activity)) return
        if (promptVisible) return
        val pending = peekPending(activity) ?: return

        if (activity is MainActivity) {
            activity.viewModel.showCrashReportPrompt = true
            return
        }

        promptVisible = true

        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val bodyText = buildDialogBody(pending)
        val intro = TextView(activity).apply {
            text = bodyText
        }
        val note = EditText(activity).apply {
            hint = "What were you doing? (optional)"
            minLines = 2
            maxLines = 5
            setSingleLine(false)
        }
        container.addView(intro)
        container.addView(
            note,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad }
        )

        val scroll = ScrollView(activity).apply { addView(container) }
        val title = dialogTitle(pending)

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Send report") { _, _ ->
                submit(activity, pending, note.text?.toString())
                Toast.makeText(activity, "Thanks — crash report sent", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("OK") { _, _ ->
                // User saw the explanation; clear so we don't re-prompt every resume.
                clearPending(activity)
            }
            .setNeutralButton("Don't ask again") { _, _ ->
                setNeverPrompt(activity, true)
                clearPending(activity)
            }
            .setCancelable(true)
            .setOnCancelListener {
                // Keep pending so we can ask once more on a later launch.
            }
            .setOnDismissListener {
                promptVisible = false
            }
            .show()
    }

    fun markPromptVisible(visible: Boolean) {
        promptVisible = visible
    }

    internal fun dialogTitle(pending: PendingReport): String {
        return when {
            pending.kind == "distro_crash" && pending.exitCode == 137 ->
                "Android stopped the desktop"
            pending.kind == "distro_crash" ->
                "Linux desktop stopped"
            else ->
                "Send crash report?"
        }
    }

    internal fun buildDialogBody(pending: PendingReport): String {
        val explanation = pending.userExplanation?.takeIf { it.isNotBlank() }
        return if (pending.kind == "distro_crash" && explanation != null) {
            buildString {
                append(explanation)
                append("\n\n")
                append(
                    "Optional: send an anonymous report (app version, device model, Android version, " +
                        "RAM, storage, CPU/GPU, and recent session text) to help improve stability. " +
                        "No personal files."
                )
            }
        } else {
            buildString {
                append("A crash was detected")
                if (!pending.activeDistro.isNullOrBlank()) append(" (${pending.activeDistro})")
                append(".\n\n")
                append(pending.summary)
                append("\n\n")
                append(
                    "Sending a report helps fix this for everyone. The optional report includes " +
                        "app version, device model, Android version, RAM, storage, CPU/GPU, " +
                        "and recent session text. No personal files."
                )
            }
        }
    }

    private fun truncate(value: String?, max: Int): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        return if (trimmed.length <= max) trimmed else trimmed.takeLast(max)
    }
}
