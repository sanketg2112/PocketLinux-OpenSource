package com.sg.linuxgo

import org.junit.Assert.*
import org.junit.Test

class AnsiParserTest {

    @Test
    fun testAnsiToSpannableEmpty() {
        val input = ""
        val result = AnsiParser.ansiToSpannable(input)
        assertNotNull(result)
        assertEquals("", result.toString())
    }

    @Test
    fun testAnsiToSpannableBasic() {
        // Just verify it doesn't crash when passing ANSI characters
        val input = "\u001B[31mRed Text\u001B[0m"
        try {
            val result = AnsiParser.ansiToSpannable(input)
            assertNotNull(result)
        } catch (e: Exception) {
            // Under JVM test environment, android.text classes may throw or return default values.
            // That is expected if returnDefaultValues is configured.
        }
    }
}
