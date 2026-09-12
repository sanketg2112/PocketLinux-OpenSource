package com.sg.linuxgo

import android.view.KeyEvent

/**
 * xterm / VT key sequences, including application-cursor and modifier forms.
 */
object TerminalKeyHandler {
    const val MOD_SHIFT = 1
    const val MOD_ALT = 2
    const val MOD_CTRL = 4

    fun modifiersOf(event: KeyEvent): Int {
        var m = 0
        if (event.isShiftPressed) m = m or MOD_SHIFT
        if (event.isAltPressed) m = m or MOD_ALT
        if (event.isCtrlPressed) m = m or MOD_CTRL
        return m
    }

    /**
     * Sequence for a navigation / function key, or null if this handler does not own it.
     */
    fun sequenceFor(
        keyCode: Int,
        modifiers: Int,
        applicationCursor: Boolean,
        applicationKeypad: Boolean
    ): String? {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> arrow("A", modifiers, applicationCursor)
            KeyEvent.KEYCODE_DPAD_DOWN -> arrow("B", modifiers, applicationCursor)
            KeyEvent.KEYCODE_DPAD_RIGHT -> arrow("C", modifiers, applicationCursor)
            KeyEvent.KEYCODE_DPAD_LEFT -> arrow("D", modifiers, applicationCursor)
            KeyEvent.KEYCODE_MOVE_HOME ->
                if (modifiers == 0) {
                    if (applicationCursor) "\u001BOH" else "\u001B[H"
                } else {
                    modified("\u001B[1", modifiers, 'H')
                }
            KeyEvent.KEYCODE_MOVE_END ->
                if (modifiers == 0) {
                    if (applicationCursor) "\u001BOF" else "\u001B[F"
                } else {
                    modified("\u001B[1", modifiers, 'F')
                }
            KeyEvent.KEYCODE_PAGE_UP -> tilde(5, modifiers)
            KeyEvent.KEYCODE_PAGE_DOWN -> tilde(6, modifiers)
            KeyEvent.KEYCODE_INSERT -> tilde(2, modifiers)
            KeyEvent.KEYCODE_FORWARD_DEL -> tilde(3, modifiers)
            KeyEvent.KEYCODE_TAB -> if ((modifiers and MOD_SHIFT) != 0) "\u001B[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_F1 -> function("\u001BOP", 1, 11, modifiers)
            KeyEvent.KEYCODE_F2 -> function("\u001BOQ", 1, 12, modifiers)
            KeyEvent.KEYCODE_F3 -> function("\u001BOR", 1, 13, modifiers)
            KeyEvent.KEYCODE_F4 -> function("\u001BOS", 1, 14, modifiers)
            KeyEvent.KEYCODE_F5 -> tilde(15, modifiers)
            KeyEvent.KEYCODE_F6 -> tilde(17, modifiers)
            KeyEvent.KEYCODE_F7 -> tilde(18, modifiers)
            KeyEvent.KEYCODE_F8 -> tilde(19, modifiers)
            KeyEvent.KEYCODE_F9 -> tilde(20, modifiers)
            KeyEvent.KEYCODE_F10 -> tilde(21, modifiers)
            KeyEvent.KEYCODE_F11 -> tilde(23, modifiers)
            KeyEvent.KEYCODE_F12 -> tilde(24, modifiers)
            KeyEvent.KEYCODE_NUMPAD_ENTER -> if (applicationKeypad) "\u001BOM" else "\r"
            else -> null
        }
    }

    fun extraKeyName(
        name: String,
        applicationCursor: Boolean
    ): String? {
        return when (name.uppercase()) {
            "ESC" -> "\u001B"
            "TAB" -> "\t"
            "ENTER" -> "\r"
            "LEFT" -> if (applicationCursor) "\u001BOD" else "\u001B[D"
            "UP" -> if (applicationCursor) "\u001BOA" else "\u001B[A"
            "DOWN" -> if (applicationCursor) "\u001BOB" else "\u001B[B"
            "RIGHT" -> if (applicationCursor) "\u001BOC" else "\u001B[C"
            "HOME" -> if (applicationCursor) "\u001BOH" else "\u001B[H"
            "END" -> if (applicationCursor) "\u001BOF" else "\u001B[F"
            "PGUP" -> "\u001B[5~"
            "PGDN" -> "\u001B[6~"
            "INS" -> "\u001B[2~"
            "DEL" -> "\u001B[3~"
            "F1" -> "\u001BOP"
            "F2" -> "\u001BOQ"
            "F3" -> "\u001BOR"
            "F4" -> "\u001BOS"
            "F5" -> "\u001B[15~"
            "F6" -> "\u001B[17~"
            "F7" -> "\u001B[18~"
            "F8" -> "\u001B[19~"
            "F9" -> "\u001B[20~"
            "F10" -> "\u001B[21~"
            "F11" -> "\u001B[23~"
            "F12" -> "\u001B[24~"
            else -> null
        }
    }

    /**
     * Wrap clipboard paste. Bracketed paste lets vim/nano treat the blob as text.
     */
    fun wrapPaste(text: String, bracketed: Boolean): String {
        val normalized = text.replace("\r\n", "\n").replace('\n', '\r')
        return if (bracketed) {
            "\u001B[200~$normalized\u001B[201~"
        } else {
            normalized
        }
    }

    private fun arrow(letter: String, modifiers: Int, applicationCursor: Boolean): String {
        return if (modifiers == 0) {
            if (applicationCursor) "\u001BO$letter" else "\u001B[$letter"
        } else {
            modified("\u001B[1", modifiers, letter[0])
        }
    }

    private fun tilde(n: Int, modifiers: Int): String {
        return if (modifiers == 0) "\u001B[$n~" else "\u001B[$n;${1 + modifiers}~"
    }

    private fun function(plain: String, prefix: Int, n: Int, modifiers: Int): String {
        return if (modifiers == 0) plain else "\u001B[$n;${1 + modifiers}~"
    }

    private fun modified(prefix: String, modifiers: Int, final: Char): String {
        return "$prefix;${1 + modifiers}$final"
    }
}
