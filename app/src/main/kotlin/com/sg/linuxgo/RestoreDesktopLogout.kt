package com.sg.linuxgo

import android.util.Log
import java.io.File

/**
 * Guest helpers for **intentional** panel Log Out (all distros).
 *
 * Writes `/tmp/pocketlinux-session-ended` with content `user_logout` immediately
 * so the host can return to Home. Panel crash must NOT write this marker.
 *
 * XFCE panel often execs absolute `/usr/bin/xfce4-session-logout` — wrapping only
 * `/usr/local/bin` is not enough. We also rename the real binary to `.real`.
 */
fun ContainerRestoreEngine.ensureDesktopLogoutHelpers(rootfs: File, onLog: (String) -> Unit = {}) {
    try {
        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val usrBin = File(rootfs, "usr/bin").apply { mkdirs() }

        val logoutBody = """
            #!/bin/sh
            # PocketLinux: intentional Log Out only — signal host Home immediately.
            # Marker content must contain user_logout (host rejects anything else).
            printf 'user_logout\n' > /tmp/pocketlinux-session-ended 2>/dev/null || true
            sync 2>/dev/null || true
            # Ask real session manager if present (non-blocking; marker already set)
            if [ -x /usr/bin/xfce4-session-logout.real ]; then
                /usr/bin/xfce4-session-logout.real --logout 2>/dev/null &
            elif [ -x /usr/bin/xfce4-session-logout ] \
                && ! grep -q 'PocketLinux' /usr/bin/xfce4-session-logout 2>/dev/null; then
                /usr/bin/xfce4-session-logout --logout 2>/dev/null &
            fi
            # Tear down DE so proot does not hang on wallpaper-only X
            for p in xfce4-panel mate-panel lxqt-panel plasmashell gnome-shell \
                xfce4-session mate-session lxqt-session gnome-session startxfce4 \
                xfdesktop xfwm4 xfsettingsd openbox Hyprland hyprland weston waybar \
                quickshell; do
                pkill -x "${'$'}p" 2>/dev/null || true
            done
            pkill -f '/usr/local/bin/pocketlinux-xfce-session' 2>/dev/null || true
            pkill -f 'startxfce4' 2>/dev/null || true
            sleep 0.15
            for p in xfce4-session startxfce4 xfce4-panel xfdesktop xfwm4; do
                pkill -9 -x "${'$'}p" 2>/dev/null || true
            done
            exit 0
        """.trimIndent() + "\n"

        val logout = File(localBin, "pocketlinux-logout")
        logout.writeText(logoutBody)
        logout.setReadable(true, false)
        logout.setExecutable(true, false)

        val sessionLogoutWrapper = """
            #!/bin/sh
            # PocketLinux: intentional Log Out (panel menu) → host Home.
            # Write marker FIRST so Android can switch Home without delay.
            printf 'user_logout\n' > /tmp/pocketlinux-session-ended 2>/dev/null || true
            sync 2>/dev/null || true
            if [ -x /usr/bin/xfce4-session-logout.real ]; then
                /usr/bin/xfce4-session-logout.real "${'$'}@" 2>/dev/null &
            fi
            exec /usr/local/bin/pocketlinux-logout
        """.trimIndent() + "\n"

        // /usr/local/bin (PATH)
        val localSessionLogout = File(localBin, "xfce4-session-logout")
        localSessionLogout.writeText(sessionLogoutWrapper)
        localSessionLogout.setReadable(true, false)
        localSessionLogout.setExecutable(true, false)

        // Absolute /usr/bin path used by many XFCE panel actions
        val usrSessionLogout = File(usrBin, "xfce4-session-logout")
        val usrReal = File(usrBin, "xfce4-session-logout.real")
        try {
            if (usrSessionLogout.isFile) {
                val head = try {
                    usrSessionLogout.readText().take(200)
                } catch (_: Exception) {
                    ""
                }
                val isOurWrap = head.contains("PocketLinux")
                if (!isOurWrap) {
                    if (!usrReal.exists()) {
                        usrSessionLogout.copyTo(usrReal, overwrite = false)
                        usrReal.setExecutable(true, false)
                    }
                    usrSessionLogout.writeText(sessionLogoutWrapper)
                    usrSessionLogout.setReadable(true, false)
                    usrSessionLogout.setExecutable(true, false)
                } else if (!usrSessionLogout.readText().contains("user_logout")) {
                    usrSessionLogout.writeText(sessionLogoutWrapper)
                    usrSessionLogout.setExecutable(true, false)
                }
            } else {
                // No system binary — still provide wrapper so PATH hits work
                usrSessionLogout.writeText(sessionLogoutWrapper)
                usrSessionLogout.setReadable(true, false)
                usrSessionLogout.setExecutable(true, false)
            }
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "wrap /usr/bin/xfce4-session-logout: ${e.message}")
        }

        // Version stamp so prepareGuiRootfs re-applies after upgrades
        try {
            val stamp = File(rootfs, "var/lib/pocketlinux/logout_helpers_v3")
            stamp.parentFile?.mkdirs()
            stamp.writeText("ok\n")
            File(rootfs, "var/lib/pocketlinux/logout_helpers_v2").delete()
            File(rootfs, "var/lib/pocketlinux/logout_helpers_v1").delete()
        } catch (_: Exception) {
        }

        onLog("✓ Desktop logout helpers (intentional user_logout, /usr/bin wrap)")
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "ensureDesktopLogoutHelpers: ${e.message}")
        onLog("! logout helpers: ${e.message}")
    }
}

/** True if marker file content proves intentional panel/session Log Out. */
fun isIntentionalDesktopLogoutMarker(content: String?): Boolean {
    if (content.isNullOrBlank()) return false
    return content.trim().contains("user_logout")
}
