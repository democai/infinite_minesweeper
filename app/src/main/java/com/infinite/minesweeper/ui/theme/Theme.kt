package com.infinite.minesweeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BoardPalette.AccentGold,
    onPrimary = BoardPalette.Background,
    secondary = BoardPalette.OnSurface,
    onSecondary = BoardPalette.Background,
    background = BoardPalette.Background,
    onBackground = BoardPalette.OnSurface,
    surface = BoardPalette.Surface,
    onSurface = BoardPalette.OnSurface,
    surfaceVariant = BoardPalette.CellHidden,
    onSurfaceVariant = BoardPalette.HudMuted,
    error = BoardPalette.MineExploded,
    onError = Color.White,
)

@Composable
fun InfiniteMinesweeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = HudTypography,
        content = content,
    )
}
