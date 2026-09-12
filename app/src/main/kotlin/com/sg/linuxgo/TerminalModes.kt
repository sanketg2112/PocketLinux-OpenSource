package com.sg.linuxgo
import android.graphics.Color

/**
 * DEC private mode set/reset (CSI ? Pm h / l) used by the emulator façade.
 */
internal fun TerminalEmulator.handleModeSet(modesParam: String) {
    val previousMouseEnabled = isMouseReportingEnabled
    modesParam.split(";").mapNotNull { it.toIntOrNull() }.forEach { mode ->
        when (mode) {
            1 -> applicationCursorKeys = true
            5 -> reverseVideo = true
            6 -> originMode = true
            7 -> autowrap = true
            25 -> isCursorVisible = true
            47, 1047 -> {
                restoreBaseColorScheme()
                isAlternateBuffer = true
                activeLines = alternateLines
            }
            1048 -> saveAltScreenCursor()
            1049 -> {
                saveAltScreenCursor()
                restoreBaseColorScheme()
                isAlternateBuffer = true
                activeLines = alternateLines
                for (line in alternateLines) line.clear(defaultFg, Color.TRANSPARENT)
                cursorX = 0
                cursorY = 0
                wrapPending = false
            }
            9, 1000 -> mouseNormalMode = true
            1002 -> mouseButtonEventMode = true
            1003 -> mouseAnyEventMode = true
            1006 -> mouseSgrMode = true
            1007 -> alternateScrollMode = true
            1015 -> mouseUrxvtMode = true
            2004 -> bracketedPaste = true
        }
    }
    if (previousMouseEnabled != isMouseReportingEnabled) {
        onMouseModeChanged?.invoke(isMouseReportingEnabled)
    }
}

internal fun TerminalEmulator.handleModeReset(modesParam: String) {
    val previousMouseEnabled = isMouseReportingEnabled
    modesParam.split(";").mapNotNull { it.toIntOrNull() }.forEach { mode ->
        when (mode) {
            1 -> applicationCursorKeys = false
            5 -> reverseVideo = false
            6 -> originMode = false
            7 -> autowrap = false
            25 -> isCursorVisible = false
            47, 1047 -> {
                isAlternateBuffer = false
                activeLines = primaryLines
                restoreBaseColorScheme()
            }
            1048 -> restoreAltScreenCursor()
            1049 -> {
                isAlternateBuffer = false
                activeLines = primaryLines
                restoreAltScreenCursor()
                restoreBaseColorScheme()
            }
            9, 1000 -> {
                mouseNormalMode = false
                mouseButtonEventMode = false
                mouseAnyEventMode = false
            }
            1002 -> mouseButtonEventMode = false
            1003 -> mouseAnyEventMode = false
            1006 -> mouseSgrMode = false
            1007 -> alternateScrollMode = false
            1015 -> mouseUrxvtMode = false
            2004 -> bracketedPaste = false
        }
    }
    if (previousMouseEnabled != isMouseReportingEnabled) {
        onMouseModeChanged?.invoke(isMouseReportingEnabled)
    }
}

internal fun TerminalEmulator.handleAnsiModeSet(modesParam: String) {
    modesParam.split(";").mapNotNull { it.toIntOrNull() }.forEach { mode ->
        when (mode) {
            4 -> insertMode = true
        }
    }
}

internal fun TerminalEmulator.handleAnsiModeReset(modesParam: String) {
    modesParam.split(";").mapNotNull { it.toIntOrNull() }.forEach { mode ->
        when (mode) {
            4 -> insertMode = false
        }
    }
}
