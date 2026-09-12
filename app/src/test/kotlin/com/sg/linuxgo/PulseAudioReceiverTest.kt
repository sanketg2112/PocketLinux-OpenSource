package com.sg.linuxgo

import org.junit.Assert.*
import org.junit.Test

class PulseAudioReceiverTest {

    @Test
    fun testInitialization() {
        val receiver = PulseAudioReceiver(4711) { }
        assertNotNull(receiver)
    }

    @Test
    fun testLoggingOnStart() {
        var loggedMessage = ""
        val receiver = PulseAudioReceiver(4711) { msg ->
            loggedMessage = msg
        }
        receiver.start()
        assertEquals("🔊 Starting audio receiver (port 4711)...", loggedMessage)
        receiver.stop()
    }
}
