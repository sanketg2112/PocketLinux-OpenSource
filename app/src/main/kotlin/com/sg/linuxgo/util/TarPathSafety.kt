package com.sg.linuxgo.util

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Zip-slip / path-escape checks for tar members.
 *
 * Member names must stay under the extract dest. Guest-absolute symlink
 * targets such as `/bin/busybox` are allowed; Android host prefixes are not.
 */
object TarPathSafety {
    class UnsafeArchivePathException(message: String) : Exception(message)

    /** Android userdata / shared storage only — not guest /system /vendor /mnt. */
    private val HOST_ABSOLUTE_PREFIXES = setOf(
        "data", "sdcard", "storage", "data_mirror"
    )

    private val GUEST_ABS_HINTS = listOf(
        "/usr/", "/bin/", "/sbin/", "/lib", "/etc/", "/var/", "/run/",
        "/proc/", "/opt/", "/home/", "/root/", "/tmp/", "/boot/", "/sys/",
        "/dev/", "/mnt/", "/media/", "/srv/"
    )

    fun normalizeMemberName(raw: String): String {
        var n = raw.replace('\\', '/').trim()
        while (n.startsWith("./")) n = n.removePrefix("./")
        while (n.startsWith("/")) n = n.removePrefix("/")
        while (n.contains("//")) n = n.replace("//", "/")
        if (n == "." || n == "./") return ""
        return n
    }

    /**
     * True when [raw] stays under the archive root after collapsing `.` / `..`.
     * GCC/libtool members like `usr/lib/gcc/x/../../../lib/foo` are allowed.
     */
    fun isSafeMemberName(raw: String): Boolean {
        return collapseSegments(normalizeMemberName(raw)) != null
    }

    /** Member name from toybox `tar -t` (`name`, `name -> target`, `name link to target`). */
    fun tarListMemberName(line: String): String {
        val s = line.trim()
        val cut = listOf(s.indexOf(" -> "), s.indexOf(" link to "))
            .filter { it >= 0 }
            .minOrNull()
            ?: return s
        return s.substring(0, cut).trim()
    }

    fun tarListLinkTarget(line: String): String? {
        val s = line.trim()
        val arrow = s.indexOf(" -> ")
        if (arrow >= 0) return s.substring(arrow + 4).trim().ifEmpty { null }
        val linkTo = s.indexOf(" link to ")
        if (linkTo >= 0) return s.substring(linkTo + 9).trim().ifEmpty { null }
        return null
    }

    /**
     * Toybox `tar -t` prints `name -> target` even without `-v`.
     * Only the member **name** must stay under the archive root. Guest
     * relative targets (`lib64 -> ../lib`) and `/system` (hybris) are allowed.
     * Absolute links into Android userdata / shared storage are not.
     */
    fun isSafeTarListLine(line: String): Boolean = unsafeTarListReason(line) == null

