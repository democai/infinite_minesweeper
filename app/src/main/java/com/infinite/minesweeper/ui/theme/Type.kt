package com.infinite.minesweeper.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * HUD and board typography.
 *
 * Material roles map as:
 * - [Typography.titleMedium] — top-bar title / mode label
 * - [Typography.labelLarge] — viewport coordinate readout (`X: -76 Y: 59`)
 * - [Typography.labelMedium] — counter values (flags, cleared, locked, wiped)
 * - [Typography.labelSmall] — counter captions
 * - [Typography.bodyLarge] — revealed cell digits (Canvas measurer uses size; color from palette)
 */
val HudTypography = Typography(
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
)

/** Suggested digit size as a fraction of the on-screen cell edge for Canvas text. */
const val CellDigitSizeFraction: Float = 0.55f
