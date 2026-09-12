package com.sg.linuxgo

/**
 * Cell-grid text selection for the terminal surface.
 *
 * Coordinates are absolute line indices into the full line list (scrollback + screen)
 * and 0-based character columns. The range is half-open: [start, end) in reading order.
 */
data class TerminalCell(
    val line: Int,
    val col: Int
) : Comparable<TerminalCell> {
    override fun compareTo(other: TerminalCell): Int {
        val byLine = line.compareTo(other.line)
        return if (byLine != 0) byLine else col.compareTo(other.col)
    }
}

data class TerminalSelection(
    /** Where the selection gesture began (fixed while extending with the free handle). */
    val anchor: TerminalCell,
    /** Active end of the selection (moves while dragging after long-press). */
    val focus: TerminalCell
) {
    val start: TerminalCell get() = minOf(anchor, focus)
    val end: TerminalCell get() = maxOf(anchor, focus)

    fun isEmpty(): Boolean = start >= end

    fun withFocus(cell: TerminalCell): TerminalSelection = copy(focus = cell)

    companion object {
        fun wordAt(line: Int, tokenStart: Int, tokenEnd: Int): TerminalSelection {
            val start = TerminalCell(line, tokenStart.coerceAtLeast(0))
            val end = TerminalCell(line, tokenEnd.coerceAtLeast(tokenStart))
            return TerminalSelection(anchor = start, focus = end)
        }

        fun singleChar(line: Int, col: Int): TerminalSelection {
            val c = col.coerceAtLeast(0)
            return TerminalSelection(
                anchor = TerminalCell(line, c),
                focus = TerminalCell(line, c + 1)
            )
        }
    }
}

object TerminalSelectionOps {

    /**
     * Extract selected plain text from [lines] (one string per row).
     * Multi-line ranges join with `\n`.
     */
    fun extractText(lines: List<CharSequence>, selection: TerminalSelection): String {
        if (lines.isEmpty() || selection.isEmpty()) return ""
        val start = selection.start
        val end = selection.end
        if (start.line !in lines.indices) return ""

        if (start.line == end.line) {
            val text = lines[start.line]
            val a = start.col.coerceIn(0, text.length)
            val b = end.col.coerceIn(0, text.length)
            if (a >= b) return ""
            return text.subSequence(a, b).toString()
        }

        val sb = StringBuilder()
        val first = lines[start.line]
        val firstFrom = start.col.coerceIn(0, first.length)
        sb.append(first.subSequence(firstFrom, first.length))

        val lastLineIndex = if (end.col == 0 && end.line > start.line) {
            end.line - 1
        } else {
            end.line
        }.coerceIn(0, lines.lastIndex)

        for (i in (start.line + 1) until lastLineIndex) {
            sb.append('\n')
            sb.append(lines[i])
        }

        if (lastLineIndex > start.line) {
            sb.append('\n')
            val last = lines[lastLineIndex]
            val lastTo = if (lastLineIndex == end.line) {
                end.col.coerceIn(0, last.length)
            } else {
                last.length
            }
            sb.append(last.subSequence(0, lastTo))
        }
        return sb.toString()
    }

    /**
     * Visible content length of a terminal row: trailing spaces (empty cells padded
     * to the PTY width) are not selectable.
     */
    fun contentLength(line: CharSequence): Int {
        var end = line.length
        while (end > 0 && line[end - 1].isWhitespace()) end--
        return end
    }

    fun clampCell(lines: List<CharSequence>, cell: TerminalCell): TerminalCell {
        if (lines.isEmpty()) return TerminalCell(0, 0)
        val line = cell.line.coerceIn(0, lines.lastIndex)
        val maxCol = contentLength(lines[line])
        val col = cell.col.coerceIn(0, maxCol)
        return TerminalCell(line, col)
    }

    /**
     * Snap a free-floating pointer cell onto the nearest content-bearing position.
     * Empty (all-space) rows collapse toward a neighbor with glyphs.
     */
    fun snapToContent(lines: List<CharSequence>, cell: TerminalCell): TerminalCell {
        if (lines.isEmpty()) return TerminalCell(0, 0)
        var line = cell.line.coerceIn(0, lines.lastIndex)
        var maxCol = contentLength(lines[line])
        if (maxCol == 0 && lines.size > 1) {
            var up = line - 1
            var down = line + 1
            while (up >= 0 || down <= lines.lastIndex) {
                if (up >= 0) {
                    val len = contentLength(lines[up])
                    if (len > 0) {
                        line = up
                        maxCol = len
                        break
                    }
                    up--
                }
                if (down <= lines.lastIndex) {
                    val len = contentLength(lines[down])
                    if (len > 0) {
                        line = down
                        maxCol = len
                        break
                    }
                    down++
                }
            }
        }
        val col = cell.col.coerceIn(0, maxCol)
        return TerminalCell(line, col)
    }

