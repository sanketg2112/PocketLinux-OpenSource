package com.sg.linuxgo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import com.sg.linuxgo.ui.utils.performClickHaptic
import com.sg.linuxgo.R
import com.sg.linuxgo.ContainerConfig
import com.sg.linuxgo.FeatureGates
import com.sg.linuxgo.LOW_RAM_WARN_MB
import com.sg.linuxgo.ui.theme.*

data class ContainerCardStats(
    val storageUsedMB: Int,
    val storageTotalMB: Int,
    val ramUsedMB: Int,
    val ramTotalMB: Int,
    val systemUsedStorageMB: Int,
    val systemUsedRamMB: Int,
    /** Recent RAM samples (MB) for the in-card timeseries while a session is live. */
    val ramHistoryMB: List<Int> = emptyList()
)

/** Max points kept for the card RAM sparkline (~2 min at 3s poll). */
internal const val RAM_HISTORY_MAX_POINTS = 40

/**
 * Append a live RAM sample, or clear history when the session is idle (sample ≤ 0).
 */
internal fun appendRamHistorySample(
    history: List<Int>,
    sampleMb: Int,
    maxPoints: Int = RAM_HISTORY_MAX_POINTS
): List<Int> {
    if (sampleMb <= 0) return emptyList()
    val capped = maxPoints.coerceAtLeast(1)
    return (history + sampleMb).takeLast(capped)
}

/** Compact size for card subtitle, e.g. "1.5GB" / "512MB" (at most one decimal). */
internal fun formatStorageUsedCompact(sizeMB: Int): String {
    if (sizeMB < 0) return "0MB"
    if (sizeMB < 1024) return "${sizeMB}MB"
    val sizeGB = sizeMB / 1024.0
    val formatted = String.format(java.util.Locale.US, "%.1f", sizeGB)
    val trimmed = if (formatted.endsWith(".0")) formatted.dropLast(2) else formatted
    return "${trimmed}GB"
}

