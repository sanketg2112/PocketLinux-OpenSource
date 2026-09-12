package com.sg.linuxgo.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.theme.Cyan
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.utils.performKeyHaptic

/**
 * Data class representing a single special key button.
 */
private data class SpecialKey(
    val label: String,
    val keyName: String,
    val isArrow: Boolean = false,
    val isToggle: Boolean = false
)

/**
 * Horizontally scrollable row of special key buttons for terminal/GUI extra keys.
 * Matches the XML HorizontalScrollView layout exactly.
 *
 * @param modifier Modifier applied to the root Row
 * @param onKeyPressed Callback invoked with the key name when a key is pressed
 * @param ctrlActive Whether CTRL toggle is active (shown in Cyan)
 * @param altActive Whether ALT toggle is active (shown in Cyan)
 * @param shiftActive Whether SHIFT toggle is active (shown in Cyan)
 */
@Composable
fun SpecialKeysBar(
    modifier: Modifier = Modifier,
    onKeyPressed: (keyName: String) -> Unit = {},
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    shiftActive: Boolean = false,
    accentColor: androidx.compose.ui.graphics.Color = Cyan,
    textColor: androidx.compose.ui.graphics.Color = TextSecondary
) {
    val context = LocalContext.current
    val keys = listOf(
        SpecialKey("◀", "LEFT", isArrow = true),
        SpecialKey("▲", "UP", isArrow = true),
        SpecialKey("▼", "DOWN", isArrow = true),
        SpecialKey("▶", "RIGHT", isArrow = true),
        SpecialKey("ENTER", "ENTER"),
        SpecialKey("CTRL", "CTRL", isToggle = true),
        SpecialKey("ALT", "ALT", isToggle = true),
        SpecialKey("SHIFT", "SHIFT", isToggle = true),
        SpecialKey("ESC", "ESC"),
        SpecialKey("TAB", "TAB"),
        SpecialKey("FN", "FN"),
        SpecialKey("HOM", "HOME"),
        SpecialKey("END", "END"),
        SpecialKey("PGU", "PGUP"),
        SpecialKey("PGD", "PGDN"),
        SpecialKey("INS", "INS"),
        SpecialKey("DEL", "DEL"),
        SpecialKey("^C", "COPY"),
        SpecialKey("^V", "PASTE"),
        SpecialKey("^S", "SAVE")
    )

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        keys.forEach { key ->
            val isActive = when (key.keyName) {
                "CTRL" -> ctrlActive
                "ALT" -> altActive
                "SHIFT" -> shiftActive
                else -> false
            }
            val buttonColor = if (isActive) accentColor else textColor
            val fontSize = if (key.isArrow) 14.sp else 12.sp
            val fontFamily = if (key.isArrow) null else FontFamily.Default

            TextButton(
                onClick = {
                    context.performKeyHaptic()
                    onKeyPressed(key.keyName)
                },
                modifier = Modifier
                    .width(48.dp)
                    .height(40.dp),
            ) {
                Text(
                    text = key.label,
                    color = buttonColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}
