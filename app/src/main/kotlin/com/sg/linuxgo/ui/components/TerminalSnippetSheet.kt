package com.sg.linuxgo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.SnippetEntity
import com.sg.linuxgo.SnippetPromptState
import com.sg.linuxgo.SnippetStore
import com.sg.linuxgo.ui.theme.Cyan
import com.sg.linuxgo.ui.theme.StrokeLight
import com.sg.linuxgo.ui.theme.SurfaceDark
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary

@Composable
fun TerminalSnippetSheet(
    onDismiss: () -> Unit,
    onExecuteSnippet: (SnippetEntity) -> Unit,
    accentColor: Color = Cyan,
    textColor: Color = TextSecondary,
    backgroundColor: Color = SurfaceDark
) {
    val context = LocalContext.current
    var snippets by remember { mutableStateOf(SnippetStore.loadSnippets(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    fun refreshSnippets() {
        snippets = SnippetStore.loadSnippets(context)
    }

    val filtered = remember(snippets, searchQuery) {
        if (searchQuery.isBlank()) snippets
        else snippets.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.command.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("⚡ Terminal Snippets", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = textColor, modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filter snippets...", color = textColor.copy(alpha = 0.4f), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = textColor,
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = StrokeLight
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Snippet", tint = accentColor)
                    }
                }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "No snippets saved yet. Tap + to add one!" else "No matching snippets.",
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.id }) { snippet ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onExecuteSnippet(snippet)
                                        onDismiss()
                                    },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = StrokeLight.copy(alpha = 0.25f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = snippet.title,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                            if (snippet.variablesString.isNotBlank()) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "var: ${snippet.variablesString}",
                                                    color = accentColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(accentColor.copy(alpha = 0.12f))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = snippet.command,
                                            color = textColor,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            onExecuteSnippet(snippet)
                                            onDismiss()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Run Snippet",
                                            tint = accentColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            SnippetStore.deleteSnippet(context, snippet.id)
                                            refreshSnippets()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Snippet",
                                            tint = Color(0xFFE06C75),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = textColor)
            }
        },
        containerColor = backgroundColor
    )

    if (showAddDialog) {
        AddSnippetDialog(
            accentColor = accentColor,
            textColor = textColor,
            backgroundColor = backgroundColor,
            onDismiss = { showAddDialog = false },
            onConfirm = { title, command, variables ->
                SnippetStore.addSnippet(context, title, command, variables)
                refreshSnippets()
                showAddDialog = false
            }
        )
    }
}

@Composable
internal fun AddSnippetDialog(
    accentColor: Color,
    textColor: Color,
    backgroundColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (title: String, command: String, variables: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var variables by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Snippet", color = accentColor, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("Title", color = textColor.copy(alpha = 0.7f)) },
                    placeholder = { Text("e.g. Start Web Server", color = textColor.copy(alpha = 0.35f), fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = textColor,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = StrokeLight
                    )
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it; error = null },
                    label = { Text("Command", color = textColor.copy(alpha = 0.7f)) },
                    placeholder = { Text("e.g. python3 -m http.server {{port}}", color = textColor.copy(alpha = 0.35f), fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = textColor,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = StrokeLight
                    )
                )
                OutlinedTextField(
                    value = variables,
                    onValueChange = { variables = it; error = null },
                    label = { Text("Variables (optional, comma-separated)", color = textColor.copy(alpha = 0.7f)) },
                    placeholder = { Text("e.g. port", color = textColor.copy(alpha = 0.35f), fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = textColor,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = StrokeLight
                    )
                )
                if (error != null) {
                    Text(error!!, color = Color(0xFFE06C75), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val t = title.trim()
                    val c = command.trim()
                    val v = variables.trim()
                    if (t.isEmpty()) {
                        error = "Enter a title for the snippet"
                    } else if (c.isEmpty()) {
                        error = "Enter a command to run"
                    } else {
                        onConfirm(t, c, v)
                    }
                }
            ) {
                Text("Save", color = accentColor, fontWeight = FontWeight.Bold)
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
fun SnippetVariablePromptDialog(
    promptState: SnippetPromptState,
    onDismiss: () -> Unit,
    onSubmit: (Map<String, String>) -> Unit,
    accentColor: Color = Cyan,
    textColor: Color = TextSecondary,
    backgroundColor: Color = SurfaceDark
) {
    val inputs = remember(promptState) {
        mutableStateMapOf<String, String>().apply {
            promptState.variablesNeeded.forEach { v ->
                put(v, promptState.valuesProvided[v] ?: "")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Snippet Variables: ${promptState.snippet.title}", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Fill in the required variable values for command:\n${promptState.snippet.command}",
                    color = textColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                promptState.variablesNeeded.forEach { variable ->
                    OutlinedTextField(
                        value = inputs[variable] ?: "",
                        onValueChange = { inputs[variable] = it },
                        label = { Text("Variable: {{$variable}}", color = accentColor) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = textColor,
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = StrokeLight
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(inputs.toMap())
                }
            ) {
                Text("Inject", color = accentColor, fontWeight = FontWeight.Bold)
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
