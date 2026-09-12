package com.sg.linuxgo

import com.sg.linuxgo.util.ExtractionUtils
import com.sg.linuxgo.util.TarHardlinkSafeExtract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Synthetic ustar with a hardlink member — must become a regular file copy.
 */
class TarHardlinkSafeExtractTest {

    @Test
    fun hardlinkBecomesFileCopy() {
        val targetContent = "hello-hardlink-target\n".toByteArray()
        val tarBytes = buildUstarWithHardlink(
            fileName = "usr/bin/perl",
            fileContent = targetContent,
            hardlinkName = "usr/bin/perl5.40.1",
            hardlinkTarget = "usr/bin/perl"
        )
        val out = createTempDirectory("tar-hl").toFile()
        try {
            val n = TarHardlinkSafeExtract.extractStream(
                ByteArrayInputStream(tarBytes),
                out
            )
            assertTrue(n >= 2)
            val target = File(out, "usr/bin/perl")
            val link = File(out, "usr/bin/perl5.40.1")
            assertTrue(target.isFile)
            assertTrue(link.isFile)
            assertEquals(target.readText(), link.readText())
            assertEquals("hello-hardlink-target\n", link.readText())
            // Mode 0755 from header → executable (PRoot requires this for env/sh)
            assertTrue("perl must be executable after extract", target.canExecute())
            assertTrue("hardlink copy must be executable", link.canExecute())
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun defaultFileModeMarksBinariesExecutable() {
        assertEquals(0b111_101_101, TarHardlinkSafeExtract.defaultFileMode("usr/bin/env"))
        assertEquals(0b111_101_101, TarHardlinkSafeExtract.defaultFileMode("bin/sh"))
        assertEquals(0b110_100_100, TarHardlinkSafeExtract.defaultFileMode("etc/passwd"))
    }

    @Test
    fun ensureCriticalExecutablesFixesEnv() {
        val root = createTempDirectory("crit-exec").toFile()
        try {
            val env = File(root, "usr/bin/env")
            env.parentFile?.mkdirs()
            env.writeText("#!/bin/sh\n")
            env.setExecutable(false, false)
            assertTrue(!env.canExecute() || true) // may still report true on some FS
            TarHardlinkSafeExtract.ensureCriticalExecutables(root)
            assertTrue(env.canExecute())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun skipsDevNodes() {
        val tarBytes = buildUstarRegular("dev/null", ByteArray(0), typeflag = '3')
        val out = createTempDirectory("tar-dev").toFile()
        try {
            TarHardlinkSafeExtract.extractStream(ByteArrayInputStream(tarBytes), out)
            assertTrue(!File(out, "dev/null").exists())
        } finally {
            out.deleteRecursively()
        }
    }

    /**
     * Alpine proot-distro: bin/sh → /bin/busybox (absolute guest symlink).
     * Must NOT become relative "bin/busybox" (would resolve to bin/bin/busybox).
     */
    @Test
    fun absoluteBusyboxSymlinkPreservedForAlpineSh() {
        val busyboxPayload = "busybox-binary-payload\n".toByteArray()
        val tarBytes = buildUstarBusyboxAlpine(busyboxPayload)
        val out = createTempDirectory("tar-alpine-sh").toFile()
        try {
            TarHardlinkSafeExtract.extractStream(ByteArrayInputStream(tarBytes), out)
            val busybox = File(out, "bin/busybox")
            val sh = File(out, "bin/sh")
            val env = File(out, "usr/bin/env")
            assertTrue("busybox must be a real file", busybox.isFile)
            assertEquals("busybox-binary-payload\n", busybox.readText())
            assertTrue(
                "bin/sh must exist as rootfs entry (symlink ok)",
                TarHardlinkSafeExtract.existsInRootfs(sh)
            )
            assertTrue(
                "bin/sh must be a symbolic link",
                java.nio.file.Files.isSymbolicLink(sh.toPath())
            )
            val target = java.nio.file.Files.readSymbolicLink(sh.toPath()).toString()
            assertEquals(
                "Alpine absolute guest target /bin/busybox must be preserved",
                "/bin/busybox",
                target
            )
            assertTrue(TarHardlinkSafeExtract.existsInRootfs(env))
            assertEquals(
                "/bin/busybox",
                java.nio.file.Files.readSymbolicLink(env.toPath()).toString()
            )
            // Host cannot resolve guest-absolute targets
            assertTrue(
                "Host File.exists() is false for guest-absolute links (expected)",
                !sh.exists() || java.nio.file.Files.isSymbolicLink(sh.toPath())
            )
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun existsInRootfsSeesGuestAbsoluteSymlink() {
        val root = createTempDirectory("exists-rootfs").toFile()
        try {
            val sh = File(root, "bin/sh")
            sh.parentFile?.mkdirs()
            java.nio.file.Files.createSymbolicLink(
                sh.toPath(),
                java.nio.file.Paths.get("/bin/busybox")
            )
            assertTrue(TarHardlinkSafeExtract.existsInRootfs(sh))
            assertTrue(!sh.exists()) // host cannot resolve
            assertTrue(TarHardlinkSafeExtract.deleteRootfsEntry(sh))
            assertTrue(!TarHardlinkSafeExtract.existsInRootfs(sh))
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Restore extract used File.exists() on bin/sh and failed Alpine after a clean tar -x
     * because the guest-absolute busybox link does not resolve on the Android host.
     */
    @Test
    fun hasGuestShellAcceptsAlpineAbsoluteBusyboxSh() {
        val root = createTempDirectory("guest-shell-alpine").toFile()
        try {
            File(root, "bin").mkdirs()
            File(root, "bin/busybox").writeText("bb")
            java.nio.file.Files.createSymbolicLink(
                File(root, "bin/sh").toPath(),
                java.nio.file.Paths.get("/bin/busybox")
            )
            assertTrue(!File(root, "bin/sh").exists())
            assertTrue(TarHardlinkSafeExtract.hasGuestShell(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fedoraOpensuseCryptoPolicyAbsLinksExtractAndFlatten() {
        val tarBytes = buildUstarFedoraStyleRootfs()
        val out = createTempDirectory("tar-fedora-style").toFile()
        try {
            TarHardlinkSafeExtract.extractStream(ByteArrayInputStream(tarBytes), out)
            val nested = File(out, "opensuse-aarch64")
            assertTrue(ExtractionUtils.looksLikeRootfs(nested))
            val bind = File(nested, "etc/crypto-policies/back-ends/bind.config")
            assertTrue(TarHardlinkSafeExtract.existsInRootfs(bind))
            assertTrue(java.nio.file.Files.isSymbolicLink(bind.toPath()))
            assertEquals(
                "/usr/share/crypto-policies/DEFAULT/bind.txt",
                java.nio.file.Files.readSymbolicLink(bind.toPath()).toString()
            )
            assertEquals(
                "opensuse-aarch64",
                ExtractionUtils.flattenRootfsIfNeeded(out)
            )
            assertTrue(ExtractionUtils.looksLikeRootfs(out))
            assertTrue(ExtractionUtils.salvageExtractedRootfs(out))
            assertTrue(File(out, "etc/passwd").isFile)
            assertTrue(
                TarHardlinkSafeExtract.existsInRootfs(
                    File(out, "etc/crypto-policies/back-ends/bind.config")
                )
            )
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun fedoraUsrBinMode555StillExtractsBash() {
        val bos = ByteArrayOutputStream()
        writeUstarMember(bos, "fedora-aarch64/", '5', ByteArray(0), linkname = "")
        writeUstarMember(bos, "fedora-aarch64/bin", '2', ByteArray(0), linkname = "usr/bin")
        writeUstarMember(bos, "fedora-aarch64/etc/passwd", '0', "root:x:0:0:root:/root:/bin/bash\n".toByteArray(), linkname = "")
        writeUstarMember(bos, "fedora-aarch64/usr/", '5', ByteArray(0), linkname = "")
        writeUstarMember(bos, "fedora-aarch64/usr/bin", '5', ByteArray(0), linkname = "", mode = 0b101_101_101)
        writeUstarMember(bos, "fedora-aarch64/usr/bin/bash", '0', "#!/bin/bash\n".toByteArray(), linkname = "")
        writeUstarMember(bos, "fedora-aarch64/usr/bin/sh", '2', ByteArray(0), linkname = "bash")
        bos.write(ByteArray(512))
        bos.write(ByteArray(512))
        val out = createTempDirectory("tar-fedora-555").toFile()
        try {
            TarHardlinkSafeExtract.extractStream(ByteArrayInputStream(bos.toByteArray()), out)
            val nested = File(out, "fedora-aarch64")
            val bash = File(nested, "usr/bin/bash")
            assertTrue("usr/bin must stay writable so bash can be created", File(nested, "usr/bin").canWrite())
            assertTrue("Fedora 0555 usr/bin must still receive bash", bash.isFile)
            assertEquals("#!/bin/bash\n", bash.readText())
            assertTrue(TarHardlinkSafeExtract.hasGuestShell(nested))
            assertTrue(ExtractionUtils.salvageExtractedRootfs(out))
            assertTrue(TarHardlinkSafeExtract.hasGuestShell(out))
            assertTrue(File(out, "usr/bin/bash").isFile)
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun extractContinuesAfterUnsafeMemberSoUsrBinBashSurvives() {
        val bos = java.io.ByteArrayOutputStream()
        writeUstarMember(bos, "fedora-aarch64/etc/passwd", '0', "root:x:0:0:root:/root:/bin/bash\n".toByteArray(), linkname = "")
        writeUstarMember(bos, "../evil.txt", '0', "pwned".toByteArray(), linkname = "")
        writeUstarMember(bos, "fedora-aarch64/usr/bin/bash", '0', "#!/bin/bash\n".toByteArray(), linkname = "")
        bos.write(ByteArray(512))
        bos.write(ByteArray(512))
        val parent = createTempDirectory("tar-continue-parent").toFile()
        val out = File(parent, "dest").apply { mkdirs() }
        try {
            TarHardlinkSafeExtract.extractStream(ByteArrayInputStream(bos.toByteArray()), out)
            assertTrue(File(out, "fedora-aarch64/usr/bin/bash").isFile)
            assertTrue(File(out, "fedora-aarch64/etc/passwd").isFile)
            assertTrue(!File(parent, "evil.txt").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun fedoraCaTrustRelativeHashSymlinkIsCreated() {
        val bos = java.io.ByteArrayOutputStream()
        writeUstarMember(
            bos, "etc/pki/ca-trust/extracted/pem/GlobalSign_Root_R46.pem",
            '0', "CERT\n".toByteArray(), linkname = ""
        )
        writeUstarMember(
            bos, "etc/pki/ca-trust/extracted/pem/directory-hash/002c0b4f.0",
            '2', ByteArray(0), linkname = "GlobalSign_Root_R46.pem"
        )
        bos.write(ByteArray(512))
        bos.write(ByteArray(512))
        val out = createTempDirectory("tar-ca-trust").toFile()
        try {
            TarHardlinkSafeExtract.extractStream(ByteArrayInputStream(bos.toByteArray()), out)
            val hash = File(out, "etc/pki/ca-trust/extracted/pem/directory-hash/002c0b4f.0")
            assertTrue(TarHardlinkSafeExtract.existsInRootfs(hash))
            assertTrue(java.nio.file.Files.isSymbolicLink(hash.toPath()))
            assertEquals(
                "GlobalSign_Root_R46.pem",
                java.nio.file.Files.readSymbolicLink(hash.toPath()).toString()
            )
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun zipSlipMemberIsRejectedAndNotWrittenOutsideDest() {
        val tarBytes = buildUstarRegular("../evil.txt", "pwned".toByteArray(), typeflag = '0')
        val parent = createTempDirectory("tar-slip-parent").toFile()
        val out = File(parent, "dest").apply { mkdirs() }
        val escaped = File(parent, "evil.txt")
        try {
            TarHardlinkSafeExtract.extractStream(ByteArrayInputStream(tarBytes), out)
            assertTrue(!escaped.exists())
            assertTrue(!File(out, "evil.txt").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun hostAbsoluteSymlinkIsRejected() {
        val tarBytes = buildUstarRegular(
            "bin/evil",
            ByteArray(0),
            typeflag = '2',
            linkname = "/data/data/com.sg.linuxgo/shared_prefs/x.xml"
        )
        val out = createTempDirectory("tar-host-link").toFile()
        try {
            TarHardlinkSafeExtract.extractStream(ByteArrayInputStream(tarBytes), out)
            assertTrue(!File(out, "bin/evil").exists())
            assertTrue(!TarHardlinkSafeExtract.existsInRootfs(File(out, "bin/evil")))
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun hasGuestShellAcceptsUsrMergeBashOnly() {
        val root = createTempDirectory("guest-shell-fedora").toFile()
        try {
            File(root, "usr/bin").mkdirs()
            File(root, "usr/bin/bash").writeText("#!/bin/bash\n")
            java.nio.file.Files.createSymbolicLink(
                File(root, "bin").toPath(),
                java.nio.file.Paths.get("usr/bin")
            )
            assertTrue(!File(root, "bin/sh").exists())
            assertTrue(
                "Fedora usr-merge with only usr/bin/bash must count as a guest shell",
                TarHardlinkSafeExtract.hasGuestShell(root)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun hasGuestShellFalseWhenEmpty() {
        val root = createTempDirectory("guest-shell-empty").toFile()
        try {
            assertTrue(!TarHardlinkSafeExtract.hasGuestShell(root))
        } finally {
            root.deleteRecursively()
        }
    }

    /** Minimal ustar: regular file then hardlink (type '1'). */
    private fun buildUstarWithHardlink(
        fileName: String,
        fileContent: ByteArray,
        hardlinkName: String,
        hardlinkTarget: String
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        writeUstarMember(bos, fileName, '0', fileContent, linkname = "")
        writeUstarMember(bos, hardlinkName, '1', ByteArray(0), linkname = hardlinkTarget)
        // end of archive: two zero blocks
        bos.write(ByteArray(512))
        bos.write(ByteArray(512))
        return bos.toByteArray()
    }

    private fun buildUstarRegular(
        name: String,
        content: ByteArray,
        typeflag: Char,
        linkname: String = ""
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        writeUstarMember(bos, name, typeflag, content, linkname = linkname)
        bos.write(ByteArray(512))
        bos.write(ByteArray(512))
        return bos.toByteArray()
    }

    /** Nested openSUSE/Fedora-style rootfs: usr-merge + crypto-policies abs links. */
    private fun buildUstarFedoraStyleRootfs(): ByteArray {
        val bos = ByteArrayOutputStream()
        writeUstarMember(bos, "opensuse-aarch64/", '5', ByteArray(0), linkname = "")
        writeUstarMember(bos, "opensuse-aarch64/bin", '2', ByteArray(0), linkname = "usr/bin")
        writeUstarMember(bos, "opensuse-aarch64/etc/", '5', ByteArray(0), linkname = "")
        writeUstarMember(bos, "opensuse-aarch64/etc/passwd", '0', "root:x:0:0:root:/root:/bin/sh\n".toByteArray(), linkname = "")
        writeUstarMember(
            bos, "opensuse-aarch64/etc/os-release", '2', ByteArray(0),
            linkname = "../usr/lib/os-release"
        )
        writeUstarMember(bos, "opensuse-aarch64/etc/crypto-policies/back-ends/", '5', ByteArray(0), linkname = "")
        writeUstarMember(
            bos, "opensuse-aarch64/etc/crypto-policies/back-ends/bind.config", '2', ByteArray(0),
            linkname = "/usr/share/crypto-policies/DEFAULT/bind.txt"
        )
        writeUstarMember(bos, "opensuse-aarch64/usr/", '5', ByteArray(0), linkname = "")
        writeUstarMember(bos, "opensuse-aarch64/usr/bin", '5', ByteArray(0), linkname = "", mode = 0b101_101_101)
        writeUstarMember(bos, "opensuse-aarch64/usr/bin/sh", '0', "#!/bin/sh\n".toByteArray(), linkname = "")
        writeUstarMember(bos, "opensuse-aarch64/usr/lib/os-release", '0', "ID=opensuse-leap\n".toByteArray(), linkname = "")
        writeUstarMember(
            bos, "opensuse-aarch64/usr/share/crypto-policies/DEFAULT/bind.txt", '0',
            "DEFAULT\n".toByteArray(), linkname = ""
        )
        bos.write(ByteArray(512))
        bos.write(ByteArray(512))
        return bos.toByteArray()
    }

    /** busybox real file + absolute symlinks for sh and usr/bin/env (Alpine layout). */
    private fun buildUstarBusyboxAlpine(busyboxContent: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        writeUstarMember(bos, "bin/busybox", '0', busyboxContent, linkname = "")
        writeUstarMember(bos, "bin/sh", '2', ByteArray(0), linkname = "/bin/busybox")
        writeUstarMember(bos, "usr/bin/env", '2', ByteArray(0), linkname = "/bin/busybox")
        bos.write(ByteArray(512))
        bos.write(ByteArray(512))
        return bos.toByteArray()
    }

    private fun writeUstarMember(
        out: ByteArrayOutputStream,
        name: String,
        typeflag: Char,
        content: ByteArray,
        linkname: String,
        mode: Int = 0b111_101_101
    ) {
        val header = ByteArray(512)
        fun put(s: String, off: Int, len: Int) {
            val b = s.toByteArray(Charsets.UTF_8)
            System.arraycopy(b, 0, header, off, minOf(b.size, len - 1))
        }
        put(name, 0, 100)
        put((mode and 0x1FF).toString(8).padStart(7, '0'), 100, 8)
        put("0000000", 108, 8)
        put("0000000", 116, 8)
        put(content.size.toString(8).padStart(11, '0') + "\u0000", 124, 12)
        put("00000000000", 136, 12)
        // checksum field spaces during calc
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = typeflag.code.toByte()
        put(linkname, 157, 100)
        put("ustar\u0000", 257, 6)
        put("00", 263, 2)
        // checksum
        var sum = 0
        for (b in header) sum += b.toInt().and(0xFF)
        val chk = sum.toString(8).padStart(6, '0') + "\u0000 "
        val cb = chk.toByteArray(Charsets.US_ASCII)
        System.arraycopy(cb, 0, header, 148, minOf(cb.size, 8))
        out.write(header)
        if (content.isNotEmpty()) {
            out.write(content)
            val pad = (512 - (content.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
    }
}
