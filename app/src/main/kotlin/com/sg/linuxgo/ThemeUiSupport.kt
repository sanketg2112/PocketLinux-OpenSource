package com.sg.linuxgo

import android.app.Activity
import android.graphics.Color
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.sg.linuxgo.ui.screens.TerminalTheme
import com.sg.linuxgo.ui.theme.TerminalFontOption

/**
 * Status-bar and legacy terminal TextView theming helpers.
 * Terminal colors are sourced from [TerminalTheme] so Compose + legacy views stay in sync.
 */
object ThemeUiSupport {

    fun isColorLight(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.RGBToHSL(red, green, blue, hsl)
        return hsl[2] > 0.5f
    }

    fun terminalTheme(termThemeId: String): TerminalTheme = TerminalTheme.fromId(termThemeId)

    fun terminalBackgroundArgb(termThemeId: String): Int =
        terminalTheme(termThemeId).backgroundColor.toArgb()

    fun terminalTextArgb(termThemeId: String): Int =
        terminalTheme(termThemeId).textColor.toArgb()

    fun terminalCursorArgb(termThemeId: String): Int =
        terminalTheme(termThemeId).accentColor.toArgb()

    fun terminalTabBarArgb(termThemeId: String): Int =
        terminalTheme(termThemeId).backgroundColor.toArgb()

    fun updateStatusBarColorAndIcons(
        activity: Activity,
        mainRoot: View?,
        isTerminalVisible: Boolean,
        activeTerminalTheme: String,
        /** When Match GUI is active, use guest canvas bg instead of app theme id. */
        overrideTerminalBgArgb: Int? = null
    ) {
        try {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val theme = sharedPrefs.getString("pocketlinux_theme", "system") ?: "system"
            val isDark = when (theme) {
                "light" -> false
                "dark" -> true
                else -> {
                    val mode = activity.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
            }
            val color = if (isTerminalVisible) {
                // Match the terminal canvas (not the tab bar) so status bar doesn't look washed-out grey.
                overrideTerminalBgArgb ?: terminalBackgroundArgb(activeTerminalTheme)
            } else if (isDark) {
                Color.parseColor("#121213")
            } else {
                Color.parseColor("#F6F6F9")
            }

            val window = activity.window

            Log.d(
                "PocketLinuxTheme",
                "updateStatusBarColorAndIcons: colorHex = ${String.format("#%06X", 0xFFFFFF and color)}"
            )

            // Edge-to-edge: keep system bars transparent. Status-bar "color" comes from
            // the content background drawn behind the bar, not from statusBarColor APIs.
            mainRoot?.setBackgroundColor(color)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))

            val isLightBg = isColorLight(color)
            val controller = androidx.core.view.WindowCompat.getInsetsController(
                window,
                window.decorView
            )
            controller.isAppearanceLightStatusBars = isLightBg
            controller.isAppearanceLightNavigationBars = isLightBg
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in updateStatusBarColorAndIcons", e)
        }
    }

    fun applyTerminalThemeToLegacyViews(
        activity: Activity,
        tvTerminalOutput: TextView,
        terminalScroll: ScrollView,
        terminalCursor: View?,
        termThemeId: String,
        onRecalculateSize: () -> Unit,
        overrideBgArgb: Int? = null,
        overrideTextArgb: Int? = null,
        overrideCursorArgb: Int? = null,
        guestFontPath: String? = null
    ) {
        try {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val bgColor = overrideBgArgb ?: terminalBackgroundArgb(termThemeId)
            val textColor = overrideTextArgb ?: terminalTextArgb(termThemeId)
            val cursorColor = overrideCursorArgb ?: terminalCursorArgb(termThemeId)

            // Paint every layer so empty terminal areas are never material-grey.
            // Do NOT call setBackgroundResource(0) — it can flash the default grey drawable.
            terminalScroll.setBackgroundColor(bgColor)
            val content = terminalScroll.getChildAt(0)
            content?.setBackgroundColor(bgColor)
            if (content is ViewGroup) {
                for (i in 0 until content.childCount) {
                    val child = content.getChildAt(i)
                    if (child is TextView) {
                        child.setBackgroundColor(bgColor)
                        // Base color for non-ANSI text; ANSI spans still override per character.
                        child.setTextColor(textColor)
                    } else if (child !== terminalCursor) {
                        // Don't paint the cursor view with the bg.
                    }
                }
            }

            tvTerminalOutput.setBackgroundColor(bgColor)
            tvTerminalOutput.setTextColor(textColor)
            terminalCursor?.setBackgroundColor(cursorColor)

            val fontId = sharedPrefs.getString("terminal_font_family", "meslo") ?: "meslo"
            val fontSize = sharedPrefs.getFloat("terminal_font_size", 12f)

            // Prefer guest Nerd Font when Match GUI found one; else app assets.
            val guestTf = guestFontPath?.let { path ->
                try {
                    val f = java.io.File(path)
                    if (f.isFile) android.graphics.Typeface.createFromFile(f) else null
                } catch (_: Exception) {
                    null
                }
            }
            val fontOption = TerminalFontOption.fromId(fontId)
            tvTerminalOutput.typeface = guestTf ?: fontOption.assetPath?.let { path ->
                try {
                    android.graphics.Typeface.createFromAsset(activity.assets, path)
                } catch (_: Exception) {
                    null
                }
            } ?: android.graphics.Typeface.MONOSPACE
            tvTerminalOutput.textSize = fontSize
            onRecalculateSize()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in applyTerminalThemeToLegacyViews", e)
        }
    }
}
