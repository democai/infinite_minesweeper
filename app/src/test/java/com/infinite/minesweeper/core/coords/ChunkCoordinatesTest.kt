package com.infinite.minesweeper.core.coords

import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.ChunkCoord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkCoordinatesTest {
    @Test
    fun negativeBoundaryCoordinatesUseFloorDivisionAndModulus() {
        assertMapping(x = -1, expectedChunkX = -1, expectedLocalX = 7)
        assertMapping(x = -8, expectedChunkX = -1, expectedLocalX = 0)
        assertMapping(x = -9, expectedChunkX = -2, expectedLocalX = 7)
    }

    @Test
    fun bothAxesMapIndependently() {
        val cell = CellCoord(x = -9, y = 8)

        assertEquals(ChunkCoord(cx = -2, cy = 1), cellToChunk(cell))
        assertEquals(LocalCellCoord(x = 7, y = 0), cellToLocal(cell))
        assertEquals(7, cellToLocalIndex(cell))
    }

    @Test
    fun chunkAndLocalRoundTripAcrossOrigin() {
        for (x in -24..24) {
            for (y in -24..24) {
                val cell = CellCoord(x, y)
                assertEquals(cell, chunkLocalToCell(cellToChunk(cell), cellToLocal(cell)))
            }
        }
    }

    @Test
    fun localIndexUsesRowMajorOrderAndRoundTrips() {
        for (index in 0 until 64) {
            assertEquals(index, localIndexToCoord(index).index)
        }
        assertEquals(LocalCellCoord(0, 0), localIndexToCoord(0))
        assertEquals(LocalCellCoord(7, 7), localIndexToCoord(63))
    }

    @Test
    fun invalidLocalIndexIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { localIndexToCoord(-1) }
        assertThrows(IllegalArgumentException::class.java) { localIndexToCoord(64) }
    }

    private fun assertMapping(
        x: Int,
        expectedChunkX: Int,
        expectedLocalX: Int,
    ) {
        val cell = CellCoord(x = x, y = x)
        assertEquals(expectedChunkX, cellToChunk(cell).cx)
        assertEquals(expectedLocalX, cellToLocal(cell).x)
    }
}
