package com.sg.linuxgo

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sg.linuxgo.ui.components.SpecialKeysBar
import com.sg.linuxgo.ui.theme.Cyan
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fullscreen / cutout / keep-screen-on for Wayland surface. */

internal fun WaylandActivity.setupFullscreen() {
    val fullscreen = sharedPrefs.getBoolean("fullscreen", true)
    val keepScreenOn = sharedPrefs.getBoolean("keepScreenOn", true)
    val hideCutout = sharedPrefs.getBoolean("hideCutout", false)

    // Edge-to-edge for all users (API 35+). NativeActivity cannot use
    // ComponentActivity.enableEdgeToEdge(); mirror that behavior with WindowCompat.
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
    val systemBars =
        androidx.core.view.WindowInsetsCompat.Type.statusBars() or
            androidx.core.view.WindowInsetsCompat.Type.navigationBars()
    if (fullscreen) {
        controller.hide(systemBars)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        controller.show(systemBars)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val attrs = window.attributes
        // Avoid deprecated SHORT_EDGES / DEFAULT. NEVER = avoid notch; ALWAYS = into cutout.
        attrs.layoutInDisplayCutoutMode = when {
            hideCutout -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else -> attrs.layoutInDisplayCutoutMode
        }
        window.attributes = attrs
    }
}
