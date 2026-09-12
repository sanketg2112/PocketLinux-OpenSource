package com.sg.linuxgo.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Canonical “what it is / isn’t” copy for onboarding + Learn More. */
internal object LinuxExpectations {
    val isItems = listOf(
        "Full Linux desktop, terminal, and apps on Android",
        "Install packages with apt, pacman, and more",
        "No root, dual boot, or PC required",
        "Great for coding, learning, scripts, and browsing"
    )

    val isNotItems = listOf(
        "Not a dual-boot or separate OS install",
        "Not a full PC or server (no Docker, systemd, kernel modules)",
        "Not always as fast as a laptop under heavy work",
        "Not real root privileges — rootless Linux on Android",
        "Not a separate Android user — Linux uses this app’s access"
    )

    const val sweetSpot =
        "Sweet spot: everyday desktop, packages, coding, and learning Linux on the go."

    const val shortBody =
        "Real Linux userspace on your phone — with clear boundaries."
}

/**
 * Two clean stacked cards: What it is / What it’s not.
 * Compact, scannable, no alarm styling.
 */
@Composable
internal fun WhatItIsWhatItIsNotCards(
    isItems: List<String> = LinuxExpectations.isItems,
    isNotItems: List<String> = LinuxExpectations.isNotItems,
    accentColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBg: Color,
    border: Color,
    showSweetSpot: Boolean = true,
    compact: Boolean = false
) {
    val gap = if (compact) 10.dp else 12.dp
    val itemGap = if (compact) 8.dp else 10.dp
    val pad = if (compact) 12.dp else 14.dp
    val titleSize = if (compact) 12.sp else 13.sp
    val bodySize = if (compact) 13.sp else 14.sp
    val lineHeight = if (compact) 18.sp else 20.sp

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        ExpectationCard(
            label = "What it is",
            items = isItems,
            marker = "✓",
            markerColor = accentColor,
            markerBg = accentColor.copy(alpha = 0.14f),
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            cardBg = cardBg,
            border = border,
            pad = pad,
            titleSize = titleSize,
            bodySize = bodySize,
            lineHeight = lineHeight,
            itemGap = itemGap
        )

        ExpectationCard(
            label = "What it’s not",
            items = isNotItems,
            marker = "–",
            markerColor = textSecondary,
            markerBg = textSecondary.copy(alpha = 0.12f),
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            cardBg = cardBg,
            border = border,
            pad = pad,
            titleSize = titleSize,
            bodySize = bodySize,
            lineHeight = lineHeight,
            itemGap = itemGap
        )

        if (showSweetSpot) {
            Text(
                text = LinuxExpectations.sweetSpot,
                color = textSecondary,
                fontSize = if (compact) 12.sp else 13.sp,
                fontFamily = FontFamily.Default,
                lineHeight = if (compact) 17.sp else 18.sp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun ExpectationCard(
    label: String,
    items: List<String>,
    marker: String,
    markerColor: Color,
    markerBg: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBg: Color,
    border: Color,
    pad: androidx.compose.ui.unit.Dp,
    titleSize: androidx.compose.ui.unit.TextUnit,
    bodySize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    itemGap: androidx.compose.ui.unit.Dp
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(itemGap)
    ) {
        Text(
            text = label.uppercase(),
            color = textSecondary,
            fontSize = titleSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            fontFamily = FontFamily.Default
        )

        items.forEach { line ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(markerBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = marker,
                        color = markerColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Default
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = line,
                    color = textPrimary,
                    fontSize = bodySize,
                    fontFamily = FontFamily.Default,
                    lineHeight = lineHeight,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
