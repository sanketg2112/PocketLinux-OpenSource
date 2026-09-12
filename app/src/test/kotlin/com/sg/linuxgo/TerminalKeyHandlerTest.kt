package com.sg.linuxgo

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeyHandlerTest {

    @Test
    fun applicationCursorUsesSS3() {
        val normal = TerminalKeyHandler.sequenceFor(
            KeyEvent.KEYCODE_DPAD_UP, 0, applicationCursor = false, applicationKeypad = false
        )
        val app = TerminalKeyHandler.sequenceFor(
            KeyEvent.KEYCODE_DPAD_UP, 0, applicationCursor = true, applicationKeypad = false
        )
        assertEquals("\u001B[A", normal)
        assertEquals("\u001BOA", app)
    }

    @Test
    fun ctrlModifierUsesXtermForm() {
        val seq = TerminalKeyHandler.sequenceFor(
            KeyEvent.KEYCODE_DPAD_LEFT,
            TerminalKeyHandler.MOD_CTRL,
            applicationCursor = false,
            applicationKeypad = false
        )
        assertEquals("\u001B[1;5D", seq)
    }

    @Test
    fun extraKeyNameRespectsApplicationCursor() {
        assertEquals("\u001B[A", TerminalKeyHandler.extraKeyName("UP", false))
        assertEquals("\u001BOA", TerminalKeyHandler.extraKeyName("UP", true))
    }

    @Test
    fun wrapPasteBracketsWhenEnabled() {
        val raw = "ls\n"
        assertEquals("ls\r", TerminalKeyHandler.wrapPaste(raw, false))
        assertEquals("\u001B[200~ls\r\u001B[201~", TerminalKeyHandler.wrapPaste(raw, true))
    }
}
