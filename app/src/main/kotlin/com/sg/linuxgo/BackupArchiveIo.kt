package com.sg.linuxgo

import java.io.File
import java.nio.charset.StandardCharsets

/** Shared-storage publish / restore helpers for plaintext and password-encrypted backups. */
object BackupArchiveIo {
    const val SIDECAR_SUFFIX = ".pocketlinux.json"

    /**
     * Sidecar metadata is a tiny JSON object. Anything larger is the archive
     * (or garbage) and must never be slurped with [File.readText].
     */
    const val MAX_SIDECAR_BYTES = 64L * 1024L

    fun isBackupFileName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(BackupCrypto.FILE_SUFFIX)
    }

    /**
     * Stem used for the sidecar next to a backup:
     * `Debian.tar.gz` / `Debian.plbk` / `dlfile` → `Debian` / `Debian` / `dlfile`.
     */
    fun sidecarStem(fileName: String): String {
        val suffixes = listOf(
            SIDECAR_SUFFIX,
            BackupCrypto.FILE_SUFFIX,
            ".tar.gz",
            ".tar.xz",
            ".tgz",
            ".tar"
        )
        for (suffix in suffixes) {
            if (fileName.endsWith(suffix, ignoreCase = true)) {
                return fileName.dropLast(suffix.length)
            }
        }
        return fileName
    }

    fun expectedSidecarFile(archive: File): File {
        val parent = archive.parentFile ?: File(".")
        return File(parent, sidecarStem(archive.name) + SIDECAR_SUFFIX)
    }

    /** Small `.pocketlinux.json` next to the archive — never the archive itself. */
    fun isSafeSidecar(sidecar: File, archive: File): Boolean {
        if (!sidecar.isFile) return false
        if (!sidecar.name.endsWith(SIDECAR_SUFFIX, ignoreCase = true)) return false
        val len = sidecar.length()
        if (len < 3L || len > MAX_SIDECAR_BYTES) return false
        return try {
            sidecar.canonicalFile != archive.canonicalFile
        } catch (_: Exception) {
            sidecar.absoluteFile.normalize() != archive.absoluteFile.normalize()
        }
    }

    /**
     * Read sidecar JSON text next to [archive], or null. Never allocates more
     * than [MAX_SIDECAR_BYTES]; never treats the archive as the sidecar.
     */
    fun readSidecarText(archive: File): String? {
        val sidecar = expectedSidecarFile(archive)
        if (!isSafeSidecar(sidecar, archive)) return null
        return try {
            sidecar.inputStream().use { input ->
                val cap = MAX_SIDECAR_BYTES.toInt()
                val buf = ByteArray(cap + 1)
                val n = input.read(buf)
                if (n < 3 || n > cap) return null
                String(buf, 0, n, StandardCharsets.UTF_8)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Copy [stagedTar] to [backupDir]/[name].tar.gz, or encrypt to [name].plbk when
     * [password] is non-blank.
     */
    fun publishStagedArchive(
        stagedTar: File,
        backupDir: File,
        name: String,
        password: String?,
        onLog: (String) -> Unit
    ): File {
        val encrypt = !password.isNullOrEmpty()
        val dest = if (encrypt) {
            File(backupDir, "$name${BackupCrypto.FILE_SUFFIX}")
        } else {
            File(backupDir, "$name.tar.gz")
        }
        if (dest.exists()) dest.delete()
        if (encrypt) {
            onLog("Encrypting backup with your password…")
            BackupCrypto.encryptFile(stagedTar, dest, password)
        } else {
            stagedTar.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!encrypt && dest.length() != stagedTar.length()) {
            dest.delete()
            throw Exception("Copy size mismatch (${dest.length()} vs ${stagedTar.length()})")
        }
        if (encrypt && dest.length() < stagedTar.length()) {
            // GCM adds a tag; encrypted file should not be smaller than a tiny archive.
            if (dest.length() < 32L) {
                dest.delete()
                throw Exception("Encrypted backup is empty")
            }
        }
        return dest
    }

    /**
     * If [file] is encrypted, decrypt into [cacheDir] using [password].
     * Encrypted archives require a caller-supplied password.
     * Returns the plaintext tar the restore engine can extract.
     */
    fun prepareArchiveForRestore(
        file: File,
        password: String?,
        cacheDir: File,
        onLog: (String) -> Unit
    ): File {
        val encrypted = BackupCrypto.isEncryptedName(file.name) || BackupCrypto.isEncryptedFile(file)
        if (!encrypted) return file
        if (password.isNullOrEmpty()) {
            throw BackupCrypto.WrongPasswordException()
        }
        onLog("Decrypting password-protected backup…")
        val dest = File(cacheDir, "restore_decrypted_${System.currentTimeMillis()}.tar.gz")
        try {
            BackupCrypto.decryptFile(file, dest, password)
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
        return dest
    }
}
