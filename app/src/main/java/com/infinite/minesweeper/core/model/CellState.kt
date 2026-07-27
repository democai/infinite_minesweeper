package com.infinite.minesweeper.core.model

/**
 * Player-visible state of a cell.
 *
 * The ordinal is deliberately not used by the persistence format. The blob codec owns the
 * stable two-bit mapping so this enum can remain a domain contract.
 */
enum class CellState {
    HIDDEN,
    REVEALED,
    FLAGGED,
    EXPLODED,
}

/**
 * Complete in-memory representation of one cell.
 *
 * Mine locations are domain data, but renderers must not expose [isMine] for hidden cells.
 */
data class Cell(
    val state: CellState = CellState.HIDDEN,
    val isMine: Boolean = false,
    val adjacentMines: Int = 0,
) {
    init {
        require(adjacentMines in 0..8) {
            "adjacentMines must be in 0..8, but was $adjacentMines"
        }
        require(state != CellState.EXPLODED || isMine) {
            "Only a mine can be in the exploded state"
        }
    }
}
