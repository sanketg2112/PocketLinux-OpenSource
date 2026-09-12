package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoidXbpsReposTest {

    @Test
    fun defaultRepoIsAarch64NotX86Current() {
        assertEquals(
            "https://repo-default.voidlinux.org/current/aarch64",
            VoidXbpsRepos.DEFAULT_REPO,
        )
        assertTrue(VoidXbpsRepos.DEFAULT_REPO.endsWith("/current/aarch64"))
        assertFalse(VoidXbpsRepos.DEFAULT_REPO.endsWith("/current"))
    }

    @Test
    fun x86CurrentMirrorGainsAarch64Suffix() {
        assertEquals(
            VoidXbpsRepos.DEFAULT_REPO,
            VoidXbpsRepos.resolveRepoUrl("https://repo-default.voidlinux.org/current"),
        )
        assertEquals(
            VoidXbpsRepos.DEFAULT_REPO,
            VoidXbpsRepos.resolveRepoUrl("https://repo-default.voidlinux.org/current/"),
        )
    }

    @Test
    fun aarch64RepoIsUnchanged() {
        assertEquals(
            VoidXbpsRepos.DEFAULT_REPO,
            VoidXbpsRepos.resolveRepoUrl(VoidXbpsRepos.DEFAULT_REPO),
        )
    }

    @Test
    fun muslUsesAarch64MuslPath() {
        assertEquals(
            "https://repo-default.voidlinux.org/current/aarch64/musl",
            VoidXbpsRepos.resolveRepoUrl(
                "https://repo-default.voidlinux.org/current",
                musl = true,
            ),
        )
        assertEquals(
            "https://repo-default.voidlinux.org/current/aarch64/musl",
            VoidXbpsRepos.resolveRepoUrl(
                "https://repo-default.voidlinux.org/current/aarch64",
                musl = true,
            ),
        )
    }

    @Test
    fun hostOnlyMirrorGetsCurrentAarch64() {
        assertEquals(
            "https://mirrors.servercentral.com/voidlinux/current/aarch64",
            VoidXbpsRepos.resolveRepoUrl("https://mirrors.servercentral.com/voidlinux"),
        )
    }

    @Test
    fun emptyMirrorFallsBackToOfficialAarch64() {
        assertEquals(VoidXbpsRepos.DEFAULT_REPO, VoidXbpsRepos.resolveRepoUrl(""))
        assertEquals(VoidXbpsRepos.DEFAULT_REPO, VoidXbpsRepos.resolveRepoUrl("  "))
    }

    @Test
    fun linkShimCreatesSonameSymlinksViaSymlinkat() {
        val bsh = sourceFileAt(listOf("b.sh", "../b.sh"))
        if (!bsh.isFile) return
        val block = bsh.readText().substringAfter("Hardlink shim for Android/PRoot")
            .substringBefore("UID spoof")
        assertTrue("intercepts symlink()", block.contains("int symlink("))
        assertTrue("intercepts symlinkat()", block.contains("int symlinkat("))
        assertTrue("intercepts linkat() for libarchive hardlinks", block.contains("int linkat("))
        assertTrue("uses symlinkat syscall", block.contains("PL_SYS_SYMLINKAT"))
        assertTrue("retries relative paths as absolute from /", block.contains("pl_make_abs"))
        assertTrue("copies the library when PRoot rejects the symlink", block.contains("pl_copy_file"))
        assertTrue("does not give up when path lacks ./ prefix", !block.contains("linkpath[1] == '/'"))
    }

    @Test
    fun xbpsExtractShimIsBuiltWithoutAndroidLibdl() {
        val bsh = sourceFileAt(listOf("b.sh", "../b.sh"))
        if (!bsh.isFile) return
        val block = bsh.readText().substringAfter("Compiling xbps_extract_shim.so")
            .substringBefore("Compiling pocketlinux_uid_spoof.so")
        assertTrue("must use -nodefaultlibs like link_shim", block.contains("-nodefaultlibs"))
        assertFalse("must not include Android dlfcn.h (pulls libdl.so)", block.contains("dlfcn.h"))
        assertTrue(
            "strips SECURE_SYMLINKS on archive_write_disk_set_options too",
            block.contains("archive_write_disk_set_options"),
        )
        assertTrue("retries dlsym via RTLD_DEFAULT", block.contains("RTLD_DEFAULT"))
        val so = sourceFileAt(
            listOf(
                "src/main/assets/xbps_extract_shim.so",
                "app/src/main/assets/xbps_extract_shim.so",
            )
        )
        if (so.isFile) {
            val bytes = so.readBytes()
            val ascii = bytes.map { it.toInt().and(0xff).toChar() }.joinToString("")
            assertFalse(
                "must not DT_NEEDED Android libdl.so (guest glibc has libdl.so.2)",
                ascii.contains("libdl.so") && !ascii.contains("libdl.so.2"),
            )
        }
    }

    @Test
    fun xbpsWrapperPreloadsLinkShimAndExecsRealBinary() {
        val script = xbpsLinkShimWrapperScript("/usr/bin/xbps-install.real")
        assertTrue(script.contains("PocketLinux xbps unpack shim v6"))
        assertTrue(script.contains("/usr/lib/link_shim.so"))
        assertTrue(script.contains("/usr/lib/xbps_extract_shim.so"))
        assertTrue(script.contains("LD_PRELOAD"))
        assertTrue(script.contains("cd /"))
        assertTrue(script.contains("export PWD=/"))
        assertTrue(script.contains("XBPS_ARCH"))
        assertTrue(script.contains("/usr/bin/xbps-install.real"))
        assertTrue(script.contains("pocketlinux-xbps-materialize"))
        assertTrue(script.contains("failed to extract file"))
        assertTrue(script.contains("00-pocketlinux-noextract.conf"))
        assertTrue("rewrites stale helper that called the zstd CLI", script.contains("materialize v3"))
        assertTrue("unlinks existing soname symlink before copy", script.contains("lexists"))
        assertTrue(script.contains("compression.zstd"))
        assertFalse(script.contains("export DEBIAN_FRONTEND"))
        assertFalse("must not exec the wrapper itself", script.contains("exec /usr/bin/xbps-install \""))
    }

    @Test
    fun xbpsWrappersReplaceBinaryAndNeverExecSelf() {
        val root = File.createTempFile("void-xbps-wrap", "").apply {
            delete()
            mkdirs()
        }
        try {
            val binDir = File(root, "usr/bin").apply { mkdirs() }
            val localBin = File(root, "usr/local/bin").apply { mkdirs() }
            File(binDir, "xbps-install").writeText("fake-elf-xbps-install-binary-xxxxxxxx")
            val stale = File(localBin, "pocketlinux-xbps-materialize")
            stale.writeText("#!/usr/bin/env python3\nimport subprocess\nsubprocess.Popen([\"zstd\", \"-d\", \"-c\", pkg])\n")
            installXbpsWrappersOnto(root, localBin)
            val helper = File(root, "usr/local/bin/pocketlinux-xbps-materialize").readText()
            assertTrue("stale zstd-CLI helper is replaced", helper.contains("materialize v3"))
            assertTrue(helper.contains("compression.zstd"))
            assertFalse(helper.contains("""Popen(["zstd""""))
            val wrapped = File(binDir, "xbps-install").readText()
            val real = File(binDir, "xbps-install.real")
            assertTrue("real binary kept as .real", real.isFile)
            assertTrue(real.readText().contains("fake-elf"))
            assertTrue(wrapped.contains("PocketLinux xbps unpack shim v6"))
            assertTrue(wrapped.contains("/usr/bin/xbps-install.real"))
            assertTrue(wrapped.contains("pocketlinux-xbps-materialize"))
            assertFalse("must not exec the wrapper itself", wrapped.contains("exec /usr/bin/xbps-install \""))
            val local = File(localBin, "xbps-install").readText()
            assertTrue(local.contains("/usr/bin/xbps-install.real"))
            installXbpsWrappersOnto(root, localBin)
            assertTrue(
                "second pass still points at .real",
                File(binDir, "xbps-install").readText().contains("/usr/bin/xbps-install.real"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun guiStartDeploysXbpsUnpackShimForExistingVoid() {
        val prep = sourceFile("GuiRootfsPrep.kt").readText()
        assertTrue(prep.contains("installXbpsWrappersOnto"))
        assertTrue(prep.contains("deployLinkShim"))
        assertTrue("must refresh wrappers on every GUI start", !prep.contains("xbps_unpack_shim_v4"))
        val helpers = sourceFile("RestoreProotHelpers.kt").readText()
        assertTrue(helpers.contains("pocketlinux_xbps_materialize.py"))
    }

    @Test
    fun materializeTurnsXbpsSymlinkIntoRegularCopy() {
        val script = sourceFileAt(
            listOf(
                "src/main/assets/pocketlinux_xbps_materialize.py",
                "app/src/main/assets/pocketlinux_xbps_materialize.py",
            )
        )
        assertTrue(script.isFile)
        val src = script.readText()
        assertTrue(src.contains("materialize v3"))
        assertTrue("must unlink dest if it is already a symlink to the library", src.contains("replace_with_copy"))
        assertTrue("Python 3.14 stdlib zstd first (Void has no zstd CLI)", src.contains("compression.zstd"))
        assertTrue(src.contains("ZstdFile"))
        assertFalse("must not require zstd on PATH as the only method", src.contains("""Popen(["zstd""""))
        val work = File.createTempFile("xbps-mat", "").apply {
            delete()
            mkdirs()
        }
        try {
            val cache = File(work, "cache").apply { mkdirs() }
            val root = File(work, "root").apply { mkdirs() }
            val conf = File(work, "noextract.conf")
            val staging = File(work, "pkg").apply { mkdirs() }
            File(staging, "usr/lib").mkdirs()
            File(staging, "usr/lib/libfoo.so.1.0").writeText("hello-soname")
            val tar = File(work, "pkg.tar")
            val mk = File(work, "mk.py")
            mk.writeText(
                """
                import os, tarfile
                staging = r'''${staging.absolutePath}'''
                tar = r'''${tar.absolutePath}'''
                os.symlink("libfoo.so.1.0", os.path.join(staging, "usr/lib/libfoo.so.1"))
                with tarfile.open(tar, "w") as tf:
                    tf.add(os.path.join(staging, "usr"), arcname="usr")
                """.trimIndent()
            )
            val build = ProcessBuilder("python3", mk.absolutePath).redirectErrorStream(true).start()
            val buildOut = build.inputStream.bufferedReader().readText()
            assertEquals("symlink tar: $buildOut", 0, build.waitFor())
            val xbps = File(cache, "libfoo-1_1.xbps")
            val zstd = ProcessBuilder("zstd", "-f", "-o", xbps.absolutePath, tar.absolutePath)
                .redirectErrorStream(true).start()
            val zOut = zstd.inputStream.bufferedReader().readText()
            assertEquals("zstd: $zOut", 0, zstd.waitFor())
            val run = ProcessBuilder(
                "python3", script.absolutePath,
                "--root", root.absolutePath,
                "--cache", cache.absolutePath,
                "--conf", conf.absolutePath,
            ).redirectErrorStream(true).start()
            val out = run.inputStream.bufferedReader().readText()
            assertEquals("materialize: $out", 0, run.waitFor())
            val copy = File(root, "usr/lib/libfoo.so.1")
            val real = File(root, "usr/lib/libfoo.so.1.0")
            assertTrue(real.isFile)
            assertTrue(copy.isFile)
            assertFalse(
                "soname must be a regular file, not a symlink",
                Files.isSymbolicLink(copy.toPath()),
            )
            assertEquals("hello-soname", copy.readText())
            assertTrue(conf.readText().contains("noextract=/usr/lib/libfoo.so.1"))
            copy.delete()
            Files.createSymbolicLink(copy.toPath(), real.toPath().fileName)
            assertTrue(Files.isSymbolicLink(copy.toPath()))
            val run2 = ProcessBuilder(
                "python3", script.absolutePath,
                "--root", root.absolutePath,
                "--cache", cache.absolutePath,
                "--conf", conf.absolutePath,
            ).redirectErrorStream(true).start()
            val out2 = run2.inputStream.bufferedReader().readText()
            assertEquals("materialize overwrite symlink: $out2", 0, run2.waitFor())
            assertFalse(
                "existing soname symlink must be replaced with a regular copy",
                Files.isSymbolicLink(copy.toPath()),
            )
            assertEquals("hello-soname", copy.readText())
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun containerPresetWiresAarch64Default() {
        val bootstrap = sourceFile("Bootstrap.kt").readText()
        assertTrue(bootstrap.contains("VoidXbpsRepos.DEFAULT_REPO"))
        assertFalse(
            bootstrap.contains("\"https://repo-default.voidlinux.org/current\""),
        )
    }

    private fun sourceFile(name: String): File {
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, "src/main/kotlin/com/sg/linuxgo/$name"),
            File(cwd, "app/src/main/kotlin/com/sg/linuxgo/$name"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("source not found: $name (cwd=$cwd)")
    }

    private fun sourceFileAt(relPaths: List<String>): File {
        val cwd = File(System.getProperty("user.dir")!!)
        return relPaths.map { File(cwd, it) }.firstOrNull { it.isFile }
            ?: File(cwd, relPaths.first())
    }
}
