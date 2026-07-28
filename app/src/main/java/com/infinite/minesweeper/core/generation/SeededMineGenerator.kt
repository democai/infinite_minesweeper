package com.infinite.minesweeper.core.generation

import com.infinite.minesweeper.core.coords.LocalCellCoord
import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.coords.chunkLocalToCell
import com.infinite.minesweeper.core.model.CHUNK_SIDE_LENGTH
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GenerationResult
import com.infinite.minesweeper.core.model.MineGenerator
import java.util.Random

private const val BASE_DENSITY = 0.156f
private const val MAX_DENSITY = 0.35f
private const val INITIAL_ROLL_SALT = 0x2A17C4E35B6D8091L
private const val REROLL_SALT = 0x16D1B54A32D192EDL
private const val DENSITY_SALT = 0xD15C0517A17C4E3L

/**
 * A deterministic, per-chunk mine generator.
 *
 * Each chunk receives its own random stream derived from [seed], its coordinate, and the kind of
 * roll. Generation order therefore does not consume shared random state; the only intentional
 * layout difference is removal of mines that fall inside a call's first-touch exclusion zone.
 *
 * Per-chunk mine density is an independent hash of `(seed, coord)` mapped uniformly into
 * [[BASE_DENSITY], [MAX_DENSITY]], so nearby selectors mix easy and hard rather than ramping with
 * distance from the origin.
 */
class SeededMineGenerator(
    private val seed: Long,
) : MineGenerator {
    override fun mineDensityFor(coord: ChunkCoord): Float {
        val u = unitInterval(chunkSeed(coord, DENSITY_SALT))
        return BASE_DENSITY + u.toFloat() * (MAX_DENSITY - BASE_DENSITY)
    }

    override suspend fun generateForFirstTouch(
        firstTouch: CellCoord,
        knownChunks: Map<ChunkCoord, Chunk>,
    ): GenerationResult {
        val center = cellToChunk(firstTouch)
        val generationCoords = neighborhood(center)
        val merged = knownChunks.toMutableMap()
        val newlyGenerated = mutableSetOf<ChunkCoord>()

        generationCoords.forEach { coord ->
            val existing = merged[coord] ?: Chunk(coord = coord)
            if (!existing.generated) {
                merged[coord] = rollChunk(
                    chunk = existing,
                    excludedFromMines = firstTouch.exclusionZone(),
                    salt = INITIAL_ROLL_SALT,
                )
                newlyGenerated += coord
            }
        }

        val adjacencyTargets = generationCoords
            .asSequence()
            .flatMap { neighborhood(it).asSequence() }
            .filterTo(mutableSetOf()) { merged[it]?.generated == true }
        val withAdjacency = recomputeAdjacency(merged, adjacencyTargets)

        val changed = adjacencyTargets
            .filterTo(linkedSetOf()) { coord ->
                coord in newlyGenerated || withAdjacency[coord] != knownChunks[coord]
            }
            .associateWith { coord -> requireNotNull(withAdjacency[coord]) }

        return GenerationResult(changed)
    }

    override suspend fun ensureNeighborsGenerated(
        center: ChunkCoord,
        knownChunks: Map<ChunkCoord, Chunk>,
    ): GenerationResult {
        val merged = knownChunks.toMutableMap()
        val newlyGenerated = mutableSetOf<ChunkCoord>()

        for (coord in neighborhood(center)) {
            if (coord == center) continue
            val existing = merged[coord] ?: Chunk(coord = coord)
            if (!existing.generated) {
                merged[coord] = rollChunk(
                    chunk = existing,
                    excludedFromMines = emptySet(),
                    salt = INITIAL_ROLL_SALT,
                )
                newlyGenerated += coord
            }
        }

        if (newlyGenerated.isEmpty()) return GenerationResult(emptyMap())

        val adjacencyTargets = newlyGenerated
            .asSequence()
            .flatMap { neighborhood(it).asSequence() }
            .filterTo(mutableSetOf()) { merged[it]?.generated == true }
        if (merged[center]?.generated == true) adjacencyTargets += center

        val withAdjacency = recomputeAdjacency(merged, adjacencyTargets)
        val changed = adjacencyTargets
            .filterTo(linkedSetOf()) { coord ->
                coord in newlyGenerated || withAdjacency[coord] != knownChunks[coord]
            }
            .associateWith { coord -> requireNotNull(withAdjacency[coord]) }

        return GenerationResult(changed)
    }

    override suspend fun reroll(
        coord: ChunkCoord,
        knownChunks: Map<ChunkCoord, Chunk>,
    ): GenerationResult {
        val previous = knownChunks[coord] ?: Chunk(coord = coord)
        val rerolled = rollChunk(
            chunk = previous.copy(
                cells = List(CHUNK_SIDE_LENGTH * CHUNK_SIDE_LENGTH) { Cell() },
                status = ChunkStatus.NORMAL,
                everSurrounded = false,
                lockedAt = null,
            ),
            excludedFromMines = emptySet(),
            salt = REROLL_SALT,
        )
        val merged = knownChunks.toMutableMap().apply { put(coord, rerolled) }
        val adjacencyTargets = neighborhood(coord)
            .filterTo(linkedSetOf()) { merged[it]?.generated == true }
        val withAdjacency = recomputeAdjacency(merged, adjacencyTargets)
        val changed = adjacencyTargets
            .filterTo(linkedSetOf()) { target ->
                target == coord || withAdjacency[target] != knownChunks[target]
            }
            .associateWith { target -> requireNotNull(withAdjacency[target]) }

        return GenerationResult(changed)
    }

    private fun rollChunk(
        chunk: Chunk,
        excludedFromMines: Set<CellCoord>,
        salt: Long,
    ): Chunk {
        val random = Random(chunkSeed(chunk.coord, salt))
        val density = mineDensityFor(chunk.coord).toDouble()
        val cells = chunk.cells.mapIndexed { index, cell ->
            val local = LocalCellCoord(
                x = index % CHUNK_SIDE_LENGTH,
                y = index / CHUNK_SIDE_LENGTH,
            )
            val world = chunkLocalToCell(chunk.coord, local)
            val isMine = random.nextDouble() < density && world !in excludedFromMines
            cell.copy(
                state = if (cell.state == CellState.EXPLODED && !isMine) {
                    CellState.HIDDEN
                } else {
                    cell.state
                },
                isMine = isMine,
                adjacentMines = 0,
            )
        }
        return chunk.copy(generated = true, cells = cells)
    }

    private fun chunkSeed(coord: ChunkCoord, salt: Long): Long {
        var mixed = seed xor salt
        mixed = mixed xor (coord.cx.toLong() * -7046029254386353131L)
        mixed = mixed xor (coord.cy.toLong() * -4658895280553007687L)
        mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
        mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
        return mixed xor (mixed ushr 31)
    }
}

