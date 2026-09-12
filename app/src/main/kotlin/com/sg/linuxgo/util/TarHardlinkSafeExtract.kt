package com.sg.linuxgo.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Stream-extract a ustar/pax tar into [outputDir], converting hard links to
 * regular file copies.
 *
 * Android app data partitions typically deny [link] (EPERM / "Permission denied"),
 * so toybox/system `tar -x` fails on NetHunter rootfs hardlinks (klibc tools, perl).
 * Soft links are still created as symlinks when the platform allows.
 *
 * Alpine (and many musl rootfs) ship busybox tools as *absolute* guest symlinks
 * (`bin/sh` → `/bin/busybox`). Those must stay absolute so proot resolves them
 * inside the rootfs. Stripping the leading slash turns them into relative links
 * (`bin/busybox` from `bin/sh` → `bin/bin/busybox`) and breaks the shell.
 */
object TarHardlinkSafeExtract {
    private const val TAG = "TarHardlinkSafeExtract"
    private const val BLOCK = 512

    /**
     * True if [file] exists as a real file/dir **or** as a symlink entry
     * (even when the target is guest-absolute and unresolvable on the Android host).
     */
    fun existsInRootfs(file: File): Boolean {
        return try {
            Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            file.exists()
        }
    }

    /**
     * True if the rootfs has a usable guest shell entry.
     * Alpine ships `bin/sh` → `/bin/busybox` (absolute); host [File.exists] is false
     * for those links, so callers must use this instead of plain exists().
     */
    fun hasGuestShell(rootfs: File): Boolean {
        return existsInRootfs(File(rootfs, "bin/sh")) ||
            existsInRootfs(File(rootfs, "usr/bin/sh")) ||
            existsInRootfs(File(rootfs, "usr/bin/bash")) ||
            existsInRootfs(File(rootfs, "bin/bash")) ||
            existsInRootfs(File(rootfs, "usr/bin/dash")) ||
            existsInRootfs(File(rootfs, "bin/busybox")) ||
            existsInRootfs(File(rootfs, "usr/bin/busybox"))
    }

