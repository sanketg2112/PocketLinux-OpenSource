package com.sg.linuxgo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.sg.linuxgo.ui.theme.StrokeLight
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.sg.linuxgo.SnippetEntity
import com.sg.linuxgo.TerminalTextSearch

internal data class SnippetCursorAnchor(val x: Dp, val y: Dp)

/** Long-press URL prompt: highlight stays via terminalSelection; chip offers Open. */
internal data class TerminalLinkPrompt(
    val url: String,
    val line: Int,
    val startCol: Int,
    val endCol: Int
)

/**
 * Map the live terminal cursor to viewport dp for anchoring the suggestion list.
 * Returns null when the cursor line is scrolled off-screen (LazyColumn).
 */
internal fun resolveSnippetCursorAnchor(
    tuiMode: Boolean,
    paintCursorX: Int,
    paintCursorY: Int,
    sessionCursorX: Int,
    sessionCursorY: Int,
    charWidthDp: Dp,
    charHeightDp: Dp,
    lazyListState: LazyListState,
    density: Density
): SnippetCursorAnchor? {
    if (tuiMode) {
        if (paintCursorY < 0) return null
        return SnippetCursorAnchor(
            x = charWidthDp * paintCursorX.coerceAtLeast(0),
            y = charHeightDp * paintCursorY.coerceAtLeast(0)
        )
    }
    val layoutInfo = lazyListState.layoutInfo
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == sessionCursorY }
        ?: return null
    val y = with(density) { item.offset.toDp() }
    return SnippetCursorAnchor(
        x = charWidthDp * sessionCursorX.coerceAtLeast(0),
        y = y
    )
}

/**
 * Map an absolute terminal cell (scrollback line + column) to viewport dp.
 * Used for anchoring the URL Open chip next to a long-pressed link.
 */
internal fun resolveTerminalCellAnchor(
    tuiMode: Boolean,
    absoluteLine: Int,
    col: Int,
    screenRows: Int,
    totalLines: Int,
    isAlternateBuffer: Boolean,
    charWidthDp: Dp,
    charHeightDp: Dp,
    lazyListState: LazyListState,
    density: Density
): SnippetCursorAnchor? {
    if (tuiMode) {
        val firstVisible = if (isAlternateBuffer || totalLines <= screenRows) {
            0
        } else {
            totalLines - screenRows
        }
        val visualRow = absoluteLine - firstVisible
        if (visualRow < 0 || visualRow >= screenRows) return null
        return SnippetCursorAnchor(
            x = charWidthDp * col.coerceAtLeast(0),
            y = charHeightDp * visualRow.coerceAtLeast(0)
        )
    }
    val layoutInfo = lazyListState.layoutInfo
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == absoluteLine }
        ?: return null
    val y = with(density) { item.offset.toDp() }
    return SnippetCursorAnchor(
        x = charWidthDp * col.coerceAtLeast(0),
        y = y
    )
}

/**
 * Snippet-style chip under a long-pressed URL — user confirms before leaving the app.
 */
@Composable
internal fun TerminalUrlOpenSuggestion(
    url: String,
    accent: Color,
    textColor: Color,
    surface: Color,
    anchorX: Dp,
    anchorY: Dp,
    lineHeight: Dp,
    viewportWidth: Dp,
    viewportHeight: Dp,
    onOpen: () -> Unit
) {
    val density = LocalDensity.current
    val rowH = (lineHeight.value * 1.05f).coerceIn(18f, 28f).dp
    val panelPadV = 3.dp
    val panelH = rowH + panelPadV * 2
    val edgePad = 4.dp
    val maxPanelW = (viewportWidth - edgePad * 2).coerceAtLeast(80.dp)

    val belowY = anchorY + lineHeight + 1.dp
    val y = if (belowY + panelH <= viewportHeight - 2.dp) {
        belowY
    } else {
        (anchorY - panelH - 1.dp).coerceAtLeast(0.dp)
    }

    var panelWidthPx by remember(url) { mutableStateOf(0) }
    val panelWidthDp = with(density) { panelWidthPx.toDp() }
    val x = when {
        panelWidthPx <= 0 -> anchorX.coerceIn(edgePad, (viewportWidth - edgePad).coerceAtLeast(edgePad))
        anchorX + panelWidthDp <= viewportWidth - edgePad -> anchorX.coerceAtLeast(edgePad)
        else -> (viewportWidth - edgePad - panelWidthDp).coerceAtLeast(edgePad)
    }

    val hostLabel = remember(url) {
        try {
            val host = Uri.parse(TerminalTextSearch.normalizeUrl(url)).host
            if (!host.isNullOrBlank()) host else url
        } catch (_: Exception) {
            url
        }
    }

    Column(
        modifier = Modifier
            .offset(x = x, y = y)
            .zIndex(10f)
            .widthIn(max = maxPanelW)
            .wrapContentWidth(align = Alignment.Start)
            .onSizeChanged { panelWidthPx = it.width }
            .clip(RoundedCornerShape(4.dp))
            .background(surface.copy(alpha = 0.88f))
            .border(0.5.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(vertical = panelPadV),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth(align = Alignment.Start)
                .heightIn(min = rowH)
                .clickable(onClick = onOpen)
                .background(accent.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Open",
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1
            )
            Text(
                text = hostLabel,
                color = textColor.copy(alpha = 0.78f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxPanelW - 64.dp)
            )
        }
    }
}

