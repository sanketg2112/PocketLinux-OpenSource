package com.sg.linuxgo

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Shared backup-archive restore logic used by:
 *  - Settings → Restore ([BackupRestoreService])
 *  - New container install from golden image ([Bootstrap])
 *
 * Implementation is split across Restore* extension files to stay under the
 * 1000-line project limit; this class holds context + companion utilities.
 */
class ContainerRestoreEngine(internal val context: Context) {

    companion object {
        internal const val TAG = "ContainerRestoreEngine"

        /**
         * True if [dir] is a host-readable XKB tree Lorie can actually load.
         * Empty or broken symlink trees must NOT be passed as XKB_CONFIG_ROOT —
         * that aborts the server before it binds filesystem socket X0.
         */
        fun isValidXkbTree(dir: File): Boolean {
            if (!dir.exists()) return false
            return try {
                val can = dir.canonicalFile
                can.isDirectory && (
                    File(can, "rules").isDirectory ||
                        File(can, "symbols").isDirectory ||
                        File(can, "keycodes").isDirectory
                    )
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Arch (and some other rolling images) ship `usr/share/X11/xkb` as an
         * **absolute** symlink to `/usr/share/xkeyboard-config-2`. That works
         * inside proot, but Lorie and the Wayland compositor run as Android
         * host processes: they resolve the symlink against the device root, so
         * `/usr/share/...` does not exist → X server never creates X0 (blank
         * desktop) and Wayland crashes while loading keymaps.
         *
         * Convert absolute xkb symlinks to relative ones under [rootfs].
         * Same idea as Local Desktop's `fix_xkb_symlink`.
         *
         * @return host path to a readable XKB config directory, or null
         */
        fun fixAndResolveXkbConfigRoot(rootfs: File, onLog: (String) -> Unit = {}): String? {
            if (!rootfs.isDirectory) return null
            val preferred = listOf(
                "usr/share/xkeyboard-config-2",
                "usr/share/xkeyboard-config",
                "usr/share/X11/xkb"
            )
            val classic = File(rootfs, "usr/share/X11/xkb")
            val parent = classic.parentFile ?: File(rootfs, "usr/share/X11")

            fun linkClassicTo(targetUnderRootfs: File): Boolean {
                return try {
                    if (!isValidXkbTree(targetUnderRootfs)) return false
                    parent.mkdirs()
                    val rel = parent.toPath().relativize(targetUnderRootfs.toPath()).toString()
                    val path = classic.toPath()
                    Files.deleteIfExists(path)
                    Files.createSymbolicLink(path, Paths.get(rel))
                    onLog("Fixed XKB symlink: X11/xkb → $rel")
                    Log.i(TAG, "Fixed xkb symlink ${classic.absolutePath} -> $rel")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "linkClassicTo: ${e.message}")
                    false
                }
            }

            try {
                val path = classic.toPath()
                if (Files.isSymbolicLink(path)) {
                    val target = Files.readSymbolicLink(path)
                    val absGuest = target.toString()
                    if (target.isAbsolute) {
                        val underRootfs = when {
                            absGuest.startsWith("/usr/share/") ->
                                File(rootfs, absGuest.removePrefix("/"))
                            absGuest.startsWith("usr/share/") ->
                                File(rootfs, absGuest)
                            else -> null
                        }
                        if (underRootfs != null && isValidXkbTree(underRootfs)) {
                            linkClassicTo(underRootfs)
                        } else {
                            onLog("⚠ Absolute XKB symlink points outside rootfs: $absGuest")
                            Log.w(TAG, "xkb absolute symlink target missing under rootfs: $absGuest")
                        }
                    } else if (!isValidXkbTree(classic)) {
                        onLog("⚠ XKB relative symlink unreadable on host: $absGuest")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fixAndResolveXkbConfigRoot symlink fix: ${e.message}")
            }

            if (!isValidXkbTree(classic)) {
                for (rel in listOf("usr/share/xkeyboard-config-2", "usr/share/xkeyboard-config")) {
                    val cand = File(rootfs, rel)
                    if (isValidXkbTree(cand) && linkClassicTo(cand)) break
                }
            }

            for (rel in preferred) {
                val f = File(rootfs, rel)
                if (!isValidXkbTree(f)) continue
                try {
                    val can = f.canonicalFile
                    val rootCan = rootfs.canonicalFile
                    if (can.isDirectory && can.absolutePath.startsWith(rootCan.absolutePath)) {
                        Log.i(TAG, "XKB_CONFIG_ROOT → ${can.absolutePath}")
                        return can.absolutePath
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "XKB resolve $rel: ${e.message}")
                }
            }
            onLog("! No host-readable XKB tree (install: pacman -S --needed xkeyboard-config)")
            Log.w(TAG, "No valid XKB tree under ${rootfs.absolutePath}")
            return null
        }

        /**
         * Guest-side user-local dirs that common installers use (OpenCode, cargo, pip, npm, …).
         * Relative to guest home (e.g. /home/PocketLinux).
         */
        val USER_LOCAL_BIN_SUFFIXES: List<String> = listOf(
            ".local/bin",
            "bin",
            ".opencode/bin",
            ".cargo/bin",
            ".npm-global/bin",
            ".npm/bin",
            "go/bin",
            ".bun/bin",
            ".deno/bin",
            ".local/share/fnm"
        )

        /**
         * Build PATH for a guest session: existing user-local bins first, then system.
         * [rootfs] is the host path to the container root; [homeDir] is the guest home
         * (e.g. /home/PocketLinux). Only dirs that already exist on disk are prepended.
         * [pocketlinux-path.sh] also re-scans on login/prompt when new dirs appear.
         */
        fun buildGuestPath(rootfs: File, homeDir: String): String {
            val system =
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            if (homeDir.isBlank() || homeDir == "/") return system
            val homeRel = homeDir.trimStart('/')
            val prefix = USER_LOCAL_BIN_SUFFIXES.mapNotNull { suffix ->
                val guest = "$homeDir/$suffix"
                val host = File(rootfs, "$homeRel/$suffix")
                if (host.isDirectory) guest else null
            }
            return if (prefix.isEmpty()) system else prefix.joinToString(":") + ":" + system
        }
    }

    /** Host helpers directory (legacy; nested proot path no longer used). */
    fun hostHelpersDir(): File = File(context.filesDir, "pl_helpers").apply { mkdirs() }
}
