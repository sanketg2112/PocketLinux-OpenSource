package com.sg.linuxgo.ui.screens

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sg.linuxgo.R
import com.sg.linuxgo.ui.components.GuiFirstRunCoachOverlay
import com.sg.linuxgo.ui.components.GuiFirstRunCoachPrefs
import com.sg.linuxgo.ui.components.SpecialKeysBar
import com.sg.linuxgo.ui.theme.Black
import com.sg.linuxgo.ui.theme.Cyan
import com.sg.linuxgo.ui.theme.CyanDark
import com.sg.linuxgo.ui.theme.CyanLight
import com.sg.linuxgo.ui.theme.GuiKeyBarBg
import com.sg.linuxgo.ui.theme.GuiLoadingBg
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.theme.TextTertiary
import com.sg.linuxgo.ui.utils.performLightHaptic
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val FAB_PREFS = "gui_fab_pos_v3"

private fun saveButtonPosition(context: Context, xRatio: Float, yRatio: Float) {
    context.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat("xr", xRatio)
        .putFloat("yr", yRatio)
        .apply()
}

private fun loadButtonPosition(context: Context): Pair<Float, Float> {
    val prefs = context.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)
    return prefs.getFloat("xr", -1f) to prefs.getFloat("yr", -1f)
}

@Composable
fun GuiScreen(
    modifier: Modifier = Modifier,
    showLoading: Boolean = false,
    showKeyBar: Boolean = false,
    keyBarBottomPadding: Int = 0,
    showHomeButton: Boolean = false,
    loadingStatusText: String = "Starting desktop",
    loadingRamHint: String? = null,
    onKeyboardToggle: () -> Unit = {},
    onGoHome: () -> Unit = {},
    onKeyPressed: (keyName: String) -> Unit = {},
    lorieViewFactory: ((android.content.Context) -> android.view.View)? = null,
    imeAnchorFactory: ((android.content.Context) -> android.view.View)? = null,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    shiftActive: Boolean = false,
    reseed: Boolean = false,
    showFirstRunCoach: Boolean = false,
    onDismissFirstRunCoach: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (reseed) Modifier.imePadding() else Modifier)
            .background(Black)
    ) {
        // LorieView Slot using AndroidView
        if (lorieViewFactory != null) {
            AndroidView(
                factory = lorieViewFactory,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (showKeyBar && reseed) Modifier.padding(bottom = 48.dp) else Modifier)
            )
        }

        if (imeAnchorFactory != null) {
            AndroidView(
                factory = imeAnchorFactory,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(1.dp)
                    .alpha(0f)
            )
        }

        // Draggable floating menu button (hidden-mode strip on right edge)
        if (showHomeButton) {
            DraggableFabMenu(
                modifier = Modifier.fillMaxSize(),
                onKeyboardToggle = onKeyboardToggle,
                onGoHome = onGoHome
            )
        }

        // SpecialKeysBar at bottom with GuiKeyBarBg
        // Uses imePadding() to sit just above the soft keyboard
        if (showKeyBar) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
            ) {
                SpecialKeysBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(GuiKeyBarBg),
                    onKeyPressed = onKeyPressed,
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    shiftActive = shiftActive
                )
            }
        }

        // Loading Overlay — stays until XFCE/panel is ready (see GuiSessionManager)
        if (showLoading) {
            FancyGuiLoadingOverlay(statusText = loadingStatusText, hintText = loadingRamHint)
        }

        // First-run coach after loading (do not cover boot animation).
        if (showFirstRunCoach && !showLoading) {
            GuiFirstRunCoachOverlay(
                onDismiss = {
                    GuiFirstRunCoachPrefs.markShown(context)
                    onDismissFirstRunCoach()
                }
            )
        }
    }
}

/**
 * Full-screen branded loading animation shown while X11 + desktop (panel) start.
 * Dual rotating arcs, orbiting dots, and a soft pulsing core.
 */
@Composable
fun FancyGuiLoadingOverlay(
    statusText: String,
    modifier: Modifier = Modifier,
    hintText: String? = null,
) {
    val infinite = rememberInfiniteTransition(label = "guiLoad")
    val rotationOuter by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotOuter"
    )
    val rotationInner by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotInner"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val orbit by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit"
    )
    val glowAlpha by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1A2A32),
                        GuiLoadingBg,
                        Color(0xFF0A0A0C)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(pulse),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 5.dp.toPx()
                    val pad = 10.dp.toPx()
                    val diameter = size.minDimension - pad * 2
                    val topLeft = Offset(pad, pad)
                    val arcSize = Size(diameter, diameter)
                    val cx = size.width / 2f
                    val cy = size.height / 2f

                    // Soft glow disc
                    drawCircle(
                        color = Cyan.copy(alpha = glowAlpha * 0.35f),
                        radius = diameter * 0.28f * pulse,
                        center = Offset(cx, cy)
                    )

                    // Outer arc (clockwise)
                    rotate(rotationOuter, Offset(cx, cy)) {
                        drawArc(
                            color = Cyan,
                            startAngle = -20f,
                            sweepAngle = 110f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = CyanDark.copy(alpha = 0.55f),
                            startAngle = 140f,
                            sweepAngle = 70f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke * 0.7f, cap = StrokeCap.Round)
                        )
                    }

                    // Inner arc (counter-clockwise)
                    val innerPad = pad + 14.dp.toPx()
                    val innerD = size.minDimension - innerPad * 2
                    rotate(rotationInner, Offset(cx, cy)) {
                        drawArc(
                            color = CyanLight,
                            startAngle = 40f,
                            sweepAngle = 130f,
                            useCenter = false,
                            topLeft = Offset(innerPad, innerPad),
                            size = Size(innerD, innerD),
                            style = Stroke(width = stroke * 0.75f, cap = StrokeCap.Round)
                        )
                    }

                    // Orbiting dots
                    val orbitR = diameter * 0.48f
                    for (i in 0 until 3) {
                        val ang = Math.toRadians((orbit + i * 120.0))
                        val dx = cos(ang).toFloat() * orbitR
                        val dy = sin(ang).toFloat() * orbitR
                        drawCircle(
                            color = Cyan.copy(alpha = 0.9f - i * 0.15f),
                            radius = 4.dp.toPx() + i * 0.6f,
                            center = Offset(cx + dx, cy + dy)
                        )
                    }

                    // Core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(CyanLight, Cyan, CyanDark),
                            center = Offset(cx, cy),
                            radius = 14.dp.toPx()
                        ),
                        radius = 11.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = statusText.ifBlank { "Booting Desktop" },
                color = Cyan,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Default,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 28.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Please wait while the desktop finishes starting",
                color = TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Default,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            if (!hintText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = hintText,
                    color = Color(0xFFE8C98A),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 36.dp)
                )
            }
        }
    }
}

