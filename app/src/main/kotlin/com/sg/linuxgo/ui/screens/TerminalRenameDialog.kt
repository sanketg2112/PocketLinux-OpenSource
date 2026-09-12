package com.sg.linuxgo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
internal fun RenameDialog(
    initialName: String,
    theme: TerminalTheme,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialName) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.backgroundColor,
        titleContentColor = theme.accentColor,
        textContentColor = theme.textColor,
        title = {
            Text(
                "Rename Tab",
                fontFamily = FontFamily.Default,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = theme.tabBarColor,
                    unfocusedContainerColor = theme.tabBarColor,
                    focusedIndicatorColor = theme.accentColor,
                    unfocusedIndicatorColor = theme.textColor.copy(alpha = 0.4f),
                    cursorColor = theme.accentColor
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) {
                Text("OK", color = theme.accentColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.textColor.copy(alpha = 0.7f))
            }
        }
    )
}
