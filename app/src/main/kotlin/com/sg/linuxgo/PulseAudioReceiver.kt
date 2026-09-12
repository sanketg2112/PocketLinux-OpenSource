package com.sg.linuxgo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

class PulseAudioReceiver(private val port: Int, private val onLog: (String) -> Unit) {
    @Volatile private var isRunning = false
    private var thread: Thread? = null

    fun start() {
        // Allow re-start after a failed connect or stream end
        if (isRunning) return
        isRunning = true
        onLog("🔊 Starting audio receiver (port $port)...")
        thread = Thread {
            try {
                // Guest PA needs a moment for null-sink + simple-protocol-tcp
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    return@Thread
                }

                val sampleRate = 44100
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()

                var socket: Socket? = null
                try {
                    var connected = false
                    var attempts = 0
                    val maxAttempts = 40 // ~40 * 1.5s + initial 3s ≈ 60s total
                    while (isRunning && !connected && attempts < maxAttempts) {
                        try {
                            val s = Socket()
                            s.connect(InetSocketAddress("127.0.0.1", port), 1500)
                            s.tcpNoDelay = true
                            socket = s
                            connected = true
                        } catch (e: Exception) {
                            attempts++
                            if (attempts == 1 || attempts % 5 == 0) {
                                onLog("🔊 Audio: waiting for PulseAudio ($attempts/$maxAttempts)...")
                            }
                            try {
                                Thread.sleep(1500)
                            } catch (_: InterruptedException) {
                                return@Thread
                            }
                        }
                    }

                    if (!connected) {
                        Log.e(
                            "PulseAudioReceiver",
                            "Could not connect to PulseAudio simple protocol on port $port after $maxAttempts attempts"
                        )
                        onLog("⚠ Audio: could not connect to PulseAudio on port $port")
                        return@Thread
                    }

                    onLog("✓ Audio: connected to PulseAudio")
                    val inputStream = socket?.getInputStream()
                    val buffer = ByteArray(bufferSize)
                    while (isRunning) {
                        val read = inputStream?.read(buffer) ?: -1
                        if (read > 0) {
                            audioTrack.write(buffer, 0, read)
                        } else if (read == -1) {
                            onLog("⚠ Audio: stream closed by guest")
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e("PulseAudioReceiver", "Stream error: ${e.message}")
                } finally {
                    try {
                        socket?.close()
                        audioTrack.stop()
                        audioTrack.release()
                    } catch (_: Exception) {
                    }
                }
            } finally {
                isRunning = false
            }
        }.also {
            it.name = "PulseAudioReceiver"
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        isRunning = false
        thread?.interrupt()
        thread = null
    }
}
