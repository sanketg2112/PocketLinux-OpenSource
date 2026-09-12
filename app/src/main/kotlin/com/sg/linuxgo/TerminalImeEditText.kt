package com.sg.linuxgo

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * Invisible IME host for the terminal.
 *
 * Uses the real EditText [InputConnection] (via super) so the IME stays in sync with
 * selection/surrounding text, then immediately drains committed text to the PTY and
 * clears the field — without leaving the buffer non-empty (which stalls many keyboards).
 *
 * Hardware / external keyboards do not go through [InputConnection] for printable keys;
 * they arrive as [onKeyDown] / [onKeyMultiple] and are forwarded here to the PTY.
 */
class TerminalImeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** Called for printable text (may include multiple chars / newlines from the IME). */
    var onImeCommit: ((String) -> Unit)? = null
    /** Called once per logical backspace. */
    var onImeBackspace: (() -> Unit)? = null
    /** Called for soft-keyboard Enter. */
    var onImeEnter: (() -> Unit)? = null

    /** In-flight composition already forwarded to the PTY (avoids double-emit on commit). */
    private var emittedComposition: String = ""

    /** Pending dead-key accent from hardware keyboards (e.g. ´ then e → é). */
    private var pendingDeadKeyAccent: Int = 0

    /** When true, arrows emit application-cursor sequences (DECSET 1). */
    var applicationCursorKeys: () -> Boolean = { false }
    var applicationKeypad: () -> Boolean = { false }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        showSoftInputOnFocus = true
        isCursorVisible = false
        background = null
        setTextColor(android.graphics.Color.TRANSPARENT)
        setHintTextColor(android.graphics.Color.TRANSPARENT)
        setTextIsSelectable(false)
        // Char-based input (Samsung / Gboard friendly). Avoid TYPE_NULL.
        // Do NOT use VISIBLE_PASSWORD or NO_SUGGESTIONS — both disable Gboard/Samsung
        // gesture (swipe) typing on most OEM keyboards.
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
            InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        setText("")
        setSelection(0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * Hardware / Bluetooth / USB keyboards deliver printable characters here (not via
     * InputConnection). Soft keyboards use InputConnection and rarely hit this path for
     * letters — [InputConnectionWrapper.sendKeyEvent] already handles those.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleHardwareKey(event)) return true
        // Never let EditText handle DEL/forward-del itself — its field cursor would
        // move (often looking like the caret jumps forward) without PTY delete.
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handleHardwareKey(event)) return true
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        // Some external keyboards batch text as KEYCODE_UNKNOWN + characters.
        val chars = event.characters
        if (!chars.isNullOrEmpty()) {
            dispatchCommitted(chars)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    /**
     * @return true if the event was consumed and written to the PTY.
     */
    fun handleHardwareKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            // Consume UP for keys we handle so they don't leak into EditText.
            return isHardwareTerminalKey(event.keyCode) ||
                event.unicodeChar != 0 ||
                event.isCtrlPressed ||
                event.isAltPressed
        }

        val keyCode = event.keyCode

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                pendingDeadKeyAccent = 0
                onImeEnter?.invoke()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                // Hardware / external keyboard Backspace. Do not fall through to
                // EditText (that advances the field cursor) or treat DEL as printable.
                pendingDeadKeyAccent = 0
                onImeBackspace?.invoke()
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                pendingDeadKeyAccent = 0
                // "Delete" key (delete-under-cursor), not Backspace.
                onImeCommit?.invoke("\u001B[3~")
                return true
            }
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_INSERT,
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
            KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
            KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12 -> {
                pendingDeadKeyAccent = 0
                val seq = TerminalKeyHandler.sequenceFor(
                    keyCode = keyCode,
                    modifiers = TerminalKeyHandler.modifiersOf(event),
                    applicationCursor = applicationCursorKeys(),
                    applicationKeypad = applicationKeypad()
                )
                if (seq != null) {
                    onImeCommit?.invoke(seq)
                    return true
                }
            }
        }

        // Ctrl+A..Z → ASCII control codes (works even when unicodeChar is 0).
        if (event.isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            pendingDeadKeyAccent = 0
            val ctrl = (keyCode - KeyEvent.KEYCODE_A + 1).toChar()
            onImeCommit?.invoke(ctrl.toString())
            return true
        }
        // Common Ctrl punctuation used in terminals.
        if (event.isCtrlPressed) {
            pendingDeadKeyAccent = 0
            when (keyCode) {
                KeyEvent.KEYCODE_LEFT_BRACKET -> {
                    onImeCommit?.invoke("\u001B"); return true
                }
                KeyEvent.KEYCODE_BACKSLASH -> {
                    onImeCommit?.invoke("\u001C"); return true
                }
                KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                    onImeCommit?.invoke("\u001D"); return true
                }
                KeyEvent.KEYCODE_SPACE -> {
                    onImeCommit?.invoke("\u0000"); return true
                }
            }
        }

        var unicode = event.unicodeChar
        if (unicode == 0) return false

        // Some maps report Backspace as unicode BS/DEL without KEYCODE_DEL.
        val unicodeNoMeta = unicode and KeyCharacterMap.COMBINING_ACCENT.inv()
        if (unicodeNoMeta == 0x08 || unicodeNoMeta == 0x7F) {
            pendingDeadKeyAccent = 0
            onImeBackspace?.invoke()
            return true
        }

        // Dead keys (´ ` ^ ~ ¨) from external keyboards.
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            pendingDeadKeyAccent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        if (pendingDeadKeyAccent != 0) {
            val combined = KeyEvent.getDeadChar(pendingDeadKeyAccent, unicode)
            pendingDeadKeyAccent = 0
            if (combined != 0) {
                unicode = combined
            }
        }

        val ch = unicode.toChar()
        if (ch.isISOControl() && ch != '\t') return false

        if (event.isAltPressed && !event.isCtrlPressed) {
            // ESC-prefix (meta) for Alt+char — common terminal convention.
            onImeCommit?.invoke("\u001B$ch")
        } else {
            onImeCommit?.invoke(ch.toString())
        }
        return true
    }

    private fun isHardwareTerminalKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_DEL ||
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL ||
            keyCode == KeyEvent.KEYCODE_TAB ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
            keyCode == KeyEvent.KEYCODE_MOVE_END ||
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            keyCode == KeyEvent.KEYCODE_INSERT ||
            keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ||
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // Must use super so we get EditableInputConnection tied to this field's Editable.
        // A bare BaseInputConnection desyncs IME selection and stalls after one key.
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.inputType = inputType
        outAttrs.imeOptions = (outAttrs.imeOptions and EditorInfo.IME_MASK_ACTION.inv()) or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = selectionStart.coerceAtLeast(0)
        outAttrs.initialSelEnd = selectionEnd.coerceAtLeast(0)

        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val raw = text?.toString().orEmpty()
                // Already streamed via setComposingText deltas — don't double-send.
                if (raw.isNotEmpty() && raw == emittedComposition) {
                    emittedComposition = ""
                    // Still let the base clear composition spans, then empty the field.
                    super.commitText("", 1)
                    clearFieldQuietly()
                    return true
                }
                if (emittedComposition.isNotEmpty() && raw.startsWith(emittedComposition)) {
                    val rest = raw.substring(emittedComposition.length)
                    emittedComposition = ""
                    if (rest.isNotEmpty()) dispatchCommitted(rest)
                    super.commitText("", 1)
                    clearFieldQuietly()
                    return true
                }
                // Swipe-to-type often commits a word different from last composing path
                // (autocorrect / full rewrite). Replace streamed prefix if needed.
                if (emittedComposition.isNotEmpty() && raw.isNotEmpty() && raw != emittedComposition) {
                    if (!raw.startsWith(emittedComposition) && !emittedComposition.startsWith(raw)) {
                        repeat(emittedComposition.length) { onImeBackspace?.invoke() }
                        dispatchCommitted(raw)
                    } else if (emittedComposition.startsWith(raw)) {
                        val deleted = emittedComposition.length - raw.length
                        repeat(deleted) { onImeBackspace?.invoke() }
                    } else {
                        val rest = raw.substring(emittedComposition.length)
                        if (rest.isNotEmpty()) dispatchCommitted(rest)
                    }
                    emittedComposition = ""
                    super.commitText("", 1)
                    clearFieldQuietly()
                    return true
                }
                emittedComposition = ""
                if (raw.isNotEmpty()) dispatchCommitted(raw)
                super.commitText("", 1)
                clearFieldQuietly()
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val next = text?.toString().orEmpty()
                // Live-forward composition so English keyboards that only compose still work.
                // Keep the real editable composition for Gboard swipe / gesture typing —
                // clearing mid-gesture aborts the trail on many OEMs.
                if (next.startsWith(emittedComposition)) {
                    val added = next.substring(emittedComposition.length)
                    if (added.isNotEmpty()) dispatchCommitted(added)
                } else if (emittedComposition.startsWith(next)) {
                    val deleted = emittedComposition.length - next.length
                    repeat(deleted) { onImeBackspace?.invoke() }
                } else {
                    repeat(emittedComposition.length) { onImeBackspace?.invoke() }
                    if (next.isNotEmpty()) dispatchCommitted(next)
                }
                emittedComposition = next
                return super.setComposingText(text, newCursorPosition)
            }

            override fun finishComposingText(): Boolean {
                // Composition already streamed via setComposingText deltas.
                emittedComposition = ""
                val ok = super.finishComposingText()
                clearFieldQuietly()
                return ok
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (emittedComposition.isNotEmpty()) {
                    val remove = beforeLength.coerceAtMost(emittedComposition.length)
                    emittedComposition = emittedComposition.dropLast(remove)
                    repeat(remove) { onImeBackspace?.invoke() }
                    val rest = (beforeLength - remove).coerceAtLeast(0)
                    repeat(rest) { onImeBackspace?.invoke() }
                    clearFieldQuietly()
                    return true
                }
                val before = beforeLength.coerceAtLeast(0)
                repeat(before) { onImeBackspace?.invoke() }
                clearFieldQuietly()
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                return deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event == null) return super.sendKeyEvent(event)
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            emittedComposition = ""
                            onImeEnter?.invoke()
                            clearFieldQuietly()
                            return true
                        }
                        KeyEvent.KEYCODE_DEL -> {
                            if (emittedComposition.isNotEmpty()) {
                                emittedComposition = emittedComposition.dropLast(1)
                            }
                            onImeBackspace?.invoke()
                            clearFieldQuietly()
                            return true
                        }
                        KeyEvent.KEYCODE_FORWARD_DEL -> {
                            onImeBackspace?.invoke()
                            clearFieldQuietly()
                            return true
                        }
                        KeyEvent.KEYCODE_TAB -> {
                            onImeCommit?.invoke("\t")
                            return true
                        }
                        KeyEvent.KEYCODE_ESCAPE -> {
                            onImeCommit?.invoke("\u001B")
                            return true
                        }
                    }
                    val unicode = event.unicodeChar
                    if (unicode != 0 && !event.isCtrlPressed && !event.isAltPressed) {
                        val ch = unicode.toChar()
                        if (!ch.isISOControl() || ch == '\t') {
                            onImeCommit?.invoke(ch.toString())
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                when (actionCode) {
                    EditorInfo.IME_ACTION_GO,
                    EditorInfo.IME_ACTION_SEND,
                    EditorInfo.IME_ACTION_DONE,
                    EditorInfo.IME_ACTION_NEXT,
                    EditorInfo.IME_ACTION_NONE,
                    EditorInfo.IME_ACTION_UNSPECIFIED -> {
                        emittedComposition = ""
                        onImeEnter?.invoke()
                        clearFieldQuietly()
                        return true
                    }
                }
                return super.performEditorAction(actionCode)
            }

            private fun clearFieldQuietly() {
                val editable = editableText ?: return
                if (editable.isNotEmpty()) {
                    editable.clear()
                }
                try {
                    setSelection(0)
                } catch (_: IndexOutOfBoundsException) {
                }
            }
        }
    }

    private fun dispatchCommitted(raw: String) {
        if (raw.isEmpty()) return
        var i = 0
        val n = raw.length
        val sb = StringBuilder()
        while (i < n) {
            val c = raw[i]
            when {
                c == '\r' && i + 1 < n && raw[i + 1] == '\n' -> {
                    flush(sb)
                    onImeEnter?.invoke()
                    i += 2
                }
                c == '\n' || c == '\r' -> {
                    flush(sb)
                    onImeEnter?.invoke()
                    i++
                }
                c == '\u200B' -> i++
                c == '\b' || c == '\u007F' -> {
                    flush(sb)
                    onImeBackspace?.invoke()
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        flush(sb)
    }

    private fun flush(sb: StringBuilder) {
        if (sb.isNotEmpty()) {
            onImeCommit?.invoke(sb.toString())
            sb.clear()
        }
    }

    /**
     * Keep buffer empty when (re)focusing. Avoid calling mid-composition.
     */
    fun stabilizeBuffer() {
        emittedComposition = ""
        val current = text?.toString().orEmpty()
        if (current.isNotEmpty()) {
            setText("")
        }
        if (selectionStart != 0 || selectionEnd != 0) {
            try {
                setSelection(0)
            } catch (_: IndexOutOfBoundsException) {
            }
        }
    }
}
