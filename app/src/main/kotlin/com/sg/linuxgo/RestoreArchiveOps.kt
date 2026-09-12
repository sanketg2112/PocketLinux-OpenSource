package com.sg.linuxgo

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayDeque

/** Toybox, archive extract/verify, scrub, host access. */

fun ContainerRestoreEngine.ensureToyboxPath(): String {
    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    var toyboxFile = File(nativeDir, "libtoybox.so")

    if (!toyboxFile.exists()) {
        nativeDir.parentFile?.listFiles()?.filter { it.isDirectory }?.forEach { abi ->
            val test = File(abi, "libtoybox.so")
            if (test.exists()) toyboxFile = test
        }
    }

    if (!toyboxFile.exists()) {
        Log.e(ContainerRestoreEngine.TAG, "libtoybox.so not found under ${nativeDir.absolutePath}")
        throw Exception("Toybox binary is missing. Please reinstall the application.")
    }

    try {
        toyboxFile.setReadable(true, true)
        toyboxFile.setWritable(true, true)
        toyboxFile.setExecutable(true, true)
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "setExecutable on toybox: ${e.message}")
    }

    val symlink = File(context.noBackupFilesDir, "toybox")
    try {
        symlink.delete()
        android.system.Os.symlink(toyboxFile.absolutePath, symlink.absolutePath)
        Log.d(ContainerRestoreEngine.TAG, "toybox symlink: ${symlink.absolutePath} -> ${toyboxFile.absolutePath}")
        return symlink.absolutePath
    } catch (e: Exception) {
        Log.e(ContainerRestoreEngine.TAG, "Failed to create toybox symlink: ${e.message}", e)
        throw Exception("Could not prepare backup engine (toybox symlink): ${e.message}")
    }
}

/**
 * Guess app distro id from a backup filename / display string.
 * Returns null when no distro keyword is found.
 */
fun ContainerRestoreEngine.detectDistroFromName(name: String): String? =
    com.sg.linuxgo.restore.detectDistroFromName(name)

/** Detect distro from extracted rootfs /etc/os-release. */
fun ContainerRestoreEngine.detectDistroFromRootfs(rootfs: File, fallback: String = "alpine"): String =
    com.sg.linuxgo.restore.detectDistroFromRootfs(rootfs, fallback)

/**
 * Read embedded `.pocketlinux_config.json` from a backup archive without
 * extracting the whole image (used so restore creates the right distro slot).
 */
fun ContainerRestoreEngine.peekBackupMetadata(archive: File): JSONObject? {
    if (!archive.exists() || archive.length() == 0L) return null
    val toybox = try {
        ensureToyboxPath()
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "peekBackupMetadata: no toybox: ${e.message}")
        return null
    }
    val isXz = archive.name.endsWith(".xz") || archive.name.endsWith(".txz")
    val candidates = listOf(
        "./.pocketlinux_config.json",
        ".pocketlinux_config.json",
        "./.linuxgo_config.json",
        ".linuxgo_config.json"
    )
    for (entry in candidates) {
        try {
            val pb = ProcessBuilder(
                toybox, "tar",
                if (isXz) "-xJO" else "-xzO",
                "-f", archive.absolutePath,
                entry
            ).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code == 0 && out.contains("{")) {
                val jsonStart = out.indexOf('{')
                val json = out.substring(jsonStart).trim()
                return JSONObject(json)
            }
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "peekBackupMetadata $entry: ${e.message}")
        }
    }
    return null
}

/**
 * Make [root] and its subdirectories host-writable/executable so the app
 * UID can write metadata and toybox can mkdir during extract.
 * Proot/pacman often leave dirs as 0555 → EACCES for host File.writeText.
 */
