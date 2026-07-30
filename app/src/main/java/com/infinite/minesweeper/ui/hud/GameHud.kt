package com.infinite.minesweeper.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.ui.theme.BoardPalette
import com.infinite.minesweeper.ui.theme.HudTypography

/**
 * Top HUD bar (plan §7): settings entry, selector-from-Home offset, and the flags/cleared/locked
 * counters, kept to a single tight row.
 */
@Composable
fun GameHud(
    uiState: HudUiState,
    modifier: Modifier = Modifier,
    onSettingsClick: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = BoardPalette.Surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onSettingsClick != null) {
                TextButton(onClick = onSettingsClick) {
                    Text(text = "⚙", style = HudTypography.labelLarge, color = BoardPalette.AccentGold)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = uiState.selectorLabel,
                    style = HudTypography.labelLarge,
                    color = BoardPalette.AccentGold,
                )
                HudCounter(label = "FLAGS", value = uiState.flagsPlaced, valueColor = BoardPalette.OnSurface)
                HudCounter(
                    label = "CLEARED",
                    value = uiState.selectorsCleared,
                    valueColor = BoardPalette.OnSurface,
                )
                HudCounter(
                    label = "LOCKED",
                    value = uiState.selectorsLocked,
                    valueColor = if (uiState.selectorsLocked > 0) BoardPalette.Flag else BoardPalette.HudMuted,
                )
            }
        }
    }
}

/**
 * Convenience overload for callers holding raw engine/viewport values instead of a pre-built
 * [HudUiState].
 */
@Composable
fun GameHud(
    state: GameState,
    viewportCenterX: Double,
    viewportCenterY: Double,
    modifier: Modifier = Modifier,
    onSettingsClick: (() -> Unit)? = null,
) {
    GameHud(
        uiState = state.toHudUiState(viewportCenterX, viewportCenterY),
        modifier = modifier,
        onSettingsClick = onSettingsClick,
    )
}

@Composable
private fun HudCounter(label: String, value: Int, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value.toString(), style = HudTypography.labelMedium, color = valueColor)
        Text(text = label, style = HudTypography.labelSmall, color = BoardPalette.HudMuted)
    }
}
