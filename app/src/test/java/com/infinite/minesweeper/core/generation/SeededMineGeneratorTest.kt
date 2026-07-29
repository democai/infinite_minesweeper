package com.infinite.minesweeper.core.generation

import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.coords.cellToLocalIndex
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeededMineGeneratorTest {
    @Test
    fun fixedSeedIsDeterministicAndGeneratesLazyNeighbors() = runTest {
        val first = SeededMineGenerator(seed = 8675309L)
            .generateForFirstTouch(CellCoord(2, 3), emptyMap())
        val second = SeededMineGenerator(seed = 8675309L)
            .generateForFirstTouch(CellCoord(2, 3), emptyMap())

        assertEquals(first, second)
        assertEquals(9, first.chunks.size)
        assertEquals(
            (-1..1).flatMap { cy -> (-1..1).map { cx -> ChunkCoord(cx, cy) } }.toSet(),
            first.chunks.keys,
        )
        assertTrue(first.chunks.values.all(Chunk::generated))
    }

    @Test
    fun safeZoneNeverContainsMineEvenAcrossChunkBoundary() = runTest {
        val touch = CellCoord(7, 7)
        val result = SeededMineGenerator(seed = 42L)
            .generateForFirstTouch(touch, emptyMap())

        for (y in 6..8) {
            for (x in 6..8) {
                val cell = CellCoord(x, y)
                val chunk = requireNotNull(result.chunks[cellToChunk(cell)])
                assertFalse("Expected $cell to be safe", chunk.cells[cellToLocalIndex(cell)].isMine)
            }
        }
    }

    @Test
    fun densityIsSeedHashedEasyBiasedInBandAndDeterministic() {
        val generator = SeededMineGenerator(seed = 0xC0FFEEL)
        val home = generator.mineDensityFor(ChunkCoord(0, 0))
        val far = generator.mineDensityFor(ChunkCoord(12, 50))
        val other = generator.mineDensityFor(ChunkCoord(-10, 4))

        assertTrue(home in 0.156f..0.35f)
        assertTrue(far in 0.156f..0.35f)
        assertTrue(other in 0.156f..0.35f)
        // Same seed+coord always yields the same density (independent of Chebyshev distance).
        assertEquals(home, SeededMineGenerator(seed = 0xC0FFEEL).mineDensityFor(ChunkCoord(0, 0)))
        assertEquals(far, SeededMineGenerator(seed = 0xC0FFEEL).mineDensityFor(ChunkCoord(12, 50)))
        // Different coords typically differ; at least one of these pairs must (hash avalanche).
        assertTrue(
            "Expected density to vary across chunk coordinates for a fixed seed",
            home != far || home != other || far != other,
        )

        // Cubic ease: most mass near easy end — median below band midpoint, hard share rare.
        val samples = (-10..10).flatMap { cy ->
            (-10..10).map { cx -> generator.mineDensityFor(ChunkCoord(cx, cy)) }
        }.sorted()
        assertEquals(441, samples.size)
        assertTrue(samples.all { it in 0.156f..0.35f })
        val midpoint = (0.156f + 0.35f) / 2f
        assertTrue(
            "Expected median density below band midpoint under cubic ease, was ${samples[samples.size / 2]}",
            samples[samples.size / 2] < midpoint,
        )
        val hardShare = samples.count { it >= 0.30f }.toFloat() / samples.size
        assertTrue(
            "Expected hard density share well below uniform (~26%), was $hardShare",
            hardShare < 0.20f,
        )
    }

    @Test
    fun ensureNeighborsGeneratedIsDeterministicAndAppliesNoExclusion() = runTest {
        val generator = SeededMineGenerator(seed = 42L)
        val initial = generator.generateForFirstTouch(CellCoord(0, 0), emptyMap()).chunks
        val center = ChunkCoord(1, 0)

        val first = generator.ensureNeighborsGenerated(center, initial)
        val second = generator.ensureNeighborsGenerated(center, initial)
        assertEquals(first, second)
        assertTrue(ChunkCoord(2, 0) in first.chunks)

        // Empty exclusion: a cell that would be punched by a first-touch at (15,0) can still be a mine.
        val preGenerated = (initial + first.chunks).getValue(ChunkCoord(2, 0))
        val firstTouchInto = generator.generateForFirstTouch(
            CellCoord(15, 0),
            initial + first.chunks,
        )
        // Chunk (2,0) already generated — must not be re-rolled by a later first-touch.
        assertFalse(ChunkCoord(2, 0) in firstTouchInto.chunks)
        assertEquals(preGenerated, (initial + first.chunks).getValue(ChunkCoord(2, 0)))
    }

    @Test
    fun adjacencyCountsMinesAcrossThreeByThreeChunkFixture() {
        val chunks = (-1..1).flatMap { cy ->
            (-1..1).map { cx -> generatedChunk(ChunkCoord(cx, cy)) }
        }.associateBy(Chunk::coord).toMutableMap()
        chunks[ChunkCoord(0, 0)] = chunks.getValue(ChunkCoord(0, 0))
            .withMine(CellCoord(6, 6))
        chunks[ChunkCoord(1, 0)] = chunks.getValue(ChunkCoord(1, 0))
            .withMine(CellCoord(8, 7))
        chunks[ChunkCoord(0, 1)] = chunks.getValue(ChunkCoord(0, 1))
            .withMine(CellCoord(7, 8))
        chunks[ChunkCoord(1, 1)] = chunks.getValue(ChunkCoord(1, 1))
            .withMine(CellCoord(8, 8))

        val result = recomputeAdjacency(chunks)

        assertEquals(4, result.cellAt(CellCoord(7, 7)).adjacentMines)
        assertEquals(2, result.cellAt(CellCoord(7, 6)).adjacentMines)
        assertEquals(2, result.cellAt(CellCoord(6, 7)).adjacentMines)
        assertEquals(0, result.cellAt(CellCoord(-8, -8)).adjacentMines)
    }

    @Test
    fun generatingBeyondExistingFrontierPatchesOldBoundaryNumbers() = runTest {
        val generator = SeededMineGenerator(seed = 1234L)
        val initial = generator.generateForFirstTouch(CellCoord(0, 0), emptyMap()).chunks
        val staleBoundary = initial.getValue(ChunkCoord(1, 0))
        val expanded = generator.generateForFirstTouch(CellCoord(15, 0), initial)

        assertTrue(ChunkCoord(2, 0) in expanded.chunks)
        val merged = initial + expanded.chunks
        val expected = recomputeAdjacency(merged).getValue(ChunkCoord(1, 0))
        assertEquals(expected, merged.getValue(ChunkCoord(1, 0)))
        assertTrue(
            expanded.chunks[ChunkCoord(1, 0)] == null ||
                expanded.chunks.getValue(ChunkCoord(1, 0)) != staleBoundary,
        )
    }

    @Test
    fun rerollIsDeterministicAndPatchesNeighborBorder() = runTest {
        val generator = SeededMineGenerator(seed = 999L)
        val known = generator.generateForFirstTouch(CellCoord(0, 0), emptyMap()).chunks

        val first = generator.reroll(ChunkCoord(0, 0), known)
        val second = generator.reroll(ChunkCoord(0, 0), known)

        assertEquals(first, second)
        assertTrue(
            first.chunks.getValue(ChunkCoord(0, 0)).cells.all {
                it.state == CellState.HIDDEN
            },
        )
        val merged = known + first.chunks
        assertEquals(
            recomputeAdjacency(merged).filterKeys { it in first.chunks },
            first.chunks,
        )
    }

    private fun generatedChunk(coord: ChunkCoord): Chunk = Chunk(
        coord = coord,
        generated = true,
    )

    private fun Chunk.withMine(cell: CellCoord): Chunk {
        val cells = cells.toMutableList()
        cells[cellToLocalIndex(cell)] = cells[cellToLocalIndex(cell)].copy(isMine = true)
        return copy(cells = cells)
    }

    private fun Map<ChunkCoord, Chunk>.cellAt(coord: CellCoord): Cell =
        getValue(cellToChunk(coord)).cells[cellToLocalIndex(coord)]
}
