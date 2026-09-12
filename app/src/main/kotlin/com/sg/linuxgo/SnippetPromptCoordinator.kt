package com.sg.linuxgo
import com.sg.linuxgo.SnippetEntity
import com.sg.linuxgo.SnippetPromptState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the snippet variable-prompt dialog state and expands snippets into shell text.
 *
 * Selected snippets are **injected** into the terminal line (no trailing Enter) so the
 * user can review / edit and execute themselves.
 */
internal class SnippetPromptCoordinator(
    /** Inject expanded command text without auto-executing (no trailing newline). */
    private val injectCommand: (String) -> Unit
) {
    private val _activeSnippetPrompt = MutableStateFlow<SnippetPromptState?>(null)
    val activeSnippetPrompt: StateFlow<SnippetPromptState?> = _activeSnippetPrompt.asStateFlow()

    fun executeSnippet(snippet: SnippetEntity) {
        val vars = if (snippet.variablesString.isNotEmpty()) {
            snippet.variablesString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        if (vars.isNotEmpty()) {
            _activeSnippetPrompt.value = SnippetPromptState(
                snippet = snippet,
                variablesNeeded = vars,
                valuesProvided = emptyMap()
            )
        } else {
            injectCommand(snippet.command)
        }
    }

    fun submitSnippetVariables(values: Map<String, String>) {
        val promptState = _activeSnippetPrompt.value ?: return
        var command = promptState.snippet.command
        values.forEach { (variable, value) ->
            command = command.replace("{{$variable}}", value)
        }
        injectCommand(command)
        _activeSnippetPrompt.value = null
    }

    fun cancelSnippetPrompt() {
        _activeSnippetPrompt.value = null
    }
}
