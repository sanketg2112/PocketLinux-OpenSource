package com.sg.linuxgo
/**
 * CSI sequence dispatch (cursor, erase, scroll, DA/DSR, SGR, modes).
 * State lives on [TerminalEmulator]; this file only holds the handler bodies.
 */
internal fun TerminalEmulator.handleCsiSequence(cmd: Char, params: String) {
    val parsed = parseCsiParams(params)
    when (cmd) {
        'A' -> { // Cursor Up
            wrapPending = false
            val count = getCsiParam(parsed, 0, 1)
            cursorY = Math.max(topMargin, cursorY - count)
        }
        'B' -> { // Cursor Down
            wrapPending = false
            val count = getCsiParam(parsed, 0, 1)
            cursorY = Math.min(bottomMargin, cursorY + count)
        }
        'C' -> { // Cursor Forward
            wrapPending = false
            val count = getCsiParam(parsed, 0, 1)
            cursorX = Math.min(cols - 1, cursorX + count)
        }
        'D' -> { // Cursor Backward
            val count = getCsiParam(parsed, 0, 1)
            if (wrapPending && count > 0) {
                // First step cancels wrap-pending (cursor already on last cell).
                wrapPending = false
                cursorX = (cols - 1).coerceAtLeast(0)
                if (count > 1) cursorX = Math.max(0, cursorX - (count - 1))
            } else {
                wrapPending = false
                cursorX = Math.max(0, cursorX - count)
            }
        }
        'H', 'f' -> { // Cursor Position
            wrapPending = false
            val r = getCsiParam(parsed, 0, 1)
            val c = getCsiParam(parsed, 1, 1)
            if (originMode) {
                cursorY = (topMargin + r - 1).coerceIn(topMargin, bottomMargin)
                cursorX = (c - 1).coerceIn(0, cols - 1)
            } else {
                cursorY = (r - 1).coerceIn(0, rows - 1)
                cursorX = (c - 1).coerceIn(0, cols - 1)
            }
        }
        'J' -> { // Erase in Display
            val mode = parsed.getOrNull(0) ?: 0
            when (mode) {
                0 -> { // Cursor to end of screen
                    clearRange(cursorY, cursorX, rows - 1, cols - 1)
                }
                1 -> { // Start of screen to cursor
                    clearRange(0, 0, cursorY, cursorX)
                }
                2 -> { // Clear entire screen
                    clearRange(0, 0, rows - 1, cols - 1)
                }
                3 -> { // Clear scrollback
                    scrollback.clear()
                }
            }
        }
        'K' -> { // Erase in Line
            val mode = parsed.getOrNull(0) ?: 0
            val line = activeLines[cursorY]
            when (mode) {
                0 -> { // Cursor to end of line
                    line.markDirty()
                    for (i in cursorX until cols) eraseCell(line, i)
                }
                1 -> { // Start of line to cursor
                    line.markDirty()
                    for (i in 0..cursorX) eraseCell(line, i)
                }
                2 -> { // Clear entire line (BCE: current SGR)
                    line.clear(currentFgColor, currentBgColor)
                }
            }
        }
        'L' -> { // Insert line
            val count = getCsiParam(parsed, 0, 1)
            for (c in 0 until count) {
                // Shift lines down within scroll region
                for (i in bottomMargin downTo cursorY + 1) {
                    activeLines[i].copyFrom(activeLines[i - 1])
                }
                activeLines[cursorY].clear(currentFgColor, currentBgColor)
            }
        }
        'M' -> { // Delete line
            val count = getCsiParam(parsed, 0, 1)
            for (c in 0 until count) {
                // Shift lines up within scroll region
                for (i in cursorY until bottomMargin) {
                    activeLines[i].copyFrom(activeLines[i + 1])
                }
                activeLines[bottomMargin].clear(currentFgColor, currentBgColor)
            }
        }
        'P' -> { // Delete character
            val count = getCsiParam(parsed, 0, 1).coerceAtMost(cols - cursorX).coerceAtLeast(0)
            val line = activeLines[cursorY]
            if (cursorX < cols && count > 0) {
                line.markDirty()
                val limit = cols - count
                for (i in cursorX until limit) {
                    line.chars[i] = line.chars[i + count]
                    line.fgColors[i] = line.fgColors[i + count]
                    line.bgColors[i] = line.bgColors[i + count]
                    line.effects[i] = line.effects[i + count]
                }
                for (i in limit until cols) eraseCell(line, i)
            }
        }
        'd' -> { // Vertical Position Absolute
            wrapPending = false
            val row = getCsiParam(parsed, 0, 1)
            cursorY = if (originMode) {
                (topMargin + row - 1).coerceIn(topMargin, bottomMargin)
            } else {
                (row - 1).coerceIn(0, rows - 1)
            }
        }
        'G' -> { // Cursor Horizontal Absolute
            wrapPending = false
            val col = getCsiParam(parsed, 0, 1)
            cursorX = (col - 1).coerceIn(0, cols - 1)
        }
        'E' -> { // Cursor Next Line
            wrapPending = false
            val count = getCsiParam(parsed, 0, 1)
            cursorX = 0
            cursorY = Math.min(bottomMargin, cursorY + count)
        }
        'F' -> { // Cursor Previous Line
            wrapPending = false
            val count = getCsiParam(parsed, 0, 1)
            cursorX = 0
            cursorY = Math.max(topMargin, cursorY - count)
        }
        'X' -> { // Erase Characters
            val count = getCsiParam(parsed, 0, 1)
            val line = activeLines[cursorY]
            val end = Math.min(cursorX + count, cols)
            line.markDirty()
            for (i in cursorX until end) eraseCell(line, i)
        }
        '@' -> { // Insert Characters
            val count = getCsiParam(parsed, 0, 1).coerceAtMost(cols - cursorX).coerceAtLeast(0)
            val line = activeLines[cursorY]
            if (cursorX < cols && count > 0) {
                line.markDirty()
                for (i in (cols - 1) downTo cursorX + count) {
                    line.chars[i] = line.chars[i - count]
                    line.fgColors[i] = line.fgColors[i - count]
                    line.bgColors[i] = line.bgColors[i - count]
                    line.effects[i] = line.effects[i - count]
                }
                for (i in cursorX until Math.min(cursorX + count, cols)) {
                    eraseCell(line, i)
                }
            }
        }
        's' -> saveCursor() // SCOSC
        'u' -> restoreCursor() // SCORC
        'S' -> { // Scroll Up
            val count = getCsiParam(parsed, 0, 1)
            for (i in 0 until count) {
                scrollUp(topMargin, bottomMargin)
            }
        }
        'T' -> { // Scroll Down
            val count = getCsiParam(parsed, 0, 1)
            for (i in 0 until count) {
                scrollDown(topMargin, bottomMargin)
            }
        }
        'n' -> { // Device Status Report
            val mode = parsed.getOrNull(0) ?: 0
            if (mode == 6) {
                // Cursor Position Report
                onResponse?.invoke("\u001B[${cursorY + 1};${cursorX + 1}R")
            } else if (mode == 5) {
                // OK status
                onResponse?.invoke("\u001B[0n")
            }
        }
        'c' -> { // Device Attributes
            val mode = parsed.getOrNull(0) ?: 0
            if (mode == 0 || params.isEmpty()) {
                // Primary DA - report as VT220 with 256 colors
                onResponse?.invoke("\u001B[?62;22;2c")
            }
        }
        'r' -> { // Set Scroll Margins
            val top = getCsiParam(parsed, 0, 1)
            val bottom = getCsiParam(parsed, 1, rows)
            topMargin = (top - 1).coerceIn(0, rows - 1)
            bottomMargin = (bottom - 1).coerceIn(topMargin, rows - 1)
        }
        'm' -> { // Graphics Renditions (colors)
            handleGraphicsRendition(parsed)
        }
        'h' -> { // Mode Set
            if (params.startsWith("?")) {
                handleModeSet(params.substring(1))
            } else {
                handleAnsiModeSet(params)
            }
        }
        'l' -> { // Mode Reset
            if (params.startsWith("?")) {
                handleModeReset(params.substring(1))
            } else {
                handleAnsiModeReset(params)
            }
        }
        'q' -> { // DECSCUSR — cursor shape (CSI Ps SP q; we also accept CSI Ps q)
            val shape = parsed.getOrNull(0) ?: 0
            cursorShape = when (shape) {
                3, 4 -> TerminalCursorShape.UNDERLINE
                5, 6 -> TerminalCursorShape.BAR
                else -> TerminalCursorShape.BLOCK
            }
        }
    }
}

