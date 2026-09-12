package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedLineTrackerTest {

    private fun tracker(
        history: MutableList<String> = mutableListOf(),
        snippets: List<SnippetEntity> = emptyList(),
        sessionId: String = "s1",
        sent: MutableList<String> = mutableListOf(),
        executed: MutableList<String> = mutableListOf()
    ): TypedLineTracker {
        return TypedLineTracker(
            maxCommandHistory = 5,
            initialHistory = history.toList(),
            saveHistory = { history.clear(); history.addAll(it) },
            clearHistoryPrefs = { history.clear() },
            sendInputToSession = { _, input -> sent.add(input) },
            currentSessionId = { sessionId },
            snippets = { snippets },
            executeSnippet = { executed.add(it.command) }
        )
    }

    @Test
    fun recordCommandDedupesAndCaps() {
        val history = mutableListOf<String>()
        val t = tracker(history = history)
        t.recordCommand("ls")
        t.recordCommand("pwd")
        t.recordCommand("ls")
        assertEquals(listOf("ls", "pwd"), t.commandHistory.value)
        assertEquals(listOf("ls", "pwd"), history)

        repeat(10) { t.recordCommand("c$it") }
        assertEquals(5, t.commandHistory.value.size)
    }

    @Test
    fun recordCommandSkipsEmptyAndControlNoise() {
        val t = tracker()
        t.recordCommand("   ")
        t.recordCommand("\u0003")
        assertTrue(t.commandHistory.value.isEmpty())
    }

    @Test
    fun trackTypedInputBuildsLineAndRecordsOnEnter() {
        val t = tracker(sessionId = "s1")
        t.trackTypedInput("s1", "echo hi")
        assertEquals("echo hi", t.currentTypedLine.value)
        t.trackTypedInput("s1", "\r")
        assertEquals("", t.currentTypedLine.value)
        assertEquals(listOf("echo hi"), t.commandHistory.value)
    }

    @Test
    fun trackTypedInputHandlesBackspaceAndCtrlC() {
        val t = tracker(sessionId = "s1")
        t.trackTypedInput("s1", "abcd")
        t.trackTypedInput("s1", "\u007f\u007f")
        assertEquals("ab", t.currentTypedLine.value)
        t.trackTypedInput("s1", "\u0003")
        assertEquals("", t.currentTypedLine.value)
    }

    @Test
    fun commandSuggestionsPreferHistoryThenSnippets() {
        val snippets = listOf(
            SnippetEntity(id = "1", title = "List", command = "ls -la")
        )
        val history = mutableListOf("ls", "ls -l")
        val t = tracker(history = history, snippets = snippets)
        val hits = t.commandSuggestions("ls")
        assertEquals("ls", hits[0].command)
        assertEquals(SuggestionSource.HISTORY, hits[0].source)
        assertTrue(hits.any { it.command == "ls -la" && it.source == SuggestionSource.SNIPPET })
    }

    @Test
    fun snippetHighlightNavigationWraps() {
        val t = tracker()
        assertFalse(t.moveSnippetHighlight(1))
        t.syncSnippetSuggestionCount(3)
        assertEquals(0, t.snippetHighlightIndex.value)
        assertTrue(t.moveSnippetHighlight(1))
        assertEquals(1, t.snippetHighlightIndex.value)
        assertTrue(t.moveSnippetHighlight(1))
        assertEquals(2, t.snippetHighlightIndex.value)
        assertTrue(t.moveSnippetHighlight(1))
        assertEquals(0, t.snippetHighlightIndex.value)
        assertTrue(t.moveSnippetHighlight(-1))
        assertEquals(2, t.snippetHighlightIndex.value)
    }

    @Test
    fun tryAcceptTopSnippetInjectsAndErasesPartial() {
        val sent = mutableListOf<String>()
        val executed = mutableListOf<String>()
        val snippets = listOf(
            SnippetEntity(id = "1", title = "Disk", command = "df -h")
        )
        val t = tracker(
            snippets = snippets,
            sessionId = "s1",
            sent = sent,
            executed = executed
        )
        t.trackTypedInput("s1", "df")
        assertTrue(t.tryAcceptTopSnippetSuggestion())
        assertEquals(listOf("df -h"), executed)
        // erased two chars of partial "df"
        assertTrue(sent.any { it == "\u007f\u007f" })
        assertEquals("", t.currentTypedLine.value)
    }

    @Test
    fun clearCommandHistoryEmptiesPrefs() {
        val history = mutableListOf("a", "b")
        val t = tracker(history = history)
        t.clearCommandHistory()
        assertTrue(t.commandHistory.value.isEmpty())
        assertTrue(history.isEmpty())
    }
}
