package com.sg.linuxgo.ui.legal

import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.NestedScrollView
import com.sg.linuxgo.LegalDocuments
import com.sg.linuxgo.ui.theme.BackgroundDark
import com.sg.linuxgo.ui.theme.BackgroundLight
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary

@Composable
fun LegalDocumentScreen(
    kind: LegalDocuments.Kind,
    isDarkTheme: Boolean,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val background = if (isDarkTheme) BackgroundDark else BackgroundLight
    val dividerColor =
        if (isDarkTheme) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f)
    val body = remember(kind) { legalDocumentSpanned(kind) }
    val bodyColor = textSecondary.toArgb()
    val pageColor = background.toArgb()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(background)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(background)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = textPrimary
                        )
                    }
                    Text(
                        text = kind.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = dividerColor)
            }
            AndroidView(
                factory = { context ->
                    val density = context.resources.displayMetrics.density
                    val pad = (20 * density).toInt()
                    NestedScrollView(context).apply {
                        isFillViewport = true
                        overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        isNestedScrollingEnabled = true
                        clipToPadding = true
                        setBackgroundColor(pageColor)
                        setPadding(pad, pad, pad, pad)
                        addView(
                            TextView(context).apply {
                                setTextIsSelectable(false)
                                setBackgroundColor(pageColor)
                                textSize = 15f
                                setLineSpacing(8f * density, 1f)
                                includeFontPadding = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                                }
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                            }
                        )
                    }
                },
                update = { scroller ->
                    scroller.setBackgroundColor(pageColor)
                    val tv = scroller.getChildAt(0) as TextView
                    tv.setBackgroundColor(pageColor)
                    tv.setTextColor(bodyColor)
                    if (tv.text !== body) {
                        tv.text = body
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(background)
                    .navigationBarsPadding()
            )
        }
    }
}

internal fun legalDocumentSpanned(kind: LegalDocuments.Kind): CharSequence {
    val b = SpannableStringBuilder()
    val updated = "Last updated: ${LegalDocuments.LAST_UPDATED}"
    b.append(updated)
    b.setSpan(
        RelativeSizeSpan(0.9f),
        0,
        updated.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    kind.sections.forEach { section ->
        b.append("\n\n")
        val start = b.length
        b.append(section.heading)
        b.setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            b.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        b.setSpan(
            RelativeSizeSpan(1.07f),
            start,
            b.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        b.append("\n\n")
        b.append(section.body)
    }
    return b
}