    fun selectAll(lines: List<CharSequence>): TerminalSelection? {
        if (lines.isEmpty()) return null
        var last = lines.lastIndex
        while (last > 0 && contentLength(lines[last]) == 0) last--
        val endCol = contentLength(lines[last])
        if (last == 0 && endCol == 0) return null
        return TerminalSelection(
            anchor = TerminalCell(0, 0),
            focus = TerminalCell(last, endCol)
        )
    }

    fun contains(selection: TerminalSelection, line: Int, col: Int): Boolean {
        if (selection.isEmpty()) return false
        val cell = TerminalCell(line, col)
        return cell >= selection.start && cell < selection.end
    }

    /**
     * Highlight column range for a single [lineIndex] as half-open [startCol, endCol).
     * Returns null when the line is outside the selection.
     */
    fun highlightRange(
        selection: TerminalSelection,
        lineIndex: Int,
        lineLength: Int
    ): IntRange? {
        if (selection.isEmpty()) return null
        val start = selection.start
        val end = selection.end
        if (lineIndex < start.line || lineIndex > end.line) return null

        val from = when {
            lineIndex == start.line -> start.col
            else -> 0
        }.coerceIn(0, lineLength)

        val to = when {
            lineIndex == end.line -> end.col
            else -> lineLength
        }.coerceIn(0, lineLength)

        if (from >= to) return null
        return from until to
    }

    /**
     * Build selection from a long-press hit: prefer a full URL span, then the whitespace
     * token under the finger, otherwise a single character (or empty → null).
     */
    fun selectionFromHit(lineIndex: Int, hit: TerminalTextSearch.CellHit): TerminalSelection? {
        if (hit.url != null && hit.urlEnd > hit.urlStart) {
            return TerminalSelection.wordAt(lineIndex, hit.urlStart, hit.urlEnd)
        }
        if (hit.token.isNotBlank() && hit.tokenEnd > hit.tokenStart) {
            return TerminalSelection.wordAt(lineIndex, hit.tokenStart, hit.tokenEnd)
        }
        val line = hit.lineText
        if (line.isEmpty()) return null
        val col = hit.column.coerceIn(0, (line.length - 1).coerceAtLeast(0))
        if (line.getOrNull(col)?.isWhitespace() == true) return null
        return TerminalSelection.singleChar(lineIndex, col)
    }

    fun fromPointerHit(lines: List<CharSequence>, lineIndex: Int, col: Int): TerminalSelection? {
        if (lineIndex !in lines.indices) return null
        val hit = TerminalTextSearch.hitTestLine(lines[lineIndex].toString(), col)
        if (hit.lineText.isBlank() && hit.token.isBlank() && hit.url == null) return null
        val sel = selectionFromHit(lineIndex, hit) ?: TerminalSelection.singleChar(lineIndex, col)
        return TerminalSelection(
            anchor = clampCell(lines, sel.anchor),
            focus = clampCell(lines, sel.focus)
        )
    }

    fun dragFocus(
        lines: List<CharSequence>,
        lineIndex: Int,
        col: Int,
        current: TerminalSelection
    ): TerminalSelection {
        val cell = snapToContent(lines, TerminalCell(lineIndex, col))
        return current.withFocus(cell)
    }

    /** URL under the tap stays selected; anything else clears (returns null). */
    fun tapUrlOrClear(lines: List<CharSequence>, lineIndex: Int?, col: Int?): TerminalSelection? {
        if (lineIndex == null || col == null || lineIndex !in lines.indices) return null
        val hit = TerminalTextSearch.hitTestLine(lines[lineIndex].toString(), col)
        if (hit.url != null && hit.urlEnd > hit.urlStart) {
            val sel = selectionFromHit(lineIndex, hit)
                ?: TerminalSelection.wordAt(lineIndex, hit.urlStart, hit.urlEnd)
            return TerminalSelection(
                anchor = clampCell(lines, sel.anchor),
                focus = clampCell(lines, sel.focus)
            )
        }
        return null
    }

    /**
     * Map a surface position to absolute (line, 0-based col), or null if outside the grid.
     */
    fun hitLineCol(
        posX: Float,
        posY: Float,
        cellW: Float,
        cellH: Float,
        firstVisibleLine: Int,
        firstLinePixelOffset: Float,
        originX: Float,
        lineCount: Int
    ): Pair<Int, Int>? {
        if (cellW <= 0f || cellH <= 0f || lineCount <= 0) return null
        val col = ((posX - originX) / cellW).toInt().coerceAtLeast(0)
        val line = firstVisibleLine + ((posY + firstLinePixelOffset) / cellH).toInt()
        if (line !in 0 until lineCount) return null
        return line to col
    }
}
