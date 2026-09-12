package com.sg.linuxgo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.R
import com.sg.linuxgo.ui.theme.Red
import com.sg.linuxgo.ui.theme.TextTerminal
import com.sg.linuxgo.ui.theme.Yellow
import com.sg.linuxgo.ui.theme.rememberTerminalFontFamily
import kotlinx.coroutines.flow.distinctUntilChanged

data class ChecklistItemState(
    val text: String,
    val color: Color
)

/** Near-bottom threshold (px) used to decide whether auto-scroll should stick. */
private const val AUTO_SCROLL_EDGE_PX = 48

@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    welcomeVisible: Boolean = false,
    stepDescriptionText: String = "",
    stepDescriptionVisible: Boolean = false,
    progress: Float = 0f,
    progressIndeterminate: Boolean = false,
    progressVisible: Boolean = false,
    progressStatusText: String = "",
    progressStatusVisible: Boolean = false,
    checklistVisible: Boolean = false,
    checklistItems: List<ChecklistItemState> = emptyList(),
    promptContent: @Composable (() -> Unit)? = null,
    metricsCardVisible: Boolean = false,
    installPath: String = "",
    storageStatus: String = "",
    onBackupClick: () -> Unit = {},
    onRestoreClick: () -> Unit = {},
    restoreSetupVisible: Boolean = false,
    onRestoreSetupClick: () -> Unit = {},
    logToggleText: String = "More Information ▼",
    onToggleLogsClick: () -> Unit = {},
    logCardVisible: Boolean = false,
    isLogFullscreen: Boolean = false,
    logText: String = "",
    onDownloadLogClick: () -> Unit = {},
    onFullscreenLogClick: () -> Unit = {},
    onCopyLogClick: () -> Unit = {}
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    // Stick to bottom by default; disable when the user scrolls up to read history.
    var stickToBottom by remember { mutableStateOf(true) }
    val monoFont = rememberTerminalFontFamily("jetbrains_mono")

    // Track whether the user is near the bottom (re-enable auto-scroll when they return).
    LaunchedEffect(verticalScroll) {
        snapshotFlow {
            val max = verticalScroll.maxValue
            max == 0 || verticalScroll.value >= max - AUTO_SCROLL_EDGE_PX
        }
            .distinctUntilChanged()
            .collect { atBottom ->
                stickToBottom = atBottom
            }
    }

    // Auto-scroll only when stuck to bottom — like a real terminal.
    LaunchedEffect(logText, stickToBottom) {
        if (stickToBottom) {
            // Wait a frame so maxValue reflects the new content height.
            verticalScroll.scrollTo(verticalScroll.maxValue.coerceAtLeast(0))
            // Second pass after layout settles (long curl lines can grow maxValue).
            verticalScroll.scrollTo(verticalScroll.maxValue.coerceAtLeast(0))
        }
    }

    val displayText = remember(logText) {
        parseStatusLog(logText)
    }

    Card(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Install logs",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    IconButton(
                        onClick = onDownloadLogClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = "Download Log",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onCopyLogClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = "Copy Log",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Terminal-style log: monospace, no wrap, 2D scroll (vertical + horizontal).
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(horizontalScroll)
                    ) {
                        Text(
                            text = displayText,
                            fontSize = 12.sp,
                            fontFamily = monoFont,
                            lineHeight = 16.sp,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            // Keep long curl/wget columns on one line; scroll sideways to read.
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
            }
        }
    }
}

/**
 * Strip ANSI CSI/OSC sequences and CR progress rewrites, then apply simple
 * terminal-style color coding (error red, warning yellow, default light gray).
 */
private fun parseStatusLog(text: String): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")

    val cleaned = stripAnsiAndControl(text)
    // Drop empty trailing noise; keep blank lines that separate sections.
    val lines = cleaned.split("\n")

    return buildAnnotatedString {
        for ((i, rawLine) in lines.withIndex()) {
            if (i > 0) append("\n")
            // Last segment of a \r-updated progress line wins (curl/wget in-place).
            val line = rawLine.substringAfterLast('\r')

            val color = when {
                isErrorLine(line) -> Red
                isWarningLine(line) -> Yellow
                isSuccessLine(line) -> Color(0xFF81C784) // soft green for ✓ success
                else -> TextTerminal // light gray like the container terminal
            }

            withStyle(SpanStyle(color = color)) {
                append(line)
            }
        }
    }
}

/** Remove ANSI escape sequences and other control noise that breaks layout. */
private fun stripAnsiAndControl(text: String): String {
    var s = text
    // CSI sequences: ESC [ ... letter  (colors, cursor, erase, etc.)
    s = s.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
    // OSC sequences: ESC ] ... BEL or ST
    s = s.replace(Regex("\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)"), "")
    // Other incomplete ESC sequences
    s = s.replace(Regex("\u001B."), "")
    // Backspace pairs (rare in package managers)
    s = s.replace(Regex(".\u0008"), "")
    // Strip remaining lone ESC / BEL / null
    s = s.replace("\u001B", "")
        .replace("\u0007", "")
        .replace("\u0000", "")
    return s
}

private fun isErrorLine(line: String): Boolean {
    val lower = line.lowercase()
    return lower.contains("error") ||
        lower.contains("fail") ||
        lower.contains("failure") ||
        lower.contains("fatal") ||
        lower.contains("exception") ||
        lower.contains("timed out") ||
        lower.contains("timeout") ||
        lower.contains("connection refused") ||
        lower.contains("killed") ||
        lower.contains("abort") ||
        line.contains("✗") ||
        line.trimStart().startsWith("!") ||
        // timestamped "! message"
        Regex("""^\[\d{2}:\d{2}:\d{2}\]\s*!""").containsMatchIn(line)
}

private fun isWarningLine(line: String): Boolean {
    val lower = line.lowercase()
    return lower.contains("warning") ||
        lower.contains("warn:") ||
        lower.contains("warn ") ||
        line.contains("⚠")
}

private fun isSuccessLine(line: String): Boolean {
    return line.contains("✓") ||
        line.lowercase().contains("complete") && line.lowercase().contains("ready")
}
