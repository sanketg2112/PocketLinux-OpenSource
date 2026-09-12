package com.sg.linuxgo

import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Setup / container install log append helpers.
 * Always keeps at most [MAX_LOG_LINES] lines in the UI buffer.
 */
object SetupLogSupport {

    /** Match [SetupForegroundService] ring-buffer size. */
    const val MAX_LOG_LINES = 2000

    /**
     * PRoot cannot chmod extracted files to 0777. Pacman emits one warning per
     * file; posting them to the UI freezes the install screen.
     */
    fun isInstallLogNoise(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        return t.contains("Can't set permissions") ||
            t.contains("warning given when extracting") ||
            t.contains("directory permissions differ") ||
            t.startsWith("filesystem:") ||
            t.startsWith("package:")
    }

    fun formatLogLine(message: String): String {
        // If the message was already stamped by the service thread (starts with "[HH:mm:ss]"),
        // don't prepend a second timestamp — that's what caused the sync mismatch.
        val alreadyStamped = message.matches(Regex("^\\[\\d{2}:\\d{2}:\\d{2}\\] .*"))
        return if (alreadyStamped) {
            message
        } else {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            "[$timestamp] $message"
        }
    }

    /**
     * Append or in-place-replace a log line on the status mini-log.
     *
     * Lines containing `\r` (from [ProotRunner]'s CR-aware pump) replace the last
     * row — that is how curl/wget progress stays on one line. Blank frames are
     * dropped so CR-LF pairs do not open empty rows.
     */
    fun appendLogLineToMini(
        line: String,
        tvMiniLog: TextView,
        miniLogScroll: ScrollView?,
        lastMiniLogInteractionTime: Long,
        setProgrammaticScroll: (Boolean) -> Unit,
        onTextChanged: (String) -> Unit
    ) {
        val hasCarriageReturn = line.contains('\r')
        // Keep the last segment after CR (in case multiple frames arrived glued).
        val plain = line.substringAfterLast('\r').replace("\r", "")
        // Drop blank frames (e.g. bare CR/LF) so they never open an empty log row.
        if (plain.isBlank()) return

        val styledLine = AnsiParser.ansiToSpannable(plain)
        val editable = tvMiniLog.editableText

        if (editable != null) {
            if (hasCarriageReturn) {
                val lastNewline = editable.lastIndexOf('\n')
                if (lastNewline >= 0) {
                    editable.replace(lastNewline + 1, editable.length, styledLine)
                } else {
                    editable.replace(0, editable.length, styledLine)
                }
            } else {
                if (editable.isNotEmpty()) editable.append("\n")
                editable.append(styledLine)
            }
            trimToMaxLines(tvMiniLog)
            onTextChanged(tvMiniLog.text.toString())
        } else {
            if (hasCarriageReturn) {
                val text = tvMiniLog.text
                val lastNewline = text.lastIndexOf('\n')
                if (lastNewline >= 0) {
                    tvMiniLog.text = text.subSequence(0, lastNewline)
                    tvMiniLog.append("\n")
                    tvMiniLog.append(styledLine)
                } else {
                    tvMiniLog.text = styledLine
                }
            } else {
                if (tvMiniLog.text.isNotEmpty()) tvMiniLog.append("\n")
                tvMiniLog.append(styledLine)
            }
            trimToMaxLines(tvMiniLog)
            onTextChanged(tvMiniLog.text.toString())
        }

        val timeSinceInteraction = System.currentTimeMillis() - lastMiniLogInteractionTime
        if (lastMiniLogInteractionTime == 0L || timeSinceInteraction > 3000) {
            setProgrammaticScroll(true)
            miniLogScroll?.post { miniLogScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Drop oldest lines so the UI buffer never exceeds [MAX_LOG_LINES]. */
    private fun trimToMaxLines(tvMiniLog: TextView) {
        val editable = tvMiniLog.editableText
        if (editable != null) {
            var newlineCount = 0
            for (i in editable.indices) {
                if (editable[i] == '\n') newlineCount++
            }
            // newlineCount + 1 ≈ line count when text is non-empty
            val lineCount = if (editable.isEmpty()) 0 else newlineCount + 1
            if (lineCount <= MAX_LOG_LINES) return
            var drop = lineCount - MAX_LOG_LINES
            var cutAt = 0
            while (drop > 0 && cutAt < editable.length) {
                if (editable[cutAt] == '\n') drop--
                cutAt++
            }
            if (cutAt > 0 && cutAt <= editable.length) {
                editable.delete(0, cutAt)
            }
        } else {
            val text = tvMiniLog.text?.toString() ?: return
            if (text.isEmpty()) return
            val lines = text.split('\n')
            if (lines.size <= MAX_LOG_LINES) return
            tvMiniLog.text = lines.takeLast(MAX_LOG_LINES).joinToString("\n")
        }
    }
}
