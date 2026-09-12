package com.sg.linuxgo.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream

class ExtractionUtils(private val context: Context) {
    private val TAG = "ExtractionUtils"

    // ✅ ANDROID 14+ FINAL SOLUTION:
    // ✅ BUNDLE TOYBOX AS PREBUILT NATIVE LIBRARY INSIDE APK
    // ✅ This is the ONLY method allowed. All other paths are blocked at kernel level.
    // ✅ FIXED SAMSUNG ISSUE: Samsung renames arm64-v8a to arm64 during extraction
    private fun getToyboxFile(): File {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir ?: context.filesDir.absolutePath
        // First try the jniLibs directory where the .so files are actually stored
        val jniLibsDir = File(nativeLibDir)
        val abiFolders = jniLibsDir.parentFile?.listFiles { file -> file.isDirectory }
        if (abiFolders != null) {
            for (abiFolder in abiFolders) {
                val testFile = File(abiFolder, "libtoybox.so")
                if (testFile.exists()) return testFile
            }
        }

        // Fallback: Try the native library directory
        val nativeDir = File(nativeLibDir)
        val file = File(nativeDir, "libtoybox.so")
        if (file.exists()) return file

        // Final fallback - return standard path for proper error logging
        return file
    }

    private val toyboxFile: File = getToyboxFile()

