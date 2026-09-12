package com.sg.linuxgo.util

import android.util.Log
import java.io.File

/**
 * Safe writes under a container rootfs (bashrc / profile / similar).
 *
 * Crash C (user_crash.md): FileNotFoundException when opening
 * `…/rootfs/root/.bashrc` because the parent home dir was missing or not a directory.
 * Always ensure parents exist and never throw out of shell-file helpers.
 */
object GuestShellFiles {
    private const val TAG = "GuestShellFiles"

    /**
     * Create parent directories if needed, then write [content].
     * @return true if the file was written.
     */
    fun writeTextSafe(file: File, content: String): Boolean {
        return try {
            if (!ensureParentDir(file)) return false
            file.writeText(content)
            true
        } catch (e: Exception) {
            Log.w(TAG, "writeTextSafe ${file.absolutePath}: ${e.message}")
            false
        }
    }

    /**
     * Create parent directories if needed, then append [content].
     * @return true if the file was appended (or created).
     */
    fun appendTextSafe(file: File, content: String): Boolean {
        return try {
            if (!ensureParentDir(file)) return false
            file.appendText(content)
            true
        } catch (e: Exception) {
            Log.w(TAG, "appendTextSafe ${file.absolutePath}: ${e.message}")
            false
        }
    }

    /**
     * Read existing text or empty string; never throws.
     */
    fun readTextOrEmpty(file: File): String {
        return try {
            if (file.isFile) file.readText() else ""
        } catch (e: Exception) {
            Log.w(TAG, "readTextOrEmpty ${file.absolutePath}: ${e.message}")
            ""
        }
    }

    /**
     * Ensure [file]'s parent is a directory (mkdirs). Returns false if impossible.
     */
    fun ensureParentDir(file: File): Boolean {
        val parent = file.parentFile ?: return false
        return try {
            if (parent.isDirectory) return true
            if (parent.exists() && !parent.isDirectory) {
                // Corrupt rootfs layout (e.g. "root" is a file) — cannot write.
                Log.w(TAG, "parent is not a directory: ${parent.absolutePath}")
                return false
            }
            parent.mkdirs()
            parent.isDirectory
        } catch (e: Exception) {
            Log.w(TAG, "ensureParentDir ${parent.absolutePath}: ${e.message}")
            false
        }
    }

    /**
     * Remove a managed block (and legacy variants) so user configs can return
     * to a normal Linux layout. Preserves all non-managed content.
     */
    fun stripManagedBlock(
        existing: String,
        startMarker: String,
        endMarker: String
    ): String {
        if (existing.isEmpty() || !existing.contains(startMarker)) return existing
        val start = existing.indexOf(startMarker)
        if (start < 0) return existing
        val before = existing.substring(0, start)
        val fromStart = existing.substring(start)
        val endInRest = fromStart.indexOf(endMarker)
        val after: String = if (endInRest >= 0) {
            fromStart.substring(endInRest + endMarker.length)
        } else {
            // Legacy footer without end marker — drop managed-looking lines only.
            val lines = fromStart.lines()
            var i = 1
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.isEmpty()) {
                    i++
                    break
                }
                val isManagedLine =
                    t.startsWith("#") ||
                        t.startsWith("if ") ||
                        t.startsWith("else") ||
                        t.startsWith("elif ") ||
                        t.startsWith("fi") ||
                        t.startsWith("[ ") ||
                        t.startsWith("[[ ") ||
                        t.contains("profile.d/pocketlinux") ||
                        t.contains("POCKETLINUX") ||
                        t.startsWith("__pl_") ||
                        t == "then" ||
                        t == "fi"
                if (!isManagedLine) break
                i++
            }
            lines.drop(i).joinToString("\n")
        }
        return (before.trimEnd() + "\n" + after.trimStart()).trimEnd() +
            if (existing.endsWith("\n")) "\n" else ""
    }

    /**
     * Insert or refresh a managed block in a shell config file **without deleting
     * user customizations** before or after the block.
     *
     * Older PocketLinux code used `substringBefore(startMarker) + snippet`, which
     * permanently deleted anything the user (or a theme installer) appended after
     * the managed section — the root cause of "bashrc theme gone after restart".
     *
     * [blockBody] should include start/end markers (or at least [startMarker]).
     * When [endMarker] is present in an existing file, content after it is kept.
     * For legacy blocks without [endMarker], we only replace the old 2–4 line
     * managed footer and keep the remainder of the file.
     *
     * @param prependIfMissing when true and block is absent, put it at the top
     *   so later user/theme appends always win for PS1 and PATH customizations.
     */
    fun upsertManagedBlock(
        existing: String,
        startMarker: String,
        endMarker: String,
        blockBody: String,
        prependIfMissing: Boolean = true
    ): String {
        val block = blockBody.trimEnd() + "\n"
        val start = existing.indexOf(startMarker)
        if (start < 0) {
            val body = existing.trimEnd()
            return if (body.isBlank()) {
                block
            } else if (prependIfMissing) {
                block + "\n" + body.trimStart() + "\n"
            } else {
                body + "\n\n" + block
            }
        }

        val before = existing.substring(0, start).trimEnd()
        val fromStart = existing.substring(start)
        val endInRest = fromStart.indexOf(endMarker)
        val after: String = if (endInRest >= 0) {
            fromStart.substring(endInRest + endMarker.length).trimStart()
        } else {
            // Legacy managed footer without end marker (typically 2–4 lines of
            // source commands). Keep everything after those lines so user themes
            // appended after the old block are not deleted.
            val lines = fromStart.lines()
            var i = 1 // skip start marker line
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.isEmpty()) {
                    i++
                    break
                }
                val isManagedLine =
                    t.startsWith("#") ||
                        t.startsWith("if ") ||
                        t.startsWith("else") ||
                        t.startsWith("elif ") ||
                        t.startsWith("fi") ||
                        t.startsWith("[ ") ||
                        t.startsWith("[[ ") ||
                        t.contains("profile.d/pocketlinux") ||
                        t.contains("POCKETLINUX") ||
                        t.startsWith("__pl_") ||
                        t == "then" ||
                        t == "fi"
                if (!isManagedLine) break
                i++
            }
            lines.drop(i).joinToString("\n").trimStart()
        }

        val parts = mutableListOf<String>()
        if (before.isNotBlank()) parts.add(before)
        parts.add(block.trimEnd())
        if (after.isNotBlank()) parts.add(after)
        return parts.joinToString("\n\n") + "\n"
    }
}
