package com.sg.linuxgo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sg.linuxgo.ui.theme.Red
import com.sg.linuxgo.ui.theme.TextWhite
import com.sg.linuxgo.ui.utils.performClickHaptic

/**
 * Shared Compose confirmation dialog — replaces AppCompat AlertDialog for
 * simple yes/no flows so chrome matches the rest of PocketLinux.
 */
data class AppConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String = "Confirm",
    val dismissLabel: String = "Cancel",
    val destructive: Boolean = false,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit = {},
)

@Composable
fun AppConfirmDialog(
    request: AppConfirmRequest,
    isDarkTheme: Boolean,
) {
    val view = LocalView.current
    val cardBg = MaterialTheme.colorScheme.surface
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val confirmBg = if (request.destructive) Red else MaterialTheme.colorScheme.primary
    val confirmFg = if (request.destructive) TextWhite else Color.White

    Dialog(
        onDismissRequest = request.onDismiss,
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
                    text = request.title,
                    color = textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = request.message,
                    color = textSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = {
                        view.performClickHaptic()
                        request.onConfirm()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmBg,
                        contentColor = confirmFg,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = request.confirmLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        view.performClickHaptic()
                        request.onDismiss()
                    },
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
                        text = request.dismissLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                    )
                }
            }
        }
    }
}
