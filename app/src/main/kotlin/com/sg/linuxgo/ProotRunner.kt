package com.sg.linuxgo

import android.content.Context
import android.util.Log
import com.sg.linuxgo.util.TarHardlinkSafeExtract
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ProotRunner(
    private val context: Context,
    private val onLogUpdate: (String) -> Unit,
    /** Hybrid mode: SETUP/TERMINAL stay on classic; DESKTOP follows last bench. */
    purpose: ProotBinary.Purpose = ProotBinary.Purpose.GENERAL
) {
    companion object {
        private const val TAG = "ProotRunner"
        /** After the guest prints this, proot must exit soon or be force-stopped. */
        private const val INSTALL_COMPLETE_MARKER = "=== Install complete"
        /**
         * Arch's `pacman-key` leaves gpg-agent/dirmngr running under proot; the shell
         * can print Install complete and still never exit. Grace period before kill.
         */
        private const val INSTALL_COMPLETE_EXIT_GRACE_MS = 8_000L

        /**
         * Alpine bin/sh is a guest-absolute symlink to /bin/busybox; host File.exists()
         * returns false for those. Detect the symlink entry itself (NOFOLLOW).
         */
        private fun hasGuestShell(rootFsPath: String): Boolean {
            val root = File(rootFsPath)
            return TarHardlinkSafeExtract.hasGuestShell(root)
        }
    }

    /** Resolved at construction so a single setup/launch uses one runtime consistently. */
    private val runtime: ProotBinary.Runtime = ProotBinary.resolve(context, purpose)
    private val nativeLibDir: String = runtime.nativeLibDir
    private val prootPath: String = runtime.launcherPath

    @Volatile var activeProcess: Process? = null
        private set

    /** Set when guest stdout contains [INSTALL_COMPLETE_MARKER] (setup scripts only). */
    private val installCompleteSeen = AtomicBoolean(false)
    private val installCompleteAtMs = AtomicLong(0L)

    fun destroyActiveProcess() {
        try {
            activeProcess?.destroyForcibly()
        } catch (_: Exception) {
            try {
                activeProcess?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy active process", e)
            }
        }
        activeProcess = null
    }

    init {
        val prootFile = File(prootPath)
        if (!prootFile.exists()) {
            val error = "FATAL: rootless runtime not found at $prootPath"
            onLogUpdate("! $error")
            Log.e(TAG, error)
            throw IllegalStateException(error)
        }
        prootFile.setExecutable(true, false)
        runtime.loaderPath?.let { path ->
            File(path).takeIf { it.exists() }?.setExecutable(true, false)
        }
        if (runtime.engine == ProotBinary.Engine.TAWCROOT) {
            onLogUpdate("Using experimental tawcroot (systrap) runtime")
        }
    }

    /**
     * Set ONLY host-side PRoot / tawcroot environment variables.
     * Guest environment is set via /usr/bin/env -i in the command array
     * to prevent Android host vars (LD_LIBRARY_PATH, etc.) from leaking
     * into the guest and breaking musl's dynamic linker.
     */
    private fun buildHostEnv(processBuilder: ProcessBuilder) {
        // tawcroot: clear Android env so LD_LIBRARY_PATH / ANDROID_* never
        // reach the guest ELF loader. Guest vars only via env -i after `--`.
        ProotBinary.configureProcessBuilderEnv(processBuilder.environment(), context, runtime)
    }

    /**
     * Host process cwd for tawcroot must reverse-translate cleanly (otherwise
     * guest getcwd() fails with ENOENT and shells print "getcwd() failed").
     */
    private fun prepareProcessBuilder(
        processBuilder: ProcessBuilder,
        hostTmpDir: String? = null,
        rootFsPath: String? = null
    ) {
        buildHostEnv(processBuilder)
        if (!runtime.useTawcroot) return
        val candidates = listOfNotNull(
            hostTmpDir?.let { File(it) },
            rootFsPath?.let { File(it) },
            context.cacheDir
        )
        val dir = candidates.firstOrNull { it.isDirectory } ?: context.cacheDir
        processBuilder.directory(dir)
    }

    /**
     * Build the PRoot command with all bindings.
     * Uses /usr/bin/env -i to create a completely clean guest environment,
     * matching how Termux's proot-distro isolates the guest from Android.
     *
     * @param rootFsPath Path to the guest rootfs
     * @param workingDir Guest working directory
     * @param guestExtraEnv Additional guest environment variables
     * @param hostTmpBind Optional host directory to bind-mount as /tmp in the guest.
     *                    When set, PRoot's --link2symlink can properly intercept link()
     *                    calls in /tmp, fixing Xvnc lock file creation.
     */
    /**
     * @param changeId guest credentials as "uid:gid" (proot -i). When set and not
     *   "0:0", the guest appears as that user instead of root (-0). Required for
     *   XFCE/GUI so getuid/getpwuid report the real desktop user. Terminal keeps -0
     *   so apt/sudo still work under Android NO_NEW_PRIVS.
     */
    private fun prootCommand(
        rootFsPath: String,
        workingDir: String = "/root",
        guestExtraEnv: Map<String, String> = emptyMap(),
        hostTmpBind: String? = null,
        extraBinds: Map<String, String> = emptyMap(),
        bindGpuDevices: Boolean = true,
        changeId: String? = null
    ): MutableList<String> {
        // tawcroot CLI: -r ROOT [-b SRC:DST[:ro]]... -- CMD  (no -0/-w/--link2symlink)
        if (runtime.useTawcroot) {
            return tawcrootCommand(
                rootFsPath, workingDir, guestExtraEnv, hostTmpBind, extraBinds, bindGpuDevices
            )
        }

        // Keep the bind set minimal — extra /proc overlays and --kill-on-exit
        // made every GUI launch slower/glitchier after the P0–P2 pass.
        val cmd = mutableListOf(
            prootPath,
            "--link2symlink"
        )
        // Identity: classic proot supports -i uid:gid for desktop sessions.
        val id = changeId?.trim().orEmpty()
        if (id.isNotEmpty() && id != "0:0" && id != "0") {
            cmd.add("-i")
            cmd.add(id)
        } else {
            cmd.add("-0")
        }
        val hostGuest = runtime.requiresHostGuestBinds
        fun addBind(spec: String) {
            cmd.add("-b")
            cmd.add(ProotBinary.bindSpec(spec, hostGuest))
        }
        fun addBind(host: String, guest: String) {
            cmd.add("-b")
            cmd.add("$host:$guest")
        }
        cmd.addAll(listOf("-r", rootFsPath))
        addBind("/dev")
        addBind("/dev/urandom", "/dev/urandom")
        addBind("/dev/null", "/dev/null")
        addBind("/proc")
        addBind("/sys")
        LinuxIsolation.phoneStorageBindSpec(context)?.let { spec ->
            val parts = spec.split(":", limit = 2)
            if (parts.size == 2) addBind(parts[0], parts[1])
        }

        // Host /dev/shm might not exist or be restricted on Android
        if (java.io.File("/dev/shm").exists()) {
            addBind("/dev/shm", "/dev/shm")
        } else {
            val shmDir = java.io.File(context.cacheDir, "shm")
            if (!shmDir.exists()) shmDir.mkdirs()
            addBind(shmDir.absolutePath, "/dev/shm")
        }

        cmd.add("--sysvipc")
        if (hostTmpBind != null) {
            addBind(hostTmpBind, "/tmp")
        } else {
            val x11SocketDir = java.io.File(context.cacheDir, ".X11-unix")
            if (!x11SocketDir.exists()) x11SocketDir.mkdirs()
            addBind(x11SocketDir.absolutePath, "/tmp/.X11-unix")
        }
        if (bindGpuDevices) {
            val gpuNodes = listOf("/dev/kgsl-3d0", "/dev/mali0", "/dev/dri")
            for (node in gpuNodes) {
                if (java.io.File(node).exists()) {
                    addBind(node, node)
                }
            }
        }

        val hybrisBinds = hybrisDirectoryBinds(rootFsPath)
        for ((host, guest) in hybrisBinds) {
            addBind(host, guest)
        }

        for ((host, guest) in extraBinds) {
            if (hybrisBinds.containsKey(host)) continue
            addBind(host, guest)
        }

        val defaultHome = guestExtraEnv["HOME"]
            ?: if (workingDir == "/" || workingDir.isBlank()) "/root" else workingDir
        val defaultUser = guestExtraEnv["USER"]
            ?: guestExtraEnv["POCKETLINUX_USERNAME"]
            ?: "root"
        cmd.addAll(listOf(
            "-w", workingDir,
            "/usr/bin/env", "-i",
            "HOME=$defaultHome",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "USER=$defaultUser",
            "LOGNAME=$defaultUser",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PWD=${guestExtraEnv["PWD"] ?: workingDir}"
        ))
        val hybrisEnv = hybrisGuestEnv(rootFsPath, guestExtraEnv)
        for ((key, value) in hybrisEnv) {
            cmd.add("$key=$value")
        }
        return cmd
    }

    /**
     * tawcroot argv: libtawcroot.so -r ROOT -b host:guest... -- /usr/bin/env -i … [cmd…]
     * Hardlink symlink fallback and fake-root are built in; no /dev/shm bind needed
     * (in-process memfd shm). Working directory is applied by callers via
     * [ProotBinary.withGuestWorkingDir] when they append the guest program, or via
     * PWD + a portable sh chdir wrapper for the env tail when workdir ≠ /.
     */
    private fun tawcrootCommand(
        rootFsPath: String,
        workingDir: String,
        guestExtraEnv: Map<String, String>,
        hostTmpBind: String?,
        extraBinds: Map<String, String>,
        bindGpuDevices: Boolean
    ): MutableList<String> {
        // Prefer setsid so desktop children get a clean session (matches tawc).
        val cmd = mutableListOf<String>()
        val setsid = File("/system/bin/setsid")
        if (setsid.canExecute()) {
            cmd.add(setsid.absolutePath)
        }
        // -r must be absolute; tawcroot rejects non-absolute rootfs (exit 80).
        val rootAbs = File(rootFsPath).absolutePath
        cmd.add(prootPath)
        cmd.add("-r")
        cmd.add(rootAbs)

        fun canBindSrc(host: String): Boolean {
            // tawcroot opens bind SRC with O_DIRECTORY — files (e.g. libapk.so) always fail.
            return try {
                val f = File(host)
                f.exists() && f.isDirectory
            } catch (_: Exception) {
                false
            }
        }
        fun addBind(host: String, guest: String, ro: Boolean = false) {
            if (!canBindSrc(host)) {
                val f = File(host)
                if (f.isFile) {
                    Log.w(TAG, "tawcroot: skip file bind (dirs only): $host -> $guest")
                    onLogUpdate("tawcroot: skipped file bind $guest (directory binds only)")
                } else {
                    Log.w(TAG, "tawcroot: skip bind (missing/not a dir): $host")
                }
                return
            }
            cmd.add("-b")
            cmd.add(if (ro) "$host:$guest:ro" else "$host:$guest")
        }
        fun addBindSame(path: String) = addBind(path, path)

        // Essential kernel views — directories only (tawcroot O_DIRECTORY).
        if (canBindSrc("/dev")) addBindSame("/dev")
        if (canBindSrc("/proc")) addBindSame("/proc")
        if (canBindSrc("/sys")) addBindSame("/sys")
        if (LinuxIsolation.shouldBindPhoneStorage(context)) {
            when {
                canBindSrc("/sdcard") -> addBind("/sdcard", "/sdcard")
                canBindSrc("/storage/emulated/0") -> addBind("/storage/emulated/0", "/sdcard")
            }
        }

        if (hostTmpBind != null) {
            File(hostTmpBind).mkdirs()
            File(hostTmpBind, ".X11-unix").mkdirs()
            addBind(hostTmpBind, "/tmp")
        } else {
            val x11SocketDir = File(context.cacheDir, ".X11-unix")
            if (!x11SocketDir.exists()) x11SocketDir.mkdirs()
            addBind(x11SocketDir.absolutePath, "/tmp/.X11-unix")
        }

        if (bindGpuDevices) {
            for (node in listOf("/dev/kgsl-3d0", "/dev/mali0", "/dev/dri")) {
                if (canBindSrc(node)) addBindSame(node)
            }
        }
        val hybrisBinds = hybrisDirectoryBinds(rootFsPath)
        for ((host, guest) in hybrisBinds) {
            addBind(host, guest, ro = true)
        }
        for ((host, guest) in extraBinds) {
            if (hybrisBinds.containsKey(host)) continue
            addBind(host, guest)
        }

        val defaultHome = guestExtraEnv["HOME"]
            ?: if (workingDir == "/" || workingDir.isBlank()) "/root" else workingDir
        val defaultUser = guestExtraEnv["USER"]
            ?: guestExtraEnv["POCKETLINUX_USERNAME"]
            ?: "root"
        val pwd = guestExtraEnv["PWD"] ?: workingDir

        // Guest isolation: only keys we list (plus guestExtraEnv) reach the shell.
        cmd.add("--")
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        cmd.add("HOME=$defaultHome")
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        cmd.add("USER=$defaultUser")
        cmd.add("LOGNAME=$defaultUser")
        cmd.add("TERM=xterm-256color")
        cmd.add("LANG=C.UTF-8")
        cmd.add("LC_ALL=C.UTF-8")
        cmd.add("TMPDIR=/tmp")
        cmd.add("PWD=$pwd")
        // Launch script uses this to pick tawcroot-stable DE paths (mini-session).
        cmd.add("POCKETLINUX_RUNTIME=tawcroot")
        // Inject caller vars; skip empty values and avoid clobbering TMPDIR with "".
        val hybrisEnv = hybrisGuestEnv(rootFsPath, guestExtraEnv)
        for ((key, value) in hybrisEnv) {
            if (key.isBlank() || value.isEmpty()) continue
            if (key == "POCKETLINUX_RUNTIME") continue
            cmd.add("$key=$value")
        }
        return cmd
    }

    private fun hybrisDirectoryBinds(rootFsPath: String): Map<String, String> {
        // X11 desktop uses the container GPU picker, not libhybris.
        if (runtime.purpose == ProotBinary.Purpose.DESKTOP) return emptyMap()
        val root = File(rootFsPath)
        if (!LibhybrisRuntime.shouldApply(context, root)) return emptyMap()
        return LibhybrisRuntime.directoryBinds(context).associate { it.first to it.second }
    }

    private fun hybrisGuestEnv(
        rootFsPath: String,
        guestExtraEnv: Map<String, String>
    ): Map<String, String> {
        if (!LibhybrisRuntime.shouldApply(context, File(rootFsPath))) return guestExtraEnv
        return LibhybrisRuntime.mergeGuestEnv(guestExtraEnv)
    }

    /**
     * Append guest program for tawcroot, applying portable chdir when needed.
     * Callers of [prootCommand] that use tawcroot should prefer this helper.
     */
    private fun MutableList<String>.appendTawcrootGuest(
        workingDir: String,
        guestArgv: List<String>
    ) {
        if (!runtime.useTawcroot) {
            addAll(guestArgv)
            return
        }
        addAll(ProotBinary.withGuestWorkingDir(workingDir, guestArgv))
    }

    fun executeSetupScript(
        rootFsPath: String,
        scriptPath: String,
        extraEnv: Map<String, String> = emptyMap(),
        extraBinds: Map<String, String> = emptyMap()
    ) {
        if (!hasGuestShell(rootFsPath)) {
            val shFile = File(rootFsPath, "bin/sh")
            val msg = "bin/sh not found at ${shFile.absolutePath}. Rootfs extraction failed."
            onLogUpdate("! CRITICAL ERROR: $msg")
            Log.e(TAG, msg)
            throw IllegalStateException(msg)
        }

        val hasStdbuf = File(rootFsPath, "usr/bin/stdbuf").exists() || File(rootFsPath, "bin/stdbuf").exists()
        val guestProg = buildList {
            if (hasStdbuf) {
                add("stdbuf"); add("-oL"); add("-eL")
            }
            add("/bin/sh")
            add(scriptPath)
        }
        val command = prootCommand(rootFsPath, guestExtraEnv = extraEnv, extraBinds = extraBinds).apply {
            appendTawcrootGuest("/root", guestProg)
        }

        onLogUpdate("PRoot CMD: ${command.joinToString(" ")}")
        Log.d(TAG, "Executing: ${command.joinToString(" ")}")

        installCompleteSeen.set(false)
        installCompleteAtMs.set(0L)

        try {
            val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
            prepareProcessBuilder(processBuilder, rootFsPath = rootFsPath)
            val process = processBuilder.start()
            activeProcess = process

            val readerThread = Thread {
                try {
                    pumpCrAwareLogStream(process.inputStream, trackInstallComplete = true)
                } catch (e: Exception) {
                    onLogUpdate("! Log stream closed: ${e.message}")
                    Log.w(TAG, "Log stream closed: ${e.message}")
                }
            }
            readerThread.start()

            // Arch pacman-key leaves gpg-agent under proot; shell may never exit after
            // printing "=== Install complete ===". Detect that marker and force-stop.
            val exitCode = waitForSetupProcess(process)
            readerThread.join(3000)

            if (exitCode != 0) {
                onLogUpdate("! PRoot exited with code: $exitCode")
                Log.e(TAG, "PRoot exited with code: $exitCode")
                if (!installCompleteSeen.get()) {
                    throw IllegalStateException(
                        "Setup script failed (exit $exitCode). Delete the container and retry."
                    )
                }
            } else {
                onLogUpdate("✓ PRoot execution finished successfully")
                Log.d(TAG, "PRoot execution finished successfully")
            }
        } catch (e: Exception) {
            onLogUpdate("! Failed to execute PRoot: ${e.message}")
            Log.e(TAG, "ProcessBuilder failed", e)
            throw e
        } finally {
            activeProcess = null
            installCompleteSeen.set(false)
        }
    }

    /**
     * Wait for [process] to exit. If the setup script already printed the install-
     * complete marker but proot is still alive past the grace period (orphaned
     * gpg-agent/dirmngr under Arch), destroy the process so the UI can finalize.
     */
    private fun waitForSetupProcess(process: Process): Int {
        while (true) {
            if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                return process.exitValue()
            }
            if (!installCompleteSeen.get()) continue
            val seenAt = installCompleteAtMs.get()
            if (seenAt <= 0L) continue
            val waited = System.currentTimeMillis() - seenAt
            if (waited < INSTALL_COMPLETE_EXIT_GRACE_MS) continue

            onLogUpdate(
                "! Install script finished but PRoot is still running " +
                    "(likely leftover gpg-agent from pacman-key). Stopping proot so setup can complete..."
            )
            Log.w(TAG, "Force-stopping proot after install-complete hang (${waited}ms)")
            try {
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "destroy() failed: ${e.message}")
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                try {
                    process.destroyForcibly()
                } catch (e: Exception) {
                    Log.w(TAG, "destroyForcibly() failed: ${e.message}")
                }
                process.waitFor(2, TimeUnit.SECONDS)
            }
            return try {
                process.exitValue()
            } catch (_: IllegalThreadStateException) {
                -1
            }
        }
    }

    /**
     * Read process stdout with proper in-place progress handling.
     *
     * curl/wget/pacman update a single progress line using CR (`\r`), and finish
     * with LF (`\n`). [java.io.BufferedReader.readLine] drops `\r`, so every frame
     * becomes a new log line. This pump:
     *  - First CR frame → emit as a normal new line (open a progress row)
     *  - Later CR frames → emit with leading `\r` so the UI replaces that row
     *  - Final LF after progress → emit as replace (not a new line)
     *  - Bare `\r\n` after a CR frame → finalize the row without emitting a blank line
     */
    private fun noteInstallCompleteIfNeeded(content: String, trackInstallComplete: Boolean) {
        if (!trackInstallComplete) return
        if (!content.contains(INSTALL_COMPLETE_MARKER)) return
        if (installCompleteSeen.compareAndSet(false, true)) {
            installCompleteAtMs.set(System.currentTimeMillis())
            Log.i(TAG, "Install-complete marker seen in guest output")
        }
    }

    private fun pumpCrAwareLogStream(
        stream: java.io.InputStream,
        trackInstallComplete: Boolean = false
    ) {
        stream.use { input ->
            val sb = StringBuilder()
            val buf = ByteArray(8192)
            // True after the previous char was CR (for detecting CR-LF pairs).
            var lastWasCr = false
            // True while an in-place progress row is open in the UI log.
            var progressLineOpen = false

            fun emitProgressFrame(content: String) {
                if (content.isEmpty()) return
                noteInstallCompleteIfNeeded(content, trackInstallComplete)
                if (progressLineOpen) {
                    // Subsequent frame: mark for in-place replace.
                    onLogUpdate("\r$content")
                    Log.d("PRoot", "\r$content")
                } else {
                    // First frame: open a new row; later frames will replace it.
                    onLogUpdate(content)
                    Log.d("PRoot", content)
                    progressLineOpen = true
                }
            }

            fun emitNormalLine(content: String) {
                noteInstallCompleteIfNeeded(content, trackInstallComplete)
                onLogUpdate(content)
                if (content.isNotEmpty()) Log.d("PRoot", content)
                progressLineOpen = false
            }

            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    when (val c = buf[i].toInt().toChar()) {
                        '\r' -> {
                            if (sb.isNotEmpty()) {
                                emitProgressFrame(sb.toString())
                                sb.clear()
                            }
                            lastWasCr = true
                        }
                        '\n' -> {
                            if (lastWasCr && sb.isEmpty()) {
                                // CR-LF after a progress frame: row already emitted on CR.
                                // Finalize so the next text starts a new log line.
                                lastWasCr = false
                                progressLineOpen = false
                            } else {
                                val content = sb.toString()
                                sb.clear()
                                lastWasCr = false
                                if (progressLineOpen) {
                                    // Final progress value terminated by LF (e.g. 100% then \n).
                                    if (content.isNotEmpty()) {
                                        onLogUpdate("\r$content")
                                        Log.d("PRoot", "\r$content")
                                    }
                                    progressLineOpen = false
                                } else {
                                    emitNormalLine(content)
                                }
                            }
                        }
                        else -> {
                            lastWasCr = false
                            sb.append(c)
                        }
                    }
                }
            }
            // Flush trailing content without a terminator.
            if (sb.isNotEmpty()) {
                val content = sb.toString()
                if (progressLineOpen) {
                    onLogUpdate("\r$content")
                    Log.d("PRoot", "\r$content")
                } else {
                    emitNormalLine(content)
                }
            }
        }
    }

    /**
     * Execute the launch.sh script with /tmp bind-mounted from a host directory.
     * This is critical for Xvnc which creates lock files in /tmp using link()
     * (hardlink). PRoot's --link2symlink can only intercept link() when the
     * target path is on a filesystem PRoot controls — which means /tmp must
     * be bind-mounted from the host, not live inside the rootfs.
     *
     * @param rootFsPath Path to the guest rootfs
     * @param scriptPath Guest path to the launch script
     * @param hostTmpDir Host directory to bind as /tmp
     * @param extraEnv Additional guest environment variables
     */
    fun executeLaunchScript(
        rootFsPath: String,
        scriptPath: String,
        hostTmpDir: String,
        extraEnv: Map<String, String> = emptyMap()
    ) {
        if (!hasGuestShell(rootFsPath)) {
            val shFile = File(rootFsPath, "bin/sh")
            onLogUpdate("! CRITICAL ERROR: bin/sh not found at ${shFile.absolutePath}")
            Log.e(TAG, "bin/sh not found at ${shFile.absolutePath}")
            return
        }

        val hasStdbuf = File(rootFsPath, "usr/bin/stdbuf").exists() || File(rootFsPath, "bin/stdbuf").exists()
        val guestProg = buildList {
            if (hasStdbuf) {
                add("stdbuf"); add("-oL"); add("-eL")
            }
            add("/bin/sh")
            add(scriptPath)
        }
        val command = prootCommand(
            rootFsPath,
            guestExtraEnv = extraEnv,
            hostTmpBind = hostTmpDir
        ).apply {
            appendTawcrootGuest("/root", guestProg)
        }

        onLogUpdate("PRoot CMD: ${command.joinToString(" ")}")
        Log.d(TAG, "Launch executing: ${command.joinToString(" ")}")

        try {
            val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
            prepareProcessBuilder(processBuilder, hostTmpDir = hostTmpDir, rootFsPath = rootFsPath)
            val process = processBuilder.start()

            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            onLogUpdate(line ?: "")
                            Log.d("PRoot-Launch", line ?: "")
                        }
                    }
                } catch (e: Exception) {
                    onLogUpdate("! Log stream closed: ${e.message}")
                    Log.w(TAG, "Log stream closed: ${e.message}")
                }
            }
            readerThread.start()

            val exitCode = process.waitFor()
            readerThread.join(3000)

            if (exitCode != 0) {
                onLogUpdate("! ${runtimeLabel()} exited with code: $exitCode${tawcrootExitHint(exitCode)}")
                Log.e(TAG, "Launch exited with code: $exitCode")
            } else {
                onLogUpdate("✓ ${runtimeLabel()} launch finished successfully")
                Log.d(TAG, "Launch finished successfully")
            }
        } catch (e: Exception) {
            onLogUpdate("! Failed to launch ${runtimeLabel()}: ${e.message}")
            Log.e(TAG, "ProcessBuilder launch failed", e)
        }
    }

    private fun runtimeLabel(): String = when (runtime.engine) {
        ProotBinary.Engine.TAWCROOT -> "tawcroot"
        ProotBinary.Engine.CLASSIC -> "PRoot"
    }

    /** tawcroot main.c exit codes for bootstrap failures. */
    private fun tawcrootExitHint(code: Int): String {
        if (!runtime.useTawcroot) return ""
        return when (code) {
            80 -> " (rootfs path must be absolute)"
            84 -> " (malformed -b bind spec)"
            86 -> " (PR_SET_NO_NEW_PRIVS failed)"
            88 -> " (seccomp filter install failed)"
            89 -> " (too many trapped syscalls)"
            93 -> " (bind source missing/unreadable — check /sdcard or GPU nodes)"
            else -> ""
        }
    }

    /**
     * Launch the VNC script ASYNCHRONOUSLY — does NOT block the calling thread.
     *
     * Monitors stdout for "LINUXGO_VNC_READY:<port>" and calls [onReady]
     * when the VNC + websockify pipeline is confirmed running. This allows
     * the Android app to surface the GUI screen at exactly the right time.
     *
     * @return The started Process, which the caller must keep alive and destroy
     *         when the app exits or the user requests a restart.
     */
    fun launchAsync(
        rootFsPath: String,
        scriptPath: String,
        hostTmpDir: String,
        extraEnv: Map<String, String> = emptyMap(),
        onReady: (webPort: Int) -> Unit
    ): Process? {
        if (!hasGuestShell(rootFsPath)) {
            val shFile = File(rootFsPath, "bin/sh")
            onLogUpdate("! CRITICAL ERROR: bin/sh not found at ${shFile.absolutePath}")
            Log.e(TAG, "bin/sh not found at ${shFile.absolutePath}")
            return null
        }

        val command = prootCommand(
            rootFsPath,
            guestExtraEnv = extraEnv,
            hostTmpBind = hostTmpDir
        ).apply {
            appendTawcrootGuest("/root", listOf("/bin/sh", scriptPath))
        }

        onLogUpdate("PRoot CMD: ${command.joinToString(" ")}")
        Log.d(TAG, "Async launch: ${command.joinToString(" ")}")

        return try {
            val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
            prepareProcessBuilder(processBuilder, hostTmpDir = hostTmpDir, rootFsPath = rootFsPath)
            val process = processBuilder.start()

            // Background thread: read stdout, log everything, detect readiness
            Thread {
                var readyFired = false
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: ""
                            onLogUpdate(l)
                            Log.d("PRoot-Async", l)

                            // Detect readiness marker emitted by launch.sh
                            if (!readyFired && (l.startsWith("POCKETLINUX_VNC_READY:") || l.startsWith("LINUXGO_VNC_READY:"))) {
                                readyFired = true
                                val port = l.substringAfter(":").trim().toIntOrNull() ?: 6080
                                Log.i(TAG, "VNC ready detected on web port $port")
                                onReady(port)
                            }
                        }
                    }
                } catch (e: Exception) {
                    onLogUpdate("! Log stream closed: ${e.message}")
                    Log.w(TAG, "Async log stream closed: ${e.message}")
                }
                // If we never fired ready (e.g. script crashed), log it
                if (!readyFired) {
                    onLogUpdate("! VNC readiness marker was never emitted")
                    Log.e(TAG, "VNC readiness marker never detected — launch likely failed")
                }
            }.apply { isDaemon = true; name = "proot-launch-reader" }.start()

            process
        } catch (e: Exception) {
            onLogUpdate("! Failed to launch PRoot: ${e.message}")
            Log.e(TAG, "Async launch failed", e)
            null
        }
    }

    fun launchPersistent(
        rootFsPath: String,
        scriptPath: String,
        hostTmpDir: String,
        guestExtraEnv: Map<String, String> = emptyMap(),
        extraBinds: Map<String, String> = emptyMap(),
        bindGpuDevices: Boolean = true,
        workingDir: String = "/root",
        /** Guest "uid:gid" for desktop sessions (e.g. "1000:1000"). Null = root (-0). */
        changeId: String? = null
    ): Process? {
        if (!hasGuestShell(rootFsPath)) {
            val shFile = File(rootFsPath, "bin/sh")
            onLogUpdate("! CRITICAL ERROR: bin/sh not found at ${shFile.absolutePath}")
            Log.e(TAG, "bin/sh not found at ${shFile.absolutePath}")
            return null
        }

        val command = prootCommand(
            rootFsPath,
            workingDir = workingDir,
            guestExtraEnv = guestExtraEnv,
            hostTmpBind = hostTmpDir,
            extraBinds = extraBinds,
            bindGpuDevices = bindGpuDevices,
            changeId = changeId
        ).apply {
            // Desktop: chdir into user home (tawcroot has no -w); classic uses -w already.
            appendTawcrootGuest(workingDir, listOf("/bin/sh", scriptPath))
        }

        onLogUpdate("PRoot CMD: ${command.joinToString(" ")}")
        Log.d(TAG, "Persistent launch: ${command.joinToString(" ")}")

        return try {
            val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
            prepareProcessBuilder(processBuilder, hostTmpDir = hostTmpDir, rootFsPath = rootFsPath)
            val process = processBuilder.start()

            Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: ""
                            onLogUpdate(l)
                            Log.d("PRoot-Persistent", l)
                        }
                    }
                } catch (e: Exception) {
                    onLogUpdate("! Log stream closed: ${e.message}")
                    Log.w(TAG, "Persistent log stream closed: ${e.message}")
                }
            }.apply {
                isDaemon = true
                name = "proot-persistent-reader"
            }.start()

            process
        } catch (e: Exception) {
            onLogUpdate("! Failed to launch PRoot: ${e.message}")
            Log.e(TAG, "Persistent launch failed", e)
            null
        }
    }

    /** Execute a single shell command inside the guest rootfs */
    fun executeCommand(
        rootFsPath: String,
        commandStr: String,
        workingDir: String = "/",
        extraEnv: Map<String, String> = emptyMap()
    ): Int {
        if (!hasGuestShell(rootFsPath)) {
            onLogUpdate("! CRITICAL ERROR: bin/sh not found.")
            return -1
        }

        val hasStdbuf = File(rootFsPath, "usr/bin/stdbuf").exists() || File(rootFsPath, "bin/stdbuf").exists()
        val guestProg = buildList {
            if (hasStdbuf) {
                add("stdbuf"); add("-oL"); add("-eL")
            }
            add("/bin/sh")
            add("-c")
            add(commandStr)
        }
        val command = prootCommand(rootFsPath, workingDir, guestExtraEnv = extraEnv).apply {
            appendTawcrootGuest(workingDir, guestProg)
        }

        return try {
            val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
            prepareProcessBuilder(processBuilder, rootFsPath = rootFsPath)
            val process = processBuilder.start()
            activeProcess = process

            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onLogUpdate(line ?: "")
                    Log.d("PRoot-Cmd", line ?: "")
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                onLogUpdate("! Command exited with code: $exitCode")
                Log.e(TAG, "Command exited with code: $exitCode")
            }
            exitCode
        } catch (e: Exception) {
            onLogUpdate("! Command failed: ${e.message}")
            -1
        } finally {
            activeProcess = null
        }
    }

    /** Execute a command and capture its full output as a String */
    fun runWithOutput(
        rootFsPath: String,
        commandStr: String,
        extraEnv: Map<String, String> = emptyMap()
    ): String {
        val command = prootCommand(rootFsPath, "/", guestExtraEnv = extraEnv).apply {
            appendTawcrootGuest("/", listOf("/bin/sh", "-c", commandStr))
        }

        return try {
            val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
            prepareProcessBuilder(processBuilder, rootFsPath = rootFsPath)
            val process = processBuilder.start()
            activeProcess = process
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "runWithOutput failed", e)
            ""
        } finally {
            activeProcess = null
        }
    }
}
