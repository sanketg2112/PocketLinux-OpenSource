package com.sg.linuxgo

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class TerminalSession(
    val id: String,
    var title: String,
    val prootPath: String,
    val args: Array<String>,
    val env: Array<String>,
    private val terminalBridge: TerminalBridge,
    var onSessionUpdated: (() -> Unit)? = null,
    var currentDir: String = ""
) {
    var ptyFd: Int = -1
    var ptyPfd: ParcelFileDescriptor? = null
    var ptyOutputStream: FileOutputStream? = null
    var ptyInputStream: FileInputStream? = null
    var isRunning = false
    var isCommandRunning = false
    var wasAtBottomBeforeUpdate = true
    val emulator = TerminalEmulator()
    var linesInScrollbackInUi = 0
    var charWidthPx: Int = 0
    var charHeightPx: Int = 0
    var shellPid: Int = 0
        private set
    var exitStatus: Int? = null
        private set

    @Volatile
    var appearance: GuestTerminalAppearance.Resolved? = null

    init {
        emulator.onResponse = { response -> write(response) }
        emulator.onBell = { /* host may hook later */ }
    }

    var scrollbackTextLength = 0
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var waiterThread: Thread? = null
    private val processToTerminal = TerminalByteQueue(64 * 1024)
    private val terminalToProcess = TerminalByteQueue(4096)
    private val mainHandler = MainHandler()

    private val hasPendingUpdate = AtomicBoolean(false)
    private val UI_UPDATE_FAST_MS = 33L
    private val UI_UPDATE_SLOW_MS = 100L
    private val HEAVY_OUTPUT_BYTES_PER_SEC = 64 * 1024
    private var lastUpdateTime = 0L
    private var pendingUpdateRunnable: Runnable? = null
    private var bytesInWindow = 0
    private var windowStartMs = 0L

    class RenderedState(
        val content: CharSequence,
        val lines: List<AnnotatedString>,
        val cursorLine: Int,
        val scrollbackSize: Int,
        val cursorX: Int,
        val cursorY: Int,
        val isAlternateBuffer: Boolean,
        val isCursorVisible: Boolean,
        val promptLineText: String,
        val isMouseReportingEnabled: Boolean = false,
        val applicationCursorKeys: Boolean = false,
        val bracketedPaste: Boolean = false,
        val reverseVideo: Boolean = false,
        val cursorShape: TerminalCursorShape = TerminalCursorShape.BLOCK,
        val screenRows: Int = 24,
        val screenCols: Int = 80
    )

    @Volatile
    var renderedState: RenderedState? = null
        private set

    private var lastGoodTui: PublishedTuiFrame? = null
    private var tuiHoldFlushRunnable: Runnable? = null
    private val TUI_HOLD_FLUSH_MS = 1800L

    fun start() {
        try {
            val hostCwd = File(prootPath).parent ?: "/"
            val pidOut = intArrayOf(0)
            val rows = emulator.rows.coerceAtLeast(1)
            val cols = emulator.cols.coerceAtLeast(1)
            val xpixel = cols * charWidthPx.coerceAtLeast(0)
            val ypixel = rows * charHeightPx.coerceAtLeast(0)
            ptyFd = terminalBridge.spawnShell(
                prootPath, hostCwd, args, env, pidOut, rows, cols, xpixel, ypixel
            )
            shellPid = pidOut[0]
            if (ptyFd != -1) {
                try {
                    terminalBridge.setPtyUtf8Mode(ptyFd)
                } catch (_: Throwable) {
                }
                ptyPfd = ParcelFileDescriptor.adoptFd(ptyFd)
                ptyOutputStream = FileOutputStream(ptyPfd!!.fileDescriptor)
                ptyInputStream = FileInputStream(ptyPfd!!.fileDescriptor)
                isRunning = true
                startIoThreads()
            }
        } catch (e: Exception) {
            Log.e("TerminalSession", "Failed to start session $id", e)
        }
    }

    private fun currentUiIntervalMs(): Long {
        val now = System.currentTimeMillis()
        if (windowStartMs == 0L || now - windowStartMs > 1000L) {
            windowStartMs = now
            bytesInWindow = 0
        }
        return if (bytesInWindow >= HEAVY_OUTPUT_BYTES_PER_SEC) {
            UI_UPDATE_SLOW_MS
        } else {
            UI_UPDATE_FAST_MS
        }
    }

    private fun renderSnapshot() {
        try {
            synchronized(emulator) {
                val lines = renderLinesLocked()
                val isAlt = emulator.isAlternateBuffer
                val scrollbackSize = if (isAlt) 0 else emulator.getScrollbackSize()
                val liveCursorY = emulator.cursorY
                val cursorLine = if (isAlt) liveCursorY else scrollbackSize + liveCursorY
                val previousTui = lastGoodTui
                val unhealthy = isAlt &&
                    TuiFrameHealth.isCollapsedOrSunk(
                        previous = previousTui,
                        newLines = lines,
                        newRows = emulator.rows,
                        newCols = emulator.cols
                    )
                if (unhealthy && previousTui != null) {
                    renderedState = RenderedState(
                        content = "",
                        lines = previousTui.lines,
                        cursorLine = previousTui.cursorY,
                        scrollbackSize = 0,
                        cursorX = previousTui.cursorX,
                        cursorY = previousTui.cursorY,
                        isAlternateBuffer = true,
                        isCursorVisible = previousTui.isCursorVisible,
                        promptLineText = "",
                        isMouseReportingEnabled = emulator.isMouseReportingEnabled,
                        applicationCursorKeys = emulator.applicationCursorKeys,
                        bracketedPaste = emulator.bracketedPaste,
                        reverseVideo = emulator.reverseVideo,
                        cursorShape = emulator.cursorShape,
                        screenRows = previousTui.rows,
                        screenCols = previousTui.cols
                    )
                    return
                }
                if (isAlt) {
                    lastGoodTui = PublishedTuiFrame(
                        lines = lines,
                        rows = emulator.rows,
                        cols = emulator.cols,
                        isAlternateBuffer = true,
                        cursorX = emulator.cursorX,
                        cursorY = liveCursorY,
                        isCursorVisible = emulator.isCursorVisible
                    )
                    cancelTuiHoldFlush()
                } else {
                    lastGoodTui = null
                    cancelTuiHoldFlush()
                }
                renderedState = RenderedState(
                    content = "",
                    lines = lines,
                    cursorLine = cursorLine,
                    scrollbackSize = scrollbackSize,
                    cursorX = emulator.cursorX,
                    cursorY = liveCursorY,
                    isAlternateBuffer = isAlt,
                    isCursorVisible = emulator.isCursorVisible,
                    promptLineText = emulator.getLineText(liveCursorY),
                    isMouseReportingEnabled = emulator.isMouseReportingEnabled,
                    applicationCursorKeys = emulator.applicationCursorKeys,
                    bracketedPaste = emulator.bracketedPaste,
                    reverseVideo = emulator.reverseVideo,
                    cursorShape = emulator.cursorShape,
                    screenRows = emulator.rows,
                    screenCols = emulator.cols
                )
            }
        } catch (oom: OutOfMemoryError) {
            Log.w("TerminalSession", "OOM rendering session $id; dropping frame")
            try {
                System.gc()
            } catch (_: Throwable) {
            }
        }
    }

    private fun armTuiHoldFlush() {
        cancelTuiHoldFlush()
        val runnable = Runnable {
            lastGoodTui = null
            renderSnapshot()
            onSessionUpdated?.invoke()
        }
        tuiHoldFlushRunnable = runnable
        mainHandler.postDelayed(runnable, TUI_HOLD_FLUSH_MS)
    }

    private fun cancelTuiHoldFlush() {
        tuiHoldFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        tuiHoldFlushRunnable = null
    }

    private fun renderLinesLocked(): List<AnnotatedString> {
        val scrollbackSize = if (emulator.isAlternateBuffer) 0 else emulator.getScrollbackSize()
        val totalLines = ArrayList<AnnotatedString>(scrollbackSize + emulator.rows)
        val defaultFg = emulator.defaultFg
        val reverse = emulator.reverseVideo
        val canvasBg = emulator.colorScheme.defaultBg

        if (!emulator.isAlternateBuffer) {
            for (i in 0 until scrollbackSize) {
                val line = emulator.getScrollbackLine(i)
                var cached = line.cachedPresentation as? AnnotatedString
                if (cached == null) {
                    cached = TerminalLineRender.toAnnotated(line, defaultFg, reverse, canvasBg)
                    line.cachedPresentation = cached
                }
                totalLines.add(cached)
            }
        }

        for (i in 0 until emulator.rows) {
            val line = emulator.getActiveLine(i)
            totalLines.add(TerminalLineRender.toAnnotated(line, defaultFg, reverse, canvasBg))
        }
        return totalLines
    }

    fun writeMouseEvent(col: Int, row: Int, button: Int = 0, isRelease: Boolean = false, isMotion: Boolean = false) {
        val payload = synchronized(emulator) {
            if (!emulator.isMouseReportingEnabled) null
            else emulator.buildMouseEvent(col, row, button, isRelease, isMotion)
        } ?: return
        write(payload)
    }

    fun writeScrollSteps(steps: Int, col: Int = 1, row: Int = 1) {
        if (steps == 0) return
        val payload = synchronized(emulator) {
            if (!emulator.shouldForwardScrollToApp) null
            else emulator.buildScrollSequences(steps, col, row)
        } ?: return
        if (payload.isNotEmpty()) write(payload)
    }

    fun transcriptText(): String {
        val lines = renderedState?.lines ?: return ""
        return TerminalLineRender.transcriptText(lines)
    }

    private fun scheduleUiUpdate() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastUpdateTime
        val interval = currentUiIntervalMs()

        if (elapsed >= interval) {
            lastUpdateTime = now
            renderSnapshot()
            mainHandler.post {
                hasPendingUpdate.set(false)
                onSessionUpdated?.invoke()
            }
        } else if (hasPendingUpdate.compareAndSet(false, true)) {
            pendingUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
            val delay = (interval - elapsed).coerceAtLeast(1L)
            val runnable = Runnable {
                renderSnapshot()
                lastUpdateTime = System.currentTimeMillis()
                hasPendingUpdate.set(false)
                onSessionUpdated?.invoke()
            }
            pendingUpdateRunnable = runnable
            mainHandler.postDelayed(runnable, delay)
        }
    }

    private fun startIoThreads() {
        val inputStream = ptyInputStream ?: return
        val outputStream = ptyOutputStream ?: return

        readerThread = Thread({
            val buffer = ByteArray(4096)
            try {
                while (isRunning) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    if (!processToTerminal.write(buffer, 0, read)) break
                    mainHandler.sendEmptyMessage(MSG_NEW_INPUT)
                }
            } catch (e: IOException) {
                Log.d("TerminalSession", "PTY read ended for $id: ${e.message}")
            } catch (t: Throwable) {
                Log.e("TerminalSession", "Reader crash for $id", t)
            }
        }, "TerminalReader-$id").also { it.start() }

        writerThread = Thread({
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val n = terminalToProcess.read(buffer, block = true)
                    if (n < 0) return@Thread
                    if (n == 0) continue
                    outputStream.write(buffer, 0, n)
                }
            } catch (_: IOException) {
            }
        }, "TerminalWriter-$id").also { it.start() }

        if (shellPid > 0) {
            waiterThread = Thread({
                val code = try {
                    terminalBridge.waitProcess(shellPid)
                } catch (_: Throwable) {
                    0
                }
                mainHandler.sendMessage(mainHandler.obtainMessage(MSG_PROCESS_EXITED, code))
            }, "TerminalWaiter-$id").also { it.start() }
        }
    }

    fun resize(rows: Int, cols: Int) {
        val wasAlt = synchronized(emulator) { emulator.isAlternateBuffer }
        emulator.resize(rows, cols)
        if (ptyFd != -1 && isRunning) {
            val xpixel = cols * charWidthPx
            val ypixel = rows * charHeightPx
            terminalBridge.setWindowSize(ptyFd, rows, cols, xpixel, ypixel)
        }
        if (wasAlt) {
            // Keep the last rich TUI fit-scaled until the guest redraws. Publishing
            // the locally resized (wiped / sunk) alt grid is the keyboard-open flash.
            armTuiHoldFlush()
            mainHandler.post { onSessionUpdated?.invoke() }
            return
        }
        renderSnapshot()
        mainHandler.post { onSessionUpdated?.invoke() }
    }

    fun write(data: String) {
        if (data.isEmpty() || !isRunning) return
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        terminalToProcess.write(bytes, 0, bytes.size)
    }

    fun refreshRenderedState() {
        renderSnapshot()
        mainHandler.post { onSessionUpdated?.invoke() }
    }

    /** Re-announce the current PTY size so live TUIs redraw after a theme change. */
    fun notifyPtyGeometry() {
        if (ptyFd == -1 || !isRunning) return
        val cols = emulator.cols
        val rows = emulator.rows
        val xpixel = cols * charWidthPx
        val ypixel = rows * charHeightPx
        terminalBridge.setWindowSize(ptyFd, rows, cols, xpixel, ypixel)
    }

    fun writeBytes(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (length <= 0 || !isRunning) return
        terminalToProcess.write(data, offset, length)
    }

    fun close() {
        isRunning = false
        cancelTuiHoldFlush()
        lastGoodTui = null
        pendingUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingUpdateRunnable = null
        hasPendingUpdate.set(false)
        processToTerminal.close()
        terminalToProcess.close()
        if (shellPid > 0) {
            try {
                terminalBridge.killProcess(shellPid, OsConstants.SIGKILL)
            } catch (_: Throwable) {
                try {
                    Os.kill(shellPid, OsConstants.SIGKILL)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            ptyOutputStream?.close()
        } catch (_: Exception) {
        }
        try {
            ptyInputStream?.close()
        } catch (_: Exception) {
        }
        try {
            ptyPfd?.close()
        } catch (_: Exception) {
        }
        if (ptyPfd == null && ptyFd != -1) {
            try {
                terminalBridge.closeFd(ptyFd)
            } catch (_: Exception) {
                try {
                    ParcelFileDescriptor.adoptFd(ptyFd).close()
                } catch (_: Exception) {
                }
            }
        }
        ptyOutputStream = null
        ptyInputStream = null
        ptyPfd = null
        ptyFd = -1
        shellPid = -1
        renderedState = null
    }

    private inner class MainHandler : Handler(Looper.getMainLooper()) {
        private val receive = ByteArray(64 * 1024)
        private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        private val leftover = ByteArray(4)
        private var leftoverLen = 0
        private val outChars = CharBuffer.allocate(receive.size + 4)

        override fun handleMessage(msg: Message) {
            drainInput()
            if (msg.what == MSG_PROCESS_EXITED) {
                val code = msg.obj as? Int ?: 0
                exitStatus = code
                isRunning = false
                val desc = buildString {
                    append("\r\n[Process completed")
                    when {
                        code > 0 -> append(" (code $code)")
                        code < 0 -> append(" (signal ${-code})")
                    }
                    append(" — press Enter]")
                }
                try {
                    emulator.write(desc)
                } catch (_: Throwable) {
                }
                renderSnapshot()
                onSessionUpdated?.invoke()
                processToTerminal.close()
                terminalToProcess.close()
            }
        }

        private fun drainInput() {
            val n = processToTerminal.read(receive, block = false)
            if (n <= 0) return
            val now = System.currentTimeMillis()
            if (windowStartMs == 0L || now - windowStartMs > 1000L) {
                windowStartMs = now
                bytesInWindow = 0
            }
            bytesInWindow += n
            outChars.clear()
            val decodeBuf = if (leftoverLen == 0) {
                ByteBuffer.wrap(receive, 0, n)
            } else {
                val combined = ByteArray(leftoverLen + n)
                System.arraycopy(leftover, 0, combined, 0, leftoverLen)
                System.arraycopy(receive, 0, combined, leftoverLen, n)
                leftoverLen = 0
                ByteBuffer.wrap(combined)
            }
            decoder.decode(decodeBuf, outChars, false)
            if (decodeBuf.hasRemaining()) {
                leftoverLen = decodeBuf.remaining().coerceAtMost(leftover.size)
                decodeBuf.get(leftover, 0, leftoverLen)
            }
            outChars.flip()
            if (outChars.hasRemaining()) {
                try {
                    emulator.write(outChars.toString())
                } catch (oom: OutOfMemoryError) {
                    Log.w("TerminalSession", "OOM feeding emulator for $id")
                }
                scheduleUiUpdate()
            }
        }
    }

    companion object {
        private const val MSG_NEW_INPUT = 1
        private const val MSG_PROCESS_EXITED = 4
    }
}
