package com.sg.linuxgo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ContainerConfig
import com.sg.linuxgo.ui.theme.DividerDark
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.theme.TrackColor

/** Grid cell: either a real container card or the trailing "add" tile. */
private sealed class GridCell {
    data class Container(val config: ContainerConfig) : GridCell()
    data object Add : GridCell()
}

/**
 * Responsive container-card layout:
 * - 1 column on narrow phones (portrait)
 * - 2 / 3 / 4 columns as width grows (landscape, DeX, external displays)
 * - Trailing dashed "Add container" tile shares the same cell size as cards
 *
 * Uses available width (not orientation alone) so freeform / multi-window
 * and desktop-like surfaces pick the right density.
 */
@Composable
fun ContainerCardsGrid(
    containers: List<ContainerConfig>,
    installingId: String?,
    activeId: String?,
    isGuiSessionActive: Boolean,
    isShellSessionActive: Boolean,
    installProgressMessage: String,
    installProgressPercent: Int,
    statsMap: Map<String, ContainerCardStats>,
    accentColor: Color,
    isDarkTheme: Boolean,
    onAddContainer: () -> Unit,
    onLaunchGui: (ContainerConfig) -> Unit,
    onLaunchShell: (ContainerConfig) -> Unit,
    onInstall: (ContainerConfig) -> Unit,
    onSettings: (ContainerConfig) -> Unit,
    onViewLogs: (ContainerConfig) -> Unit,
    onTerminate: (ContainerConfig) -> Unit,
    onAbortInstall: (ContainerConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // ~280dp+ per card so stats/actions stay usable.
        val columns = when {
            maxWidth >= 1120.dp -> 4
            maxWidth >= 840.dp -> 3
            maxWidth >= 560.dp -> 2
            else -> 1
        }
        val gap = if (columns == 1) 24.dp else 12.dp
        val cells: List<GridCell> =
            containers.map { GridCell.Container(it) } + GridCell.Add

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            cells.chunked(columns).forEach { rowItems ->
                if (columns == 1) {
                    rowItems.forEach { cell ->
                        GridCellContent(
                            cell = cell,
                            installingId = installingId,
                            activeId = activeId,
                            isGuiSessionActive = isGuiSessionActive,
                            isShellSessionActive = isShellSessionActive,
                            installProgressMessage = installProgressMessage,
                            installProgressPercent = installProgressPercent,
                            statsMap = statsMap,
                            accentColor = accentColor,
                            isDarkTheme = isDarkTheme,
                            onAddContainer = onAddContainer,
                            onLaunchGui = onLaunchGui,
                            onLaunchShell = onLaunchShell,
                            onInstall = onInstall,
                            onSettings = onSettings,
                            onViewLogs = onViewLogs,
                            onTerminate = onTerminate,
                            onAbortInstall = onAbortInstall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        rowItems.forEach { cell ->
                            GridCellContent(
                                cell = cell,
                                installingId = installingId,
                                activeId = activeId,
                                isGuiSessionActive = isGuiSessionActive,
                                isShellSessionActive = isShellSessionActive,
                                installProgressMessage = installProgressMessage,
                                installProgressPercent = installProgressPercent,
                                statsMap = statsMap,
                                accentColor = accentColor,
                                isDarkTheme = isDarkTheme,
                                onAddContainer = onAddContainer,
                                onLaunchGui = onLaunchGui,
                                onLaunchShell = onLaunchShell,
                                onInstall = onInstall,
                                onSettings = onSettings,
                                onViewLogs = onViewLogs,
                                onTerminate = onTerminate,
                                onAbortInstall = onAbortInstall,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                        // Keep last-row cells column-width (do not stretch a lone tile full width).
                        repeat(columns - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCellContent(
    cell: GridCell,
    installingId: String?,
    activeId: String?,
    isGuiSessionActive: Boolean,
    isShellSessionActive: Boolean,
    installProgressMessage: String,
    installProgressPercent: Int,
    statsMap: Map<String, ContainerCardStats>,
    accentColor: Color,
    isDarkTheme: Boolean,
    onAddContainer: () -> Unit,
    onLaunchGui: (ContainerConfig) -> Unit,
    onLaunchShell: (ContainerConfig) -> Unit,
    onInstall: (ContainerConfig) -> Unit,
    onSettings: (ContainerConfig) -> Unit,
    onViewLogs: (ContainerConfig) -> Unit,
    onTerminate: (ContainerConfig) -> Unit,
    onAbortInstall: (ContainerConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    when (cell) {
        is GridCell.Container -> ContainerCardItem(
            config = cell.config,
            installingId = installingId,
            activeId = activeId,
            isGuiSessionActive = isGuiSessionActive,
            isShellSessionActive = isShellSessionActive,
            installProgressMessage = installProgressMessage,
            installProgressPercent = installProgressPercent,
            statsMap = statsMap,
            accentColor = accentColor,
            isDarkTheme = isDarkTheme,
            onLaunchGui = onLaunchGui,
            onLaunchShell = onLaunchShell,
            onInstall = onInstall,
            onSettings = onSettings,
            onViewLogs = onViewLogs,
            onTerminate = onTerminate,
            onAbortInstall = onAbortInstall,
            modifier = modifier
        )
        GridCell.Add -> AddContainerDashedTile(
            onAddContainer = onAddContainer,
            modifier = modifier
        )
    }
}

/** Dashed "add container" cell — same rounded size language as [ContainerCard]. */
@Composable
fun AddContainerDashedTile(
    onAddContainer: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Add environment"
) {
    val strokeColor = if (isSystemInDarkTheme()) TrackColor else DividerDark
    val accentColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawRoundRect(
                    color = strokeColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(8.dp.toPx(), 4.dp.toPx()),
                            0f
                        )
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            }
            .clickable(onClick = onAddContainer)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 12.dp)
                .size(36.dp)
                .border(2.dp, accentColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "＋",
                color = accentColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Default,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Default
        )
    }
}

@Composable
private fun ContainerCardItem(
    config: ContainerConfig,
    installingId: String?,
    activeId: String?,
    isGuiSessionActive: Boolean,
    isShellSessionActive: Boolean,
    installProgressMessage: String,
    installProgressPercent: Int,
    statsMap: Map<String, ContainerCardStats>,
    accentColor: Color,
    isDarkTheme: Boolean,
    onLaunchGui: (ContainerConfig) -> Unit,
    onLaunchShell: (ContainerConfig) -> Unit,
    onInstall: (ContainerConfig) -> Unit,
    onSettings: (ContainerConfig) -> Unit,
    onViewLogs: (ContainerConfig) -> Unit,
    onTerminate: (ContainerConfig) -> Unit,
    onAbortInstall: (ContainerConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val cid = config.id
    ContainerCard(
        container = config,
        isInstalling = cid == installingId,
        isActive = cid == activeId && (isGuiSessionActive || isShellSessionActive),
        isGuiActive = isGuiSessionActive && cid == activeId,
        isShellActive = isShellSessionActive && cid == activeId,
        installProgressMessage = installProgressMessage,
        installProgressPercent = installProgressPercent,
        stats = statsMap[cid],
        accentColor = accentColor,
        isDarkTheme = isDarkTheme,
        onLaunchGui = { onLaunchGui(config) },
        onLaunchShell = { onLaunchShell(config) },
        onInstall = { onInstall(config) },
        onSettings = { onSettings(config) },
        onViewLogs = { onViewLogs(config) },
        onTerminate = { onTerminate(config) },
        onAbortInstall = { onAbortInstall(config) },
        modifier = modifier
    )
}
