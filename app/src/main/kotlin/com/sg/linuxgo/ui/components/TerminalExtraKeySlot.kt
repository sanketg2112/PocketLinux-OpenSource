package com.sg.linuxgo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.utils.performClickHaptic
import com.sg.linuxgo.ui.utils.performKeyHaptic

import kotlinx.coroutines.withTimeoutOrNull

/** Shared slot size so top bar and expanded panel feel like one grid. */
internal val KeySlotWidth = 48.dp
/** Bar/slot height at 0.8× the previous 40.dp — font sizes in [KeyGlyph] are unchanged. */
internal val KeySlotHeight = 32.dp
internal val KeyRowPaddingH = 4.dp
/** Minimal vertical chrome so the bar sits tight under the terminal. */
internal val KeyRowPaddingV = 0.dp
/** Vertical gap between panel rows (each row holds [KEYS_PER_PANEL_ROW] keys). */
internal val PanelRowGap = 4.dp

@Composable
internal fun ExtraKeySlot(
    key: ExtraKey,
    isActive: Boolean,
    isDropTarget: Boolean,
    isDragging: Boolean,
    editMode: Boolean,
    accentColor: Color,
    textColor: Color,
    fixedWidth: Dp?,
    longPressTimeoutMs: Long,
    onTap: () -> Unit,
    onDragStart: (localInSlot: Offset, rootPos: Offset) -> Unit,
    onDrag: (rootPos: Offset, distanceDelta: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onPositioned: (LayoutCoordinates) -> Unit
) {
    val buttonColor = when {
        isDropTarget -> accentColor
        isActive -> accentColor
        key.id == ADD_KEY_ID -> accentColor
        else -> textColor
    }
    val shape = RoundedCornerShape(6.dp)
    var layoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val viewConfiguration = LocalViewConfiguration.current
    val context = LocalContext.current

    val baseModifier = Modifier
        .then(if (fixedWidth != null) Modifier.width(fixedWidth) else Modifier.fillMaxWidth())
        .height(KeySlotHeight)
        .padding(horizontal = 1.dp, vertical = KeyRowPaddingV)
        .onGloballyPositioned { coords ->
            layoutCoords = coords
            onPositioned(coords)
        }
        .clip(shape)
        .then(
            when {
                isDragging -> Modifier
                    .background(textColor.copy(alpha = 0.08f))
                    .border(1.dp, textColor.copy(alpha = 0.25f), shape)
                    .graphicsLayer { alpha = 0.35f }
                isDropTarget -> Modifier
                    .background(accentColor.copy(alpha = 0.2f))
                    .border(1.5.dp, accentColor, shape)
                key.id == ADD_KEY_ID -> Modifier.background(accentColor.copy(alpha = 0.08f))
                else -> Modifier
            }
        )
        .pointerInput(editMode, key.id, longPressTimeoutMs) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downPosition = down.position
                val coords = layoutCoords

                if (!editMode || key.id == ADD_KEY_ID) {
                    // Simple tap only when not in edit mode, or for the "+" key.
                    // Tick on press (not release) so feedback feels immediate like a keyboard.
                    // Context vibrator path — View haptics alone were silent on several OEMs.
                    context.performKeyHaptic()
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var event = down
                        while (event.pressed) {
                            val e = awaitPointerEvent()
                            val change = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                return@withTimeoutOrNull change
                            }
                            // Cancel tap if finger moves too far.
                            val dx = change.position.x - downPosition.x
                            val dy = change.position.y - downPosition.y
                            if (dx * dx + dy * dy > viewConfiguration.touchSlop * viewConfiguration.touchSlop) {
                                return@withTimeoutOrNull null
                            }
                            event = change
                        }
                        null
                    }
                    if (up != null) {
                        onTap()
                    }
                    return@awaitEachGesture
                }

                // Edit mode: short tap = press key; long-press then drag = reposition.
                // null = long-press fired; true = quick release (tap); false = moved before long-press.
                val earlyResult: Boolean? = withTimeoutOrNull(longPressTimeoutMs) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@withTimeoutOrNull false
                        if (!change.pressed) {
                            return@withTimeoutOrNull true // released → tap
                        }
                        val dx = change.position.x - downPosition.x
                        val dy = change.position.y - downPosition.y
                        if (dx * dx + dy * dy > viewConfiguration.touchSlop * viewConfiguration.touchSlop) {
                            return@withTimeoutOrNull false // moved early → ignore (e.g. scroll)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }

                when (earlyResult) {
                    true -> {
                        context.performKeyHaptic()
                        onTap()
                    }
                    false -> {
                        // Finger moved before long-press: don't treat as tap; let other gestures proceed.
                    }
                    null -> {
                        // Long-press timeout elapsed while still holding → drag mode.
                        context.performClickHaptic()
                        fun rootOf(local: Offset): Offset {
                            val origin = coords?.positionInRoot() ?: Offset.Zero
                            return Offset(origin.x + local.x, origin.y + local.y)
                        }
                        onDragStart(downPosition, rootOf(downPosition))
                        // Track absolute movement from last known position so we stay accurate
                        // even if this slot's layout shifts under the finger.
                        var lastRoot = rootOf(downPosition)
                        val dragResult = drag(down.id) { change ->
                            val delta = change.positionChange()
                            change.consume()
                            val dist = kotlin.math.sqrt(delta.x * delta.x + delta.y * delta.y)
                            // Prefer local+layout; fall back to accumulating deltas if layout detaches.
                            val rp = if (coords?.isAttached == true) {
                                rootOf(change.position)
                            } else {
                                lastRoot + delta
                            }
                            lastRoot = rp
                            onDrag(rp, dist)
                        }
                        if (dragResult) onDragEnd() else onDragCancel()
                    }
                }
            }
        }

    Box(
        modifier = baseModifier,
        contentAlignment = Alignment.Center
    ) {
        KeyGlyph(key = key, color = buttonColor, isAdd = key.id == ADD_KEY_ID)
    }
}

@Composable
internal fun KeyGlyph(
    key: ExtraKey,
    color: Color,
    isAdd: Boolean
) {
    val labelText = key.label
    val fontSize = when {
        key.isArrow -> 16.sp
        isAdd -> 20.sp
        labelText.length >= 4 -> 10.sp
        labelText.length >= 3 -> 11.sp
        labelText.length >= 2 -> 12.sp
        else -> 14.sp
    }
    when (key.keyName) {
        "SETTINGS_ACTION" -> Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Terminal Settings",
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        "SFTP_ACTION" -> Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = "SFTP Browser",
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        else -> Text(
            text = labelText,
            color = color,
            fontSize = fontSize,
            fontFamily = if (key.isArrow) null else FontFamily.Default,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            lineHeight = fontSize,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}
