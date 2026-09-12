package com.sg.linuxgo.ui.onboarding

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
// Icons.Default aliases Filled set used elsewhere in the app
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.utils.performClickHaptic

/**
 * Copy + UI for the Android "Disable child process restrictions" tip.
 * Linux containers spawn many processes; without this, Android 12+ may kill
 * them (phantom process killer), which looks like random distro/desktop crashes.
 */
object AndroidStabilityTips {
    const val TITLE = "Recommended on Android 12+"
    const val SETTING_EXACT_NAME = "Disable child process restrictions"
    const val TAGLINE =
        "Turn on this setting for stable Linux desktops and terminals."

    const val WHY =
        "Your Linux environment starts many small processes (desktop, apps, shells). " +
            "Android can kill those child processes in the background — the desktop " +
            "may freeze, go black, or close without a clear error."

    const val WHAT_TO_DO =
        "In Developer options, turn on this setting (exact name):"

    val steps = listOf(
        "Settings → About phone → tap Build number 7 times to unlock Developer options.",
        "Open Settings → System → Developer options (on some phones: Settings → Developer options).",
        "Turn on “Disable child process restrictions”.",
        "Reboot the phone if Android asks you to."
    )

    const val FOOTNOTE =
        "Turning it on lifts Android’s limit so PocketLinux can run more processes. " +
            "If the option is missing, your manufacturer may hide it — also set PocketLinux " +
            "battery use to Unrestricted."
}

/**
 * Highlighted stability card used in package tips (and similar surfaces).
 */
@Composable
fun AndroidStabilityTipCard(
    isDarkTheme: Boolean,
    accentColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    modifier: Modifier = Modifier,
    showOpenSettingsButton: Boolean = true
) {
    val context = LocalContext.current
    val view = LocalView.current
    val cardBg = if (isDarkTheme) Color(0xFF2A1F12) else Color(0xFFFFF8EE)
    val border = if (isDarkTheme) Color(0xFF6B4E1E) else Color(0xFFE8C98A)
    val warn = if (isDarkTheme) Color(0xFFFBBF24) else Color(0xFFB45309)
    val codeBg = if (isDarkTheme) Color(0xFF1A140C) else Color(0xFFFFF1D6)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = warn,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = AndroidStabilityTips.TITLE,
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default
                )
                Text(
                    text = AndroidStabilityTips.TAGLINE,
                    color = textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = AndroidStabilityTips.WHY,
            color = textPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Default,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = AndroidStabilityTips.WHAT_TO_DO,
            color = textSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Default,
            lineHeight = 16.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = AndroidStabilityTips.SETTING_EXACT_NAME,
            color = warn,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(codeBg)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AndroidStabilityTips.steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = "${index + 1}.",
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                        modifier = Modifier.width(18.dp)
                    )
                    Text(
                        text = step,
                        color = textPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = AndroidStabilityTips.FOOTNOTE,
            color = textSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Default,
            lineHeight = 15.sp
        )

        if (showOpenSettingsButton) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    view.performClickHaptic()
                    openDeveloperOptions(context)
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accentColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open Developer options",
                    color = textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Default
                )
            }
        }
    }
}

fun openDeveloperOptions(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            Toast.makeText(
                context,
                "Unlock Developer options first: About phone → tap Build number 7 times",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Open Settings → Developer options → “Disable child process restrictions”",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
