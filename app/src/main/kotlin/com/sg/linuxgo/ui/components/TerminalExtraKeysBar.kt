package com.sg.linuxgo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import com.sg.linuxgo.ui.theme.Cyan
import com.sg.linuxgo.ui.theme.StrokeLight
import com.sg.linuxgo.ui.theme.SurfaceDark
import com.sg.linuxgo.ui.theme.TextSecondary

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import com.sg.linuxgo.ui.utils.performClickHaptic
import com.sg.linuxgo.ui.utils.performKeyHaptic
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Special keys bar with a fixed right-side toggle button.
 *
 * Structure is always:
 * ```
 * [ top strip  ]  ← stays at the same Y when ⋮ is pressed
 * [ panel / IME space below, same height as soft keyboard ]
 * ```
 * Expanding swaps the soft-keyboard region for the extra-keys panel (same height),
 * so the top strip does not jump.
 *
 * When the expanded panel is open, long-press and drag any key to reposition.
 */
@Composable
fun TerminalExtraKeysBar(
    modifier: Modifier = Modifier,
    onKeyPressed: (keyName: String) -> Unit = {},
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    shiftActive: Boolean = false,
    isKeyboardVisible: Boolean = false,
    /**
     * App-level intent that the soft keyboard should be up (button was used).
     * Prefer this over [isKeyboardVisible] alone so a lingering IME after tab
     * switch does not make the icon open the extra-keys panel instead.
     */
    softKeyboardDesired: Boolean = false,
    showExpandedOverlay: Boolean = false,
    /** Height of the panel below the top strip — match soft-keyboard height. */
    expandedPanelHeight: Dp = 260.dp,
    onExpandedOverlayChanged: (Boolean) -> Unit = {},
    onShowKeyboard: () -> Unit = {},
    onHideKeyboard: () -> Unit = {},
    accentColor: Color = Cyan,
    textColor: Color = TextSecondary,
    backgroundColor: Color = SurfaceDark
) {
    val context = LocalContext.current
    val prefs = remember { extraKeysPrefs(context) }
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current

    val topKeys = remember { mutableStateListOf<ExtraKey>() }
    val panelKeys = remember { mutableStateListOf<ExtraKey>() }
    var layoutRevision by remember { mutableStateOf(keysRevision(prefs)) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var customLabel by remember { mutableStateOf("") }
    var customCombo by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    var keyPendingDelete by remember { mutableStateOf<ExtraKey?>(null) }

    var dragSession by remember { mutableStateOf<DragSession?>(null) }
    var dragPointerRoot by remember { mutableStateOf(Offset.Zero) }
    var hoverTargetId by remember { mutableStateOf<String?>(null) }
    var totalDragDistance by remember { mutableFloatStateOf(0f) }

    val slotBounds = remember { mutableStateMapOf<String, SlotInfo>() }
    var rootPositionInWindow by remember { mutableStateOf(Offset.Zero) }

    fun applyLayout(layout: ExtraKeysLayout) {
        topKeys.clear()
        topKeys.addAll(layout.topKeys)
        panelKeys.clear()
        panelKeys.addAll(layout.panelKeys)
    }

    // Initial load + live reload when Keyboard settings (or another session) writes prefs.
    DisposableEffect(prefs) {
        applyLayout(loadExtraKeysLayout(prefs))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "keys_revision" || key == "top_bar_keys_v2" || key == "panel_keys_v2") {
                layoutRevision = keysRevision(prefs)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(layoutRevision) {
        // Skip overwrite while the user is mid-drag; otherwise reload from disk.
        if (dragSession == null) {
            applyLayout(loadExtraKeysLayout(prefs))
        }
    }

    fun persistAll() {
        saveExtraKeysLayout(
            prefs,
            ExtraKeysLayout(
                topKeys = topKeys.toList(),
                panelKeys = panelKeys.filter { it.id != ADD_KEY_ID }
            )
        )
        layoutRevision = keysRevision(prefs)
    }

    fun resetToDefaults() {
        applyLayout(resetExtraKeysLayout(prefs))
        layoutRevision = keysRevision(prefs)
    }

    LaunchedEffect(showExpandedOverlay) {
        if (!showExpandedOverlay) {
            dragSession = null
            hoverTargetId = null
            totalDragDistance = 0f
        }
    }

    // Same height as the soft keyboard so swapping IME ↔ panel keeps the top strip put.
    val panelHeight = expandedPanelHeight.coerceAtLeast(160.dp)

    fun isKeyActive(key: ExtraKey): Boolean = when (key.keyName) {
        "CTRL" -> ctrlActive
        "ALT" -> altActive
        "SHIFT" -> shiftActive
        else -> false
    }

    fun findDropTarget(pointerRoot: Offset): SlotInfo? {
        val hits = slotBounds.values.filter { info ->
            info.keyId != ADD_KEY_ID &&
                info.keyId != dragSession?.key?.id &&
                info.rect.contains(pointerRoot)
        }
        if (hits.isNotEmpty()) return hits.minByOrNull {
            val c = it.rect.center
            (c.x - pointerRoot.x) * (c.x - pointerRoot.x) + (c.y - pointerRoot.y) * (c.y - pointerRoot.y)
        }
        // Snap to nearest slot center within a generous radius (helps between cells).
        val nearest = slotBounds.values
            .filter { it.keyId != ADD_KEY_ID && it.keyId != dragSession?.key?.id }
            .minByOrNull {
                val c = it.rect.center
                (c.x - pointerRoot.x) * (c.x - pointerRoot.x) + (c.y - pointerRoot.y) * (c.y - pointerRoot.y)
            } ?: return null
        val c = nearest.rect.center
        val distSq = (c.x - pointerRoot.x) * (c.x - pointerRoot.x) + (c.y - pointerRoot.y) * (c.y - pointerRoot.y)
        val maxDist = with(density) { 40.dp.toPx() }
        return if (distSq <= maxDist * maxDist) nearest else null
    }

    fun applyDrop(target: SlotInfo?) {
        val session = dragSession ?: return
        if (target == null) {
            dragSession = null
            hoverTargetId = null
            totalDragDistance = 0f
            return
        }
        moveKeyTo(
            topKeys = topKeys,
            panelKeys = panelKeys,
            keyId = session.key.id,
            targetZone = target.zone,
            targetIndex = target.index
        )
        persistAll()
        context.performClickHaptic()
        dragSession = null
        hoverTargetId = null
        totalDragDistance = 0f
    }

    fun registerSlot(zone: KeyZone, key: ExtraKey, index: Int, coords: LayoutCoordinates) {
        if (!coords.isAttached) return
        val topLeft = coords.positionInRoot()
        val size = coords.size
        slotBounds[key.id] = SlotInfo(
            zone = zone,
            keyId = key.id,
            index = index,
            rect = Rect(
                left = topLeft.x,
                top = topLeft.y,
                right = topLeft.x + size.width,
                bottom = topLeft.y + size.height
            )
        )
    }

    fun onKeyTap(key: ExtraKey) {
        // Haptic is fired in ExtraKeySlot on finger-down (keyboard tick).
        if (key.id == ADD_KEY_ID || key.keyName == ADD_KEY_NAME) {
            customLabel = ""
            customCombo = ""
            customError = null
            showAddDialog = true
            return
        }
        onKeyPressed(key.keyName)
    }

    val panelDisplayKeys = panelKeys.toList() + ExtraKey(
        id = ADD_KEY_ID,
        label = "+",
        keyName = ADD_KEY_NAME
    )
    // 8 keys per row (two groups of 4 side by side).
    val panelRows = panelDisplayKeys.chunked(KEYS_PER_PANEL_ROW)
    val draggingId = dragSession?.key?.id

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .onGloballyPositioned { rootPositionInWindow = it.positionInRoot() }
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Soft top-edge shadow so the bar lifts slightly from the terminal.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.28f),
                                Color.Black.copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // ── Top bar — always first in the column (fixed Y when panel opens) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KeySlotHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = KeyRowPaddingH, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    topKeys.forEachIndexed { index, key ->
                        ExtraKeySlot(
                            key = key,
                            isActive = isKeyActive(key),
                            isDropTarget = hoverTargetId == key.id,
                            isDragging = draggingId == key.id,
                            editMode = showExpandedOverlay,
                            accentColor = accentColor,
                            textColor = textColor,
                            fixedWidth = KeySlotWidth,
                            longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis,
                            onTap = {
                                onKeyTap(key)
                            },
                            onDragStart = { localInSlot, rootPos ->
                                // Haptic already fired in ExtraKeySlot on long-press enter.
                                totalDragDistance = 0f
                                dragPointerRoot = rootPos
                                dragSession = DragSession(
                                    key = key,
                                    fromZone = KeyZone.TOP,
                                    grabOffsetInSlot = localInSlot,
                                    startRoot = rootPos
                                )
                            },
                            onDrag = { rootPos, distanceDelta ->
                                dragPointerRoot = rootPos
                                totalDragDistance += distanceDelta
                                hoverTargetId = findDropTarget(rootPos)?.keyId
                            },
                            onDragEnd = {
                                val moved = totalDragDistance > viewConfiguration.touchSlop
                                val target = findDropTarget(dragPointerRoot)
                                if (!moved && key.isCustom) {
                                    keyPendingDelete = key
                                    dragSession = null
                                    hoverTargetId = null
                                    totalDragDistance = 0f
                                } else {
                                    applyDrop(target)
                                }
                            },
                            onDragCancel = {
                                dragSession = null
                                hoverTargetId = null
                                totalDragDistance = 0f
                            },
                            onPositioned = { coords ->
                                registerSlot(KeyZone.TOP, key, index, coords)
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(StrokeLight)
                )

                // ⋮ only when IME is actually up AND we asked for it this session.
                // Otherwise always the keyboard icon and always force-show on tap
                // (including after Back dismissed the IME).
                val keyboardModeActive = isKeyboardVisible && softKeyboardDesired
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .fillMaxHeight()
                        .focusProperties { canFocus = false }
                        .clickable {
                            context.performKeyHaptic()
                            dragSession = null
                            when {
                                showExpandedOverlay -> onShowKeyboard()
                                keyboardModeActive -> {
                                    onExpandedOverlayChanged(true)
                                    onHideKeyboard()
                                }
                                else -> onShowKeyboard()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (showExpandedOverlay || !keyboardModeActive) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "Show Keyboard",
                            tint = accentColor,
                            modifier = Modifier.size(26.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Show Extra Keys",
                            tint = textColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // ── Panel BELOW the top strip (same height as soft keyboard) ──
            if (showExpandedOverlay) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelHeight)
                        .background(backgroundColor)
                        .padding(horizontal = KeyRowPaddingH, vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(PanelRowGap)
                    ) {
                        panelRows.forEach { rowKeys ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(KeySlotHeight),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (i in 0 until KEYS_PER_PANEL_ROW) {
                                    val key = rowKeys.getOrNull(i)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (key != null) {
                                            val panelIndex = panelKeys.indexOfFirst { it.id == key.id }
                                                .let { if (it < 0) panelKeys.size else it }
                                            ExtraKeySlot(
                                                key = key,
                                                isActive = isKeyActive(key),
                                                isDropTarget = hoverTargetId == key.id,
                                                isDragging = draggingId == key.id,
                                                editMode = true,
                                                accentColor = accentColor,
                                                textColor = textColor,
                                                fixedWidth = null,
                                                longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis,
                                                onTap = {
                                                    onKeyTap(key)
                                                },
                                                onDragStart = { localInSlot, rootPos ->
                                                    if (key.id == ADD_KEY_ID) {
                                                        onKeyTap(key)
                                                        return@ExtraKeySlot
                                                    }
                                                    // Haptic already fired in ExtraKeySlot on long-press enter.
                                                    totalDragDistance = 0f
                                                    dragPointerRoot = rootPos
                                                    dragSession = DragSession(
                                                        key = key,
                                                        fromZone = KeyZone.PANEL,
                                                        grabOffsetInSlot = localInSlot,
                                                        startRoot = rootPos
                                                    )
                                                },
                                                onDrag = { rootPos, distanceDelta ->
                                                    dragPointerRoot = rootPos
                                                    totalDragDistance += distanceDelta
                                                    hoverTargetId = findDropTarget(rootPos)?.keyId
                                                },
                                                onDragEnd = {
                                                    if (key.id == ADD_KEY_ID) return@ExtraKeySlot
                                                    val moved = totalDragDistance > viewConfiguration.touchSlop
                                                    val target = findDropTarget(dragPointerRoot)
                                                    if (!moved && key.isCustom) {
                                                        keyPendingDelete = key
                                                        dragSession = null
                                                        hoverTargetId = null
                                                        totalDragDistance = 0f
                                                    } else {
                                                        applyDrop(target)
                                                    }
                                                },
                                                onDragCancel = {
                                                    dragSession = null
                                                    hoverTargetId = null
                                                    totalDragDistance = 0f
                                                },
                                                onPositioned = { coords ->
                                                    registerSlot(KeyZone.PANEL, key, panelIndex, coords)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (dragSession != null) {
                                "Drag to a new slot — release to drop"
                            } else {
                                "Long-press & drag to reposition"
                            },
                            color = if (dragSession != null) accentColor else textColor.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontWeight = if (dragSession != null) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Reset",
                            color = accentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    context.performKeyHaptic()
                                    showResetDialog = true
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Floating ghost that follows the finger while dragging.
        val session = dragSession
        if (session != null && showExpandedOverlay) {
            val ghostX = dragPointerRoot.x - rootPositionInWindow.x - session.grabOffsetInSlot.x
            val ghostY = dragPointerRoot.y - rootPositionInWindow.y - session.grabOffsetInSlot.y
            Box(
                modifier = Modifier
                    .zIndex(20f)
                    .offset { IntOffset(ghostX.roundToInt(), ghostY.roundToInt()) }
                    .width(KeySlotWidth)
                    .height(KeySlotHeight)
                    .shadow(8.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(backgroundColor)
                    .border(1.5.dp, accentColor, RoundedCornerShape(6.dp))
                    .graphicsLayer {
                        scaleX = 1.08f
                        scaleY = 1.08f
                        alpha = 0.95f
                    },
                contentAlignment = Alignment.Center
            ) {
                KeyGlyph(
                    key = session.key,
                    color = accentColor,
                    isAdd = false
                )
            }
        }
    }

    if (showAddDialog) {
        SpecialKeysAddDialog(
            customLabel = customLabel,
            onCustomLabelChange = {
                customLabel = it
                customError = null
            },
            customCombo = customCombo,
            onCustomComboChange = {
                customCombo = it
                customError = null
            },
            customError = customError,
            accentColor = accentColor,
            textColor = textColor,
            backgroundColor = backgroundColor,
            onDismiss = { showAddDialog = false },
            onConfirm = {
                val name = customLabel.trim()
                val combo = customCombo.trim()
                when {
                    name.isEmpty() -> customError = "Enter a name (1–4 characters)"
                    combo.isEmpty() -> customError = "Enter a key or combination to send"
                    else -> {
                        val payload = resolveKeyPayload(combo)
                        panelKeys.add(newCustomExtraKey(name, payload))
                        persistAll()
                        showAddDialog = false
                        customLabel = ""
                        customCombo = ""
                        customError = null
                    }
                }
            }
        )
    }

    if (showResetDialog) {
        SpecialKeysResetDialog(
            accentColor = accentColor,
            textColor = textColor,
            backgroundColor = backgroundColor,
            onDismiss = { showResetDialog = false },
            onConfirm = {
                resetToDefaults()
                showResetDialog = false
                context.performClickHaptic()
            }
        )
    }

    if (keyPendingDelete != null) {
        val doomed = keyPendingDelete!!
        SpecialKeysDeleteDialog(
            keyLabel = doomed.label,
            accentColor = accentColor,
            textColor = textColor,
            backgroundColor = backgroundColor,
            onDismiss = { keyPendingDelete = null },
            onConfirm = {
                panelKeys.removeAll { it.id == doomed.id }
                topKeys.removeAll { it.id == doomed.id }
                persistAll()
                keyPendingDelete = null
            }
        )
    }
}
