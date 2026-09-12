package com.sg.linuxgo

import org.junit.Assert.*
import org.junit.Test

class TerminalSessionManagerTest {

    @Test
    fun testInitialState() {
        val manager = TerminalSessionManager()
        assertTrue(manager.sessions.isEmpty())
        assertEquals(-1, manager.activeSessionIndex)
        assertNull(manager.getActiveSession())
    }

    @Test
    fun testAddSession() {
        var sessionsChangedCount = 0
        var activeSessionChangedCount = 0
        var lastActiveIndex = -2

        val manager = TerminalSessionManager(
            onSessionsChanged = { sessionsChangedCount++ },
            onActiveSessionChanged = { index ->
                activeSessionChangedCount++
                lastActiveIndex = index
            }
        )

        val bridge = TerminalBridge()
        val session1 = TerminalSession("s1", "Tab 1", "", emptyArray(), emptyArray(), bridge)
        manager.addSession(session1)

        assertEquals(1, manager.sessions.size)
        assertEquals(session1, manager.sessions[0])
        assertEquals(0, manager.activeSessionIndex)
        assertEquals(session1, manager.getActiveSession())

        assertEquals(1, sessionsChangedCount)
        assertEquals(1, activeSessionChangedCount)
        assertEquals(0, lastActiveIndex)

        val session2 = TerminalSession("s2", "Tab 2", "", emptyArray(), emptyArray(), bridge)
        manager.addSession(session2)

        assertEquals(2, manager.sessions.size)
        assertEquals(session2, manager.sessions[1])
        assertEquals(1, manager.activeSessionIndex)
        assertEquals(session2, manager.getActiveSession())

        assertEquals(2, sessionsChangedCount)
        assertEquals(2, activeSessionChangedCount)
        assertEquals(1, lastActiveIndex)
    }

    @Test
    fun testSelectSession() {
        val manager = TerminalSessionManager()
        val bridge = TerminalBridge()
        val session1 = TerminalSession("s1", "Tab 1", "", emptyArray(), emptyArray(), bridge)
        val session2 = TerminalSession("s2", "Tab 2", "", emptyArray(), emptyArray(), bridge)
        manager.addSession(session1)
        manager.addSession(session2)

        assertEquals(1, manager.activeSessionIndex)

        manager.selectSession(0)
        assertEquals(0, manager.activeSessionIndex)
        assertEquals(session1, manager.getActiveSession())

        // Out of bounds selection should be ignored
        manager.selectSession(5)
        assertEquals(0, manager.activeSessionIndex)

        manager.selectSession(-2)
        assertEquals(0, manager.activeSessionIndex)
    }

    @Test
    fun testRenameSession() {
        var sessionsChangedCount = 0
        val manager = TerminalSessionManager(
            onSessionsChanged = { sessionsChangedCount++ }
        )
        val bridge = TerminalBridge()
        val session = TerminalSession("s1", "Tab 1", "", emptyArray(), emptyArray(), bridge)
        manager.addSession(session)

        sessionsChangedCount = 0
        manager.renameSession(0, "New Title")

        assertEquals("New Title", manager.sessions[0].title)
        assertEquals(1, sessionsChangedCount)

        // Invalid index rename should be ignored
        manager.renameSession(1, "Ignored")
        assertEquals(1, sessionsChangedCount)
    }

    @Test
    fun testRemoveSessionAndClampIndex() {
        val manager = TerminalSessionManager()
        val bridge = TerminalBridge()
        val session1 = TerminalSession("s1", "Tab 1", "", emptyArray(), emptyArray(), bridge)
        val session2 = TerminalSession("s2", "Tab 2", "", emptyArray(), emptyArray(), bridge)
        val session3 = TerminalSession("s3", "Tab 3", "", emptyArray(), emptyArray(), bridge)
        manager.addSession(session1)
        manager.addSession(session2)
        manager.addSession(session3)

        // Currently selected is index 2 (session3)
        assertEquals(2, manager.activeSessionIndex)

        // Remove the active session (index 2) -> active index should clamp to index 1 (session2)
        val removed = manager.removeSessionAt(2)
        assertEquals(session3, removed)
        assertEquals(2, manager.sessions.size)
        assertEquals(1, manager.activeSessionIndex)
        assertEquals(session2, manager.getActiveSession())

        // Select index 0 (session1) and remove index 1 -> active index should remain 0 (session1)
        manager.selectSession(0)
        assertEquals(0, manager.activeSessionIndex)
        manager.removeSessionAt(1)
        assertEquals(1, manager.sessions.size)
        assertEquals(0, manager.activeSessionIndex)
        assertEquals(session1, manager.getActiveSession())
    }

    @Test
    fun testRemoveLastSessionResetsState() {
        val manager = TerminalSessionManager()
        val bridge = TerminalBridge()
        val session = TerminalSession("s1", "Tab 1", "", emptyArray(), emptyArray(), bridge)
        manager.addSession(session)

        assertEquals(0, manager.activeSessionIndex)

        val removed = manager.removeSessionAt(0)
        assertEquals(session, removed)
        assertTrue(manager.sessions.isEmpty())
        assertEquals(-1, manager.activeSessionIndex)
    }

    @Test
    fun testClear() {
        val manager = TerminalSessionManager()
        val bridge = TerminalBridge()
        manager.addSession(TerminalSession("s1", "Tab 1", "", emptyArray(), emptyArray(), bridge))
        manager.addSession(TerminalSession("s2", "Tab 2", "", emptyArray(), emptyArray(), bridge))

        manager.clear()
        assertTrue(manager.sessions.isEmpty())
        assertEquals(-1, manager.activeSessionIndex)
    }
}
