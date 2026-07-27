package com.infinite.minesweeper.ui.hud

import com.infinite.minesweeper.core.model.GameState
import kotlin.math.roundToInt

/**
 * Presentation-ready snapshot of the top HUD bar (plan §7).
 *
 * No global "mines remaining" counter exists by design: the board is infinite.
 */
data class HudUiState(
    val coordinateLabel: String,
    val flagsPlaced: Int,
    val selectorsCleared: Int,
    val selectorsLocked: Int,
    val selectorsWiped: Int,
)

/**
 * Derives the HUD snapshot from a live [GameState] and the viewport's world-space center.
 *
 * [GameState.selectorsLocked] is already a live count over [GameState.chunks], so this mapper
 * stays a pure, allocation-light projection with no state of its own — safe to call on every
 * engine/viewport emission.
 */
fun GameState.toHudUiState(viewportCenterX: Double, viewportCenterY: Double): HudUiState = HudUiState(
    coordinateLabel = formatCoordinateLabel(viewportCenterX, viewportCenterY),
    flagsPlaced = meta.flagsPlaced,
    selectorsCleared = meta.selectorsCleared,
    selectorsLocked = selectorsLocked,
    selectorsWiped = meta.selectorsWiped,
)

/** Matches the reference readout style, e.g. `X: -76 Y: 59`. */
fun formatCoordinateLabel(centerX: Double, centerY: Double): String =
    "X: ${centerX.roundToInt()} Y: ${centerY.roundToInt()}"