internal fun TerminalEmulator.handleGraphicsRendition(params: List<Int>) {
    if (params.isEmpty()) {
        currentFgColor = defaultFg
        currentBgColor = android.graphics.Color.TRANSPARENT
        currentEffects = TerminalCellStyle.NONE
        return
    }
    val colorParams = mutableListOf<Int>()
    var effects = currentEffects
    var idx = 0
    while (idx < params.size) {
        val p = params[idx]
        when (p) {
            0 -> {
                effects = TerminalCellStyle.NONE
                colorParams.add(0)
                idx++
            }
            1, 2, 3, 4, 5, 6, 7, 8, 9, 21, 22, 23, 24, 25, 27, 28, 29 -> {
                val next = TerminalCellStyle.applyAttribute(effects, p)
                if (next >= 0) effects = next
                idx++
            }
            38, 48 -> {
                colorParams.add(p)
                var look = idx + 1
                if (look < params.size && params[look] == 5 && look + 1 < params.size) {
                    colorParams.add(params[look])
                    colorParams.add(params[look + 1])
                    idx = look + 2
                } else if (look < params.size && params[look] == 2 && look + 3 < params.size) {
                    colorParams.add(params[look])
                    colorParams.add(params[look + 1])
                    colorParams.add(params[look + 2])
                    colorParams.add(params[look + 3])
                    idx = look + 4
                } else {
                    idx++
                }
            }
            else -> {
                colorParams.add(p)
                idx++
            }
        }
    }
    currentEffects = effects
    if (colorParams.isNotEmpty()) {
        val (fg, bg) = TerminalPalette.applySgr(colorParams, currentFgColor, currentBgColor, colorScheme)
        currentFgColor = fg
        currentBgColor = bg
    }
}
