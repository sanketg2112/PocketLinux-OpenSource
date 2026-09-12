package com.sg.linuxgo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val MonoFamily = FontFamily.Default

val AppTypography = Typography(
    // Large titles — e.g. "WELCOME_TO_POCKETLINUX"
    headlineLarge = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
    ),
    // Section headers — e.g. container names
    titleMedium = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
    ),
    // Subheadings — e.g. GUI loading status
    titleSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    ),
    // Body — e.g. welcome description text
    bodyMedium = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    // Small body — e.g. terminal output
    bodySmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    // Labels — e.g. button text, step descriptions
    labelLarge = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    ),
    // Labels medium — e.g. key bar keys, step text
    labelMedium = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    // Labels small — e.g. status badges, stats labels
    labelSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
    ),
)
