package com.infinite.minesweeper.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.ui.theme.BoardPalette
import com.infinite.minesweeper.ui.theme.HudTypography

private const val DefaultTitle = "Infinite Minesweeper"

/**
 * Top HUD bar (plan §7): title/mode label, viewport coordinate readout, and the
 * flags/cleared/locked/wiped counters. A stateless renderer — callers own deriving [HudUiState]
 * from the live [GameState] and viewport on every frame that needs it.
 */
@Composable
fun GameHud(
    uiState: HudUiState,
    modifier: Modifier = Modifier,
    title: String = DefaultTitle,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = BoardPalette.Surface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, style = HudTypography.titleMedium, color = BoardPalette.AccentGold)
                Text(
                    text = uiState.coordinateLabel,
                    style = HudTypography.labelLarge,
                    color = BoardPalette.OnSurface,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                HudCounter(label = "FLAGS", value = uiState.flagsPlaced, valueColor = BoardPalette.OnSurface)
                HudCounter(
                    label = "CLEARED",
                    value = uiState.selectorsCleared,
                    valueColor = BoardPalette.OnSurface,
                )
                HudCounter(
                    label = "LOCKED",
                    value = uiState.selectorsLocked,
                    // The "uh oh" indicator (plan §7): only reads as an alarm once something is
                    // actually locked, otherwise it would falsely draw the eye at rest.
                    valueColor = if (uiState.selectorsLocked > 0) BoardPalette.Flag else BoardPalette.HudMuted,
                )
                HudCounter(label = "WIPED", value = uiState.selectorsWiped, valueColor = BoardPalette.HudMuted)
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
    title: String = DefaultTitle,
) {
    GameHud(uiState = state.toHudUiState(viewportCenterX, viewportCenterY), modifier = modifier, title = title)
}

@Composable
private fun HudCounter(label: String, value: Int, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value.toString(), style = HudTypography.labelMedium, color = valueColor)
        Text(text = label, style = HudTypography.labelSmall, color = BoardPalette.HudMuted)
    }
}
