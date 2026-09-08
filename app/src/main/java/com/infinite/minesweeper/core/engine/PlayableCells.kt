package com.infinite.minesweeper.core.engine

import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.coords.cellToLocalIndex
import com.infinite.minesweeper.core.engine.lock.neighboringChunkCoords
import com.infinite.minesweeper.core.model.CHUNK_SIDE_LENGTH
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus

/**
 * True when [cell] may be touched by a direct player action: it's Moore-adjacent to a
 * [CellState.REVEALED] cell, or it qualifies for the bounded "solved ring" exception (a
 * pocket of hidden cells fully enclosed by already-solved territory, which would otherwise
 * be permanently unreachable — e.g. an interior cell whose entire neighborhood is flagged
 * mines). Cascaded/chorded reveals are trusted consequences of one already-gated action and
 * must not call this per cell.
 */
internal fun isPlayableCell(cell: CellCoord, chunks: Map<ChunkCoord, Chunk>): Boolean {
    if (neighbors8(cell).any { neighbor ->
            cellAt(neighbor, chunks)?.state == CellState.REVEALED
        }
    ) {
        return true
    }
    return isInSolvedRing(cell, chunks)
}

private fun isInSolvedRing(cell: CellCoord, chunks: Map<ChunkCoord, Chunk>): Boolean {
    val coord = cellToChunk(cell)
    if (isChunkSolvedRing(coord, chunks)) return true
    return floodFillEnclosed(cell, coord, chunks)
}

/**
 * Fast path: mirrors [com.infinite.minesweeper.core.engine.lock.LockAndWipeMechanic]'s
 * surrounded-selector check — a chunk whose 8 neighbors are all solved (locked peers
 * skipped, same as there) has every one of its hidden cells playable without the more
 * expensive per-cell fallback below.
 */
private fun isChunkSolvedRing(coord: ChunkCoord, chunks: Map<ChunkCoord, Chunk>): Boolean {
    val neighbors = neighboringChunkCoords(coord)
    if (neighbors.size != 8) return false
    var solvedCount = 0
    for (neighborCoord in neighbors) {
        val neighbor = chunks[neighborCoord] ?: return false
        if (neighbor.status == ChunkStatus.LOCKED) continue
        if (!neighbor.isSolved) return false
        solvedCount++
    }
    return solvedCount > 0
}

/**
 * Fallback: bounded flood-fill over HIDDEN cells starting at [start], walled by any
 * REVEALED/FLAGGED/EXPLODED cell, strictly limited to the 3x3-chunk block (24x24 cells)
 * centered on [homeChunk] — "limit the blast radius to 8 selectors". Escaping that window,
 * or needing to expand into an absent/ungenerated chunk, fails closed (not proven enclosed),
 * matching the fail-closed convention used elsewhere for missing chunk data.
 */
private fun floodFillEnclosed(
    start: CellCoord,
    homeChunk: ChunkCoord,
    chunks: Map<ChunkCoord, Chunk>,
): Boolean {
    val minX = homeChunk.cx * CHUNK_SIDE_LENGTH - CHUNK_SIDE_LENGTH
    val minY = homeChunk.cy * CHUNK_SIDE_LENGTH - CHUNK_SIDE_LENGTH
    val maxX = minX + 3 * CHUNK_SIDE_LENGTH - 1
    val maxY = minY + 3 * CHUNK_SIDE_LENGTH - 1

    val visited = hashSetOf(start)
    val queue = ArrayDeque<CellCoord>().apply { add(start) }
    while (queue.isNotEmpty()) {
        val cell = queue.removeFirst()
        for (neighbor in neighbors8(cell)) {
            if (neighbor.x !in minX..maxX || neighbor.y !in minY..maxY) return false
            if (!visited.add(neighbor)) continue
            val chunk = chunks[cellToChunk(neighbor)]
            if (chunk == null || !chunk.generated) return false
            if (chunk.cells[cellToLocalIndex(neighbor)].state == CellState.HIDDEN) {
                queue.add(neighbor)
            }
        }
    }
    return true
}

private fun cellAt(cell: CellCoord, chunks: Map<ChunkCoord, Chunk>) =
    chunks[cellToChunk(cell)]
        ?.takeIf { it.generated }
        ?.cells
        ?.get(cellToLocalIndex(cell))
