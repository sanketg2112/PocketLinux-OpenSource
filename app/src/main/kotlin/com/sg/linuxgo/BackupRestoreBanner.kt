package com.sg.linuxgo

/**
 * Settings Backup & restore progress chip. Visible only while
 * [BackupRestoreService] is actually running — a finished restore must not
 * leave the bar up, including after a late extract-monitor tick.
 */
data class BackupRestoreBanner(
    val visible: Boolean,
    val progress: Float,
    val message: String
) {
    companion object {
        val Hidden = BackupRestoreBanner(visible = false, progress = 0f, message = "")

        fun running(progressPercent: Int, message: String): BackupRestoreBanner {
            val pct = progressPercent.coerceIn(0, 100)
            return BackupRestoreBanner(
                visible = true,
                progress = pct / 100f,
                message = message.ifBlank { "Operation in progress…" }
            )
        }

        fun acceptProgress(serviceRunning: Boolean): Boolean = serviceRunning

        /**
         * Rebuild from service statics. Idle (or completed) always hides —
         * completion is handled separately (Install Complete dialog), not by
         * leaving this chip at 100%.
         */
        fun hydrate(
            serviceRunning: Boolean,
            lastProgress: Int,
            lastMessage: String
        ): BackupRestoreBanner {
            if (!serviceRunning) return Hidden
            return running(lastProgress, lastMessage)
        }
    }
}
