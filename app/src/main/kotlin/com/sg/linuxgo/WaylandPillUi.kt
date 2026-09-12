package com.sg.linuxgo

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sg.linuxgo.ui.components.SpecialKeysBar
import com.sg.linuxgo.ui.theme.Cyan
import com.sg.linuxgo.ui.utils.performLightHaptic
import kotlin.math.roundToInt

/**
 * Edge pill for Wayland — same visual + interaction model as [PillPopupController] (X11):
 * tucked edge handle, first tap reveals, second expands horizontal Keyboard/Home buttons.
 */

/** How far the tucked pill peeks past the screen edge (larger = easier to grab). */
private fun edgePeekPx(density: Float): Int = (20 * density).toInt()

internal fun WaylandActivity.showPillPopup() {
    val host = this
    if (isFinishing || isDestroyed) return
    if (isDeXMode()) return

    if (pillPopup != null) {
        if (pillPopup?.isShowing == false) {
            try {
                val density = resources.displayMetrics.density
                val screenW = resources.displayMetrics.widthPixels
                val wPx = (96 * density).toInt()
                val shiftPx = edgePeekPx(density)
                val drawX = if (pillX == 0) {
                    if (isPillHidden) -shiftPx else 0
                } else {
                    if (isPillHidden) screenW - wPx + shiftPx else screenW - wPx
                }
                pillPopup?.showAtLocation(window.decorView, Gravity.NO_GRAVITY, drawX, pillY)
                pillPopup?.let { ComposePopupOwners.installOnPopup(it, host) }
                resetPillAutoHideTimer()
            } catch (e: Exception) {
                Log.e("WaylandPillUi", "Failed to re-show pill popup", e)
                pillPopup = null
                isPillPopupInitialized = false
            }
        } else {
            updatePillPopupSize(menuExpanded)
            resetPillAutoHideTimer()
        }
        if (pillPopup != null) return
    }

    val density = resources.displayMetrics.density
    val prefs = getSharedPreferences("wayland_fab_pos", Context.MODE_PRIVATE)
    val savedXr = prefs.getFloat("xr", 1f)

    val metrics = resources.displayMetrics
    val screenW = metrics.widthPixels
    val screenH = metrics.heightPixels

    val wPx = (96 * density).toInt()
    val hPx = (64 * density).toInt()

    val defaultYr = 0.5f - (hPx.toFloat() / 2f / screenH)
    val savedYr = prefs.getFloat("yr", defaultYr)

    pillX = if (savedXr < 0.5f) 0 else screenW - wPx
    pillY = (savedYr * screenH).toInt().coerceIn(0, screenH - hPx)

    isPillHidden = true
    menuExpanded = false
    isDraggingPill = false

    val pillComposeView = ComposeView(this).apply {
        setContent {
            val isAtLeft = pillX == 0
            WaylandDraggablePill(
                menuExpanded = menuExpanded,
                isAtLeftEdge = isAtLeft,
                isDragging = isDraggingPill,
                drawX = currentDrawX,
                drawY = currentDrawY,
                onPillClick = {
                    menuExpanded = !menuExpanded
                    if (!menuExpanded) {
                        isPillHidden = true
                    }
                    updatePillPopupSize(menuExpanded)
                    resetPillAutoHideTimer()
                },
                onKeyboardToggle = {
                    menuExpanded = false
                    isPillHidden = true
                    updatePillPopupSize(false)
                    toggleSoftKeyboard()
                    resetPillAutoHideTimer()
                },
                onGoHome = {
                    menuExpanded = false
                    isPillHidden = true
                    updatePillPopupSize(false)
                    resetPillAutoHideTimer()
                    val intent = Intent(host, MainActivity::class.java).apply {
                        // Same task as WaylandActivity; bring MainActivity to front without a new window.
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("EXTRA_GO_HOME", true)
                    }
                    startActivity(intent)
                }
            )
        }
    }

    // Owners must reach PopupDecorView before Compose attaches (Crash H).
    // Prefer contentContainer over android.R.id.content (Crash A shadowing risk).
    pillComposeView.setViewTreeLifecycleOwner(host)
    pillComposeView.setViewTreeSavedStateRegistryOwner(host)
    pillComposeView.setViewTreeViewModelStoreOwner(host)
    val pillContainer = ComposePopupOwners.contentContainer(this, host).apply {
        addView(pillComposeView)
    }

    pillPopup = PopupWindow(
        pillContainer,
        wPx,
        hPx,
        false
    ).apply {
        isOutsideTouchable = false
        isTouchable = true
        isFocusable = false
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    // Always read live display metrics so rotation / multi-window resize stay correct.
    pillContainer.setOnTouchListener(object : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var initialX = 0
        private var initialY = 0
        private var isClick = false

        private fun liveMetrics(): Triple<Int, Int, Int> {
            val d = resources.displayMetrics.density
            val m = resources.displayMetrics
            return Triple(m.widthPixels, m.heightPixels, (96 * d).toInt())
        }

        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            if (menuExpanded) {
                resetPillAutoHideTimer()
                return false
            }

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = pillX
                    initialY = pillY
                    isClick = true
                    isDraggingPill = false
                    wasHiddenOnDown = isPillHidden

                    if (isPillHidden) {
                        isPillHidden = false
                        updatePillPopupSize(false)
                    }
                    resetPillAutoHideTimer()
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val (liveW, liveH, livePillW) = liveMetrics()
                    val liveHPx = (64 * resources.displayMetrics.density).toInt()
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val distance = Math.hypot(dx.toDouble(), dy.toDouble())

                    if (distance > 10) {
                        isClick = false
                        isDraggingPill = true

                        val nextX = (initialX + dx).toInt().coerceIn(0, liveW - livePillW)
                        val nextY = (initialY + dy).toInt().coerceIn(0, liveH - liveHPx)

                        currentDrawX = nextX.toFloat()
                        currentDrawY = nextY.toFloat()

                        pillPopup?.update(nextX, nextY, -1, -1)
                    }
                    resetPillAutoHideTimer()
                    return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingPill) {
                        val (liveW, liveH, livePillW) = liveMetrics()
                        val liveHPx = (64 * resources.displayMetrics.density).toInt()
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val finalX = (initialX + dx).toInt().coerceIn(0, liveW - livePillW)
                        val finalY = (initialY + dy).toInt().coerceIn(0, liveH - liveHPx)

                        pillX = if (finalX < liveW / 2) 0 else liveW - livePillW
                        pillY = finalY

                        val xr = if (pillX == 0) 0f else 1f
                        val yr = pillY.toFloat() / liveH.toFloat().coerceAtLeast(1f)
                        getSharedPreferences("wayland_fab_pos", Context.MODE_PRIVATE)
                            .edit()
                            .putFloat("xr", xr)
                            .putFloat("yr", yr)
                            .apply()

                        isDraggingPill = false
                        updatePillPopupSize(false)
                    } else if (isClick) {
                        if (wasHiddenOnDown) {
                            isPillHidden = false
                            updatePillPopupSize(false)
                        } else {
                            menuExpanded = true
                            isPillHidden = false
                            updatePillPopupSize(true)
                        }
                    }
                    resetPillAutoHideTimer()
                    return true
                }
            }
            return false
        }
    })

    val shiftPx = edgePeekPx(density)
    val drawX = if (pillX == 0) -shiftPx else screenW - wPx + shiftPx
    try {
        pillPopup?.showAtLocation(window.decorView, Gravity.NO_GRAVITY, drawX, pillY)
        pillPopup?.let { ComposePopupOwners.installOnPopup(it, host) }
        isPillPopupInitialized = true
        resetPillAutoHideTimer()
    } catch (e: Exception) {
        Log.e("WaylandPillUi", "Failed to show pill popup", e)
        try {
            pillPopup?.dismiss()
        } catch (_: Exception) {
        }
        pillPopup = null
        isPillPopupInitialized = false
    }
}

