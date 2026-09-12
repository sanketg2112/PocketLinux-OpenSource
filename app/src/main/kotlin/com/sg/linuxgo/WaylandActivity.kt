package com.sg.linuxgo

import android.app.NativeActivity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import com.sg.linuxgo.ui.utils.performClickHaptic
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sg.linuxgo.ui.components.SpecialKeysBar
import com.sg.linuxgo.ui.theme.Cyan
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.BorderStroke
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Custom Wayland activity that extends NativeActivity to load `libwayland_compositor.so`.
 *
 * Provides full input translation (touch-to-mouse), overlay UI (key bar, FAB menu),
 * keyboard dispatch with sticky modifiers, fullscreen/PIP, and independent preferences.
 * Designed as a complete, standalone replacement for the X11 display path.
 */
class WaylandActivity : NativeActivity(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    // Lifecycle and state support for ComposeView inside NativeActivity
    internal val lifecycleRegistry = LifecycleRegistry(this)
    internal val savedStateRegistryController = SavedStateRegistryController.create(this)
    internal val _viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    internal lateinit var sharedPrefs: SharedPreferences
    internal val accessibilityService = app.polarbear.KeyboardAccessibilityService()
    internal lateinit var overlayLayout: FrameLayout
    internal lateinit var guiImeAnchor: EditText
    internal lateinit var keybarAnchor: View

    // Session Paused Overlay
    internal var isSessionPaused = false
    internal lateinit var sessionPausedComposeView: ComposeView

    // Modifier key sticky states
    internal var isCtrlActive by mutableStateOf(false)
    internal var isAltActive by mutableStateOf(false)
    internal var isShiftActive by mutableStateOf(false)
    internal var isFnActive by mutableStateOf(false)

    // IME visibility tracking
    internal var isImeVisible by mutableStateOf(false)

    // Virtual mouse pointer position (trackpad mode)
    internal var mouseX = 0f
    internal var mouseY = 0f
    internal var lastTouchX = 0f
    internal var lastTouchY = 0f
    internal var isDragging = false

    // Popups for overlays to prevent native EGL rendering from drawing over them
    internal var pillPopup: PopupWindow? = null
    internal var keybarPopup: PopupWindow? = null
    internal var isPillPopupInitialized = false
    internal var pillX = 0
    internal var pillY = 0

    internal var isPillHidden by mutableStateOf(true)
    internal var menuExpanded by mutableStateOf(false)
    internal var shownDexToast = false
    internal var isDraggingPill by mutableStateOf(false)
    /** True when ACTION_DOWN started while the pill was tucked (first tap only reveals). */
    internal var wasHiddenOnDown = false
    internal var currentDrawX by mutableFloatStateOf(0f)
    internal var currentDrawY by mutableFloatStateOf(0f)

    internal val pillAutoHideHandler = Handler(Looper.getMainLooper())
    internal val pillAutoHideRunnable = Runnable {
        if (!menuExpanded && !isImeVisible && !isDraggingPill) {
            isPillHidden = true
            updatePillPopupSize(false)
        }
    }

    // Back press state machine
    internal var guiBackPressState = 0
    internal val guiBackPressResetHandler = Handler(Looper.getMainLooper())
    internal val guiBackPressResetRunnable = Runnable { guiBackPressState = 0 }

    internal var showLoadingOverlay by mutableStateOf(true)
    internal var bootingLabel by mutableStateOf("Booting Desktop")
    /** Full-screen loading PopupWindow (must sit above native EGL surface). */
    internal var loadingPopup: PopupWindow? = null
    internal lateinit var loadingComposeView: ComposeView

    internal val pillWatchdogHandler = Handler(Looper.getMainLooper())
    internal val pillWatchdogRunnable = object : Runnable {
        override fun run() {
            if (isDeXMode()) return
            if (isPillPopupInitialized && pillPopup?.isShowing != true && !isFinishing && !isDestroyed) {
                showPillPopup()
            }
            pillWatchdogHandler.postDelayed(this, 3000)
        }
    }

    // Broadcast receiver for termination
    internal val terminateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.sg.linuxgo.ACTION_TERMINATE_SESSION") {
                finish()
            }
        }
    }

    // Double-ESC exit tracking
    internal var doubleEscPressedOnce = false

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            System.loadLibrary("wayland_compositor")
        } catch (e: Exception) {
            android.util.Log.e("WaylandActivity", "Failed to load wayland_compositor library", e)
        }
        savedStateRegistryController.performRestore(savedInstanceState)
        // Edge-to-edge before content (NativeActivity is not a ComponentActivity, so no
        // enableEdgeToEdge(); setupFullscreen applies WindowCompat + cutout modes).
        super.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        sharedPrefs = getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)

        // Register termination receiver
        val filter = android.content.IntentFilter("com.sg.linuxgo.ACTION_TERMINATE_SESSION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(terminateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(terminateReceiver, filter)
        }

        // Set environment variables passed from MainActivity to the Wayland process
        intent?.extras?.let { extras ->
            for (key in extras.keySet()) {
                val value = extras.get(key)?.toString()
                if (value != null) {
                    try {
                        android.system.Os.setenv(key, value, true)
                        android.util.Log.d("WaylandActivity", "Set env $key=$value")
                    } catch (e: Exception) {
                        android.util.Log.e("WaylandActivity", "Failed to set env $key=$value", e)
                    }
                }
            }
        }
        // Force GUI_MODE and WAYLAND_DISPLAY environment variables
        try {
            android.system.Os.setenv("GUI_MODE", "wayland", true)
            android.system.Os.setenv("WAYLAND_DISPLAY", "wayland-0", true)
        } catch (e: Exception) {
            android.util.Log.e("WaylandActivity", "Failed to set default Wayland envs", e)
        }

        setupFullscreen()
        // Match the boot overlay so the first EGL frame is not a white/black flash.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(0xFF0A0A0C.toInt())
        )

        // Set ViewTree owners on the Window's decorView so they are available to all child views
        val decorView = window.decorView as FrameLayout
        decorView.setViewTreeLifecycleOwner(this)
        decorView.setViewTreeSavedStateRegistryOwner(this)
        decorView.setViewTreeViewModelStoreOwner(this)

        // Inject overlay layout on top of the native surface by adding it directly to the Window's DecorView.
        // The DecorView is always laid out to full screen size, ensuring our overlay receives layout passes.
        overlayLayout = FrameLayout(this)
        decorView.addView(
            overlayLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Hidden EditText as IME anchor for soft keyboard
        guiImeAnchor = EditText(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            alpha = 0f
            isFocusable = true
            isFocusableInTouchMode = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            privateImeOptions = "nm"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    injectKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 28)
                    injectKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 28)
                    true
                } else {
                    false
                }
            }
            setOnKeyListener { _, _, _ ->
                true // Consume key events to prevent them from recursively modifying the text of EditText in Java
            }
            setText("  ")
            setSelection(2)
            addTextChangedListener(object : android.text.TextWatcher {
                private var isResetting = false
                private var isDispatching = false
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    android.util.Log.d("WaylandActivity", "afterTextChanged: text='${s?.toString()}'")
                    if (isResetting || isDispatching) return
                    val text = s?.toString() ?: ""
                    if (text != "  ") {
                        val diff = text.length - 2
                        isDispatching = true
                        try {
                            if (diff > 0) {
                                val addedText = text.substring(2)
                                for (c in addedText) {
                                    dispatchCharEvents(c)
                                }
                            } else if (diff < 0) {
                                val deletesCount = kotlin.math.abs(diff)
                                for (i in 0 until deletesCount) {
                                    injectKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 14)
                                    injectKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 14)
                                }
                            }
                        } finally {
                            isDispatching = false
                        }
                        isResetting = true
                        setText("  ")
                        setSelection(2)
                        isResetting = false
                    }
                }
            })
        }
        overlayLayout.addView(guiImeAnchor)

        // Session Paused Overlay
        sessionPausedComposeView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                MaterialTheme {
                    val view = LocalView.current
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xAA000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .background(
                                    color = Color(0xFF1E1E24),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(24.dp)
                                .width(280.dp)
                        ) {
                            Text(
                                text = "Session Paused",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Debian / XFCE",
                                color = Color(0xFF8A8A8F),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    view.performClickHaptic()
                                    hideSessionPausedOverlay()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2F80ED)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("RESUME", color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    view.performClickHaptic()
                                    finish()
                                },
                                border = BorderStroke(1.dp, Color(0xFFEB5757)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("STOP SESSION", color = Color(0xFFEB5757))
                            }
                        }
                    }
                }
            }
        }
        overlayLayout.addView(sessionPausedComposeView)

        // Anchor view for the keybar popup at the bottom of the screen
        keybarAnchor = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                gravity = Gravity.BOTTOM
            }
        }
        overlayLayout.addView(keybarAnchor)

        // Track IME visibility and height via GlobalLayoutListener (highly reliable)
        overlayLayout.viewTreeObserver.addOnGlobalLayoutListener {
            // Boot overlay + first NativeActivity layout passes report a tiny
            // visible frame. That looks like a huge "keyboard" and used to pop
            // the extra-keys bar in the middle of the loading screen.
            if (showLoadingOverlay) {
                if (isImeVisible) isImeVisible = false
                hideKeyBarPopup()
                return@addOnGlobalLayoutListener
            }
            val rect = android.graphics.Rect()
            overlayLayout.getWindowVisibleDisplayFrame(rect)
            val screenHeight = overlayLayout.rootView.height
            if (screenHeight <= 0 || overlayLayout.height < screenHeight / 2) {
                return@addOnGlobalLayoutListener
            }
            val keypadHeight = screenHeight - rect.bottom
            val isKeyboardVisibleNow = keypadHeight > screenHeight * 0.15
            val currentHeight = if (isKeyboardVisibleNow) keypadHeight else 0
            val wasVisible = isImeVisible
            isImeVisible = isKeyboardVisibleNow
            updateKeybarVisibility(currentHeight)
            if (wasVisible && !isKeyboardVisibleNow) {
                resetPillAutoHideTimer()
            }
        }

        // Boot animation as PopupWindow (EGL surface covers DecorView children).
        // Also keep a hidden ComposeView for lifecycle ownership parity with pill.
        bootingLabel = resolveBootingLabel()
        loadingComposeView = ComposeView(this).apply {
            visibility = View.GONE
            setContent { /* loading lives in PopupWindow */ }
        }
        overlayLayout.addView(
            loadingComposeView,
            FrameLayout.LayoutParams(1, 1)
        )

        // Cover the first native frames immediately if the token is ready.
        showLoadingPopup()
        // Show as soon as the window token exists (onCreate is often too early)
        window.decorView.post {
            if (!isFinishing && !isDestroyed && showLoadingOverlay) {
                showLoadingPopup()
            }
        }

        // Wait for guest xfce4-panel (marker under active_rootfs/tmp or rootfs/tmp)
        Thread {
            val markers = listOf(
                java.io.File(filesDir, "containers/active_rootfs/tmp/pocketlinux-desktop-ready"),
                java.io.File(filesDir, "rootfs/tmp/pocketlinux-desktop-ready")
            )
            val deadline = System.currentTimeMillis() + 90_000L
            while (System.currentTimeMillis() < deadline) {
                if (markers.any { it.isFile && it.length() > 0L }) break
                try {
                    Thread.sleep(350)
                } catch (_: InterruptedException) {
                    break
                }
            }
            // Panel up (or timeout) — wait 3s so wallpaper/panel can settle
            try {
                Thread.sleep(3_000L)
            } catch (_: InterruptedException) {
            }
            runOnUiThread {
                hideLoadingPopup()
                // Reveal edge pill after boot animation (same timing as X11 hideLoadingOverlay)
                if (!isDeXMode() && !isFinishing && !isDestroyed) {
                    if (!isPillPopupInitialized) {
                        isPillPopupInitialized = true
                    }
                    showPillPopup()
                }
            }
        }.apply {
            isDaemon = true
            name = "wayland-desktop-ready"
            start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupFullscreen()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // PopupWindow keeps absolute coords across orientation; re-snap edge pill.
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                repositionPillForCurrentScreen()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Ensure boot animation is on top once the window is focused
            if (showLoadingOverlay && loadingPopup?.isShowing != true) {
                showLoadingPopup()
            }
            if (isDeXMode()) {
                if (!shownDexToast) {
                    shownDexToast = true
                    Toast.makeText(this, "press esc twice to go to home tab", Toast.LENGTH_LONG).show()
                }
                return
            }
            // Don't show pill while boot animation covers the screen
            if (showLoadingOverlay) return
            if (!isPillPopupInitialized) {
                isPillPopupInitialized = true
                Handler(Looper.getMainLooper()).post {
                    showPillPopup()
                }
            } else {
                showPillPopup()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        setupFullscreen()
        if (isDeXMode()) {
            if (!shownDexToast) {
                shownDexToast = true
                Toast.makeText(this, "press esc twice to go to home tab", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (showLoadingOverlay) {
            hideKeyBarPopup()
        } else {
            if (isPillPopupInitialized) {
                showPillPopup()
            }
            updateKeybarVisibility()
        }
        pillWatchdogHandler.removeCallbacks(pillWatchdogRunnable)
        pillWatchdogHandler.postDelayed(pillWatchdogRunnable, 3000)
    }

    override fun onPause() {
        pillWatchdogHandler.removeCallbacks(pillWatchdogRunnable)
        hidePillPopup()
        hideKeyBarPopup()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        hideLoadingPopup()
        try {
            unregisterReceiver(terminateReceiver)
        } catch (_: Exception) {}
        _viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBackPressed() {
        android.util.Log.d("WaylandActivity", "onBackPressed called (ignored)")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        savedStateRegistryController.performSave(outState)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Window Management
    // ─────────────────────────────────────────────────────────────────────────


    // ── PIP Support (debug/experimental Wayland path) ───────────────────────
    // Matches MainActivity: preference + permission + feature checks, never throws.

    override fun onUserLeaveHint() {
        val pipEnabled = try {
            sharedPrefs.getBoolean("PIP", true)
        } catch (_: Exception) {
            true
        }
        if (!pipEnabled) return
        safeEnterPictureInPicture(logTag = "WaylandActivity")
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (!isInPip) {
            try {
                setupFullscreen()
            } catch (e: Exception) {
                android.util.Log.w("WaylandActivity", "setupFullscreen after PiP: ${e.message}")
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        android.util.Log.d("WaylandActivity", "dispatchKeyEvent: keyCode=$keyCode, action=${event.action}, scanCode=${event.scanCode}")

        // Pass volume keys through
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return super.dispatchKeyEvent(event)
        }

        // Handle BACK key via our state machine (do NOT send to native compositor)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            android.util.Log.d("WaylandActivity", "dispatchKeyEvent: KEYCODE_BACK, action=${event.action}")
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            @Suppress("DEPRECATION")
            onBackPressed()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true  // Consume ACTION_DOWN silently
        }

        // Double-ESC exit
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_DOWN) {
            if (doubleEscPressedOnce) {
                doubleEscPressedOnce = false
                if (isDeXMode()) {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("EXTRA_GO_HOME", true)
                    }
                    startActivity(intent)
                } else {
                    // Same task as MainActivity — back shows home tab without a second window.
                    moveTaskToBack(true)
                }
                return true
            }
            doubleEscPressedOnce = true
            val msg = if (isDeXMode()) "press esc twice to go to home tab" else "Press ESC again to go back"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ doubleEscPressedOnce = false }, 2000)
        }

        // Merge sticky modifier states into the event
        val meta = getMetaState()
        if (meta != 0 && (event.metaState and meta) != meta) {
            val modifiedEvent = KeyEvent(
                event.downTime,
                event.eventTime,
                event.action,
                event.keyCode,
                event.repeatCount,
                event.metaState or meta,
                event.deviceId,
                event.scanCode,
                event.flags,
                event.source
            )
            return super.dispatchKeyEvent(modifiedEvent)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isSessionPaused) {
            return sessionPausedComposeView.dispatchTouchEvent(event)
        }

        val rawX = event.rawX
        val rawY = event.rawY

        // Check if touch falls within pillPopup bounds
        pillPopup?.let { popup ->
            if (popup.isShowing) {
                val density = resources.displayMetrics.density
                val screenWidth = resources.displayMetrics.widthPixels
                val w = popup.width
                val h = popup.height
                val touchBoxWidthPx = (80 * density).toInt()
                
                val shiftPx = (8 * density).toInt()
                val actualPopupX = if (pillX == 0) {
                    if (isPillHidden) -shiftPx else 0
                } else {
                    if (isPillHidden) screenWidth - w + shiftPx else screenWidth - w
                }
                val y = pillY

                if (isDraggingPill) {
                    if (rawX >= pillX && rawX <= pillX + touchBoxWidthPx && rawY >= pillY && rawY <= pillY + h) {
                        return super.dispatchTouchEvent(event)
                    }
                } else if (menuExpanded) {
                    if (rawX >= actualPopupX && rawX <= actualPopupX + w && rawY >= y && rawY <= y + h) {
                        return super.dispatchTouchEvent(event)
                    }
                } else {
                    val touchBoxLeft = if (pillX == 0) {
                        actualPopupX
                    } else {
                        actualPopupX + w - touchBoxWidthPx
                    }
                    val touchBoxRight = touchBoxLeft + touchBoxWidthPx
                    if (rawX >= touchBoxLeft && rawX <= touchBoxRight && rawY >= y && rawY <= y + h) {
                        return super.dispatchTouchEvent(event)
                    }
                }
            }
        }

        // Check if touch falls within keybarPopup bounds
        keybarPopup?.let { popup ->
            if (popup.isShowing) {
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                val w = screenWidth
                val h = popup.height
                val lp = keybarAnchor.layoutParams as FrameLayout.LayoutParams
                val keyboardHeight = lp.bottomMargin
                val y = screenHeight - keyboardHeight - h
                if (rawX >= 0 && rawX <= screenWidth && rawY >= y && rawY <= y + h) {
                    return super.dispatchTouchEvent(event)
                }
            }
        }

        // Only convert if touchMode is "1" (Trackpad Mode)
        val touchMode = try {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getString("touchMode", "1") ?: "1"
        } catch (e: Exception) {
            "1"
        }

        if (touchMode == "1") {
            val action = event.actionMasked
            val x = event.x
            val y = event.y

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()

            // Initialize mouse position in the center on first touch
            if (mouseX == 0f && mouseY == 0f) {
                mouseX = width / 2f
                mouseY = height / 2f
            }

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = x
                    lastTouchY = y
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY

                    // Read pointer speed factor (default 100 = 1.0x)
                    val speedFactor = try {
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getInt("capturedPointerSpeedFactor", 100) / 100f
                    } catch (e: Exception) {
                        1.0f
                    }

                    mouseX += dx * speedFactor
                    mouseY += dy * speedFactor

                    // Clamp to screen bounds
                    mouseX = mouseX.coerceIn(0f, width)
                    mouseY = mouseY.coerceIn(0f, height)

                    lastTouchX = x
                    lastTouchY = y
                }
            }

            // Create a simulated mouse event
            val mouseAction = when (action) {
                MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_HOVER_ENTER
                MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_HOVER_MOVE
                MotionEvent.ACTION_UP -> MotionEvent.ACTION_HOVER_EXIT
                else -> action
            }

            val mouseEvent = obtainMouseEvent(
                event.downTime,
                event.eventTime,
                mouseAction,
                mouseX,
                mouseY,
                0
            )

            // If it's a tap (ACTION_UP without much drag), we simulate a left click
            if (action == MotionEvent.ACTION_UP) {
                val dragDistance = Math.hypot((x - lastTouchX).toDouble(), (y - lastTouchY).toDouble())
                val duration = event.eventTime - event.downTime
                if (dragDistance < 20 && duration < 300) {
                    // Send Mouse Down
                    val mouseDown = obtainMouseEvent(
                        event.downTime,
                        event.eventTime,
                        MotionEvent.ACTION_DOWN,
                        mouseX,
                        mouseY,
                        MotionEvent.BUTTON_PRIMARY
                    )
                    super.dispatchTouchEvent(mouseDown)
                    mouseDown.recycle()

                    // Send Mouse Up
                    val mouseUp = obtainMouseEvent(
                        event.downTime,
                        event.eventTime,
                        MotionEvent.ACTION_UP,
                        mouseX,
                        mouseY,
                        0
                    )
                    super.dispatchTouchEvent(mouseUp)
                    mouseUp.recycle()
                }
            }

            val handled = super.dispatchTouchEvent(mouseEvent)
            mouseEvent.recycle()
            return handled
        }

        return super.dispatchTouchEvent(event)
    }

    /**
     * Check if the overlay Compose views should consume this touch event.
     * This prevents touch events aimed at the key bar or FAB from being
     * interpreted as mouse movement.
     */
}
