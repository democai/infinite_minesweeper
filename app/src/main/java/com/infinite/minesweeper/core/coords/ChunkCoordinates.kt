package com.infinite.minesweeper.core.coords

import com.infinite.minesweeper.core.model.CELLS_PER_CHUNK
import com.infinite.minesweeper.core.model.CHUNK_SIDE_LENGTH
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.ChunkCoord

/**
 * A cell coordinate relative to the top-left of a chunk.
 */
data class LocalCellCoord(
    val x: Int,
    val y: Int,
) {
    init {
        require(x in 0 until CHUNK_SIDE_LENGTH) {
            "Local x must be in 0 until $CHUNK_SIDE_LENGTH, but was $x"
        }
        require(y in 0 until CHUNK_SIDE_LENGTH) {
            "Local y must be in 0 until $CHUNK_SIDE_LENGTH, but was $y"
        }
    }

    val index: Int
        get() = y * CHUNK_SIDE_LENGTH + x
}

/**
 * The chunk containing [cell].
 *
 * [Math.floorDiv] is intentional: integer division truncates toward zero and therefore assigns
 * negative cells to the wrong chunk.
 */
fun cellToChunk(cell: CellCoord): ChunkCoord = ChunkCoord(
    cx = Math.floorDiv(cell.x, CHUNK_SIDE_LENGTH),
    cy = Math.floorDiv(cell.y, CHUNK_SIDE_LENGTH),
)

/**
 * The position of [cell] inside its chunk.
 *
 * [Math.floorMod] keeps both axes in `0..7`, including for negative world coordinates.
 */
fun cellToLocal(cell: CellCoord): LocalCellCoord = LocalCellCoord(
    x = Math.floorMod(cell.x, CHUNK_SIDE_LENGTH),
    y = Math.floorMod(cell.y, CHUNK_SIDE_LENGTH),
)

fun cellToLocalIndex(cell: CellCoord): Int = cellToLocal(cell).index

fun localIndexToCoord(index: Int): LocalCellCoord {
    require(index in 0 until CELLS_PER_CHUNK) {
        "Local index must be in 0 until $CELLS_PER_CHUNK, but was $index"
    }
    return LocalCellCoord(
        x = index % CHUNK_SIDE_LENGTH,
        y = index / CHUNK_SIDE_LENGTH,
    )
}

/**
 * Returns the global coordinate for [local] in [chunk].
 *
 * Chunk coordinates near the limits of [Int] can describe global cells outside the model's
 * integer range. Such conversions fail instead of silently wrapping.
 */
fun chunkLocalToCell(
    chunk: ChunkCoord,
    local: LocalCellCoord,
): CellCoord {
    val globalX = chunk.cx.toLong() * CHUNK_SIDE_LENGTH + local.x
    val globalY = chunk.cy.toLong() * CHUNK_SIDE_LENGTH + local.y
    require(globalX in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Chunk/local x is outside the global cell coordinate range"
    }
    require(globalY in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Chunk/local y is outside the global cell coordinate range"
    }
    return CellCoord(globalX.toInt(), globalY.toInt())
}
