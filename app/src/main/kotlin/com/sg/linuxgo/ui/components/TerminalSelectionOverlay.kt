package com.sg.linuxgo.ui.components

import android.app.SearchManager
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.sg.linuxgo.TerminalCell
import com.sg.linuxgo.TerminalClipboard
import com.sg.linuxgo.TerminalSelection
import com.sg.linuxgo.TerminalSelectionOps
import com.sg.linuxgo.TerminalTextSearch
import kotlin.math.roundToInt

/**
 * Maps absolute terminal cells → viewport pixel coordinates for the current paint mode.
 */
data class TerminalViewportMapper(
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    /** Absolute line index of the first row drawn at y=0 of the terminal surface. */
    val firstVisibleLine: Int,
    /** Pixel scroll offset within the first visible line (LazyColumn only). */
    val firstLinePixelOffset: Float = 0f,
    val lineCount: Int,
    /** Horizontal inset matching LazyColumn content padding. */
    val originX: Float = 0f
) {
    fun cellTopLeft(cell: TerminalCell): Offset {
        val visualRow = cell.line - firstVisibleLine
        return Offset(
            x = originX + cell.col * cellWidthPx,
            y = visualRow * cellHeightPx - firstLinePixelOffset
        )
    }

    fun positionToCell(pos: Offset): TerminalCell {
        val line = firstVisibleLine +
            ((pos.y + firstLinePixelOffset) / cellHeightPx).toInt()
        val col = ((pos.x - originX) / cellWidthPx).toInt().coerceAtLeast(0)
        return TerminalCell(line.coerceAtLeast(0), col)
    }
}

/**
 * Draws selection highlight + start/end drop handles, and hosts the floating Android
 * ActionMode toolbar (Copy / Paste / Share / Search / Select all / Open).
 */
@Composable
fun TerminalSelectionLayer(
    selection: TerminalSelection,
    lines: List<CharSequence>,
    mapper: TerminalViewportMapper,
    highlightColor: Color,
    handleColor: Color,
    onSelectionChange: (TerminalSelection) -> Unit,
    onClearSelection: () -> Unit,
    onPasteRequested: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val context = LocalContext.current
    val selectedText = remember(selection, lines) {
        TerminalSelectionOps.extractText(lines, selection)
    }
    val selectionState = rememberUpdatedState(selection)
    val linesState = rememberUpdatedState(lines)
    val mapperState = rememberUpdatedState(mapper)
    val selectedTextState = rememberUpdatedState(selectedText)
    val onClearState = rememberUpdatedState(onClearSelection)
    val onPasteState = rememberUpdatedState(onPasteRequested)
    val onSelectionChangeState = rememberUpdatedState(onSelectionChange)

    var actionMode by remember { mutableStateOf<ActionMode?>(null) }

    fun contentRectFor(sel: TerminalSelection, map: TerminalViewportMapper): Rect {
        val start = map.cellTopLeft(sel.start)
        val end = map.cellTopLeft(sel.end)
        val top = minOf(start.y, end.y)
        val bottom = maxOf(start.y + map.cellHeightPx, end.y + map.cellHeightPx)
        val left = if (sel.start.line == sel.end.line) {
            minOf(start.x, end.x)
        } else {
            map.originX
        }
        val right = if (sel.start.line == sel.end.line) {
            maxOf(start.x, end.x)
        } else {
            map.originX + map.cellWidthPx * 40f
        }
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            right.roundToInt().coerceAtLeast(left.roundToInt() + 1),
            bottom.roundToInt().coerceAtLeast(top.roundToInt() + 1)
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            actionMode?.finish()
            actionMode = null
        }
    }

    LaunchedEffect(selection, selectedText, mapper.firstVisibleLine, mapper.firstLinePixelOffset) {
        if (selectedText.isEmpty()) {
            actionMode?.finish()
            actionMode = null
            return@LaunchedEffect
        }
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                if (onPasteState.value != null) {
                    menu.add(Menu.NONE, android.R.id.paste, 1, android.R.string.paste)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                menu.add(Menu.NONE, android.R.id.selectAll, 2, android.R.string.selectAll)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(Menu.NONE, MENU_SHARE, 3, "Share")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(Menu.NONE, MENU_SEARCH, 4, "Search")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                if (TerminalTextSearch.looksLikeUrl(selectedTextState.value)) {
                    menu.add(Menu.NONE, MENU_OPEN, 5, "Open")
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val text = selectedTextState.value
                when (item.itemId) {
                    android.R.id.copy -> {
                        TerminalClipboard.writeText(context, text)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        mode.finish()
                        onClearState.value()
                        return true
                    }
                    MENU_SHARE -> {
                        shareText(context, text)
                        mode.finish()
                        return true
                    }
                    MENU_SEARCH -> {
                        webSearch(context, text)
                        mode.finish()
                        return true
                    }
                    MENU_OPEN -> {
                        openUrl(context, TerminalTextSearch.normalizeUrl(text))
                        mode.finish()
                        onClearState.value()
                        return true
                    }
                    android.R.id.selectAll -> {
                        TerminalSelectionOps.selectAll(linesState.value)?.let {
                            onSelectionChangeState.value(it)
                        }
                        return true
                    }
                    android.R.id.paste -> {
                        onPasteState.value?.invoke()
                        mode.finish()
                        onClearState.value()
                        return true
                    }
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (actionMode === mode) {
                    actionMode = null
                }
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                outRect.set(contentRectFor(selectionState.value, mapperState.value))
            }
        }

        if (actionMode == null) {
            actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidateContentRect()
        }
    }

    val handleSize = 22.dp

    Box(modifier = modifier.fillMaxSize().zIndex(8f)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sel = selection
            val map = mapper
            val color = highlightColor
            val startLine = sel.start.line.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            val endLine = sel.end.line.coerceIn(0, lines.size)
            for (lineIdx in startLine..minOf(endLine, lines.lastIndex)) {
                val len = TerminalSelectionOps.contentLength(lines[lineIdx])
                val range = TerminalSelectionOps.highlightRange(sel, lineIdx, len) ?: continue
                val topLeft = map.cellTopLeft(TerminalCell(lineIdx, range.first))
                val width = (range.last - range.first + 1) * map.cellWidthPx
                if (topLeft.y + map.cellHeightPx < 0f || topLeft.y > size.height) continue
                drawRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(width, map.cellHeightPx)
                )
            }
        }

        SelectionHandle(
            attachment = mapper.cellTopLeft(selection.start).let {
                Offset(it.x, it.y + mapper.cellHeightPx)
            },
            isStart = true,
            color = handleColor,
            size = handleSize,
            onDragTo = { fingerPos ->
                val map = mapperState.value
                val current = selectionState.value
                val probe = fingerPos + Offset(0f, -map.cellHeightPx * 0.5f)
                val clamped = TerminalSelectionOps.snapToContent(
                    linesState.value,
                    map.positionToCell(probe)
                )
                val end = current.end
                onSelectionChangeState.value(
                    if (clamped <= end) {
                        TerminalSelection(anchor = clamped, focus = end)
                    } else {
                        TerminalSelection(anchor = end, focus = clamped)
                    }
                )
            }
        )

        SelectionHandle(
            attachment = mapper.cellTopLeft(selection.end).let {
                Offset(it.x, it.y + mapper.cellHeightPx)
            },
            isStart = false,
            color = handleColor,
            size = handleSize,
            onDragTo = { fingerPos ->
                val map = mapperState.value
                val current = selectionState.value
                val probe = fingerPos + Offset(0f, -map.cellHeightPx * 0.5f)
                val clamped = TerminalSelectionOps.snapToContent(
                    linesState.value,
                    map.positionToCell(probe)
                )
                val start = current.start
                onSelectionChangeState.value(
                    if (clamped >= start) {
                        TerminalSelection(anchor = start, focus = clamped)
                    } else {
                        TerminalSelection(anchor = clamped, focus = start)
                    }
                )
            }
        )
    }
}

