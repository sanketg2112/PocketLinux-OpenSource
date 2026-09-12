package com.sg.linuxgo
/**
 * Pure xterm mouse / scroll sequence encoding.
 * Mode flags and screen geometry are passed in; no emulator instance required.
 */
object TerminalMouseEncoder {
    /**
     * Build an xterm mouse event sequence for the given cell and button state.
     * [col] and [row] are 1-based terminal coordinates.
     */
    fun buildMouseEvent(
        col: Int,
        row: Int,
        button: Int,
        isRelease: Boolean,
        isMotion: Boolean,
        cols: Int,
        rows: Int,
        sgrMode: Boolean,
        urxvtMode: Boolean
    ): String {
        val safeCol = col.coerceIn(1, cols)
        val safeRow = row.coerceIn(1, rows)
        val cb = when {
            isMotion && button >= 0 -> button + 32
            isMotion -> 35
            else -> button
        }

        return when {
            sgrMode -> "\u001B[<$cb;$safeCol;${safeRow}${if (isRelease) 'm' else 'M'}"
            urxvtMode -> "\u001B[${cb};$safeCol;${safeRow}${if (isRelease) 'm' else 'M'}"
            else -> {
                val b = (cb + 32).coerceIn(32, 255).toChar()
                val cx = (safeCol + 32).coerceIn(33, 255).toChar()
                val ry = (safeRow + 32).coerceIn(33, 255).toChar()
                "\u001B[M$b$cx$ry"
            }
        }
    }

    /**
     * Convert a finger/trackpad scroll into sequences the running app understands.
     *
     * [steps] &gt; 0 means "scroll up" (reveal content above / older chat) — xterm wheel
     * button 64 or CSI CUU. [steps] &lt; 0 means "scroll down" — button 65 or CSI CUD.
     *
     * Emits mouse-wheel when the app enabled mouse reporting, and always emits cursor keys
     * on the alternate screen. Many chat TUIs enable mouse for clicks but only scroll on
     * keys (or vice versa); sending both is safe when the app ignores one of them.
     */
    fun buildScrollSequences(
        steps: Int,
        col: Int,
        row: Int,
        cols: Int,
        rows: Int,
        mouseReportingEnabled: Boolean,
        alternateBuffer: Boolean,
        alternateScrollMode: Boolean,
        sgrMode: Boolean,
        urxvtMode: Boolean
    ): String {
        if (steps == 0) return ""
        val count = kotlin.math.abs(steps).coerceAtMost(30)
        val scrollUp = steps > 0
        val safeCol = col.coerceIn(1, cols)
        val safeRow = row.coerceIn(1, rows)

        return buildString {
            if (mouseReportingEnabled) {
                val button = if (scrollUp) 64 else 65
                repeat(count) {
                    append(
                        buildMouseEvent(
                            col = safeCol,
                            row = safeRow,
                            button = button,
                            isRelease = false,
                            isMotion = false,
                            cols = cols,
                            rows = rows,
                            sgrMode = sgrMode,
                            urxvtMode = urxvtMode
                        )
                    )
                }
            }
            if (alternateBuffer || alternateScrollMode) {
                // Cursor keys (xterm alternateScroll / 1007). Always on alt screen so
                // chat TUIs that ignore wheel still move history.
                val key = if (scrollUp) "\u001B[A" else "\u001B[B"
                repeat(count) { append(key) }
            } else if (!mouseReportingEnabled) {
                // Host asked us to forward scroll (viewport full, no local history) but
                // the app never enabled mouse/alt — still emit arrows so chat TUIs move.
                val key = if (scrollUp) "\u001B[A" else "\u001B[B"
                repeat(count) { append(key) }
            }
        }
    }

    fun shouldForwardScrollToApp(
        alternateBuffer: Boolean,
        mouseReportingEnabled: Boolean,
        alternateScrollMode: Boolean
    ): Boolean = alternateBuffer || mouseReportingEnabled || alternateScrollMode
}