internal fun WaylandActivity.updatePillPopupSize(expanded: Boolean) {
    val popup = pillPopup ?: return
    if (!popup.isShowing) {
        try {
            showPillPopup()
        } catch (_: Exception) {
        }
        return
    }
    val density = resources.displayMetrics.density
    val metrics = resources.displayMetrics
    val screenW = metrics.widthPixels
    val screenH = metrics.heightPixels

    val wPx = ((if (expanded) 160 else 96) * density).toInt()
    val hPx = (64 * density).toInt()
    val shiftPx = edgePeekPx(density)

    // Keep logical edge (left vs right) and clamp Y to the current screen.
    val onLeft = pillX == 0
    pillX = if (onLeft) 0 else screenW - wPx
    pillY = pillY.coerceIn(0, (screenH - hPx).coerceAtLeast(0))

    val drawX = if (onLeft) {
        if (isPillHidden) -shiftPx else 0
    } else {
        if (isPillHidden) screenW - wPx + shiftPx else screenW - wPx
    }

    popup.update(drawX, pillY, wPx, hPx)
}

/**
 * Re-snap the edge pill after orientation / screen-size changes.
 * PopupWindow keeps absolute pixel coords across configChanges, so without this
 * the pill can sit mid-screen until the user touches it.
 */
internal fun WaylandActivity.repositionPillForCurrentScreen() {
    val popup = pillPopup ?: return
    if (!popup.isShowing) return
    if (isDraggingPill) return

    val density = resources.displayMetrics.density
    val metrics = resources.displayMetrics
    val screenW = metrics.widthPixels
    val screenH = metrics.heightPixels
    val wPx = ((if (menuExpanded) 160 else 96) * density).toInt()
    val hPx = (64 * density).toInt()

    val prefs = getSharedPreferences("wayland_fab_pos", Context.MODE_PRIVATE)
    val savedXr = prefs.getFloat("xr", if (pillX == 0) 0f else 1f)
    val defaultYr = 0.5f - (hPx.toFloat() / 2f / screenH.coerceAtLeast(1))
    val savedYr = prefs.getFloat("yr", defaultYr)

    pillX = if (savedXr < 0.5f) 0 else screenW - wPx
    pillY = (savedYr * screenH).toInt().coerceIn(0, (screenH - hPx).coerceAtLeast(0))

    updatePillPopupSize(menuExpanded)
    resetPillAutoHideTimer()
}

