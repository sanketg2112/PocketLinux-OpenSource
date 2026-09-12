package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Bench target discovery must accept Alpine guest-absolute bin/sh → /bin/busybox
 * (host File.exists is false for those links).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProotMicrobenchTargetTest {

    @Test
    fun rootfsHasGuestShellAcceptsAlpineAbsoluteBusyboxSh() {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "bench-alpine-sh").apply {
            deleteRecursively()
            mkdirs()
        }
        File(root, "bin").mkdirs()
        Files.createSymbolicLink(
            File(root, "bin/sh").toPath(),
            File("/bin/busybox").toPath()
        )
        assertFalse(
            "host File.exists must be false for guest-absolute symlink",
            File(root, "bin/sh").exists()
        )
        assertTrue(ProotMicrobench.rootfsHasGuestShell(root))
    }

    @Test
    fun rootfsHasGuestShellRejectsEmptyRootfs() {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "bench-empty").apply {
            deleteRecursively()
            mkdirs()
        }
        assertFalse(ProotMicrobench.rootfsHasGuestShell(root))
    }

    @Test
    fun listTargetsIncludesAlpineInstalledContainer() {
        val ctx = RuntimeEnvironment.getApplication()
        val cm = ContainerManager(ctx)
        val id = "bench_alpine_${System.nanoTime()}"
        val cfg = ContainerConfig(
            id = id,
            distro = "alpine",
            de = "xfce4",
            wm = "none",
            name = "Alpine Bench",
            username = "PocketLinux",
            software = emptyList(),
            createdAt = System.currentTimeMillis(),
            isInstalled = true
        )
        assertTrue(cm.addContainer(cfg))
        try {
            val root = File(cm.getContainerRootfsPath(id)).apply {
                deleteRecursively()
                mkdirs()
            }
            File(root, "bin").mkdirs()
            File(root, "root").mkdirs()
            // Alpine-style absolute sh + install marker
            Files.createSymbolicLink(
                File(root, "bin/sh").toPath(),
                File("/bin/busybox").toPath()
            )
            File(root, "root/launch.sh").writeText("#!/bin/sh\n")

            val targets = ProotMicrobench.listTargets(ctx)
            assertTrue(
                "Alpine container with guest-absolute bin/sh must appear as bench target",
                targets.any { it.containerId == id }
            )
        } finally {
            cm.removeContainer(id)
        }
    }
}
