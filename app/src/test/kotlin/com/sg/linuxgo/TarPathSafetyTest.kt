package com.sg.linuxgo

import com.sg.linuxgo.util.TarPathSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TarPathSafetyTest {

    @Test
    fun rejectsDotDotMemberNames() {
        assertFalse(TarPathSafety.isSafeMemberName("../evil"))
        assertFalse(TarPathSafety.isSafeMemberName("usr/../../etc/passwd"))
        assertFalse(TarPathSafety.isSafeMemberName("foo/../../../shared_prefs/x.xml"))
        assertTrue(TarPathSafety.isSafeMemberName("usr/bin/env"))
        assertTrue(TarPathSafety.isSafeMemberName("./etc/os-release"))
        assertTrue(TarPathSafety.isSafeMemberName("/bin/sh"))
        assertTrue(TarPathSafety.isSafeMemberName("."))
        assertTrue(
            TarPathSafety.isSafeMemberName(
                "usr/lib/gcc/aarch64-linux-gnu/12/../../../lib/libgcc_s.so.1"
            )
        )
    }

    @Test
    fun toyboxListLineAllowsRelativeLibSymlinks() {
        assertTrue(
            TarPathSafety.isSafeTarListLine(
                "usr/lib/aarch64-linux-gnu/libz.so -> ../../lib/aarch64-linux-gnu/libz.so.1"
            )
        )
        assertTrue(TarPathSafety.isSafeTarListLine("bin/sh -> /bin/busybox"))
        assertTrue(TarPathSafety.isSafeTarListLine("usr/bin/perl5.40.1 link to usr/bin/perl"))
        assertTrue(TarPathSafety.isSafeTarListLine("lib64 -> ../lib"))
        assertTrue(TarPathSafety.isSafeTarListLine("usr/lib/libEGL.so -> /system/lib64/libEGL.so"))
        assertTrue(TarPathSafety.isSafeTarListLine("etc/mtab -> /proc/self/mounts"))
        assertFalse(TarPathSafety.isSafeTarListLine("../evil"))
        assertFalse(
            TarPathSafety.isSafeTarListLine("ok -> /data/data/com.sg.linuxgo/shared_prefs/x.xml")
        )
        val hostRootfsLink =
            "./home/PocketLinux/.ssh/.l2s.known_hosts0001 -> " +
                "/data/data/com.sg.linuxgo/files/containers/78147ce5/rootfs/" +
                "home/PocketLinux/.ssh/.l2s.known_hosts0001.0001"
        assertTrue(TarPathSafety.isSafeTarListLine(hostRootfsLink))
        assertTrue(
            TarPathSafety.isHostPathInsideContainerRootfs(
                "/data/user/0/com.sg.linuxgo.debug/files/containers/abc/rootfs/home/x"
            )
        )
        assertFalse(
            TarPathSafety.isHostPathInsideContainerRootfs(
                "/data/data/com.sg.linuxgo/files/containers/abc/rootfs/../../shared_prefs/x.xml"
            )
        )
        assertEquals(
            "usr/lib/libz.so",
            TarPathSafety.tarListMemberName("usr/lib/libz.so -> ../../lib/libz.so.1")
        )
        assertNull(TarPathSafety.unsafeTarListReason("lib64 -> ../lib"))
        assertNull(TarPathSafety.unsafeTarListReason("usr/lib/libEGL.so -> /system/lib64/libEGL.so"))
    }

    @Test
    fun resolveStaysUnderDest() {
        val dest = createTempDirectory("tar-dest").toFile()
        try {
            dest.mkdirs()
            assertNotNull(TarPathSafety.resolveUnderDest(dest, "usr/bin/sh"))
            assertEquals(
                dest.absoluteFile.normalize().absolutePath,
                TarPathSafety.resolveUnderDest(dest, ".")!!.absoluteFile.normalize().absolutePath
            )
            assertNull(TarPathSafety.resolveUnderDest(dest, "../outside"))
            assertNull(TarPathSafety.resolveUnderDest(dest, "a/../../outside"))
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun alpineBusyboxLinkAllowedHostDataRejected() {
        val dest = createTempDirectory("tar-link").toFile()
        try {
            dest.mkdirs()
            assertTrue(TarPathSafety.isSafeLinkTarget(dest, "bin/sh", "/bin/busybox"))
            assertTrue(TarPathSafety.isSafeLinkTarget(dest, "usr/bin/python3", "../lib/python3"))
            assertTrue(TarPathSafety.isSafeLinkTarget(dest, "lib64", "../lib"))
            assertTrue(TarPathSafety.isSafeLinkTarget(dest, "usr/lib/libEGL.so", "/system/lib64/libEGL.so"))
            assertFalse(TarPathSafety.isSafeLinkTarget(dest, "bin/sh", "/data/data/com.sg.linuxgo/shared_prefs/x.xml"))
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun collapseAllowsRelativeLinkInsideDest() {
        val dest = createTempDirectory("tar-collapse").toFile()
        try {
            dest.mkdirs()
            val resolved = TarPathSafety.collapseUnderDest(dest, "usr/bin/../lib/python3")
            assertNotNull(resolved)
            assertTrue(resolved!!.absolutePath.endsWith("usr/lib/python3") ||
                resolved.absolutePath.endsWith("usr${File.separator}lib${File.separator}python3"))
            assertNull(TarPathSafety.collapseUnderDest(dest, "usr/../../outside"))
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun guestAbsoluteCryptoPolicyLinkIsNotZipSlip() {
        val dest = createTempDirectory("tar-crypto").toFile()
        try {
            dest.mkdirs()
            val backends = File(dest, "etc/crypto-policies/back-ends").apply { mkdirs() }
            val bind = File(backends, "bind.config")
            java.nio.file.Files.createSymbolicLink(
                bind.toPath(),
                java.nio.file.Paths.get("/usr/share/crypto-policies/DEFAULT/bind.txt")
            )
            assertTrue(TarPathSafety.isUnderDest(dest, bind))
            assertNotNull(TarPathSafety.requireUnderDest(dest, "etc/crypto-policies/back-ends/gnutls.config"))
            assertTrue(
                TarPathSafety.isSafeLinkTarget(
                    dest,
                    "etc/crypto-policies/back-ends/bind.config",
                    "/usr/share/crypto-policies/DEFAULT/bind.txt"
                )
            )
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun toyboxNotUnderGuestAbsIsBenign() {
        val line =
            "tar: 'opensuse-aarch64/etc/crypto-policies/back-ends/bind.config' " +
                "/usr/share/crypto-policies/DEFAULT/bind.txt not under " +
                "'/data/data/com.sg.linuxgo.debug/files/containers/c6997111/rootfs'"
        assertTrue(TarPathSafety.isBenignToyboxExtractLine(line))
        assertTrue(
            TarPathSafety.isBenignToyboxExtractLine(
                "tar: can't link 'fedora-aarch64/etc/pki/ca-trust/extracted/pem/" +
                    "directory-hash/002c0b4f.0' -> 'GlobalSign_Root_R46.pem': Permission denied"
            )
        )
        assertTrue(TarPathSafety.isMostlyBenignToyboxExtractLog("$line\n$line\n"))
        assertFalse(
            TarPathSafety.isMostlyBenignToyboxExtractLog(
                "$line\n tar: unexpected EOF in archive\n"
            )
        )
    }

    @Test
    fun resolveWalksRelativeUsrMerge() {
        val dest = createTempDirectory("tar-usrmerge").toFile()
        try {
            File(dest, "usr/bin").mkdirs()
            java.nio.file.Files.createSymbolicLink(
                File(dest, "bin").toPath(),
                java.nio.file.Paths.get("usr/bin")
            )
            val sh = TarPathSafety.resolveUnderDest(dest, "bin/sh")
            assertNotNull(sh)
            assertTrue(sh!!.absolutePath.replace('\\', '/').endsWith("usr/bin/sh"))
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test(expected = TarPathSafety.UnsafeArchivePathException::class)
    fun requireUnderDestThrowsOnEscape() {
        val dest = createTempDirectory("tar-req").toFile()
        try {
            TarPathSafety.requireUnderDest(dest, "../escape")
        } finally {
            dest.deleteRecursively()
        }
    }
}
