package com.sg.linuxgo.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = TextWhite,
    primaryContainer = CyanDark,
    secondary = PrimaryBlue,
    onSecondary = TextWhite,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = BackgroundCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = StrokeDark,
    outlineVariant = StrokeLight,
    error = Red,
)

/** Light scheme used only for specific bottom sheets (settings) */
val LightColorScheme = lightColorScheme(
    primary = Magenta,
    onPrimary = TextWhite,
    primaryContainer = Magenta,
    secondary = Magenta,
    onSecondary = TextWhite,
    background = BackgroundLight,
    onBackground = TextLightPrimary,
    surface = BackgroundLightCard,
    onSurface = TextLightPrimary,
    surfaceVariant = SurfaceLightElevated,
    onSurfaceVariant = TextLightSecondary,
    outline = DividerLight,
    outlineVariant = DividerLight,
)

@Composable
fun PocketLinuxTheme(
    darkTheme: Boolean = true,
    accentColor: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = if (accentColor != null) {
        baseScheme.copy(
            primary = accentColor,
            secondary = accentColor,
            primaryContainer = accentColor
        )
    } else {
        baseScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Edge-to-edge: do not set statusBarColor (deprecated / no-op on API 35+).
            // Surface background paints under transparent bars; only icon contrast is set here.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
