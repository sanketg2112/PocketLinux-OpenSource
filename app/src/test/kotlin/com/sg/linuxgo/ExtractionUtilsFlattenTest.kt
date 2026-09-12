package com.sg.linuxgo

import com.sg.linuxgo.util.ExtractionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Kali NetHunter (and similar) rootfs tarballs unpack as a single top-level dir
 * (e.g. kali-arm64/). Hardlinks require extracting without --strip-components,
 * then flattening that dir into the rootfs mount point.
 */
class ExtractionUtilsFlattenTest {

    @Test
    fun looksLikeRootfsDetectsBinShAndOsRelease() {
        val root = createTempDirectory("rootfs-look").toFile()
        try {
            assertFalse(ExtractionUtils.looksLikeRootfs(root))
            File(root, "etc").mkdirs()
            File(root, "etc/os-release").writeText("ID=kali\n")
            assertTrue(ExtractionUtils.looksLikeRootfs(root))
        } finally {
            root.deleteRecursively()
        }
    }

    /** Alpine: only guest-absolute bin/sh → /bin/busybox (host exists() false). */
    @Test
    fun looksLikeRootfsDetectsAlpineAbsoluteShSymlink() {
        val root = createTempDirectory("rootfs-alpine-look").toFile()
        try {
            assertFalse(ExtractionUtils.looksLikeRootfs(root))
            File(root, "bin").mkdirs()
            File(root, "bin/busybox").writeText("bb\n")
            java.nio.file.Files.createSymbolicLink(
                File(root, "bin/sh").toPath(),
                java.nio.file.Paths.get("/bin/busybox")
            )
            assertTrue(
                "Alpine absolute bin/sh symlink must count as a rootfs shell",
                ExtractionUtils.looksLikeRootfs(root)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun flattenLiftsKaliArm64StyleNesting() {
        val root = createTempDirectory("rootfs-flat").toFile()
        try {
            val nested = File(root, "kali-arm64").apply { mkdirs() }
            File(nested, "usr/bin").mkdirs()
            File(nested, "usr/bin/sh").writeText("#!/bin/sh\n")
            File(nested, "etc").mkdirs()
            File(nested, "etc/os-release").writeText("ID=kali\n")
            File(nested, "usr/lib/klibc/bin").mkdirs()
            File(nested, "usr/lib/klibc/bin/zcat").writeText("zcat\n")
            // Simulate hardlink sibling that must stay under same tree during lift
            File(nested, "usr/lib/klibc/bin/gzip").writeText("gzip\n")

            assertFalse(ExtractionUtils.looksLikeRootfs(root))
            val lifted = ExtractionUtils.flattenRootfsIfNeeded(root)
            assertEquals("kali-arm64", lifted)
            assertTrue(ExtractionUtils.looksLikeRootfs(root))
            assertTrue(File(root, "usr/bin/sh").isFile)
            assertTrue(File(root, "etc/os-release").isFile)
            assertTrue(File(root, "usr/lib/klibc/bin/zcat").isFile)
            assertFalse(File(root, "kali-arm64").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun salvageLiftsOpensuseAarch64Prefix() {
        val root = createTempDirectory("rootfs-suse").toFile()
        try {
            val nested = File(root, "opensuse-aarch64").apply { mkdirs() }
            File(nested, "etc").mkdirs()
            File(nested, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
            File(nested, "usr/lib").mkdirs()
            File(nested, "usr/bin").mkdirs()
            File(nested, "usr/bin/bash").writeText("#!/bin/bash\n")
            File(nested, "usr/lib/os-release").writeText("ID=opensuse-leap\n")
            java.nio.file.Files.createSymbolicLink(
                File(nested, "etc/os-release").toPath(),
                java.nio.file.Paths.get("../usr/lib/os-release")
            )
            java.nio.file.Files.createSymbolicLink(
                File(nested, "bin").toPath(),
                java.nio.file.Paths.get("usr/bin")
            )
            assertTrue(ExtractionUtils.salvageExtractedRootfs(root))
            assertTrue(File(root, "etc/passwd").isFile)
            assertFalse(File(root, "opensuse-aarch64").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun salvageRejectsPasswdOnlyIncompleteExtract() {
        val root = createTempDirectory("rootfs-partial").toFile()
        try {
            val nested = File(root, "fedora-aarch64").apply { mkdirs() }
            File(nested, "etc").mkdirs()
            File(nested, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
            File(nested, "etc/os-release").writeText("ID=fedora\n")
            assertTrue(
                "passwd is enough to recognize a nested prefix",
                ExtractionUtils.looksLikeRootfs(nested)
            )
            assertFalse(
                "incomplete extract without a shell must not be treated as success",
                ExtractionUtils.salvageExtractedRootfs(root)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun salvageLiftsUsrWhenEtcAlreadyAtTop() {
        val root = createTempDirectory("rootfs-partial-flat").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
            val nested = File(root, "fedora-aarch64").apply { mkdirs() }
            File(nested, "usr/bin").mkdirs()
            File(nested, "usr/bin/bash").writeText("#!/bin/bash\n")
            assertTrue(
                "passwd at top must not hide leftover nested usr/bin/bash",
                ExtractionUtils.salvageExtractedRootfs(root)
            )
            assertTrue(File(root, "usr/bin/bash").isFile)
            assertTrue(
                !File(root, "fedora-aarch64/usr/bin/bash").exists() ||
                    !File(root, "fedora-aarch64").exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun flattenDoesNotDeleteNestedUsrWhenEtcAlreadyLifted() {
        val root = createTempDirectory("rootfs-no-wipe-usr").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
            val nested = File(root, "fedora-aarch64").apply { mkdirs() }
            File(nested, "usr/bin").mkdirs()
            File(nested, "usr/bin/bash").writeText("#!/bin/bash\n")
            ExtractionUtils.flattenRootfsIfNeeded(root)
            assertTrue(
                "usr/bin/bash must survive flatten when only etc was at top",
                File(root, "usr/bin/bash").isFile ||
                    File(nested, "usr/bin/bash").isFile
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun flattenIsNoOpWhenAlreadyFlat() {
        val root = createTempDirectory("rootfs-already").toFile()
        try {
            File(root, "bin").mkdirs()
            File(root, "bin/sh").writeText("#!/bin/sh\n")
            assertNull(ExtractionUtils.flattenRootfsIfNeeded(root))
            assertTrue(File(root, "bin/sh").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