internal fun WaylandActivity.hidePillPopup() {
    pillPopup?.dismiss()
    pillPopup = null
    isPillPopupInitialized = false
}

internal fun WaylandActivity.showKeyBarPopup() {
    val host = this
    if (isFinishing || isDestroyed) return
    if (keybarPopup?.isShowing == true) return

    val density = resources.displayMetrics.density
    val heightPx = (48 * density).toInt()

    val keybarComposeView = ComposeView(this).apply {
        setContent {
            SpecialKeysBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(ComposeColor(0xFF1A1A1A)),
                onKeyPressed = { onSpecialKeyPressed(it) },
                ctrlActive = isCtrlActive,
                altActive = isAltActive,
                shiftActive = isShiftActive
            )
        }
    }

    keybarComposeView.setViewTreeLifecycleOwner(host)
    keybarComposeView.setViewTreeSavedStateRegistryOwner(host)
    keybarComposeView.setViewTreeViewModelStoreOwner(host)
    val keybarContainer = ComposePopupOwners.contentContainer(this, host).apply {
        addView(keybarComposeView)
    }

    keybarPopup = PopupWindow(
        keybarContainer,
        WindowManager.LayoutParams.MATCH_PARENT,
        heightPx,
        false
    ).apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        isOutsideTouchable = false
        isClippingEnabled = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }

    try {
        keybarPopup?.showAsDropDown(keybarAnchor, 0, -heightPx)
        keybarPopup?.let { ComposePopupOwners.installOnPopup(it, host) }
    } catch (e: Exception) {
        Log.e("WaylandPillUi", "Failed to show keybar popup", e)
        try {
            keybarPopup?.dismiss()
        } catch (_: Exception) {
        }
        keybarPopup = null
    }
}

internal fun WaylandActivity.hideKeyBarPopup() {
    keybarPopup?.dismiss()
    keybarPopup = null
}

@Composable
internal fun WaylandActivity.WaylandDraggablePill(
    menuExpanded: Boolean,
    isAtLeftEdge: Boolean,
    isDragging: Boolean,
    drawX: Float,
    drawY: Float,
    onPillClick: () -> Unit,
    onKeyboardToggle: () -> Unit,
    onGoHome: () -> Unit
) {
    val pillColor = ComposeColor(0xFF333333).copy(alpha = 0.85f)
    val buttonBgColor = ComposeColor(0xFF222222)

    Box(modifier = Modifier.fillMaxSize()) {
        if (isDragging) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(drawX.roundToInt(), drawY.roundToInt()) }
                    .width(96.dp)
                    .height(64.dp),
                contentAlignment = if (isAtLeftEdge) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                PillHandle(pillColor)
            }
        } else if (menuExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = if (isAtLeftEdge) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isAtLeftEdge) {
                    PillHandle(pillColor, onClick = onPillClick)
                    Spacer(modifier = Modifier.width(8.dp))
                    PillActionButton(
                        bg = buttonBgColor,
                        iconRes = R.drawable.ic_keyboard,
                        contentDescription = "Keyboard",
                        onClick = onKeyboardToggle
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PillActionButton(
                        bg = buttonBgColor,
                        iconRes = R.drawable.ic_home,
                        contentDescription = "Go Home",
                        onClick = onGoHome
                    )
                } else {
                    PillActionButton(
                        bg = buttonBgColor,
                        iconRes = R.drawable.ic_home,
                        contentDescription = "Go Home",
                        onClick = onGoHome
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PillActionButton(
                        bg = buttonBgColor,
                        iconRes = R.drawable.ic_keyboard,
                        contentDescription = "Keyboard",
                        onClick = onKeyboardToggle
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PillHandle(pillColor, onClick = onPillClick)
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = if (isAtLeftEdge) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                PillHandle(pillColor)
            }
        }
    }
}

@Composable
private fun PillHandle(pillColor: ComposeColor, onClick: (() -> Unit)? = null) {
    val mod = Modifier
        .width(16.dp)
        .height(48.dp)
        .shadow(6.dp, RoundedCornerShape(8.dp))
        .clip(RoundedCornerShape(8.dp))
        .background(pillColor)
        .let { if (onClick != null) it.clickable { onClick() } else it }
    Box(modifier = mod, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Cyan.copy(alpha = 0.7f))
        )
    }
}

@Composable
private fun PillActionButton(
    bg: ComposeColor,
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(4.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable {
                view.performLightHaptic()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Cyan,
            modifier = Modifier.size(20.dp)
        )
    }
}

internal fun WaylandActivity.resetPillAutoHideTimer() {
    pillAutoHideHandler.removeCallbacks(pillAutoHideRunnable)
    pillAutoHideHandler.postDelayed(pillAutoHideRunnable, 5000)
}
