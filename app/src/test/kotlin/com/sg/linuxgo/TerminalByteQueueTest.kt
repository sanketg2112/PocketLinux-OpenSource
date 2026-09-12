package com.sg.linuxgo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalByteQueueTest {

    @Test
    fun writeThenReadReturnsSameBytes() {
        val q = TerminalByteQueue(32)
        val src = "hello-pty".toByteArray()
        assertTrue(q.write(src, 0, src.size))
        val dst = ByteArray(32)
        val n = q.read(dst, block = false)
        assertEquals(src.size, n)
        assertArrayEquals(src, dst.copyOf(n))
    }

    @Test
    fun closedEmptyReadReturnsMinusOne() {
        val q = TerminalByteQueue(16)
        q.close()
        assertEquals(-1, q.read(ByteArray(8), block = false))
    }

    @Test
    fun wrapAroundPreservesOrder() {
        val q = TerminalByteQueue(8)
        assertTrue(q.write(byteArrayOf(1, 2, 3, 4, 5), 0, 5))
        val first = ByteArray(3)
        assertEquals(3, q.read(first, false))
        assertTrue(q.write(byteArrayOf(6, 7, 8, 9), 0, 4))
        val rest = ByteArray(16)
        val n = q.read(rest, false)
        assertEquals(6, n)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8, 9), rest.copyOf(n))
    }
}
