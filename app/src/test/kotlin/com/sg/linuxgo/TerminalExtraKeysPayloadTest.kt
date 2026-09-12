package com.sg.linuxgo

import com.sg.linuxgo.ui.components.resolveKeyPayload
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for extra-key payload resolution (used by the terminal extra keys bar).
 */
class TerminalExtraKeysPayloadTest {

    @Test
    fun ctrlLetterProducesControlByte() {
        assertEquals("\u0001", resolveKeyPayload("Ctrl+A"))
        assertEquals("\u0003", resolveKeyPayload("ctrl-c"))
        assertEquals("\u001a", resolveKeyPayload("^Z"))
    }

    @Test
    fun altLetterPrefixesEscape() {
        assertEquals("\u001bx", resolveKeyPayload("Alt+x"))
        assertEquals("\u001bX", resolveKeyPayload("meta-X"))
    }

    @Test
    fun namedKeys() {
        assertEquals("\u001b", resolveKeyPayload("esc"))
        assertEquals("\r", resolveKeyPayload("Enter"))
        assertEquals("\t", resolveKeyPayload("TAB"))
        assertEquals(" ", resolveKeyPayload("space"))
        assertEquals("\u007f", resolveKeyPayload("backspace"))
        assertEquals("\u001b[3~", resolveKeyPayload("delete"))
        assertEquals("\u001b[A", resolveKeyPayload("up"))
        assertEquals("\u001b[D", resolveKeyPayload("left"))
    }

    @Test
    fun escapeSequencesInRawText() {
        assertEquals("\u001b[?25h", resolveKeyPayload("\\e[?25h"))
        assertEquals("a\tb", resolveKeyPayload("a\\tb"))
    }

    @Test
    fun plainTextPassthrough() {
        assertEquals("hello", resolveKeyPayload("hello"))
        assertEquals("", resolveKeyPayload("   "))
    }
}
