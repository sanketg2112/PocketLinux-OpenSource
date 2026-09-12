package com.sg.linuxgo

/**
 * User-facing copy for desktop / display session deaths.
 *
 * Philosophy: explain what **Android** did (especially low-RAM OOM), suggest
 * optional steps, never imply PocketLinux intentionally killed the desktop.
 */
object DistroCrashMessages {

    /**
     * Short toast when returning home after a dead GUI.
     * Only mention low memory when [lowRam] is true — exit 137 is also used
     * for child-process kills on phones that still have several GB free.
     */
    fun toastForExit(exitCode: Int, lowRam: Boolean = false): String {
        return when (exitCode) {
            137 ->
                if (lowRam) {
                    "Android stopped the desktop (low memory). Free RAM and try again."
                } else {
                    "The Linux desktop stopped. Try starting it again."
                }
            139 ->
                if (lowRam) {
                    "The Linux desktop crashed (native error). Free RAM and try again."
                } else {
                    "The Linux desktop crashed (native error). Try starting it again."
                }
            else ->
                "The Linux desktop stopped. Try starting it again."
        }
    }

    /**
     * Main explanation shown in the crash dialog (reason + suggestions).
     */
    fun explanationForExit(exitCode: Int, distroLabel: String?): String {
        val where = distroLabel?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return when (exitCode) {
            137 -> buildString {
                append("Android stopped your Linux desktop$where.\n\n")
                append(
                    "What happened: the system killed the desktop process because the phone " +
                        "was low on free memory (exit 137 = Android OOM / SIGKILL). " +
                        "PocketLinux did not shut the session down on purpose.\n\n"
                )
                append(SUGGESTIONS)
            }
            139 -> buildString {
                append("Your Linux desktop$where crashed (exit 139).\n\n")
                append(
                    "What happened: a Linux process inside the container hit a native error " +
                        "(often while the phone was under memory pressure). " +
                        "This is not a normal force-stop by PocketLinux.\n\n"
                )
                append(SUGGESTIONS)
            }
            else -> buildString {
                append("Your Linux desktop$where stopped unexpectedly.\n\n")
                if (exitCode > 0) {
                    append("Exit code: $exitCode.\n\n")
                }
                append(
                    "Often Android reclaims memory or background processes under pressure. " +
                        "PocketLinux did not intentionally end your session.\n\n"
                )
                append(SUGGESTIONS)
            }
        }
    }

    /** One-line summary stored on crash_report / pending queue. */
    fun summaryForExit(exitCode: Int, appInForeground: Boolean): String {
        val fg = if (appInForeground) {
            " The app was in the foreground."
        } else {
            " PocketLinux was in the background."
        }
        return when (exitCode) {
            137 ->
                "Android killed the Linux desktop (exit 137 / low memory / OOM).$fg " +
                    "No auto-recovery was attempted."
            139 ->
                "Linux desktop died (exit 139 / native crash).$fg " +
                    "No auto-recovery was attempted."
            else ->
                "Linux desktop session died${if (exitCode > 0) " (exit $exitCode)" else ""}.$fg " +
                    "No auto-recovery was attempted."
        }
    }

    const val SUGGESTIONS =
        "Suggestions (optional — you stay in control):\n" +
            "• Close other Android apps to free RAM, then start the desktop again.\n" +
            "• On Android 12+: Developer options → turn on “Disable child process restrictions” " +
            "so Linux child processes are less likely to be killed.\n" +
            "• Set PocketLinux battery use to Unrestricted if the session dies in the background.\n" +
            "• Keep notifications allowed so the “session running” notice can help Android leave the session alone."
}
