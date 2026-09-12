package com.sg.linuxgo

/**
 * Debounces PTY / SIGWINCH resizes until the layout has stopped moving.
 *
 * IME open/close animates the viewport every frame. Applying each intermediate
 * size reflows the shell and wipes TUI alt-screens — the “text jumps up and
 * down” glitch. Wait [SETTLE_MS] after the last size change, then apply once.
 */
class TerminalResizeScheduler(
    private val settleMs: Long = SETTLE_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    data class GridSize(
        val sessionId: String,
        val rows: Int,
        val cols: Int,
        val charWidthPx: Int,
        val charHeightPx: Int
    )

    var lastApplied: GridSize? = null
        private set
    var pending: GridSize? = null
        private set
    private var lastChangeAtMs: Long = 0L

    fun submit(
        sessionId: String,
        rows: Int,
        cols: Int,
        charWidthPx: Int,
        charHeightPx: Int
    ): GridSize {
        val size = GridSize(
            sessionId = sessionId,
            rows = rows.coerceAtLeast(MIN_ROWS),
            cols = cols.coerceAtLeast(MIN_COLS),
            charWidthPx = charWidthPx.coerceAtLeast(1),
            charHeightPx = charHeightPx.coerceAtLeast(1)
        )
        if (lastApplied == size && pending == null) {
            return size
        }
        if (pending != size) {
            pending = size
            lastChangeAtMs = nowMs()
        }
        return size
    }

    /** First layout applies immediately so the session is not stuck at 24×80. */
    fun shouldApplyImmediately(): Boolean {
        return lastApplied == null && pending != null
    }

    fun isVisualPending(): Boolean {
        val p = pending ?: return false
        return p != lastApplied
    }

    fun remainingSettleMs(now: Long = nowMs()): Long {
        val p = pending ?: return 0L
        if (p == lastApplied) return 0L
        if (lastApplied == null) return 0L
        return (settleMs - (now - lastChangeAtMs)).coerceAtLeast(0L)
    }

    fun takeSettled(now: Long = nowMs()): GridSize? {
        val p = pending ?: return null
        if (p == lastApplied) {
            pending = null
            return null
        }
        if (lastApplied != null && now - lastChangeAtMs < settleMs) return null
        lastApplied = p
        pending = null
        return p
    }

    fun clear() {
        pending = null
        lastApplied = null
        lastChangeAtMs = 0L
    }

    companion object {
        const val SETTLE_MS = 200L
        const val MIN_ROWS = 5
        const val MIN_COLS = 20
    }
}