@Composable
fun ContainerCard(
    container: ContainerConfig,
    isInstalling: Boolean,
    isActive: Boolean,
    isGuiActive: Boolean,
    isShellActive: Boolean,
    installProgressMessage: String,
    installProgressPercent: Int,
    stats: ContainerCardStats?,
    accentColor: Color,
    isDarkTheme: Boolean,
    onLaunchGui: () -> Unit,
    onLaunchShell: () -> Unit,
    onInstall: () -> Unit,
    onSettings: () -> Unit,
    onViewLogs: () -> Unit = {},
    onTerminate: () -> Unit,
    onAbortInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val mutedAccent = accentColor.copy(alpha = 0.78f)
    val secondaryTextColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val statusText: String
    val statusColor: Color
    when {
        isInstalling -> {
            statusText = "Installing"
            statusColor = InstallBlue
        }
        container.isInstalled && isActive && isGuiActive -> {
            statusText = "Desktop running"
            statusColor = mutedAccent
        }
        container.isInstalled && isActive && isShellActive -> {
            statusText = "Terminal running"
            statusColor = mutedAccent
        }
        container.isInstalled -> {
            statusText = "Ready"
            statusColor = mutedAccent
        }
        else -> {
            statusText = "Pending"
            statusColor = Yellow
        }
    }
    val distroIconRes = when (container.distro) {
        "alpine" -> R.drawable.ic_alpine
        "archlinux" -> R.drawable.ic_archlinux
        "debian" -> R.drawable.ic_debian
        "ubuntu" -> R.drawable.ic_ubuntu
        "kali" -> R.drawable.ic_kali
        else -> R.drawable.ic_alpine
    }

    val sessionLive = isActive && (isGuiActive || isShellActive) && !isInstalling
    val activeBorder = if (sessionLive) {
        BorderStroke(1.5.dp, mutedAccent.copy(alpha = 0.75f))
    } else {
        null
    }

    val logsFromCard = FeatureGates.installLogsVisible()
    Card(
        // During install (debug only): open live logs. Release: no-op while installing.
        // Otherwise open container settings.
        onClick = {
            when {
                isInstalling && logsFromCard -> onViewLogs()
                !isInstalling -> onSettings()
            }
        },
        enabled = true,
        shape = RoundedCornerShape(16.dp),
        border = activeBorder,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        // Spacing between cards is owned by the parent grid/list.
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append(container.name)
                    append(". ")
                    append(statusText)
                    when {
                        isInstalling && logsFromCard -> append(". Tap for install logs")
                        !isInstalling -> append(". Tap for settings")
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header: distro icon + name; stop at top-right when session is live.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (isInstalling || (container.isInstalled && stats != null)) 12.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val iconBg = if (isDarkTheme) IconBgDark else Color(0xFFE5E5EA)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = distroIconRes),
                        contentDescription = null,
                        // Slightly larger than before so marks (esp. Kali dragon) read clearly.
                        modifier = Modifier.size(34.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = container.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                        letterSpacing = 0.3.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Username · distro · DE under the title (e.g. pocketlinux · Debian · XFCE4).
                    val subtitle = when {
                        isInstalling -> "Installing…"
                        !container.isInstalled -> "Not installed"
                        else -> {
                            val deText = if (container.de.lowercase() == "none" || container.de.isEmpty()) {
                                "PRoot"
                            } else {
                                container.de.uppercase()
                            }
                            val distroText = when (container.distro.lowercase()) {
                                "alpine" -> "Alpine"
                                "archlinux" -> "Arch"
                                "debian" -> "Debian"
                                "ubuntu" -> "Ubuntu"
                                "kali" -> "Kali"
                                else -> container.distro.replaceFirstChar {
                                    if (it.isLowerCase()) it.titlecase() else it.toString()
                                }
                            }
                            "${container.username}  •  $distroText  •  $deText"
                        }
                    }
                    Text(
                        text = subtitle,
                        color = secondaryTextColor,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Stop — top right of the card while desktop/terminal is live.
                if (sessionLive) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(RedAlpha15)
                            .border(1.dp, Red.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                            .semantics { contentDescription = "Stop session" }
                            .clickable {
                                view.performClickHaptic()
                                onTerminate()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            tint = Red,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else if (!container.isInstalled && !isInstalling) {
                    // Pending chip only when not yet installed.
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.12f))
                            .border(1.dp, statusColor.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Default,
                            maxLines = 1
                        )
                    }
                }
            }

            // Storage utilisation (installed, not installing)
            if (container.isInstalled && !isInstalling && stats != null) {
                val storageTotal = maxOf(1, stats.storageTotalMB)
                val storageFree = maxOf(0, storageTotal - stats.systemUsedStorageMB)
                val deviceExistingStorage = maxOf(0, stats.systemUsedStorageMB - stats.storageUsedMB)
                val systemStorageProgress = deviceExistingStorage.toFloat() / storageTotal.toFloat()
                val containerStorageProgress = stats.storageUsedMB.toFloat() / storageTotal.toFloat()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (sessionLive) 10.dp else 12.dp)
                ) {
                    Text(
                        text = buildString {
                            append("Used ${formatStorageUsedCompact(stats.storageUsedMB)}")
                            append("  ·  Free ${formatStorageUsedCompact(storageFree)}")
                            append("  ·  Total ${formatStorageUsedCompact(storageTotal)}")
                        },
                        color = if (isDarkTheme) TextWhite else TextLightPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    DualProgressBar(
                        systemProgress = systemStorageProgress,
                        containerProgress = containerStorageProgress,
                        systemColor = DeviceProgressGray,
                        containerColor = mutedAccent,
                        isDarkTheme = isDarkTheme
                    )
                }
            }

            // Live session: RAM timeseries (same card background; label top-left).
            if (sessionLive && stats != null) {
                val freeRamMb = if (stats.ramTotalMB > 0) {
                    (stats.ramTotalMB - stats.systemUsedRamMB).coerceAtLeast(0)
                } else {
                    -1
                }
                // Match low-RAM pill: yellowish warning when free ≤ 500 MB.
                val graphColor = if (freeRamMb in 0..LOW_RAM_WARN_MB) {
                    Yellow
                } else {
                    mutedAccent
                }
                RamUsageTimeseries(
                    samplesMb = stats.ramHistoryMB.ifEmpty {
                        if (stats.ramUsedMB > 0) listOf(stats.ramUsedMB) else emptyList()
                    },
                    currentMb = stats.ramUsedMB,
                    freeMb = freeRamMb,
                    accentColor = graphColor,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }

            // Installation progress section
            if (isInstalling) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    val progressFraction = if (installProgressPercent in 0..100) {
                        installProgressPercent / 100f
                    } else {
                        0f
                    }
                    // Status below the bar: phase + download size in MB only.
                    // Percent is shown solely above the progress bar (never duplicated here).
                    val statusLabel = installProgressMessage
                        .ifBlank { "Preparing..." }
                        .substringBefore(" - Storage:")
                        .substringBefore(" | Storage:")
                        .let { label ->
                            // Defensive: drop any leftover "N%" the service may still embed.
                            label
                                .replace(Regex(""":\s*\d+%\s*·\s*"""), ": ")
                                .replace(Regex(""":\s*\d+%\s*$"""), "")
                                .replace(Regex("""\s+\d+%\s*·\s*"""), " ")
                                .replace(Regex("""\s+\d+%\s*$"""), "")
                                .trim()
                                .trimEnd(':', ' ', '·')
                                .trim()
                                .ifBlank { "Preparing..." }
                        }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "INSTALLING",
                            color = secondaryTextColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = if (installProgressPercent in 0..100) {
                                "$installProgressPercent%"
                            } else {
                                "…"
                            },
                            color = if (isDarkTheme) TextWhite else TextLightPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Normal
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    DualProgressBar(
                        systemProgress = 0f,
                        containerProgress = progressFraction,
                        systemColor = DeviceProgressGray,
                        containerColor = mutedAccent,
                        isDarkTheme = isDarkTheme
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = statusLabel,
                        color = secondaryTextColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Safe to leave — install continues in the background.",
                        color = secondaryTextColor.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Default,
                        lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            view.performClickHaptic()
                            onAbortInstall()
                        },
                        border = BorderStroke(1.dp, if (isDarkTheme) Color(0xFF44444A) else DividerDark),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = if (isDarkTheme) TextWhite else TextLightPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .semantics { contentDescription = "Abort installation" },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "Abort installation",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Default
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Action Buttons or Install Button
            if (container.isInstalled && !isInstalling) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val guiLabel = when {
                        isActive && isGuiActive -> "Resume desktop"
                        else -> "Launch desktop"
                    }
                    val shellLabel = when {
                        isActive && isShellActive -> "Resume terminal"
                        else -> "Open terminal"
                    }
                    Button(
                        onClick = {
                            view.performClickHaptic()
                            onLaunchGui()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = mutedAccent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .padding(end = 6.dp)
                            .semantics { contentDescription = guiLabel },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = guiLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Default,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            view.performClickHaptic()
                            onLaunchShell()
                        },
                        border = BorderStroke(1.dp, if (isDarkTheme) Color(0xFF44444A) else DividerDark),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = if (isDarkTheme) TextWhite else TextLightPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .padding(start = 6.dp)
                            .semantics { contentDescription = shellLabel },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = shellLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Default,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else if (!container.isInstalled && !isInstalling) {
                Button(
                    onClick = {
                        view.performClickHaptic()
                        onInstall()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .semantics { contentDescription = "Start installation" },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "Start installation",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default
                    )
                }
            }
        }
    }
}

