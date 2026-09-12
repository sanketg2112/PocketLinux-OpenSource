package com.sg.linuxgo
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan

class TerminalEmulator(
    var rows: Int = 24,
    var cols: Int = 80,
    internal val maxScrollback: Int = 2000
) {
    companion object {
        // Public re-exports for tests / UI (implementation lives in TerminalPalette / TerminalCharWidth).
        val PALETTE_BLACK = TerminalPalette.PALETTE_BLACK
        val PALETTE_RED = TerminalPalette.PALETTE_RED
        val PALETTE_GREEN = TerminalPalette.PALETTE_GREEN
        val PALETTE_YELLOW = TerminalPalette.PALETTE_YELLOW
        val PALETTE_BLUE = TerminalPalette.PALETTE_BLUE
        val PALETTE_MAGENTA = TerminalPalette.PALETTE_MAGENTA
        val PALETTE_CYAN = TerminalPalette.PALETTE_CYAN
        val PALETTE_WHITE = TerminalPalette.PALETTE_WHITE

        val PALETTE_BRIGHT_BLACK = TerminalPalette.PALETTE_BRIGHT_BLACK
        val PALETTE_BRIGHT_RED = TerminalPalette.PALETTE_BRIGHT_RED
        val PALETTE_BRIGHT_GREEN = TerminalPalette.PALETTE_BRIGHT_GREEN
        val PALETTE_BRIGHT_YELLOW = TerminalPalette.PALETTE_BRIGHT_YELLOW
        val PALETTE_BRIGHT_BLUE = TerminalPalette.PALETTE_BRIGHT_BLUE
        val PALETTE_BRIGHT_MAGENTA = TerminalPalette.PALETTE_BRIGHT_MAGENTA
        val PALETTE_BRIGHT_CYAN = TerminalPalette.PALETTE_BRIGHT_CYAN
        val PALETTE_BRIGHT_WHITE = TerminalPalette.PALETTE_BRIGHT_WHITE

        val DEFAULT_FG = TerminalPalette.DEFAULT_FG

        internal const val EMPTY = 0
        private const val CONTINUATION = -1

        @JvmStatic
        fun charWidth(codePoint: Int): Int = TerminalCharWidth.charWidth(codePoint)
    }

    class ScreenLine(val size: Int) {
        val chars = IntArray(size) { EMPTY }
        val fgColors = IntArray(size) { DEFAULT_FG }
        val bgColors = IntArray(size) { Color.TRANSPARENT }
        val effects = IntArray(size) { TerminalCellStyle.NONE }
        var cachedPresentation: Any? = null

        fun markDirty() {
            cachedPresentation = null
        }

        fun effectAt(x: Int): Int = if (x in 0 until size) effects[x] else TerminalCellStyle.NONE

        fun clear(fg: Int, bg: Int) {
            chars.fill(EMPTY)
            fgColors.fill(fg)
            bgColors.fill(bg)
            effects.fill(TerminalCellStyle.NONE)
            cachedPresentation = null
        }

        fun copyFrom(other: ScreenLine) {
            System.arraycopy(other.chars, 0, chars, 0, size)
            System.arraycopy(other.fgColors, 0, fgColors, 0, size)
            System.arraycopy(other.bgColors, 0, bgColors, 0, size)
            System.arraycopy(other.effects, 0, effects, 0, size)
            // Do not share presentation cache references across rows — a later edit to
            // the source row must not leave this row pointing at a recycled string.
            cachedPresentation = null
        }

        fun toText(): String {
            val sb = StringBuilder(size)
            for (i in 0 until size) {
                val cp = chars[i]
                if (cp > 0) {
                    if (Character.isBmpCodePoint(cp)) {
                        sb.append(cp.toChar())
                    } else {
                        sb.append(Character.highSurrogate(cp))
                        sb.append(Character.lowSurrogate(cp))
                    }
                } else if (cp == EMPTY) {
                    sb.append(' ')
                }
                // CONTINUATION (-1) cells are skipped
            }
            return sb.toString()
        }
    }

    // Double-buffered screen grids
    internal var primaryLines = Array(rows) { ScreenLine(cols) }
    internal var alternateLines = Array(rows) { ScreenLine(cols) }
    internal var activeLines = primaryLines

    internal val scrollback = ArrayList<ScreenLine>()

    // Cursor position
    var cursorX = 0
    var cursorY = 0
    var isCursorVisible = true

    // DECSC / DECRC (ESC 7 / ESC 8) and CSI s / CSI u
    private var savedCursorX = 0
    private var savedCursorY = 0
    private var savedFgColor = DEFAULT_FG
    private var savedBgColor = Color.TRANSPARENT
    private var savedEffects = TerminalCellStyle.NONE

    // Scroll regions
    internal var topMargin = 0
    internal var bottomMargin = rows - 1

    /**
     * Active 16-color + default fg/bg scheme (Match GUI / app theme / OSC updates).
     * [defaultFg] is the SGR 0 / 39 color used for new cells.
     */
    @Volatile
    var colorScheme: TerminalColorScheme = TerminalPalette.ONE_DARK
        private set

    /**
     * Last scheme applied from Match GUI / app theme (not OSC 4/10/11/12).
     * Restored when a TUI enters or leaves the alt screen so the next app
     * does not inherit palette overrides from the previous one.
     */
    @Volatile
    private var baseColorScheme: TerminalColorScheme = colorScheme

    /** Default foreground for erase / SGR 0 under the active scheme. */
    internal val defaultFg: Int
        get() = colorScheme.defaultFg

    // Graphic renditions
    internal var currentFgColor = DEFAULT_FG
    internal var currentBgColor = Color.TRANSPARENT
    internal var currentEffects: Int = TerminalCellStyle.NONE

    // Alternate buffer state
    var isAlternateBuffer = false

    /**
     * Latest OSC 0/2 window title (vim, htop, shell PROMPT_COMMAND, etc.).
     * Empty when never set or cleared.
     */
    var windowTitle: String = ""
        private set

    /**
     * Latest OSC 7 working directory (file:// URI from shell integration).
     * Empty when unknown.
     */
    var remoteCwd: String = ""
        private set

    // Response callback for device queries (DA, DSR)
    var onResponse: ((String) -> Unit)? = null

    // Notified when xterm mouse reporting modes change (for touch routing in the UI).
    var onMouseModeChanged: ((Boolean) -> Unit)? = null

    // xterm mouse reporting modes
    internal var mouseNormalMode = false
    internal var mouseButtonEventMode = false
    internal var mouseAnyEventMode = false
    internal var mouseSgrMode = false
    internal var mouseUrxvtMode = false
    // DECSET 1007: when set, wheel/scroll on the alternate screen maps to cursor keys.
    // Many mobile TUIs still need this behavior even when the app never enables 1007.
    internal var alternateScrollMode = false

    val isMouseReportingEnabled: Boolean
        get() = mouseNormalMode || mouseButtonEventMode || mouseAnyEventMode

    /** True when the active screen is the alt buffer (vim, htop, chat TUIs, etc.). */
    val prefersApplicationScroll: Boolean
        get() = isAlternateBuffer

    // Autowrap mode
    internal var autowrap = true

    /** DECSET 1 — application cursor keys (vim / less / htop). */
    var applicationCursorKeys: Boolean = false
        internal set

    /** ESC = / DECSET 66 — application keypad. */
    var applicationKeypad: Boolean = false
        internal set

    /** DECSET 6 — origin mode (CUP relative to scroll region). */
    internal var originMode = false

    /** IRM (CSI 4h) — insert characters instead of overwriting. */
    internal var insertMode = false

    /** DECSET 5 — whole-screen reverse video. */
    var reverseVideo: Boolean = false
        internal set

    /** DECSET 2004 — bracketed paste. */
    var bracketedPaste: Boolean = false
        internal set

    var cursorShape: TerminalCursorShape = TerminalCursorShape.BLOCK
        internal set

    var onBell: (() -> Unit)? = null

    // Saved DEC private snapshot for CSI ? 1048 / 1049
    private var savedAltCursorX = 0
    private var savedAltCursorY = 0
    private var savedAltFg = DEFAULT_FG
    private var savedAltBg = Color.TRANSPARENT
    private var savedAltEffects = TerminalCellStyle.NONE
    private var savedAltWrapPending = false

    /**
     * xterm-style wrap-pending: after writing into the last column, the next glyph
     * wraps to the next line, but Backspace/CUB only *clear* the pending flag without
     * moving left. Without this, the painted cursor sits past the last cell (invisible)
     * and the first backspace appears to skip over a character.
     */
    internal var wrapPending = false

    // Parser State
    private enum class State { TEXT, ESC, CSI, OSC, OSC_ESC, ESC_IGNORE_ONE, DCS, DCS_ESC }
    private var state = State.TEXT
    private val csiParams = StringBuilder()
    private val oscBuffer = StringBuilder()

    @Synchronized
    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return

        val oldRows = rows
        val oldCols = cols
        val wasAlt = isAlternateBuffer
        rows = newRows
        cols = newCols

        // Primary: preserve overlapping top-left content (xterm-style shell history).
        // Alternate (TUI): reallocate then wipe. Keeping old cells across SIGWINCH left
        // 2–3 residual title/header rows that apps never repaint, so they looked like
        // dead space the TUI "couldn't use". The UI freezes the last Compose frame
        // (fit-scale) until the post-resize redraw arrives — no black flash needed.
        fun resizeGrid(grid: Array<ScreenLine>, preserve: Boolean): Array<ScreenLine> {
            return Array(newRows) { r ->
                val line = ScreenLine(newCols)
                if (preserve && r < oldRows) {
                    val oldLine = grid[r]
                    val copyLen = Math.min(oldCols, newCols)
                    System.arraycopy(oldLine.chars, 0, line.chars, 0, copyLen)
                    System.arraycopy(oldLine.fgColors, 0, line.fgColors, 0, copyLen)
                    System.arraycopy(oldLine.bgColors, 0, line.bgColors, 0, copyLen)
                    System.arraycopy(oldLine.effects, 0, line.effects, 0, copyLen)
                }
                line
            }
        }

        primaryLines = resizeGrid(primaryLines, preserve = true)
        // Always wipe the inactive/active alt grid on size change so a later 1049
        // enter cannot resurrect residual lines from a previous TUI geometry.
        alternateLines = resizeGrid(alternateLines, preserve = false)
        activeLines = if (wasAlt) alternateLines else primaryLines

        // Resize all scrollback lines to match new columns size
        for (i in 0 until scrollback.size) {
            val oldLine = scrollback[i]
            if (oldLine.chars.size != newCols) {
                val newLine = ScreenLine(newCols)
                val copyLen = Math.min(oldLine.chars.size, newCols)
                System.arraycopy(oldLine.chars, 0, newLine.chars, 0, copyLen)
                System.arraycopy(oldLine.fgColors, 0, newLine.fgColors, 0, copyLen)
                System.arraycopy(oldLine.bgColors, 0, newLine.bgColors, 0, copyLen)
                System.arraycopy(oldLine.effects, 0, newLine.effects, 0, copyLen)
                scrollback[i] = newLine
            }
        }

        // Scroll region always tracks full screen after a size change (apps re-DECSTBM).
        topMargin = 0
        bottomMargin = newRows - 1

        cursorX = cursorX.coerceIn(0, newCols - 1)
        cursorY = cursorY.coerceIn(0, newRows - 1)
        wrapPending = false
    }

    @Synchronized
    fun clearAll() {
        scrollback.clear()
        for (line in primaryLines) line.clear(defaultFg, Color.TRANSPARENT)
        for (line in alternateLines) line.clear(defaultFg, Color.TRANSPARENT)
        cursorX = 0
        cursorY = 0
        wrapPending = false
        topMargin = 0
        bottomMargin = rows - 1
        state = State.TEXT
    }

    @Synchronized
    fun trimMemory(level: Int) {
        for (line in scrollback) line.markDirty()
        for (line in primaryLines) line.markDirty()
        for (line in alternateLines) line.markDirty()
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val keep = (maxScrollback / 2).coerceAtLeast(1)
            if (scrollback.size > keep) {
                val toRemove = scrollback.size - keep
                scrollback.subList(0, toRemove).clear()
            }
        }
    }

    /**
     * Feed raw terminal output into the emulator.
     */
    @Synchronized
    fun write(text: String) {
        val len = text.length
        var idx = 0
        while (idx < len) {
            val c = text[idx]
            when (state) {
                State.TEXT -> {
                    if (c == '\u001B') {
                        state = State.ESC
                    } else if (c.isHighSurrogate() && idx + 1 < len && text[idx + 1].isLowSurrogate()) {
                        val codePoint = Character.toCodePoint(c, text[idx + 1])
                        idx++
                        handleCodePoint(codePoint)
                    } else {
                        handleCodePoint(c.code)
                    }
                }
                State.ESC -> {
                    if (c == '[') {
                        csiParams.setLength(0)
                        state = State.CSI
                    } else if (c == ']') {
                        oscBuffer.setLength(0)
                        state = State.OSC
                    } else if (c == 'P') {
                        state = State.DCS
                    } else if (c == '(' || c == ')') {
                        state = State.ESC_IGNORE_ONE
                    } else if (c == '7') {
                        // DECSC — save cursor (used heavily by TUI redraw)
                        saveCursor()
                        state = State.TEXT
                    } else if (c == '8') {
                        // DECRC — restore cursor
                        restoreCursor()
                        state = State.TEXT
                    } else if (c == 'D') {
                        // IND — index (move down / scroll)
                        moveCursorDownAndScroll()
                        state = State.TEXT
                    } else if (c == 'M') {
                        // RI — reverse index
                        reverseIndex()
                        state = State.TEXT
                    } else if (c == 'E') {
                        // NEL — next line
                        cursorX = 0
                        moveCursorDownAndScroll()
                        state = State.TEXT
                    } else if (c == '=') {
                        applicationKeypad = true
                        state = State.TEXT
                    } else if (c == '>') {
                        applicationKeypad = false
                        state = State.TEXT
                    } else {
                        // Unhandled ESC sequence, fallback
                        state = State.TEXT
                        handleCodePoint(c.code)
                    }
                }
                State.CSI -> {
                    if (c.code < 0x20) {
                        handleCodePoint(c.code)
                    } else if (c.code in 0x20..0x3F) {
                        csiParams.append(c)
                    } else if (c.code in 0x40..0x7E) {
                        state = State.TEXT
                        handleCsiSequence(c, csiParams.toString())
                    } else {
                        state = State.TEXT
                    }
                }
                State.OSC -> {
                    if (c == '\u0007') {
                        handleOsc(oscBuffer.toString())
                        oscBuffer.setLength(0)
                        state = State.TEXT
                    } else if (c == '\u001B') {
                        state = State.OSC_ESC
                    } else if (oscBuffer.length < TerminalOsc.MAX_OSC_LENGTH) {
                        oscBuffer.append(c)
                    }
                }
                State.OSC_ESC -> {
                    if (c == '\\') {
                        handleOsc(oscBuffer.toString())
                        oscBuffer.setLength(0)
                        state = State.TEXT
                    } else {
                        // Not ST — treat prior ESC as discarded and continue OSC payload.
                        if (oscBuffer.length < TerminalOsc.MAX_OSC_LENGTH) {
                            oscBuffer.append(c)
                        }
                        state = State.OSC
                    }
                }
                State.DCS -> {
                    if (c == '\u0007') {
                        state = State.TEXT
                    } else if (c == '\u001B') {
                        state = State.DCS_ESC
                    }
                }
                State.DCS_ESC -> {
                    if (c == '\\') {
                        state = State.TEXT
                    } else {
                        state = State.DCS
                    }
                }
                State.ESC_IGNORE_ONE -> {
                    state = State.TEXT
                }
            }
            idx++
        }
    }

    private fun handleCodePoint(codePoint: Int) {
        when {
            codePoint == '\n'.code -> {
                wrapPending = false
                cursorX = 0
                moveCursorDownAndScroll()
            }
            codePoint == '\r'.code -> {
                wrapPending = false
                cursorX = 0
            }
            codePoint == '\b'.code -> {
                handleBackspace()
            }
            codePoint == '\t'.code -> {
                wrapPending = false
                val nextTab = (cursorX / 8 + 1) * 8
                cursorX = Math.min(cols - 1, nextTab)
            }
            codePoint == 0x07 -> {
                onBell?.invoke()
            }
            else -> {
                val width = charWidth(codePoint)
                if (width == 0) {
                    // Combining mark: stay on the previous cell (do not advance).
                    return
                }

                // Pending wrap from previous last-column write → actually wrap now.
                if (wrapPending) {
                    wrapPending = false
                    if (autowrap) {
                        cursorX = 0
                        moveCursorDownAndScroll()
                    } else {
                        cursorX = (cols - 1).coerceAtLeast(0)
                    }
                } else if (cursorX >= cols) {
                    if (autowrap) {
                        cursorX = 0
                        moveCursorDownAndScroll()
                    } else {
                        cursorX = cols - 1
                    }
                }

                if (width == 2) {
                    if (cursorX + 1 >= cols) {
                        if (autowrap) {
                            cursorX = 0
                            moveCursorDownAndScroll()
                        } else {
                            putGlyph(codePoint, width = 1)
                            return
                        }
                    }
                    putGlyph(codePoint, width = 2)
                } else {
                    putGlyph(codePoint, width = 1)
                }
            }
        }
    }

    /**
     * Apply a full color scheme (Match GUI / app theme). Updates current SGR defaults
     * when they still track the previous default foreground.
     */
    @Synchronized
    fun applyColorScheme(scheme: TerminalColorScheme) {
        val prev = colorScheme
        val prevDefault = prev.defaultFg
        remintPalette(prev, scheme)
        colorScheme = scheme
        if (scheme.source != TerminalColorScheme.Source.OSC_DYNAMIC) {
            baseColorScheme = scheme
        }
        if (currentFgColor == prevDefault || currentFgColor == DEFAULT_FG) {
            currentFgColor = scheme.defaultFg
        }
        if (currentBgColor == prev.defaultBg) {
            currentBgColor = android.graphics.Color.TRANSPARENT
        }
        savedFgColor = scheme.defaultFg
        invalidatePresentationCache()
    }

    /**
     * Re-apply the last Match GUI / app theme after a TUI overwrote OSC colors.
     */
    internal fun restoreBaseColorScheme() {
        val base = baseColorScheme
        if (colorScheme != base) {
            applyColorScheme(base)
        }
    }

    /**
     * Rewire cells that used the old 16-color / default chrome so a live TUI
     * (htop, btop, vim) picks up the new theme without a restart.
     */
    private fun remintPalette(old: TerminalColorScheme, new: TerminalColorScheme) {
        val map = HashMap<Int, Int>(20)
        for (i in 0..15) map[old.color(i)] = new.color(i)
        map[old.defaultFg] = new.defaultFg
        map[old.defaultBg] = new.defaultBg
        fun remint(line: ScreenLine) {
            var dirty = false
            for (i in 0 until line.size) {
                map[line.fgColors[i]]?.let { mapped ->
                    if (line.fgColors[i] != mapped) {
                        line.fgColors[i] = mapped
                        dirty = true
                    }
                }
                val bg = line.bgColors[i]
                if (bg != 0 && bg != android.graphics.Color.TRANSPARENT) {
                    map[bg]?.let { mapped ->
                        if (line.bgColors[i] != mapped) {
                            line.bgColors[i] = mapped
                            dirty = true
                        }
                    }
                }
            }
            if (dirty) line.markDirty()
        }
        for (line in primaryLines) remint(line)
        for (line in alternateLines) remint(line)
        for (line in scrollback) remint(line)
    }

    /**
     * Handle OSC (Operating System Command) payloads.
     * - 0 / 2: window title (TUI apps and shell title sequences)
     * - 4: change color palette entry (OSC 4;index;spec)
     * - 7: current working directory (shell integration, file:// URI)
     * - 10 / 11 / 12: default fg / bg / cursor color
     */
    private fun handleOsc(payload: String) {
        if (payload.isEmpty()) return
        val (code, data) = TerminalOsc.splitPayload(payload)
        when (code) {
            "0", "2" -> {
                // Icon+title or window title. Keep a sane length for UI labels.
                windowTitle = data.trim().take(200)
            }
            "4", "10", "11", "12" -> {
                val result = TerminalOscColor.apply(code, data, colorScheme, currentFgColor)
                    ?: return
                if (result.changed) {
                    colorScheme = result.scheme
                    result.currentFg?.let { currentFgColor = it }
                    invalidatePresentationCache()
                }
                for (reply in result.replies) onResponse?.invoke(reply)
            }
            "7" -> {
                TerminalOsc.parseOsc7Cwd(data)?.let { remoteCwd = it }
            }
        }
    }

    /**
     * Backspace with xterm wrap-pending + reverse-wrap at column 0 (multi-line chat inputs).
     */
    private fun handleBackspace() {
        if (wrapPending) {
            // Cancel pending wrap only — cursor already sits on the last column.
            wrapPending = false
            cursorX = (cols - 1).coerceAtLeast(0)
            return
        }
        if (cursorX > 0) {
            cursorX--
            // Land on lead cell if we stepped onto a wide-char trail.
            if (cursorX > 0 && activeLines[cursorY].chars[cursorX] == CONTINUATION) {
                cursorX--
            }
            return
        }
        // Column 0: reverse-wrap to end of previous line so multi-line TUI fields
        // (Grok Build chat input line 2 → line 1) move the cursor onto the last glyph.
        if (autowrap && cursorY > topMargin) {
            cursorY--
            cursorX = (cols - 1).coerceAtLeast(0)
            if (cursorX > 0 && activeLines[cursorY].chars[cursorX] == CONTINUATION) {
                cursorX--
            }
        }
    }

    /**
     * Place a glyph at the cursor, repairing wide-character halves so overwrite /
     * erase never leaves a stale CONTINUATION cell (a source of “stuck” glyphs in TUIs).
     */
    private fun putGlyph(codePoint: Int, width: Int) {
        val line = activeLines[cursorY]
        line.markDirty()

        if (insertMode && width > 0 && cursorX < cols) {
            val shift = width.coerceAtMost(cols - cursorX)
            for (i in (cols - 1) downTo cursorX + shift) {
                line.chars[i] = line.chars[i - shift]
                line.fgColors[i] = line.fgColors[i - shift]
                line.bgColors[i] = line.bgColors[i - shift]
                line.effects[i] = line.effects[i - shift]
            }
            for (i in cursorX until cursorX + shift) {
                line.chars[i] = EMPTY
                line.fgColors[i] = currentFgColor
                line.bgColors[i] = currentBgColor
                line.effects[i] = TerminalCellStyle.NONE
            }
        }

        // Writing into the trailing half of a previous wide char: blank the lead cell.
        if (cursorX > 0 && line.chars[cursorX] == CONTINUATION) {
            line.chars[cursorX - 1] = EMPTY
            line.fgColors[cursorX - 1] = currentFgColor
            line.bgColors[cursorX - 1] = currentBgColor
        }

        if (width == 2) {
            // If the second cell is the lead of another wide char, drop its trail too.
            if (cursorX + 1 < cols &&
                cursorX + 2 < cols &&
                line.chars[cursorX + 2] == CONTINUATION &&
                line.chars[cursorX + 1] > 0
            ) {
                line.chars[cursorX + 2] = EMPTY
                line.fgColors[cursorX + 2] = currentFgColor
                line.bgColors[cursorX + 2] = currentBgColor
            }
            line.chars[cursorX] = codePoint
            line.fgColors[cursorX] = currentFgColor
            line.bgColors[cursorX] = currentBgColor
            line.effects[cursorX] = currentEffects
            line.chars[cursorX + 1] = CONTINUATION
            line.fgColors[cursorX + 1] = currentFgColor
            line.bgColors[cursorX + 1] = currentBgColor
            line.effects[cursorX + 1] = currentEffects
            cursorX += 2
            if (cursorX >= cols) {
                cursorX = cols
                wrapPending = true
            }
        } else {
            line.chars[cursorX] = codePoint
            line.fgColors[cursorX] = currentFgColor
            line.bgColors[cursorX] = currentBgColor
            line.effects[cursorX] = currentEffects
            // Overwriting the lead of a wide char: clear the orphaned trail cell.
            if (cursorX + 1 < cols && line.chars[cursorX + 1] == CONTINUATION) {
                line.chars[cursorX + 1] = EMPTY
                line.fgColors[cursorX + 1] = currentFgColor
                line.bgColors[cursorX + 1] = currentBgColor
            }
            cursorX++
            if (cursorX >= cols) {
                cursorX = cols
                wrapPending = true
            }
        }
    }

    internal fun saveCursor() {
        savedCursorX = cursorX
        savedCursorY = cursorY
        savedFgColor = currentFgColor
        savedBgColor = currentBgColor
        savedEffects = currentEffects
    }

    internal fun restoreCursor() {
        // Allow col == cols to restore wrap-pending; never beyond that.
        cursorX = savedCursorX.coerceIn(0, cols)
        cursorY = savedCursorY.coerceIn(0, rows - 1)
        currentFgColor = savedFgColor
        currentBgColor = savedBgColor
        currentEffects = savedEffects
        wrapPending = cursorX >= cols
    }

    internal fun saveAltScreenCursor() {
        savedAltCursorX = cursorX
        savedAltCursorY = cursorY
        savedAltFg = currentFgColor
        savedAltBg = currentBgColor
        savedAltEffects = currentEffects
        savedAltWrapPending = wrapPending
    }

    internal fun restoreAltScreenCursor() {
        cursorX = savedAltCursorX.coerceIn(0, cols)
        cursorY = savedAltCursorY.coerceIn(0, rows - 1)
        currentFgColor = savedAltFg
        currentBgColor = savedAltBg
        currentEffects = savedAltEffects
        wrapPending = savedAltWrapPending && cursorX >= cols - 1
    }

    private fun moveCursorDownAndScroll() {
        if (cursorY < bottomMargin) {
            cursorY++
        } else if (cursorY == bottomMargin) {
            // Scroll screen up
            scrollUp(topMargin, bottomMargin)
        }
    }

    private fun reverseIndex() {
        if (cursorY > topMargin) {
            cursorY--
        } else if (cursorY == topMargin) {
            scrollDown(topMargin, bottomMargin)
        }
    }

    /**
     * Build an xterm mouse event sequence for the given cell and button state.
     * [col] and [row] are 1-based terminal coordinates.
     */
    @Synchronized
    fun buildMouseEvent(col: Int, row: Int, button: Int, isRelease: Boolean, isMotion: Boolean = false): String {
        return TerminalMouseEncoder.buildMouseEvent(
            col = col,
            row = row,
            button = button,
            isRelease = isRelease,
            isMotion = isMotion,
            cols = cols,
            rows = rows,
            sgrMode = mouseSgrMode,
            urxvtMode = mouseUrxvtMode
        )
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
    @Synchronized
    fun buildScrollSequences(steps: Int, col: Int = cols / 2, row: Int = rows / 2): String {
        return TerminalMouseEncoder.buildScrollSequences(
            steps = steps,
            col = col,
            row = row,
            cols = cols,
            rows = rows,
            mouseReportingEnabled = isMouseReportingEnabled,
            alternateBuffer = isAlternateBuffer,
            alternateScrollMode = alternateScrollMode,
            sgrMode = mouseSgrMode,
            urxvtMode = mouseUrxvtMode
        )
    }

    /**
     * Whether finger-scroll should be forwarded into the session (TUI / mouse apps)
     * instead of scrolling host scrollback.
     */
    @get:Synchronized
    val shouldForwardScrollToApp: Boolean
        get() = TerminalMouseEncoder.shouldForwardScrollToApp(
            alternateBuffer = isAlternateBuffer,
            mouseReportingEnabled = isMouseReportingEnabled,
            alternateScrollMode = alternateScrollMode
        )

    /**
     * Render the active screen + scrollback into a single styled SpannableStringBuilder.
     * Returns a pair of: CharSequence to display, and absolute cursor line.
     */
    @Synchronized
    fun render(): Pair<CharSequence, Int> {
        val sb = SpannableStringBuilder()

        // 1. Render scrollback lines (only in normal buffer mode)
        if (!isAlternateBuffer) {
            for (line in scrollback) {
                appendLine(sb, line)
            }
        }

        // 2. Render visible screen lines
        val screenStartOffset = sb.length
        for (line in activeLines) {
            appendLine(sb, line)
        }

        // Remove the trailing newline of the last line
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') {
            sb.delete(sb.length - 1, sb.length)
        }

        // Calculate absolute cursor line offset
        val cursorLine = if (isAlternateBuffer) cursorY else scrollback.size + cursorY
        return Pair(sb, cursorLine)
    }

    private fun appendLine(sb: SpannableStringBuilder, line: ScreenLine) {
        sb.append(
            TerminalLineRender.toSpannable(
                line,
                defaultFg,
                reverseVideo = reverseVideo,
                canvasBg = colorScheme.defaultBg
            )
        )
        sb.append('\n')
    }

    @Synchronized
    fun getScrollbackSize(): Int {
        return scrollback.size
    }

    /** Drop cached rendered lines (e.g. after theme / force-TUI-color settings change). */
    @Synchronized
    fun invalidatePresentationCache() {
        for (line in primaryLines) line.markDirty()
        for (line in alternateLines) line.markDirty()
        for (line in scrollback) line.markDirty()
    }

    @Synchronized
    fun appendScrollbackLine(sb: SpannableStringBuilder, index: Int) {
        if (index in 0 until scrollback.size) {
            appendLine(sb, scrollback[index])
        }
    }

    @Synchronized
    fun renderActiveLine(index: Int): CharSequence {
        val sb = SpannableStringBuilder()
        if (index in 0 until rows) {
            val line = activeLines[index]

            // Build string and track cell-to-string position mapping
            val strPos = IntArray(line.size + 1)
            for (i in 0 until line.size) {
                strPos[i] = sb.length
                val cp = line.chars[i]
                if (cp > 0) {
                    if (Character.isBmpCodePoint(cp)) {
                        sb.append(cp.toChar())
                    } else {
                        sb.append(Character.highSurrogate(cp))
                        sb.append(Character.lowSurrogate(cp))
                    }
                } else if (cp == EMPTY) {
                    sb.append(' ')
                }
                // CONTINUATION (-1) cells are skipped
            }
            strPos[line.size] = sb.length

            // Apply spans using string positions
            var x = 0
            while (x < line.size) {
                val fg = line.fgColors[x]
                val bg = line.bgColors[x]
                var runLen = 1
                while (x + runLen < line.size && line.fgColors[x + runLen] == fg && line.bgColors[x + runLen] == bg) {
                    runLen++
                }

                val spanStart = strPos[x]
                val spanEnd = strPos[x + runLen]

                if (spanStart < spanEnd) {
                    if (fg != defaultFg && fg != DEFAULT_FG) {
                        sb.setSpan(ForegroundColorSpan(fg), spanStart, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    if (bg != Color.TRANSPARENT) {
                        sb.setSpan(BackgroundColorSpan(bg), spanStart, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }

                x += runLen
            }
        }
        return sb
    }

    @Synchronized
    fun getLineText(index: Int): String {
        if (index in 0 until rows) {
            return activeLines[index].toText()
        }
        return ""
    }

    @Synchronized
    fun renderActiveScreen(): CharSequence {
        val sb = SpannableStringBuilder()
        for (line in activeLines) {
            appendLine(sb, line)
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') {
            sb.delete(sb.length - 1, sb.length)
        }
        return sb
    }

    @Synchronized
    fun getScrollbackLine(index: Int): ScreenLine {
        return scrollback[index]
    }

    @Synchronized
    fun getActiveLine(index: Int): ScreenLine {
        return activeLines[index]
    }

    @Synchronized
    fun renderLine(line: ScreenLine): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        appendLine(sb, line)
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') {
            sb.delete(sb.length - 1, sb.length)
        }
        return sb
    }
}