fun ContainerRestoreEngine.ensureHostDirAccess(root: File, maxDepth: Int = 12, onLog: (String) -> Unit = {}) {
    if (!root.exists()) {
        root.mkdirs()
    }
    var fixed = 0
    val stack = ArrayDeque<Pair<File, Int>>()
    stack.addLast(root to 0)
    while (stack.isNotEmpty()) {
        val (dir, depth) = stack.removeLast()
        try {
            // 0755 — owner rwx so host can create files; others rx
            try {
                android.system.Os.chmod(dir.absolutePath, 0x1ed)
            } catch (_: Exception) {
            }
            if (dir.setReadable(true, false)) { /* ok */ }
            if (dir.setWritable(true, false)) fixed++
            dir.setExecutable(true, false)
        } catch (_: Exception) {
        }
        if (depth >= maxDepth) continue
        val kids = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: continue
        for (f in kids) {
            try {
                if (Files.isSymbolicLink(f.toPath())) continue
            } catch (_: Exception) {
            }
            if (f.isDirectory) stack.addLast(f to (depth + 1))
            else {
                // Readable for tar -c
                try {
                    f.setReadable(true, false)
                } catch (_: Exception) {
                }
            }
        }
    }
    if (fixed > 0) {
        onLog("✓ Fixed host permissions on $fixed director(ies) under ${root.name}")
        Log.i(ContainerRestoreEngine.TAG, "ensureHostDirAccess fixed≈$fixed under ${root.absolutePath}")
    }
}

/**
 * Put toybox multicall first on PATH as gzip/zcat so create/extract don't
 * depend on inconsistent /system/bin gzip implementations.
 */
fun ContainerRestoreEngine.prepareToyboxArchiverPath(toyboxPath: String): String {
    val helpers = File(context.noBackupFilesDir, "tar_helpers").apply { mkdirs() }
    for (name in listOf("gzip", "zcat", "gunzip", "tar")) {
        val link = File(helpers, name)
        try {
            link.delete()
            android.system.Os.symlink(toyboxPath, link.absolutePath)
        } catch (_: Exception) {
            try {
                // Fallback: copy not needed; toybox must be invokable via symlink
                link.delete()
                Runtime.getRuntime().exec(arrayOf("ln", "-sf", toyboxPath, link.absolutePath)).waitFor()
            } catch (e2: Exception) {
                Log.w(ContainerRestoreEngine.TAG, "symlink $name: ${e2.message}")
            }
        }
    }
    val oldPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
    return "${helpers.absolutePath}:$oldPath"
}

/** Quick integrity check: archive must list etc/os-release (or etc/). */
fun ContainerRestoreEngine.verifyBackupArchive(archive: File, toyboxPath: String? = null): Boolean {
    if (!archive.exists() || archive.length() < 1024L) return false
    val toybox = toyboxPath ?: try {
        ensureToyboxPath()
    } catch (_: Exception) {
        return false
    }
    val isXz = archive.name.endsWith(".xz") || archive.name.endsWith(".txz")
    return try {
        val pb = ProcessBuilder(
            toybox, "tar",
            if (isXz) "-tJf" else "-tzf",
            archive.absolutePath
        ).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        pb.environment()["PATH"] = prepareToyboxArchiverPath(toybox)
        val process = pb.start()
        var sawEtc = false
        var lines = 0
        process.inputStream.bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                lines++
                val l = line ?: continue
                if (!com.sg.linuxgo.util.TarPathSafety.isSafeTarListLine(l)) {
                    process.destroy()
                    return false
                }
                if (l.contains("etc/os-release") || l == "./etc/" || l == "etc/" ||
                    l.startsWith("./etc/") || l.startsWith("etc/")
                ) {
                    sawEtc = true
                    // Drain a bit more then kill — listing multi‑GB can be slow
                    if (lines > 50) break
                }
                if (lines > 5000 && sawEtc) break
                if (lines > 20000) break
            }
        }
        process.destroy()
        // If we saw etc at all, good enough; also accept high entry count
        sawEtc || lines > 100
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "verifyBackupArchive: ${e.message}")
        false
    }
}

/**
 * List archive members and refuse extract if any path escapes the dest
 * (`..` segments). Used for user-chosen restore, not catalog install.
 */
fun ContainerRestoreEngine.assertArchiveMembersSafe(archive: File, toyboxPath: String) {
    val isXz = archive.name.endsWith(".xz") || archive.name.endsWith(".txz")
    val pb = ProcessBuilder(
        toyboxPath, "tar",
        if (isXz) "-tJf" else "-tzf",
        archive.absolutePath
    ).redirectErrorStream(true)
    pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
    pb.environment()["PATH"] = prepareToyboxArchiverPath(toyboxPath)
    val process = pb.start()
    try {
        process.inputStream.bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val name = line ?: continue
                val bad = com.sg.linuxgo.util.TarPathSafety.unsafeTarListReason(name)
                if (bad != null) {
                    process.destroy()
                    val shown = if (bad.length > 160) bad.take(160) + "…" else bad
                    Log.w(ContainerRestoreEngine.TAG, "unsafe archive member: $bad")
                    throw Exception(
                        "Backup archive has an unsafe path and was not restored: $shown"
                    )
                }
            }
        }
        val code = process.waitFor()
        if (code != 0 && code != 1) {
            throw Exception("Could not list backup archive (exit $code).")
        }
    } catch (e: Exception) {
        process.destroy()
        throw e
    }
}

