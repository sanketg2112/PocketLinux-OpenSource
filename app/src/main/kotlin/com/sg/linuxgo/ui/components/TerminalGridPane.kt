package com.sg.linuxgo.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sg.linuxgo.TerminalSelection
import com.sg.linuxgo.TerminalSelectionOps
import com.sg.linuxgo.computeTerminalPaintWindow
import com.sg.linuxgo.terminalGridHorizontalOrigin
import com.sg.linuxgo.terminalCursorHiddenByViewportShrink
import com.sg.linuxgo.terminalCursorOffscreen
import com.sg.linuxgo.terminalCursorRevealScrollIndex
import com.sg.linuxgo.terminalImeViewportGrew
import com.sg.linuxgo.terminalImeViewportShrunk
import com.sg.linuxgo.terminalShouldFollowOutput
import com.sg.linuxgo.terminalShouldRestickToBottom
import com.sg.linuxgo.terminalStickScrollIndex
import com.sg.linuxgo.terminalUserScrollUnsticks
import com.sg.linuxgo.terminalViewportIsAtBottom
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Terminal character grid, pinch zoom, mouse/scroll gestures, and text selection
 * (drop handles + floating Copy / Search / Share bar).
 */
@Composable
fun TerminalGridPane(
    terminalLines: List<AnnotatedString>,
    cursorX: Int,
    cursorLine: Int,
    cursorVisible: Boolean,
    isAlternateBuffer: Boolean,
    isMouseReportingEnabled: Boolean,
    renderGeneration: Int,
    cellMetrics: TerminalCellMetrics,
    canvasBackground: Color,
    canvasText: Color,
    canvasCursor: Color,
    terminalFontSize: Float,
    terminalInputView: View?,
    suppressIme: Boolean,
    gridListState: LazyListState,
    viewConfiguration: ViewConfiguration,
    capturesTouch: Boolean,
    onGridSizeChanged: (rows: Int, cols: Int, charWidthPx: Int, charHeightPx: Int) -> Unit,
    onMouseEvent: (col: Int, row: Int, button: Int, isRelease: Boolean, isMotion: Boolean) -> Unit,
    onScrollSteps: (steps: Int, col: Int, row: Int) -> Unit,
    onFontSizeSelected: (Float) -> Unit,
    onPaste: () -> Unit,
    /** Committed PTY rows (0 = use live layout). */
    screenRows: Int = 0,
    /** Committed PTY cols (0 = use live layout). */
    screenCols: Int = 0,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val view = LocalView.current
    var pinchScale by remember { mutableFloatStateOf(1f) }
    var isPinching by remember { mutableStateOf(false) }
    var terminalSelection by remember { mutableStateOf<TerminalSelection?>(null) }
    val linesState = rememberUpdatedState(terminalLines)
    val selectionState = rememberUpdatedState(terminalSelection)
    val fontSizeState = rememberUpdatedState(terminalFontSize)
    val onFontSizeState = rememberUpdatedState(onFontSizeSelected)
    val onMouseState = rememberUpdatedState(onMouseEvent)
    val onScrollState = rememberUpdatedState(onScrollSteps)
    val altState = rememberUpdatedState(isAlternateBuffer)
    val mouseActiveState = rememberUpdatedState(isMouseReportingEnabled)

    BackHandler(enabled = terminalSelection != null) {
        terminalSelection = null
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPaddingDp = 2.dp
        val sidePaddingPx = with(density) { horizontalPaddingDp.toPx() }
        val maxW = (constraints.maxWidth.toFloat() - sidePaddingPx * 2f).coerceAtLeast(0f)
        val maxH = constraints.maxHeight.toFloat()
        val cols = max(20, (maxW / cellMetrics.cellWidthPx).toInt())
        val rows = max(5, (maxH / cellMetrics.cellHeightPx).toInt())
        val committedRows = if (screenRows > 0) screenRows else rows
        val committedCols = if (screenCols > 0) screenCols else cols
        val charWidthDp = with(density) { cellMetrics.cellWidthPx.toDp() }
        val charHeightDp = with(density) { cellMetrics.cellHeightPx.toDp() }
        val paint = computeTerminalPaintWindow(
            lines = terminalLines,
            committedRows = committedRows,
            committedCols = committedCols,
            cursorX = cursorX,
            cursorLine = cursorLine,
            isAlternateBuffer = isAlternateBuffer,
            fittedRows = rows
        )
        // Never stretch TUI cells to the live viewport (IME open/close used to
        // squash htop/vim). 1:1 cells, clip overflow; SIGWINCH waits for settle.
        val visualCharW = cellMetrics.cellWidthPx * pinchScale
        val visualCharH = cellMetrics.cellHeightPx * pinchScale
        val gridOriginX = if (paint.tuiMode) {
            terminalGridHorizontalOrigin(
                viewportWidthPx = constraints.maxWidth.toFloat(),
                cols = committedCols,
                cellWidthPx = visualCharW
            )
        } else {
            sidePaddingPx
        }

        LaunchedEffect(
            rows,
            cols,
            cellMetrics.cellWidthPx,
            cellMetrics.cellHeightPx,
            isPinching
        ) {
            if (isPinching) return@LaunchedEffect
            onGridSizeChanged(
                rows,
                cols,
                cellMetrics.cellWidthPx.toInt().coerceAtLeast(1),
                cellMetrics.cellHeightPx.toInt().coerceAtLeast(1)
            )
        }

        val stickToBottomState = remember { mutableStateOf(true) }
        var stickToBottom by stickToBottomState
        var hasLeftBottom by remember { mutableStateOf(false) }
        val userScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput &&
                        terminalUserScrollUnsticks(available.y)
                    ) {
                        stickToBottomState.value = false
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (available.y != 0f) {
                        stickToBottomState.value = false
                    }
                    // Eat inertial fling so lift-off leaves the transcript put.
                    return available
                }
            }
        }
        val isAtBottom by remember {
            derivedStateOf {
                val info = gridListState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                    ?: return@derivedStateOf info.totalItemsCount == 0
                terminalViewportIsAtBottom(
                    totalItems = info.totalItemsCount,
                    lastVisibleIndex = last.index,
                    lastVisibleOffset = last.offset,
                    lastVisibleSize = last.size,
                    viewportEndOffset = info.viewportEndOffset
                )
            }
        }
        val userScrolling = gridListState.isScrollInProgress
        LaunchedEffect(isAtBottom, userScrolling) {
            if (!isAtBottom) hasLeftBottom = true
            if (terminalShouldRestickToBottom(isAtBottom, userScrolling, hasLeftBottom)) {
                stickToBottom = true
                hasLeftBottom = false
            }
        }
        var idleVisibleRows by remember { mutableStateOf(0) }
        var burstOriginRows by remember { mutableStateOf(0) }
        var geometryQuiet by remember { mutableStateOf(true) }
        val cursorLineLive = rememberUpdatedState(cursorLine)
        val terminalLinesLive = rememberUpdatedState(terminalLines)
        val userScrollingLive = rememberUpdatedState(userScrolling)
        val tuiLive = rememberUpdatedState(paint.tuiMode)
        val mouseLive = rememberUpdatedState(isMouseReportingEnabled)
        LaunchedEffect(rows) {
            if (idleVisibleRows == 0) {
                idleVisibleRows = rows
                return@LaunchedEffect
            }
            if (burstOriginRows == 0) burstOriginRows = idleVisibleRows
            geometryQuiet = false
            delay(280)
            val origin = burstOriginRows
            burstOriginRows = 0
            idleVisibleRows = rows
            val liveCursor = cursorLineLive.value
            val liveLines = terminalLinesLive.value
            if (!tuiLive.value && !mouseLive.value && !userScrollingLive.value) {
                if (terminalImeViewportGrew(origin, rows)) {
                    stickToBottom = false
                    hasLeftBottom = true
                } else if (
                    terminalImeViewportShrunk(origin, rows) &&
                    liveLines.isNotEmpty() &&
                    terminalCursorHiddenByViewportShrink(
                        cursorLine = liveCursor,
                        firstVisibleLine = gridListState.firstVisibleItemIndex,
                        oldVisibleRows = origin,
                        newVisibleRows = rows
                    )
                ) {
                    gridListState.scrollToItem(
                        terminalCursorRevealScrollIndex(
                            cursorLine = liveCursor,
                            lineCount = liveLines.size,
                            visibleRows = rows,
                            emptyLinesBelow = 1
                        )
                    )
                }
            }
            geometryQuiet = true
        }
        var prevCommittedRows by remember { mutableStateOf(committedRows) }
        LaunchedEffect(
            terminalLines.size,
            cursorLine,
            stickToBottom,
            userScrolling,
            committedRows,
            geometryQuiet,
            paint.tuiMode,
            isMouseReportingEnabled
        ) {
            val ptyRowsChanged = committedRows != prevCommittedRows
            prevCommittedRows = committedRows
            if (paint.tuiMode || isMouseReportingEnabled) return@LaunchedEffect
            val firstVisible = gridListState.firstVisibleItemIndex
            val cursorOffscreen = terminalCursorOffscreen(
                cursorLine = cursorLine,
                firstVisibleLine = firstVisible,
                visibleRows = rows
            )
            if (!terminalShouldFollowOutput(
                    stickToBottom = stickToBottom,
                    userScrollInProgress = userScrolling,
                    ptyRowsChanged = ptyRowsChanged,
                    geometryQuiet = geometryQuiet,
                    cursorOffscreen = cursorOffscreen
                )
            ) {
                return@LaunchedEffect
            }
            if (terminalLines.isEmpty()) return@LaunchedEffect
            gridListState.scrollToItem(
                terminalStickScrollIndex(
                    cursorLine = cursorLine,
                    lineCount = terminalLines.size,
                    visibleRows = rows
                )
            )
        }

        val charWState = rememberUpdatedState(visualCharW)
        val charHState = rememberUpdatedState(visualCharH)
        val originXState = rememberUpdatedState(gridOriginX)
        val forwardScrollState = rememberUpdatedState(
            isAlternateBuffer || isMouseReportingEnabled
        )
        val scrollbackLines = if (isAlternateBuffer) {
            0
        } else {
            (terminalLines.size - committedRows).coerceAtLeast(0)
        }
        val scrollbackState = rememberUpdatedState(scrollbackLines)
        val rowsState = rememberUpdatedState(rows)
        val colsState = rememberUpdatedState(cols)
        val tuiBaseLine = if (isAlternateBuffer && terminalLines.size > paint.screenRows) {
            terminalLines.size - paint.screenRows
        } else {
            0
        }
        val tuiBaseState = rememberUpdatedState(tuiBaseLine)

        fun hitAbsolute(posX: Float, posY: Float): Pair<Int, Int>? {
            val charW = charWState.value
            val charH = charHState.value
            val originX = originXState.value
            return if (altState.value) {
                TerminalSelectionOps.hitLineCol(
                    posX = posX,
                    posY = posY,
                    cellW = charW,
                    cellH = charH,
                    firstVisibleLine = tuiBaseState.value,
                    firstLinePixelOffset = 0f,
                    originX = originX,
                    lineCount = linesState.value.size
                )
            } else {
                TerminalSelectionOps.hitLineCol(
                    posX = posX,
                    posY = posY,
                    cellW = charW,
                    cellH = charH,
                    firstVisibleLine = gridListState.firstVisibleItemIndex,
                    firstLinePixelOffset = gridListState.firstVisibleItemScrollOffset.toFloat(),
                    originX = originX,
                    lineCount = linesState.value.size
                )
            }
        }

        val selecting = terminalSelection != null
        val hostScrollEnabled = !isAlternateBuffer && !capturesTouch && !isPinching && !selecting
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .clipToBounds()
                .then(
                    if (paint.tuiMode) {
                        Modifier
                    } else {
                        Modifier.graphicsLayer {
                            val s = pinchScale.coerceIn(0.2f, 4f)
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                    }
                )
        ) {
            val gestureModifier = Modifier
                .fillMaxSize()
                .pointerInput(
                    capturesTouch,
                    isMouseReportingEnabled,
                    isAlternateBuffer
                ) {
                        detectTerminalSurfaceGestures(
                            touchSlop = viewConfiguration.touchSlop,
                            cellHeightPx = { charHState.value },
                            fontSizeSp = { fontSizeState.value },
                            mouseReporting = { mouseActiveState.value },
                            forwardScroll = { forwardScrollState.value },
                            toCell = { pos ->
                                val charW = charWState.value
                                val charH = charHState.value
                                val rMax = rowsState.value
                                val cMax = colsState.value
                                if (charW <= 0f || charH <= 0f) {
                                    return@detectTerminalSurfaceGestures null
                                }
                                val absoluteY =
                                    gridListState.firstVisibleItemIndex * charH +
                                        gridListState.firstVisibleItemScrollOffset +
                                        pos.y
                                val lineIndex = (absoluteY / charH).toInt()
                                val row = if (altState.value) {
                                    lineIndex + 1
                                } else {
                                    (lineIndex - scrollbackState.value) + 1
                                }
                                if (row !in 1..rMax) return@detectTerminalSurfaceGestures null
                                val col = (((pos.x - originXState.value) / charW).toInt() + 1)
                                    .coerceIn(1, cMax)
                                col to row
                            },
                            onMouseEvent = { c, r, btn, rel, motion ->
                                onMouseState.value(c, r, btn, rel, motion)
                            },
                            onScrollSteps = { steps, c, r ->
                                onScrollState.value(steps, c, r)
                            },
                            onPinchScale = { scale ->
                                isPinching = true
                                pinchScale = scale
                            },
                            onPinchEnd = { finalSp ->
                                isPinching = false
                                pinchScale = 1f
                                onFontSizeState.value(finalSp)
                            },
                            onLongPressAt = { pos ->
                                val lines = linesState.value
                                val hit = hitAbsolute(pos.x, pos.y) ?: return@detectTerminalSurfaceGestures
                                val plain = lines.map { it.text }
                                val sel = TerminalSelectionOps.fromPointerHit(
                                    plain, hit.first, hit.second
                                ) ?: TerminalSelection.singleChar(hit.first, hit.second)
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                terminalSelection = sel
                            },
                            onSelectionDragAt = { pos ->
                                val current = selectionState.value ?: return@detectTerminalSurfaceGestures
                                val lines = linesState.value
                                val hit = hitAbsolute(pos.x, pos.y) ?: return@detectTerminalSurfaceGestures
                                terminalSelection = TerminalSelectionOps.dragFocus(
                                    lines.map { it.text },
                                    hit.first,
                                    hit.second,
                                    current
                                )
                            },
                            onTapAt = { pos ->
                                val lines = linesState.value
                                val hit = hitAbsolute(pos.x, pos.y)
                                terminalSelection = TerminalSelectionOps.tapUrlOrClear(
                                    lines.map { it.text },
                                    hit?.first,
                                    hit?.second
                                )
                            },
                            hasSelection = { selectionState.value != null }
                        )
                    }

            if (paint.tuiMode) {
                Box(
                    modifier = gestureModifier.clipToBounds(),
                    contentAlignment = Alignment.TopStart
                ) {
                    TerminalTuiGrid(
                        lines = paint.paintLines,
                        metrics = cellMetrics,
                        defaultColor = canvasText,
                        cursorX = paint.paintCursorX,
                        cursorY = paint.paintCursorY,
                        cursorVisible = cursorVisible,
                        cursorColor = canvasCursor,
                        modifier = Modifier.fillMaxSize(),
                        canvasBackground = canvasBackground,
                        frameSeq = renderGeneration.toLong(),
                        gridCols = committedCols.coerceAtLeast(1),
                        gridRows = paint.screenRows.coerceAtLeast(1),
                        extraScaleX = pinchScale,
                        extraScaleY = pinchScale,
                        fitToViewport = false,
                        originX = gridOriginX
                    )
                }
            } else {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                LazyColumn(
                    state = gridListState,
                    userScrollEnabled = hostScrollEnabled,
                    flingBehavior = TerminalNoFling,
                    verticalArrangement = Arrangement.Top,
                    contentPadding = PaddingValues(horizontal = horizontalPaddingDp),
                    modifier = gestureModifier.nestedScroll(userScrollConnection)
                ) {
                    items(
                        count = terminalLines.size,
                        key = { index -> index },
                        contentType = { "termLine" }
                    ) { index ->
                        val line = terminalLines[index]
                        val isCursorLine = index == cursorLine
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(charHeightDp)
                        ) {
                            TerminalGridLine(
                                text = line,
                                metrics = cellMetrics,
                                defaultColor = canvasText,
                                canvasBackground = canvasBackground,
                                modifier = Modifier.fillMaxWidth(),
                                frameSeq = renderGeneration.toLong()
                            )
                            if (isCursorLine && cursorVisible) {
                                Box(
                                    modifier = Modifier
                                        .size(width = charWidthDp, height = charHeightDp)
                                        .offset(x = charWidthDp * cursorX)
                                        .background(canvasCursor.copy(alpha = 0.8f))
                                )
                            }
                        }
                    }
                }
                }
            }

            terminalSelection?.let { sel ->
                val firstVisibleLine = if (isAlternateBuffer) tuiBaseLine else {
                    gridListState.firstVisibleItemIndex
                }
                val firstLinePixelOffset = if (isAlternateBuffer) {
                    0f
                } else {
                    gridListState.firstVisibleItemScrollOffset.toFloat()
                }
                val mapper = TerminalViewportMapper(
                    cellWidthPx = visualCharW,
                    cellHeightPx = visualCharH,
                    firstVisibleLine = firstVisibleLine,
                    firstLinePixelOffset = firstLinePixelOffset,
                    lineCount = terminalLines.size,
                    originX = gridOriginX
                )
                TerminalSelectionLayer(
                    selection = sel,
                    lines = terminalLines.map { it.text },
                    mapper = mapper,
                    highlightColor = canvasCursor.copy(alpha = 0.35f),
                    handleColor = canvasCursor,
                    onSelectionChange = { terminalSelection = it },
                    onClearSelection = { terminalSelection = null },
                    onPasteRequested = onPaste,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (terminalInputView != null) {
            AndroidView(
                factory = {
                    val parent = terminalInputView.parent as? android.view.ViewGroup
                    parent?.removeView(terminalInputView)
                    (terminalInputView as? android.widget.EditText)?.let { et ->
                        et.isFocusable = true
                        et.isFocusableInTouchMode = true
                        et.setCursorVisible(false)
                        et.isEnabled = true
                        et.isClickable = false
                        et.isLongClickable = false
                    }
                    terminalInputView
                },
                update = { v ->
                    v.isEnabled = true
                    v.visibility = android.view.View.VISIBLE
                    (v as? android.widget.EditText)?.let { et ->
                        et.setCursorVisible(false)
                        et.isClickable = false
                        et.isLongClickable = false
                        et.showSoftInputOnFocus = !suppressIme
                    }
                    if (suppressIme) {
                        v.isFocusable = false
                        v.isFocusableInTouchMode = false
                        v.clearFocus()
                    } else {
                        v.isFocusable = true
                        v.isFocusableInTouchMode = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .alpha(0f)
            )
        }
    }
}

/** Finger tracking only — lift-off must not keep the transcript moving. */
private object TerminalNoFling : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = 0f
}
