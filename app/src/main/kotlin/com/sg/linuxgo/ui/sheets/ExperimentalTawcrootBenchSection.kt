package com.sg.linuxgo.ui.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ProotBinary
import com.sg.linuxgo.ProotMicrobench
import com.sg.linuxgo.R
import com.sg.linuxgo.TawcrootMicrobench
import com.sg.linuxgo.ui.theme.DividerDark
import com.sg.linuxgo.ui.theme.DividerLight
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.theme.TextWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * tawcroot master toggle + always/hybrid modes, performance check vs classic,
 * bar-chart results, and logs. Classic proot when off.
 */
@Composable
fun ExperimentalTawcrootBenchSection(
    accentColor: Color,
    isDarkTheme: Boolean,
    onHelp: (title: String, body: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) DividerDark else DividerLight
    val cardBg = if (isDarkTheme) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
    val classicBarColor = Color(0xFF5C6BC0)
    val tawcBarColor = Color(0xFF26A69A)

    var runtimeMode by remember { mutableStateOf(ProotBinary.getMode(context)) }
    var tawcEnabled by remember {
        mutableStateOf(
            runtimeMode == ProotBinary.Mode.TAWCROOT ||
                runtimeMode == ProotBinary.Mode.TAWCROOT_HYBRID
        )
    }
    var lastTawcMode by remember {
        mutableStateOf(
            when (val m = ProotBinary.getMode(context)) {
                ProotBinary.Mode.TAWCROOT_HYBRID -> ProotBinary.Mode.TAWCROOT_HYBRID
                else -> ProotBinary.Mode.TAWCROOT
            }
        )
    }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val cached = remember { TawcrootMicrobench.loadLastTawcrootReport(context) }
    var reportText by remember { mutableStateOf(cached?.first) }
    var reportAtMs by remember { mutableStateOf(cached?.second ?: 0L) }
    var chartBars by remember { mutableStateOf(TawcrootMicrobench.loadTawcrootChartBars(context)) }
    var recommendation by remember {
        mutableStateOf(TawcrootMicrobench.lastTawcrootRecommendation(context))
    }
    var hybridMap by remember {
        mutableStateOf(TawcrootMicrobench.tawcrootHybridRoutingSummary(context))
    }
    var showLogs by remember { mutableStateOf(false) }

    val targets = remember { ProotMicrobench.listTargets(context) }
    var selectedTargetId by remember {
        mutableStateOf(
            ProotMicrobench.pickTarget(context)?.containerId ?: targets.firstOrNull()?.containerId
        )
    }
    val selectedTarget = remember(selectedTargetId, targets) {
        targets.firstOrNull { it.containerId == selectedTargetId } ?: targets.firstOrNull()
    }
    val hasTarget = selectedTarget != null
    val tawcPresent = remember { ProotBinary.isTawcrootBinaryPresent(context) }
    val enabledModes = listOf(ProotBinary.Mode.TAWCROOT, ProotBinary.Mode.TAWCROOT_HYBRID)

    if (showLogs && reportText != null) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = {
                Text(
                    text = "tawcroot performance log",
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textPrimary
                )
            },
            text = {
                SelectionContainer {
                    Text(
                        text = reportText!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = textPrimary,
                        lineHeight = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogs = false }) {
                    Text("Close", color = accentColor)
                }
            },
            containerColor = cardBg
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(thickness = 1.dp, color = dividerColor)
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "TAWCROOT",
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = accentColor,
        letterSpacing = 0.1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    SwitchSettingRow(
        title = "Enable tawcroot options",
        summary = if (tawcEnabled) {
            "On — ${ProotBinary.modeLabel(runtimeMode)}"
        } else {
            "Off — not using tawcroot"
        },
        description = "Systrap rootless runtime from github.com/wmww/tawc (libtawcroot.so). " +
            "When off, classic proot is used. Restart terminal/desktop after changing.",
        checked = tawcEnabled,
        onCheckedChange = { on ->
            tawcEnabled = on
            if (on) {
                val restore = lastTawcMode.takeIf {
                    it == ProotBinary.Mode.TAWCROOT || it == ProotBinary.Mode.TAWCROOT_HYBRID
                } ?: ProotBinary.Mode.TAWCROOT
                runtimeMode = restore
                ProotBinary.setMode(context, restore)
            } else {
                if (runtimeMode == ProotBinary.Mode.TAWCROOT ||
                    runtimeMode == ProotBinary.Mode.TAWCROOT_HYBRID
                ) {
                    lastTawcMode = runtimeMode
                }
                runtimeMode = ProotBinary.Mode.CLASSIC
                ProotBinary.setMode(context, ProotBinary.Mode.CLASSIC)
            }
        },
        onInfoClick = {
            onHelp(
                "Enable tawcroot options",
                "tawcroot is a single-process systrap alternative to classic proot " +
                    "(github.com/wmww/tawc). Off = classic proot. On = tawcroot always, " +
                    "or Hybrid based on the tawcroot performance check. Works with Alpine: " +
                    "host apk is copied into the rootfs (tawcroot cannot bind single files)."
            )
        },
        accentColor = accentColor,
        isDarkTheme = isDarkTheme
    )

    if (!tawcEnabled) {
        Text(
            text = "tawcroot library: ${if (tawcPresent) "bundled (arm64)" else "not present"}.",
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            color = textSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        return
    }

    Spacer(modifier = Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "TAWCROOT MODE",
            fontFamily = FontFamily.Default,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            letterSpacing = 0.1.sp
        )
        Spacer(modifier = Modifier.width(6.dp))
        IconButton(
            onClick = {
                onHelp(
                    "tawcroot modes",
                    "tawcroot always — every session uses libtawcroot.so (arm64).\n\n" +
                        "Hybrid — after a tawcroot performance check, terminal & setup use " +
                        "the fork winner; desktop uses path/stat/write/compute (wall as tie-break)."
                )
            },
            modifier = Modifier.size(18.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_info),
                contentDescription = "Info tawcroot modes",
                tint = textSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
    Text(
        text = "Arm64 lib: ${if (tawcPresent) "present" else "missing"}",
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        color = textSecondary,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
    )

    enabledModes.forEach { mode ->
        val selected = runtimeMode == mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    runtimeMode = mode
                    lastTawcMode = mode
                    ProotBinary.setMode(context, mode)
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = {
                    runtimeMode = mode
                    lastTawcMode = mode
                    ProotBinary.setMode(context, mode)
                },
                colors = RadioButtonDefaults.colors(
                    selectedColor = accentColor,
                    unselectedColor = textSecondary
                )
            )
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    text = ProotBinary.modeLabel(mode),
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = textPrimary
                )
                Text(
                    text = ProotBinary.modeSummary(mode),
                    fontFamily = FontFamily.Default,
                    fontSize = 10.sp,
                    color = textSecondary
                )
            }
        }
    }

    if (runtimeMode == ProotBinary.Mode.TAWCROOT_HYBRID) {
        Text(
            text = hybridMap,
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            color = textPrimary,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
        )
    }
    recommendation?.let { rec ->
        Text(
            text = rec,
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            color = textSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(thickness = 1.dp, color = dividerColor)
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "TAWCROOT PERFORMANCE CHECK",
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = accentColor,
        letterSpacing = 0.1.sp
    )
    Text(
        text = "Runs classic proot and tawcroot on the selected container. " +
            "Lower bars are faster. Results power tawcroot Hybrid routing.",
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        color = textSecondary,
        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
    )

    if (targets.isEmpty()) {
        Text(
            text = "No installed container with a guest shell (bin/sh). " +
                "Finish install first. Alpine is supported (busybox symlink).",
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            color = Color(0xFFE57373),
            modifier = Modifier.padding(bottom = 12.dp)
        )
    } else {
        Text(
            text = "Target container",
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        targets.forEach { t ->
            val selected = t.containerId == selectedTargetId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedTargetId = t.containerId
                        ProotMicrobench.setPreferredTargetId(context, t.containerId)
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = {
                        selectedTargetId = t.containerId
                        ProotMicrobench.setPreferredTargetId(context, t.containerId)
                    },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = accentColor,
                        unselectedColor = textSecondary
                    )
                )
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = t.displayName,
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        color = textPrimary
                    )
                    Text(
                        text = t.containerId,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = textSecondary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }

    Button(
        onClick = {
            if (running) return@Button
            val id = selectedTarget?.containerId ?: return@Button
            running = true
            progress = "Starting…"
            errorText = null
            scope.launch {
                try {
                    val report = withContext(Dispatchers.IO) {
                        TawcrootMicrobench.runCompareTawcroot(context, containerId = id) { msg ->
                            scope.launch(Dispatchers.Main.immediate) { progress = msg }
                        }
                    }
                    val formatted = report.format()
                    TawcrootMicrobench.saveLastTawcrootReport(context, formatted)
                    TawcrootMicrobench.saveTawcrootCompareOutcome(context, report)
                    reportText = formatted
                    reportAtMs = System.currentTimeMillis()
                    chartBars = TawcrootMicrobench.loadTawcrootChartBars(context)
                    recommendation = TawcrootMicrobench.lastTawcrootRecommendation(context)
                    hybridMap = TawcrootMicrobench.tawcrootHybridRoutingSummary(context)
                    progress = "Done"
                } catch (e: Exception) {
                    errorText = e.message ?: e.javaClass.simpleName
                    progress = null
                } finally {
                    running = false
                }
            }
        },
        enabled = !running && hasTarget,
        colors = ButtonDefaults.buttonColors(
            containerColor = accentColor,
            contentColor = TextWhite,
            disabledContainerColor = accentColor.copy(alpha = 0.4f),
            disabledContentColor = TextWhite.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = if (running) "Checking performance…" else "Check performance (classic vs tawcroot)",
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }

    progress?.let {
        Text(
            text = it,
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            color = accentColor,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    errorText?.let {
        Text(
            text = it,
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            color = Color(0xFFE57373),
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    if (chartBars.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, dividerColor, RoundedCornerShape(8.dp))
                .background(cardBg, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RESULTS",
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTawcReportTime(reportAtMs),
                    fontFamily = FontFamily.Default,
                    fontSize = 10.sp,
                    color = textSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TawcLegendDot(classicBarColor)
                Text("classic", fontSize = 10.sp, color = textSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                TawcLegendDot(tawcBarColor)
                Text("tawcroot", fontSize = 10.sp, color = textSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                Text("lower = faster", fontSize = 10.sp, color = textSecondary)
            }
            Spacer(modifier = Modifier.height(12.dp))

            chartBars.forEach { bar ->
                TawcMetricBarRow(
                    bar = bar,
                    classicColor = classicBarColor,
                    tawcColor = tawcBarColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            val cWins = chartBars.count { it.winner == "classic" }
            val tWins = chartBars.count { it.winner == "tawcroot" }
            val ties = chartBars.count { it.winner == "tie" }
            Text(
                text = when {
                    tWins > cWins -> "Overall: tawcroot won more metrics ($tWins–$cWins" +
                        if (ties > 0) ", $ties ties)" else ")"
                    cWins > tWins -> "Overall: classic won more metrics ($cWins–$tWins" +
                        if (ties > 0) ", $ties ties)" else ")"
                    else -> "Overall: tied metric wins ($cWins–$tWins)"
                },
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = textPrimary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Text(
                text = hybridMap,
                fontFamily = FontFamily.Default,
                fontSize = 11.sp,
                color = textSecondary,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showLogs = true },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    enabled = !reportText.isNullOrBlank()
                ) {
                    Text("Logs", fontSize = 12.sp, color = accentColor)
                }
                OutlinedButton(
                    onClick = {
                        TawcrootMicrobench.clearLastTawcrootReport(context)
                        reportText = null
                        reportAtMs = 0L
                        chartBars = emptyList()
                        progress = null
                        recommendation = null
                        hybridMap = TawcrootMicrobench.tawcrootHybridRoutingSummary(context)
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear", fontSize = 12.sp, color = textSecondary)
                }
            }
        }
    } else {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No chart data yet. Run Check performance to compare classic vs tawcroot.",
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            color = textSecondary
        )
        if (!reportText.isNullOrBlank()) {
            OutlinedButton(
                onClick = { showLogs = true },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("View logs", fontSize = 12.sp, color = accentColor)
            }
        }
    }
}

@Composable
private fun TawcLegendDot(color: Color) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .size(10.dp)
            .background(color, RoundedCornerShape(2.dp))
    )
}

@Composable
private fun TawcMetricBarRow(
    bar: ProotMicrobench.ChartBar,
    classicColor: Color,
    tawcColor: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    val c = bar.classicMs
    val t = bar.challengerMs
    val maxMs = max(c ?: 0.0, t ?: 0.0).coerceAtLeast(1.0)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = bar.label,
                fontFamily = FontFamily.Default,
                fontSize = 11.sp,
                color = textPrimary,
                modifier = Modifier.weight(1f)
            )
            val winLabel = when (bar.winner) {
                "classic" -> "classic wins"
                "tawcroot" -> "tawcroot wins"
                "tie" -> "tie"
                else -> "—"
            }
            Text(
                text = winLabel,
                fontFamily = FontFamily.Default,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = when (bar.winner) {
                    "classic" -> classicColor
                    "tawcroot" -> tawcColor
                    else -> textSecondary
                }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        listOf(
            Triple("C", c, classicColor),
            Triple("T", t, tawcColor)
        ).forEach { (tag, ms, color) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = tag,
                    fontSize = 9.sp,
                    color = textSecondary,
                    modifier = Modifier.width(14.dp)
                )
                val frac = if (ms != null) (ms / maxMs).toFloat().coerceIn(0.02f, 1f) else 0f
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                ) {
                    val h = size.height
                    val w = size.width * frac
                    drawRoundRect(
                        color = if (ms != null) color else color.copy(alpha = 0.15f),
                        topLeft = Offset(0f, 0f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }
                Text(
                    text = if (ms != null) formatTawcMs(ms) else "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = textSecondary,
                    modifier = Modifier
                        .width(56.dp)
                        .padding(start = 6.dp)
                )
            }
        }
    }
}

private fun formatTawcMs(ms: Double): String {
    return if (ms >= 100) "${ms.toInt()} ms" else String.format(Locale.US, "%.1f ms", ms)
}

private fun formatTawcReportTime(atMs: Long): String {
    if (atMs <= 0L) return "—"
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(atMs))
    } catch (_: Exception) {
        "—"
    }
}
