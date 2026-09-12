package com.sg.linuxgo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.PointerIcon
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import com.sg.linuxgo.x11.ICmdEntryInterface
import com.sg.linuxgo.x11.LorieView
import com.sg.linuxgo.x11.Prefs
import com.sg.linuxgo.util.getSafeBoolean
import com.sg.linuxgo.util.getSafeFloat
import com.sg.linuxgo.util.getSafeString
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** Fullscreen, pointer capture, cursor, session resolution. */

fun GuiSessionManager.setFullscreen() {
    activity.runOnUiThread {
        try {
            if (activity is androidx.appcompat.app.AppCompatActivity) {
                activity.supportActionBar?.hide()
            }
            
            updateCursorVisibility(true)

            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val hideCutout = sharedPrefs.getBoolean("hideCutout", false)
            val isFullscreen = sharedPrefs.getBoolean("fullscreen", true)
            val keepScreenOn = sharedPrefs.getBoolean("keepScreenOn", true)
            val reseed = sharedPrefs.getBoolean("Reseed", false)

            val attrs = activity.window.attributes
            var attrsChanged = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 15+: avoid deprecated DEFAULT / SHORT_EDGES. NEVER = avoid notch;
                // ALWAYS (API 30+) = draw edge-to-edge into cutout.
                val targetCutout = when {
                    hideCutout -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else -> attrs.layoutInDisplayCutoutMode // API 28–29: leave platform default
                }
                if (attrs.layoutInDisplayCutoutMode != targetCutout) {
                    attrs.layoutInDisplayCutoutMode = targetCutout
                    attrsChanged = true
                }
            }

            val targetSoftInputMode = if (reseed) {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
            if ((attrs.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) != targetSoftInputMode) {
                attrs.softInputMode = (attrs.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or targetSoftInputMode
                attrsChanged = true
            }

            val hasKeepScreenOn = (attrs.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            if (hasKeepScreenOn != keepScreenOn) {
                if (keepScreenOn) {
                    attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                } else {
                    attrs.flags = attrs.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                }
                attrsChanged = true
            }

            // Prefer WindowInsetsController over FLAG_FULLSCREEN (deprecated with edge-to-edge).
            if ((attrs.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0) {
                attrs.flags = attrs.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN.inv()
                attrsChanged = true
            }

            // Stay edge-to-edge (decor does not fit system windows). Immersive mode only
            // hides/shows bars via WindowInsetsControllerCompat — no deprecated systemUi flags.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            val insetsController = androidx.core.view.WindowInsetsControllerCompat(
                activity.window,
                activity.window.decorView
            )
            var sysUiChanged = true
            if (isFullscreen) {
                insetsController.hide(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                        androidx.core.view.WindowInsetsCompat.Type.navigationBars()
                )
                insetsController.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                        androidx.core.view.WindowInsetsCompat.Type.navigationBars()
                )
            }

            if (attrsChanged) {
                activity.window.attributes = attrs
            }
            
            if (attrsChanged || sysUiChanged) {
                activity.window.decorView.requestLayout()
            }
            updatePointerCapture()
        } catch (e: Exception) {
            Log.e("GuiSessionManager", "Error setting fullscreen", e)
        }
    }
}

fun GuiSessionManager.resetFullscreen() {
    loadingHandler.removeCallbacks(fullscreenRunnable)
    if (activity is androidx.appcompat.app.AppCompatActivity) {
        activity.supportActionBar?.show()
    }
    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    activity.applyKeepScreenOnPreference()

    // Restore visible system bars while staying edge-to-edge (API 35+).
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    val insetsController = androidx.core.view.WindowInsetsControllerCompat(
        activity.window,
        activity.window.decorView
    )
    insetsController.show(
        androidx.core.view.WindowInsetsCompat.Type.statusBars() or
            androidx.core.view.WindowInsetsCompat.Type.navigationBars()
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Edge-to-edge into cutout when leaving immersive (replaces deprecated DEFAULT).
        val attrs = activity.window.attributes
        attrs.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        activity.window.attributes = attrs
    }
    updatePointerCapture()
}

fun GuiSessionManager.updatePointerCapture() {
    val lorieView = viewDelegate.getLorieView()
    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(activity)
    val capture = sharedPrefs.getBoolean("pointerCapture", false)
    val isGuiVisible = viewDelegate.isGuiVisible()
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (capture && isGuiVisible && activity.hasWindowFocus()) {
            if (!lorieView.hasPointerCapture()) {
                try {
                    lorieView.requestPointerCapture()
                } catch (e: Exception) {
                    Log.e("GuiSessionManager", "Failed to request pointer capture", e)
                }
            }
        } else {
            if (lorieView.hasPointerCapture()) {
                try {
                    lorieView.releasePointerCapture()
                } catch (e: Exception) {
                    Log.e("GuiSessionManager", "Failed to release pointer capture", e)
                }
            }
        }
    }
}

internal var currentCursorHidden: Boolean? = null
internal fun GuiSessionManager.updateCursorVisibility(hide: Boolean) {
    if (currentCursorHidden == hide) return
    currentCursorHidden = hide
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try {
            val icon = if (hide) PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_NULL)
                       else PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_ARROW)
            
            viewDelegate.getLorieView().pointerIcon = icon
        } catch (e: Exception) {
            Log.e("GuiSessionManager", "Error updating cursor visibility", e)
        }
    }
}

fun GuiSessionManager.getSessionResolution(): String {
    val cid = activeContainerIdProvider()
    val prefsName = if (cid != null) "container_${cid}_settings" else "pocket_linux_settings"
    val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    val autoRes = prefs.getBoolean("res_auto", true)
    val metrics = activity.resources.displayMetrics
    return com.sg.linuxgo.gui.computeSessionResolution(
        autoRes = autoRes,
        screenWidthPx = metrics.widthPixels,
        screenHeightPx = metrics.heightPixels,
        scalePct = selectedScalePct,
        customWidth = prefs.getInt("res_width", 1280),
        customHeight = prefs.getInt("res_height", 720)
    )
}

