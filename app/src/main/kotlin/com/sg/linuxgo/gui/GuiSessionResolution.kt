package com.sg.linuxgo.gui

import android.content.SharedPreferences
import java.io.File

/**
 * Pure helpers for GUI session resolution / rootfs heuristics.
 */

/**
 * Display (viewer) scale — Lorie/Termux-X11 style.
 * Shrinks/grows the X11 framebuffer vs the Android surface; the guest DE is left at 1× DPI.
 * Range matches global settings + Termux X11 prefs.
 */
const val DISPLAY_SCALE_PCT_MIN = 30
const val DISPLAY_SCALE_PCT_MAX = 300

/** Clamp a stored or UI scale % into the supported range. */
fun clampDisplayScalePct(raw: Int): Int = raw.coerceIn(DISPLAY_SCALE_PCT_MIN, DISPLAY_SCALE_PCT_MAX)

/** Read viewer scale % from app-default (Lorie) SharedPreferences. */
fun readDisplayScalePct(appPrefs: SharedPreferences): Int {
    return clampDisplayScalePct(appPrefs.getInt("displayScale", 100))
}

/**
 * Write viewer-only scale into Lorie prefs.
 * - 100% → resolution mode "native" (1:1 pixels, no resample)
 * - other → "scaled" (framebuffer = surface × 100 / scale)
 * Does not touch container/guest settings.
 */
fun writeDisplayScalePct(appPrefs: SharedPreferences, scalePct: Int) {
    val pct = clampDisplayScalePct(scalePct)
    val mode = if (pct == 100) "native" else "scaled"
    appPrefs.edit()
        .putInt("displayScale", pct)
        .putString("displayResolutionMode", mode)
        .apply()
}

/**
 * Resolution mode string for a given viewer scale (pure; for tests / callers).
 */
fun displayResolutionModeForScalePct(scalePct: Int): String {
    return if (clampDisplayScalePct(scalePct) == 100) "native" else "scaled"
}

/**
 * Lorie-style logical size: surface × 100 / scalePct (same as LorieView scaled mode).
 */
fun computeScaledDisplaySize(
    surfaceWidthPx: Int,
    surfaceHeightPx: Int,
    scalePct: Int
): Pair<Int, Int> {
    val pct = clampDisplayScalePct(scalePct).coerceAtLeast(1)
    val w = (surfaceWidthPx * 100) / pct
    val h = (surfaceHeightPx * 100) / pct
    return w.coerceAtLeast(1) to h.coerceAtLeast(1)
}

/**
 * Compute display resolution string from prefs-like inputs.
 * [scalePct] here is legacy guest-resolution scaling; prefer viewer [writeDisplayScalePct]
 * and keep guest at 100 for quality.
 * Caps auto mode at 1920×1080.
 */
fun computeSessionResolution(
    autoRes: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    scalePct: Int,
    customWidth: Int = 1280,
    customHeight: Int = 720
): String {
    if (!autoRes) {
        return "${customWidth}x${customHeight}"
    }
    val factor = (scalePct / 100f).coerceAtLeast(0.01f)
    val scaledW = (screenWidthPx / factor).toInt()
    val scaledH = (screenHeightPx / factor).toInt()
    val finalW = scaledW.coerceAtMost(1920)
    val finalH = scaledH.coerceAtMost(1080)
    return "${finalW}x$finalH"
}

/**
 * Old browser-block stubs in pocketlinux-xfce-session should be rewritten.
 * Matches [com.sg.linuxgo.GuiSessionManager] prepareGuiRootfs heuristic.
 */
fun fileLooksLikeOldBrowserBlock(file: File): Boolean {
    if (!file.isFile || file.length() > 50_000L) return false
    return try {
        val t = file.readText()
        t.contains("block-heavy") || t.contains("browser block lifted")
    } catch (_: Exception) {
        false
    }
}

fun isArchRootfs(rootFsDir: File): Boolean {
    return try {
        File(rootFsDir, "etc/arch-release").isFile ||
            File(rootFsDir, "etc/artix-release").isFile ||
            File(rootFsDir, "etc/os-release").takeIf { it.isFile }?.readText()
                ?.contains("arch", ignoreCase = true) == true
    } catch (_: Exception) {
        false
    }
}
