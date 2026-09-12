package com.sg.linuxgo

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sg.linuxgo.ui.screens.FancyGuiLoadingOverlay

/**
 * Full-screen boot animation for Wayland.
 *
 * Must use a [PopupWindow] (same reason as the edge pill): NativeActivity’s EGL
 * surface draws over normal DecorView children, so a ComposeView in the overlay
 * layout is invisible. PopupWindows sit above the native surface.
 */

internal fun WaylandActivity.resolveBootingLabel(): String {
    intent?.getStringExtra("POCKETLINUX_BOOT_LABEL")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return it
    }
    return try {
        val prefs = getSharedPreferences("pocket_linux_state", Context.MODE_PRIVATE)
        val cid = prefs.getString("active_container_id", null)
            ?: intent?.getStringExtra("CONTAINER_ID")
        val name = cid?.let { ContainerManager(this).getContainer(it)?.name }?.trim().orEmpty()
        if (name.isNotEmpty()) "Booting $name" else "Booting Desktop"
    } catch (_: Exception) {
        "Booting Desktop"
    }
}

internal fun WaylandActivity.showLoadingPopup() {
    if (isFinishing || isDestroyed) return
    if (loadingPopup?.isShowing == true) return

    bootingLabel = resolveBootingLabel()
    showLoadingOverlay = true
    hideKeyBarPopup()

    val host = this
    val composeView = ComposeView(this).apply {
        setViewTreeLifecycleOwner(host)
        setViewTreeSavedStateRegistryOwner(host)
        setViewTreeViewModelStoreOwner(host)
        setContent {
            if (showLoadingOverlay) {
                FancyGuiLoadingOverlay(
                    statusText = bootingLabel,
                    hintText = run {
                        val availMb = readAvailRamMbForPill(host)
                        lowRamLoadingHint(lowRamPhaseFor(availMb), availMb)
                    },
                )
            }
        }
    }

    // Crash H: owners must be on PopupDecorView before Compose attaches.
    val container = ComposePopupOwners.contentContainer(this, host).apply {
        addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    loadingPopup = PopupWindow(
        container,
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        false
    ).apply {
        isOutsideTouchable = false
        isTouchable = true
        isFocusable = false
        isClippingEnabled = false
        setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        // Keep above the native compositor surface for the whole boot.
        elevation = 32f
    }

    fun showNow(anchor: View) {
        loadingPopup?.showAtLocation(anchor, Gravity.NO_GRAVITY, 0, 0)
        loadingPopup?.let { ComposePopupOwners.installOnPopup(it, host) }
    }

    try {
        val decor = window.decorView
        if (decor.windowToken != null) {
            showNow(decor)
        } else {
            decor.post {
                if (!isFinishing && !isDestroyed && showLoadingOverlay) {
                    try {
                        showNow(window.decorView)
                    } catch (e: Exception) {
                        Log.e("WaylandLoadingUi", "Deferred showLoadingPopup failed", e)
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e("WaylandLoadingUi", "showLoadingPopup failed", e)
    }
}

internal fun WaylandActivity.updateLoadingStatus(status: String) {
    bootingLabel = status
}

internal fun WaylandActivity.hideLoadingPopup() {
    showLoadingOverlay = false
    try {
        loadingPopup?.dismiss()
    } catch (_: Exception) {
    }
    loadingPopup = null
    try {
        loadingComposeView.visibility = View.GONE
    } catch (_: Exception) {
        // lateinit may not be set yet during early destroy
    }
}
