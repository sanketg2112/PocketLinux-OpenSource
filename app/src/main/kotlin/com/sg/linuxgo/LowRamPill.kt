package com.sg.linuxgo

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.sg.linuxgo.ui.utils.performLightHaptic
import com.sg.linuxgo.ui.utils.performWarningShakeHaptic
import kotlin.math.roundToInt

/** Device free RAM at or below this → yellow warning pill. */
const val LOW_RAM_WARN_MB = 500

/** Device free RAM at or below this → red critical pill. */
const val LOW_RAM_CRITICAL_MB = 250

/**
 * Experimental pref: force the low-RAM pill without stressing the device.
 * Values: [SIMULATE_LOW_RAM_OFF], [SIMULATE_LOW_RAM_WARN], [SIMULATE_LOW_RAM_CRITICAL].
 */
const val PREF_SIMULATE_LOW_RAM = "debug_simulate_low_ram"
const val SIMULATE_LOW_RAM_OFF = "off"
const val SIMULATE_LOW_RAM_WARN = "warn"
const val SIMULATE_LOW_RAM_CRITICAL = "critical"

enum class LowRamPillPhase {
    None,
    /** ≤500 MB free — dismissible chip. */
    Warning,
    /** ≤250 MB free — stronger chip, always visible. */
    Critical,
}

fun lowRamPhaseFor(availRamMb: Int): LowRamPillPhase {
    if (availRamMb < 0) return LowRamPillPhase.None
    return when {
        availRamMb <= LOW_RAM_CRITICAL_MB -> LowRamPillPhase.Critical
        availRamMb <= LOW_RAM_WARN_MB -> LowRamPillPhase.Warning
        else -> LowRamPillPhase.None
    }
}

/** Map low-RAM severity onto the session-limit colour ladder (shared handle look). */
fun lowRamAsSessionPhase(phase: LowRamPillPhase): SessionLimitPillPhase {
    return when (phase) {
        LowRamPillPhase.None -> SessionLimitPillPhase.None
        LowRamPillPhase.Warning -> SessionLimitPillPhase.Warning
        LowRamPillPhase.Critical -> SessionLimitPillPhase.Critical
    }
}

/**
 * Worst of session-limit and low-RAM alerts (session time limit removed — low-RAM only).
 * Kept for tests / callers that still pass a session phase.
 */
fun effectivePillAlertPhase(
    session: SessionLimitPillPhase,
    lowRam: LowRamPillPhase,
): SessionLimitPillPhase {
    val ram = lowRamAsSessionPhase(lowRam)
    val rank = { p: SessionLimitPillPhase ->
        when (p) {
            SessionLimitPillPhase.None -> 0
            SessionLimitPillPhase.Warning -> 1
            SessionLimitPillPhase.Critical -> 2
        }
    }
    return if (rank(ram) > rank(session)) ram else session
}

/** Both warning and critical chips are dismissible; handle keeps alert colour after dismiss. */
fun lowRamShowsChip(phase: LowRamPillPhase, dismissedWarning: Boolean): Boolean {
    return when (phase) {
        LowRamPillPhase.None -> false
        LowRamPillPhase.Warning, LowRamPillPhase.Critical -> !dismissedWarning
    }
}

/**
 * True when any alert chip should pin the pill open (no tuck).
 */
fun pillAlertChipPinned(
    lowRamPhase: LowRamPillPhase,
    lowRamDismissed: Boolean,
): Boolean {
    return lowRamShowsChip(lowRamPhase, lowRamDismissed)
}

/** Handle colour from low-RAM phase only (session time limit removed). */
fun effectivePillAlertPhase(lowRam: LowRamPillPhase): SessionLimitPillPhase {
    return lowRamAsSessionPhase(lowRam)
}

/**
 * Popup width in dp when a low-RAM chip is showing (session chip uses [sessionLimitPillWidthDp]).
 * Critical is a bit wider for the free-MB + risk subtitle.
 */
fun lowRamPillWidthDp(phase: LowRamPillPhase): Int {
    return if (phase == LowRamPillPhase.Critical) 188 else 172
}

/** Expanded menu: handle + keyboard + home + free-RAM chip. */
fun expandedPillMenuWidthDp(includeRamChip: Boolean): Int {
    return if (includeRamChip) 292 else 160
}

/**
 * Parse MemAvailable (kB) from a `/proc/meminfo` dump.
 * This is what system Settings usually treats as free/available RAM.
 */
fun parseMemAvailableKb(meminfo: String): Long? {
    meminfo.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (!line.startsWith("MemAvailable:", ignoreCase = true)) return@forEach
        val kb = line.substringAfter(':').trim().takeWhile { it.isDigit() }.toLongOrNull()
        if (kb != null) return kb
    }
    return null
}

/** [parseMemAvailableKb] converted to MB, or -1 if missing. */
fun parseMemAvailableMb(meminfo: String): Int {
    val kb = parseMemAvailableKb(meminfo) ?: return -1
    return (kb / 1024L).toInt().coerceAtLeast(0)
}

fun readMemAvailableMbFromProc(): Int {
    return try {
        parseMemAvailableMb(java.io.File("/proc/meminfo").readText())
    } catch (_: Exception) {
        -1
    }
}