@Composable
private fun RamUsageTimeseries(
    samplesMb: List<Int>,
    currentMb: Int,
    freeMb: Int,
    accentColor: Color,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val displayMb = when {
        currentMb > 0 -> currentMb
        samplesMb.isNotEmpty() -> samplesMb.last()
        else -> 0
    }
    val plotSamples = when {
        samplesMb.size >= 2 -> samplesMb
        samplesMb.size == 1 -> listOf(samplesMb[0], samplesMb[0])
        displayMb > 0 -> listOf(displayMb, displayMb)
        else -> emptyList()
    }
    val labelColor = if (isDarkTheme) TextWhite else TextLightPrimary
    val usagePart = if (displayMb > 0) {
        "RAM usage ${formatStorageUsedCompact(displayMb)}"
    } else {
        "RAM usage —"
    }
    val freePart = if (freeMb >= 0) {
        "Free ${formatStorageUsedCompact(freeMb)}"
    } else {
        null
    }
    val ramLabel = if (freePart != null) "$usagePart  ·  $freePart" else usagePart

    // Flat on the card surface — no separate panel background.
    // Label sits top-left inside the graph bounds so it reads with the sparkline.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        if (plotSamples.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Leave room under the label so the line stays readable.
                    .padding(top = 22.dp)
            ) {
                val maxY = (plotSamples.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
                val scaleMax = maxY * 1.12f
                val yRange = scaleMax.coerceAtLeast(1f)
                val n = plotSamples.size
                val stepX = if (n <= 1) 0f else size.width / (n - 1).toFloat()

                fun pointAt(i: Int): Offset {
                    val x = i * stepX
                    val ratio = (plotSamples[i] / yRange).coerceIn(0f, 1f)
                    val y = size.height * (1f - ratio)
                    return Offset(x, y)
                }

                val linePath = Path()
                val fillPath = Path()
                for (i in plotSamples.indices) {
                    val p = pointAt(i)
                    if (i == 0) {
                        linePath.moveTo(p.x, p.y)
                        fillPath.moveTo(p.x, size.height)
                        fillPath.lineTo(p.x, p.y)
                    } else {
                        linePath.lineTo(p.x, p.y)
                        fillPath.lineTo(p.x, p.y)
                    }
                }
                fillPath.lineTo(size.width, size.height)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = if (isDarkTheme) 0.22f else 0.16f),
                            accentColor.copy(alpha = 0.0f)
                        )
                    )
                )
                drawPath(
                    path = linePath,
                    color = accentColor.copy(alpha = 0.95f),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
                val last = pointAt(plotSamples.lastIndex)
                drawCircle(
                    color = accentColor,
                    radius = 3.5.dp.toPx(),
                    center = last
                )
                drawCircle(
                    color = Color.White.copy(alpha = if (isDarkTheme) 0.9f else 1f),
                    radius = 1.6.dp.toPx(),
                    center = last
                )
            }
        }

        Text(
            text = ramLabel,
            color = labelColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .semantics {
                    contentDescription = ramLabel
                }
        )
    }
}

