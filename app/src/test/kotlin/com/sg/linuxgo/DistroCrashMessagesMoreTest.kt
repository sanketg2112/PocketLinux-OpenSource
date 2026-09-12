package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extends [DistroCrashMessagesTest] for non-OOM exit codes that were only lightly covered.
 */
class DistroCrashMessagesMoreTest {

    @Test
    fun toastAndExplanationForNativeCrash139() {
        val toast = DistroCrashMessages.toastForExit(139)
        assertTrue(toast.contains("native", ignoreCase = true) || toast.contains("crashed", ignoreCase = true))

        val explanation = DistroCrashMessages.explanationForExit(139, "Debian XFCE")
        assertTrue(explanation.contains("Debian XFCE"))
        assertTrue(explanation.contains("139"))
        assertFalse(explanation.contains("PocketLinux intentionally", ignoreCase = true))
    }

    @Test
    fun genericExitIncludesCodeAndSuggestions() {
        val explanation = DistroCrashMessages.explanationForExit(1, null)
        assertTrue(explanation.contains("Exit code: 1"))
        assertTrue(explanation.contains("Free RAM") || explanation.contains("memory") || explanation.length > 40)

        val summaryFg = DistroCrashMessages.summaryForExit(1, appInForeground = true)
        assertTrue(summaryFg.contains("foreground"))
        val summaryBg = DistroCrashMessages.summaryForExit(1, appInForeground = false)
        assertTrue(summaryBg.contains("background"))
    }
}
