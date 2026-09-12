package com.sg.linuxgo

/**
 * Serializes terminal IME show/hide so delayed show retries cannot fight a later hide.
 *
 * [ensureTerminalKeyboardReady] posts follow-up shows at 0 / 120 / 350 ms. A later hide
 * (or an overlay that must keep the keyboard down) increments [generation] so those
 * posts become no-ops instead of popping the IME back up.
 */
class TerminalImeCoordinator {
    var suppressed: Boolean = false
        private set
    var generation: Int = 0
        private set

    /** Block every show attempt (appearance / snippet overlays). */
    fun suppress(): Int {
        suppressed = true
        generation += 1
        return generation
    }

    fun release() {
        suppressed = false
    }

    /** Invalidate in-flight show posts without changing [suppressed]. */
    fun invalidateShows(): Int {
        generation += 1
        return generation
    }

    /** Start a show. Null if [suppressed]. */
    fun beginShow(): Int? {
        if (suppressed) return null
        generation += 1
        return generation
    }

    fun isCurrentShow(token: Int): Boolean = !suppressed && token == generation
}
