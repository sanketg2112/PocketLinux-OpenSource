package com.sg.linuxgo.ui.sheets

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.sg.linuxgo.TelemetryManager
import com.sg.linuxgo.ui.theme.BackgroundDark
import com.sg.linuxgo.ui.theme.BackgroundLight
import com.sg.linuxgo.ui.theme.DividerDark
import com.sg.linuxgo.ui.theme.DividerLight
import com.sg.linuxgo.ui.theme.StrokeDark
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.theme.*
import com.sg.linuxgo.ui.utils.performClickHaptic
import org.json.JSONObject

private const val MAX_MESSAGE_CHARS = 2000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackAndSuggestionsSheet(
    onDismiss: () -> Unit,
    isDarkTheme: Boolean,
    onSubmitted: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val textPrimaryColor = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondaryColor = if (isDarkTheme) TextSecondary else TextLightSecondary
    val dividerColor = if (isDarkTheme) DividerDark else DividerLight
    val backgroundSheetColor = if (isDarkTheme) BackgroundDark else BackgroundLight
    val strokeColor = if (isDarkTheme) StrokeDark else DividerLight
    val fieldBg = if (isDarkTheme) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)

    var rating by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf("feedback") } // feedback | feature
    var message by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = backgroundSheetColor,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(dividerColor, shape = RoundedCornerShape(2.dp))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "FEEDBACK & SUGGESTIONS",
                fontFamily = FontFamily.Default,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondaryColor,
                letterSpacing = 0.05.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Rate your experience and share feedback or a feature idea. Everything is anonymous.",
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                color = textSecondaryColor,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Your rating",
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimaryColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (star in 1..5) {
                    val filled = star <= rating
                    Text(
                        text = if (filled) "★" else "☆",
                        fontSize = 32.sp,
                        color = if (filled) Color(0xFFFFD600) else textSecondaryColor,
                        modifier = Modifier
                            .clickable {
                                view.performClickHaptic()
                                rating = star
                            }
                            .padding(4.dp)
                    )
                }
                if (rating > 0) {
                    Text(
                        text = "$rating / 5",
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        color = textSecondaryColor,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Type",
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimaryColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip(
                    label = "Feedback",
                    selected = category == "feedback",
                    accent = Color(0xFF2196F3),
                    isDarkTheme = isDarkTheme,
                    onClick = {
                        view.performClickHaptic()
                        category = "feedback"
                    }
                )
                CategoryChip(
                    label = "Feature suggestion",
                    selected = category == "feature",
                    accent = Color(0xFF9C27B0),
                    isDarkTheme = isDarkTheme,
                    onClick = {
                        view.performClickHaptic()
                        category = "feature"
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = if (category == "feature") "Your idea" else "Your feedback",
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimaryColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { if (it.length <= MAX_MESSAGE_CHARS) message = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                placeholder = {
                    Text(
                        text = if (category == "feature") {
                            "Describe the feature you'd love to see…"
                        } else {
                            "What worked well? What should improve?"
                        },
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        color = textSecondaryColor
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textPrimaryColor,
                    unfocusedTextColor = textPrimaryColor,
                    focusedBorderColor = Color(0xFF2196F3),
                    unfocusedBorderColor = strokeColor,
                    focusedContainerColor = fieldBg,
                    unfocusedContainerColor = fieldBg,
                    cursorColor = Color(0xFF2196F3)
                ),
                shape = RoundedCornerShape(10.dp)
            )
            Text(
                text = "${message.length} / $MAX_MESSAGE_CHARS",
                fontFamily = FontFamily.Default,
                fontSize = 10.sp,
                color = textSecondaryColor,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    if (rating < 1) {
                        Toast.makeText(context, "Please pick a star rating", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (message.isBlank()) {
                        Toast.makeText(context, "Please write a short message", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (submitting) return@Button
                    submitting = true
                    view.performClickHaptic()
                    val meta = JSONObject().apply {
                        put("rating", rating)
                        put("category", category)
                        put("message", message.trim().take(MAX_MESSAGE_CHARS))
                        put("user_submitted", true)
                    }
                    TelemetryManager.trackEvent(
                        context = context,
                        eventType = "user_feedback",
                        metadata = meta.toString()
                    )
                    com.sg.linuxgo.util.HomeFeedbackStripPrefs.markStripDismissed(context)
                    onSubmitted?.invoke()
                    Toast.makeText(context, "Thanks for the feedback!", Toast.LENGTH_SHORT).show()
                    submitting = false
                    onDismiss()
                },
                enabled = !submitting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = if (submitting) "Sending…" else "Submit",
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Cancel", fontFamily = FontFamily.Default, color = textSecondaryColor)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    accent: Color,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent
    val border = if (selected) accent else if (isDarkTheme) StrokeDark else DividerLight
    val text = if (selected) accent else if (isDarkTheme) TextSecondary else TextLightSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Default,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = text
        )
    }
}
