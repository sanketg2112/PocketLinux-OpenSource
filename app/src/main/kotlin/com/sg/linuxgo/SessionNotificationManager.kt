package com.sg.linuxgo

import android.content.Context

/**
 * Publishes the "session is running" notification via [SessionKeepAliveService]
 * so the process keeps elevated priority while the app is backgrounded.
 */
class SessionNotificationManager(
    private val context: Context,
    private val containerManager: ContainerManager
) {
    companion object {
        const val NOTIFICATION_ID = 404
        const val CHANNEL_ID = "pocketlinux_session_channel"
        const val ACTION_TERMINATE_SESSION = "com.sg.linuxgo.ACTION_TERMINATE_SESSION"
    }

    fun createNotificationChannel() {
        // Ensure channel exists before any startForegroundService call (Crash D).
        SessionKeepAliveService.ensureChannel(context)
    }

    fun updateSessionNotification(
        containerId: String,
        ramUsedMB: Int,
        isPollingStats: Boolean,
        activeContainerId: String?
    ) {
        // Only while this container is the active session — never from poller after Stop.
        if (activeContainerId == null || activeContainerId != containerId) {
            return
        }

        val container = containerManager.getContainer(containerId)
        val name = container?.name ?: "Linux Session"

        val distroIcon = when (container?.distro) {
            "debian" -> R.drawable.ic_debian
            "ubuntu" -> R.drawable.ic_ubuntu
            "kali" -> R.drawable.ic_kali
            "archlinux" -> R.drawable.ic_archlinux
            else -> R.drawable.ic_alpine
        }

        val contentText = if (ramUsedMB > 0) "RAM Usage: $ramUsedMB MB" else "Session is active"
        val title = "$name is running"

        SessionKeepAliveService.update(
            context = context,
            containerId = containerId,
            title = title,
            text = contentText,
            iconRes = distroIcon
        )
    }

    fun clearSessionNotification() {
        SessionLifecycleGate.setAllowed(context, false)
        SessionKeepAliveService.stop(context)
        X11Service.stop(context)
        try {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(NOTIFICATION_ID)
            nm?.cancel(405)
        } catch (_: Exception) {
        }
    }
}
