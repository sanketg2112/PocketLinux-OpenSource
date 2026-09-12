package com.sg.linuxgo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import com.sg.linuxgo.ui.utils.performClickHaptic
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.Bootstrap
import com.sg.linuxgo.ui.theme.*

@Composable
fun SoftwareGrid(
    choices: List<Bootstrap.Choice>,
    selectedIds: Set<String>,
    onSelectionChanged: (String, Boolean, List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        modifier = modifier
    ) {
        items(choices) { choice ->
            val isSelected = selectedIds.contains(choice.id)
            SoftwareCard(
                choice = choice,
                isSelected = isSelected,
                onClick = {
                    val newSelected = selectedIds.toMutableSet()
                    val isChecked = !isSelected
                    if (isChecked) {
                        newSelected.add(choice.id)
                    } else {
                        newSelected.remove(choice.id)
                    }
                    onSelectionChanged(choice.id, isChecked, newSelected.toList())
                }
            )
        }
    }
}

@Composable
fun SoftwareCard(
    choice: Bootstrap.Choice,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    Card(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) Cyan else StrokeLight
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CyanAlpha20 else SurfaceElevated
        ),
        modifier = modifier
            .padding(4.dp)
            .clickable(onClick = {
                view.performClickHaptic()
                onClick()
            })
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val emoji = getEmojiForSoftware(choice.id)
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emoji,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = choice.label.split(" ").first(),
                color = TextWhite,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Default,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = choice.label.split(" ").drop(1).joinToString(" "),
                color = TextSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Default,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Checkbox(
                checked = isSelected,
                onCheckedChange = null, // Checked state change is handled by the parent Card's click action
                colors = CheckboxDefaults.colors(
                    checkedColor = Magenta,
                    uncheckedColor = StrokeLight,
                    checkmarkColor = TextWhite
                ),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun getEmojiForSoftware(id: String): String = when (id) {
    "chromium" -> "🌐"
    "firefox" -> "🦊"
    "vlc" -> "📽️"
    "mpv" -> "🎬"
    "code-oss" -> "👨‍💻"
    "geany" -> "📝"
    "thunar", "pcmanfm", "dolphin" -> "📂"
    "ranger", "nnn", "neovim" -> "💻"
    "gimp", "inkscape" -> "🎨"
    "transmission-gtk", "transmission-cli" -> "⬇️"
    "libreoffice" -> "📊"
    "thunderbird" -> "📧"
    "discord" -> "💬"
    "telegram-desktop" -> "✈️"
    "spotify" -> "🎵"
    else -> "📦"
}