    private fun getExecutableSymlink(): String {
        val symlink = File(context.noBackupFilesDir, "toybox")
        if (!symlink.exists()) {
            try {
                // Remove old symlink if target changed or it's dead
                symlink.delete()
                android.system.Os.symlink(toyboxFile.absolutePath, symlink.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Symlink failed: ${e.message}")
                return toyboxFile.absolutePath
            }
        }
        return symlink.absolutePath
    }

    /**
     * Extract GZipped tar archive.
     * Prefer hardlink-as-copy stream extract (Android denies link()); fall back to toybox tar.
     */
    fun extractTarGz(tarGzFile: File, outputDir: File, progressCallback: ((count: Int) -> Unit)? = null) {
        if (!outputDir.exists()) outputDir.mkdirs()

        ensureToyboxExecutable()

        // gunzip to temp tar, then hardlink-safe extract
        val tempTar = File(context.cacheDir, "rootfs_temp_gz.tar")
        try {
            Log.d(TAG, "Decompressing gzip rootfs…")
            val gunzip = arrayOf(getExecutableSymlink(), "gzip", "-dc", tarGzFile.absolutePath)
            val p = Runtime.getRuntime().exec(gunzip)
            FileOutputStream(tempTar).use { out -> p.inputStream.copyTo(out) }
            // Drain stderr so we don't block
            Thread { try { p.errorStream.use { it.skip(Long.MAX_VALUE) } } catch (_: Exception) {} }.start()
            val code = p.waitFor()
            if (code != 0 || !tempTar.isFile || tempTar.length() == 0L) {
                // Fallback: toybox tar -xzf (may still fail on hardlinks)
                Log.w(TAG, "gzip -dc failed (exit $code) — trying toybox tar -xz")
                extractTarNative(tarGzFile, outputDir, xz = false, progressCallback)
            } else {
                extractTarHardlinkSafe(tempTar, outputDir, progressCallback)
            }
        } finally {
            tempTar.delete()
        }
    }

    /**
     * Extract XZ compressed tar archive.
     * xzcat → hardlink-as-copy extract (required for Kali NetHunter on Android).
     */
    fun extractTarXz(tarXzFile: File, outputDir: File, progressCallback: ((count: Int) -> Unit)? = null) {
        if (!outputDir.exists()) outputDir.mkdirs()

        ensureToyboxExecutable()

        Log.d(TAG, "Running XZ decompress…")
        val tempTar = File(context.cacheDir, "rootfs_temp.tar")
        try {
            val decompressCmd = arrayOf(
                getExecutableSymlink(),
                "xzcat", tarXzFile.absolutePath
            )
            val decompressProcess = Runtime.getRuntime().exec(decompressCmd)
            FileOutputStream(tempTar).use { out ->
                decompressProcess.inputStream.copyTo(out)
            }
            Thread {
                try { decompressProcess.errorStream.use { it.skip(Long.MAX_VALUE) } } catch (_: Exception) {}
            }.start()
            decompressProcess.waitFor()

            if (decompressProcess.exitValue() != 0 || !tempTar.isFile || tempTar.length() == 0L) {
                val err = try {
                    decompressProcess.errorStream.bufferedReader().readText()
                } catch (_: Exception) { "" }
                throw Exception("XZ decompress failed (exit ${decompressProcess.exitValue()}): $err")
            }

            extractTarHardlinkSafe(tempTar, outputDir, progressCallback)
        } finally {
            tempTar.delete()
        }
    }

    /**
     * Android-safe path: convert tar hardlinks to file copies (link() is EPERM on app storage).
     */
    private fun extractTarHardlinkSafe(
        tarFile: File,
        outputDir: File,
        progressCallback: ((count: Int) -> Unit)?
    ) {
        Log.d(TAG, "Hardlink-safe tar extract (${tarFile.length() / (1024 * 1024)} MB)…")
        try {
            TarHardlinkSafeExtract.extract(tarFile, outputDir, progressCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Hardlink-safe extract failed: ${e.message}", e)
            if (salvageExtractedRootfs(outputDir)) {
                Log.w(TAG, "Stream extract error but rootfs is usable — skipping toybox fallback")
                runPostExtract(outputDir)
                return
            }
            // Toybox `link()`/`symlink()` is EPERM on app storage (Fedora ca-trust
            // directory-hash). Do not wipe a partial stream extract and retry with
            // toybox — that dies in /etc and never reaches usr/bin/bash.
            throw Exception(
                "Rootfs extract failed: ${e.message}. Delete the container and retry.",
                e
            )
        }
        if (!salvageExtractedRootfs(outputDir)) {
            throw Exception(
                "Rootfs extract finished but no shell/os-release found under ${outputDir.name}. " +
                    "Delete the container and retry."
            )
        }
        runPostExtract(outputDir)
    }

    /**
     * Classic toybox tar -x (no strip-components). Used as fallback only.
     */
    private fun extractTarNative(
        archiveOrTar: File,
        outputDir: File,
        xz: Boolean,
        progressCallback: ((count: Int) -> Unit)?
    ) {
        val cmd = if (xz) {
            // not used currently
            arrayOf(
                getExecutableSymlink(), "tar", "-x",
                "--exclude=dev/*", "--exclude=*/dev/*",
                "-f", archiveOrTar.absolutePath, "-C", outputDir.absolutePath
            )
        } else if (archiveOrTar.name.endsWith(".gz") || archiveOrTar.name.endsWith(".tgz")) {
            arrayOf(
                getExecutableSymlink(), "tar", "-xz",
                "--exclude=dev/*", "--exclude=*/dev/*",
                "-f", archiveOrTar.absolutePath, "-C", outputDir.absolutePath
            )
        } else {
            arrayOf(
                getExecutableSymlink(), "tar", "-x",
                "--exclude=dev/*", "--exclude=*/dev/*",
                "-f", archiveOrTar.absolutePath, "-C", outputDir.absolutePath
            )
        }
        val process = Runtime.getRuntime().exec(cmd)
        val t1 = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var count = 0
                    reader.forEachLine {
                        count++
                        if (count % 100 == 0) progressCallback?.invoke(count)
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }
        val errText = StringBuilder()
        val t2 = Thread {
            try {
                process.errorStream.bufferedReader().use { r ->
                    r.forEachLine { errText.append(it).append('\n') }
                }
            } catch (_: Exception) {}
        }.apply { start() }
        val exitCode = process.waitFor()
        t1.join(2000)
        t2.join(2000)
        if (exitCode != 0) {
            if (salvageExtractedRootfs(outputDir) &&
                TarPathSafety.isMostlyBenignToyboxExtractLog(errText.toString())
            ) {
                Log.w(TAG, "Toybox tar exit $exitCode from guest-absolute symlinks — rootfs is usable")
                runPostExtract(outputDir)
                return
            }
            if (salvageExtractedRootfs(outputDir)) {
                Log.w(TAG, "Toybox tar exit $exitCode but rootfs is usable: $errText")
                runPostExtract(outputDir)
                return
            }
            throw Exception("Tar extraction failed (exit $exitCode): $errText")
        }
        if (!salvageExtractedRootfs(outputDir)) {
            throw Exception(
                "Tar extraction finished but no shell/os-release found under ${outputDir.name}."
            )
        }
        runPostExtract(outputDir)
    }

    companion object {
        /**
         * After extracting a rootfs tarball that has a single top-level directory
         * (e.g. `kali-arm64/`, `debian-trixie-aarch64/`), move its contents up so
         * [outputDir] is a real rootfs (`bin/`, `usr/`, `etc/`, …).
         *
         * Pure path logic — unit-tested without toybox.
         *
         * @return name of nested dir that was flattened, or null if already flat / no-op
         */
        fun flattenRootfsIfNeeded(outputDir: File): String? {
            if (!outputDir.isDirectory) return null
            val nested = findNestedRootfsDir(outputDir) ?: return null

            val nestedName = nested.name
            nested.listFiles()?.forEach { child ->
                val dest = File(outputDir, child.name)
                if (!movePath(child, dest)) {
                    Log.w("ExtractionUtils", "flatten: failed to move ${child.name}")
                }
            }
            // Never delete leftover nested/usr after only /etc was lifted —
            // that used to wipe Fedora's usr/bin/bash.
            if (nested.list().isNullOrEmpty()) {
                nested.delete()
            } else if (
                TarHardlinkSafeExtract.hasGuestShell(outputDir) &&
                !TarHardlinkSafeExtract.hasGuestShell(nested)
            ) {
                nested.deleteRecursively()
            }
            Log.d("ExtractionUtils", "flattenRootfsIfNeeded: lifted $nestedName → ${outputDir.name}")
            return nestedName
        }

        /**
         * Nested prefix (`fedora-aarch64/`) even when `/etc/passwd` is already
         * at the top from a partial flatten. Prefer a child that still has a shell.
         */
        internal fun findNestedRootfsDir(outputDir: File): File? {
            if (TarHardlinkSafeExtract.hasGuestShell(outputDir)) return null
            val children = outputDir.listFiles()?.filter { it.name != "." && it.name != ".." }
                ?: return null
            children.firstOrNull { it.isDirectory && TarHardlinkSafeExtract.hasGuestShell(it) }
                ?.let { return it }
            if (children.size == 1 && children[0].isDirectory) {
                val only = children[0]
                if (looksLikeRootfs(only) || TarHardlinkSafeExtract.hasGuestShell(only)) return only
            }
            return children.firstOrNull { it.isDirectory && looksLikeRootfs(it) }
        }

        /**
         * Flatten a nested prefix dir (opensuse-aarch64/, fedora-aarch64/) and
         * report whether [outputDir] is now a usable guest rootfs.
         *
         * A tree that only has /etc/passwd (extract aborted during `etc/`) must
         * not count as success — Fedora/openSUSE would then skip toybox fallback
         * and later fail with "bin/sh not found".
         */
        fun salvageExtractedRootfs(outputDir: File): Boolean {
            repeat(4) {
                if (TarHardlinkSafeExtract.hasGuestShell(outputDir)) return true
                if (flattenRootfsIfNeeded(outputDir) == null) return@repeat
            }
            return TarHardlinkSafeExtract.hasGuestShell(outputDir)
        }

        /** True if [dir] looks like a Linux rootfs (has shell and/or etc). */
        fun looksLikeRootfs(dir: File): Boolean {
            if (!dir.isDirectory) return false
            // Alpine / Fedora / openSUSE: guest-absolute or relative symlinks —
            // host File.exists() may be false (NOFOLLOW / dangling until flatten).
            return TarHardlinkSafeExtract.hasGuestShell(dir) ||
                TarHardlinkSafeExtract.existsInRootfs(File(dir, "etc/os-release")) ||
                TarHardlinkSafeExtract.existsInRootfs(File(dir, "etc/passwd")) ||
                File(dir, "usr/lib/os-release").isFile
        }

        private fun movePath(src: File, dest: File): Boolean {
            val srcLink = try {
                java.nio.file.Files.isSymbolicLink(src.toPath())
            } catch (_: Exception) {
                false
            }
            if (srcLink) return moveSymlink(src, dest)
            if (!src.exists() && !TarHardlinkSafeExtract.existsInRootfs(src)) return false
            if (src.isDirectory) {
                if (!dest.exists() && src.renameTo(dest)) return true
                dest.mkdirs()
                src.listFiles()?.forEach { child ->
                    movePath(child, File(dest, child.name))
                }
                if (src.list().isNullOrEmpty()) src.delete()
                return dest.isDirectory
            }
            if (dest.exists() || TarHardlinkSafeExtract.existsInRootfs(dest)) return true
            dest.parentFile?.mkdirs()
            if (src.renameTo(dest)) return true
            return try {
                src.copyTo(dest, overwrite = false)
                src.delete()
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun moveSymlink(src: File, dest: File): Boolean {
            if (TarHardlinkSafeExtract.existsInRootfs(dest)) {
                TarHardlinkSafeExtract.deleteRootfsEntry(src)
                return true
            }
            dest.parentFile?.mkdirs()
            if (src.renameTo(dest)) return true
            return try {
                val target = java.nio.file.Files.readSymbolicLink(src.toPath())
                TarHardlinkSafeExtract.deleteRootfsEntry(dest)
                java.nio.file.Files.createSymbolicLink(dest.toPath(), target)
                TarHardlinkSafeExtract.deleteRootfsEntry(src)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Ensures Toybox has proper executable permissions before every run
     * Works around Android security restrictions - compatible with Android 14+
     */
    fun ensureToyboxExecutable() {
        // ✅ ANDROID 14+ ONLY: Execute MUST be owner ONLY (mode 0700)
        // Any other permission mode will be blocked by SELinux policy
        toyboxFile.setReadable(true, true)
        toyboxFile.setWritable(true, true)
        toyboxFile.setExecutable(true, true)

        // Verify permissions and retry if needed
        if (!toyboxFile.canExecute()) {
            Log.w(TAG, "Toybox not executable - applying fallback permission fix")

            // Only 0700 is allowed on Android 14+
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "0700", toyboxFile.absolutePath)).waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "Chmod failed: ${e.message}")
            }
        }

        if (!toyboxFile.exists()) {
            Log.e(TAG, "CRITICAL ERROR: Toybox binary NOT FOUND at path: ${toyboxFile.absolutePath}")
            throw Exception("Toybox binary is missing. Please reinstall application.")
        }

        if (!toyboxFile.canExecute()) {
            Log.e(TAG, "FINAL VERIFICATION FAILED - Toybox STILL cannot be executed")
            Log.e(TAG, "Path: ${toyboxFile.absolutePath}")
            Log.e(TAG, "Can read: ${toyboxFile.canRead()}")
            Log.e(TAG, "Can write: ${toyboxFile.canWrite()}")
            Log.e(TAG, "File size: ${toyboxFile.length()} bytes")

            throw Exception("Cannot execute Toybox. This is an Android security restriction on your device.")
        }

        Log.d(TAG, "✅ Toybox verified executable successfully")
        Log.d(TAG, "📍 Toybox path: ${toyboxFile.absolutePath}")
        Log.d(TAG, "📦 File size: ${toyboxFile.length()} bytes")
    }

    /**
     * Post extract operations for rootfs archives
     */
    private fun runPostExtract(outputDir: File) {
        // Hardlink-safe stream extract must still mark bins executable (PRoot checks this).
        TarHardlinkSafeExtract.ensureCriticalExecutables(outputDir)

        val busyboxFile = File(outputDir, "bin/busybox")
        val toyboxFile = File(outputDir, "bin/toybox")
        val shFile = File(outputDir, "bin/sh")
        val envFile = File(outputDir, "usr/bin/env")

        val shellSource = when {
            toyboxFile.isFile && !java.nio.file.Files.isSymbolicLink(toyboxFile.toPath()) -> toyboxFile
            busyboxFile.isFile && !java.nio.file.Files.isSymbolicLink(busyboxFile.toPath()) -> busyboxFile
            File(outputDir, "usr/bin/bash").isFile -> File(outputDir, "usr/bin/bash")
            File(outputDir, "usr/bin/sh").isFile &&
                !java.nio.file.Files.isSymbolicLink(File(outputDir, "usr/bin/sh").toPath()) ->
                File(outputDir, "usr/bin/sh")
            else -> null
        }

        if (shellSource != null) {
            TarHardlinkSafeExtract.applyUnixMode(shellSource, 0b111_101_101)
            // Alpine: bin/sh is already a guest-absolute symlink (/bin/busybox). Host
            // File.exists() is false for those, but the entry is valid under proot —
            // never File.copyTo over a symlink (open follows → ENOENT install crash).
            val shPresent = TarHardlinkSafeExtract.existsInRootfs(shFile)
            if (!shPresent) {
                shFile.parentFile?.mkdirs()
                val usrSh = File(outputDir, "usr/bin/sh")
                if (usrSh.isFile || TarHardlinkSafeExtract.existsInRootfs(usrSh)) {
                    try {
                        TarHardlinkSafeExtract.deleteRootfsEntry(shFile)
                        java.nio.file.Files.createSymbolicLink(
                            shFile.toPath(),
                            java.nio.file.Paths.get("/usr/bin/sh")
                        )
                    } catch (_: Exception) {
                        TarHardlinkSafeExtract.deleteRootfsEntry(shFile)
                        shellSource.copyTo(shFile, overwrite = true)
                        TarHardlinkSafeExtract.applyUnixMode(shFile, 0b111_101_101)
                    }
                } else if (busyboxFile.isFile) {
                    // Prefer guest-absolute busybox link (matches Alpine rootfs layout)
                    try {
                        TarHardlinkSafeExtract.deleteRootfsEntry(shFile)
                        java.nio.file.Files.createSymbolicLink(
                            shFile.toPath(),
                            java.nio.file.Paths.get("/bin/busybox")
                        )
                    } catch (_: Exception) {
                        TarHardlinkSafeExtract.deleteRootfsEntry(shFile)
                        shellSource.copyTo(shFile, overwrite = true)
                        TarHardlinkSafeExtract.applyUnixMode(shFile, 0b111_101_101)
                    }
                } else {
                    TarHardlinkSafeExtract.deleteRootfsEntry(shFile)
                    shellSource.copyTo(shFile, overwrite = true)
                    TarHardlinkSafeExtract.applyUnixMode(shFile, 0b111_101_101)
                }
            } else if (shFile.isFile && !java.nio.file.Files.isSymbolicLink(shFile.toPath())) {
                TarHardlinkSafeExtract.applyUnixMode(shFile, 0b111_101_101)
            }
        }
        // env may be absolute symlink to busybox — only chmod regular files
        if (envFile.isFile && !java.nio.file.Files.isSymbolicLink(envFile.toPath())) {
            TarHardlinkSafeExtract.applyUnixMode(envFile, 0b111_101_101)
        }

        // Android/tar extract often leaves directories as 0700. Pacman then floods
        // "warning: directory permissions differ (filesystem: 700 package: 755)"
        // for every path in every package. Normalize dirs to 0755 after extract.
        fixExtractedDirPermissions(outputDir)
    }

    /**
     * Walk the extracted rootfs and chmod directories that are owner-only (0700/etc.)
     * to 0755 so guest package managers do not treat them as permission mismatches.
     */
    private fun fixExtractedDirPermissions(outputDir: File) {
        try {
            val root = outputDir.absolutePath
            // Prefer a single find+chmod batch (toybox or system find).
            val cmd = arrayOf(
                "sh", "-c",
                "find '$root' -type d \\( -perm 700 -o -perm 770 -o -perm 750 -o -perm 711 \\) " +
                    "-exec chmod 755 {} + 2>/dev/null; " +
                    "for d in '' bin sbin lib lib64 usr usr/bin usr/lib usr/share etc var var/lib " +
                    "var/cache opt home root tmp boot; do " +
                    "  [ -d '$root/'\"\$d\" ] && chmod 755 '$root/'\"\$d\" 2>/dev/null || true; " +
                    "done"
            )
            val p = Runtime.getRuntime().exec(cmd)
            // Drain streams so we never block on a full pipe.
            Thread { try { p.inputStream.use { it.skip(Long.MAX_VALUE) } } catch (_: Exception) {} }.start()
            Thread { try { p.errorStream.use { it.skip(Long.MAX_VALUE) } } catch (_: Exception) {} }.start()
            val code = p.waitFor()
            Log.d(TAG, "fixExtractedDirPermissions exit=$code for $root")
        } catch (e: Exception) {
            Log.w(TAG, "fixExtractedDirPermissions failed: ${e.message}")
        }
    }

    /**
     * Test archive integrity using BusyBox xz -t or gzip -t
     */
    fun isArchiveValid(archiveFile: File): Boolean {
        if (!archiveFile.exists() || archiveFile.length() == 0L) return false
        ensureToyboxExecutable()
        return try {
            val cmd = if (archiveFile.name.endsWith(".xz")) {
                arrayOf(getExecutableSymlink(), "xzcat", archiveFile.absolutePath)
            } else {
                arrayOf(getExecutableSymlink(), "gzip", "-t", archiveFile.absolutePath)
            }
            val process = Runtime.getRuntime().exec(cmd)
            
            // Drain stdout and stderr in background threads to avoid blocking/deadlock
            val tOut = Thread {
                try {
                    process.inputStream.use { it.skip(Long.MAX_VALUE) }
                } catch (e: Exception) {}
            }.apply { start() }
            
            val tErr = Thread {
                try {
                    process.errorStream.use { it.skip(Long.MAX_VALUE) }
                } catch (e: Exception) {}
            }.apply { start() }
            
            val exitCode = process.waitFor()
            tOut.join(1000)
            tErr.join(1000)
            
            Log.d(TAG, "Archive validation for ${archiveFile.name} returned exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Archive validation failed for ${archiveFile.name}: ${e.message}")
            false
        }
    }

    /**
     * Auto detect archive type and extract
     */
    fun autoExtract(archiveFile: File, outputDir: File, progressCallback: ((count: Int) -> Unit)? = null) {
        when {
            archiveFile.name.endsWith(".xz") -> extractTarXz(archiveFile, outputDir, progressCallback)
            archiveFile.name.endsWith(".gz") -> extractTarGz(archiveFile, outputDir, progressCallback)
            else -> throw Exception("Unknown archive format: ${archiveFile.name}")
        }
    }
}