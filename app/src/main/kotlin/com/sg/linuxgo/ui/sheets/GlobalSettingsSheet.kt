package com.sg.linuxgo.ui.sheets

import android.content.Intent
import androidx.preference.PreferenceManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.sg.linuxgo.ui.utils.performClickHaptic
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.R
import com.sg.linuxgo.ui.theme.*

/**
 * Full-page section settings (Output / Keyboard / Pointer / Other).
 * Named sheet historically; presented as a dedicated page like ExperimentalSettingsScreen.
 */
@Composable
fun GlobalSettingsSheet(
    onDismiss: () -> Unit,
    onSettingsSaved: () -> Unit,
    isDarkTheme: Boolean,
    section: String? = null
) {
    val context = LocalContext.current
    val sharedPrefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    
    // Theme colors setup
    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) Color.White.copy(alpha = 0.08f) else DividerLight
    val backgroundSheetColor = if (isDarkTheme) BackgroundDark else BackgroundLight
    
    // Accent Color loading from pocketlinux settings
    val accentHex = remember { sharedPrefs.getString("pocketlinux_accent", "#2196F3") ?: "#2196F3" }
    val accentColor = remember(accentHex) {
        try {
            Color(android.graphics.Color.parseColor(accentHex))
        } catch (e: Exception) {
            Magenta
        }
    }
    
    // Help Dialog state
    var helpDialogInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    
    // Settings state backing
    var displayResolutionMode by remember { mutableStateOf(sharedPrefs.getString("displayResolutionMode", "native") ?: "native") }
    var displayFilteringMode by remember { mutableStateOf(sharedPrefs.getString("displayFilteringMode", "nearest") ?: "nearest") }
    var touchMode by remember { mutableStateOf(sharedPrefs.getString("touchMode", "1") ?: "1") }
    var transformCapturedPointer by remember { mutableStateOf(sharedPrefs.getString("transformCapturedPointer", "no") ?: "no") }
    
    var displayScale by remember { mutableStateOf(sharedPrefs.getInt("displayScale", 100).toFloat()) }
    var pointerSpeed by remember { mutableStateOf(sharedPrefs.getInt("capturedPointerSpeedFactor", 100).toFloat()) }
    
    // Switches
    var fullscreen by remember { mutableStateOf(sharedPrefs.getBoolean("fullscreen", true)) }
    var keepScreenOn by remember { mutableStateOf(sharedPrefs.getBoolean("keepScreenOn", true)) }
    var reseed by remember { mutableStateOf(sharedPrefs.getBoolean("Reseed", false)) }
    var pip by remember { mutableStateOf(sharedPrefs.getBoolean("PIP", true)) }
    var hideCutout by remember { mutableStateOf(sharedPrefs.getBoolean("hideCutout", false)) }
    
    var showAdditionalKbd by remember { mutableStateOf(sharedPrefs.getBoolean("showAdditionalKbd", true)) }
    var showIMEExternal by remember { mutableStateOf(sharedPrefs.getBoolean("showIMEWhileExternalConnected", true)) }
    var preferScancodes by remember { mutableStateOf(sharedPrefs.getBoolean("preferScancodes", false)) }
    var hwKbdWorkaround by remember { mutableStateOf(sharedPrefs.getBoolean("hardwareKbdScancodesWorkaround", true)) }
    var dexMetaKeyCapture by remember { mutableStateOf(sharedPrefs.getBoolean("dexMetaKeyCapture", false)) }

    var pauseKeyEsc by remember { mutableStateOf(sharedPrefs.getBoolean("pauseKeyInterceptingWithEsc", false)) }
    var filterWinkey by remember { mutableStateOf(sharedPrefs.getBoolean("filterOutWinkey", false)) }
    var enforceCharInput by remember { mutableStateOf(sharedPrefs.getBoolean("enforceCharBasedInput", false)) }
    
    var scaleTouchpad by remember { mutableStateOf(sharedPrefs.getBoolean("scaleTouchpad", true)) }
    var showStylusClick by remember { mutableStateOf(sharedPrefs.getBoolean("showStylusClickOverride", false)) }
    var stylusIsMouse by remember { mutableStateOf(sharedPrefs.getBoolean("stylusIsMouse", false)) }
    var stylusButtonContact by remember { mutableStateOf(sharedPrefs.getBoolean("stylusButtonContactModifierMode", false)) }
    var showMouseHelper by remember { mutableStateOf(sharedPrefs.getBoolean("showMouseHelper", false)) }
    var pointerCapture by remember { mutableStateOf(sharedPrefs.getBoolean("pointerCapture", false)) }
    var tapToMove by remember { mutableStateOf(sharedPrefs.getBoolean("tapToMove", false)) }
    
    var clipboard by remember { mutableStateOf(sharedPrefs.getBoolean("clipboardEnable", true)) }
    var secondaryDisplayPrefs by remember { mutableStateOf(sharedPrefs.getBoolean("storeSecondaryDisplayPreferencesSeparately", false)) }
    var toggleHidden by remember { mutableStateOf(sharedPrefs.getBoolean("togglehidden", false)) }
    
    // Save preferences and trigger refresh/callback
    fun saveAndBroadcast() {
        val editor = sharedPrefs.edit()
        
        // Viewer scale: when mode is "scaled", keep user's scale; when they pick scaled
        // via scale slider elsewhere we also set mode. Honor explicit mode choice here.
        editor.putString("displayResolutionMode", displayResolutionMode)
        editor.putString("displayFilteringMode", displayFilteringMode)
        editor.putString("touchMode", touchMode)
        editor.putString("transformCapturedPointer", transformCapturedPointer)
        editor.putInt("displayScale", displayScale.toInt().coerceIn(30, 300))
        editor.putInt("capturedPointerSpeedFactor", pointerSpeed.toInt())
        
        editor.putBoolean("fullscreen", fullscreen)
        editor.putBoolean("keepScreenOn", keepScreenOn)
        editor.putBoolean("Reseed", reseed)
        editor.putBoolean("PIP", pip)
        editor.putBoolean("hideCutout", hideCutout)
        
        editor.putBoolean("showAdditionalKbd", showAdditionalKbd)
        if (showAdditionalKbd) {
            editor.putBoolean("additionalKbdVisible", true)
        }
        editor.putBoolean("showIMEWhileExternalConnected", showIMEExternal)
        editor.putBoolean("preferScancodes", preferScancodes)
        editor.putBoolean("hardwareKbdScancodesWorkaround", hwKbdWorkaround)
        editor.putBoolean("dexMetaKeyCapture", dexMetaKeyCapture)

        editor.putBoolean("pauseKeyInterceptingWithEsc", pauseKeyEsc)
        editor.putBoolean("filterOutWinkey", filterWinkey)
        editor.putBoolean("enforceCharBasedInput", enforceCharInput)
        
        editor.putBoolean("scaleTouchpad", scaleTouchpad)
        editor.putBoolean("showStylusClickOverride", showStylusClick)
        editor.putBoolean("stylusIsMouse", stylusIsMouse)
        editor.putBoolean("stylusButtonContactModifierMode", stylusButtonContact)
        editor.putBoolean("showMouseHelper", showMouseHelper)
        editor.putBoolean("pointerCapture", pointerCapture)
        editor.putBoolean("tapToMove", tapToMove)
        
        editor.putBoolean("clipboardEnable", clipboard)
        editor.putBoolean("storeSecondaryDisplayPreferencesSeparately", secondaryDisplayPrefs)
        editor.putBoolean("togglehidden", toggleHidden)
        
        editor.apply()
        
        context.sendBroadcast(
            Intent("com.sg.linuxgo.x11.ACTION_PREFERENCES_CHANGED").apply {
                putExtra("fromBroadcast", true)
                setPackage(context.packageName)
            }
        )
        onSettingsSaved()
    }
    
    // Help dialog layout
    if (helpDialogInfo != null) {
        AlertDialog(
            onDismissRequest = { helpDialogInfo = null },
            title = {
                Text(
                    text = helpDialogInfo!!.first,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
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
            containerColor = backgroundSheetColor,
            shape = RoundedCornerShape(8.dp)
        )
    }
    
    val pageTitle = when (section) {
        "output" -> "Output settings"
        "keyboard" -> "Keyboard settings"
        "pointer" -> "Pointer settings"
        "other" -> "Other settings"
        else -> "Display settings"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundSheetColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = textPrimaryColor
                    )
                }
                Text(
                    text = pageTitle,
                    fontFamily = FontFamily.Default,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimaryColor
                )
            }

            HorizontalDivider(thickness = 1.dp, color = dividerColor)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
            // SECTION: OUTPUT
            if (section == null || section == "output") {
                Column {
                    if (section == null) {
                        Text(
                            text = "OUTPUT",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            letterSpacing = 0.1.sp,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )
                    }
                    
                    DropdownSetting(
                        title = "DISPLAY_RESOLUTION_MODE",
                        description = "Determines how the screen resolution is determined:\n\n• native: Matches the device's physical resolution.\n• scaled: Scaled to a percentage of native resolution.\n• exact: Exact match of the container dimensions.\n• custom: Custom resolution specified.",
                        selectedValue = displayResolutionMode,
                        entryLabels = listOf("native", "scaled", "exact", "custom"),
                        entryValues = listOf("native", "scaled", "exact", "custom"),
                        onValueChange = {
                            displayResolutionMode = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Display Resolution Mode" to "Determines how the screen resolution is determined:\n\n• native: Matches the device's physical resolution.\n• scaled: Scaled to a percentage of native resolution.\n• exact: Exact match of the container dimensions.\n• custom: Custom resolution specified."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    if (displayResolutionMode == "scaled") {
                        SliderSetting(
                            title = "DISPLAY_SCALE_%",
                            value = displayScale,
                            valueRange = 30f..300f,
                            onValueChange = { displayScale = it },
                            onValueChangeFinished = { saveAndBroadcast() },
                            onInfoClick = {
                                helpDialogInfo = "Display Scale" to "Viewer-only zoom (Termux X11 style): resizes the X11 framebuffer relative to the phone screen, then stretches to fill. Does not change Linux DPI or guest config. Prefer Display Filtering = nearest for sharp UI. 100% is pixel-perfect (native)."
                            },
                            accentColor = accentColor,
                            isDarkTheme = isDarkTheme
                        )
                    }
                    
                    DropdownSetting(
                        title = "DISPLAY_FILTERING_MODE",
                        description = "Choose display rendering texture filter:\n\n• bilinear: Smooth rendering.\n• nearest: Sharp pixel-perfect rendering.",
                        selectedValue = displayFilteringMode,
                        entryLabels = listOf("bilinear", "nearest"),
                        entryValues = listOf("bilinear", "nearest"),
                        onValueChange = {
                            displayFilteringMode = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Display Filtering Mode" to "Choose display rendering texture filter:\n\n• bilinear: Smooth rendering.\n• nearest: Sharp pixel-perfect rendering."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Fullscreen",
                        summary = "Toggle immersive mode",
                        description = "Hides android system status bars and navigation bar during graphical sessions for an immersive layout.",
                        checked = fullscreen,
                        onCheckedChange = {
                            fullscreen = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Fullscreen" to "Hides android system status bars and navigation bar during graphical sessions for an immersive layout."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Keep Screen On",
                        description = "Prevents the device display from turning off or sleeping while using the app.",
                        checked = keepScreenOn,
                        onCheckedChange = {
                            keepScreenOn = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Keep Screen On" to "Prevents the device display from turning off or sleeping while using the app."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Reseed screen while keyboard open",
                        summary = "Screen size adjusts while Soft Keyboard is open",
                        description = "Dynamically resizes/re-lays out the graphical environment display when the virtual software keyboard is opened, ensuring text fields stay visible.",
                        checked = reseed,
                        onCheckedChange = {
                            reseed = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Reseed screen while keyboard open" to "Dynamically resizes/re-lays out the graphical environment display when the virtual software keyboard is opened, ensuring text fields stay visible."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "PIP mode",
                        summary = "Show in picture-in-picture on home/recents press",
                        description = "Enables picture-in-picture window mode when pressing the system home or recents button.",
                        checked = pip,
                        onCheckedChange = {
                            pip = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "PIP mode" to "Enables picture-in-picture window mode when pressing the system home or recents button."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Hide display cutout (if any)",
                        description = "Enforces layout bounds to sit below front camera punch-holes or screen notches.",
                        checked = hideCutout,
                        onCheckedChange = {
                            hideCutout = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Hide display cutout (if any)" to "Enforces layout bounds to sit below front camera punch-holes or screen notches."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                }
            }
            
            // SECTION: KEYBOARD
            if (section == null || section == "keyboard") {
                Column {
                    if (section == null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(thickness = 1.dp, color = dividerColor, modifier = Modifier.padding(bottom = 16.dp))
                        Text(
                            text = "KEYBOARD",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            letterSpacing = 0.1.sp,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )
                    }
                    
                    SwitchSettingRow(
                        title = "Show additional keyboard",
                        description = "Displays extra key shortcuts (Ctrl, Alt, Esc, etc.) in a bar above the virtual keyboard.",
                        checked = showAdditionalKbd,
                        onCheckedChange = {
                            showAdditionalKbd = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Show additional keyboard" to "Displays extra key shortcuts (Ctrl, Alt, Esc, etc.) in a bar above the virtual keyboard."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Show IME with external keyboard",
                        summary = "Show software keyboard while hardware keyboard connected",
                        description = "Allows the virtual onscreen keyboard to open even when a hardware external keyboard is connected.",
                        checked = showIMEExternal,
                        onCheckedChange = {
                            showIMEExternal = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Show IME with external keyboard" to "Allows the virtual onscreen keyboard to open even when a hardware external keyboard is connected."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Prefer scancodes when possible",
                        summary = "Let X server handle hardware keyboard layout",
                        description = "Passes raw hardware keyboard scancodes directly to the X server rather than translated characters.",
                        checked = preferScancodes,
                        onCheckedChange = {
                            preferScancodes = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Prefer scancodes when possible" to "Passes raw hardware keyboard scancodes directly to the X server rather than translated characters."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Hardware keyboard scancodes workaround",
                        summary = "Fixes scancodes on some devices",
                        description = "Alternative scancode mapping rules to fix key layouts on specific devices.",
                        checked = hwKbdWorkaround,
                        onCheckedChange = {
                            hwKbdWorkaround = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Hardware keyboard scancodes workaround" to "Alternative scancode mapping rules to fix key layouts on specific devices."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Intercept system shortcuts",
                        summary = "Samsung Dex: intercept Alt+F4, Meta+D, etc.",
                        description = "Samsung DeX or standard system: Intercepts system keys like Alt+Tab, Alt+F4, and Meta (Win) key search behaviors.",
                        checked = dexMetaKeyCapture,
                        onCheckedChange = {
                            dexMetaKeyCapture = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Intercept system shortcuts" to "Samsung DeX or standard system: Intercepts system keys like Alt+Tab, Alt+F4, and Meta (Win) key search behaviors."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    

                    
                    SwitchSettingRow(
                        title = "Pause key intercepting with Esc key",
                        description = "Configures system to let Esc act as a modifier toggle behavior for key intercepting.",
                        checked = pauseKeyEsc,
                        onCheckedChange = {
                            pauseKeyEsc = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Pause key intercepting with Esc key" to "Configures system to let Esc act as a modifier toggle behavior for key intercepting."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Filter out intercepted Win (Meta) key",
                        summary = "Use Dex shortcuts while intercepting",
                        description = "Prevents the Meta key from launching the Google Assistant or Android launcher while pressed.",
                        checked = filterWinkey,
                        onCheckedChange = {
                            filterWinkey = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Filter out intercepted Win (Meta) key" to "Prevents the Meta key from launching the Google Assistant or Android launcher while pressed."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Enforce char based input",
                        summary = "Suppresses suggestions, predictive typing, CJK",
                        description = "Suppresses predictive typing suggestions, autocorrect, and word completions. Recommended for terminal consoles.",
                        checked = enforceCharInput,
                        onCheckedChange = {
                            enforceCharInput = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Enforce char based input" to "Suppresses predictive typing suggestions, autocorrect, and word completions. Recommended for terminal consoles."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                }
            }
            
            // SECTION: POINTER
            if (section == null || section == "pointer") {
                Column {
                    if (section == null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(thickness = 1.dp, color = dividerColor, modifier = Modifier.padding(bottom = 16.dp))
                        Text(
                            text = "POINTER",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            letterSpacing = 0.1.sp,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )
                    }
                    
                    DropdownSetting(
                        title = "TOUCHSCREEN_INPUT_MODE",
                        description = "Configures pointer tracking behavior:\n\n• Trackpad: Touch moves mouse cursor relative to position.\n• Simulated touchscreen: Touch acts as tap-and-drag.\n• Direct touch: Clicks exactly where finger taps.",
                        selectedValue = touchMode,
                        entryLabels = listOf("Trackpad", "Simulated touchscreen", "Direct touch"),
                        entryValues = listOf("1", "2", "3"),
                        onValueChange = {
                            touchMode = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Touchscreen Input Mode" to "Configures pointer tracking behavior:\n\n• Trackpad: Touch moves mouse cursor relative to position.\n• Simulated touchscreen: Touch acts as tap-and-drag.\n• Direct touch: Clicks exactly where finger taps."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Apply display scale factor to touchpad",
                        checked = scaleTouchpad,
                        onCheckedChange = {
                            scaleTouchpad = it
                            saveAndBroadcast()
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Show stylus click options",
                        checked = showStylusClick,
                        onCheckedChange = {
                            showStylusClick = it
                            saveAndBroadcast()
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Enable stylus mouse mode",
                        summary = "Stylus works like a mouse, ignoring pressure/tilt",
                        description = "Treats stylus input strictly as a normal mouse cursor, ignoring raw pressure and tilt properties.",
                        checked = stylusIsMouse,
                        onCheckedChange = {
                            stylusIsMouse = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Enable stylus mouse mode" to "Treats stylus input strictly as a normal mouse cursor, ignoring raw pressure and tilt properties."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Stylus button contact modifier mode",
                        summary = "Send right/middle click only on stylus touch",
                        description = "Sends right or middle clicks only when the stylus tip is actively in contact with the screen.",
                        checked = stylusButtonContact,
                        onCheckedChange = {
                            stylusButtonContact = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Stylus button contact modifier mode" to "Sends right or middle clicks only when the stylus tip is actively in contact with the screen."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Show mouse click helper overlay",
                        summary = "Onscreen mouse buttons for touchpad",
                        description = "Draws floating on-screen buttons (Left Click, Right Click, Middle Click) for easier touchpad interaction.",
                        checked = showMouseHelper,
                        onCheckedChange = {
                            showMouseHelper = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Show mouse click helper overlay" to "Draws floating on-screen buttons (Left Click, Right Click, Middle Click) for easier touchpad interaction."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Capture external pointer devices",
                        summary = "Intercept all hardware pointer events",
                        description = "Locks the cursor of connected USB/Bluetooth mice within the session window.",
                        checked = pointerCapture,
                        onCheckedChange = {
                            pointerCapture = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Capture external pointer devices" to "Locks the cursor of connected USB/Bluetooth mice within the session window."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Enable tap-to-move for touchpads",
                        checked = tapToMove,
                        onCheckedChange = {
                            tapToMove = it
                            saveAndBroadcast()
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    DropdownSetting(
                        title = "TRANSFORM_CAPTURED_POINTER",
                        description = "Rotates physical mouse movements relative to display orientation (e.g. rotated sideways).",
                        selectedValue = transformCapturedPointer,
                        entryLabels = listOf("No", "Clockwise", "Counter clockwise", "Upside down", "Automatic (for touchpad)"),
                        entryValues = listOf("no", "c", "cc", "ud", "at"),
                        onValueChange = {
                            transformCapturedPointer = it
                            saveAndBroadcast()
                        },
                        onInfoClick = {
                            helpDialogInfo = "Transform Captured Pointer" to "Rotates physical mouse movements relative to display orientation (e.g. rotated sideways)."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SliderSetting(
                        title = "CAPTURED_POINTER_SPEED_%",
                        value = pointerSpeed,
                        valueRange = 1f..300f,
                        onValueChange = { pointerSpeed = it },
                        onValueChangeFinished = { saveAndBroadcast() },
                        onInfoClick = {
                            helpDialogInfo = "Captured Pointer Speed Factor" to "Multiplier factor for hardware cursor speed, scaling from 1% to 300%."
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                }
            }
            
            // SECTION: OTHER
            if (section == null || section == "other") {
                Column {
                    if (section == null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(thickness = 1.dp, color = dividerColor, modifier = Modifier.padding(bottom = 16.dp))
                        Text(
                            text = "OTHER",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            letterSpacing = 0.1.sp,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )
                    }
                    
                    SwitchSettingRow(
                        title = "Clipboard sharing",
                        checked = clipboard,
                        onCheckedChange = {
                            clipboard = it
                            saveAndBroadcast()
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                    
                    SwitchSettingRow(
                        title = "Store preferences for secondary displays separately",
                        checked = secondaryDisplayPrefs,
                        onCheckedChange = {
                            secondaryDisplayPrefs = it
                            saveAndBroadcast()
                        },
                        accentColor = accentColor,
                        isDarkTheme = isDarkTheme
                    )
                }
            }
            
            // Experimental / Big Screen live on dedicated full pages.
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SwitchSettingRow(
    title: String,
    summary: String? = null,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfoClick: (() -> Unit)? = null,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) DividerDark else DividerLight
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = textPrimaryColor
                )
                if (description != null && onInfoClick != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = onInfoClick,
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = "Info $title",
                            tint = textSecondaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (summary != null) {
                Text(
                    text = summary,
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    color = textSecondaryColor,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        val view = LocalView.current
        Switch(
            checked = checked,
            onCheckedChange = { newChecked ->
                view.performClickHaptic()
                onCheckedChange(newChecked)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextWhite,
                checkedTrackColor = accentColor,
                uncheckedThumbColor = textSecondaryColor,
                uncheckedTrackColor = dividerColor,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun DropdownSetting(
    title: String,
    description: String,
    selectedValue: String,
    entryLabels: List<String>,
    entryValues: List<String>,
    onValueChange: (String) -> Unit,
    onInfoClick: () -> Unit,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.Default,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondaryColor,
                letterSpacing = 0.05.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(16.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_info),
                    contentDescription = "Info $title",
                    tint = textSecondaryColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        PillSelectionGroup(
            labels = entryLabels,
            values = entryValues,
            selectedValue = selectedValue,
            onValueChange = onValueChange,
            accentColor = accentColor.copy(alpha = 0.78f),
            isDarkTheme = isDarkTheme
        )
    }
}

@Composable
fun SliderSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onInfoClick: () -> Unit,
    accentColor: Color,
    isDarkTheme: Boolean
) {
    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) DividerDark else DividerLight
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.Default,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondaryColor,
                letterSpacing = 0.05.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(16.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_info),
                    contentDescription = "Info $title",
                    tint = textSecondaryColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${value.toInt()}%",
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimaryColor
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        val view = LocalView.current
        var lastIntVal by remember { mutableIntStateOf(value.toInt()) }

        Slider(
            value = value,
            onValueChange = { newVal ->
                if (newVal.toInt() != lastIntVal) {
                    lastIntVal = newVal.toInt()
                    view.performClickHaptic()
                }
                onValueChange(newVal)
            },
            onValueChangeFinished = {
                view.performClickHaptic()
                onValueChangeFinished()
            },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = dividerColor
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
