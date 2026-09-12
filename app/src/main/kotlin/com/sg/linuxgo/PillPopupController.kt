package com.sg.linuxgo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.sg.linuxgo.ui.theme.Cyan
import com.sg.linuxgo.ui.utils.performLightHaptic
import kotlin.math.roundToInt

class PillPopupController(private val activity: MainActivity) {

    private var pillPopup: PopupWindow? = null
    var isPillPopupInitialized = false
        private set
    private var pillX = 0
    private var pillY = 0
    private var wasHiddenOnDown = false
    /** Avoid re-entrant recreate loops from update → show → update. */
    private var isRecreatingPopup = false

    private val pillAutoHideHandler = Handler(Looper.getMainLooper())
    private val pillAutoHideRunnable = Runnable {
        if (!isActivityWindowUsable()) return@Runnable
        val vm = activity.viewModel
        val chipPinned = pillAlertChipPinned(
            vm.lowRamPhase,
            vm.lowRamWarningDismissed,
        )
        if (!vm.menuExpanded && !vm.isImeVisible && !vm.isDraggingPill && !chipPinned) {
            vm.isPillHidden = true
            updatePillPopupSize(false)
        }
    }

    fun resetPillAutoHideTimer() {
        pillAutoHideHandler.removeCallbacks(pillAutoHideRunnable)
        if (!isActivityWindowUsable()) return
        val vm = activity.viewModel
        val chipPinned = pillAlertChipPinned(
            vm.lowRamPhase,
            vm.lowRamWarningDismissed,
        )
        if (chipPinned) return
        pillAutoHideHandler.postDelayed(pillAutoHideRunnable, 5000)
    }

    /**
     * Activity + decor must be live before any PopupWindow show/update.
     * Crash F: [PopupWindow.update] after decor was detached → process kill.
     */
    private fun isActivityWindowUsable(): Boolean {
        val decor = activity.window?.decorView
        return PillPopupWindowSafety.isActivityWindowUsable(
            activityFinishingOrDestroyed = activity.isFinishing || activity.isDestroyed,
            decorAttached = decor?.isAttachedToWindow == true,
        )
    }

    /** True when the pill popup can safely call [PopupWindow.update]. */
    private fun isPopupAttachedForUpdate(popup: PopupWindow): Boolean {
        val content = popup.contentView
        return PillPopupWindowSafety.isPopupSafeToUpdate(
            activityUsable = isActivityWindowUsable(),
            popupIsShowing = popup.isShowing,
            contentAttached = content?.isAttachedToWindow,
        )
    }

    private fun invalidatePopup(reason: String) {
        Log.w(TAG, "Invalidating pill popup: $reason")
        try {
            pillPopup?.dismiss()
        } catch (_: Exception) {
        }
        pillPopup = null
        isPillPopupInitialized = false
    }

    /**
     * Safe [PopupWindow.update]. Returns false if skipped or failed (popup may be cleared).
     * @param width use -1 to keep width; same for height
     */
    private fun safeUpdatePopup(x: Int, y: Int, width: Int = -1, height: Int = -1): Boolean {
        val popup = pillPopup ?: return false
        if (!isPopupAttachedForUpdate(popup)) {
            // Detached while still referenced — drop and let caller recreate if needed.
            if (popup.isShowing || popup.contentView != null) {
                invalidatePopup("not attached for update")
            }
            return false
        }
        return try {
            popup.update(x, y, width, height)
            true
        } catch (e: IllegalArgumentException) {
            // Crash F signature
            Log.w(TAG, "PopupWindow.update not attached: ${e.message}")
            invalidatePopup("IllegalArgumentException on update")
            false
        } catch (e: Exception) {
            Log.w(TAG, "PopupWindow.update failed: ${e.message}")
            invalidatePopup("update failed")
            false
        }
    }