fun readActivityManagerAvailMb(context: Context): Int {
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        (info.availMem / (1024L * 1024L)).toInt().coerceAtLeast(0)
    } catch (_: Exception) {
        -1
    }
}

/**
 * Pick the more optimistic free-RAM reading so OEM ActivityManager under-reports
 * (common on high-RAM Samsung / OneUI) do not trip a false low-RAM warning.
 */
fun combineFreeRamMb(procMb: Int, activityMb: Int): Int {
    val candidates = listOf(procMb, activityMb).filter { it >= 0 }
    return candidates.maxOrNull() ?: -1
}

/** Device available RAM in MB ([/proc/meminfo] MemAvailable, fallback ActivityManager). */
fun readDeviceAvailRamMb(context: Context): Int {
    return combineFreeRamMb(readMemAvailableMbFromProc(), readActivityManagerAvailMb(context))
}

/** Non-blocking copy for the GUI loading overlay. Null when RAM is fine. */
fun lowRamLoadingHint(phase: LowRamPillPhase, availMb: Int): String? {
    if (phase == LowRamPillPhase.None || availMb < 0) return null
    val free = formatAvailRamShort(availMb)
    return when (phase) {
        LowRamPillPhase.Critical ->
            "Very little free memory ($free). Close extra apps if the desktop fails to start."
        LowRamPillPhase.Warning ->
            "Free memory is $free. Close extra apps if the desktop is slow."
        LowRamPillPhase.None -> null
    }
}

/**
 * Available RAM used for the low-RAM pill, honouring experimental simulate mode.
 * Simulate injects a fake free-MB value so high-RAM phones can verify UI without
 * filling memory.
 */
fun readAvailRamMbForPill(context: Context): Int {
    val real = readDeviceAvailRamMb(context)
    if (!FeatureGates.lowRamSimulationAllowed()) return real
    val mode = try {
        // Same store as Experimental settings (PreferenceManager defaults).
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString(PREF_SIMULATE_LOW_RAM, SIMULATE_LOW_RAM_OFF)
            ?: SIMULATE_LOW_RAM_OFF
    } catch (_: Exception) {
        SIMULATE_LOW_RAM_OFF
    }
    return when (mode) {
        SIMULATE_LOW_RAM_WARN -> (LOW_RAM_WARN_MB - 50).coerceAtLeast(0) // 450 MB → Warning
        SIMULATE_LOW_RAM_CRITICAL -> (LOW_RAM_CRITICAL_MB - 50).coerceAtLeast(0) // 200 MB → Critical
        else -> real
    }
}

fun formatAvailRamShort(availMb: Int): String {
    return if (availMb >= 1024) {
        val gb = availMb / 1024.0
        String.format(java.util.Locale.US, "%.1f GB", gb)
    } else {
        "$availMb MB"
    }
}

/**
 * Edge-pill free-RAM chip.
 *
 * - [phase] None → neutral “RAM / free” (expanded menu).
 * - Warning / Critical → alert colours; free MB always shown; optional dismiss.
 */
@Composable
fun GuiFreeRamChip(
    phase: LowRamPillPhase,
    availRamMb: Int,
    shakeNonce: Int,
    playShake: Boolean,
    showDismiss: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(shakeNonce, playShake) {
        if (!playShake || shakeNonce <= 0) return@LaunchedEffect
        view.performWarningShakeHaptic()
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

    val isCritical = phase == LowRamPillPhase.Critical
    val isAlert = phase != LowRamPillPhase.None
    val chipBg = when (phase) {
        LowRamPillPhase.Critical -> Color(0xFF3A1212).copy(alpha = 0.95f)
        LowRamPillPhase.Warning -> Color(0xFF3A3010).copy(alpha = 0.95f)
        LowRamPillPhase.None -> Color(0xFF222222).copy(alpha = 0.95f)
    }
    val accent = when (phase) {
        LowRamPillPhase.None -> com.sg.linuxgo.ui.theme.Cyan.copy(alpha = 0.9f)
        else -> sessionLimitAccentColor(lowRamAsSessionPhase(phase))
    }
    val title = if (isAlert) "Low RAM" else "RAM"
    val subtitle = when {
        isCritical -> "${formatAvailRamShort(availRamMb)} free · close apps"
        phase == LowRamPillPhase.Warning -> "${formatAvailRamShort(availRamMb)} free · risk"
        else -> "${formatAvailRamShort(availRamMb)} free"
    }

    Row(
        modifier = modifier
            .offset { IntOffset(shakeX.value.roundToInt(), 0) }
            .height(44.dp)
            .widthIn(min = 100.dp)
            .shadow(6.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(chipBg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight(0.55f)
                .clip(RoundedCornerShape(1.5.dp))
                .background(accent),
        )
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        if (showDismiss && isAlert) {
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

/** Collapsed low-RAM alert chip (dismissible; free MB always visible). */
@Composable
fun LowRamWarningChip(
    phase: LowRamPillPhase,
    availRamMb: Int,
    shakeNonce: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (phase == LowRamPillPhase.None) return
    GuiFreeRamChip(
        phase = phase,
        availRamMb = availRamMb,
        shakeNonce = shakeNonce,
        playShake = true,
        showDismiss = true,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}
