package com.infinite.minesweeper.core.codec

import com.infinite.minesweeper.core.model.CELLS_PER_CHUNK
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellState

/**
 * Stable 64-byte chunk blob packer/unpacker.
 *
 * Per-cell byte layout (bit 0 = LSB):
 * - bits 0–1: state (0 hidden, 1 revealed, 2 flagged, 3 exploded)
 * - bit 2: mine
 * - bits 3–6: adjacency 0–8
 * - bit 7: spare (always 0 on write)
 *
 * Owned by T2; consumed by the Room mapper in T3 so packing stays DRY.
 */
object ChunkBlobCodec {
    const val BLOB_SIZE: Int = CELLS_PER_CHUNK

    private const val STATE_MASK = 0b11
    private const val MINE_BIT = 1 shl 2
    private const val ADJACENCY_SHIFT = 3
    private const val ADJACENCY_MASK = 0b1111

    fun pack(cells: List<Cell>): ByteArray {
        require(cells.size == CELLS_PER_CHUNK) {
            "Expected $CELLS_PER_CHUNK cells, got ${cells.size}"
        }
        val blob = ByteArray(BLOB_SIZE)
        for (i in cells.indices) {
            blob[i] = packCell(cells[i])
        }
        return blob
    }

    fun unpack(blob: ByteArray): List<Cell> {
        require(blob.size == BLOB_SIZE) {
            "Expected $BLOB_SIZE-byte blob, got ${blob.size}"
        }
        return List(BLOB_SIZE) { unpackCell(blob[it]) }
    }

    /**
     * Compatibility name used by the T2 contract tests and persistence callers that describe
     * serialization as encoding.
     */
    fun encode(cells: List<Cell>): ByteArray = pack(cells)

    /**
     * Compatibility name used by the T2 contract tests and persistence callers that describe
     * deserialization as decoding.
     */
    fun decode(blob: ByteArray): List<Cell> = unpack(blob)

    private fun packCell(cell: Cell): Byte {
        val stateBits = when (cell.state) {
            CellState.HIDDEN -> 0
            CellState.REVEALED -> 1
            CellState.FLAGGED -> 2
            CellState.EXPLODED -> 3
        }
        val mineBit = if (cell.isMine) MINE_BIT else 0
        val adjacencyBits = (cell.adjacentMines and ADJACENCY_MASK) shl ADJACENCY_SHIFT
        return (stateBits or mineBit or adjacencyBits).toByte()
    }

    private fun unpackCell(raw: Byte): Cell {
        val value = raw.toInt() and 0xFF
        val state = when (value and STATE_MASK) {
            0 -> CellState.HIDDEN
            1 -> CellState.REVEALED
            2 -> CellState.FLAGGED
            3 -> CellState.EXPLODED
            else -> error("Unreachable state bits")
        }
        val isMine = value and MINE_BIT != 0
        val adjacentMines = (value shr ADJACENCY_SHIFT) and ADJACENCY_MASK
        require(adjacentMines in 0..8) {
            "Invalid adjacency $adjacentMines in packed cell byte 0x${value.toString(16)}"
        }
        return Cell(state = state, isMine = isMine, adjacentMines = adjacentMines)
    }
}