@Composable
private fun DualProgressBar(
    systemProgress: Float,
    containerProgress: Float,
    systemColor: Color,
    containerColor: Color,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val trackColor = if (isDarkTheme) TrackColor else Color(0xFFE5E5EA)
    val systemFillColor = if (isDarkTheme) systemColor else systemColor.copy(alpha = 0.35f)
    val containerFillColor = if (isDarkTheme) containerColor else containerColor.copy(alpha = 0.5f)
    val barHeight = 12.dp
    val cornerRadius = 6.dp

    val systemCornerSize = if (containerProgress > 0f) 0.dp else cornerRadius
    val containerCornerSize = if (systemProgress > 0f) 0.dp else cornerRadius

    val systemShape = RoundedCornerShape(
        topStart = cornerRadius,
        topEnd = systemCornerSize,
        bottomEnd = systemCornerSize,
        bottomStart = cornerRadius
    )

    val containerShape = RoundedCornerShape(
        topStart = containerCornerSize,
        topEnd = cornerRadius,
        bottomEnd = cornerRadius,
        bottomStart = containerCornerSize
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val width = maxWidth
        // Track (empty background)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .background(trackColor, shape = RoundedCornerShape(cornerRadius))
        )
        // System usage (grey) - drawn first as base layer
        if (systemProgress > 0f) {
            val clampedProgress = systemProgress.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(clampedProgress)
                    .height(barHeight)
                    .background(systemFillColor, shape = systemShape)
            )
        }
        // Container usage (accent) - drawn on top
        if (containerProgress > 0f) {
            val clampedProgress = containerProgress.coerceIn(0f, 1f)
            val systemWidth = width * systemProgress.coerceIn(0f, 1f)
            val containerWidth = width * clampedProgress
            Box(
                modifier = Modifier
                    .offset(x = systemWidth)
                    .width(containerWidth)
                    .height(barHeight)
                    .background(containerFillColor, shape = containerShape)
            )
        }
    }
}
