package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTextSearchTest {

    @Test
    fun findAllIsCaseInsensitiveAndTracksLines() {
        val text = "Hello\nworld\nHELLO again"
        val matches = TerminalTextSearch.findAll(text, "hello")
        assertEquals(2, matches.size)
        assertEquals(0, matches[0].lineIndex)
        assertEquals(2, matches[1].lineIndex)
        assertEquals(emptyList<TerminalTextSearch.Match>(), TerminalTextSearch.findAll(text, ""))
        assertEquals(emptyList<TerminalTextSearch.Match>(), TerminalTextSearch.findAll("", "x"))
    }

    @Test
    fun extractSelectionClampsAndRejectsInvertedRange() {
        assertEquals("ell", TerminalTextSearch.extractSelection("Hello", 1, 4))
        assertEquals("", TerminalTextSearch.extractSelection("Hello", 4, 1))
        assertEquals("", TerminalTextSearch.extractSelection("", 0, 1))
        assertEquals("Hello", TerminalTextSearch.extractSelection("Hello", -5, 99))
    }

    @Test
    fun extractTokenAtFindsWordUnderColumn() {
        val line = "ls -la /tmp/file"
        val (token, range) = TerminalTextSearch.extractTokenAt(line, 10)
        assertEquals("/tmp/file", token)
        assertEquals(7, range.first)
        assertEquals(15, range.last)
    }

    @Test
    fun extractTokenAtPrefersLeftWhenOnWhitespace() {
        val line = "foo  bar"
        val (token, _) = TerminalTextSearch.extractTokenAt(line, 3) // first space after foo
        assertEquals("foo", token)
    }

    @Test
    fun looksLikeUrlAndNormalize() {
        assertTrue(TerminalTextSearch.looksLikeUrl("https://example.com"))
        assertTrue(TerminalTextSearch.looksLikeUrl("www.example.com"))
        assertFalse(TerminalTextSearch.looksLikeUrl("not-a-url"))
        assertEquals(
            "https://www.example.com",
            TerminalTextSearch.normalizeUrl("www.example.com")
        )
        assertEquals(
            "https://example.com/path",
            TerminalTextSearch.normalizeUrl("https://example.com/path.")
        )
    }

    @Test
    fun findUrlAtStripsTrailingPunctuation() {
        val line = "see https://example.com/docs,"
        assertEquals("https://example.com/docs", TerminalTextSearch.findUrlAt(line, 10))
        assertNull(TerminalTextSearch.findUrlAt("no url here", 2))
    }

    @Test
    fun findAllUrlSpansLeftToRight() {
        val line = "a https://a.test and www.b.test end"
        val spans = TerminalTextSearch.findAllUrlSpans(line)
        assertEquals(2, spans.size)
        assertEquals("https://a.test", spans[0].url)
        assertEquals("https://www.b.test", spans[1].url)
    }

    @Test
    fun hitTestLineReturnsTokenAndUrl() {
        val line = "open https://pocket.example/path now"
        val hit = TerminalTextSearch.hitTestLine(line, 10)
        assertEquals("https://pocket.example/path", hit.url)
        assertTrue(hit.token.contains("https://"))
        assertEquals(line.trimEnd(), hit.lineText)
    }
}