    fun unsafeTarListReason(line: String): String? {
        val s = line.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("tar:") || s.startsWith("gzip:") || s.startsWith("toybox:")) {
            return null
        }
        val name = tarListMemberName(s)
        if (!isSafeMemberName(name)) return s
        val target = tarListLinkTarget(s) ?: return null
        if (target.startsWith("/") && isBlockedHostAbsolute(target)) return s
        return null
    }

    fun isUnderDest(dest: File, candidate: File): Boolean {
        // Lexical only — File.canonicalFile follows guest-absolute symlinks
        // (e.g. crypto-policies → /usr/share/...) and falsely looks like zip-slip.
        val root = dest.absoluteFile.normalize().absolutePath
        val path = candidate.absoluteFile.normalize().absolutePath
        return path == root || path.startsWith(root + File.separator)
    }

    fun resolveUnderDest(dest: File, memberName: String): File? {
        val destRoot = dest.absoluteFile.normalize()
        val parts = collapseSegments(normalizeMemberName(memberName)) ?: return null
        if (parts.isEmpty()) return destRoot
        var current = destRoot
        for (i in parts.indices) {
            val next = File(current, parts[i])
            val last = i == parts.lastIndex
            if (!last && isSymlink(next)) {
                val target = readLinkTarget(next) ?: return null
                current = resolveLinkInsideGuest(destRoot, current, target) ?: return null
            } else if (last) {
                return if (isUnderDest(destRoot, next)) next else null
            } else {
                current = next
            }
        }
        return current
    }

    fun requireUnderDest(dest: File, memberName: String): File {
        return resolveUnderDest(dest, memberName)
            ?: throw UnsafeArchivePathException("Unsafe archive path")
    }

    /** Collapse `.` / `..` and require the result to stay under [dest]. */
    fun collapseUnderDest(dest: File, relative: String): File? {
        val destRoot = dest.absoluteFile.normalize()
        val parts = collapseSegments(relative.replace('\\', '/')) ?: return null
        val out = if (parts.isEmpty()) destRoot else File(destRoot, parts.joinToString("/"))
        return if (isUnderDest(destRoot, out)) out else null
    }

    /**
     * Toybox `tar -x` dirflush follows existing guest-absolute symlinks and
     * prints `tar: 'member' /usr/share/... not under 'dest'`. Those are
     * expected Fedora/openSUSE crypto-policies links, not zip-slip.
     */
    fun isBenignToyboxExtractLine(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty()) return true
        if (!s.startsWith("tar:")) return false
        if (s.contains("bad symlink")) return true
        if (s.contains("can't link") && s.contains("Permission denied")) return true
        if (!s.contains(" not under ")) return false
        return GUEST_ABS_HINTS.any { hint ->
            s.contains(" $hint") || s.contains("'$hint")
        }
    }

    fun isMostlyBenignToyboxExtractLog(errText: String): Boolean {
        val lines = errText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return false
        return lines.all { isBenignToyboxExtractLine(it) }
    }

    /** Null if a `..` would walk above the archive root. */
    private fun collapseSegments(relative: String): List<String>? {
        val parts = ArrayList<String>()
        for (seg in relative.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") {
                if (parts.isEmpty()) return null
                parts.removeAt(parts.lastIndex)
            } else {
                parts.add(seg)
            }
        }
        return parts
    }

    /**
     * Absolute guest targets (`/bin/busybox`, `/system/...`) are allowed.
     * Android userdata / shared storage is not. Relative `../` is allowed
     * (e.g. `lib64 -> ../lib`); zip-slip is enforced on member names.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isSafeLinkTarget(dest: File, memberName: String, linkTarget: String): Boolean {
        val t = linkTarget.replace('\\', '/').trim()
        if (t.isEmpty()) return false
        if (t.startsWith("/")) return !isBlockedHostAbsolute(t)
        return true
    }

    private fun isBlockedHostAbsolute(target: String): Boolean {
        val t = target.replace('\\', '/').trim()
        val first = t.trimStart('/').substringBefore('/')
        if (first.isEmpty() || first !in HOST_ABSOLUTE_PREFIXES) return false
        // SSH and similar tools sometimes store host paths into the guest
        // (…/files/containers/<id>/rootfs/…). Those are inside a rootfs, not
        // shared_prefs. Restore must not fail the whole archive.
        return !isHostPathInsideContainerRootfs(t)
    }

    internal fun isHostPathInsideContainerRootfs(path: String): Boolean {
        val n = path.replace('\\', '/')
        val marker = "/files/containers/"
        val i = n.indexOf(marker)
        if (i < 0) return false
        val afterId = n.substring(i + marker.length)
        val slash = afterId.indexOf('/')
        if (slash <= 0) return false
        val rest = afterId.substring(slash + 1)
        if (rest != "rootfs" && !rest.startsWith("rootfs/")) return false
        val inside = if (rest == "rootfs") "" else rest.removePrefix("rootfs/")
        return collapseSegments(inside) != null
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Exception) {
            false
        }
    }

    private fun readLinkTarget(file: File): String? {
        return try {
            Files.readSymbolicLink(file.toPath()).toString().replace('\\', '/')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolve a symlink target as a path inside the guest dest.
     * `/usr/bin` → dest/usr/bin (not the Android host /usr/bin).
     */
    private fun resolveLinkInsideGuest(destRoot: File, parent: File, target: String): File? {
        val t = target.replace('\\', '/').trim()
        if (t.isEmpty()) return null
        if (t.startsWith("/")) {
            if (isBlockedHostAbsolute(t)) return null
            return collapseUnderDest(destRoot, normalizeMemberName(t))
        }
        val parentRel = try {
            destRoot.toPath().relativize(parent.toPath()).toString().replace('\\', '/')
        } catch (_: Exception) {
            return null
        }
        val combined = if (parentRel.isEmpty() || parentRel == ".") t else "$parentRel/$t"
        return collapseUnderDest(destRoot, combined)
    }
}
