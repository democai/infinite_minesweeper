package com.infinite.minesweeper.core.engine.lock

import com.infinite.minesweeper.core.generation.recomputeAdjacency
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameEvent
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.core.model.MineGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of consuming one engine transition.
 *
 * [events] contains only the additional transitions caused by the lock mechanic. The triggering
 * T8 event has already been delivered to its consumers and is not repeated.
 */
data class LockTransition(
    val state: GameState,
    val events: List<GameEvent> = emptyList(),
)

/**
 * Consumes T8's [GameEvent.ChunkLocked] and [GameEvent.ChunkCleared] transitions and applies the
 * selector fail rules.
 *
 * The caller serializes calls with game-engine dispatches and publishes [LockTransition.state]
 * before processing the next action. Keeping that ordering explicit avoids a collector racing a
 * subsequent player input, and lets T13 wire this component to either an engine or a ViewModel.
 */
class LockAndWipeMechanic(
    private val mineGenerator: MineGenerator,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun process(
        event: GameEvent,
        state: GameState,
    ): LockTransition = withContext(backgroundDispatcher) {
        when (event) {
            is GameEvent.ChunkLocked -> onChunkLocked(event, state)
            is GameEvent.ChunkCleared -> onChunkCleared(event, state)
            is GameEvent.ChunkSoftResolved,
            is GameEvent.ChunkWiped,
            -> LockTransition(state)
        }
    }

    private suspend fun onChunkLocked(
        event: GameEvent.ChunkLocked,
        state: GameState,
    ): LockTransition {
        val chunk = state.chunks[event.chunk] ?: return LockTransition(state)
        if (chunk.status != ChunkStatus.LOCKED || !chunk.everSurrounded) {
            return LockTransition(state)
        }

        val oldFlagCount = chunk.cells.count { it.state == CellState.FLAGGED }
        val rerolled = mineGenerator.reroll(event.chunk, state.chunks).chunks
        val generatedChunk = requireNotNull(rerolled[event.chunk]) {
            "MineGenerator.reroll must return the wiped chunk ${event.chunk}"
        }

        // Enforce the wipe's domain invariants at this boundary. In particular, a generator must
        // not accidentally preserve flags or the exploded marker from the previous layout.
        val wipedChunk = generatedChunk.copy(
            cells = generatedChunk.cells.map { cell ->
                cell.copy(state = CellState.HIDDEN)
            },
            status = ChunkStatus.NORMAL,
            everSurrounded = false,
            lockedAt = null,
        )
        val chunks = state.chunks.toMutableMap().apply {
            putAll(rerolled)
            put(event.chunk, wipedChunk)
        }
        val updated = state.copy(
            chunks = chunks,
            meta = state.meta.copy(
                flagsPlaced = (state.meta.flagsPlaced - oldFlagCount).coerceAtLeast(0),
                selectorsWiped = state.meta.selectorsWiped + 1,
            ),
        )
        return LockTransition(updated, listOf(GameEvent.ChunkWiped(event.chunk)))
    }

    private fun onChunkCleared(
        event: GameEvent.ChunkCleared,
        state: GameState,
    ): LockTransition {
        var chunks = state.chunks
        val emitted = mutableListOf<GameEvent>()

        // One cleared selector can be shared by several locked selectors. Re-evaluate all of them
        // against the latest working map so each lock resolves (or remains locked) independently.
        for (candidate in neighboringChunkCoords(event.chunk).sortedWith(CHUNK_COORD_ORDER)) {
            val locked = chunks[candidate] ?: continue
            if (locked.status != ChunkStatus.LOCKED) continue
            // A selector only receives one soft save. Normally the preceding ChunkLocked event
            // has already wiped this case; retaining the guard here keeps delayed/duplicated
            // ChunkCleared delivery from granting a second soft resolution.
            if (locked.everSurrounded) continue
            if (!isSurrounded(candidate, chunks)) continue

            chunks = softResolve(candidate, chunks)
            emitted += GameEvent.ChunkSoftResolved(candidate)
        }

        return LockTransition(
            state = if (chunks === state.chunks) state else state.copy(chunks = chunks),
            events = emitted,
        )
    }

    private fun softResolve(
        coord: ChunkCoord,
        chunks: Map<ChunkCoord, Chunk>,
    ): Map<ChunkCoord, Chunk> {
        val locked = chunks.getValue(coord)
        val explodedIndex = locked.cells.indexOfFirst { it.state == CellState.EXPLODED }
        check(explodedIndex >= 0) {
            "Locked chunk $coord must contain its exploded mine"
        }

        val cells = locked.cells.toMutableList()
        val exploded = cells[explodedIndex]
        cells[explodedIndex] = Cell(
            state = CellState.REVEALED,
            isMine = false,
            adjacentMines = exploded.adjacentMines,
        )

        val patched = chunks.toMutableMap().apply {
            put(
                coord,
                locked.copy(
                    cells = cells,
                    status = ChunkStatus.NORMAL,
                    everSurrounded = true,
                    lockedAt = null,
                ),
            )
        }
        val targets = (neighboringChunkCoords(coord) + coord)
            .filterTo(linkedSetOf()) { patched[it]?.generated == true }
        return recomputeAdjacency(patched, targets)
    }

    private fun isSurrounded(
        coord: ChunkCoord,
        chunks: Map<ChunkCoord, Chunk>,
    ): Boolean {
        val neighbors = neighboringChunkCoords(coord)
        if (neighbors.size != 8) return false
        return neighbors.all { neighborCoord ->
            val neighbor = chunks[neighborCoord] ?: return@all false
            neighbor.status != ChunkStatus.LOCKED &&
                neighbor.generated &&
                neighbor.cells.all { it.isMine || it.state == CellState.REVEALED }
        }
    }
}

private val CHUNK_COORD_ORDER = compareBy<ChunkCoord>({ it.cy }, { it.cx })

internal fun neighboringChunkCoords(center: ChunkCoord): Set<ChunkCoord> = buildSet(8) {
    for (dy in -1..1) {
        for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
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
