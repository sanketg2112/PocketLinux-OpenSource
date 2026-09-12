package com.sg.linuxgo.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.R

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onAddContainer: () -> Unit,
    containerCards: @Composable () -> Unit,
    hasContainers: Boolean = true,
    /** Sticky package tips above bottom nav; null when dismissed permanently. */
    onShowTips: (() -> Unit)? = null,
    onDismissTips: (() -> Unit)? = null,
    /**
     * Feedback strip in the same slot as tips (only when tips are gone and the
     * user has stopped at least one GUI/terminal session). Null when hidden.
     */
    onShowFeedback: (() -> Unit)? = null,
    onDismissFeedback: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!hasContainers) {
                EmptyHomeState(onAddContainer = onAddContainer)
            } else {
                // Grid includes the dashed "Add environment" tile as the last cell.
                containerCards()
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Fixed above bottom navigation — not on container cards.
        // Tips take priority; feedback reuses the same strip slot once tips are gone.
        when {
            onShowTips != null -> {
                HomeBottomStrip(
                    emoji = "💡",
                    title = "Install apps like Firefox or OpenCode",
                    subtitle = "Tap for distro tips · dismiss anytime",
                    actionLabel = "Tips",
                    onOpen = onShowTips,
                    onDismiss = onDismissTips,
                    dismissContentDescription = "Dismiss tips"
                )
            }
            onShowFeedback != null -> {
                HomeBottomStrip(
                    emoji = "⭐",
                    title = "Feedback & suggestions",
                    subtitle = "Rate PocketLinux · share ideas · dismiss anytime",
                    actionLabel = "Share",
                    onOpen = onShowFeedback,
                    onDismiss = onDismissFeedback,
                    dismissContentDescription = "Dismiss feedback strip"
                )
            }
        }
    }
}

@Composable
private fun HomeBottomStrip(
    emoji: String,
    title: String,
    subtitle: String,
    actionLabel: String,
    onOpen: () -> Unit,
    onDismiss: (() -> Unit)?,
    dismissContentDescription: String
) {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val border = if (isSystemInDarkTheme()) Color(0xFF2A2A30) else Color(0xFFE5E5EA)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface)
            .border(width = 1.dp, color = border)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Default,
                maxLines = 1
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Default
            )
        }
        Text(
            text = actionLabel,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Default,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = dismissContentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyHomeState(onAddContainer: () -> Unit) {
    val accentColor = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ic_pocket_linux),
            contentDescription = "PocketLinux",
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "No environments yet",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Install a lightweight Linux desktop or terminal on this device. Create an environment to get started.",
            color = secondary,
            fontSize = 14.sp,
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EmptyStep(number = "1", title = "Create", detail = "Pick Debian, Arch, Ubuntu, or Alpine")
            EmptyStep(number = "2", title = "Install", detail = "Needs Wi‑Fi and free storage")
            EmptyStep(number = "3", title = "Launch", detail = "Open desktop or terminal, then install apps")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onAddContainer,
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = "Create environment",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Default
            )
        }
    }
}

@Composable
private fun EmptyStep(number: String, title: String, detail: String) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Default
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Default
            )
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = FontFamily.Default
            )
        }
    }
}


