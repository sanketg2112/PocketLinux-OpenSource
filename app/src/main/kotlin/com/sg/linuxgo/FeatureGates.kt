package com.sg.linuxgo

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Release vs debug feature gates.
 *
 * Experimental settings (Wayland, tawcroot) are shown on all builds.
 * Debug-only surfaces: install logs, DE convert, and low-RAM simulation.
 */
object FeatureGates {

    const val PREF_WAYLAND_GUI_DEFAULTED = "wayland_default_gui_applied_v1"

    /** Full Experimental settings page and entry in global settings. */
    fun experimentalSettingsVisible(): Boolean = true

    /** Experimental page is always accessible in this source tree. */
    fun experimentalSettingsAccessible(isPremiumUser: Boolean): Boolean = true

    /**
     * Whether Wayland may be offered or launched.
     * Honors the Experimental toggle on debug and release.
     */
    fun isWaylandEnabled(context: Context): Boolean {
        return try {
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("enableWaylandSupport", false)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolve effective GUI mode for a container.
     * When Experimental Wayland is on, missing/blank stored values default to Wayland.
     * Explicit [x11] is still honored after the user picks it on the container card.
     */
    fun effectiveGuiMode(context: Context, storedMode: String?): String {
        if (!isWaylandEnabled(context)) return "x11"
        return if (storedMode == "x11") "x11" else "wayland"
    }

    /** Default GUI server when Experimental Wayland is on (new containers / unset prefs). */
    fun defaultGuiMode(context: Context): String =
        if (isWaylandEnabled(context)) "wayland" else "x11"

    /** Container is actually using the Wayland path (experimental + per-container). */
    fun isContainerOnWayland(context: Context, guiMode: String?): Boolean {
        return isWaylandEnabled(context) && guiMode == "wayland"
    }

    /**
     * One-shot: when Experimental Wayland is first used, existing cards that still
     * have the historical X11 default become Wayland so the option is selected
     * without opening each card. Later X11 picks are not overwritten.
     */
    fun applyDefaultWaylandGuiToContainers(context: Context) {
        if (!isWaylandEnabled(context)) return
        val global = PreferenceManager.getDefaultSharedPreferences(context)
        if (global.getBoolean(PREF_WAYLAND_GUI_DEFAULTED, false)) return
        val mgr = ContainerManager(context)
        for (container in mgr.getContainers()) {
            if (container.guiMode != "wayland") {
                mgr.updateContainer(container.copy(guiMode = "wayland"))
            }
            context.getSharedPreferences("container_${container.id}_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("gui_mode", "wayland")
                .commit()
        }
        global.edit().putBoolean(PREF_WAYLAND_GUI_DEFAULTED, true).commit()
    }

    /** tawcroot / hybrid runtime modes (Experimental settings). */
    fun experimentalRuntimeAllowed(): Boolean = true

    /**
     * libhybris (stock Android GPU) via Experimental → Graphics backend.
     * Off unless Wayland is on and that backend is selected.
     */
    fun isLibhybrisAllowed(): Boolean = true

    /** Debug-only fake low-RAM values disabled in this source tree. */
    fun lowRamSimulationAllowed(): Boolean = false

    /**
     * Install / setup log viewer from the container card.
     */
    fun installLogsVisible(): Boolean = BuildConfig.DEBUG

    /**
     * DE conversion disabled in this source tree.
     */
    fun deConversionAllowed(): Boolean = false

    /**
     * Legacy package install disabled — always use prebuilt container images.
     */
    fun legacyPackageInstallAllowed(): Boolean = false
}
