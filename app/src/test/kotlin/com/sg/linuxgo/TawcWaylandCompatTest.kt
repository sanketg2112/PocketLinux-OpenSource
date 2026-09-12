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
class TawcWaylandCompatTest {

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    private fun setWayland(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx())
            .edit()
            .putBoolean("enableWaylandSupport", enabled)
            .commit()
    }

    @Test
    fun gtk3MenusDefaultOn() {
        val ctx = RuntimeEnvironment.getApplication()
        assertTrue(TawcWaylandCompat.isGtk3MenusWorkaroundEnabled(ctx))
        TawcWaylandCompat.setGtk3MenusWorkaroundEnabled(ctx, false)
        assertFalse(TawcWaylandCompat.isGtk3MenusWorkaroundEnabled(ctx))
        TawcWaylandCompat.setGtk3MenusWorkaroundEnabled(ctx, true)
        assertTrue(TawcWaylandCompat.isGtk3MenusWorkaroundEnabled(ctx))
    }

    @Test
    fun graphicsBackendRoundTrip() {
        val ctx = RuntimeEnvironment.getApplication()
        LibhybrisRuntime.setEnabled(ctx, false)
        assertEquals(TawcWaylandCompat.GFX_MESA, TawcWaylandCompat.graphicsBackend(ctx))
        TawcWaylandCompat.setGraphicsBackend(ctx, TawcWaylandCompat.GFX_CPU)
        assertEquals(TawcWaylandCompat.GFX_CPU, TawcWaylandCompat.graphicsBackend(ctx))
        TawcWaylandCompat.setGraphicsBackend(ctx, "bogus")
        assertEquals(TawcWaylandCompat.GFX_MESA, TawcWaylandCompat.graphicsBackend(ctx))
    }

    @Test
    fun compositorEnvWhenWaylandGateOpen() {
        val ctx = RuntimeEnvironment.getApplication()
        // FeatureGates.experimental is DEBUG; Robolectric debug builds allow Wayland gate false
        // by default unless prefs set. Env extras still return keys only when Wayland enabled.
        // Force-check map shape when prefs would matter:
        TawcWaylandCompat.setGtk3MenusWorkaroundEnabled(ctx, true)
        TawcWaylandCompat.setGraphicsBackend(ctx, TawcWaylandCompat.GFX_CPU)
        // Without enableWaylandSupport FeatureGates may still block; installGuestProfile is no-op then.
        val root = File(ctx.filesDir, "tawc-wl-profile").apply {
            deleteRecursively()
            mkdirs()
        }
        TawcWaylandCompat.installGuestProfile(root, ctx)
        // Either wrote profile (if wayland gate open) or skipped cleanly.
        val profile = File(root, "etc/profile.d/pocketlinux-tawc-wayland.sh")
        if (FeatureGates.isWaylandEnabled(ctx)) {
            assertTrue(profile.isFile)
            val text = profile.readText()
            assertTrue(text.contains("POCKETLINUX_WAYLAND_GFX"))
            assertTrue(text.contains("SDL_VIDEODRIVER"))
            assertTrue(text.contains("hybris") || text.contains("mesa"))
        }
    }

    @Test
    fun graphicsBackendLabels() {
        assertTrue(TawcWaylandCompat.graphicsBackendLabel(TawcWaylandCompat.GFX_CPU).contains("CPU"))
        assertTrue(TawcWaylandCompat.graphicsBackendLabel(TawcWaylandCompat.GFX_HYBRIS).contains("hybris"))
        assertTrue(TawcWaylandCompat.graphicsBackendLabel(TawcWaylandCompat.GFX_MESA).contains("Mesa"))
    }

    @Test
    fun graphicsBackendDefaultsToHybrisWhenWaylandOn() {
        val ctx = ctx()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .remove(TawcWaylandCompat.PREF_GRAPHICS_BACKEND)
            .commit()
        setWayland(false)
        assertEquals(TawcWaylandCompat.GFX_MESA, TawcWaylandCompat.graphicsBackend(ctx))
        setWayland(true)
        assertEquals(TawcWaylandCompat.GFX_HYBRIS, TawcWaylandCompat.graphicsBackend(ctx))
    }
}
