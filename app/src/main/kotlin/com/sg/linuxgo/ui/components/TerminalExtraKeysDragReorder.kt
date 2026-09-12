package com.sg.linuxgo.ui.components

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal enum class KeyZone { TOP, PANEL }

internal data class SlotInfo(
    val zone: KeyZone,
    val keyId: String,
    val index: Int,
    val rect: Rect
)

internal data class DragSession(
    val key: ExtraKey,
    val fromZone: KeyZone,
    val grabOffsetInSlot: Offset,
    val startRoot: Offset
)

/**
 * Move [keyId] to [targetIndex] in [targetZone], removing it from its current list first.
 */
internal fun moveKeyTo(
    topKeys: SnapshotStateList<ExtraKey>,
    panelKeys: SnapshotStateList<ExtraKey>,
    keyId: String,
    targetZone: KeyZone,
    targetIndex: Int
) {
    // Work on plain lists to avoid concurrent modification issues with SnapshotStateList mid-edit.
    val top = topKeys.toMutableList()
    val panel = panelKeys.toMutableList()

    val fromTop = top.indexOfFirst { it.id == keyId }
    val fromPanel = panel.indexOfFirst { it.id == keyId }
    val item = when {
        fromTop >= 0 -> top.removeAt(fromTop)
        fromPanel >= 0 -> panel.removeAt(fromPanel)
        else -> return
    }

    val dest = if (targetZone == KeyZone.TOP) top else panel
    var insertAt = targetIndex
    val sameList = (targetZone == KeyZone.TOP && fromTop >= 0) ||
        (targetZone == KeyZone.PANEL && fromPanel >= 0)
    val fromIndex = if (fromTop >= 0) fromTop else fromPanel
    if (sameList && fromIndex < insertAt) {
        insertAt -= 1
    }
    insertAt = insertAt.coerceIn(0, dest.size)
    dest.add(insertAt, item)

    topKeys.clear()
    topKeys.addAll(top)
    panelKeys.clear()
    panelKeys.addAll(panel)
}
