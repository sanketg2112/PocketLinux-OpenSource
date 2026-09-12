package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLogSupportTest {

    @Test
    fun formatLogLineDoesNotDoubleStamp() {
        val stamped = "[12:34:56] already stamped"
        assertEquals(stamped, SetupLogSupport.formatLogLine(stamped))
    }

    @Test
    fun formatLogLineAddsTimestamp() {
        val result = SetupLogSupport.formatLogLine("hello setup")
        assertTrue(result.matches(Regex("""^\[\d{2}:\d{2}:\d{2}\] hello setup$""")))
    }

    @Test
    fun maxLogLinesConstantMatchesServiceBufferIntent() {
        assertEquals(2000, SetupLogSupport.MAX_LOG_LINES)
        assertFalse(SetupLogSupport.MAX_LOG_LINES < 100)
    }

    @Test
    fun dropsProotPacmanChmodNoise() {
        assertTrue(
            SetupLogSupport.isInstallLogNoise(
                "warning: warning given when extracting /usr/share/man/man3/X509.3ssl.gz (Can't set permissions to 0777)"
            )
        )
        assertTrue(SetupLogSupport.isInstallLogNoise("warning: directory permissions differ on /etc/sudoers.d/"))
        assertTrue(SetupLogSupport.isInstallLogNoise("filesystem: 755  package: 750"))
        assertFalse(SetupLogSupport.isInstallLogNoise("upgrading glibc..."))
        assertFalse(SetupLogSupport.isInstallLogNoise("=== Phase 1 done ==="))
    }
}
