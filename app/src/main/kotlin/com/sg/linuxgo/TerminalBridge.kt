package com.sg.linuxgo

class TerminalBridge {
    companion object {
        init {
            try {
                System.loadLibrary("terminal")
            } catch (_: UnsatisfiedLinkError) {
                // Ignore in local JVM unit tests
            }
        }
    }

    /**
     * Spawn [shellPath] on a PTY. Writes the child pid into [pidOut] (index 0).
     * Returns the master fd, or -1 on failure.
     */
    external fun spawnShell(
        shellPath: String,
        cwd: String,
        args: Array<String>?,
        env: Array<String>?,
        pidOut: IntArray,
        rows: Int = 24,
        cols: Int = 80,
        xpixel: Int = 0,
        ypixel: Int = 0
    ): Int

    /** Exit code (>=0) or negated signal (<0). */
    external fun waitProcess(pid: Int): Int

    external fun setWindowSize(fd: Int, rows: Int, cols: Int, xpixel: Int = 0, ypixel: Int = 0)

    external fun setPtyUtf8Mode(fd: Int)

    external fun killProcess(pid: Int, signal: Int): Int

    external fun closeFd(fd: Int)
}