    /** Delete a path even when it is a broken / guest-absolute symlink. */
    fun deleteRootfsEntry(file: File): Boolean {
        return try {
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(file.toPath())
                true
            } else {
                file.delete()
            }
        } catch (_: Exception) {
            try {
                file.delete()
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * @return number of members processed (files/dirs/links)
     */
    fun extract(
        tarFile: File,
        outputDir: File,
        progressCallback: ((count: Int) -> Unit)? = null
    ): Int {
        if (!outputDir.exists()) outputDir.mkdirs()
        FileInputStream(tarFile).use { fis ->
            return extractStream(fis, outputDir, progressCallback)
        }
    }

    fun extractStream(
        input: InputStream,
        outputDir: File,
        progressCallback: ((count: Int) -> Unit)? = null
    ): Int {
        val header = ByteArray(BLOCK)
        var count = 0
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        while (true) {
            if (!readFully(input, header)) break
            if (isZeroBlock(header)) {
                // Two zero blocks end the archive; tolerate a single one.
                if (!readFully(input, header) || isZeroBlock(header)) break
                // Non-zero after one zero: treat as next header (already in buffer)
            }

            val typeflag = header[156].toInt().and(0xFF).toChar()
            val size = parseOctal(header, 124, 12)
            // mode is 12-bit permission bits (e.g. 0755); ignore file type nibble if present
            val mode = (parseOctal(header, 100, 8).toInt() and 0x1FF)
            val nameField = parseString(header, 0, 100)
            val linkField = parseString(header, 157, 100)
            val prefix = parseString(header, 345, 155)

            var name = pendingLongName ?: joinPrefix(prefix, nameField)
            // Keep raw link target (may be absolute guest path like /bin/busybox)
            val linknameRaw = (pendingLongLink ?: linkField).replace('\\', '/')
            pendingLongName = null
            pendingLongLink = null

            name = TarPathSafety.normalizeMemberName(name)
            // For hardlink resolve / host path lookup only (never for symlink create)
            val linknameForResolve = linknameRaw.trimStart('/')

            when (typeflag) {
                'L' -> { // GNU long name
                    pendingLongName = readPayloadAsString(input, size).trimEnd('\u0000')
                    continue
                }
                'K' -> { // GNU long link name — keep absolute guest targets
                    pendingLongLink = readPayloadAsString(input, size).trimEnd('\u0000')
                    continue
                }
                'x', 'g' -> { // pax extended / global — applies to the *next* member
                    val pax = readPayloadAsString(input, size)
                    val parsed = parsePax(pax)
                    if (parsed != null) {
                        pendingLongName = parsed.first?.trimStart('/')?.replace('\\', '/')
                            ?: pendingLongName
                        // Preserve absolute linkpath for Alpine busybox tools
                        pendingLongLink = parsed.second?.replace('\\', '/')
                            ?: pendingLongLink
                    }
                    continue
                }
            }

            val memberFile = try {
                TarPathSafety.requireUnderDest(outputDir, name)
            } catch (e: TarPathSafety.UnsafeArchivePathException) {
                Log.w(TAG, "skip unsafe member $name: ${e.message}")
                skipPayload(input, size)
                continue
            }

            // Skip device nodes under any .../dev/... (Android cannot create them)
            if (shouldSkipPath(name)) {
                skipPayload(input, size)
                continue
            }

            var payloadConsumed = size <= 0L
            try {
                when (typeflag) {
                    '5' -> {
                        memberFile.mkdirs()
                        // Dirs default 0755 if archive mode missing. Owner-write is
                        // forced inside applyUnixMode so Fedora/openSUSE 0555 /usr/bin
                        // does not reject the files that follow this directory member.
                        applyUnixMode(memberFile, if (mode != 0) mode else 0b111_101_101)
                    }
                    '0', '\u0000', '7' -> {
                        memberFile.parentFile?.mkdirs()
                        ensureWritableParent(memberFile)
                        writePayloadToFile(input, memberFile, size)
                        payloadConsumed = true
                        // Critical: without this, /usr/bin/env etc. are not executable under PRoot
                        applyUnixMode(memberFile, if (mode != 0) mode else defaultFileMode(name))
                    }
                    '1' -> {
                        // Hard link → copy target (Android cannot link())
                        memberFile.parentFile?.mkdirs()
                        ensureWritableParent(memberFile)
                        deleteRootfsEntry(memberFile)
                        val target = resolveLinkTarget(outputDir, name, linknameForResolve)
                        if (target != null && target.isFile) {
                            target.copyTo(memberFile, overwrite = true)
                        } else {
                            Log.w(TAG, "hardlink copy miss: $name -> $linknameRaw")
                            memberFile.writeBytes(ByteArray(0))
                        }
                        applyUnixMode(memberFile, if (mode != 0) mode else defaultFileMode(name))
                    }
                    '2' -> {
                        extractSymlinkMember(
                            outputDir, memberFile, name, linknameRaw, linknameForResolve, mode
                        )
                    }
                    else -> {
                        // devices, fifo, unknown — skip payload
                        skipPayload(input, size)
                        payloadConsumed = true
                    }
                }
            } catch (e: Exception) {
                // One Fedora ca-trust hash must not abort the rest (usr/bin/bash is later).
                Log.w(TAG, "member $name failed: ${e.message}")
                if (!payloadConsumed) {
                    try {
                        skipPayload(input, size)
                    } catch (_: Exception) {
                    }
                }
            }

            count++
            if (count % 100 == 0) {
                try {
                    progressCallback?.invoke(count)
                } catch (_: Exception) {
                }
            }
        }
        progressCallback?.invoke(count)
        Log.d(TAG, "extract finished members=$count → ${outputDir.absolutePath}")
        return count
    }

    private fun shouldSkipPath(name: String): Boolean {
        if (name == "dev" || name.startsWith("dev/")) return true
        // Nested rootfs: kali-arm64/dev/...
        if (name.endsWith("/dev") || name.contains("/dev/")) {
            // only skip actual device tree, not "develop" etc.
            val parts = name.split('/')
            if (parts.any { it == "dev" }) {
                val idx = parts.indexOf("dev")
                // .../dev or .../dev/something
                return idx >= 0
            }
        }
        return false
    }

    /**
     * Fedora ca-trust: `directory-hash/002c0b4f.0` → relative `GlobalSign_Root_R46.pem`
     * living in the parent `pem/` directory (or the same dir). Android denies link().
     */
    private fun extractSymlinkMember(
        outputDir: File,
        memberFile: File,
        name: String,
        linknameRaw: String,
        linknameForResolve: String,
        mode: Int
    ) {
        if (!TarPathSafety.isSafeLinkTarget(outputDir, name, linknameRaw)) {
            Log.w(TAG, "skip unsafe link $name -> $linknameRaw")
            return
        }
        memberFile.parentFile?.mkdirs()
        ensureWritableParent(memberFile)
        deleteRootfsEntry(memberFile)
        try {
            // Keep absolute targets (/bin/busybox) so proot resolves inside rootfs.
            // Relative targets stay relative to the member directory.
            Files.createSymbolicLink(memberFile.toPath(), Paths.get(linknameRaw))
            return
        } catch (e: Exception) {
            Log.w(TAG, "symlink $name -> $linknameRaw: ${e.message}")
        }
        val target = resolveLinkTarget(outputDir, name, linknameForResolve)
        if (target != null && target.isFile) {
            deleteRootfsEntry(memberFile)
            target.copyTo(memberFile, overwrite = true)
            applyUnixMode(memberFile, if (mode != 0) mode else defaultFileMode(name))
            return
        }
        deleteRootfsEntry(memberFile)
        memberFile.writeBytes(ByteArray(0))
        applyUnixMode(memberFile, if (mode != 0) mode else defaultFileMode(name))
    }

    private fun resolveLinkTarget(outputDir: File, memberName: String, linkname: String): File? {
        if (linkname.isBlank()) return null
        if (!TarPathSafety.isSafeLinkTarget(outputDir, memberName, linkname)) return null
        val destRoot = outputDir.absoluteFile
        val stripped = TarPathSafety.normalizeMemberName(linkname)
        val abs = TarPathSafety.resolveUnderDest(destRoot, stripped)
        if (abs != null && abs.isFile) return abs
        val parentRel = TarPathSafety.normalizeMemberName(memberName).let { n ->
            if (n.contains('/')) n.substringBeforeLast('/') else ""
        }
        val sameDir = if (parentRel.isEmpty()) stripped else "$parentRel/$stripped"
        val rel = TarPathSafety.collapseUnderDest(destRoot, sameDir)
        if (rel != null && rel.isFile) return rel
        // directory-hash/NAME.0 → ../Cert.pem in the parent folder
        if (parentRel.contains('/')) {
            val grand = parentRel.substringBeforeLast('/')
            val up = TarPathSafety.collapseUnderDest(destRoot, "$grand/$stripped")
            if (up != null && up.isFile) return up
        }
        return null
    }

    private fun joinPrefix(prefix: String, name: String): String {
        if (prefix.isEmpty()) return name
        if (name.isEmpty()) return prefix
        return "$prefix/$name"
    }

    private fun parsePax(payload: String): Pair<String?, String?>? {
        // records: "LEN key=value\n"
        var path: String? = null
        var link: String? = null
        var i = 0
        val bytes = payload
        while (i < bytes.length) {
            val sp = bytes.indexOf(' ', i)
            if (sp < 0) break
            val len = bytes.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0) break
            val recordEnd = i + len
            if (recordEnd > bytes.length) break
            val record = bytes.substring(sp + 1, recordEnd).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0) {
                val key = record.substring(0, eq)
                val value = record.substring(eq + 1)
                when (key) {
                    "path" -> path = value
                    "linkpath" -> link = value
                }
            }
            i = recordEnd
        }
        if (path == null && link == null) return null
        return path to link
    }

