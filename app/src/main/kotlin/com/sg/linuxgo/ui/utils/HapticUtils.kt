package com.sg.linuxgo.ui.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Standard button-press haptic (nav, primary actions).
 */
fun View?.performClickHaptic() {
    performHaptic(HapticStrength.Click)
}

/**
 * Smaller pulse for dense controls (terminal extra keys, edge pill ticks).
 * Always drives the vibrator — View.performHapticFeedback often returns true
 * without any physical feedback on several OEMs.
 */
fun View?.performLightHaptic() {
    performHaptic(HapticStrength.Light)
}

/**
 * Context-based key tick (no View required). Prefer this from pointer handlers
 * so feedback does not depend on Compose LocalView attachment.
 */
fun Context.performKeyHaptic() {
    performHapticFromContext(this, HapticStrength.Light)
}

/** Context-based standard click (long-press / mode change). */
fun Context.performClickHaptic() {
    performHapticFromContext(this, HapticStrength.Click)
}

/**
 * Strong multi-pulse “shake” for attention (e.g. onboarding skip while notifications denied).
 */
fun View?.performWarningShakeHaptic() {
    performHaptic(HapticStrength.WarningShake)
}

/**
 * Compose helper for standard click haptic.
 */
@Composable
fun rememberHapticFeedback(): () -> Unit {
    val view = LocalView.current
    return { view.performClickHaptic() }
}

/** Compose helper for extra-key / keyboard tick haptic. */
@Composable
fun rememberKeyHaptic(): () -> Unit {
    val context = LocalContext.current
    return { context.performKeyHaptic() }
}

private enum class HapticStrength {
    Click,
    Light,
    WarningShake,
}

private val mainHandler = Handler(Looper.getMainLooper())

private fun View?.performHaptic(strength: HapticStrength) {
    val view = this
    val context = view?.context
    // Run on main looper — pointerInput is usually main, but keep it safe.
    val run = {
        // 1) Direct vibrator first — reliable physical feedback for key bars.
        if (context != null) {
            performVibratorPulse(context, strength)
        }
        // 2) Also request framework touch haptic (some devices only honor this path).
        if (view != null) {
            tryViewHaptic(view, strength)
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        run()
    } else {
        mainHandler.post(run)
    }
}

private fun performHapticFromContext(context: Context, strength: HapticStrength) {
    val run = {
        performVibratorPulse(context, strength)
        // Best-effort: activity window decor if available.
        val decor = (context as? Activity)?.window?.decorView
        if (decor != null) {
            tryViewHaptic(decor, strength)
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        run()
    } else {
        mainHandler.post(run)
    }
}

private fun tryViewHaptic(view: View, strength: HapticStrength) {
    try {
        if (!view.isHapticFeedbackEnabled) {
            view.isHapticFeedbackEnabled = true
        }
        // IGNORE_GLOBAL_SETTING is needed on some OEMs where the system "touch
        // feedback" toggle is off but the user still expects in-app key ticks.
        // FLAG_IGNORE_VIEW_SETTING alone is not enough.
        @Suppress("DEPRECATION")
        val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
            if (Build.VERSION.SDK_INT >= 33) {
                // API 33+: FLAG_IGNORE_GLOBAL_SETTING = 2 (same value historically).
                0x0002
            } else {
                // Pre-33: same bit was FLAG_IGNORE_GLOBAL_SETTING.
                0x0002
            }
        val constants = when (strength) {
            HapticStrength.Light -> buildList {
                add(HapticFeedbackConstants.KEYBOARD_TAP)
                add(HapticFeedbackConstants.VIRTUAL_KEY)
                add(HapticFeedbackConstants.CLOCK_TICK)
                add(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            HapticStrength.Click -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(HapticFeedbackConstants.CONFIRM)
                }
                add(HapticFeedbackConstants.VIRTUAL_KEY)
                add(HapticFeedbackConstants.KEYBOARD_TAP)
                add(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            HapticStrength.WarningShake -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(HapticFeedbackConstants.REJECT)
                }
                add(HapticFeedbackConstants.LONG_PRESS)
                add(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
        for (constant in constants) {
            if (view.performHapticFeedback(constant, flags)) return
        }
        val decor = (view.context as? Activity)?.window?.decorView
        if (decor != null && decor !== view) {
            if (!decor.isHapticFeedbackEnabled) {
                decor.isHapticFeedbackEnabled = true
            }
            for (constant in constants) {
                if (decor.performHapticFeedback(constant, flags)) return
            }
        }
    } catch (_: Exception) {
        // ignore
    }
}

/** @return true if a vibration was requested successfully. */
private fun performVibratorPulse(context: Context, strength: HapticStrength): Boolean {
    return try {
        val vibrator = resolveVibrator(context) ?: return false
        if (!vibrator.hasVibrator()) return false

        when (strength) {
            HapticStrength.Light -> vibrateLight(vibrator)
            HapticStrength.Click -> vibrateClick(vibrator)
            HapticStrength.WarningShake -> vibrateWarningShake(vibrator)
        }
        true
    } catch (_: Exception) {
        false
    }
}

private fun vibrateClick(vibrator: Vibrator) {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            // Predefined + explicit one-shot: some devices ignore predefined.
            try {
                vibrator.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                    touchAttrs()
                )
            } catch (_: Exception) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(28, 200),
                    touchAttrs()
                )
            }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            try {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } catch (_: Exception) {
                vibrator.vibrate(VibrationEffect.createOneShot(28, 200))
            }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
            vibrator.vibrate(VibrationEffect.createOneShot(28, 200))
        }
        else -> {
            @Suppress("DEPRECATION")
            vibrator.vibrate(28)
        }
    }
}

private fun vibrateLight(vibrator: Vibrator) {
    // Use a short but clearly feelable one-shot. EFFECT_TICK is inaudible/imperceptible
    // on many phones (especially when "touch feedback" is subtle).
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            try {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(16, 160),
                    touchAttrs()
                )
            } catch (_: Exception) {
                vibrator.vibrate(VibrationEffect.createOneShot(16, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
            vibrator.vibrate(VibrationEffect.createOneShot(16, 160))
        }
        else -> {
            @Suppress("DEPRECATION")
            vibrator.vibrate(16)
        }
    }
}

/** Double-thump pattern so skip-without-notifications is hard to miss. */
private fun vibrateWarningShake(vibrator: Vibrator) {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 55, 45, 55, 45, 90),
                intArrayOf(0, 220, 0, 220, 0, 255),
                -1
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, touchAttrs())
            } else {
                vibrator.vibrate(effect)
            }
        }
        else -> {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 55, 45, 55, 45, 90), -1)
        }
    }
}

private fun touchAttrs(): VibrationAttributes {
    return VibrationAttributes.Builder()
        .setUsage(VibrationAttributes.USAGE_TOUCH)
        .build()
}

private fun resolveVibrator(context: Context): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}
