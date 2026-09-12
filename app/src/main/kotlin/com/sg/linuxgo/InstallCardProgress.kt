package com.sg.linuxgo

/**
 * Tracks install-card phase / percent for the home container list.
 */
class InstallCardProgress {

    var phase: String = "Starting setup..."
    var percent: Int = -1
    var downloading: String = ""
    var dots: String = ""
    /** Bytes downloaded (image/rootfs). -1 = unknown / not a file download. */
    var downloadCurrentBytes: Long = -1L
    /** Total bytes of the file being downloaded. -1 = unknown. */
    var downloadTotalBytes: Long = -1L

    fun reset() {
        phase = "Starting setup..."
        percent = -1
        downloading = ""
        dots = ""
        downloadCurrentBytes = -1L
        downloadTotalBytes = -1L
    }

    /**
     * Progress is overall install % (monotonic) — card shows % above the bar only.
     * During file downloads, status below the bar is size only: "Downloading (v1): 320/780 MB".
     * Install phases use the phase label without an inline percent.
     */
    fun buildMessageAndPercent(): Pair<String, Int> {
        val pct = if (percent in 0..100) percent else 0
        return if (downloading.isNotEmpty()) {
            stripInlinePercent(downloading) to pct
        } else {
            val sb = StringBuilder(phase)
            if (dots.isNotEmpty()) {
                sb.append(dots)
            }
            stripInlinePercent(sb.toString()) to pct
        }
    }

    fun applyHighLevelMessage(cleanMsg: String) {
        if (cleanMsg.contains("Phase ") && cleanMsg.contains("/8")) {
            phase = cleanMsg.substringBefore(":")
            dots = when {
                cleanMsg.endsWith("...") -> "..."
                cleanMsg.endsWith("..") -> ".."
                cleanMsg.endsWith(".") -> "."
                else -> ""
            }
        } else {
            phase = cleanMsg
            dots = ""
        }
        downloading = ""
        downloadCurrentBytes = -1L
        downloadTotalBytes = -1L
    }

    fun applyDownloadProgress(
        fileName: String,
        progress: Int,
        currentBytes: Long = -1L,
        totalBytes: Long = -1L
    ) {
        // Monotonic overall % — same rule as SetupForegroundService notification bar.
        // Card UI shows this above the progress bar only (not in the status line).
        if (progress >= 0 && (percent < 0 || progress >= percent)) {
            percent = progress
        }

        // Phase-style labels keep human phase text (via applyHighLevelMessage / hydrate).
        // Real download steps show "Downloading (vX): A/B MB" — % lives only above the card bar.
        // Install/extract steps show the phase name only (no size; totals are not bytes).
        if (isPhaseStyleProgressLabel(fileName)) {
            downloadCurrentBytes = -1L
            downloadTotalBytes = -1L
            downloading = ""
            return
        }

        if (showsDownloadSize(fileName, totalBytes, currentBytes)) {
            if (currentBytes >= 0) downloadCurrentBytes = currentBytes
            if (totalBytes > 0) downloadTotalBytes = totalBytes
        } else {
            // Left download-band: drop stale MB so we never show 0/0 or last download size.
            downloadCurrentBytes = -1L
            downloadTotalBytes = -1L
        }

        val sizePart = formatDownloadSize(downloadCurrentBytes, downloadTotalBytes)
        downloading = if (sizePart.isNotEmpty()) {
            "%s: %s".format(fileName, sizePart)
        } else {
            fileName
        }
    }

    /**
     * Labels that advance overall % without replacing the card status with "Name: N%".
     * Must stay in sync with [SetupForegroundService] phase-style handling.
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
        return fileName.startsWith("Image cached") ||
            fileName.startsWith("Verifying")
    }

    fun hydrateFromService(pct: Int, msg: String) {
        if (pct in 0..100) {
            percent = if (percent < 0) pct else maxOf(percent, pct)
        }
        val cleanMsg = msg
            .let { if (it.startsWith("[") && it.contains("] ")) it.substringAfter("] ") else it }
            .substringBefore(" | Storage:")
            .substringBefore(" - Storage:")
            .trim()
        if (cleanMsg.contains(':') && (
                cleanMsg.substringAfter(':').trim().endsWith("%") ||
                    cleanMsg.contains(" MB") ||
                    cleanMsg.contains(" GB")
                )
        ) {
            // Service/notification may still embed "N% · size"; card status must not show %.
            downloading = stripInlinePercent(cleanMsg)
        } else if (cleanMsg.isNotBlank()) {
            phase = stripInlinePercent(cleanMsg)
            downloading = ""
        }
    }

    companion object {
        /** Minimum total bytes before we render A/B size (avoids percent totals → "0.0/0.0 MB"). */
        private const val MIN_SIZE_BYTES_FOR_LABEL = 1024L * 1024L // 1 MB

        /**
         * True when [fileName]/[totalBytes] represent a real file download, not install %
         * progress masquerading as bytes (e.g. Installing overall=76, total=100).
         */
        fun showsDownloadSize(fileName: String, totalBytes: Long, currentBytes: Long = -1L): Boolean {
            if (fileName.startsWith("Installing") ||
                fileName.startsWith("Extracting") ||
                fileName.startsWith("System Setup") ||
                fileName.startsWith("Finalizing") ||
                fileName.startsWith("Complete") ||
                fileName.startsWith("Resume") ||
                fileName.startsWith("Catalog") ||
                fileName.startsWith("Verifying") ||
                fileName.startsWith("Image cached")
            ) {
                return false
            }
            // Prefer explicit download labels; also accept large content-length totals.
            if (fileName.startsWith("Downloading")) return totalBytes > 0 || currentBytes > 0
            return totalBytes >= MIN_SIZE_BYTES_FOR_LABEL
        }

        /**
         * Remove inline percent from status lines so the card can show % only above the bar.
         * e.g. "Downloading (v1): 42% · 320/780 MB" → "Downloading (v1): 320/780 MB"
         *      "Installing (v1): 76%" → "Installing (v1)"
         */
        fun stripInlinePercent(msg: String): String {
            if (msg.isBlank() || !msg.contains('%')) return msg
            return msg
                .replace(Regex(""":\s*\d+%\s*·\s*"""), ": ")
                .replace(Regex(""":\s*\d+%\s*$"""), "")
                .replace(Regex("""\s+\d+%\s*·\s*"""), " ")
                .replace(Regex("""\s+\d+%\s*$"""), "")
                .trim()
                .trimEnd(':', ' ', '·')
                .trim()
        }

        /** Human size for card: always MB, e.g. "320/780 MB" or "45 MB" when total unknown. */
        fun formatDownloadSize(current: Long, total: Long): String {
            if (total >= MIN_SIZE_BYTES_FOR_LABEL && current >= 0) {
                val c = (current / (1024.0 * 1024.0)).coerceAtLeast(0.0)
                val t = total / (1024.0 * 1024.0)
                return if (t >= 100) {
                    "%.0f/%.0f MB".format(c, t)
                } else {
                    "%.1f/%.1f MB".format(c, t)
                }
            }
            // Unknown total: only show current when it is a real multi-MB amount.
            if (current >= MIN_SIZE_BYTES_FOR_LABEL && total <= 0) {
                return "%.0f MB".format(current / (1024.0 * 1024.0))
            }
            return ""
        }
    }
}
