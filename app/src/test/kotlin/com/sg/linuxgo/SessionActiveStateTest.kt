package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActiveStateTest {

    @Test
    fun pickActiveContainerCandidatePrefersMemoryThenKeepAliveThenPrefs() {
        assertEquals("m", pickActiveContainerCandidate("m", "k", "p"))
        assertEquals("k", pickActiveContainerCandidate(null, "k", "p"))
        assertEquals("p", pickActiveContainerCandidate(null, null, "p"))
        assertNull(pickActiveContainerCandidate(null, null, null))
        assertEquals("k", pickActiveContainerCandidate("", "k", "p"))
    }

    @Test
    fun shouldBindOnlyWithProcessEvidenceAndNotDuringUserStop() {
        assertTrue(shouldBindActiveContainer(true, userStopInProgress = false))
        assertFalse(shouldBindActiveContainer(true, userStopInProgress = true))
        assertFalse(shouldBindActiveContainer(false, userStopInProgress = false))
        assertFalse(shouldBindActiveContainer(false, userStopInProgress = true))
    }

    @Test
    fun computeSnapshotShowsGuiWhenDisplayServerUp() {
        val s = computeSessionActiveSnapshot(
            isX11Started = false,
            isX11SessionAlive = false,
            lorieConnected = false,
            displayServerRunning = true,
            waylandRunning = false,
            terminalSessionsRunning = false,
            guestRuntimeRunning = true,
            hasActiveContainerId = true,
            isInstalling = false
        )
        assertTrue(s.isGuiActive)
        assertFalse(s.isShellActive)
    }

    @Test
    fun computeSnapshotIdleWhenOnlyKeepAliveWithoutProcesses() {
        // Gate/keep-alive alone must NOT force Resume — that resurrected Stop.
        val s = computeSessionActiveSnapshot(
            isX11Started = false,
            isX11SessionAlive = false,
            lorieConnected = false,
            displayServerRunning = false,
            waylandRunning = false,
            terminalSessionsRunning = false,
            guestRuntimeRunning = false,
            hasActiveContainerId = true,
            isInstalling = false
        )
        assertFalse(s.isGuiActive)
        assertFalse(s.isShellActive)
    }

    @Test
    fun computeSnapshotIdleDuringUserStopEvenIfProcessesStillDying() {
        val s = computeSessionActiveSnapshot(
            isX11Started = true,
            isX11SessionAlive = true,
            lorieConnected = true,
            displayServerRunning = true,
            waylandRunning = false,
            terminalSessionsRunning = false,
            guestRuntimeRunning = true,
            hasActiveContainerId = true,
            isInstalling = false,
            userStopInProgress = true
        )
        assertFalse(s.isGuiActive)
        assertFalse(s.isShellActive)
    }

    @Test
    fun computeSnapshotPrefersTerminalWhenShellSessionsLive() {
        val s = computeSessionActiveSnapshot(
            isX11Started = false,
            isX11SessionAlive = false,
            lorieConnected = false,
            displayServerRunning = false,
            waylandRunning = false,
            terminalSessionsRunning = true,
            guestRuntimeRunning = true,
            hasActiveContainerId = true,
            isInstalling = false
        )
        assertFalse(s.isGuiActive)
        assertTrue(s.isShellActive)
    }

    @Test
    fun computeSnapshotIdleWhenNothingLive() {
        val s = computeSessionActiveSnapshot(
            isX11Started = false,
            isX11SessionAlive = false,
            lorieConnected = false,
            displayServerRunning = false,
            waylandRunning = false,
            terminalSessionsRunning = false,
            guestRuntimeRunning = false,
            hasActiveContainerId = false,
            isInstalling = false
        )
        assertFalse(s.isGuiActive)
        assertFalse(s.isShellActive)
    }

    @Test
    fun computeSnapshotShellFromGuestOnlyWithoutDisplay() {
        val s = computeSessionActiveSnapshot(
            isX11Started = false,
            isX11SessionAlive = false,
            lorieConnected = false,
            displayServerRunning = false,
            waylandRunning = false,
            terminalSessionsRunning = false,
            guestRuntimeRunning = true,
            hasActiveContainerId = true,
            isInstalling = false
        )
        assertFalse(s.isGuiActive)
        assertTrue(s.isShellActive)
    }

    @Test
    fun exclusiveConflictWhenLaunchingDesktopWhileTerminalLive() {
        assertEquals(
            ExclusiveSessionConflict.TERMINAL_RUNNING,
            exclusiveSessionConflict(
                launchingDesktop = true,
                desktopLive = false,
                terminalLive = true
            )
        )
        // Broken both-running state: still treat terminal as blocking a fresh desktop.
        assertEquals(
            ExclusiveSessionConflict.TERMINAL_RUNNING,
            exclusiveSessionConflict(
                launchingDesktop = true,
                desktopLive = true,
                terminalLive = true
            )
        )
        assertEquals(
            ExclusiveSessionConflict.NONE,
            exclusiveSessionConflict(
                launchingDesktop = true,
                desktopLive = true,
                terminalLive = false
            )
        )
        assertEquals(
            ExclusiveSessionConflict.NONE,
            exclusiveSessionConflict(
                launchingDesktop = true,
                desktopLive = false,
                terminalLive = false
            )
        )
    }

    @Test
    fun exclusiveDesktopEvidenceIgnoresStickyFlagsAndRequiresAProcess() {
        assertFalse(
            exclusiveDesktopEvidence(
                x11SessionAlive = false,
                x11ProcessAlive = false,
                waylandProcessAlive = false
            )
        )
        assertTrue(
            exclusiveDesktopEvidence(
                x11SessionAlive = true,
                x11ProcessAlive = false,
                waylandProcessAlive = false
            )
        )
        assertTrue(
            exclusiveDesktopEvidence(
                x11SessionAlive = false,
                x11ProcessAlive = true,
                waylandProcessAlive = false
            )
        )
        assertTrue(
            exclusiveDesktopEvidence(
                x11SessionAlive = false,
                x11ProcessAlive = false,
                waylandProcessAlive = true
            )
        )
        assertTrue(isAppProcessSuffixProbe(":x11"))
        assertTrue(isAppProcessSuffixProbe(":wayland"))
        assertFalse(isAppProcessSuffixProbe("libproot.so"))
        assertFalse(isAppProcessSuffixProbe("proot"))
    }

    @Test
    fun exclusiveConflictSkippedWhenResumingSameMode() {
        assertEquals(
            ExclusiveSessionConflict.NONE,
            exclusiveSessionConflict(
                launchingDesktop = false,
                desktopLive = true,
                terminalLive = true,
                resumingSameMode = true
            )
        )
        assertEquals(
            ExclusiveSessionConflict.NONE,
            exclusiveSessionConflict(
                launchingDesktop = true,
                desktopLive = true,
                terminalLive = true,
                resumingSameMode = true
            )
        )
        // Fresh terminal while a real desktop is up still conflicts.
        assertEquals(
            ExclusiveSessionConflict.DESKTOP_RUNNING,
            exclusiveSessionConflict(
                launchingDesktop = false,
                desktopLive = true,
                terminalLive = false,
                resumingSameMode = false
            )
        )
    }

    @Test
    fun exclusiveConflictWhenLaunchingTerminalWhileDesktopLive() {
        assertEquals(
            ExclusiveSessionConflict.DESKTOP_RUNNING,
            exclusiveSessionConflict(
                launchingDesktop = false,
                desktopLive = true,
                terminalLive = false
            )
        )
        assertEquals(
            ExclusiveSessionConflict.DESKTOP_RUNNING,
            exclusiveSessionConflict(
                launchingDesktop = false,
                desktopLive = true,
                terminalLive = true
            )
        )
        assertEquals(
            ExclusiveSessionConflict.NONE,
            exclusiveSessionConflict(
                launchingDesktop = false,
                desktopLive = false,
                terminalLive = true
            )
        )
        assertEquals(
            ExclusiveSessionConflict.NONE,
            exclusiveSessionConflict(
                launchingDesktop = false,
                desktopLive = false,
                terminalLive = false
            )
        )
    }

    @Test
    fun exclusivePromptCopyNamesContainerAndOffersStop() {
        val desktop = exclusiveSessionPrompt(
            ExclusiveSessionConflict.TERMINAL_RUNNING,
            "Debian XFCE"
        )
        assertEquals("Can't run both at once", desktop?.title)
        assertTrue(desktop?.message?.contains("Debian XFCE") == true)
        assertTrue(desktop?.message?.contains("can't run at the same time") == true)
        assertEquals("Stop terminal & launch", desktop?.confirmLabel)
        assertEquals("Keep terminal", desktop?.dismissLabel)

        val terminal = exclusiveSessionPrompt(
            ExclusiveSessionConflict.DESKTOP_RUNNING,
            "Ubuntu"
        )
        assertEquals("Can't run both at once", terminal?.title)
        assertTrue(terminal?.message?.contains("Ubuntu") == true)
        assertEquals("Stop desktop & open", terminal?.confirmLabel)
        assertEquals("Keep desktop", terminal?.dismissLabel)

        assertNull(exclusiveSessionPrompt(ExclusiveSessionConflict.NONE, "x"))
    }
}
