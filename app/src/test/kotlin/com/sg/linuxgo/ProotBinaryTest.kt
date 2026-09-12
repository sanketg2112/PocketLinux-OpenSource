package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProotBinaryTest {

    @Test
    fun modeFromParsesKnownAndLegacyAuto() {
        assertEquals(ProotBinary.Mode.CLASSIC, ProotBinary.Mode.from("classic"))
        assertEquals(ProotBinary.Mode.CLASSIC, ProotBinary.Mode.from("proroot"))
        assertEquals(ProotBinary.Mode.CLASSIC, ProotBinary.Mode.from("hybrid"))
        assertEquals(ProotBinary.Mode.TAWCROOT, ProotBinary.Mode.from("tawcroot"))
        assertEquals(ProotBinary.Mode.TAWCROOT_HYBRID, ProotBinary.Mode.from("tawcroot_hybrid"))
        assertEquals(ProotBinary.Mode.CLASSIC, ProotBinary.Mode.from("auto"))
        assertEquals(ProotBinary.Mode.CLASSIC, ProotBinary.Mode.from(null))
        assertEquals(ProotBinary.Mode.CLASSIC, ProotBinary.Mode.from("bogus"))
    }

    @Test
    fun getModeMapsRemovedProRootModesToClassic() {
        val ctx = RuntimeEnvironment.getApplication()
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(ProotBinary.PREF_RUNTIME_MODE, "proroot")
            .apply()
        assertEquals(ProotBinary.Mode.CLASSIC, ProotBinary.getMode(ctx))
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(ProotBinary.PREF_RUNTIME_MODE, "hybrid")
            .apply()
        assertEquals(ProotBinary.Mode.CLASSIC, ProotBinary.getMode(ctx))
        ProotBinary.setMode(ctx, ProotBinary.Mode.TAWCROOT)
        assertEquals(ProotBinary.Mode.TAWCROOT, ProotBinary.getMode(ctx))
    }

    @Test
    fun bindSpecDuplicatesPathWhenHostGuestRequired() {
        assertEquals("/dev", ProotBinary.bindSpec("/dev", requireHostGuest = false))
        assertEquals("/dev:/dev", ProotBinary.bindSpec("/dev", requireHostGuest = true))
        assertEquals("/host:/guest", ProotBinary.bindSpec("/host:/guest", requireHostGuest = true))
        assertEquals("/h:/g", ProotBinary.bindSpec("/h", "/g", requireHostGuest = true))
    }

    @Test
    fun modeLabelsAreNonEmpty() {
        ProotBinary.Mode.entries.forEach { mode ->
            assertTrue(ProotBinary.modeLabel(mode).isNotBlank())
            assertTrue(ProotBinary.modeSummary(mode).isNotBlank())
        }
    }

    @Test
    fun runtimeEngineFlags() {
        val classic = ProotBinary.Runtime(
            launcherPath = "/n/libproot.so",
            engine = ProotBinary.Engine.CLASSIC,
            nativeLibDir = "/n",
            loaderPath = "/n/libprootloader.so",
            mode = ProotBinary.Mode.CLASSIC,
            purpose = ProotBinary.Purpose.TERMINAL
        )
        assertFalse(classic.useTawcroot)
        assertFalse(classic.requiresHostGuestBinds)
        assertEquals("/n/libprootloader.so", classic.loaderPath)

        val tawc = classic.copy(
            engine = ProotBinary.Engine.TAWCROOT,
            launcherPath = "/n/libtawcroot.so",
            loaderPath = null,
            mode = ProotBinary.Mode.TAWCROOT
        )
        assertTrue(tawc.useTawcroot)
        assertTrue(tawc.requiresHostGuestBinds)
        assertEquals(null, tawc.loaderPath)
    }

    @Test
    fun withGuestWorkingDirWrapsWhenNotRoot() {
        val cmd = listOf("/bin/bash", "-l")
        assertEquals(cmd, ProotBinary.withGuestWorkingDir("/", cmd))
        val wrapped = ProotBinary.withGuestWorkingDir("/home/u", cmd)
        assertTrue(wrapped.contains("/bin/sh"))
        assertTrue(wrapped.contains("/home/u"))
        assertTrue(wrapped.contains("/bin/bash"))
    }

    @Test
    fun tawcrootEngineNeverNeedsHostLinkerPath() {
        val tawc = ProotBinary.Runtime(
            launcherPath = "/n/libtawcroot.so",
            engine = ProotBinary.Engine.TAWCROOT,
            nativeLibDir = "/n",
            loaderPath = null,
            mode = ProotBinary.Mode.TAWCROOT
        )
        assertTrue(tawc.useTawcroot)
        assertEquals(null, tawc.loaderPath)
        // configureProcessBuilderEnv clears host env for tawcroot (covered in device smoke).
    }
}
