package com.sg.linuxgo

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * One-shot private file for the backup/restore password so it is not placed
 * on a Service Intent extra (bugreports / dumpsys can log extras).
 */
object BackupPasswordStore {
    private const val DIR_NAME = "backup_pw"
    private const val MODE_OWNER_RW = 0x180 // 0600

    fun put(context: Context, password: String): String {
        val dir = dir(context)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        } else {
            dir.mkdirs()
        }
        val token = UUID.randomUUID().toString()
        val file = fileFor(dir, token)
        file.writeText(password)
        try {
            android.system.Os.chmod(file.absolutePath, MODE_OWNER_RW)
        } catch (_: Exception) {
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
        return token
    }

    fun take(context: Context, token: String?): String? {
        if (token.isNullOrBlank()) return null
        val file = fileFor(dir(context), token)
        if (!file.isFile) return null
        return try {
            file.readText()
        } finally {
            file.delete()
        }
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun dir(context: Context): File = File(context.noBackupFilesDir, DIR_NAME)

    private fun fileFor(dir: File, token: String): File {
        val safe = token.filter { it.isLetterOrDigit() || it == '-' }
        require(safe == token && safe.isNotEmpty()) { "invalid token" }
        return File(dir, safe)
    }
}
