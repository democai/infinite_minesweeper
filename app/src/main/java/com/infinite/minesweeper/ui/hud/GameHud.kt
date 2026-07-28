package com.infinite.minesweeper.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

private const val DefaultTitle = "Infinite Minesweeper"

/**
 * Top HUD bar (plan §7): title/mode label, settings entry, viewport coordinate readout,
 * selector-from-Home offset, and the flags/cleared/locked/wiped counters.
 */
@Composable
fun GameHud(
    uiState: HudUiState,
    modifier: Modifier = Modifier,
    title: String = DefaultTitle,
    onSettingsClick: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = BoardPalette.Surface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, style = HudTypography.titleMedium, color = BoardPalette.AccentGold)
                    if (onSettingsClick != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = onSettingsClick) {
                            Text(
                                text = "⚙",
                                style = HudTypography.labelLarge,
                                color = BoardPalette.AccentGold,
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = uiState.selectorLabel,
                        style = HudTypography.labelLarge,
                        color = BoardPalette.AccentGold,
                    )
                    Text(
                        text = uiState.coordinateLabel,
                        style = HudTypography.labelSmall,
                        color = BoardPalette.OnSurface,
                    )
                }
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
    onSettingsClick: (() -> Unit)? = null,
) {
    GameHud(
        uiState = state.toHudUiState(viewportCenterX, viewportCenterY),
        modifier = modifier,
        title = title,
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