/**
 * Toybox tar cannot archive sockets (S_IFSOCK → "unknown file type") and
 * often fails on FIFOs. Arch leaves gpg-agent sockets under
 * `/etc/pacman.d/gnupg/` after `pacman-key`, which dirties backups and can
 * produce archives that extract incompletely. Strip them before tar -c.
 */
fun ContainerRestoreEngine.scrubUnarchivableSpecialFiles(rootfs: File, onLog: (String) -> Unit = {}) {
    if (!rootfs.isDirectory) return
    var removed = 0
    val hotDirs = listOf(
        File(rootfs, "etc/pacman.d/gnupg"),
        File(rootfs, "root/.gnupg"),
        File(rootfs, "tmp"),
        File(rootfs, "run"),
        File(rootfs, "var/run")
    ).toMutableList()
    File(rootfs, "home").listFiles()?.filter { it.isDirectory }?.forEach { home ->
        hotDirs.add(File(home, ".gnupg"))
    }

    fun scrubDir(dir: File, maxDepth: Int) {
        if (maxDepth < 0 || !dir.isDirectory) return
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            try {
                val path = f.toPath()
                if (Files.isSymbolicLink(path)) continue
                // Prefer NIO type check — works for sockets/fifos on Android
                val isSocketOrFifo = try {
                    val attrs = Files.readAttributes(
                        path,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS
                    )
                    !attrs.isRegularFile && !attrs.isDirectory && !attrs.isSymbolicLink &&
                        attrs.isOther
                } catch (_: Exception) {
                    false
                }
                // Named gpg agent sockets always start with S.
                val isGpgSocketName = f.name.startsWith("S.") || f.name.startsWith("S.gpg")
                if (isSocketOrFifo || isGpgSocketName) {
                    if (f.delete() || f.deleteRecursively()) removed++
                    continue
                }
                if (f.isDirectory) scrubDir(f, maxDepth - 1)
            } catch (_: Exception) {
            }
        }
    }

    for (d in hotDirs) {
        if (d.isDirectory) scrubDir(d, 6)
    }
    // Also drop common agent socket basenames if they reappear elsewhere under etc
    listOf(
        "etc/pacman.d/gnupg/S.gpg-agent",
        "etc/pacman.d/gnupg/S.gpg-agent.browser",
        "etc/pacman.d/gnupg/S.gpg-agent.extra",
        "etc/pacman.d/gnupg/S.gpg-agent.ssh",
        "etc/pacman.d/gnupg/S.dirmngr",
        "etc/pacman.d/gnupg/S.keyboxd",
        "etc/pacman.d/gnupg/S.scdaemon"
    ).forEach { rel ->
        val f = File(rootfs, rel)
        if (f.exists() && f.delete()) removed++
    }
    if (removed > 0) {
        onLog("✓ Cleared $removed socket/FIFO file(s) before backup (Arch gpg-agent, etc.)")
        Log.i(ContainerRestoreEngine.TAG, "scrubUnarchivableSpecialFiles removed=$removed under ${rootfs.absolutePath}")
    }
}

/**
 * Arch-only post-restore fixes so pacman works after a backup that dropped
 * gpg-agent sockets / partial keyring state.
 */
