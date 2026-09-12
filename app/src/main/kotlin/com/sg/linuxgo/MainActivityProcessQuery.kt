package com.sg.linuxgo

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.sg.linuxgo.x11.LorieView

/** Process query + session rehydration after DeX disconnect / activity recreate. */

internal fun MainActivity.isUserSessionStopInProgress(): Boolean {
    return try {
        SessionLifecycleGate.isUserStopInProgress(this)
    } catch (_: Exception) {
        false
    } || try {
        isLateInit_bootstrap() && guiSessionManager.userRequestedTeardown
    } catch (_: Exception) {
        false
    }
}

internal fun MainActivity.isProcessRunning(name: String): Boolean {
    // `:x11` / `:wayland` are this app's process names. ActivityManager only —
    // `pgrep -f :x11` matches the pgrep argv (and terminal cmdlines) and then
    // Resume terminal thinks a desktop is running.
    if (isAppProcessSuffixProbe(name)) {
        return isAppProcessSuffixRunning(name)
    }
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", name))
        val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
        val line = reader.readLine()
        reader.close()
        val exitCode = process.waitFor()
        exitCode == 0 && !line.isNullOrBlank()
    } catch (e: Exception) {
        try {
            val procDir = java.io.File("/proc")
            val files = procDir.listFiles()
            var running = false
            files?.forEach { file ->
                if (file.name.all { it.isDigit() }) {
                    val cmdline = java.io.File(file, "cmdline")
                    if (cmdline.exists()) {
                        val text = cmdline.readText()
                        if (text.contains(name)) {
                            running = true
                            return@forEach
                        }
                    }
                }
            }
            running
        } catch (ex: Exception) {
            false
        }
    }
}

/** True when package process `applicationId + suffix` is in the running list (e.g. `:x11`). */
internal fun MainActivity.isAppProcessSuffixRunning(suffix: String): Boolean {
    return try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val target = packageName + suffix
        am.runningAppProcesses?.any { it.processName == target } == true
    } catch (_: Exception) {
        false
    }
}

/** Guest runtime (classic proot or tawcroot). */
internal fun MainActivity.isGuestRuntimeRunning(): Boolean {
    return isProcessRunning("libproot.so") ||
        isProcessRunning("libtawcroot.so") ||
        isProcessRunning("proot") ||
        isProcessRunning("tawcroot")
}

internal fun MainActivity.isDisplayServerRunning(): Boolean {
    return isAppProcessSuffixRunning(":x11") || isAppProcessSuffixRunning(":wayland")
}

/**
 * Evidence that a Linux session process is still running (not FGS gate alone).
 * Used after DeX disconnect / activity recreate when in-memory manager state is lost.
 */
internal fun MainActivity.hasLiveSessionEvidence(): Boolean {
    // User Stop must always win — never treat teardown remnants as a live session.
    if (isUserSessionStopInProgress()) return false
    if (!isLateInit_bootstrap()) return false
    try {
        if (guiSessionManager.isX11Started) return true
        if (guiSessionManager.isX11SessionAlive()) return true
    } catch (_: Exception) {
    }
    try {
        if (isLateInit_lorieView() && LorieView.connected()) return true
    } catch (_: Exception) {
    }
    if (isDisplayServerRunning()) return true
    if (isGuestRuntimeRunning()) return true
    // In-memory terminal tabs are resumable even if a PTY flag races briefly.
    if (terminalSessions.any { it.isRunning } || terminalSessions.isNotEmpty()) return true
    return false
}

/**
 * Ensure [activeContainerId] is set while terminal tabs still exist so Home cards
 * can match [MainViewModel.activeContainerIdState] for Resume + RAM graph.
 */
internal fun MainActivity.ensureActiveContainerIdForTerminalSessions(): String? {
    if (terminalSessions.isEmpty()) return activeContainerId
    activeContainerId?.let { return it }
    val prefs = getSharedPreferences("pocket_linux_state", Context.MODE_PRIVATE)
    val fromPrefs = prefs.getString("active_container_id", null)
    val fromKeepAlive = try {
        SessionKeepAliveService.activeContainerId
    } catch (_: Exception) {
        null
    }
    val recovered = pickActiveContainerCandidate(
        memoryId = null,
        keepAliveId = fromKeepAlive,
        prefsId = fromPrefs
    ) ?: containerManager.getContainers().firstOrNull { it.isInstalled }?.id
    if (recovered != null) {
        setActiveContainerId(recovered)
        Log.i("MainActivity", "Bound activeContainerId=$recovered for live terminal tabs")
    }
    return recovered
}

/**
 * Rebind [activeContainerId] when DeX / config change dropped in-memory state but
 * the container display server or guest is still running.
 *
 * Never runs during user Stop (would resurrect Stop button / RAM graph / FGS).
 *
 * @return true when a live session is bound to a container id
 */
internal fun MainActivity.rehydrateActiveSessionState(): Boolean {
    if (!isLateInit_containerManager()) return false
    if (isUserSessionStopInProgress()) {
        // Ensure UI stays idle while teardown races with the stats poller.
        if (activeContainerId != null) {
            setActiveContainerId(null)
        }
        return false
    }

    val prefs = getSharedPreferences("pocket_linux_state", Context.MODE_PRIVATE)
    val fromPrefs = prefs.getString("active_container_id", null)
    val fromKeepAlive = try {
        SessionKeepAliveService.activeContainerId
    } catch (_: Exception) {
        null
    }

    val candidate = pickActiveContainerCandidate(
        memoryId = activeContainerId,
        keepAliveId = fromKeepAlive,
        prefsId = fromPrefs
    )

    val evidence = hasLiveSessionEvidence()

    if (candidate != null && shouldBindActiveContainer(evidence, userStopInProgress = false)) {
        if (activeContainerId != candidate) {
            setActiveContainerId(candidate)
            Log.i("MainActivity", "Rehydrated activeContainerId=$candidate (DeX/session recover)")
        }
        // Activity recreate loses isX11Started while :x11 + guest may still be up.
        try {
            if (!guiSessionManager.isX11Started &&
                (isDisplayServerRunning() ||
                    (isLateInit_lorieView() && LorieView.connected()) ||
                    guiSessionManager.isX11SessionAlive())
            ) {
                guiSessionManager.markSessionLive()
            }
        } catch (_: Exception) {
        }
        return true
    }

    // No process evidence — clear stale prefs so cards do not stick on Resume.
    if (!evidence) {
        if (fromPrefs != null) {
            prefs.edit().remove("active_container_id").apply()
        }
        if (activeContainerId != null && !isDisplayServerRunning() && !isGuestRuntimeRunning()) {
            // Keep memory id while terminal tabs still exist (resumable shell).
            try {
                if (!guiSessionManager.isX11Started &&
                    !guiSessionManager.isX11SessionAlive() &&
                    terminalSessions.none { it.isRunning } &&
                    terminalSessions.isEmpty()
                ) {
                    setActiveContainerId(null)
                }
            } catch (_: Exception) {
                if (terminalSessions.isEmpty()) {
                    setActiveContainerId(null)
                }
            }
        }
    }
    return false
}
