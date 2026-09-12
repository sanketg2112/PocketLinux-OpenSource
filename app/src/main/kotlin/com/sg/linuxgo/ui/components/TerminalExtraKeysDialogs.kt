package com.sg.linuxgo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SpecialKeysAddDialog(
    customLabel: String,
    onCustomLabelChange: (String) -> Unit,
    customCombo: String,
    onCustomComboChange: (String) -> Unit,
    customError: String?,
    accentColor: Color,
    textColor: Color,
    backgroundColor: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Add custom key", color = accentColor, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Name appears on the key (max 4 characters). Combination is what gets sent to the terminal.",
                    color = textColor.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = customLabel,
                    onValueChange = { incoming ->
                        onCustomLabelChange(incoming.filter { !it.isWhitespace() }.take(4))
                    },
                    label = { Text("Name (max 4)", color = textColor.copy(alpha = 0.7f)) },
                    singleLine = true,
                    supportingText = {
                        Text("${customLabel.length}/4", color = textColor.copy(alpha = 0.5f), fontSize = 10.sp)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = accentColor,
                        unfocusedTextColor = textColor,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = textColor.copy(alpha = 0.3f),
                        cursorColor = accentColor
                    )
                )
                OutlinedTextField(
                    value = customCombo,
                    onValueChange = onCustomComboChange,
                    label = { Text("Key / combination", color = textColor.copy(alpha = 0.7f)) },
                    placeholder = {
                        Text("Ctrl+C, Alt+b, Esc, or text", color = textColor.copy(alpha = 0.35f), fontSize = 12.sp)
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = accentColor,
                        unfocusedTextColor = textColor,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = textColor.copy(alpha = 0.3f),
                        cursorColor = accentColor
                    )
                )
                if (customError != null) {
                    Text(customError, color = Color(0xFFE06C75), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Add", color = accentColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = textColor)
            }
        },
        containerColor = backgroundColor
    )
}

@Composable
internal fun SpecialKeysResetDialog(
    accentColor: Color,
    textColor: Color,
    backgroundColor: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset extra keys?", color = accentColor) },
        text = {
            Text(
                "Restore the default top bar and panel layout. Custom keys you added will be removed.",
                color = textColor,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Reset", color = Color(0xFFE06C75), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = textColor)
            }
        },
        containerColor = backgroundColor
    )
}

@Composable
internal fun SpecialKeysDeleteDialog(
    keyLabel: String,
    accentColor: Color,
    textColor: Color,
    backgroundColor: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete custom key?", color = accentColor) },
        text = {
            Text("Remove “$keyLabel” from the extra keys?", color = textColor)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = Color(0xFFE06C75), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = textColor)
            }
        },
        containerColor = backgroundColor
    )
}
