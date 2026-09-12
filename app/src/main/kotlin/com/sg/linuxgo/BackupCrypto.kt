package com.sg.linuxgo

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based AES-256-GCM for environment backups.
 *
 * New writes use [MAGIC_V2] chunked GCM so a multi-hundred-MB archive never
 * sits in a Conscrypt ByteArrayOutputStream (Crash L). [MAGIC] v1 files still
 * decrypt for older small backups.
 */
object BackupCrypto {
    const val MAGIC = "PLBK1"
    const val MAGIC_V2 = "PLBK2"
    const val FILE_SUFFIX = ".plbk"
    const val ITERATIONS = 100_000
    internal const val CHUNK_BYTES = 1024 * 1024
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val MAGIC_LEN = 5

    class WrongPasswordException : Exception("Wrong backup password")

    fun isEncryptedName(name: String): Boolean {
        return name.endsWith(FILE_SUFFIX, ignoreCase = true)
    }

    fun isEncryptedFile(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC_LEN + SALT_LEN) return false
        return file.inputStream().use { input ->
            val magic = readExactOrNull(input, MAGIC_LEN) ?: return@use false
            val s = magic.decodeToString()
            s == MAGIC || s == MAGIC_V2
        }
    }

    fun encrypt(input: InputStream, output: OutputStream, password: String) {
        require(password.isNotEmpty()) { "Password required" }
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        output.write(MAGIC_V2.toByteArray(Charsets.US_ASCII))
        output.write(salt)
        writeInt(output, CHUNK_BYTES)
        val plain = ByteArray(CHUNK_BYTES)
        while (true) {
            val n = readAtMost(input, plain)
            if (n <= 0) break
            val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
            val cipher = gcm(Cipher.ENCRYPT_MODE, key, iv)
            val packed = cipher.doFinal(plain, 0, n)
            output.write(iv)
            writeInt(output, packed.size)
            output.write(packed)
        }
    }

    fun decrypt(input: InputStream, output: OutputStream, password: String) {
        require(password.isNotEmpty()) { "Password required" }
        val magic = readExact(input, MAGIC_LEN).decodeToString()
        when (magic) {
            MAGIC_V2 -> decryptV2(input, output, password)
            MAGIC -> decryptV1(input, output, password)
            else -> throw IllegalArgumentException("Not an encrypted PocketLinux backup")
        }
    }

    fun encryptFile(src: File, dest: File, password: String) {
        try {
            src.inputStream().use { input ->
                dest.outputStream().use { output -> encrypt(input, output, password) }
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
    }

    fun decryptFile(src: File, dest: File, password: String) {
        val parent = dest.parentFile ?: throw IllegalArgumentException("Destination has no parent")
        parent.mkdirs()
        val tmp = File(parent, dest.name + ".partial")
        try {
            if (tmp.exists()) tmp.delete()
            src.inputStream().use { input ->
                tmp.outputStream().use { output -> decrypt(input, output, password) }
            }
            if (dest.exists() && !dest.delete()) {
                dest.delete()
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            dest.delete()
            throw t
        }
    }

    /** v1 single-blob GCM — tests and leftover small backups only. */
    internal fun encryptV1(input: InputStream, output: OutputStream, password: String) {
        require(password.isNotEmpty()) { "Password required" }
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(salt)
        output.write(iv)
        val cipher = gcm(Cipher.ENCRYPT_MODE, deriveKey(password, salt), iv)
        val data = input.readBytes()
        output.write(cipher.doFinal(data))
    }

    private fun decryptV2(input: InputStream, output: OutputStream, password: String) {
        val salt = readExact(input, SALT_LEN)
        val chunkSize = readInt(input)
        if (chunkSize <= 0 || chunkSize > 8 * 1024 * 1024) {
            throw IllegalArgumentException("Invalid encrypted backup")
        }
        val key = deriveKey(password, salt)
        val maxPacked = chunkSize + 32
        while (true) {
            val first = input.read()
            if (first < 0) return
            val iv = ByteArray(IV_LEN)
            iv[0] = first.toByte()
            val ivRest = readExact(input, IV_LEN - 1)
            System.arraycopy(ivRest, 0, iv, 1, IV_LEN - 1)
            val packedLen = readInt(input)
            if (packedLen < 16 || packedLen > maxPacked) {
                throw IllegalArgumentException("Truncated encrypted backup")
            }
            val packed = readExact(input, packedLen)
            val cipher = gcm(Cipher.DECRYPT_MODE, key, iv)
            val plain = try {
                cipher.doFinal(packed)
            } catch (e: AEADBadTagException) {
                throw WrongPasswordException()
            } catch (e: Exception) {
                throw unwrapAuth(e)
            }
            output.write(plain)
        }
    }

    private fun decryptV1(input: InputStream, output: OutputStream, password: String) {
        val salt = readExact(input, SALT_LEN)
        val iv = readExact(input, IV_LEN)
        val cipher = gcm(Cipher.DECRYPT_MODE, deriveKey(password, salt), iv)
        try {
            CipherInputStream(input, cipher).use { wrapped ->
                wrapped.copyTo(output)
            }
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        } catch (e: OutOfMemoryError) {
            throw Exception(
                "This password-protected backup is too large to open. " +
                    "Create a new backup after updating the app."
            )
        } catch (e: java.io.IOException) {
            throw unwrapAuth(e)
        }
    }

    private fun unwrapAuth(e: Exception): Exception {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is AEADBadTagException || cause is WrongPasswordException) {
                return WrongPasswordException()
            }
            cause = cause.cause
        }
        return e
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    private fun gcm(mode: Int, key: SecretKeySpec, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    private fun writeInt(output: OutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun readInt(input: InputStream): Int {
        val b = readExact(input, 4)
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        return readExactOrNull(input, n)
            ?: throw IllegalArgumentException("Truncated encrypted backup")
    }

    private fun readExactOrNull(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun readAtMost(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) return off
            off += r
        }
        return off
    }
}
