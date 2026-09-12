package com.sg.linuxgo.ui.components

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.theme.BackgroundCard
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.utils.performClickHaptic

object GuiFirstRunCoachPrefs {
    /** Bump when coach copy changes so users see the updated tips once. */
    const val PREF_SHOWN = "gui_first_run_coach_shown_v2"

    fun shouldShow(context: Context): Boolean {
        return !PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_SHOWN, false)
    }

    fun markShown(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_SHOWN, true)
            .apply()
    }
}

/**
 * First desktop launch coach: leave via edge pill/home, mouse right-click, keys.
 */
@Composable
fun GuiFirstRunCoachOverlay(
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = BackgroundCard,
            shadowElevation = 10.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Using the desktop",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "• Tap the side pill, then the Home icon, to return to Home — " +
                        "your session keeps running.\n" +
                        "• Use Stop on the environment card to end the session fully.\n" +
                        "• Mouse: long-press or two-finger tap for right-click.\n" +
                        "• Special keys (Ctrl / Esc) appear when the on-screen keyboard is open.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default,
                    lineHeight = 19.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        view.performClickHaptic()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2D88FF),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(
                        text = "Got it",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                    )
                }
            }
        }
    }
}
