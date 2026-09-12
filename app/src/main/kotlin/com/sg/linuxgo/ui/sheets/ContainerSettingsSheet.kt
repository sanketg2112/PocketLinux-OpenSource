package com.sg.linuxgo.ui.sheets

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.BuildConfig
import com.sg.linuxgo.MainActivity
import com.sg.linuxgo.Bootstrap
import com.sg.linuxgo.ContainerManager
import com.sg.linuxgo.ContainerConfig
import com.sg.linuxgo.FeatureGates
import com.sg.linuxgo.gui.DISPLAY_SCALE_PCT_MAX
import com.sg.linuxgo.gui.DISPLAY_SCALE_PCT_MIN
import com.sg.linuxgo.gui.readDisplayScalePct
import com.sg.linuxgo.gui.writeDisplayScalePct
import com.sg.linuxgo.ui.theme.*
import com.sg.linuxgo.util.getSafeBoolean
import com.sg.linuxgo.util.getSafeString
import java.io.File

/** Maturity tag shown under a GPU driver pill. */
private enum class GpuDriverTag { None, Stable, Beta }

private data class GpuDriverOption(
    val id: String,
    val label: String,
    val tag: GpuDriverTag = GpuDriverTag.None,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerSettingsSheet(
    containerId: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onViewLogs: (containerId: String) -> Unit = {},
    onSettingsSaved: () -> Unit,
    isDarkTheme: Boolean
) {
    val context = LocalContext.current
    val containerManager = remember { ContainerManager(context) }
    
    // Theme colors setup
    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) DividerDark else DividerLight
    val backgroundSheetColor = if (isDarkTheme) BackgroundDark else BackgroundLight
    val strokeColor = if (isDarkTheme) StrokeDark else DividerLight
    
    // Accent Color loading from pocketlinux settings
    val defaultPrefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    val accentHex = remember { defaultPrefs.getString("pocketlinux_accent", "#2196F3") ?: "#2196F3" }
    val accentColor = remember {
        try {
            Color(android.graphics.Color.parseColor(accentHex))
        } catch (e: Exception) {
            Magenta
        }
    }
    val mutedAccent = accentColor.copy(alpha = 0.78f)
    
    // Settings preference name setup
    val prefsName = remember(containerId) {
        if (containerId != null) "container_${containerId}_settings" else "pocket_linux_settings"
    }
    val prefs = remember(prefsName) { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }
    
    // Detect hardware / Vulkan support
    val isAccelSupported = remember { Bootstrap(context).isHardwareAccelSupported() }
    val hasVulkan11 = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 1) }
    val hasVulkan10 = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 0) }
    
    val vulkanSupportText = remember {
        when {
            hasVulkan11 -> "Supported (Vulkan 1.1+ detected)"
            hasVulkan10 -> "Partial (Vulkan 1.0 detected)"
            else -> "Not Supported (No Vulkan detected)"
        }
    }
    val vulkanSupportColor = remember {
        when {
            hasVulkan11 -> Color(0xFF4CAF50)
            hasVulkan10 -> Yellow
            else -> Red
        }
    }
    
    // Wayland follows Experimental settings.
    val waylandEnabled = remember { com.sg.linuxgo.FeatureGates.isWaylandEnabled(context) }
    
    // States from container preferences
    var setupName by remember {
        mutableStateOf(
            if (containerId != null) {
                containerManager.getContainer(containerId)?.name ?: prefs.getSafeString("setup_name", "") ?: ""
            } else {
                prefs.getSafeString("setup_name", "") ?: ""
            }
        )
    }
    
    var guiMode by remember {
        mutableStateOf(
            FeatureGates.effectiveGuiMode(
                context,
                if (waylandEnabled) {
                    prefs.getSafeString("gui_mode", null)
                        ?: containerId?.let { containerManager.getContainer(it)?.guiMode }
                } else {
                    "x11"
                }
            )
        )
    }
    val waylandSession = waylandEnabled && guiMode == "wayland"
    
    var orientation by remember { mutableStateOf(prefs.getSafeString("orientation", "native") ?: "native") }
    
    // Viewer (Lorie) scale — same prefs as Global → Display scale. Never writes guest DPI.
    var resScalePct by remember {
        mutableFloatStateOf(readDisplayScalePct(defaultPrefs).toFloat())
    }
    
    var gpuDriverMode by remember { mutableStateOf(prefs.getSafeString("gpu_driver_mode", "auto") ?: "auto") }
    // Badge tracks the selected driver (not a sticky pref): Auto/llvmpipe = software = DISABLED.
    // Hardware modes (Freedreno/Zink/…) show ENABLED when the device supports accel.
    // Do not gate the driver picker on this — otherwise Auto locks users out of HW picks.
    val waylandGfx = remember(waylandSession) {
        com.sg.linuxgo.TawcWaylandCompat.graphicsBackend(context)
    }
    val hwAccelActive = remember(gpuDriverMode, isAccelSupported, waylandSession, waylandGfx) {
        if (waylandSession) {
            waylandGfx != com.sg.linuxgo.TawcWaylandCompat.GFX_CPU
        } else {
            isAccelSupported && com.sg.linuxgo.bootstrap.isHardwareGpuDriverMode(gpuDriverMode)
        }
    }
    var bigScreenReady by remember { mutableStateOf(prefs.getSafeBoolean("big_screen_ready", false)) }
    var helpDialogInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    
    // Gpu options — Auto / llvmpipe are stable; hardware paths stay beta.
    val gpuDriverOptions = remember {
        listOf(
            GpuDriverOption("auto", "Auto (llvmpipe)", tag = GpuDriverTag.Stable),
            GpuDriverOption("adreno_freedreno", "Adreno: Freedreno KGSL", tag = GpuDriverTag.Beta),
            GpuDriverOption("adreno_zink", "Adreno: Zink + Turnip", tag = GpuDriverTag.Beta),
            GpuDriverOption("mali_panfrost", "Mali: Panfrost native", tag = GpuDriverTag.Beta),
            GpuDriverOption("mali_zink", "Mali: Zink + PanVK", tag = GpuDriverTag.Beta),
            GpuDriverOption("llvmpipe", "Software: llvmpipe", tag = GpuDriverTag.Stable),
        )
    }
    
    val visibleGpuDriverOptions = remember {
        val probe = listOf(
            android.os.Build.HARDWARE,
            android.os.Build.BOARD,
            android.os.Build.DEVICE,
            android.os.Build.PRODUCT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) android.os.Build.SOC_MANUFACTURER else "",
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) android.os.Build.SOC_MODEL else "",
            android.os.Build.MANUFACTURER
        ).joinToString(" ").lowercase()
        
        val family = when {
            probe.contains("qcom") || probe.contains("qualcomm") || probe.contains("adreno") || probe.contains("kalama") || probe.contains("lahaina") -> "adreno"
            probe.contains("mali") || probe.contains("mediatek") || probe.contains("mt") || probe.contains("exynos") -> "mali"
            else -> "unknown"
        }
        
        when (family) {
            "mali" -> gpuDriverOptions.filter { it.id in listOf("auto", "mali_panfrost", "mali_zink", "llvmpipe") }
            "adreno" -> gpuDriverOptions.filter { it.id in listOf("auto", "adreno_freedreno", "adreno_zink", "llvmpipe") }
            else -> gpuDriverOptions
        }
    }
    
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteConfirmInput by remember { mutableStateOf("") }
    
    // Save settings helper
    fun saveSettings(
        currentName: String,
        currentGui: String,
        currentOrient: String,
        currentScale: Float,
        currentDriver: String,
        currentBigScreen: Boolean = bigScreenReady
    ) {
        // Auto and llvmpipe are software; only explicit HW modes enable GPU bind / overlays.
        val hwWanted = isAccelSupported &&
            currentDriver != "llvmpipe" &&
            currentDriver != "auto" &&
            currentDriver.isNotBlank() &&
            com.sg.linuxgo.bootstrap.isHardwareGpuDriverMode(currentDriver)
        // commit() so setupDisplayConfig reads the mode we just chose (apply() races).
        prefs.edit()
            .putString("orientation", currentOrient)
            // Display scale is viewer-only (Lorie prefs) — do not store guest res_scale_pct.
            .putString("gui_mode", currentGui)
            .putString("gpu_driver_mode", currentDriver)
            .putString("setup_name", currentName)
            .putBoolean("big_screen_ready", currentBigScreen)
            .putBoolean("hw_accel", hwWanted)
            .commit()
        writeDisplayScalePct(defaultPrefs, currentScale.toInt())
        
        if (containerId != null) {
            val container = containerManager.getContainer(containerId)
            if (container != null) {
                if (container.name != currentName || container.guiMode != currentGui) {
                    containerManager.updateContainer(container.copy(
                        name = currentName,
                        guiMode = currentGui
                    ))
                    (context as? MainActivity)?.refreshContainers()
                }
                
                val bootstrap = Bootstrap(context)
                // Prefer updated name/guiMode so launch helpers match the setting just saved
                bootstrap.applyContainerPreset(
                    container.copy(name = currentName, guiMode = currentGui)
                )
                bootstrap.overrideGuiMode(currentGui)
                val rootfs = File(containerManager.getContainerRootfsPath(containerId))
                // Wayland uses Experimental graphics backend — don't rewrite Mesa GPU env.
                if (!FeatureGates.isContainerOnWayland(context, currentGui)) {
                    bootstrap.setupGpuEnvConfig(rootfs, currentDriver)
                }
                bootstrap.setupDisplayConfig(rootfs)
            }
        }
        onSettingsSaved()
    }
    
    // Auto-save on disposal
    DisposableEffect(Unit) {
        onDispose {
            saveSettings(setupName, guiMode, orientation, resScalePct, gpuDriverMode)
        }
    }
    
    if (helpDialogInfo != null) {
        AlertDialog(
            onDismissRequest = { helpDialogInfo = null },
            title = {
                Text(
                    text = helpDialogInfo!!.first,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    color = textPrimaryColor
                )
            },
            text = {
                Text(
                    text = helpDialogInfo!!.second,
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    color = textPrimaryColor
                )
            },
            confirmButton = {
                TextButton(onClick = { helpDialogInfo = null }) {
                    Text("OK", fontFamily = FontFamily.Default, color = accentColor)
                }
            },
            containerColor = backgroundSheetColor
        )
    }

    // Delete Confirmation Dialog Layout
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirm = false
                deleteConfirmInput = ""
            },
            title = {
                Text(
                    text = "Delete Container",
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    color = textPrimaryColor
                )
            },
            text = {
                Column {
                    Text(
                        text = "Are you sure you want to delete this container? This action is IRREVERSIBLE.\n\nPlease type 'delete' to confirm.",
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                        color = textPrimaryColor,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OutlinedTextField(
                        value = deleteConfirmInput,
                        onValueChange = { deleteConfirmInput = it },
                        placeholder = { Text("Type 'delete' to confirm", fontFamily = FontFamily.Default) },
                        textStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, color = textPrimaryColor),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Red,
                            unfocusedBorderColor = strokeColor
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val isEnabled = deleteConfirmInput.trim().lowercase() == "delete"
                Button(
                    onClick = {
                        // Parent performs a single remove + UI refresh (do not delete here —
                        // a second removeContainer raced deleteRecursively and crashed the app).
                        showDeleteConfirm = false
                        onDelete()
                        onDismiss()
                    },
                    enabled = isEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Red,
                        disabledContainerColor = Red.copy(alpha = 0.5f)
                    )
                ) {
                    Text("DELETE", fontFamily = FontFamily.Default, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        deleteConfirmInput = ""
                    }
                ) {
                    Text("Cancel", fontFamily = FontFamily.Default, color = textSecondaryColor)
                }
            },
            containerColor = backgroundSheetColor,
            shape = RoundedCornerShape(8.dp)
        )
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = backgroundSheetColor,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .heightIn(min = 280.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Drag handle to match XML custom grabber
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(dividerColor, shape = RoundedCornerShape(2.dp))
                    .align(Alignment.CenterHorizontally)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Sheet title
            Text(
                text = "CONTAINER SETTINGS",
                fontFamily = FontFamily.Default,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondaryColor,
                letterSpacing = 0.05.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            
            // OutlinedTextField for Display Name
            OutlinedTextField(
                value = setupName,
                onValueChange = { setupName = it },
                label = { Text("Setup Display Name", fontFamily = FontFamily.Default) },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    color = textPrimaryColor
                ),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = accentColor,
                    focusedLabelColor = accentColor,
                    unfocusedLabelColor = accentColor,
                    focusedTextColor = textPrimaryColor,
                    unfocusedTextColor = textPrimaryColor
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
            )
            
            HorizontalDivider(thickness = 1.dp, color = dividerColor, modifier = Modifier.padding(bottom = 12.dp))
            
            // SECTION: OUTPUT Header
            Text(
                text = "OUTPUT",
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                letterSpacing = 0.1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // GUI Mode Toggle Group
            if (waylandEnabled) {
                Column(
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    Text(
                        text = "GUI_MODE",
                        fontFamily = FontFamily.Default,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = textSecondaryColor,
                        letterSpacing = 0.05.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    SegmentedToggleButtonGroup(
                        options = listOf("x11", "wayland"),
                        selectedOption = guiMode,
                        onOptionSelected = {
                            guiMode = it
                            saveSettings(setupName, it, orientation, resScalePct, gpuDriverMode)
                        },
                        labelProvider = { opt -> if (opt == "wayland") "Wayland" else "Native X11" },
                        accentColor = mutedAccent,
                        isDarkTheme = isDarkTheme
                    )
                }
            }
            
            // Screen Orientation Toggle Group
            Column(
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                Text(
                    text = "SCREEN_ORIENTATION",
                    fontFamily = FontFamily.Default,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = textSecondaryColor,
                    letterSpacing = 0.05.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                SegmentedToggleButtonGroup(
                    options = listOf("native", "landscape", "portrait"),
                    selectedOption = orientation,
                    onOptionSelected = {
                        orientation = it
                        saveSettings(setupName, guiMode, it, resScalePct, gpuDriverMode)
                    },
                    labelProvider = { opt -> opt },
                    accentColor = mutedAccent,
                    isDarkTheme = isDarkTheme
                )
            }
            
            // Viewer scale (Termux X11 style): resize X11 framebuffer only — no guest DPI.
            Column(
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DISPLAY_SCALE",
                            fontFamily = FontFamily.Default,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = textSecondaryColor,
                            letterSpacing = 0.05.sp
                        )
                        Text(
                            text = "Viewer only — whole desktop zoom (does not change Linux DPI)",
                            fontFamily = FontFamily.Default,
                            fontSize = 10.sp,
                            color = textSecondaryColor
                        )
                    }
                    Text(
                        text = "${resScalePct.toInt()}%",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimaryColor
                    )
                }
                val scaleView = LocalView.current
                var lastScaleInt by remember { mutableIntStateOf(resScalePct.toInt()) }

                Slider(
                    value = resScalePct,
                    onValueChange = { newVal ->
                        if (newVal.toInt() != lastScaleInt) {
                            lastScaleInt = newVal.toInt()
                            scaleView.performClickHaptic()
                        }
                        resScalePct = newVal
                    },
                    onValueChangeFinished = {
                        scaleView.performClickHaptic()
                        saveSettings(setupName, guiMode, orientation, resScalePct, gpuDriverMode)
                    },
                    valueRange = DISPLAY_SCALE_PCT_MIN.toFloat()..DISPLAY_SCALE_PCT_MAX.toFloat(),
                    // ~5% steps across 30–300
                    steps = 53,
                    colors = SliderDefaults.colors(
                        thumbColor = mutedAccent,
                        activeTrackColor = mutedAccent,
                        inactiveTrackColor = Color(0xFF1E1E24),
                        activeTickColor = mutedAccent.copy(alpha = 0.5f),
                        inactiveTickColor = Color(0xFF3E3E48)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Hardware Acceleration status (derived from GPU driver — not a separate toggle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Hardware Acceleration",
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                        color = textPrimaryColor
                    )
                    Text(
                        text = if (waylandSession) {
                            when (waylandGfx) {
                                com.sg.linuxgo.TawcWaylandCompat.GFX_HYBRIS ->
                                    "Wayland · libhybris (stock Android GPU)"
                                com.sg.linuxgo.TawcWaylandCompat.GFX_CPU ->
                                    "Wayland · CPU (llvmpipe)"
                                else -> "Wayland · Mesa"
                            }
                        } else {
                            vulkanSupportText
                        },
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = when {
                            waylandSession && hwAccelActive -> Color(0xFF4CAF50)
                            !isAccelSupported -> vulkanSupportColor
                            hwAccelActive -> Color(0xFF4CAF50)
                            else -> vulkanSupportColor
                        }
                    )
                }
                
                val badgeBg = if (hwAccelActive) mutedAccent else (if (isDarkTheme) Color(0xFF27272D) else Color(0xFFE5E5EA))
                val badgeTextColor = if (hwAccelActive) Color.White else (if (isDarkTheme) Color(0xFF8E8E93) else Color(0xFF555555))
                
                Box(
                    modifier = Modifier
                        .background(badgeBg, shape = RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (hwAccelActive) "ENABLED" else "DISABLED",
                        fontFamily = FontFamily.Default,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor
                    )
                }
            }
            
            // GPU Driver Selection — always choosable when device can accelerate
            // (or always for software modes). Must not require hw_accel first.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .alpha(
                        when {
                            waylandSession -> 0.45f
                            isAccelSupported -> 1f
                            else -> 0.85f
                        }
                    )
            ) {
                Text(
                    text = "GPU_DRIVER",
                    fontFamily = FontFamily.Default,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = textSecondaryColor,
                    letterSpacing = 0.05.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (waylandSession) {
                    Text(
                        text = "Disabled while this container is on Wayland. " +
                            "Use Experimental → Graphics backend (libhybris / Mesa / CPU).",
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = textSecondaryColor,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                PillSelectionGroup(
                    labels = visibleGpuDriverOptions.map { it.label },
                    values = visibleGpuDriverOptions.map { it.id },
                    selectedValue = gpuDriverMode,
                    enabled = !waylandSession,
                    accentColor = mutedAccent,
                    isDarkTheme = isDarkTheme,
                    tagByValue = visibleGpuDriverOptions.associate { opt ->
                        opt.id to when (opt.tag) {
                            GpuDriverTag.Stable -> "STABLE"
                            GpuDriverTag.Beta -> "BETA"
                            GpuDriverTag.None -> null
                        }
                    },
                    onValueChange = { value ->
                        gpuDriverMode = value
                        saveSettings(setupName, guiMode, orientation, resScalePct, value)
                    },
                )
            }
            
            // Big screen (scrcpy) — landscape-friendly sessions for PC
            SwitchSettingRow(
                title = "Big screen ready",
                summary = "Landscape + keep-awake friendly when this desktop starts",
                description = "For scrcpy / PC use. Full ADB and scrcpy setup is under Settings → Big screen (scrcpy).",
                checked = bigScreenReady,
                onCheckedChange = {
                    bigScreenReady = it
                    saveSettings(setupName, guiMode, orientation, resScalePct, gpuDriverMode, it)
                },
                onInfoClick = {
                    helpDialogInfo = "Big screen ready" to
                        "When this container’s desktop starts, PocketLinux prefers landscape if orientation is “native”, and keeps the screen on for scrcpy. " +
                        "You still install official scrcpy and ADB on your computer. Open Settings → Big screen (scrcpy) for drivers, install, USB, and Wi‑Fi steps."
                },
                accentColor = mutedAccent,
                isDarkTheme = isDarkTheme
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Note Text
            Text(
                text = "Note: The default user account is 'root' or the custom username you configured. Default system passwords match their respective usernames (e.g., username: root, password: root). Use these credentials if prompted by screen locks or authentication screens.",
                fontFamily = FontFamily.Default,
                fontSize = 10.sp,
                color = textSecondaryColor,
                lineHeight = 14.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            
            if (containerId != null) {
                HorizontalDivider(thickness = 1.dp, color = dividerColor, modifier = Modifier.padding(bottom = 14.dp))

                // View logs — debug builds only (release hides install log surface)
                if (FeatureGates.installLogsVisible()) {
                    OutlinedButton(
                        onClick = {
                            val cid = containerId
                            onDismiss()
                            if (cid != null) onViewLogs(cid)
                        },
                        border = BorderStroke(1.dp, strokeColor),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = textPrimaryColor
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                    ) {
                        Text(
                            text = "VIEW LOGS",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.05.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Delete container button
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD05252).copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                ) {
                    Text(
                        text = "DELETE CONTAINER",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.05.sp
                    )
                }
            }
        }
    }
}

@Composable
fun <T> SegmentedToggleButtonGroup(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    labelProvider: (T) -> String,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    val strokeColor = if (isDarkTheme) StrokeDark else DividerLight
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val unselectedBg = if (isDarkTheme) Color(0xFF0F0F12) else BackgroundLightCard
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .border(1.dp, strokeColor, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selectedOption
            val shape = when {
                options.size == 1 -> RoundedCornerShape(8.dp)
                index == 0 -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                index == options.size - 1 -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                else -> RoundedCornerShape(0.dp)
            }
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (isSelected) accentColor.copy(alpha = 0.82f) else unselectedBg,
                        shape = shape
                    )
                    .clickable { onOptionSelected(option) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = labelProvider(option).uppercase(),
                    fontFamily = FontFamily.Default,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color.White else textSecondaryColor
                )
            }
            
            if (index < options.size - 1) {
                Spacer(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(strokeColor)
                )
            }
        }
    }
}

