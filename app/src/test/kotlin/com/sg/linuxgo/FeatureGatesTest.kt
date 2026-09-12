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

/**
 * Experimental settings/runtime are on all builds. Install logs, DE convert,
 * low-RAM simulation stay debug-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FeatureGatesTest {

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    private fun setWayland(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx())
            .edit()
            .putBoolean("enableWaylandSupport", enabled)
            .commit()
    }

    @Test
    fun experimentalSettingsShownOnAllBuilds() {
        assertTrue(FeatureGates.experimentalSettingsVisible())
        assertTrue(FeatureGates.experimentalRuntimeAllowed())
        assertTrue(FeatureGates.isLibhybrisAllowed())
        assertFalse(FeatureGates.lowRamSimulationAllowed())
        assertEquals(BuildConfig.DEBUG, FeatureGates.installLogsVisible())
        assertFalse(FeatureGates.deConversionAllowed())
        assertFalse(FeatureGates.legacyPackageInstallAllowed())
    }

    @Test
    fun experimentalSettingsAccessibleInOpenSource() {
        assertTrue(FeatureGates.experimentalSettingsAccessible(isPremiumUser = true))
        assertTrue(FeatureGates.experimentalSettingsAccessible(isPremiumUser = false))
    }

    @Test
    fun defaultGuiModeFollowsExperimentalWayland() {
        setWayland(false)
        assertEquals("x11", FeatureGates.defaultGuiMode(ctx()))
        setWayland(true)
        assertEquals("wayland", FeatureGates.defaultGuiMode(ctx()))
    }

    @Test
    fun effectiveGuiModeDefaultsToWaylandWhenEnabledAndUnset() {
        setWayland(true)
        assertEquals("wayland", FeatureGates.effectiveGuiMode(ctx(), null))
        assertEquals("wayland", FeatureGates.effectiveGuiMode(ctx(), ""))
        assertEquals("wayland", FeatureGates.effectiveGuiMode(ctx(), "wayland"))
        assertEquals("x11", FeatureGates.effectiveGuiMode(ctx(), "x11"))
        setWayland(false)
        assertEquals("x11", FeatureGates.effectiveGuiMode(ctx(), "wayland"))
    }

    @Test
    fun isContainerOnWaylandRequiresBothGates() {
        setWayland(false)
        assertFalse(FeatureGates.isContainerOnWayland(ctx(), "wayland"))
        setWayland(true)
        assertTrue(FeatureGates.isContainerOnWayland(ctx(), "wayland"))
        assertFalse(FeatureGates.isContainerOnWayland(ctx(), "x11"))
        assertFalse(FeatureGates.isContainerOnWayland(ctx(), null))
    }

    @Test
    fun applyDefaultWaylandUpdatesExistingX11ContainersOnce() {
        val ctx = ctx()
        setWayland(true)
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit()
            .remove(FeatureGates.PREF_WAYLAND_GUI_DEFAULTED)
            .commit()
        val mgr = ContainerManager(ctx)
        val created = mgr.createNewContainer(
            distro = "debian",
            de = "xfce4",
            wm = "none",
            software = emptyList(),
            guiMode = "x11"
        )
        assertTrue(mgr.addContainer(created))
        ctx.getSharedPreferences("container_${created.id}_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("gui_mode", "x11")
            .commit()

        FeatureGates.applyDefaultWaylandGuiToContainers(ctx)
        assertEquals("wayland", mgr.getContainer(created.id)?.guiMode)
        assertEquals(
            "wayland",
            ctx.getSharedPreferences("container_${created.id}_settings", Context.MODE_PRIVATE)
                .getString("gui_mode", null)
        )

        mgr.updateContainer(mgr.getContainer(created.id)!!.copy(guiMode = "x11"))
        ctx.getSharedPreferences("container_${created.id}_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("gui_mode", "x11")
            .commit()
        FeatureGates.applyDefaultWaylandGuiToContainers(ctx)
        assertEquals("x11", mgr.getContainer(created.id)?.guiMode)
    }

    @Test
    fun legacyPackageInstallGateControlsBootstrapPrebuiltImageInstall() {
        val bootstrap = Bootstrap(ctx())
        PreferenceManager.getDefaultSharedPreferences(ctx())
            .edit()
            .putBoolean(com.sg.linuxgo.ui.sheets.PREF_LEGACY_PACKAGE_INSTALL, true)
            .commit()

        // In open-source, legacyPackageInstallAllowed() is always false, so prebuilt images are always used
        assertTrue(bootstrap.usePrebuiltImageInstall)
    }
}