/** Maps a 64-bit mix into `[0, 1)` using the top 53 bits (same construction as `Random.nextDouble`). */
internal fun unitInterval(mixed: Long): Double =
    (mixed ushr 11).toDouble() * (1.0 / (1L shl 53).toDouble())

/**
 * Recomputes adjacency for generated [targets], counting mines in every generated chunk supplied
 * in [chunks]. Unknown chunks contribute no mines; when one is generated later, its generated
 * neighbors are included in that call's patch set.
 */
fun recomputeAdjacency(
    chunks: Map<ChunkCoord, Chunk>,
    targets: Set<ChunkCoord> = chunks.keys,
): Map<ChunkCoord, Chunk> {
    val result = chunks.toMutableMap()
    targets.forEach { coord ->
        val chunk = chunks[coord] ?: return@forEach
        if (!chunk.generated) return@forEach

        val cells = chunk.cells.mapIndexed { index, cell ->
            val world = chunkLocalToCell(
                coord,
                LocalCellCoord(
                    x = index % CHUNK_SIDE_LENGTH,
                    y = index / CHUNK_SIDE_LENGTH,
                ),
            )
            cell.copy(adjacentMines = adjacentMineCount(world, chunks))
        }
        result[coord] = chunk.copy(cells = cells)
    }
    return result
}

private fun adjacentMineCount(
    cell: CellCoord,
    chunks: Map<ChunkCoord, Chunk>,
): Int {
    var count = 0
    for (dy in -1..1) {
        for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val neighborX = cell.x.toLong() + dx
            val neighborY = cell.y.toLong() + dy
            if (neighborX !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) continue
            if (neighborY !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) continue

            val neighbor = CellCoord(neighborX.toInt(), neighborY.toInt())
            val neighborChunk = chunks[cellToChunk(neighbor)] ?: continue
            if (!neighborChunk.generated) continue
            val localX = Math.floorMod(neighbor.x, CHUNK_SIDE_LENGTH)
            val localY = Math.floorMod(neighbor.y, CHUNK_SIDE_LENGTH)
            if (neighborChunk.cells[localY * CHUNK_SIDE_LENGTH + localX].isMine) count++
        }
    }
    return count
}

private fun neighborhood(center: ChunkCoord): Set<ChunkCoord> = buildSet(9) {
    for (dy in -1..1) {
        for (dx in -1..1) {
            val cx = center.cx.toLong() + dx
            val cy = center.cy.toLong() + dy
            if (cx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                cy in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            ) {
                add(ChunkCoord(cx.toInt(), cy.toInt()))
            }
        }
    }
}

private fun CellCoord.exclusionZone(): Set<CellCoord> = buildSet(9) {
    for (dy in -1..1) {
        for (dx in -1..1) {
            val safeX = x.toLong() + dx
            val safeY = y.toLong() + dy
            if (safeX in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                safeY in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            ) {
                add(CellCoord(safeX.toInt(), safeY.toInt()))
            }
        }
    }
}
