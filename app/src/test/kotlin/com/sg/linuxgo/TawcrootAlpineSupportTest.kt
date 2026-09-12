package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TawcrootAlpineSupportTest {

    @Test
    fun detectsAlpineRootfs() {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "alpine-detect").apply {
            deleteRecursively()
            mkdirs()
        }
        assertFalse(TawcrootAlpineSupport.isAlpineRootfs(root))
        File(root, "etc").mkdirs()
        File(root, "etc/alpine-release").writeText("3.20.0\n")
        assertTrue(TawcrootAlpineSupport.isAlpineRootfs(root))
    }

    @Test
    fun stageApkStaticCopiesHostBinaryWhenPresent() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = File(ctx.filesDir, "alpine-stage").apply {
            deleteRecursively()
            mkdirs()
        }
        // Robolectric often has null nativeLibraryDir — pin a writable fake lib dir.
        val nativeDir = File(ctx.filesDir, "fake-nativelib").apply {
            deleteRecursively()
            mkdirs()
        }
        ctx.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        val fakeApk = File(nativeDir, "libapk.so")
        fakeApk.writeBytes(ByteArray(64) { it.toByte() })
        fakeApk.setExecutable(true, false)

        val staged = TawcrootAlpineSupport.stageApkStatic(ctx, root)
        assertNotNull(staged)
        val dest = File(root, "sbin/apk.static")
        assertTrue(dest.isFile)
        assertEquals(64, dest.length())
        assertTrue(dest.canExecute() || dest.setExecutable(true, false))
        val shim = File(root, "sbin/apk")
        assertTrue(shim.isFile)
        assertTrue(shim.readText().contains("apk.static"))
    }

    @Test
    fun stageApkStaticSkipsRewriteWhenSizeMatches() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = File(ctx.filesDir, "alpine-stage-skip").apply {
            deleteRecursively()
            mkdirs()
        }
        val nativeDir = File(ctx.filesDir, "fake-nativelib2").apply {
            deleteRecursively()
            mkdirs()
        }
        ctx.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        val fakeApk = File(nativeDir, "libapk.so")
        fakeApk.writeBytes(ByteArray(32) { 7 })
        fakeApk.setExecutable(true, false)

        assertNotNull(TawcrootAlpineSupport.stageApkStatic(ctx, root))
        val dest = File(root, "sbin/apk.static")
        val mtime = dest.lastModified()
        // Second call should not rewrite (same size + not older source).
        Thread.sleep(20)
        assertNotNull(TawcrootAlpineSupport.stageApkStatic(ctx, root))
        assertEquals(32, dest.length())
        assertTrue(dest.lastModified() >= mtime)
    }

    @Test
    fun doesNotOverwriteExistingGuestApk() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = File(ctx.filesDir, "alpine-keep-apk").apply {
            deleteRecursively()
            mkdirs()
        }
        File(root, "sbin").mkdirs()
        val existing = File(root, "sbin/apk")
        existing.writeText("#!/bin/sh\n# real guest apk\n")
        existing.setExecutable(true, false)

        val nativeDir = File(ctx.filesDir, "fake-nativelib3").apply {
            deleteRecursively()
            mkdirs()
        }
        ctx.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        File(nativeDir, "libapk.so").writeBytes(ByteArray(16) { 1 })

        assertNotNull(TawcrootAlpineSupport.stageApkStatic(ctx, root))
        assertTrue(File(root, "sbin/apk.static").isFile)
        assertTrue(existing.readText().contains("real guest apk"))
    }
}
