package com.sg.linuxgo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sg.linuxgo.ui.utils.performClickHaptic

/**
 * Shown once when an environment finishes installing — primary next actions.
 */
@Composable
fun InstallCompleteDialog(
    environmentName: String,
    onLaunchDesktop: () -> Unit,
    onOpenTerminal: () -> Unit,
    onShowTips: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val cardBg = MaterialTheme.colorScheme.surface
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val outlineBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    // Local guard: one button only (pairs with ViewModel.consumeInstallCompleteContainerId).
    var handled by remember { mutableStateOf(false) }
    fun once(action: () -> Unit) {
        if (handled) return
        handled = true
        action()
    }

    Dialog(
        onDismissRequest = { once(onDismiss) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Text(
                    text = "Environment ready",
                    color = textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "“$environmentName” finished installing. Launch the desktop or open a terminal to get started.",
                    color = textSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default,
                    lineHeight = 18.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        view.performClickHaptic()
                        once(onLaunchDesktop)
                    },
                    enabled = !handled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = "Launch desktop",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        view.performClickHaptic()
                        once(onOpenTerminal)
                    },
                    enabled = !handled,
                    border = BorderStroke(1.dp, outlineBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = textPrimary,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = "Open terminal",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                    )
                }
                if (onShowTips != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            view.performClickHaptic()
                            once(onShowTips)
                        },
                        enabled = !handled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Tips: install apps",
                            color = accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Default,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        view.performClickHaptic()
                        once(onDismiss)
                    },
                    enabled = !handled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Not now",
                        color = textSecondary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Default,
                    )
                }
            }
        }
    }
}
