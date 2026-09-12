package com.sg.linuxgo
import android.graphics.Color

/**
 * Scroll region, erase-range, and CSI parameter helpers used by the emulator façade.
 * Extension receivers keep call sites on [TerminalEmulator] unchanged.
 */
internal fun TerminalEmulator.scrollUp(top: Int, bottom: Int) {
    // Scroll the topmost line in region to scrollback (only in normal screen mode)
    if (!isAlternateBuffer && top == 0) {
        val scrolledLine = activeLines[0]
        val copy = TerminalEmulator.ScreenLine(cols).apply { copyFrom(scrolledLine) }
        scrollback.add(copy)
        if (scrollback.size > maxScrollback) {
            scrollback.removeAt(0)
        }
    }

    // Shift lines up in the region
    for (i in top until bottom) {
        activeLines[i].copyFrom(activeLines[i + 1])
    }
    // Clear bottom line
    activeLines[bottom].clear(defaultFg, Color.TRANSPARENT)
}

internal fun TerminalEmulator.scrollDown(top: Int, bottom: Int) {
    // Shift lines down in the region
    for (i in bottom downTo top + 1) {
        activeLines[i].copyFrom(activeLines[i - 1])
    }
    // Clear top line
    activeLines[top].clear(defaultFg, Color.TRANSPARENT)
}

/** Fill one cell with the current erase attributes (xterm-style BCE). */
internal fun TerminalEmulator.eraseCell(line: TerminalEmulator.ScreenLine, x: Int) {
    line.chars[x] = TerminalEmulator.EMPTY
    line.fgColors[x] = currentFgColor
    line.bgColors[x] = currentBgColor
    line.effects[x] = TerminalCellStyle.NONE
}

internal fun TerminalEmulator.clearRange(sy: Int, sx: Int, ey: Int, ex: Int) {
    for (y in sy..ey) {
        val line = activeLines[y]
        val startX = if (y == sy) sx else 0
        val endX = if (y == ey) ex else cols - 1
        line.markDirty()
        for (x in startX..endX) {
            eraseCell(line, x)
        }
    }
}

internal fun TerminalEmulator.parseCsiParams(params: String): List<Int> {
    val cleaned = if (params.startsWith("?")) params.substring(1) else params
    if (cleaned.isEmpty()) return emptyList()
    return cleaned.split(";").map { it.toIntOrNull() ?: 0 }
}

internal fun TerminalEmulator.getCsiParam(parsed: List<Int>, index: Int, default: Int): Int {
    val v = parsed.getOrNull(index) ?: default
    return if (v == 0) default else v
}
