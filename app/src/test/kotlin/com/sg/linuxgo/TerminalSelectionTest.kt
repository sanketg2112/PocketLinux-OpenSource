package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionTest {

    @Test
    fun extractSingleLine() {
        val lines = listOf("hello world")
        val sel = TerminalSelection(
            anchor = TerminalCell(0, 0),
            focus = TerminalCell(0, 5)
        )
        assertEquals("hello", TerminalSelectionOps.extractText(lines, sel))
    }

    @Test
    fun extractMultiLine() {
        val lines = listOf("abc", "def", "ghi")
        val sel = TerminalSelection(
            anchor = TerminalCell(0, 1),
            focus = TerminalCell(2, 2)
        )
        assertEquals("bc\ndef\ngh", TerminalSelectionOps.extractText(lines, sel))
    }

    @Test
    fun extractEndAtLineStartExcludesThatLine() {
        val lines = listOf("abc", "def")
        val sel = TerminalSelection(
            anchor = TerminalCell(0, 0),
            focus = TerminalCell(1, 0)
        )
        assertEquals("abc", TerminalSelectionOps.extractText(lines, sel))
    }

    @Test
    fun wordSelectionFromHit() {
        val hit = TerminalTextSearch.hitTestLine("git status --short", 5)
        val sel = TerminalSelectionOps.selectionFromHit(0, hit)!!
        assertEquals("status", TerminalSelectionOps.extractText(listOf("git status --short"), sel))
    }

    @Test
    fun urlSelectionFromHitPrefersFullUrl() {
        val line = "mirror https://cdn.example.com/a.tgz ok"
        val hit = TerminalTextSearch.hitTestLine(line, 15)
        val sel = TerminalSelectionOps.selectionFromHit(0, hit)!!
        assertEquals("https://cdn.example.com/a.tgz", TerminalSelectionOps.extractText(listOf(line), sel))
        assertEquals("https://cdn.example.com/a.tgz", hit.url)
    }

    @Test
    fun fromPointerHitSelectsToken() {
        val lines = listOf("git status --short")
        val sel = TerminalSelectionOps.fromPointerHit(lines, 0, 5)!!
        assertEquals("status", TerminalSelectionOps.extractText(lines, sel))
    }

    @Test
    fun highlightRangeMiddleLineIsFull() {
        val sel = TerminalSelection(
            anchor = TerminalCell(0, 2),
            focus = TerminalCell(2, 1)
        )
        val mid = TerminalSelectionOps.highlightRange(sel, 1, 10)!!
        assertEquals(0, mid.first)
        assertEquals(9, mid.last)
    }

    @Test
    fun containsHalfOpen() {
        val sel = TerminalSelection(
            anchor = TerminalCell(0, 2),
            focus = TerminalCell(0, 5)
        )
        assertTrue(TerminalSelectionOps.contains(sel, 0, 2))
        assertTrue(TerminalSelectionOps.contains(sel, 0, 4))
        assertFalse(TerminalSelectionOps.contains(sel, 0, 5))
        assertFalse(TerminalSelectionOps.contains(sel, 0, 1))
    }

    @Test
    fun selectAll() {
        val lines = listOf("ab", "cd")
        val sel = TerminalSelectionOps.selectAll(lines)!!
        assertEquals("ab\ncd", TerminalSelectionOps.extractText(lines, sel))
    }

    @Test
    fun selectAllEmpty() {
        assertNull(TerminalSelectionOps.selectAll(emptyList()))
        assertNull(TerminalSelectionOps.selectAll(listOf("")))
    }

    @Test
    fun clampCell() {
        val lines = listOf("hi")
        assertEquals(TerminalCell(0, 2), TerminalSelectionOps.clampCell(lines, TerminalCell(0, 99)))
        assertEquals(TerminalCell(0, 0), TerminalSelectionOps.clampCell(lines, TerminalCell(-1, -3)))
    }

    @Test
    fun clampCellIgnoresTrailingSpaces() {
        val lines = listOf("hi          ")
        assertEquals(2, TerminalSelectionOps.contentLength(lines[0]))
        assertEquals(TerminalCell(0, 2), TerminalSelectionOps.clampCell(lines, TerminalCell(0, 80)))
    }

    @Test
    fun snapToContentSkipsBlankRows() {
        val lines = listOf("abc", "   ", "de")
        val snapped = TerminalSelectionOps.snapToContent(lines, TerminalCell(1, 5))
        assertTrue(snapped.line == 0 || snapped.line == 2)
        assertTrue(TerminalSelectionOps.contentLength(lines[snapped.line]) > 0)
    }

    @Test
    fun dragFocusExtendsSelection() {
        val lines = listOf("hello world")
        val start = TerminalSelection.wordAt(0, 0, 5)
        val next = TerminalSelectionOps.dragFocus(lines, 0, 11, start)
        assertEquals("hello world", TerminalSelectionOps.extractText(lines, next))
    }

    @Test
    fun tapUrlOrClearKeepsUrlAndClearsWord() {
        val lines = listOf("see https://example.com now")
        val urlSel = TerminalSelectionOps.tapUrlOrClear(lines, 0, 10)
        assertEquals("https://example.com", TerminalSelectionOps.extractText(lines, urlSel!!))
        assertNull(TerminalSelectionOps.tapUrlOrClear(lines, 0, 1))
        assertNull(TerminalSelectionOps.tapUrlOrClear(lines, null, null))
    }

    @Test
    fun hitLineColMapsPixelsToCells() {
        val hit = TerminalSelectionOps.hitLineCol(
            posX = 24f,
            posY = 16f,
            cellW = 8f,
            cellH = 16f,
            firstVisibleLine = 2,
            firstLinePixelOffset = 0f,
            originX = 0f,
            lineCount = 10
        )
        assertEquals(3 to 3, hit)
    }

    @Test
    fun hitLineColHonorsOriginAndScroll() {
        val hit = TerminalSelectionOps.hitLineCol(
            posX = 12f,
            posY = 4f,
            cellW = 8f,
            cellH = 16f,
            firstVisibleLine = 5,
            firstLinePixelOffset = 16f,
            originX = 4f,
            lineCount = 20
        )
        assertEquals(6 to 1, hit)
    }

    @Test
    fun hitLineColRejectsOutOfRange() {
        assertNull(
            TerminalSelectionOps.hitLineCol(
                posX = 0f,
                posY = 100f,
                cellW = 8f,
                cellH = 16f,
                firstVisibleLine = 0,
                firstLinePixelOffset = 0f,
                originX = 0f,
                lineCount = 2
            )
        )
    }
}