fun selectionText(lines: List<AnnotatedString>, sel: TerminalSelection): String {
    return TerminalSelectionOps.extractText(lines.map { it.text }, sel)
}

@Composable
private fun SelectionHandle(
    attachment: Offset,
    isStart: Boolean,
    color: Color,
    size: Dp,
    onDragTo: (Offset) -> Unit
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val offsetX = (attachment.x - sizePx / 2f).roundToInt()
    val offsetY = attachment.y.roundToInt()
    val attachmentState = rememberUpdatedState(attachment)
    val onDragToState = rememberUpdatedState(onDragTo)
    var originAttachment by remember { mutableStateOf(Offset.Zero) }
    var totalDrag by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(size)
            .zIndex(9f)
            .pointerInput(isStart) {
                detectDragGestures(
                    onDragStart = {
                        originAttachment = attachmentState.value
                        totalDrag = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                        onDragToState.value(originAttachment + totalDrag)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val cx = this.size.width / 2f
            val cy = r
            val path = Path().apply {
                moveTo(cx, 0f)
                lineTo(cx - r * 0.35f, r * 0.55f)
                lineTo(cx + r * 0.35f, r * 0.55f)
                close()
            }
            drawPath(path, color = color, style = Fill)
            drawCircle(color = color, radius = r * 0.72f, center = Offset(cx, cy + r * 0.15f))
            if (isStart) {
                drawCircle(
                    color = color.copy(alpha = 0.35f),
                    radius = r * 0.25f,
                    center = Offset(cx - r * 0.15f, cy + r * 0.15f)
                )
            }
        }
    }
}

private const val MENU_SHARE = 0x1001
private const val MENU_SEARCH = 0x1002
private const val MENU_OPEN = 0x1003

private fun shareText(context: android.content.Context, text: String) {
    if (text.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(
            Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, "Nothing to share with", Toast.LENGTH_SHORT).show()
    }
}

private fun webSearch(context: android.content.Context, text: String) {
    if (text.isEmpty()) return
    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
        putExtra(SearchManager.QUERY, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(text))
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, "No search app available", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    if (url.isEmpty()) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, "No app to open this link", Toast.LENGTH_SHORT).show()
    }
}