    private fun safeShowAtLocation(popup: PopupWindow, x: Int, y: Int): Boolean {
        if (!isActivityWindowUsable()) return false
        val decor = activity.window.decorView
        return try {
            popup.showAtLocation(decor, Gravity.NO_GRAVITY, x, y)
            // Crash H: Compose window recomposer reads owners from PopupDecorView.
            ComposePopupOwners.installOnPopup(popup, activity)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "PopupWindow.showAtLocation not attached: ${e.message}")
            invalidatePopup("IllegalArgumentException on show")
            false
        } catch (e: IllegalStateException) {
            // ViewTreeLifecycleOwner not found from PopupDecorView (Crash H)
            Log.w(TAG, "PopupWindow.show Compose lifecycle: ${e.message}")
            invalidatePopup("IllegalStateException on show")
            false
        } catch (e: Exception) {
            Log.w(TAG, "PopupWindow.showAtLocation failed: ${e.message}")
            invalidatePopup("show failed")
            false
        }
    }

    companion object {
        private const val TAG = "PillPopupController"
    }

    /** How far the tucked pill peeks past the screen edge (larger = easier to grab). */
    private fun edgePeekPx(density: Float): Int = (20 * density).toInt()

    private fun collapsedWidthDp(): Int {
        val vm = activity.viewModel
        if (lowRamShowsChip(vm.lowRamPhase, vm.lowRamWarningDismissed)) {
            return lowRamPillWidthDp(vm.lowRamPhase)
        }
        return sessionLimitPillWidthDp(
            menuExpanded = false,
            showCountdown = false,
            phase = effectivePillAlertPhase(vm.lowRamPhase),
        )
    }

    fun showPillPopup() {
        if (!isActivityWindowUsable()) {
            // Cold start / before decor attach: retry once after layout.
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.window?.decorView?.post {
                    if (isActivityWindowUsable() && !isPillPopupInitialized) {
                        showPillPopup()
                    }
                }
            }
            return
        }
        if (activity.isDeXMode()) return

        if (pillPopup != null) {
            val existing = pillPopup
            if (existing != null && isPopupAttachedForUpdate(existing)) {
                // Already showing and attached — ensure position is valid and timer is running
                updatePillPopupSize(activity.viewModel.menuExpanded)
                resetPillAutoHideTimer()
                return
            }
            if (existing != null && !existing.isShowing) {
                val density = activity.resources.displayMetrics.density
                val screenW = activity.resources.displayMetrics.widthPixels
                val wPx = (collapsedWidthDp() * density).toInt()
                val shiftPx = edgePeekPx(density)
                val drawX = if (pillX == 0) {
                    if (activity.viewModel.isPillHidden) -shiftPx else 0
                } else {
                    if (activity.viewModel.isPillHidden) screenW - wPx + shiftPx else screenW - wPx
                }
                if (safeShowAtLocation(existing, drawX, pillY)) {
                    isPillPopupInitialized = true
                    resetPillAutoHideTimer()
                    return
                }
                // safeShow cleared invalid popup — fall through to recreate
            } else {
                // Showing flag true but not attached, or unknown state
                invalidatePopup("stale popup in showPillPopup")
            }
            if (pillPopup != null) return
        }

        val density = activity.resources.displayMetrics.density
        // Load saved position or default to right edge, middle of screen
        val prefs = activity.getSharedPreferences("wayland_fab_pos", Context.MODE_PRIVATE)
        val savedXr = prefs.getFloat("xr", 1f)

        val metrics = activity.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // Collapsed size is 96dp x 64dp (wider when session countdown is shown)
        val wPx = (collapsedWidthDp() * density).toInt()
        val hPx = (64 * density).toInt()

        val defaultYr = 0.5f - (hPx.toFloat() / 2f / screenH)
        val savedYr = prefs.getFloat("yr", defaultYr)

        pillX = if (savedXr < 0.5f) 0 else screenW - wPx
        pillY = (savedYr * screenH).toInt().coerceIn(0, screenH - hPx)

        // Initialize state
        activity.viewModel.isPillHidden = true
        activity.viewModel.menuExpanded = false
        activity.viewModel.isDraggingPill = false

        val pillComposeView = ComposeView(activity).apply {
            setContent {
                val isAtLeft = pillX == 0
                val vm = activity.viewModel
                X11DraggablePill(
                    menuExpanded = vm.menuExpanded,
                    isAtLeftEdge = isAtLeft,
                    isDragging = vm.isDraggingPill,
                    drawX = vm.currentDrawX,
                    drawY = vm.currentDrawY,
                    sessionShakeNonce = vm.sessionShakeNonce,
                    lowRamPhase = vm.lowRamPhase,
                    lowRamAvailMb = vm.lowRamAvailMb,
                    showLowRamChip = lowRamShowsChip(
                        vm.lowRamPhase,
                        vm.lowRamWarningDismissed,
                    ),
                    onPillClick = {
                        activity.viewModel.menuExpanded = !activity.viewModel.menuExpanded
                        if (!activity.viewModel.menuExpanded) {
                            activity.viewModel.isPillHidden = true
                        }
                        updatePillPopupSize(activity.viewModel.menuExpanded)
                        resetPillAutoHideTimer()
                    },
                    onKeyboardToggle = {
                        activity.viewModel.menuExpanded = false
                        activity.viewModel.isPillHidden = true
                        updatePillPopupSize(false)
                        activity.toggleSoftKeyboard()
                        resetPillAutoHideTimer()
                    },
                    onGoHome = {
                        activity.viewModel.menuExpanded = false
                        activity.viewModel.isPillHidden = true
                        updatePillPopupSize(false)
                        activity.switchToHomeTab()
                    },
                    onDismissLowRamWarning = { activity.dismissLowRamWarning() },
                )
            }
        }

        // ComposeView in a PopupWindow needs lifecycle/viewmodel owners on the content
        // and on PopupDecorView (created at show). Do NOT set android.R.id.content —
        // that shadows the activity content id (Crash A history).
        val pillComposeViewFixed = pillComposeView.apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
        }
        val pillContainer = ComposePopupOwners.contentContainer(activity, activity).apply {
            addView(pillComposeViewFixed)
        }

        pillPopup = PopupWindow(
            pillContainer,
            wPx,
            hPx,
            false
        ).apply {
            // Outside touches must reach the X11 view; do not dismiss the pill on outside touch.
            isOutsideTouchable = false
            isTouchable = true
            isFocusable = false
            // Transparent background so system doesn't auto-dismiss like a modal popup.
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        // Custom touch listener to handle dragging of the collapsed pill (16dp x 48dp handle area)
        // Always read live display metrics so rotation / multi-window resize stay correct.
        pillContainer.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var initialX = 0
            private var initialY = 0
            private var isClick = false

            private fun liveMetrics(): Triple<Int, Int, Int> {
                val d = activity.resources.displayMetrics.density
                val m = activity.resources.displayMetrics
                val wDp = collapsedWidthDp()
                return Triple(m.widthPixels, m.heightPixels, (wDp * d).toInt())
            }

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                val countdownPinned = pillAlertChipPinned(
                    activity.viewModel.lowRamPhase,
                    activity.viewModel.lowRamWarningDismissed,
                )
                // Expanded menu or alert chip: let Compose click handlers run.
                if (activity.viewModel.menuExpanded || countdownPinned) {
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
                        activity.viewModel.isDraggingPill = false
                        wasHiddenOnDown = activity.viewModel.isPillHidden

                        if (activity.viewModel.isPillHidden) {
                            activity.viewModel.isPillHidden = false
                            updatePillPopupSize(false)
                        }
                        resetPillAutoHideTimer()
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val (liveW, liveH, livePillW) = liveMetrics()
                        val liveHPx = (64 * activity.resources.displayMetrics.density).toInt()
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val distance = Math.hypot(dx.toDouble(), dy.toDouble())

                        if (distance > 10) {
                            isClick = false
                            activity.viewModel.isDraggingPill = true

                            val nextX = (initialX + dx).toInt().coerceIn(0, liveW - livePillW)
                            val nextY = (initialY + dy).toInt().coerceIn(0, liveH - liveHPx)

                            activity.viewModel.currentDrawX = nextX.toFloat()
                            activity.viewModel.currentDrawY = nextY.toFloat()

                            if (!safeUpdatePopup(nextX, nextY, -1, -1)) {
                                // Popup died mid-drag — abort drag state
                                activity.viewModel.isDraggingPill = false
                            }
                        }
                        resetPillAutoHideTimer()
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (activity.viewModel.isDraggingPill) {
                            val (liveW, liveH, livePillW) = liveMetrics()
                            val liveHPx = (64 * activity.resources.displayMetrics.density).toInt()
                            val dx = event.rawX - startX
                            val dy = event.rawY - startY
                            val finalX = (initialX + dx).toInt().coerceIn(0, liveW - livePillW)
                            val finalY = (initialY + dy).toInt().coerceIn(0, liveH - liveHPx)

                            // Snap to nearest horizontal edge
                            pillX = if (finalX < liveW / 2) 0 else liveW - livePillW
                            pillY = finalY

                            // Save snapped position in ratios
                            val xr = if (pillX == 0) 0f else 1f
                            val yr = pillY.toFloat() / liveH.toFloat().coerceAtLeast(1f)
                            activity.getSharedPreferences("wayland_fab_pos", Context.MODE_PRIVATE)
                                .edit()
                                .putFloat("xr", xr)
                                .putFloat("yr", yr)
                                .apply()

                            activity.viewModel.isDraggingPill = false

                            // Update popup with snapped coords and re-rendered compose left/right alignments
                            updatePillPopupSize(false)
                        } else if (isClick) {
                            if (wasHiddenOnDown) {
                                // Just reveal the pill from the edge, do not expand menu
                                activity.viewModel.isPillHidden = false
                                updatePillPopupSize(false)
                            } else {
                                activity.viewModel.menuExpanded = true
                                activity.viewModel.isPillHidden = false
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

        // Start slightly tucked so the handle peeks from the edge.
        val shiftPx = edgePeekPx(density)
        val drawX = if (pillX == 0) -shiftPx else screenW - wPx + shiftPx
        val created = pillPopup
        if (created == null) return
        if (safeShowAtLocation(created, drawX, pillY)) {
            isPillPopupInitialized = true
            resetPillAutoHideTimer()
        } else {
            Log.e(TAG, "Failed to show pill popup after create")
        }
    }

    fun updatePillPopupSize(expanded: Boolean) {
        if (!isActivityWindowUsable()) return
        val popup = pillPopup
        if (popup == null) return
        if (!isPopupAttachedForUpdate(popup)) {
            // Recreate if the system dismissed the window while we still track it.
            if (isRecreatingPopup) return
            isRecreatingPopup = true
            try {
                invalidatePopup("update while not attached")
                showPillPopup()
            } finally {
                isRecreatingPopup = false
            }
            return
        }
        val density = activity.resources.displayMetrics.density
        val metrics = activity.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val vm = activity.viewModel
        val wDp = if (expanded) {
            // Free-RAM chip sits with keyboard/home while GUI is on-screen.
            expandedPillMenuWidthDp(includeRamChip = true)
        } else {
            collapsedWidthDp()
        }
        val wPx = (wDp * density).toInt()
        val hPx = (64 * density).toInt()
        val shiftPx = edgePeekPx(density)

        // Keep logical edge (left vs right) and clamp Y to the current screen.
        val onLeft = pillX == 0
        pillX = if (onLeft) 0 else screenW - wPx
        pillY = pillY.coerceIn(0, (screenH - hPx).coerceAtLeast(0))

        // Never tuck while a non-dismissed alert chip is pinned.
        val chipPinned = pillAlertChipPinned(
            vm.lowRamPhase,
            vm.lowRamWarningDismissed,
        )
        val hidden = vm.isPillHidden && !chipPinned
        if (chipPinned && vm.isPillHidden) {
            vm.isPillHidden = false
        }

        val drawX = if (onLeft) {
            if (hidden) -shiftPx else 0
        } else {
            if (hidden) screenW - wPx + shiftPx else screenW - wPx
        }

        safeUpdatePopup(drawX, pillY, wPx, hPx)
    }

    /**
     * Re-snap the edge pill after orientation / screen-size changes.
     * PopupWindow keeps absolute pixel coords across configChanges, so without this
     * the pill can sit mid-screen until the user touches it.
     */
    fun repositionForCurrentScreen() {
        val popup = pillPopup ?: return
        if (!isPopupAttachedForUpdate(popup)) return
        if (activity.viewModel.isDraggingPill) return

        val density = activity.resources.displayMetrics.density
        val metrics = activity.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val vm = activity.viewModel
        val wDp = if (vm.menuExpanded) {
            expandedPillMenuWidthDp(includeRamChip = true)
        } else {
            collapsedWidthDp()
        }
        val wPx = (wDp * density).toInt()
        val hPx = (64 * density).toInt()

        val prefs = activity.getSharedPreferences("wayland_fab_pos", Context.MODE_PRIVATE)
        val savedXr = prefs.getFloat("xr", if (pillX == 0) 0f else 1f)
        val defaultYr = 0.5f - (hPx.toFloat() / 2f / screenH.coerceAtLeast(1))
        val savedYr = prefs.getFloat("yr", defaultYr)

        pillX = if (savedXr < 0.5f) 0 else screenW - wPx
        pillY = (savedYr * screenH).toInt().coerceIn(0, (screenH - hPx).coerceAtLeast(0))

        updatePillPopupSize(activity.viewModel.menuExpanded)
        resetPillAutoHideTimer()
    }

    fun hidePillPopup() {
        pillAutoHideHandler.removeCallbacks(pillAutoHideRunnable)
        try {
            pillPopup?.dismiss()
        } catch (_: Exception) {
        }
        pillPopup = null
        isPillPopupInitialized = false
    }

    @Composable
    private fun X11DraggablePill(
        menuExpanded: Boolean,
        isAtLeftEdge: Boolean,
        isDragging: Boolean,
        drawX: Float,
        drawY: Float,
        sessionShakeNonce: Int,
        lowRamPhase: LowRamPillPhase,
        lowRamAvailMb: Int,
        showLowRamChip: Boolean,
        onPillClick: () -> Unit,
        onKeyboardToggle: () -> Unit,
        onGoHome: () -> Unit,
        onDismissLowRamWarning: () -> Unit,
    ) {
        val buttonBgColor = ComposeColor(0xFF222222)
        val visualPhase = effectivePillAlertPhase(lowRamPhase)
        val showRamChip = showLowRamChip
        // Shake the thin handle only when an alert is active but its chip is dismissed.
        val shakeHandleOnly = !showRamChip && visualPhase != SessionLimitPillPhase.None

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (isDragging) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(drawX.roundToInt(), drawY.roundToInt()) }
                        .width(96.dp)
                        .height(64.dp),
                    contentAlignment = if (isAtLeftEdge) Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    SessionAwarePillHandle(
                        phase = visualPhase,
                        shakeNonce = sessionShakeNonce,
                        playShake = false,
                    )
                }
            } else if (menuExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = if (isAtLeftEdge) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Free-RAM chip on the outer end (toward screen center, farthest from edge handle).
                    val menuRamChip: @Composable () -> Unit = {
                        GuiFreeRamChip(
                            phase = lowRamPhase,
                            availRamMb = lowRamAvailMb,
                            shakeNonce = sessionShakeNonce,
                            playShake = false,
                            showDismiss = false,
                            onDismiss = {},
                        )
                    }
                    if (isAtLeftEdge) {
                        // Left edge: [handle] [keyboard] [home] [RAM →]
                        SessionAwarePillHandle(
                            phase = visualPhase,
                            shakeNonce = sessionShakeNonce,
                            playShake = false,
                            onClick = onPillClick,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        PillMenuActionButton(
                            bg = buttonBgColor,
                            iconRes = R.drawable.ic_keyboard,
                            contentDescription = "Keyboard",
                            onClick = onKeyboardToggle,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        PillMenuActionButton(
                            bg = buttonBgColor,
                            iconRes = R.drawable.ic_home,
                            contentDescription = "Go Home",
                            onClick = onGoHome,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        menuRamChip()
                    } else {
                        // Right edge: [← RAM] [home] [keyboard] [handle]
                        menuRamChip()
                        Spacer(modifier = Modifier.width(6.dp))
                        PillMenuActionButton(
                            bg = buttonBgColor,
                            iconRes = R.drawable.ic_home,
                            contentDescription = "Go Home",
                            onClick = onGoHome,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        PillMenuActionButton(
                            bg = buttonBgColor,
                            iconRes = R.drawable.ic_keyboard,
                            contentDescription = "Keyboard",
                            onClick = onKeyboardToggle,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SessionAwarePillHandle(
                            phase = visualPhase,
                            shakeNonce = sessionShakeNonce,
                            playShake = false,
                            onClick = onPillClick,
                        )
                    }
                }
            } else if (showRamChip) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = if (isAtLeftEdge) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isAtLeftEdge) {
                        SessionAwarePillHandle(
                            phase = visualPhase,
                            shakeNonce = sessionShakeNonce,
                            playShake = false,
                            onClick = onPillClick,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        LowRamWarningChip(
                            phase = lowRamPhase,
                            availRamMb = lowRamAvailMb,
                            shakeNonce = sessionShakeNonce,
                            onDismiss = onDismissLowRamWarning,
                        )
                    } else {
                        LowRamWarningChip(
                            phase = lowRamPhase,
                            availRamMb = lowRamAvailMb,
                            shakeNonce = sessionShakeNonce,
                            onDismiss = onDismissLowRamWarning,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SessionAwarePillHandle(
                            phase = visualPhase,
                            shakeNonce = sessionShakeNonce,
                            playShake = false,
                            onClick = onPillClick,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = if (isAtLeftEdge) Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    SessionAwarePillHandle(
                        phase = visualPhase,
                        shakeNonce = sessionShakeNonce,
                        playShake = shakeHandleOnly,
                    )
                }
            }
        }
    }

    @Composable
    private fun PillMenuActionButton(
        bg: ComposeColor,
        iconRes: Int,
        contentDescription: String,
        onClick: () -> Unit,
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
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = Cyan,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    fun dispatchTouchEvent(event: android.view.MotionEvent, superDispatch: (android.view.MotionEvent) -> Boolean): Boolean? {
        val popup = pillPopup ?: return null
        if (!isPopupAttachedForUpdate(popup)) return null
        
        val rawX = event.rawX
        val rawY = event.rawY
        val density = activity.resources.displayMetrics.density
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val w = popup.width
        val h = popup.height
        
        val shiftPx = edgePeekPx(density)
        val actualPopupX = if (pillX == 0) {
            if (activity.viewModel.isPillHidden) -shiftPx else 0
        } else {
            if (activity.viewModel.isPillHidden) screenWidth - w + shiftPx else screenWidth - w
        }
        val y = pillY

        if (activity.viewModel.isDraggingPill) {
            return superDispatch(event)
        } else {
            if (rawX >= actualPopupX && rawX <= actualPopupX + w && rawY >= y && rawY <= y + h) {
                return superDispatch(event)
            }
        }
        return null
    }
}
