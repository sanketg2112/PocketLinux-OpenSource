package com.sg.linuxgo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.theme.Cyan
import com.sg.linuxgo.ui.theme.Red
import com.sg.linuxgo.ui.theme.Yellow
import com.sg.linuxgo.ui.utils.performLightHaptic
import com.sg.linuxgo.ui.utils.performWarningShakeHaptic
import kotlin.math.roundToInt

/** Session warning thresholds. */
const val SESSION_WARN_REMAINING_MS = 5L * 60 * 1000
const val SESSION_CRITICAL_REMAINING_MS = 1L * 60 * 1000

enum class SessionLimitPillPhase {
    /** More than 5 minutes left, or unlimited. */
    None,
    /** ≤5 min and >1 min — warning colour, dismissible countdown. */
    Warning,
    /** ≤1 min — stronger colour, non-dismissible countdown. */
    Critical,
}

fun sessionLimitPhaseFor(remainingMs: Long): SessionLimitPillPhase {
    if (remainingMs < 0L) return SessionLimitPillPhase.None
    return when {
        remainingMs <= SESSION_CRITICAL_REMAINING_MS -> SessionLimitPillPhase.Critical
        remainingMs <= SESSION_WARN_REMAINING_MS -> SessionLimitPillPhase.Warning
        else -> SessionLimitPillPhase.None
    }
}

fun formatSessionCountdown(remainingMs: Long): String {
    val totalSecs = (remainingMs.coerceAtLeast(0L) / 1000L).toInt()
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "%d:%02d".format(mins, secs)
}

/** Handle / accent colour for the edge pill at the current phase. */
fun sessionLimitPillColor(phase: SessionLimitPillPhase, default: Color): Color {
    return when (phase) {
        SessionLimitPillPhase.None -> default
        SessionLimitPillPhase.Warning -> Color(0xFF5C4A12).copy(alpha = 0.92f)
        SessionLimitPillPhase.Critical -> Color(0xFF5C1A1A).copy(alpha = 0.92f)
    }
}

/** Grip / accent strip colour for the edge pill. */
fun sessionLimitAccentColor(phase: SessionLimitPillPhase): Color {
    return when (phase) {
        SessionLimitPillPhase.None -> Cyan.copy(alpha = 0.7f)
        SessionLimitPillPhase.Warning -> Yellow
        SessionLimitPillPhase.Critical -> Red
    }
}

/**
 * Whether the expanded countdown chip should be visible on the pill.
 * Critical is always visible; Warning respects dismiss.
 */
fun sessionLimitShowsCountdown(
    phase: SessionLimitPillPhase,
    dismissedWarning: Boolean,
): Boolean {
    return when (phase) {
        SessionLimitPillPhase.None -> false
        SessionLimitPillPhase.Warning -> !dismissedWarning
        SessionLimitPillPhase.Critical -> true
    }
}

/**
 * Popup width in dp for the edge pill, including optional session countdown chip.
 */
fun sessionLimitPillWidthDp(
    menuExpanded: Boolean,
    showCountdown: Boolean,
    phase: SessionLimitPillPhase,
): Int {
    if (menuExpanded) return 160
    if (!showCountdown) return 96
    return if (phase == SessionLimitPillPhase.Critical) 188 else 156
}

/**
 * Edge-pill countdown chip: time remaining, optional dismiss.
 * Plays a short horizontal shake when [shakeNonce] changes to a positive value.
 */
@Composable
fun SessionLimitCountdownChip(
    phase: SessionLimitPillPhase,
    remainingMs: Long,
    shakeNonce: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (phase == SessionLimitPillPhase.None) return

    val view = LocalView.current
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(shakeNonce) {
        if (shakeNonce <= 0) return@LaunchedEffect
        view.performWarningShakeHaptic()
        // Short left-right shake (~500ms)
        val peaks = floatArrayOf(10f, -10f, 8f, -6f, 4f, -2f, 0f)
        for (peak in peaks) {
            shakeX.animateTo(
                peak,
                animationSpec = tween(
                    durationMillis = 45,
                    easing = if (peak >= 0f) FastOutLinearInEasing else LinearOutSlowInEasing,
                ),
            )
        }
    }

    val isCritical = phase == SessionLimitPillPhase.Critical
    val chipBg = if (isCritical) {
        Color(0xFF3A1212).copy(alpha = 0.95f)
    } else {
        Color(0xFF3A3010).copy(alpha = 0.95f)
    }
    val accent = sessionLimitAccentColor(phase)
    val timeText = formatSessionCountdown(remainingMs)

    Row(
        modifier = modifier
            .offset { IntOffset(shakeX.value.roundToInt(), 0) }
            .height(44.dp)
            .widthIn(min = 88.dp)
            .shadow(6.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(chipBg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight(0.55f)
                .clip(RoundedCornerShape(1.5.dp))
                .background(accent),
        )
        Text(
            text = timeText,
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (!isCritical) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable {
                        view.performLightHaptic()
                        onDismiss()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Thin edge handle with optional warning/critical colour and shake.
 * Used when countdown is dismissed (warning) or as the grip next to the chip.
 */
@Composable
fun SessionAwarePillHandle(
    phase: SessionLimitPillPhase,
    shakeNonce: Int,
    playShake: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val defaultPill = Color(0xFF333333).copy(alpha = 0.85f)
    val pillColor = sessionLimitPillColor(phase, defaultPill)
    val accent = sessionLimitAccentColor(phase)

    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(shakeNonce, playShake) {
        if (!playShake || shakeNonce <= 0) return@LaunchedEffect
        view.performWarningShakeHaptic()
        val peaks = floatArrayOf(8f, -8f, 6f, -4f, 2f, 0f)
        for (peak in peaks) {
            shakeX.animateTo(
                peak,
                animationSpec = tween(durationMillis = 40),
            )
        }
    }

    val base = Modifier
        .offset { IntOffset(shakeX.value.roundToInt(), 0) }
        .width(16.dp)
        .height(48.dp)
        .shadow(6.dp, RoundedCornerShape(8.dp))
        .clip(RoundedCornerShape(8.dp))
        .background(pillColor)
        .let { if (onClick != null) it.clickable { onClick() } else it }

    Box(modifier = base, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(accent),
        )
    }
}
