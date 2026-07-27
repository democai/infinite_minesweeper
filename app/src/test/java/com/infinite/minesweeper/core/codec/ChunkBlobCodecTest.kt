package com.infinite.minesweeper.core.codec

import com.infinite.minesweeper.core.model.CELLS_PER_CHUNK
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ChunkBlobCodecTest {
    @Test
    fun packUnpack_roundTripsAllCellStates() {
        val cells = List(CELLS_PER_CHUNK) { index ->
            when (index % 4) {
                0 -> Cell(state = CellState.HIDDEN, isMine = true, adjacentMines = 0)
                1 -> Cell(state = CellState.REVEALED, isMine = false, adjacentMines = 8)
                2 -> Cell(state = CellState.FLAGGED, isMine = true, adjacentMines = 3)
                else -> Cell(state = CellState.EXPLODED, isMine = true, adjacentMines = 1)
            }
        }

        val blob = ChunkBlobCodec.pack(cells)
        assertEquals(ChunkBlobCodec.BLOB_SIZE, blob.size)
        assertEquals(cells, ChunkBlobCodec.unpack(blob))
    }

    @Test
    fun encodeDecode_areCompatibleAliases() {
        val cells = List(CELLS_PER_CHUNK) { Cell() }

        assertArrayEquals(ChunkBlobCodec.pack(cells), ChunkBlobCodec.encode(cells))
        assertEquals(cells, ChunkBlobCodec.decode(ChunkBlobCodec.encode(cells)))
    }

    @Test
    fun packUnpack_randomChunksAreStable() {
        val random = Random(seed = 42)
        repeat(50) {
            val cells = List(CELLS_PER_CHUNK) {
                val state = CellState.entries[random.nextInt(CellState.entries.size)]
                val isMine = state == CellState.EXPLODED || random.nextBoolean()
                Cell(
                    state = state,
                    isMine = isMine,
                    adjacentMines = random.nextInt(0, 9),
                )
            }
            val blob = ChunkBlobCodec.pack(cells)
            assertEquals(cells, ChunkBlobCodec.unpack(blob))
            assertArrayEquals(blob, ChunkBlobCodec.pack(ChunkBlobCodec.unpack(blob)))
        }
    }
}
