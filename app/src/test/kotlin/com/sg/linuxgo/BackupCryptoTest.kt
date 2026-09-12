package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class BackupCryptoTest {

    @Test
    fun encryptDecryptRoundTrip() {
        val plain = "ssh-ed25519 AAAA fake-key-material\nbrowser-cookie=secret\n"
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(plain.toByteArray()), encrypted, "correct horse")
        val blob = encrypted.toByteArray()
        assertTrue(blob.size > plain.length)
        assertEquals(BackupCrypto.MAGIC_V2, blob.copyOfRange(0, 5).decodeToString())

        val out = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(blob), out, "correct horse")
        assertEquals(plain, out.toString(Charsets.UTF_8.name()))
    }

    @Test(expected = BackupCrypto.WrongPasswordException::class)
    fun wrongPasswordFails() {
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream("hello".toByteArray()), encrypted, "one")
        BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), ByteArrayOutputStream(), "two")
    }

    @Test
    fun fileHelpersDetectAndRoundTrip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "plbk-${System.nanoTime()}").apply { mkdirs() }
        try {
            val src = File(dir, "a.tar.gz").apply { writeText("rootfs-bytes") }
            val enc = File(dir, "a.plbk")
            BackupCrypto.encryptFile(src, enc, "pw")
            assertTrue(BackupCrypto.isEncryptedName(enc.name))
            assertTrue(BackupCrypto.isEncryptedFile(enc))
            assertFalse(BackupCrypto.isEncryptedFile(src))
            val dest = File(dir, "out.tar.gz")
            BackupCrypto.decryptFile(enc, dest, "pw")
            assertEquals("rootfs-bytes", dest.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun chunkedEncryptRoundTripsLargerThanOneChunk() {
        val chunk = BackupCrypto.CHUNK_BYTES
        val plain = ByteArray(chunk + chunk / 2) { i -> (i % 251).toByte() }
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(plain), encrypted, "chunk-pw")
        assertEquals(BackupCrypto.MAGIC_V2, encrypted.toByteArray().copyOfRange(0, 5).decodeToString())
        val out = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), out, "chunk-pw")
        assertTrue(plain.contentEquals(out.toByteArray()))
    }

    @Test
    fun legacyV1StillDecrypts() {
        val plain = "old-format-backup\n".toByteArray()
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptV1(ByteArrayInputStream(plain), encrypted, "legacy")
        assertEquals(BackupCrypto.MAGIC, encrypted.toByteArray().copyOfRange(0, 5).decodeToString())
        val out = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), out, "legacy")
        assertEquals("old-format-backup\n", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun decryptFileDeletesPartialOnFailure() {
        val dir = File(System.getProperty("java.io.tmpdir"), "plbk-fail-${System.nanoTime()}").apply { mkdirs() }
        try {
            val src = File(dir, "a.tar.gz").apply { writeText("rootfs-bytes") }
            val enc = File(dir, "a.plbk")
            BackupCrypto.encryptFile(src, enc, "pw")
            val truncated = File(dir, "trunc.plbk")
            truncated.writeBytes(enc.readBytes().copyOf(enc.length().toInt() - 8))
            val dest = File(dir, "out.tar.gz")
            val partial = File(dir, "out.tar.gz.partial")
            try {
                BackupCrypto.decryptFile(truncated, dest, "pw")
                org.junit.Assert.fail("expected truncated decrypt to fail")
            } catch (_: Exception) {
                // expected
            }
            assertFalse(dest.exists())
            assertFalse(partial.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun decryptFileWrongPasswordLeavesNoDest() {
        val dir = File(System.getProperty("java.io.tmpdir"), "plbk-wp-${System.nanoTime()}").apply { mkdirs() }
        try {
            val src = File(dir, "a.tar.gz").apply { writeText("rootfs-bytes") }
            val enc = File(dir, "a.plbk")
            BackupCrypto.encryptFile(src, enc, "one")
            val dest = File(dir, "out.tar.gz")
            try {
                BackupCrypto.decryptFile(enc, dest, "two")
                org.junit.Assert.fail("expected wrong password")
            } catch (_: BackupCrypto.WrongPasswordException) {
                // expected
            }
            assertFalse(dest.exists())
            assertFalse(File(dir, "out.tar.gz.partial").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun backupFileNameFilter() {
        assertTrue(BackupArchiveIo.isBackupFileName("Debian.tar.gz"))
        assertTrue(BackupArchiveIo.isBackupFileName("Debian.plbk"))
        assertFalse(BackupArchiveIo.isBackupFileName("Debian.pocketlinux.json"))
    }

    @Test
    fun sidecarStemStripsKnownArchiveSuffixes() {
        assertEquals("Debian_XFCE_v1.0.0", BackupArchiveIo.sidecarStem("Debian_XFCE_v1.0.0.tar.gz"))
        assertEquals("Debian_XFCE_v1.0.0", BackupArchiveIo.sidecarStem("Debian_XFCE_v1.0.0.plbk"))
        assertEquals("restore_temp", BackupArchiveIo.sidecarStem("restore_temp.tar.gz"))
        assertEquals("dlfile", BackupArchiveIo.sidecarStem("dlfile"))
        assertEquals("image", BackupArchiveIo.sidecarStem("image.tgz"))
    }

    @Test
    fun sidecarReadSkipsArchiveItselfForRestoreTempAndDlfile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "plside-${System.nanoTime()}").apply { mkdirs() }
        try {
            // Field crash: picker copies to cache/restore_temp.tar.gz. Old
            // `.replace(".plbk", …)` was a no-op and pointed at this file.
            val restoreTemp = File(dir, "restore_temp.tar.gz").apply {
                writeBytes(ByteArray(256 * 1024) { 0x1f })
            }
            assertFalse(BackupArchiveIo.isSafeSidecar(restoreTemp, restoreTemp))
            assertEquals(null, BackupArchiveIo.readSidecarText(restoreTemp))

            // Downloads / Samsung save as extensionless "dlfile".
            val dlfile = File(dir, "dlfile").apply {
                writeBytes(ByteArray(128 * 1024) { 0x1f })
            }
            assertEquals(null, BackupArchiveIo.readSidecarText(dlfile))
            assertFalse(BackupArchiveIo.isSafeSidecar(dlfile, dlfile))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sidecarReadAcceptsSmallJsonNextToArchive() {
        val dir = File(System.getProperty("java.io.tmpdir"), "plside-ok-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(dir, "MyBox.tar.gz").apply { writeBytes(ByteArray(64) { 1 }) }
            File(dir, "MyBox.pocketlinux.json").writeText("""{"config":{"distro":"debian"}}""")
            val text = BackupArchiveIo.readSidecarText(archive)
            assertTrue(text != null && text.contains("debian"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sidecarReadRejectsOversizedJson() {
        val dir = File(System.getProperty("java.io.tmpdir"), "plside-big-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(dir, "Big.tar.gz").apply { writeBytes(ByteArray(16) { 1 }) }
            File(dir, "Big.pocketlinux.json").writeBytes(
                ByteArray((BackupArchiveIo.MAX_SIDECAR_BYTES + 8).toInt()) { '{'.code.toByte() }
            )
            assertEquals(null, BackupArchiveIo.readSidecarText(archive))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun publishEncryptsWhenPasswordSet() {
        val dir = File(System.getProperty("java.io.tmpdir"), "plpub-${System.nanoTime()}").apply { mkdirs() }
        try {
            val staged = File(dir, "stage.tar.gz").apply { writeText("archive-body") }
            val dest = BackupArchiveIo.publishStagedArchive(staged, dir, "MyBox", "secret") {}
            assertTrue(dest.name.endsWith(BackupCrypto.FILE_SUFFIX))
            assertTrue(BackupCrypto.isEncryptedFile(dest))
            val restored = BackupArchiveIo.prepareArchiveForRestore(dest, "secret", dir) {}
            assertEquals("archive-body", restored.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = BackupCrypto.WrongPasswordException::class)
    fun restoreEncryptedWithoutPasswordFails() {
        val dir = File(System.getProperty("java.io.tmpdir"), "plcustom-${System.nanoTime()}").apply { mkdirs() }
        try {
            val staged = File(dir, "user.tar.gz").apply { writeText("user-content") }
            val dest = BackupArchiveIo.publishStagedArchive(
                staged, dir, "UserBackup", "user-secret-password"
            ) {}
            assertTrue(dest.name.endsWith(BackupCrypto.FILE_SUFFIX))

            BackupArchiveIo.prepareArchiveForRestore(dest, null, dir) {}
        } finally {
            dir.deleteRecursively()
        }
    }
}
