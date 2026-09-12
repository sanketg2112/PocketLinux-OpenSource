package com.sg.linuxgo
import com.sg.linuxgo.SnippetEntity
import com.sg.linuxgo.CommandSuggestion
import com.sg.linuxgo.SuggestionSource
import com.sg.linuxgo.matchSnippetSuggestions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks in-progress shell lines per session, command history, and in-terminal
 * snippet autocomplete highlight state. [com.sg.linuxgo.ui.viewmodel.TerminalViewModel]
 * remains the public facade.
 */
internal class TypedLineTracker(
    private val maxCommandHistory: Int,
    initialHistory: List<String>,
    private val saveHistory: (List<String>) -> Unit,
    private val clearHistoryPrefs: () -> Unit,
    private val sendInputToSession: (sessionId: String, input: String) -> Unit,
    private val currentSessionId: () -> String?,
    private val snippets: () -> List<SnippetEntity>,
    private val executeSnippet: (SnippetEntity) -> Unit
) {
    private val _commandHistory = MutableStateFlow(initialHistory)
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()

    /** In-progress command lines per session, rebuilt until Enter is sent. */
    private val sessionLineBuffers = ConcurrentHashMap<String, StringBuilder>()

    private val _currentTypedLine = MutableStateFlow("")
    val currentTypedLine: StateFlow<String> = _currentTypedLine.asStateFlow()

    private val _snippetHighlightIndex = MutableStateFlow(0)
    val snippetHighlightIndex: StateFlow<Int> = _snippetHighlightIndex.asStateFlow()

    /** Last suggestion count reported by the UI (for clamping highlight / nav). */
    private var snippetSuggestionCount: Int = 0

    /**
     * Record a finished command for suggestions. Dedupes (moves to front) and caps size.
     */
    fun recordCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        // Skip pure control / tiny noise
        if (cmd.length == 1 && cmd[0].code < 32) return
        val next = buildList {
            add(cmd)
            _commandHistory.value.forEach { existing ->
                if (existing != cmd) add(existing)
            }
        }.take(maxCommandHistory)
        _commandHistory.value = next
        saveHistory(next)
    }

    fun clearCommandHistory() {
        _commandHistory.value = emptyList()
        clearHistoryPrefs()
    }

    /**
     * Suggestions for the add-macro command field: matching recent commands + snippet commands.
     */
    fun commandSuggestions(query: String, limit: Int = 8): List<CommandSuggestion> {
        val q = query.trim()
        val out = LinkedHashMap<String, CommandSuggestion>()

        fun consider(text: String, label: String, source: SuggestionSource) {
            val t = text.trim()
            if (t.isEmpty()) return
            if (q.isNotEmpty() && !t.startsWith(q, ignoreCase = true) && !label.startsWith(q, ignoreCase = true)) {
                return
            }
            // Prefer first occurrence (recent history wins over older snippets of same text)
            if (!out.containsKey(t)) {
                out[t] = CommandSuggestion(command = t, label = label, source = source)
            }
        }

        _commandHistory.value.forEach { consider(it, it, SuggestionSource.HISTORY) }
        snippets().forEach { s ->
            consider(s.command, s.title.ifBlank { s.command }, SuggestionSource.SNIPPET)
        }

        return out.values.take(limit)
    }

    /**
     * Snippet suggestions for the in-terminal autocomplete bar.
     * Only saved snippets (not recent history); shown once the user starts typing.
     */
    fun snippetSuggestionsForTypedLine(query: String, limit: Int = 6): List<SnippetEntity> {
        return matchSnippetSuggestions(query, snippets(), limit)
    }

    /**
     * Keep the highlight index in range when the UI recomputes the visible list.
     * Resets to 0 when the match set size changes (query evolved).
     */
    fun syncSnippetSuggestionCount(count: Int) {
        val prev = snippetSuggestionCount
        snippetSuggestionCount = count.coerceAtLeast(0)
        if (count <= 0) {
            _snippetHighlightIndex.value = 0
            return
        }
        if (count != prev) {
            // New match set → start on the top hit again.
            _snippetHighlightIndex.value = 0
        } else {
            _snippetHighlightIndex.value =
                _snippetHighlightIndex.value.coerceIn(0, count - 1)
        }
    }

    /**
     * Move the highlighted suggestion by [delta] (−1 up, +1 down). Wraps at ends.
     * @return true if suggestions were active and the key was consumed (do not send to PTY).
     */
    fun moveSnippetHighlight(delta: Int): Boolean {
        val count = snippetSuggestionCount
        if (count <= 0) return false
        if (count == 1) {
            // Still consume arrows so the shell doesn't get history-nav while a
            // single suggestion is offered.
            _snippetHighlightIndex.value = 0
            return true
        }
        val cur = _snippetHighlightIndex.value.coerceIn(0, count - 1)
        val next = ((cur + delta) % count + count) % count
        _snippetHighlightIndex.value = next
        return true
    }

    /**
     * Accept a snippet suggestion while typing: erase the partial line, then inject
     * the snippet command text (no trailing Enter — user executes when ready).
     */
    fun acceptSnippetFromTyping(snippet: SnippetEntity) {
        val sessionId = currentSessionId() ?: return
        erasePartialTypedLine(sessionId)
        sessionLineBuffers[sessionId]?.clear()
        publishTypedLine(sessionId)
        snippetSuggestionCount = 0
        _snippetHighlightIndex.value = 0
        // Do not remount the IME host here — that destroyed the InputConnection
        // so Enter would not execute until the keyboard was closed and reopened.
        executeSnippet(snippet)
    }

    /**
     * Accept the currently highlighted snippet for the typed line.
     * Used by Tab and Enter (physical keyboard / extra-keys bar). Space never auto-accepts.
     * Injects command text only — does not run it.
     * @return true if a suggestion was accepted (caller should not send Tab / Enter).
     */
    fun tryAcceptTopSnippetSuggestion(): Boolean {
        val list = snippetSuggestionsForTypedLine(_currentTypedLine.value, limit = 5)
        if (list.isEmpty()) return false
        val idx = _snippetHighlightIndex.value.coerceIn(0, list.lastIndex)
        acceptSnippetFromTyping(list[idx])
        return true
    }

    fun publishTypedLine(sessionId: String) {
        if (sessionId == currentSessionId()) {
            _currentTypedLine.value = sessionLineBuffers[sessionId]?.toString().orEmpty()
        }
    }

    fun onActiveSessionChanged(sessionId: String?) {
        _currentTypedLine.value = sessionId
            ?.let { sessionLineBuffers[it]?.toString().orEmpty() }
            .orEmpty()
    }

    /** Erase characters we tracked for the current line (PTY + local buffer). */
    fun erasePartialTypedLine(sessionId: String) {
        val partial = sessionLineBuffers[sessionId]?.toString().orEmpty()
        if (partial.isEmpty()) return
        // Backspace each tracked char so the shell prompt line is cleared before inject.
        sendInputToSession(sessionId, "\u007f".repeat(partial.length))
    }

    /**
     * Clear partial typed text (PTY + local buffer) before injecting a full command.
     */
    fun prepareCommandInjection(sessionId: String) {
        erasePartialTypedLine(sessionId)
        sessionLineBuffers[sessionId]?.clear()
        publishTypedLine(sessionId)
    }

    fun clearSession(sessionId: String) {
        sessionLineBuffers.remove(sessionId)
    }

    fun trackTypedInput(sessionId: String, input: String) {
        if (sessionId.isEmpty()) return
        val buf = sessionLineBuffers.getOrPut(sessionId) { StringBuilder() }
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\r' || c == '\n' -> {
                    val cmd = buf.toString().trim()
                    if (cmd.isNotEmpty()) recordCommand(cmd)
                    buf.clear()
                }
                c == '\u007f' || c == '\b' -> {
                    if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
                }
                c == '\u0003' -> { // Ctrl-C — abandon line
                    buf.clear()
                }
                c == '\u0015' -> { // Ctrl-U — kill line
                    buf.clear()
                }
                c.code >= 32 -> buf.append(c)
                // ignore other controls for history purposes
            }
            i++
        }
        publishTypedLine(sessionId)
    }
}
