package com.sg.linuxgo

/**
 * Pure helpers for “is this container’s session still live?” used by home cards
 * after DeX disconnect / activity recreate (when in-memory process refs are gone
 * but the display server / guest is still running).
 */

data class SessionActiveSnapshot(
    val isGuiActive: Boolean,
    val isShellActive: Boolean
)

/**
 * Prefer in-memory id, then keep-alive service id, then persisted prefs.
 */
fun pickActiveContainerCandidate(
    memoryId: String?,
    keepAliveId: String?,
    prefsId: String?
): String? = when {
    !memoryId.isNullOrBlank() -> memoryId
    !keepAliveId.isNullOrBlank() -> keepAliveId
    !prefsId.isNullOrBlank() -> prefsId
    else -> null
}

/**
 * Restore active container only when real process/binder evidence exists and the
 * user has not just stopped the session.
 *
 * Gate/keep-alive alone is **not** enough — after Stop those can race and would
 * resurrect the Stop button / RAM graph without a live session.
 */
fun shouldBindActiveContainer(
    hasProcessOrBinderEvidence: Boolean,
    userStopInProgress: Boolean
): Boolean = hasProcessOrBinderEvidence && !userStopInProgress

/**
 * Card Resume desktop / Resume terminal flags.
 *
 * Driven only by real session signals (flags, Lorie, display server, guest, shells).
 * Keep-alive / FGS gate alone must not force GUI active — that undid Stop.
 */
fun computeSessionActiveSnapshot(
    isX11Started: Boolean,
    isX11SessionAlive: Boolean,
    lorieConnected: Boolean,
    displayServerRunning: Boolean,
    waylandRunning: Boolean,
    terminalSessionsRunning: Boolean,
    guestRuntimeRunning: Boolean,
    hasActiveContainerId: Boolean,
    isInstalling: Boolean,
    userStopInProgress: Boolean = false
): SessionActiveSnapshot {
    if (userStopInProgress) {
        return SessionActiveSnapshot(isGuiActive = false, isShellActive = false)
    }
    val isX11Core = isX11Started ||
        isX11SessionAlive ||
        lorieConnected ||
        displayServerRunning ||
        waylandRunning
    val isTerminalCore = terminalSessionsRunning ||
        (hasActiveContainerId && guestRuntimeRunning && !isX11Core && !isInstalling)
    return SessionActiveSnapshot(
        isGuiActive = isX11Core,
        isShellActive = isTerminalCore
    )
}

/**
 * Desktop and the in-app terminal each start a separate guest on the same
 * rootfs. They cannot share display, session bus, or GPU — launching both
 * leaves the desktop black. Detect that exclusive-mode conflict.
 */
enum class ExclusiveSessionConflict {
    NONE,
    TERMINAL_RUNNING,
    DESKTOP_RUNNING
}

data class ExclusiveSessionPrompt(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String
)

/**
 * Real desktop evidence for the exclusive-mode prompt.
 *
 * The in-memory isX11Started flag is not enough: a false :x11 probe
 * (pgrep matching itself, a terminal cmdline substring) can stick that flag
 * and then block Resume terminal after a terminal-only session.
 */
fun exclusiveDesktopEvidence(
    x11SessionAlive: Boolean,
    x11ProcessAlive: Boolean,
    waylandProcessAlive: Boolean
): Boolean = x11SessionAlive || x11ProcessAlive || waylandProcessAlive

/**
 * Android secondary processes (`:x11`, `:wayland`) must be matched by
 * ActivityManager process name, never `pgrep -f :x11`.
 */
fun isAppProcessSuffixProbe(name: String): Boolean = name.startsWith(":")

fun exclusiveSessionConflict(
    launchingDesktop: Boolean,
    desktopLive: Boolean,
    terminalLive: Boolean,
    resumingSameMode: Boolean = false
): ExclusiveSessionConflict {
    // Resume desktop / Resume terminal is not a dual launch.
    if (resumingSameMode) return ExclusiveSessionConflict.NONE
    return when {
        launchingDesktop && terminalLive -> ExclusiveSessionConflict.TERMINAL_RUNNING
        !launchingDesktop && desktopLive -> ExclusiveSessionConflict.DESKTOP_RUNNING
        else -> ExclusiveSessionConflict.NONE
    }
}

fun exclusiveSessionPrompt(
    conflict: ExclusiveSessionConflict,
    containerName: String
): ExclusiveSessionPrompt? = when (conflict) {
    ExclusiveSessionConflict.NONE -> null
    ExclusiveSessionConflict.TERMINAL_RUNNING -> ExclusiveSessionPrompt(
        title = "Can't run both at once",
        message = "The terminal for “$containerName” is still running. " +
            "Desktop and terminal can't run at the same time.\n\n" +
            "Stop the terminal and launch desktop?",
        confirmLabel = "Stop terminal & launch",
        dismissLabel = "Keep terminal"
    )
    ExclusiveSessionConflict.DESKTOP_RUNNING -> ExclusiveSessionPrompt(
        title = "Can't run both at once",
        message = "The desktop for “$containerName” is still running. " +
            "Desktop and terminal can't run at the same time.\n\n" +
            "Stop the desktop and open terminal?",
        confirmLabel = "Stop desktop & open",
        dismissLabel = "Keep desktop"
    )
}
