package com.infinite.minesweeper.core.model

/**
 * Ground-truth HUD progress derived from durable chunk cells.
 *
 * [GameMeta.flagsPlaced] / [GameMeta.selectorsCleared] are maintained incrementally during play,
 * but can drift (e.g. a mid-cascade flush that observed a shared mutable chunk map). Recounting
 * from the board is the authority for FLAGS / CLEARED.
 */
data class ProgressCounters(
    val flagsPlaced: Int,
    val selectorsCleared: Int,
) {
    init {
        require(flagsPlaced >= 0) { "flagsPlaced cannot be negative" }
        require(selectorsCleared >= 0) { "selectorsCleared cannot be negative" }
    }
}

/** Counts flagged cells and currently solved selectors across [chunks]. */
fun recountProgress(chunks: Collection<Chunk>): ProgressCounters {
    var flagsPlaced = 0
    var selectorsCleared = 0
    for (chunk in chunks) {
        for (cell in chunk.cells) {
            if (cell.state == CellState.FLAGGED) flagsPlaced++
        }
        if (chunk.isSolved) selectorsCleared++
    }
    return ProgressCounters(
        flagsPlaced = flagsPlaced,
        selectorsCleared = selectorsCleared,
    )
}

/** Returns a copy of this meta with FLAGS / CLEARED replaced by [recountProgress] of [chunks]. */
fun GameMeta.withRecountedProgress(chunks: Collection<Chunk>): GameMeta {
    val recounted = recountProgress(chunks)
    if (flagsPlaced == recounted.flagsPlaced && selectorsCleared == recounted.selectorsCleared) {
        return this
    }
    return copy(
        flagsPlaced = recounted.flagsPlaced,
        selectorsCleared = recounted.selectorsCleared,
    )
}
