package com.sg.linuxgo
import com.sg.linuxgo.SnippetEntity

data class SnippetPromptState(
    val snippet: SnippetEntity,
    val variablesNeeded: List<String>,
    val valuesProvided: Map<String, String>
)

enum class SuggestionSource { HISTORY, SNIPPET }

data class CommandSuggestion(
    val command: String,
    val label: String,
    val source: SuggestionSource
)

/**
 * Rank saved snippets against the in-progress terminal line for autocomplete.
 * Pure helper so matching can be unit-tested without a ViewModel.
 */
fun matchSnippetSuggestions(
    query: String,
    snippets: List<SnippetEntity>,
    limit: Int = 6
): List<SnippetEntity> {
    val q = query.trim()
    if (q.isEmpty() || limit <= 0) return emptyList()
    return snippets
        .asSequence()
        .filter { s ->
            // Already fully on the line (typed or inserted) — nothing left to complete.
            // Keeps Enter free to execute after Tab/Enter insert.
            if (s.command.equals(q, ignoreCase = true)) return@filter false
            s.command.startsWith(q, ignoreCase = true) ||
                s.title.startsWith(q, ignoreCase = true)
        }
        .sortedWith(
            compareBy<SnippetEntity> {
                when {
                    it.command.startsWith(q, ignoreCase = true) -> 0
                    it.title.startsWith(q, ignoreCase = true) -> 1
                    else -> 2
                }
            }.thenBy { it.title.ifBlank { it.command } }
        )
        .take(limit)
        .toList()
}
