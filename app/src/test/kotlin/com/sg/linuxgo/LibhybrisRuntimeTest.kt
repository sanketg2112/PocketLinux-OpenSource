package com.sg.linuxgo

import android.content.Context
import androidx.preference.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibhybrisRuntimeTest {

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    private fun setWayland(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx())
            .edit()
            .putBoolean("enableWaylandSupport", enabled)
            .commit()
    }

    @Test
    fun toggleRoundTrip() {
        val ctx = RuntimeEnvironment.getApplication()
        LibhybrisRuntime.setEnabled(ctx, false)
        assertFalse(LibhybrisRuntime.isPrefEnabled(ctx))
        LibhybrisRuntime.setEnabled(ctx, true)
        assertTrue(LibhybrisRuntime.isPrefEnabled(ctx))
        assertEquals(TawcWaylandCompat.GFX_HYBRIS, TawcWaylandCompat.graphicsBackend(ctx))
        LibhybrisRuntime.setEnabled(ctx, false)
        assertFalse(LibhybrisRuntime.isPrefEnabled(ctx))
        assertEquals(TawcWaylandCompat.GFX_MESA, TawcWaylandCompat.graphicsBackend(ctx))
    }

    @Test
    fun graphicsBackendButtonEnablesToggle() {
        val ctx = RuntimeEnvironment.getApplication()
        LibhybrisRuntime.setEnabled(ctx, false)
        TawcWaylandCompat.setGraphicsBackend(ctx, TawcWaylandCompat.GFX_HYBRIS)
        assertTrue(LibhybrisRuntime.isPrefEnabled(ctx))
        TawcWaylandCompat.setGraphicsBackend(ctx, TawcWaylandCompat.GFX_CPU)
        assertFalse(LibhybrisRuntime.isPrefEnabled(ctx))
        assertEquals(TawcWaylandCompat.GFX_CPU, TawcWaylandCompat.graphicsBackend(ctx))
    }

    @Test
    fun muslAlpineIsRejected() {
        val ctx = ctx()
        setWayland(true)
        LibhybrisRuntime.setEnabled(ctx, false)
        val root = File(ctx.cacheDir, "hybris-alpine").apply {
            deleteRecursively()
            mkdirs()
        }
        File(root, "etc").mkdirs()
        File(root, "etc/alpine-release").writeText("3.21.0\n")
        assertTrue(LibhybrisRuntime.isMuslRootfs(root))
        LibhybrisRuntime.setEnabled(ctx, true)
        assertFalse(LibhybrisRuntime.shouldApply(ctx, root))
    }

    @Test
    fun glibcDebianIsAcceptedWhenEnabled() {
        val ctx = ctx()
        setWayland(true)
        LibhybrisRuntime.setEnabled(ctx, false)
        val root = File(ctx.cacheDir, "hybris-debian").apply {
            deleteRecursively()
            mkdirs()
        }
        File(root, "lib").mkdirs()
        File(root, "lib/ld-linux-aarch64.so.1").writeText("")
        assertTrue(LibhybrisRuntime.isGlibcRootfs(root))
        assertFalse(LibhybrisRuntime.isMuslRootfs(root))
        LibhybrisRuntime.setEnabled(ctx, true)
        assertTrue(LibhybrisRuntime.shouldApply(ctx, root))
    }

    @Test
    fun hybrisRequiresWaylandExperimental() {
        val ctx = ctx()
        setWayland(false)
        LibhybrisRuntime.setEnabled(ctx, true)
        assertTrue(LibhybrisRuntime.isPrefEnabled(ctx))
        assertFalse(LibhybrisRuntime.isEnabled(ctx))
        val root = File(ctx.cacheDir, "hybris-nowayland").apply {
            deleteRecursively()
            mkdirs()
        }
        File(root, "lib").mkdirs()
        File(root, "lib/ld-linux-aarch64.so.1").writeText("")
        assertFalse(LibhybrisRuntime.shouldApply(ctx, root))
        setWayland(true)
        assertTrue(LibhybrisRuntime.isEnabled(ctx))
        assertTrue(LibhybrisRuntime.shouldApply(ctx, root))
    }

    @Test
    fun guestEnvOverridesMesaKeys() {
        val mesa = mapOf(
            "MESA_LOADER_DRIVER_OVERRIDE" to "kgsl",
            "GALLIUM_DRIVER" to "zink",
            "HOME" to "/root",
        )
        val merged = LibhybrisRuntime.mergeGuestEnv(mesa)
        assertFalse(merged.containsKey("MESA_LOADER_DRIVER_OVERRIDE"))
        assertFalse(merged.containsKey("GALLIUM_DRIVER"))
        assertEquals("/root", merged["HOME"])
        assertEquals("wayland", merged["HYBRIS_EGLPLATFORM"])
        assertTrue(merged.getValue("LD_LIBRARY_PATH").contains("/usr/lib/hybris"))
        assertEquals("hybris", merged["POCKETLINUX_GPU_MODE"])
        assertFalse(merged.containsKey("GDK_GL"))
    }

    @Test
    fun profileClearsMesaAndSetsHybris() {
        val text = LibhybrisInstaller.guestProfileText()
        assertTrue(text.contains("HYBRIS_EGLPLATFORM=wayland"))
        assertTrue(text.contains("unset GALLIUM_DRIVER"))
        assertTrue(text.contains("zz-pocketlinux-hybris") || text.contains("libhybris"))
        assertTrue(text.contains("/usr/lib/hybris/gl-shims"))
    }

    @Test
    fun installerWritesProfileGlvndAndConfig() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = File(ctx.cacheDir, "hybris-stage").apply {
            deleteRecursively()
            mkdirs()
        }
        File(root, "lib").mkdirs()
        File(root, "lib/ld-linux-aarch64.so.1").writeText("")
        LibhybrisInstaller.writeGuestProfile(root)
        LibhybrisInstaller.writeGlvndVendor(ctx, root)
        LibhybrisInstaller.writeLinkerConfig(ctx, root)
        val profile = File(root, "etc/profile.d/zz-pocketlinux-hybris.sh")
        assertTrue(profile.isFile)
        assertTrue(profile.readText().contains("HYBRIS_EGLPLATFORM"))
        val glvnd = File(root, "usr/share/glvnd/egl_vendor.d/00_libhybris.json")
        assertTrue(glvnd.isFile)
        assertTrue(glvnd.readText().contains("/usr/lib/hybris/libEGL.so.1"))
        assertTrue(File(root, "usr/lib/hybris-config/ld.config.txt").isFile)
    }

    @Test
    fun copyTreePreservesSymlink() {
        val ctx = RuntimeEnvironment.getApplication()
        val src = File(ctx.cacheDir, "hybris-copy-src").apply {
            deleteRecursively()
            mkdirs()
        }
        val dst = File(ctx.cacheDir, "hybris-copy-dst").apply { deleteRecursively() }
        File(src, "libEGL.so.1.0.0").writeText("elf")
        java.nio.file.Files.createSymbolicLink(
            File(src, "libEGL.so.1").toPath(),
            java.nio.file.Paths.get("libEGL.so.1.0.0")
        )
        LibhybrisInstaller.copyTree(src, dst)
        assertTrue(File(dst, "libEGL.so.1.0.0").isFile)
        assertTrue(java.nio.file.Files.isSymbolicLink(File(dst, "libEGL.so.1").toPath()))
    }

    @Test
    fun prepareOffRemovesProfile() {
        val ctx = RuntimeEnvironment.getApplication()
        LibhybrisRuntime.setEnabled(ctx, false)
        val root = File(ctx.cacheDir, "hybris-off").apply {
            deleteRecursively()
            mkdirs()
        }
        File(root, "etc/profile.d").mkdirs()
        File(root, "etc/profile.d/zz-pocketlinux-hybris.sh").writeText("old\n")
        assertEquals("off", LibhybrisInstaller.prepare(ctx, root))
        assertFalse(File(root, "etc/profile.d/zz-pocketlinux-hybris.sh").exists())
    }

    @Test
    fun androidBindListCoversVendorStack() {
        val dirs = LibhybrisRuntime.ANDROID_BIND_DIRS
        assertTrue(dirs.contains("/system"))
        assertTrue(dirs.contains("/vendor"))
        assertTrue(dirs.contains("/apex"))
        assertTrue(dirs.contains("/system_ext"))
    }

    @Test
    fun hybrisLabelIsNotPlaceholder() {
        val label = TawcWaylandCompat.graphicsBackendLabel(TawcWaylandCompat.GFX_HYBRIS)
        assertTrue(label.contains("hybris"))
        assertFalse(label.contains("placeholder"))
    }
}
