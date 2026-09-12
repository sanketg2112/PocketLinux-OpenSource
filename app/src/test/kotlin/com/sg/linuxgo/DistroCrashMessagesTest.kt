package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroCrashMessagesTest {

    @Test
    fun toast_137_mentions_memory_only_when_ram_is_actually_low() {
        val low = DistroCrashMessages.toastForExit(137, lowRam = true)
        assertTrue(low.contains("Android", ignoreCase = true))
        assertTrue(low.contains("memory", ignoreCase = true))
        assertFalse(low.contains("PocketLinux stopped", ignoreCase = true))

        val plenty = DistroCrashMessages.toastForExit(137, lowRam = false)
        assertFalse(plenty.contains("memory", ignoreCase = true))
        assertFalse(plenty.contains("PocketLinux stopped", ignoreCase = true))
    }

    @Test
    fun explanation_137_says_android_oom_and_suggestions() {
        val e = DistroCrashMessages.explanationForExit(137, "Debian / XFCE")
        assertTrue(e.contains("Android"))
        assertTrue(e.contains("137"))
        assertTrue(e.contains("did not shut the session down on purpose") || e.contains("did not"))
        assertTrue(e.contains("Disable child process restrictions"))
        assertTrue(e.contains("free RAM") || e.contains("Close other"))
    }

    @Test
    fun summary_137_no_auto_recovery() {
        val s = DistroCrashMessages.summaryForExit(137, appInForeground = true)
        assertTrue(s.contains("137"))
        assertTrue(s.contains("No auto-recovery"))
        assertTrue(s.contains("foreground"))
    }

    @Test
    fun dialog_title_for_oom() {
        val pending = CrashReportCoordinator.PendingReport(
            kind = "distro_crash",
            activeDistro = "debian",
            exitCode = 137,
            containerName = "Debian",
            guiMode = "x11",
            recoveryAttempted = false,
            exceptionClass = null,
            exceptionMessage = null,
            stacktraceSnippet = null,
            sessionLogs = null,
            summary = DistroCrashMessages.summaryForExit(137, true),
            queuedAtMs = 0L,
            userExplanation = DistroCrashMessages.explanationForExit(137, "Debian")
        )
        assertTrue(CrashReportCoordinator.dialogTitle(pending).contains("Android"))
        val body = CrashReportCoordinator.buildDialogBody(pending)
        assertTrue(body.contains("Disable child process restrictions"))
        assertTrue(body.contains("anonymous") || body.contains("Optional"))
        assertTrue(body.contains("session text"))
        assertTrue(body.contains("device model"))
        assertTrue(body.contains("RAM"))
        assertTrue(body.contains("CPU/GPU"))
    }

    @Test
    fun appCrashDialogMentionsSessionText() {
        val pending = CrashReportCoordinator.PendingReport(
            kind = "app_crash",
            activeDistro = "debian",
            exitCode = null,
            containerName = null,
            guiMode = null,
            recoveryAttempted = false,
            exceptionClass = "java.lang.NullPointerException",
            exceptionMessage = null,
            stacktraceSnippet = null,
            sessionLogs = "ls",
            summary = "App process died",
            queuedAtMs = 0L
        )
        val body = CrashReportCoordinator.buildDialogBody(pending)
        assertTrue(body.contains("session text"))
        assertTrue(body.contains("device model"))
        assertTrue(body.contains("RAM"))
        assertTrue(body.contains("CPU/GPU"))
        assertFalse(body.contains("device diagnostics"))
    }
}
