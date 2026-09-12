package com.sg.linuxgo.ui.sheets

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.sg.linuxgo.ui.utils.performClickHaptic
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sg.linuxgo.ContainerConfig
import com.sg.linuxgo.ContainerImageCatalog
import com.sg.linuxgo.ContainerManager
import com.sg.linuxgo.InstallCardProgress
import com.sg.linuxgo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NewContainerSheet(
    onContainerCreated: (ContainerConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val defaultPrefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    val waylandEnabled = remember { com.sg.linuxgo.FeatureGates.isWaylandEnabled(context) }
    val toggleHidden = remember { defaultPrefs.getBoolean("togglehidden", false) }
    // Debug Settings → Developer Override can toggle legacy package install (debug only)
    val legacyPackageInstall = remember {
        com.sg.linuxgo.FeatureGates.legacyPackageInstallAllowed() &&
            defaultPrefs.getBoolean(PREF_LEGACY_PACKAGE_INSTALL, false)
    }
    // Advanced wizard (step 2 + extra DEs/WMs) only with legacy package install
    val showAdvancedWizard = legacyPackageInstall

    var currentStep by remember { mutableStateOf(0) }
    val maxStep = if (showAdvancedWizard) 1 else 0

    // RAM-based default: Alpine for ~4–6 GB phones, Debian for 6 GB+
    val totalRamMb = remember(context) { deviceTotalRamMb(context) }
    val recommendedDistro = remember(totalRamMb) {
        recommendedDistroIdForTotalRamMb(totalRamMb)
    }

    var selectedDistro by remember { mutableStateOf(recommendedDistro) }
    var selectedDE by remember { mutableStateOf("xfce4") }
    var selectedWM by remember { mutableStateOf("none") }
    var containerName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("PocketLinux") }
    var installRecommends by remember { mutableStateOf(false) }
    var selectedGuiMode by remember {
        mutableStateOf(com.sg.linuxgo.FeatureGates.defaultGuiMode(context))
    }

    // Catalog-driven options for prebuilt install (what you published to GitHub)
    var catalogState by remember {
        mutableStateOf<CatalogUiState>(
            if (showAdvancedWizard) CatalogUiState.Ready(
                ContainerImageCatalog.Manifest(1, null, emptyList(), null, emptySet())
            ) else CatalogUiState.Loading
        )
    }
    var catalogReloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(showAdvancedWizard, catalogReloadToken) {
        if (showAdvancedWizard) return@LaunchedEffect
        catalogState = CatalogUiState.Loading
        catalogState = withContext(Dispatchers.IO) {
            try {
                CatalogUiState.Ready(ContainerImageCatalog.fetch())
            } catch (e: Exception) {
                CatalogUiState.Error(e.message ?: "Could not load image catalog")
            }
        }
    }

    // When catalog loads, keep RAM recommendation when published; else fall back safely
    LaunchedEffect(catalogState, recommendedDistro) {
        val ready = catalogState as? CatalogUiState.Ready ?: return@LaunchedEffect
        if (showAdvancedWizard) return@LaunchedEffect
        val distroIds = ContainerImageCatalog.availableDistroIds(ready.manifest)
        if (distroIds.isEmpty()) return@LaunchedEffect
        if (selectedDistro !in distroIds) {
            selectedDistro = pickDefaultDistroId(distroIds, recommendedDistro)
        }
        val deIds = ContainerImageCatalog.availableDesktopIds(ready.manifest, selectedDistro)
        if (deIds.isNotEmpty() && selectedDE !in deIds) {
            selectedDE = deIds.first()
            selectedWM = "none"
        }
    }

    val availableDistroOptions = remember(catalogState, showAdvancedWizard) {
        if (showAdvancedWizard) {
            // Legacy package install: published distros plus unpublished preview ids
            orderDistrosForPicker(legacyPackageInstallDistros)
        } else {
            val ready = catalogState as? CatalogUiState.Ready
            val ids = ready?.let { ContainerImageCatalog.availableDistroIds(it.manifest) }.orEmpty()
            // Alpine | Debian on first row; other catalog distros below
            catalogInstallableDistros(ids)
        }
    }

    val filteredDesktops = remember(selectedDistro, showAdvancedWizard, catalogState) {
        if (showAdvancedWizard) {
            if (selectedDistro == "ubuntu") {
                desktops.filter { it.id != "kde" && it.id != "hyprland" }
            } else {
                desktops.filter { it.id != "ubuntu-de" && it.id != "hyprland" }
            }
        } else {
            val ready = catalogState as? CatalogUiState.Ready
            val ids = ready?.let {
                ContainerImageCatalog.availableDesktopIds(it.manifest, selectedDistro)
            }.orEmpty()
            desktops.filter { it.id in ids }
        }
    }

    val filteredWMs = remember(selectedDistro, showAdvancedWizard) {
        if (!showAdvancedWizard) emptyList()
        else windowManagers.filter {
            if (it.id == "ubuntu-wm") selectedDistro == "ubuntu" else true
        }
    }

    val onDistroSelected = { distroId: String ->
        selectedDistro = distroId
        if (showAdvancedWizard) {
            if (distroId == "ubuntu") {
                selectedDE = "ubuntu-de"
                selectedWM = "none"
            } else if (selectedDE == "ubuntu-de" || selectedDE == "hyprland" || selectedWM == "ubuntu-wm") {
                selectedDE = "xfce4"
                selectedWM = "none"
            }
        } else {
            // Prebuilt: pick first published DE for this distro
            val ready = catalogState as? CatalogUiState.Ready
            val deIds = ready?.let {
                ContainerImageCatalog.availableDesktopIds(it.manifest, distroId)
            }.orEmpty()
            selectedDE = deIds.firstOrNull() ?: "xfce4"
            selectedWM = "none"
        }
    }

    // Prebuilt path: RAM recommendation card in free space under the pickers.
    // Errors still take priority; legacy wizard keeps DE/WM blurbs.
    val infoTitleAndTextAndColor = remember(
        selectedDE,
        selectedWM,
        legacyPackageInstall,
        catalogState,
        availableDistroOptions,
        recommendedDistro,
    ) {
        val selectedOption = (desktops + windowManagers).find {
            it.id == (if (selectedWM != "none" && (selectedDE == "none" || selectedDE == "lxqt")) selectedWM else selectedDE)
        }
        when {
            !legacyPackageInstall && catalogState is CatalogUiState.Error -> {
                Triple(
                    "UNAVAILABLE",
                    (catalogState as CatalogUiState.Error).message,
                    Red
                )
            }
            !legacyPackageInstall &&
                catalogState is CatalogUiState.Ready &&
                availableDistroOptions.isEmpty() -> {
                Triple(
                    "UNAVAILABLE",
                    "No container images available right now. Please try again later.",
                    Red
                )
            }
            // Prebuilt: recommend distro only (RAM used for pick, not shown)
            !legacyPackageInstall -> {
                distroRamRecommendationCopy(recommendedDistro)
            }
            selectedOption != null -> {
                Triple(selectedOption.name.uppercase(), selectedOption.desc, selectedOption.color)
            }
            else -> {
                Triple(
                    "ENVIRONMENT INFO",
                    "Select an environment to see details.",
                    TextSecondary
                )
            }
        }
    }

    val containerManager = remember { ContainerManager(context) }

    val canInstall = showAdvancedWizard || (
        catalogState is CatalogUiState.Ready &&
            availableDistroOptions.isNotEmpty() &&
            filteredDesktops.isNotEmpty()
        )

    // Prebuilt catalog path: resolve download size for the selected distro + DE.
    val downloadSizeLabel = remember(catalogState, selectedDistro, selectedDE, showAdvancedWizard) {
        if (showAdvancedWizard) return@remember null
        val ready = catalogState as? CatalogUiState.Ready ?: return@remember null
        try {
            val image = ContainerImageCatalog.selectImage(
                ready.manifest,
                preferredDistro = selectedDistro,
                preferredDesktop = selectedDE
            )
            if (image.sizeBytes > 0) {
                InstallCardProgress.formatDownloadSize(image.sizeBytes, 0L)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    val installContainer = {
        if (!showAdvancedWizard && !canInstall) {
            Toast.makeText(
                context,
                "No published image for this selection. Try again later.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            val customName = if (showAdvancedWizard) {
                containerName.trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val finalUsername = if (showAdvancedWizard) {
                username.trim().takeIf { it.isNotEmpty() } ?: "PocketLinux"
            } else {
                "PocketLinux"
            }
            // Prebuilt: use selected distro/DE from catalog so Bootstrap fetches the right image
            val de = selectedDE
            val wm = if (showAdvancedWizard) selectedWM else "none"
            val recommends = if (showAdvancedWizard) installRecommends else false

            val config = containerManager.createNewContainer(
                distro = selectedDistro,
                de = de,
                wm = wm,
                software = emptyList(),
                username = finalUsername,
                customName = customName,
                installRecommends = recommends,
                guiMode = if (waylandEnabled) selectedGuiMode else "x11"
            )

            if (containerManager.addContainer(config)) {
                onContainerCreated(config)
                onDismiss()
            } else {
                Toast.makeText(context, "Maximum environments reached (20)", Toast.LENGTH_LONG).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(
                        text = "New environment",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Default,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = when {
                            showAdvancedWizard && currentStep == 1 -> "Name and user settings"
                            legacyPackageInstall -> "Legacy package install enabled"
                            else -> "Select your Linux environment"
                        },
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Default
                    )
                }

                // Only show step dots when advanced multi-step wizard is active
                if (showAdvancedWizard) {
                    StepIndicator(currentStep = currentStep, stepsCount = 2)
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (currentStep) {
                        0 -> Step1Content(
                            selectedDistro = selectedDistro,
                            onDistroSelected = onDistroSelected,
                            selectedDE = selectedDE,
                            onDESelected = { deId ->
                                if (selectedDE != deId) {
                                    selectedDE = deId
                                    if (selectedDE == "lxqt") {
                                        selectedWM = "openbox"
                                    } else {
                                        selectedWM = "none"
                                    }
                                }
                            },
                            availableDistros = availableDistroOptions,
                            filteredDesktops = filteredDesktops,
                            selectedWM = selectedWM,
                            onWMSelected = { wmId ->
                                selectedWM = if (selectedWM == wmId) "none" else wmId
                                if (selectedWM != "none") {
                                    if (selectedDE != "lxqt" && selectedDE != "none") {
                                        selectedDE = "none"
                                    }
                                }
                                if (selectedWM == "none" && selectedDE == "none") {
                                    selectedWM = wmId
                                }
                            },
                            filteredWMs = filteredWMs,
                            selectedGuiMode = selectedGuiMode,
                            onGuiModeSelected = { selectedGuiMode = it },
                            infoTitle = infoTitleAndTextAndColor.first,
                            infoText = infoTitleAndTextAndColor.second,
                            infoColor = infoTitleAndTextAndColor.third,
                            toggleHidden = toggleHidden,
                            showWindowManagers = showAdvancedWizard,
                            catalogLoading = !showAdvancedWizard && catalogState is CatalogUiState.Loading,
                            catalogError = (catalogState as? CatalogUiState.Error)?.message,
                            catalogMode = !showAdvancedWizard,
                            recommendedDistroId = recommendedDistro,
                            onRetryCatalog = {
                                if (!showAdvancedWizard) catalogReloadToken++
                            }
                        )
                        1 -> if (showAdvancedWizard) {
                            Step2Content(
                                containerName = containerName,
                                onContainerNameChanged = { containerName = it },
                                username = username,
                                onUsernameChanged = { username = it },
                                installRecommends = installRecommends,
                                onInstallRecommendsChanged = { installRecommends = it },
                                distroColor = Color(ContainerConfig.distroColor(selectedDistro))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Square dismiss / back control on the left
                    if (currentStep == 0) {
                        OutlinedButton(
                            onClick = {
                                view.performClickHaptic()
                                onDismiss()
                            },
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp),
                            border = BorderStroke(1.dp, Red),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                view.performClickHaptic()
                                currentStep -= 1
                            },
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp),
                            border = BorderStroke(1.dp, StrokeDark),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    val primaryLabel = when {
                        currentStep < maxStep -> "Next"
                        downloadSizeLabel != null -> "Download & install ($downloadSizeLabel)"
                        !showAdvancedWizard -> "Download & install"
                        else -> "Install"
                    }

                    Button(
                        onClick = {
                            view.performClickHaptic()
                            if (currentStep < maxStep) {
                                currentStep += 1
                            } else {
                                installContainer()
                            }
                        },
                        enabled = currentStep < maxStep || canInstall,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = TextWhite
                        )
                    ) {
                        Text(
                            text = primaryLabel,
                            fontSize = if (downloadSizeLabel != null && currentStep >= maxStep) 12.sp else 13.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