@Composable
fun PillSelectionGroup(
    labels: List<String>,
    values: List<String>,
    selectedValue: String,
    onValueChange: (String) -> Unit,
    accentColor: Color,
    isDarkTheme: Boolean,
    enabled: Boolean = true,
    /**
     * Optional maturity tag per option id (e.g. "STABLE", "BETA").
     * When any tag is present, all pills use a shared min height so rows stay even.
     */
    tagByValue: Map<String, String?> = emptyMap(),
    pillsPerRow: Int = 2,
) {
    val strokeColor = if (isDarkTheme) StrokeDark else DividerLight
    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val unselectedBg = if (isDarkTheme) Color(0xFF111115) else BackgroundLightCard
    val view = LocalView.current
    val hasAnyTag = tagByValue.values.any { !it.isNullOrBlank() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.zip(values).chunked(pillsPerRow.coerceAtLeast(1)).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowItems.forEach { (label, value) ->
                    val selected = value == selectedValue
                    val tag = tagByValue[value]
                    val tagColor = when (tag?.uppercase()) {
                        "STABLE" -> accentColor
                        "BETA" -> Yellow
                        else -> textSecondaryColor
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = if (hasAnyTag) 54.dp else 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) accentColor.copy(alpha = 0.16f) else unselectedBg)
                            .border(
                                1.dp,
                                if (selected) accentColor.copy(alpha = 0.65f) else strokeColor,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable(enabled = enabled) {
                                view.performClickHaptic()
                                onValueChange(value)
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = label,
                                fontFamily = FontFamily.Default,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) textPrimaryColor else textSecondaryColor,
                                maxLines = 2,
                                lineHeight = 13.sp,
                            )
                            if (!tag.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                    text = tag.uppercase(),
                                    fontFamily = FontFamily.Default,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.08.sp,
                                    color = tagColor,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(tagColor.copy(alpha = 0.16f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
