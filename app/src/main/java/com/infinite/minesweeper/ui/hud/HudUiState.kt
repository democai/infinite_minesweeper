package com.infinite.minesweeper.ui.hud

import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.GameState
import kotlin.math.roundToInt

/** The origin selector — chunk `(0, 0)` — that every other selector is measured against. */
val HomeSelector: ChunkCoord = ChunkCoord(0, 0)

/**
 * Presentation-ready snapshot of the top HUD bar (plan §7).
 *
 * No global "mines remaining" counter exists by design: the board is infinite.
 */
data class HudUiState(
    val coordinateLabel: String,
    val selectorLabel: String,
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
fun GameState.toHudUiState(viewportCenterX: Double, viewportCenterY: Double): HudUiState {
    val selector = cellToChunk(
        CellCoord(viewportCenterX.roundToInt(), viewportCenterY.roundToInt()),
    )
    return HudUiState(
        coordinateLabel = formatCoordinateLabel(viewportCenterX, viewportCenterY),
        selectorLabel = formatSelectorFromHomeLabel(selector),
        flagsPlaced = meta.flagsPlaced,
        selectorsCleared = meta.selectorsCleared,
        selectorsLocked = selectorsLocked,
        selectorsWiped = meta.selectorsWiped,
    )
}

/** Matches the reference readout style, e.g. `X: -76 Y: 59`. */
fun formatCoordinateLabel(centerX: Double, centerY: Double): String =
    "X: ${centerX.roundToInt()} Y: ${centerY.roundToInt()}"

/**
 * Selector (chunk) offset from [HomeSelector], e.g. `SEL: Home`, `SEL: +2, -1`.
 *
 * Chunk `(0, 0)` is Home — the first selector. Positive CX is east, positive CY is south.
 */
fun formatSelectorFromHomeLabel(selector: ChunkCoord): String {
    if (selector == HomeSelector) return "SEL: Home"
    val dx = selector.cx - HomeSelector.cx
    val dy = selector.cy - HomeSelector.cy
    return "SEL: ${formatSigned(dx)}, ${formatSigned(dy)}"
}

private fun formatSigned(value: Int): String = if (value > 0) "+$value" else value.toString()