fun ContainerRestoreEngine.prepareArchAfterRestore(rootfs: File, onLog: (String) -> Unit = {}) {
    val distro = detectDistroFromRootfs(rootfs)
    if (distro != "archlinux") return

    // Absolute XKB symlink breaks host-side Lorie (blank desktop after restore)
    ContainerRestoreEngine.fixAndResolveXkbConfigRoot(rootfs, onLog)

    // Drop any leftover agent sockets from the archive
    scrubUnarchivableSpecialFiles(rootfs)

    // Do not force SigLevel=Never. Keep an existing unsigned config and warn;
    // otherwise prefer signed packages. Verified-HTTPS Arch ARM mirrors only.
    try {
        val conf = File(rootfs, "etc/pacman.conf")
        if (conf.isFile) {
            val text = conf.readText()
            if (ArchPacmanSecurity.signaturesDisabled(text)) {
                ArchPacmanSecurity.writeUnsignedMarker(rootfs)
                onLog("⚠ Arch package signatures are off in this backup. Treat installs as untrusted.")
            } else {
                val (next, changed) = ArchPacmanSecurity.preferSignedConf(text)
                if (changed) {
                    conf.writeText(next)
                    onLog("✓ Arch pacman: ${ArchPacmanSecurity.SIGNED_SIGLEVEL}")
                }
            }
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "prepareArchAfterRestore pacman.conf: ${e.message}")
    }

    try {
        if (ArchPacmanSecurity.applySecureMirrorlist(rootfs)) {
            onLog("✓ Arch mirrors set to verified HTTPS")
        }
    } catch (e: Exception) {
        Log.w(ContainerRestoreEngine.TAG, "prepareArchAfterRestore mirrorlist: ${e.message}")
    }

    // Writable runtime dirs pacman/gpg expect
    listOf(
        "var/lib/pacman", "var/cache/pacman/pkg", "etc/pacman.d",
        "tmp", "run", "var/tmp"
    ).forEach { rel ->
        File(rootfs, rel).mkdirs()
    }
}

/**
 * Wipe [rootfsDir] and extract a PocketLinux backup .tar.gz into it.
 * Same flags as Settings restore (no --strip-components).
 *
 * @param onProgress fraction 0f..1f of extract estimate, plus human message
 * @param isCancelled return true to abort (kills tar process)
 */