    private fun parseString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(buf: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        val limit = off + len
        for (i in off until limit) {
            val c = buf[i].toInt().and(0xFF)
            if (c == 0 || c == ' '.code) break
            if (c < '0'.code || c > '7'.code) break
            v = (v shl 3) + (c - '0'.code)
        }
        return v
    }

    private fun isZeroBlock(buf: ByteArray): Boolean {
        for (b in buf) if (b != 0.toByte()) return false
        return true
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off > 0 && off == buf.size // partial = fail
            if (n == 0) continue
            off += n
        }
        return true
    }

    private fun paddedSize(size: Long): Long {
        if (size <= 0) return 0
        val rem = size % BLOCK
        return if (rem == 0L) size else size + (BLOCK - rem)
    }

    private fun skipPayload(input: InputStream, size: Long) {
        var left = paddedSize(size)
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun readPayloadAsString(input: InputStream, size: Long): String {
        if (size <= 0) {
            skipPayload(input, 0)
            return ""
        }
        val data = ByteArray(size.toInt().coerceAtLeast(0))
        var off = 0
        while (off < data.size) {
            val n = input.read(data, off, data.size - off)
            if (n < 0) break
            off += n
        }
        // consume padding
        val pad = (paddedSize(size) - size).toInt()
        if (pad > 0) {
            var left = pad
            while (left > 0) {
                val skipped = input.skip(left.toLong())
                if (skipped <= 0) {
                    if (input.read() < 0) break
                    left--
                } else left -= skipped.toInt()
            }
        }
        return String(data, 0, off, Charsets.UTF_8)
    }

    private fun writePayloadToFile(input: InputStream, out: File, size: Long) {
        out.outputStream().use { os ->
            copyPayload(input, os, size)
        }
        // padding after file data
        val pad = (paddedSize(size) - size).toInt()
        if (pad > 0) {
            var left = pad.toLong()
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) break
                    left--
                } else left -= skipped
            }
        }
    }

    private fun copyPayload(input: InputStream, os: OutputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            os.write(buf, 0, n)
            left -= n
        }
    }

    /**
     * Parent dirs such as Fedora `/usr/bin` are archived as 0555. chmod 0555
     * before children are written makes FileOutputStream fail with EACCES;
     * those errors were swallowed and bash never landed on disk.
     */
    private fun ensureWritableParent(file: File) {
        val dir = file.parentFile ?: return
        if (!dir.exists()) dir.mkdirs()
        if (dir.isDirectory && !dir.canWrite()) {
            applyUnixMode(dir, 0b111_101_101)
        }
    }

    /**
     * Apply Unix permission bits (last 9 bits of mode). Essential for PRoot:
     * without exec bits, proot reports "is not executable" for /usr/bin/env, sh, etc.
     *
     * Directories always keep owner-write: Fedora/openSUSE ship `/usr/bin` as
     * 0555, and a non-writable dir both drops later archive members and blocks
     * guest package installs (proot is not real root on the host FS).
     */
    fun applyUnixMode(file: File, mode: Int) {
        if (!file.exists()) return
        var m = mode and 0x1FF
        val isDir = try {
            Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            file.isDirectory
        }
        if (isDir) {
            m = m or 0b010_000_000
        }
        val oct = Integer.toOctalString(m)
        val wantExec = (m and 0b001_001_001) != 0
        // Device: Os.chmod is authoritative.
        try {
            android.system.Os.chmod(file.absolutePath, m)
        } catch (_: Throwable) {
        }
        // Always reinforce — unit-test android.jar stubs are no-ops; also covers FS quirks.
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", oct, file.absolutePath)).waitFor()
        } catch (_: Throwable) {
        }
        try {
            val ownerWrite = m and 0b010_000_000 != 0
            file.setReadable(true, false)
            file.setWritable(ownerWrite, true)
            if (wantExec) {
                file.setExecutable(true, true)
                file.setExecutable(true, false)
            } else {
                file.setExecutable(false, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "chmod ${file.name} mode=$oct: ${e.message}")
        }
    }

    /** Guess mode when archive field is empty: executables under bin/sbin get 0755. */
    fun defaultFileMode(path: String): Int {
        val parent = path.substringBeforeLast('/', "")
        val inBin = parent.endsWith("/bin") || parent.endsWith("/sbin") ||
            parent == "bin" || parent == "sbin" ||
            parent.endsWith("/lib/klibc/bin")
        return if (inBin) 0b111_101_101 else 0b110_100_100 // 0755 / 0644
    }

    /**
     * Safety net after extract: ensure classic rootfs bins are executable even if
     * a few headers lacked mode bits.
     */
    fun ensureCriticalExecutables(rootfs: File) {
        val paths = listOf(
            "usr/bin/env", "bin/sh", "usr/bin/sh", "usr/bin/bash", "bin/bash",
            "usr/bin/dash", "bin/busybox", "usr/bin/apt-get", "usr/bin/dpkg",
            "usr/bin/perl", "usr/bin/python3"
        )
        for (rel in paths) {
            val f = File(rootfs, rel)
            // Only regular files — do not follow guest-absolute busybox symlinks on host
            if (f.isFile && !Files.isSymbolicLink(f.toPath())) {
                applyUnixMode(f, 0b111_101_101)
            }
        }
        // Walk common bin dirs (not whole tree — keep post-extract fast)
        for (dirRel in listOf(
            "bin", "sbin", "usr/bin", "usr/sbin",
            "usr/lib/klibc/bin", "usr/local/bin"
        )) {
            val dir = File(rootfs, dirRel)
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (f.isFile && !Files.isSymbolicLink(f.toPath())) {
                    try {
                        if (!f.canExecute()) applyUnixMode(f, 0b111_101_101)
                    } catch (_: Exception) {
                        applyUnixMode(f, 0b111_101_101)
                    }
                }
            }
        }
    }
}
