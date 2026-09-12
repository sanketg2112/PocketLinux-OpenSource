package com.sg.linuxgo.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.sg.linuxgo.ui.utils.performClickHaptic
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.theme.*

@Composable
internal fun StepIndicator(
    currentStep: Int,
    stepsCount: Int = 3
) {
    val accent = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until stepsCount) {
            val isActive = i <= currentStep
            val isCurrent = i == currentStep
            
            // Circle
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isActive) accent else inactive)
                    .border(
                        width = 1.dp,
                        color = if (isCurrent) accent else Color.Transparent,
                        shape = RoundedCornerShape(50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (i + 1).toString(),
                    color = if (isActive) TextWhite else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default
                )
            }
            
            // Connecting line
            if (i < stepsCount - 1) {
                val isLineActive = i < currentStep
                Spacer(
                    modifier = Modifier
                        .width(40.dp)
                        .height(2.dp)
                        .background(if (isLineActive) accent else inactive)
                )
            }
        }
    }
}

@Composable
internal fun Step1Content(
    selectedDistro: String,
    onDistroSelected: (String) -> Unit,
    selectedDE: String,
    onDESelected: (String) -> Unit,
    availableDistros: List<DistroOption>,
    filteredDesktops: List<DEOption>,
    selectedWM: String,
    onWMSelected: (String) -> Unit,
    filteredWMs: List<DEOption>,
    selectedGuiMode: String,
    onGuiModeSelected: (String) -> Unit,
    infoTitle: String,
    infoText: String,
    infoColor: Color,
    toggleHidden: Boolean,
    showWindowManagers: Boolean = false,
    catalogLoading: Boolean = false,
    catalogError: String? = null,
    catalogMode: Boolean = false,
    recommendedDistroId: String = "debian",
    onRetryCatalog: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        if (catalogLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Loading published images…",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default
                )
            }
        }

        if (catalogError != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A1515))
                    .border(BorderStroke(1.dp, Red), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Could not load image catalog",
                    color = Red,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = catalogError,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = onRetryCatalog) {
                    Text("Retry", color = Magenta)
                }
            }
        }

        // DISTRIBUTION
        Text(
            text = "DISTRIBUTION",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Default,
            letterSpacing = 0.05.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (availableDistros.isEmpty() && !catalogLoading) {
            Text(
                text = "No published distributions yet. Try again later.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        // Always show picker sections (recommended row / other / coming soon) once not loading.
        if (!catalogLoading) {
            val (freeDistros, otherDistros) = splitDistrosForPicker(availableDistros)
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                if (freeDistros.isNotEmpty()) {
                    DistroOptionGrid(
                        distros = freeDistros,
                        selectedDistro = selectedDistro,
                        onDistroSelected = onDistroSelected,
                        toggleHidden = toggleHidden,
                        recommendedDistroId = recommendedDistroId,
                    )
                }
                val publishedIds = availableDistros.map { it.id }.toSet()
                val soonCards = if (catalogMode) {
                    comingSoonCardsForCatalog(publishedIds)
                } else {
                    emptyList()
                }
                val soonIds = soonCards.map { it.id }.toSet()
                if (otherDistros.isNotEmpty() || soonCards.isNotEmpty()) {
                    OtherDistroSectionHeader(
                        modifier = Modifier.padding(
                            top = if (freeDistros.isNotEmpty()) 16.dp else 0.dp,
                            bottom = 10.dp,
                        ),
                    )
                    DistroOptionGrid(
                        distros = otherDistros + soonCards,
                        selectedDistro = selectedDistro,
                        onDistroSelected = onDistroSelected,
                        toggleHidden = toggleHidden,
                        recommendedDistroId = recommendedDistroId,
                        comingSoonIds = soonIds,
                    )
                }
            }
        }

        // DESKTOP ENVIRONMENT
        Text(
            text = "DESKTOP ENVIRONMENT",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Default,
            letterSpacing = 0.05.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (filteredDesktops.isEmpty() && !catalogLoading) {
            Text(
                text = "No desktop image published for this distro yet.",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                val chunked = filteredDesktops.chunked(2)
                chunked.forEachIndexed { rowIndex, rowItems ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        rowItems.forEachIndexed { colIndex, de ->
                            DECard(
                                de = de,
                                isSelected = selectedDE == de.id,
                                onClick = { onDESelected(de.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(
                                        end = if (colIndex == 0) 4.dp else 0.dp,
                                        start = if (colIndex == 1) 4.dp else 0.dp
                                    )
                            )
                        }
                        if (rowItems.size < 2) {
                            Spacer(modifier = Modifier.weight(1f).padding(start = 4.dp))
                        }
                    }
                    if (rowIndex < chunked.size - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
        
        // WM Section (advanced/legacy only; shown when selectedDE is lxqt or none)
        if (showWindowManagers && (selectedDE == "lxqt" || selectedDE == "none")) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "WINDOW MANAGER",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Default,
                letterSpacing = 0.05.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Row(modifier = Modifier.fillMaxWidth()) {
                filteredWMs.forEachIndexed { index, wm ->
                    val showLogo = selectedDE != "lxqt"
                    val desc = if (selectedDE == "lxqt") {
                        when (wm.id) {
                            "openbox" -> "Minimal"
                            "awesome" -> "Configurable"
                            "i3" -> "Tiling"
                            "ubuntu-wm" -> "Native"
                            else -> wm.desc
                        }
                    } else {
                        wm.desc
                    }
                    DECard(
                        de = wm.copy(desc = desc),
                        isSelected = selectedWM == wm.id,
                        onClick = { onWMSelected(wm.id) },
                        showLogo = showLogo,
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                end = if (index < filteredWMs.size - 1) 4.dp else 0.dp
                            )
                    )
                }
            }
        }
        
        val context = LocalContext.current
        val waylandEnabled = remember(context) {
            com.sg.linuxgo.FeatureGates.isWaylandEnabled(context)
        }

        if (waylandEnabled) {
            // GUI SERVER
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "GUI SERVER",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Default,
                letterSpacing = 0.05.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Row(modifier = Modifier.fillMaxWidth()) {
                val x11Option = DEOption("x11", "X11 Server", "Classic • Stable", 0, Color(0xFF9C27B0))
                val waylandOption = DEOption("wayland", "Wayland Compositor", "Modern • Experimental", 0, Color(0xFF00BCD4))
                
                DECard(
                    de = x11Option,
                    isSelected = selectedGuiMode == "x11",
                    onClick = { onGuiModeSelected("x11") },
                    showLogo = false,
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                )
                DECard(
                    de = waylandOption,
                    isSelected = selectedGuiMode == "wayland",
                    onClick = { onGuiModeSelected("wayland") },
                    showLogo = false,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
            }
        }
        
        // Info section — only when there is something useful to show (legacy / errors)
        if (infoTitle.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 20.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = infoTitle,
                        color = infoColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Default,
                        letterSpacing = 0.05.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = infoText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Default
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/** 2-column grid of distro cards. */
@Composable
private fun DistroOptionGrid(
    distros: List<DistroOption>,
    selectedDistro: String,
    onDistroSelected: (String) -> Unit,
    toggleHidden: Boolean,
    recommendedDistroId: String,
    comingSoonIds: Set<String> = emptySet(),
) {
    val rows = distros.chunked(2)
    rows.forEachIndexed { rowIndex, rowItems ->
        Row(modifier = Modifier.fillMaxWidth()) {
            rowItems.forEachIndexed { colIndex, distro ->
                val soon = distro.id in comingSoonIds
                DistroCard(
                    distro,
                    isSelected = !soon && selectedDistro == distro.id,
                    onClick = { if (!soon) onDistroSelected(distro.id) },
                    toggleHidden = toggleHidden,
                    recommendedDistroId = recommendedDistroId,
                    isComingSoon = soon,
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            end = if (colIndex == 0 && rowItems.size > 1) 4.dp else 0.dp,
                            start = if (colIndex == 1) 4.dp else 0.dp,
                        ),
                )
            }
            if (rowItems.size < 2) {
                Spacer(modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
        }
        if (rowIndex < rows.size - 1) {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Left-aligned MORE label + muted trailing rule between Alpine/Debian and other distros. */
@Composable
private fun OtherDistroSectionHeader(modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "MORE",
            color = labelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Default,
            letterSpacing = 0.08.sp,
        )
        Spacer(modifier = Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = lineColor,
        )
    }
}

@Composable
internal fun DistroCard(
    distro: DistroOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    toggleHidden: Boolean,
    modifier: Modifier = Modifier,
    recommendedDistroId: String = "debian",
    isComingSoon: Boolean = false,
) {
    val alphaVal = if (isComingSoon) 0.48f else 1f

    val strokeW = if (isSelected) 2.dp else 1.dp
    val strokeC = if (isSelected) distro.color else MaterialTheme.colorScheme.outline
    val bgC = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface

    val view = LocalView.current

    val badgeText = if (isComingSoon) {
        "SOON"
    } else {
        distroCardBadgeLabel(distro.id, recommendedDistroId)
    }
    val badgeBg = distroCardBadgeColor(badgeText)
    val badgeFont = if (badgeText == "RECOMMENDED") 6.sp else 7.sp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgC)
            .border(BorderStroke(strokeW, strokeC), RoundedCornerShape(12.dp))
            .clickable(enabled = !isComingSoon, onClick = {
                view.performClickHaptic()
                onClick()
            })
            .height(72.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alphaVal)
                // Top padding clears the corner badge; fixed icon keeps left/right text width equal.
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(distro.iconRes),
                contentDescription = distro.name,
                modifier = Modifier.size(34.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = distro.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = distro.desc,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Default,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(
                    badgeBg.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(topEnd = 10.dp, bottomStart = 5.dp),
                )
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                text = badgeText,
                color = TextWhite,
                fontSize = badgeFont,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Default,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun DECard(
    de: DEOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLogo: Boolean = true,
) {
    val strokeW = if (isSelected) 2.dp else 1.dp
    val strokeC = if (isSelected) de.color else MaterialTheme.colorScheme.outline
    val bgC = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    
    val badgeText = when {
        de.id == "xfce4" -> "STABLE"
        de.id in listOf("mate", "lxqt", "kde", "ubuntu-de") -> "LEGACY"
        else -> null
    }

    val view = LocalView.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgC)
            .border(BorderStroke(strokeW, strokeC), RoundedCornerShape(12.dp))
            .clickable(onClick = {
                view.performClickHaptic()
                onClick()
            })
            .height(72.dp)
    ) {
        val startPadding = if (showLogo) 6.dp else 10.dp
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = startPadding, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showLogo) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(de.iconRes),
                        contentDescription = de.name,
                        modifier = Modifier.fillMaxSize(0.72f)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))
            }

            val endPadding = if (badgeText != null) 36.dp else 4.dp

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = endPadding),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = de.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default,
                    maxLines = 1
                )
                Text(
                    text = de.desc,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Default,
                    maxLines = 1
                )
            }
        }
        
        val badgeBg = when {
            de.id == "xfce4" -> Color(0xFF2E7D32)
            de.id in listOf("mate", "lxqt", "kde", "ubuntu-de") -> Color(0xFFE65100)
            else -> Color.Transparent
        }
        
        if (badgeText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(badgeBg.copy(alpha = 0.86f), shape = RoundedCornerShape(topEnd = 10.dp, bottomStart = 5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = badgeText,
                    color = TextWhite,
                    fontSize = 5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default
                )
            }
        }
    }
}
