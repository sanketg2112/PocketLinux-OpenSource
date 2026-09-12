package com.sg.linuxgo

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.sg.linuxgo.ui.screens.TerminalTheme
import java.io.File

class TerminalController(
    private val context: Context,
    private val containerManager: ContainerManager,
    private val bootstrap: Bootstrap?,
    private val terminalBridge: TerminalBridge,
    private val terminalSessionManager: TerminalSessionManager,
    private val activeContainerIdProvider: () -> String?,
    private val onSessionSelected: (TerminalSession) -> Unit,
    private val viewDelegate: TerminalViewDelegate
) {
    val terminalSessions get() = terminalSessionManager.sessions
    val activeSessionIndex get() = terminalSessionManager.activeSessionIndex
    
    private var lastRenderedSession: TerminalSession? = null
    /** Identity of last RenderedState applied to the TextView — avoids full-buffer toString() compares. */
    private var lastAppliedRendered: TerminalSession.RenderedState? = null
    private val resizeScheduler = TerminalResizeScheduler()
    private val resizeHandler = Handler(Looper.getMainLooper())
    private val applyResizeRunnable = Runnable { applySettledGridResize() }

    fun startTerminalProcess() {
        if (terminalSessions.isEmpty()) {
            addNewTerminalSession()
        }
    }

    fun addNewTerminalSession() {
        addNewTerminalSessionAt("")
    }

    fun closeActiveTerminalSession() {
        if (activeSessionIndex < 0 || activeSessionIndex >= terminalSessions.size) return
        val session = terminalSessions[activeSessionIndex]
        session.close()
        viewDelegate.getTabLayoutTerminal().removeTabAt(activeSessionIndex)
        terminalSessionManager.removeSessionAt(activeSessionIndex)

        if (terminalSessions.isEmpty()) {
            viewDelegate.releaseWakeLock()
            viewDelegate.switchToHomeTab()
        } else {
            viewDelegate.getTabLayoutTerminal().getTabAt(activeSessionIndex)?.select()
            updateTerminalUi()
        }
    }

    fun closeTerminalSessionAt(index: Int) {
        if (index < 0 || index >= terminalSessions.size) return
        val session = terminalSessions[index]
        session.close()
        viewDelegate.getTabLayoutTerminal().removeTabAt(index)
        terminalSessionManager.removeSessionAt(index)

        if (terminalSessions.isEmpty()) {
            viewDelegate.releaseWakeLock()
            viewDelegate.switchToHomeTab()
        } else {
            viewDelegate.getTabLayoutTerminal().getTabAt(activeSessionIndex)?.select()
            updateTerminalUi()
        }
    }

    fun duplicateTerminalSession(index: Int) {
        if (index < 0 || index >= terminalSessions.size) return
        val sourceSession = terminalSessions[index]
        val sourceDir = sourceSession.currentDir
        val sourceTheme = viewDelegate.getSessionThemes()[sourceSession.id]

        addNewTerminalSessionAt(sourceDir)
        
        val newSession = terminalSessions.lastOrNull()
        if (newSession != null && sourceTheme != null) {
            viewDelegate.getSessionThemes()[newSession.id] = sourceTheme
        }
    }

    fun addNewTerminalSessionAt(startDir: String) {
        val filesDirStr = context.filesDir.absolutePath
        val runtime = ProotBinary.resolve(context, ProotBinary.Purpose.TERMINAL)
        val proot = runtime.launcherPath
        val containerId = activeContainerIdProvider()
        val rootfs = if (containerId != null) {
            containerManager.getContainerRootfsPath(containerId)
        } else {
            "$filesDirStr/rootfs"
        }
        val rootfsFileForGpu = File(rootfs)
        val configuredForSetup = bootstrap?.getConfiguredGpuDriverMode(rootfsFileForGpu) ?: "auto"
        bootstrap?.setupGpuEnvConfig(rootfsFileForGpu, configuredForSetup)
        val hybrisStatus = try {
            LibhybrisInstaller.prepare(context, rootfsFileForGpu)
        } catch (e: Exception) {
            Log.w("TerminalController", "libhybris prepare: ${e.message}")
            "error"
        }
        if (hybrisStatus != "off") {
            Log.i("TerminalController", "libhybris: $hybrisStatus")
        }

        // Alpine + tawcroot: stage host apk.static into rootfs (cannot bind libapk.so).
        if (runtime.useTawcroot) {
            if (TawcrootAlpineSupport.isAlpineRootfs(rootfsFileForGpu)) {
                TawcrootAlpineSupport.stageApkStatic(context, rootfsFileForGpu)
            }
        }

        val hostTmpDir = File(context.cacheDir, "container_tmp").apply { if (!exists()) mkdirs() }
        val hostShmDir = File(context.filesDir, "container_shm").apply { if (!exists()) mkdirs() }

        val container = containerId?.let { containerManager.getContainer(it) }
        val restoreEngine = ContainerRestoreEngine(context)
        val rootfsFile = File(rootfs)
        // Prefer real /home + passwd over a stale "root" config so new installs
        // and restores open as the image's user (e.g. PocketLinux).
        val username = when {
            containerId != null -> restoreEngine.syncContainerUsername(
                rootfsFile, containerId, containerManager,
                fallback = container?.username?.takeIf { it.isNotBlank() && it != "root" }
                    ?: "PocketLinux"
            )
            else -> restoreEngine.detectGuestUsername(rootfsFile)
                ?: container?.username?.takeIf { it.isNotBlank() }
                ?: "PocketLinux"
        }
        val homeDir = if (username == "root") "/root" else "/home/$username"
        val workDir = if (startDir.isNotBlank()) startDir else homeDir

        val isHardwareAccel = bootstrap?.wantsHardwareGpuDrivers() ?: false

        // Ensure guest sudo shim + durable session identity (whoami/PS1) exist.
        // Nested host libproot.so cannot be exec'd from inside the guest on Android.
        try {
            restoreEngine.installProotHelpers(rootfsFile)
            restoreEngine.applySessionIdentity(rootfsFile, username)
            ArchPacmanSecurity.applySecureMirrorlist(rootfsFile)
        } catch (e: Exception) {
            android.util.Log.w("TerminalController", "installProotHelpers/identity: ${e.message}")
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val appThemeId = prefs.getString("terminal_theme", "default") ?: "default"
        val appTheme = TerminalTheme.fromId(appThemeId)
        val appScheme = TerminalColorScheme.fromAppChrome(
            bgArgb = appTheme.backgroundColor.toArgb(),
            fgArgb = appTheme.textColor.toArgb(),
            cursorArgb = appTheme.accentColor.toArgb(),
            themeId = appThemeId
        )
        val appearance = GuestTerminalAppearance.resolve(
            rootfs = rootfsFile,
            username = username,
            prefs = prefs,
            appScheme = appScheme
        )

        val nativeLibDir = runtime.nativeLibDir
        val apkPath = File(nativeLibDir, "libapk.so").absolutePath
        val hostGuest = runtime.requiresHostGuestBinds
        fun b(path: String) = ProotBinary.bindSpec(path, hostGuest)
        val shellBin = GuestTerminalAppearance.resolveShellBinary(rootfsFile, appearance.shell)
        val shellArgs = appearance.shell.loginArgs

        val configuredGpuDriverMode = bootstrap?.getConfiguredGpuDriverMode(rootfsFile) ?: "auto"
        val resolvedGpuDriverMode = bootstrap?.resolveGpuDriverModeForRootfs(rootfsFile, configuredGpuDriverMode)
            ?: com.sg.linuxgo.bootstrap.resolveGpuDriverMode(configuredGpuDriverMode, rootfsFile)
        // Freedreno without real Android Mesa → software. Never inject jail-only path
        // with swrast (Alpine: no swrast_dri.so in pocketlinux-dri → glmark2 dies).
        val hasRealKgsl = com.sg.linuxgo.bootstrap.rootfsHasKgslDri(rootfsFile)
        val effectiveGpuMode = when {
            resolvedGpuDriverMode == "adreno_freedreno" && !hasRealKgsl -> "llvmpipe"
            else -> resolvedGpuDriverMode
        }
        val useHybris = LibhybrisRuntime.shouldApply(context, rootfsFile)
        val terminalGpuEnv = if (useHybris) {
            LibhybrisRuntime.guestEnvPairs().map { (k, v) -> "$k=$v" }.toTypedArray()
        } else {
            com.sg.linuxgo.bootstrap.gpuGuestEnvPairs(effectiveGpuMode)
                .map { (k, v) -> "$k=$v" }
                .toTypedArray()
        }
        val hostRuntimeEnv = ProotBinary.hostEnvPairs(context, runtime)
        // Guest-safe session vars (no Android host LD_LIBRARY_PATH).
        // Do not set PS1 here — bashrc / starship / oh-my own the prompt (same as xfce4-terminal).
        val guestEnvPairs = arrayOf(
            *terminalGpuEnv,
            "MESA_DEBUG=silent",
            "PROOT_L_MT=1",
            "HOME=$homeDir",
            "USER=$username",
            "LOGNAME=$username",
            "POCKETLINUX_USERNAME=$username",
            "PWD=$workDir",
            "TMPDIR=/tmp",
            "PATH=${ContainerRestoreEngine.buildGuestPath(rootfsFile, homeDir)}",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "LC_CTYPE=C.UTF-8",
            "COLORTERM=truecolor",
            "COLORFGBG=${TerminalThemePalettes.colorFgBg(appThemeId)}",
            "POCKETLINUX_TERMINAL_THEME=$appThemeId",
            *appearance.extraEnv,
            "DEBIAN_FRONTEND=noninteractive",
            "DEBCONF_NONINTERACTIVE_SEEN=true"
        )

        val (args, env) = if (runtime.useTawcroot) {
            // tawcroot passes process envp into the guest; isolate with env -i
            // so Android host vars (LD_LIBRARY_PATH) never reach musl.
            val a = mutableListOf(
                "-r", rootfs,
                "-b", b("/dev"), "-b", b("/proc"), "-b", b("/sys"),
                "-b", "${hostTmpDir.absolutePath}:/tmp"
            )
            LinuxIsolation.phoneStorageBindSpec(context)?.let { spec ->
                a.add("-b"); a.add(spec)
            }
            // tawcroot only supports directory binds (open O_DIRECTORY). libapk.so
            // is a file — skip file binds. Alpine: host apk is staged into rootfs as
            // /sbin/apk.static via TawcrootAlpineSupport (see prepare above).
            if (isHardwareAccel && File("/dev/dri").exists()) {
                a.add("-b"); a.add(b("/dev/dri"))
            }
            if (useHybris) {
                for ((host, guest) in LibhybrisRuntime.directoryBinds(context)) {
                    if (File(host).isDirectory) {
                        a.add("-b"); a.add("$host:$guest:ro")
                    }
                }
            }
            a.add("--")
            a.add("/usr/bin/env")
            a.add("-i")
            a.addAll(guestEnvPairs)
            a.add("POCKETLINUX_RUNTIME=tawcroot")
            a.addAll(
                ProotBinary.withGuestWorkingDir(workDir, listOf(shellBin) + shellArgs)
            )
            // Host process env only: static tawcroot needs nothing special.
            a.toTypedArray() to arrayOf("TMPDIR=${context.cacheDir.absolutePath}")
        } else {
            val a = mutableListOf(
                "--link2symlink",
                "-0", "-r", rootfs,
                "-b", b("/dev"), "-b", b("/proc"), "-b", b("/sys"),
                "-b", "${hostTmpDir.absolutePath}:/tmp",
                "-b", "${hostShmDir.absolutePath}:/dev/shm"
            )
            LinuxIsolation.phoneStorageBindSpec(context)?.let { spec ->
                a.add("-b"); a.add(spec)
            }
            // Classic proot accepts file binds; host apk helper for Alpine installs.
            if (File(apkPath).isFile) {
                a.add("-b"); a.add("$apkPath:/sbin/apk")
            }
            if (isHardwareAccel && File("/dev/dri").exists()) {
                a.add("-b"); a.add(b("/dev/dri"))
            }
            if (useHybris) {
                for ((host, guest) in LibhybrisRuntime.directoryBinds(context)) {
                    if (File(host).isDirectory) {
                        a.add("-b"); a.add("$host:$guest")
                    }
                }
            }
            a.add("-w")
            a.add(workDir)
            a.add(shellBin)
            a.addAll(shellArgs)
            a.toTypedArray() to arrayOf(*hostRuntimeEnv, *guestEnvPairs)
        }

        val nextSessionNum = terminalSessions.size + 1
        val sessionId = "session_${System.currentTimeMillis()}"
        val sessionTitle = "Tab $nextSessionNum"

        val session = TerminalSession(
            id = sessionId,
            title = sessionTitle,
            prootPath = proot,
            args = args,
            env = env,
            terminalBridge = terminalBridge,
            currentDir = workDir
        )
        session.appearance = appearance
        session.emulator.applyColorScheme(appearance.scheme)
        session.emulator.cursorShape = appearance.cursorShape
        Log.i(
            "TerminalController",
            "session appearance: matchGui=${appearance.matchedGui} shell=${appearance.shell} " +
                "font=${appearance.fontFile?.name} rc=${appearance.terminalRcPath}"
        )

        val tvTerminalOutput = viewDelegate.getTerminalOutputView()
        val paint = tvTerminalOutput.paint
        val charWidth = paint.measureText("M")
        val charHeight = tvTerminalOutput.lineHeight
        session.charWidthPx = charWidth.toInt()
        session.charHeightPx = charHeight
        val terminalScroll = viewDelegate.getTerminalScrollView()
        val width = terminalScroll.width - tvTerminalOutput.paddingLeft - tvTerminalOutput.paddingRight
        val height = terminalScroll.height - tvTerminalOutput.paddingTop - tvTerminalOutput.paddingBottom

        if (width > 0 && height > 0 && charWidth > 0 && charHeight > 0) {
            val cols = (width / charWidth).toInt().coerceAtLeast(20)
            val rows = (height / charHeight).toInt().coerceAtLeast(10)
            session.resize(rows, cols)
        }

        session.onSessionUpdated = {
            if (activeSessionIndex >= 0 && activeSessionIndex < terminalSessions.size && terminalSessions[activeSessionIndex] == session) {
                updateTerminalUi()
            }
        }

        terminalSessionManager.addSession(session)
        session.start()
        containerId?.let { viewDelegate.updateSessionNotification(it, 0) }
        
        if (terminalSessions.size == 1) {
            viewDelegate.acquireWakeLock()
        }

        val tabLayoutTerminal = viewDelegate.getTabLayoutTerminal()
        val tab = tabLayoutTerminal.newTab().setText(sessionTitle)
        tabLayoutTerminal.addTab(tab)
        tab.select()
        updateTerminalUi()
    }

    fun renameTerminalSession(index: Int, newName: String) {
        if (index < 0 || index >= terminalSessions.size) return
        terminalSessionManager.renameSession(index, newName)
        viewDelegate.getTabLayoutTerminal().getTabAt(index)?.text = newName
    }

    /**
     * Re-apply appearance when the user changes theme source or app theme preset.
     * Match GUI re-reads guest terminalrc; App theme applies chrome colors.
     */
    fun applyAppThemeToSessions(themeId: String) {
        val theme = TerminalTheme.fromId(themeId)
        val appScheme = TerminalColorScheme.fromAppChrome(
            bgArgb = theme.backgroundColor.toArgb(),
            fgArgb = theme.textColor.toArgb(),
            cursorArgb = theme.accentColor.toArgb(),
            themeId = themeId
        )
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val containerId = activeContainerIdProvider()
        val rootfsPath = if (containerId != null) {
            containerManager.getContainerRootfsPath(containerId)
        } else {
            File(context.filesDir, "rootfs").absolutePath
        }
        val rootfsFile = File(rootfsPath)
        val username = try {
            if (containerId != null) {
                ContainerRestoreEngine(context).detectGuestUsername(rootfsFile)
                    ?: containerManager.getContainer(containerId)?.username
                    ?: "PocketLinux"
            } else {
                "PocketLinux"
            }
        } catch (_: Exception) {
            "PocketLinux"
        }

        if (GuestTerminalAppearance.isMatchGui(prefs) && rootfsFile.isDirectory) {
            val resolved = GuestTerminalAppearance.resolve(
                rootfs = rootfsFile,
                username = username,
                prefs = prefs,
                appScheme = appScheme
            )
            for (session in terminalSessions) {
                session.appearance = resolved
                session.emulator.applyColorScheme(resolved.scheme)
                session.emulator.cursorShape = resolved.cursorShape
                session.notifyPtyGeometry()
                session.refreshRenderedState()
            }
        } else {
            for (session in terminalSessions) {
                session.emulator.applyColorScheme(appScheme)
                val prev = session.appearance
                session.appearance = (prev ?: GuestTerminalAppearance.Resolved(
                    scheme = appScheme,
                    shell = GuestTerminalAppearance.Shell.BASH,
                    fontFile = null,
                    fontFamilyHint = null,
                    matchedGui = false,
                    terminalRcPath = null
                )).copy(scheme = appScheme, matchedGui = false, extraEnv = prev?.extraEnv ?: emptyArray())
                session.notifyPtyGeometry()
                session.refreshRenderedState()
            }
        }
        updateTerminalUi()
    }

    fun performFullRender(session: TerminalSession) {
        val tvTerminalOutput = viewDelegate.getTerminalOutputView()
        val rendered = session.renderedState
        if (rendered == null) {
            val em = session.emulator
            val isAlt = em.isAlternateBuffer
            val currentScrollbackSize = if (isAlt) 0 else em.getScrollbackSize()
            val (renderedText, absoluteCursorLine) = em.render()
            session.linesInScrollbackInUi = currentScrollbackSize
            
            if (isAlt) {
                session.scrollbackTextLength = 0
            } else {
                val sbTemp = SpannableStringBuilder()
                for (i in 0 until currentScrollbackSize) {
                    em.appendScrollbackLine(sbTemp, i)
                }
                session.scrollbackTextLength = sbTemp.length
            }
            
            // SPANNABLE keeps ANSI colors; EDITABLE makes the TextView a real editor
            // (grey Material field + steals soft-keyboard after first key).
            tvTerminalOutput.setText(renderedText, TextView.BufferType.SPANNABLE)
            positionCursor(absoluteCursorLine, session)
            return
        }

        val isAlt = rendered.isAlternateBuffer
        val currentScrollbackSize = if (isAlt) 0 else rendered.scrollbackSize
        session.linesInScrollbackInUi = currentScrollbackSize
        
        if (isAlt) {
            session.scrollbackTextLength = 0
        } else {
            session.scrollbackTextLength = rendered.content.length
        }
        
        tvTerminalOutput.setText(rendered.content, TextView.BufferType.SPANNABLE)
        lastAppliedRendered = rendered
        positionCursor(rendered.cursorLine, session)
    }

    fun updateTerminalUi() {
        if (activeSessionIndex < 0 || activeSessionIndex >= terminalSessions.size) {
            lastRenderedSession = null
            lastAppliedRendered = null
            viewDelegate.notifyTerminalOutputChanged()
            return
        }
        val session = terminalSessions[activeSessionIndex]
        if (lastRenderedSession != session) {
            lastRenderedSession = session
            lastAppliedRendered = null
            onSessionSelected(session)
        }

        val rendered = session.renderedState
        if (rendered != null) {
            val isAlt = rendered.isAlternateBuffer
            val currentScrollbackSize = if (isAlt) 0 else rendered.scrollbackSize
            if (rendered !== lastAppliedRendered) {
                lastAppliedRendered = rendered
                session.linesInScrollbackInUi = currentScrollbackSize
                session.scrollbackTextLength = if (isAlt) 0 else rendered.lines.size
            }
        }

        // Compose TerminalScreen observes this generation counter and re-reads renderedState.
        viewDelegate.notifyTerminalOutputChanged()
    }

    /**
     * Request a PTY resize from Compose cell metrics.
     *
     * Cell pixel size updates immediately. Rows/cols wait until the layout has
     * settled (IME animation) so SIGWINCH is not fired every keyboard frame.
     */
    fun resizeActiveSessionFromGrid(rows: Int, cols: Int, charWidthPx: Int, charHeightPx: Int) {
        if (activeSessionIndex < 0 || activeSessionIndex >= terminalSessions.size) return
        val session = terminalSessions[activeSessionIndex]
        if (!session.isRunning) return
        session.charWidthPx = charWidthPx.coerceAtLeast(1)
        session.charHeightPx = charHeightPx.coerceAtLeast(1)
        resizeScheduler.submit(
            sessionId = session.id,
            rows = rows,
            cols = cols,
            charWidthPx = session.charWidthPx,
            charHeightPx = session.charHeightPx
        )
        resizeHandler.removeCallbacks(applyResizeRunnable)
        val wait = resizeScheduler.remainingSettleMs()
        if (wait <= 0L) {
            applySettledGridResize()
        } else {
            resizeHandler.postDelayed(applyResizeRunnable, wait)
        }
    }

    private fun applySettledGridResize() {
        val size = resizeScheduler.takeSettled() ?: return
        val session = terminalSessions.firstOrNull { it.id == size.sessionId } ?: return
        if (!session.isRunning) return
        session.charWidthPx = size.charWidthPx
        session.charHeightPx = size.charHeightPx
        if (session.emulator.cols != size.cols || session.emulator.rows != size.rows) {
            session.resize(size.rows, size.cols)
            session.linesInScrollbackInUi = 0
            session.scrollbackTextLength = 0
            updateTerminalUi()
        }
    }

    private fun isScrollViewAtBottom(scrollView: android.widget.ScrollView): Boolean {
        val child = scrollView.getChildAt(0) ?: return true
        val diff = (child.bottom - (scrollView.height + scrollView.scrollY))
        return diff <= 40
    }

    /** Scroll to end without requesting focus on descendants (focus must stay on IME host). */
    private fun scrollToBottomPreservingFocus(scrollView: android.widget.ScrollView) {
        scrollView.post {
            val child = scrollView.getChildAt(0) ?: return@post
            val bottom = child.bottom + scrollView.paddingBottom
            val target = (bottom - scrollView.height).coerceAtLeast(0)
            if (scrollView.scrollY != target) {
                scrollView.scrollTo(0, target)
            }
        }
    }

    private fun positionCursor(absoluteCursorLine: Int, session: TerminalSession) {
        val tvTerminalOutput = viewDelegate.getTerminalOutputView()
        val terminalCursor = viewDelegate.getTerminalCursorView()
        val rendered = session.renderedState
        // Prefer the snapshot so we never race the reader thread on live emulator fields.
        val cursorCol = rendered?.cursorX ?: session.emulator.cursorX
        val cursorLine = rendered?.cursorLine ?: absoluteCursorLine
        val cursorVisible = rendered?.isCursorVisible ?: session.emulator.isCursorVisible

        val charWidth = session.charWidthPx
        val charHeight = session.charHeightPx

        if (charWidth <= 0 || charHeight <= 0) return

        val cursorX = tvTerminalOutput.paddingLeft + cursorCol * charWidth
        val cursorY = tvTerminalOutput.paddingTop + cursorLine * charHeight

        val params = terminalCursor.layoutParams as android.widget.FrameLayout.LayoutParams
        params.leftMargin = cursorX
        params.topMargin = cursorY
        params.width = charWidth.coerceAtLeast(1)
        params.height = charHeight
        terminalCursor.layoutParams = params
        terminalCursor.visibility = if (cursorVisible) View.VISIBLE else View.GONE
    }

    fun closePty() {
        resizeHandler.removeCallbacks(applyResizeRunnable)
        resizeScheduler.clear()
        for (session in terminalSessions) {
            try {
                session.close()
            } catch (_: Throwable) {}
        }
        terminalSessionManager.clear()
        lastRenderedSession = null
        lastAppliedRendered = null
        viewDelegate.getTabLayoutTerminal().removeAllTabs()
        updateTerminalUi()
    }

    fun recalculateActiveSessionSize() {
        val tvTerminalOutput = viewDelegate.getTerminalOutputView()
        val terminalScroll = viewDelegate.getTerminalScrollView()
        val activeSessionIndex = terminalSessionManager.activeSessionIndex
        val terminalSessions = terminalSessionManager.sessions

        if (activeSessionIndex < 0 || activeSessionIndex >= terminalSessions.size) return
        val session = terminalSessions[activeSessionIndex]
        if (!session.isRunning) return
        
        try {
            val paint = tvTerminalOutput.paint
            val charWidth = paint.measureText("M")
            val charHeight = tvTerminalOutput.lineHeight
            session.charWidthPx = charWidth.toInt()
            session.charHeightPx = charHeight
            
            val width = terminalScroll.width - tvTerminalOutput.paddingLeft - tvTerminalOutput.paddingRight
            val height = terminalScroll.height - tvTerminalOutput.paddingTop - tvTerminalOutput.paddingBottom
            
            if (width > 0 && height > 0 && charWidth > 0 && charHeight > 0) {
                val cols = (width / charWidth).toInt().coerceAtLeast(20)
                val rows = (height / charHeight).toInt().coerceAtLeast(10)
                
                if (session.emulator.cols != cols || session.emulator.rows != rows) {
                    session.resize(rows, cols)
                    session.linesInScrollbackInUi = 0
                    session.scrollbackTextLength = 0
                    updateTerminalUi()
                }
            }
        } catch (e: Exception) {
            // Prevent crash during concurrent resize
        }
    }

    fun scrollCursorIntoView(force: Boolean = false) {
        val tvTerminalOutput = viewDelegate.getTerminalOutputView()
        val terminalScroll = viewDelegate.getTerminalScrollView()
        val activeSessionIndex = terminalSessionManager.activeSessionIndex
        val terminalSessions = terminalSessionManager.sessions

        if (activeSessionIndex < 0 || activeSessionIndex >= terminalSessions.size) return
        val session = terminalSessions[activeSessionIndex]

        tvTerminalOutput.post {
            try {
                val layout = tvTerminalOutput.layout ?: return@post
                val lineCount = layout.lineCount
                if (lineCount <= 0) return@post

                // Prefer the last applied snapshot so we don't race the reader thread's
                // live emulator cursor against a TextView that hasn't re-laid-out yet.
                // (IndexOutOfBoundsException here was crashing apt install progress floods.)
                val rendered = session.renderedState
                val rawLine = when {
                    rendered != null -> rendered.cursorLine
                    else -> {
                        val em = session.emulator
                        val sb = em.getScrollbackSize()
                        if (sb == 0) em.cursorY else sb + em.cursorY
                    }
                }
                val absoluteCursorLine = rawLine.coerceIn(0, lineCount - 1)

                val cursorYTop = layout.getLineTop(absoluteCursorLine)
                val cursorYBottom = layout.getLineBottom(absoluteCursorLine)

                val scrollY = terminalScroll.scrollY
                val scrollHeight = terminalScroll.height

                if (force || cursorYTop < scrollY || cursorYBottom > (scrollY + scrollHeight)) {
                    val targetScrollY = (cursorYBottom - scrollHeight / 2).coerceAtLeast(0)
                    terminalScroll.scrollTo(0, targetScrollY)
                }
            } catch (e: Throwable) {
                // Layout can be mid-update vs. cursor line; never crash the app over scroll.
                Log.w("TerminalController", "scrollCursorIntoView skipped: ${e.message}")
            }
        }
    }
}