/**
 * Subtle ghost list under the terminal cursor — one suggestion per row.
 * Navigate with ↑/↓; accept via tap or Tab (physical keyboard / extra keys).
 *
 * Width hugs content (up to nearly the full viewport) and shifts left when the
 * cursor is near the right edge so long commands are not clipped at small fonts.
 */
@Composable
internal fun SnippetCursorSuggestions(
    suggestions: List<SnippetEntity>,
    selectedIndex: Int,
    accent: Color,
    textColor: Color,
    surface: Color,
    anchorX: Dp,
    anchorY: Dp,
    lineHeight: Dp,
    viewportWidth: Dp,
    viewportHeight: Dp,
    onSelect: (SnippetEntity) -> Unit
) {
    val density = LocalDensity.current
    val rowH = (lineHeight.value * 0.95f).coerceIn(14f, 22f).dp
    val panelPadV = 3.dp
    val panelH = rowH * suggestions.size + panelPadV * 2
    // Use almost the full terminal width — fixed 240.dp was clipping long macros
    // especially when the cursor sat mid/right at small font sizes.
    val edgePad = 4.dp
    val maxPanelW = (viewportWidth - edgePad * 2).coerceAtLeast(80.dp)

    // Prefer just below the cursor line; flip above when the viewport runs out of room.
    val belowY = anchorY + lineHeight + 1.dp
    val y = if (belowY + panelH <= viewportHeight - 2.dp) {
        belowY
    } else {
        (anchorY - panelH - 1.dp).coerceAtLeast(0.dp)
    }

    // After layout, slide left if the measured panel would overflow the right edge.
    var panelWidthPx by remember(suggestions) { mutableStateOf(0) }
    val panelWidthDp = with(density) { panelWidthPx.toDp() }
    val x = when {
        panelWidthPx <= 0 -> anchorX.coerceIn(edgePad, (viewportWidth - edgePad).coerceAtLeast(edgePad))
        anchorX + panelWidthDp <= viewportWidth - edgePad -> anchorX.coerceAtLeast(edgePad)
        else -> (viewportWidth - edgePad - panelWidthDp).coerceAtLeast(edgePad)
    }

    // Tab accepts the highlighted match (physical keyboard / extra-keys).
    val acceptHint = "⇥"
    val safeSelected = selectedIndex.coerceIn(0, suggestions.lastIndex.coerceAtLeast(0))

    Column(
        modifier = Modifier
            .offset(x = x, y = y)
            .zIndex(4f)
            .widthIn(max = maxPanelW)
            .wrapContentWidth(align = Alignment.Start)
            .onSizeChanged { panelWidthPx = it.width }
            .clip(RoundedCornerShape(4.dp))
            .background(surface.copy(alpha = 0.78f))
            .border(0.5.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
            .padding(vertical = panelPadV),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        suggestions.forEachIndexed { index, snippet ->
            val isSelected = index == safeSelected
            // One line: command is what runs; include title when it adds meaning.
            val label = when {
                snippet.title.isBlank() -> snippet.command
                snippet.title.equals(snippet.command, ignoreCase = true) -> snippet.command
                else -> "${snippet.title}  ·  ${snippet.command}"
            }
            Row(
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.Start)
                    .heightIn(min = rowH)
                    .clickable { onSelect(snippet) }
                    .background(
                        if (isSelected) accent.copy(alpha = 0.14f) else Color.Transparent
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (isSelected) acceptHint else " ",
                    color = accent.copy(alpha = if (isSelected) 0.7f else 0f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = label,
                    color = if (isSelected) {
                        textColor.copy(alpha = 0.92f)
                    } else {
                        textColor.copy(alpha = 0.48f)
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    // Cap text to remaining panel budget so ellipsis works only as last resort.
                    modifier = Modifier.widthIn(max = maxPanelW - 28.dp)
                )
            }
        }
    }
}

@Composable
internal fun MacroListRow(
    title: String,
    subtitle: String?,
    badge: String,
    accent: Color,
    meta: String?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    onSaveAsMacro: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(StrokeLight.copy(alpha = 0.25f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = if (badge == "Macro" && subtitle == null) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = badge,
                    color = accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = if (badge == "Macro") FontFamily.Monospace else FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (meta != null) {
                Text(
                    text = meta,
                    color = accent.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onSaveAsMacro != null) {
            TextButton(onClick = onSaveAsMacro) {
                Text("Save", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFE06C75),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Open a detected terminal URL in the default browser. */
internal fun openTerminalUrl(context: android.content.Context, url: String) {
    val normalized = TerminalTextSearch.normalizeUrl(url)
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Toast.makeText(context, "Opening link…", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
    }
}