fun ContainerRestoreEngine.extractBackupIntoRootfs(
    archive: File,
    rootfsDir: File,
    onProgress: (fraction: Float, message: String) -> Unit = { _, _ -> },
    isCancelled: () -> Boolean = { false },
    scanMemberPaths: Boolean = false
) {
    if (!archive.exists() || archive.length() == 0L) {
        throw Exception("Backup file missing or empty: ${archive.absolutePath}")
    }

    val toybox = ensureToyboxPath()
    File(toybox).setExecutable(true, false)

    val backupSize = archive.length()
    val abs = archive.absolutePath
    val isExternal = !abs.startsWith(context.filesDir.absolutePath) &&
        !abs.startsWith(context.cacheDir.absolutePath) &&
        !abs.startsWith(context.noBackupFilesDir.absolutePath)
    // Extract needs ~3×; external also needs a full staged copy in cache.
    val targetSize = if (backupSize > 0) {
        backupSize * (if (isExternal) 4 else 3)
    } else {
        900L * 1024 * 1024
    }
    val usable = try {
        context.filesDir.usableSpace
    } catch (_: Exception) {
        -1L
    }
    if (usable in 1 until targetSize) {
        val needMb = targetSize / (1024 * 1024)
        val haveMb = usable / (1024 * 1024)
        throw Exception("Not enough free storage to restore. Need ~${needMb} MB, have ${haveMb} MB.")
    }

    if (rootfsDir.exists()) {
        onProgress(0f, "Preparing container storage…")
        rootfsDir.deleteRecursively()
    }
    // Parent + rootfs must be host-writable before native tar mkdir
    rootfsDir.parentFile?.mkdirs()
    rootfsDir.mkdirs()
    ensureHostDirAccess(rootfsDir, maxDepth = 0)
    try {
        android.system.Os.chmod(rootfsDir.absolutePath, 0x1ed) // 0755
    } catch (_: Exception) {
        rootfsDir.setWritable(true, true)
        rootfsDir.setExecutable(true, true)
    }

    val isXz = archive.name.endsWith(".xz") || archive.name.endsWith(".txz")
    if (archive.name.endsWith(".zst")) {
        throw Exception("zstd images are not supported yet. Use .tar.gz.")
    }

    // External/FUSE storage can yield corrupt streams to native zcat. Stage
    // a private copy for large archives so extract is reliable.
    var extractFrom = archive
    var stagedCopy: File? = null
    if (isExternal) {
        onProgress(0.01f, "Copying backup to app storage…")
        val staged = File(context.cacheDir, "restore_stage_${System.currentTimeMillis()}.tar.gz")
        try {
            archive.inputStream().use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            if (staged.length() != archive.length()) {
                throw Exception(
                    "Staged copy size mismatch (${staged.length()} vs ${archive.length()})"
                )
            }
            extractFrom = staged
            stagedCopy = staged
            Log.i(ContainerRestoreEngine.TAG, "Staged restore archive ${staged.length() / (1024 * 1024)} MB → cache")
        } catch (e: Exception) {
            staged.delete()
            stagedCopy = null
            Log.w(ContainerRestoreEngine.TAG, "Stage copy failed, extracting from original: ${e.message}")
            // fall through with original path
        }
    }

    if (scanMemberPaths) {
        onProgress(0.015f, "Checking archive…")
        assertArchiveMembersSafe(extractFrom, toybox)
    }

    onProgress(0.02f, "Installing…")
    Log.i(ContainerRestoreEngine.TAG, "Extract ${extractFrom.name} (${extractFrom.length() / (1024 * 1024)} MB) → ${rootfsDir.absolutePath}")

    // -C BEFORE -f so chdir is unambiguous; also set process cwd as belt-and-suspenders.
    val pb = ProcessBuilder(
        toybox, "tar",
        "-C", rootfsDir.absolutePath,
        if (isXz) "-xJf" else "-xzf",
        extractFrom.absolutePath,
        "--exclude=dev/*", "--exclude=*/dev/*",
        // Never re-create gpg-agent sockets from an older buggy backup
        "--exclude=etc/pacman.d/gnupg/S.*",
        "--exclude=*/etc/pacman.d/gnupg/S.*"
    ).redirectErrorStream(true)

    pb.directory(rootfsDir)
    pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
    pb.environment()["PATH"] = prepareToyboxArchiverPath(toybox)

    val process = pb.start()
    val logOutput = StringBuilder()

    val monitor = Thread {
        try {
            while (process.isAlive) {
                if (isCancelled()) {
                    process.destroy()
                    break
                }
                val currentSize = directorySizeBytes(rootfsDir)
                // Size walk can outlive tar; skip if extract already finished/cancelled.
                if (isCancelled() || !process.isAlive) break
                val sizeMb = currentSize / (1024 * 1024)
                val frac = (currentSize.toDouble() / targetSize.toDouble()).coerceIn(0.0, 0.98).toFloat()
                onProgress(frac, "Installing… $sizeMb MB")
                Thread.sleep(2000)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(ContainerRestoreEngine.TAG, "extract monitor: ${e.message}")
        }
    }.also { it.isDaemon = true; it.start() }

    process.inputStream.bufferedReader().use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (logOutput.length < 4000) logOutput.append(l).append('\n')
        }
    }

    val exitCode = process.waitFor()
    monitor.interrupt()

    // Drop staged copy ASAP (frees space for a large rootfs)
    try {
        stagedCopy?.delete()
    } catch (_: Exception) {
    }

    if (isCancelled()) {
        throw Exception("Install aborted")
    }

    // tar exit 1 = warnings only (often OK for exclude / hardlink edge cases)
    if (exitCode != 0 && exitCode != 1) {
        throw Exception("Install extract failed (exit $exitCode): ${logOutput.takeLast(500)}")
    }

    // Structural sanity even when tar claimed success (truncated / OOM).
    // Alpine: bin/sh → /bin/busybox (guest-absolute); host File.exists() is false.
    val etcOk = File(rootfsDir, "etc").isDirectory
    val binOk = File(rootfsDir, "bin").exists() || File(rootfsDir, "usr/bin").isDirectory
    val shellOk = com.sg.linuxgo.util.TarHardlinkSafeExtract.hasGuestShell(rootfsDir)
    if (!etcOk || !binOk || !shellOk) {
        throw Exception(
            "Restored image looks incomplete (etc=$etcOk bin=$binOk shell=$shellOk). " +
                "tar said exit $exitCode. ${logOutput.takeLast(300)}"
        )
    }

    // Ensure post-extract dirs stay host-accessible for helpers/metadata
    ensureHostDirAccess(rootfsDir, maxDepth = 2)

    onProgress(1f, "✓ Installing complete")
}

/**
 * Android sets PR_SET_NO_NEW_PRIVS, so real setuid sudo cannot elevate, and
 * nested host libproot.so cannot be exec'd from inside the guest (Bionic
 * linker / nested ptrace). Terminal sessions therefore stay under outer
 * proot -0 (root capabilities) while presenting as the container user via
 * env. This installs a guest [sudo] that simply runs the command.
 */
