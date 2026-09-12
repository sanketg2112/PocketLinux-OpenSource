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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.sg.linuxgo.CrashReportCoordinator
import com.sg.linuxgo.ui.utils.performClickHaptic

/**
 * Compose replacement for the AppCompat crash-report prompt.
 */
@Composable
fun CrashRecoveryDialog(
    pending: CrashReportCoordinator.PendingReport,
    onSendReport: (userNote: String?) -> Unit,
    onDismiss: () -> Unit,
    onNeverAsk: () -> Unit,
    onViewLogs: (() -> Unit)? = null,
) {
    val view = LocalView.current
    var note by remember { mutableStateOf("") }
    val cardBg = MaterialTheme.colorScheme.surface
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val outlineBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val title = CrashReportCoordinator.dialogTitle(pending)
    val body = CrashReportCoordinator.buildDialogBody(pending)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(horizontal = 6.dp),
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = title,
                    color = textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = body,
                    color = textSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default,
                    lineHeight = 18.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 800) note = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    placeholder = {
                        Text(
                            text = "What were you doing? (optional)",
                            fontSize = 13.sp,
                            color = textSecondary,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = outlineBorder,
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary,
                        cursorColor = accent,
                    ),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        view.performClickHaptic()
                        onSendReport(note.trim().ifBlank { null })
                    },
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
                        text = "Send report",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        view.performClickHaptic()
                        onDismiss()
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
                        text = "OK",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Default,
                    )
                }
                if (onViewLogs != null) {
                    TextButton(
                        onClick = {
                            view.performClickHaptic()
                            onViewLogs()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "View logs",
                            color = accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        view.performClickHaptic()
                        onNeverAsk()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Don't ask again",
                        color = textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