@Composable
private fun DraggableFabMenu(
    modifier: Modifier = Modifier,
    onKeyboardToggle: () -> Unit,
    onGoHome: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current

    var menuExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val screenW = maxWidth
        val screenH = maxHeight
        val touchSlop = with(density) { 8.dp.toPx() }

        // Load or compute relative position (0..1 ratios)
        val saved = remember { loadButtonPosition(context) }
        var xRatio by remember { mutableFloatStateOf(if (saved.first >= 0f) saved.first else 1f) }
        var yRatio by remember { mutableFloatStateOf(if (saved.second >= 0f) saved.second else 0.15f) }

        val screenWPx = with(density) { screenW.toPx() }
        val screenHPx = with(density) { screenH.toPx() }
        val stripW = 16.dp
        val stripH = 48.dp
        val stripWPx = with(density) { stripW.toPx() }
        val stripHPx = with(density) { stripH.toPx() }

        var offsetX by remember {
            mutableFloatStateOf(
                (xRatio * screenWPx - stripWPx).coerceIn(0f, (screenWPx - stripWPx).coerceAtLeast(0f))
            )
        }
        var offsetY by remember {
            mutableFloatStateOf(
                (yRatio * screenHPx - stripHPx / 2f).coerceIn(0f, (screenHPx - stripHPx).coerceAtLeast(0f))
            )
        }

        fun snapToEdge() {
            // Directly move pixel position to nearest edge
            offsetX = if (offsetX + stripWPx / 2f < screenWPx / 2f) {
                0f  // snap to left edge
            } else {
                screenWPx - stripWPx  // snap to right edge
            }
            // Save as ratio for orientation persistence
            xRatio = if (offsetX < screenWPx / 2f) 0f else 1f
            yRatio = (offsetY + stripHPx / 2f) / screenHPx.coerceAtLeast(1f)
            saveButtonPosition(context, xRatio, yRatio)
        }

        // Absolute pixel offsets go stale on orientation / multi-window resize;
        // re-apply saved ratios so the strip stays on the edge.
        LaunchedEffect(screenWPx, screenHPx) {
            offsetX = if (xRatio < 0.5f) {
                0f
            } else {
                (screenWPx - stripWPx).coerceAtLeast(0f)
            }
            offsetY = (yRatio * screenHPx - stripHPx / 2f)
                .coerceIn(0f, (screenHPx - stripHPx).coerceAtLeast(0f))
        }

        val pillColor = Color(0xFF333333).copy(alpha = 0.85f)

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(stripW)
                .height(stripH)
                .shadow(6.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(pillColor)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downX = down.position.x
                        val downY = down.position.y
                        var lastX = downX
                        var lastY = downY
                        var dragging = false
                        down.consume()

                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) {
                                ch.consume()
                                break
                            }
                            val totalDx = ch.position.x - downX
                            val totalDy = ch.position.y - downY
                            if (!dragging && totalDx * totalDx + totalDy * totalDy > touchSlop * touchSlop) {
                                dragging = true
                                menuExpanded = false
                            }
                            if (dragging) {
                                val dx = ch.position.x - lastX
                                val dy = ch.position.y - lastY
                                lastX = ch.position.x
                                lastY = ch.position.y
                                offsetX = (offsetX + dx).coerceIn(0f, screenWPx - stripWPx)
                                offsetY = (offsetY + dy).coerceIn(0f, screenHPx - stripHPx)
                                ch.consume()
                            }
                        }

                        if (dragging) snapToEdge() else menuExpanded = !menuExpanded
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Cyan.copy(alpha = 0.7f))
            )
        }

        // Popup menu
        if (menuExpanded) {
            // Backdrop to catch outside taps
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { menuExpanded = false }
            )

            Column(
                modifier = Modifier
                    .offset {
                        val menuX = if (xRatio > 0.5f) {
                            (offsetX - with(density) { 52.dp.toPx() }).roundToInt()
                        } else {
                            (offsetX + stripWPx + with(density) { 4.dp.toPx() }).roundToInt()
                        }
                        IntOffset(
                            menuX,
                            (offsetY - with(density) { 4.dp.toPx() }).roundToInt()
                        )
                    }
                    .width(52.dp)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF222222)),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable {
                            view.performLightHaptic()
                            menuExpanded = false
                            onKeyboardToggle()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_keyboard),
                        contentDescription = "Keyboard",
                        tint = Cyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF444444))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable {
                            view.performLightHaptic()
                            menuExpanded = false
                            onGoHome()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_home),
                        contentDescription = "Go Home",
                        tint = Cyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
