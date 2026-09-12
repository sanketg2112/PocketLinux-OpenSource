package com.sg.linuxgo

/**
 * One-producer / one-consumer circular byte buffer.
 * Writer blocks when full; reader blocks when empty (optional).
 */
class TerminalByteQueue(capacity: Int) {
    private val buffer = ByteArray(capacity.coerceAtLeast(64))
    private var head = 0
    private var stored = 0
    private var open = true

    @Synchronized
    fun close() {
        open = false
        (this as Object).notifyAll()
    }

    @Synchronized
    fun isOpen(): Boolean = open

    /**
     * @return bytes copied, 0 if empty and [block] is false, -1 if closed and empty.
     */
    @Synchronized
    fun read(dst: ByteArray, block: Boolean): Int {
        while (stored == 0 && open) {
            if (!block) return 0
            try {
                (this as Object).wait()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0
            }
        }
        if (!open && stored == 0) return -1

        val cap = buffer.size
        val wasFull = stored == cap
        var remaining = dst.size
        var offset = 0
        var total = 0
        while (remaining > 0 && stored > 0) {
            val run = minOf(cap - head, stored)
            val n = minOf(remaining, run)
            System.arraycopy(buffer, head, dst, offset, n)
            head += n
            if (head >= cap) head = 0
            stored -= n
            remaining -= n
            offset += n
            total += n
        }
        if (wasFull) (this as Object).notifyAll()
        return total
    }

    /**
     * @return false if the queue was closed before the write finished.
     */
    fun write(src: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0) return open
        require(offset >= 0 && offset + length <= src.size)
        var off = offset
        var left = length
        val cap = buffer.size
        synchronized(this) {
            while (left > 0) {
                while (stored == cap && open) {
                    try {
                        (this as Object).wait()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                if (!open) return false
                val wasEmpty = stored == 0
                val room = cap - stored
                val chunk = minOf(left, room)
                var remainingChunk = chunk
                while (remainingChunk > 0) {
                    var tail = head + stored
                    val run = if (tail >= cap) {
                        tail -= cap
                        head - tail
                    } else {
                        cap - tail
                    }
                    val n = minOf(run, remainingChunk)
                    System.arraycopy(src, off, buffer, tail, n)
                    off += n
                    remainingChunk -= n
                    stored += n
                    left -= n
                }
                if (wasEmpty) (this as Object).notifyAll()
            }
        }
        return true
    }
}
