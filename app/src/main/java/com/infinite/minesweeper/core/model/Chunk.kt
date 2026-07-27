package com.infinite.minesweeper.core.model

const val CHUNK_SIDE_LENGTH: Int = 8
const val CELLS_PER_CHUNK: Int = CHUNK_SIDE_LENGTH * CHUNK_SIDE_LENGTH

/**
 * Persistence-independent chunk representation shared by the engine, cache, and data layer.
 */
data class Chunk(
    val coord: ChunkCoord,
    val generated: Boolean = false,
    val cells: List<Cell> = List(CELLS_PER_CHUNK) { Cell() },
    val status: ChunkStatus = ChunkStatus.NORMAL,
    val everSurrounded: Boolean = false,
    val lockedAt: Long? = null,
) {
    init {
        require(cells.size == CELLS_PER_CHUNK) {
            "A chunk must contain exactly $CELLS_PER_CHUNK cells, but contained ${cells.size}"
        }
        require(status == ChunkStatus.LOCKED || lockedAt == null) {
            "Only a locked chunk may have lockedAt set"
        }
    }
}
