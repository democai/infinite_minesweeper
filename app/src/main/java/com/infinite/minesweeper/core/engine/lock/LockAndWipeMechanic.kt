package com.infinite.minesweeper.core.engine.lock

import com.infinite.minesweeper.core.generation.recomputeAdjacency
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameEvent
import com.infinite.minesweeper.core.model.GameMeta
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
 * Derived [GameEvent]s in [LockTransition.events] must be fed back through [process] by the caller
 * so cascading lock resolution can continue in the same dispatch.
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

    /**
     * Re-evaluates every soft-save-eligible lock in [state]. Used after window hydration so locks
     * that became surrounded while a neighbor was evicted (or that were locked into an already-
     * cleared ring) still resolve without waiting for another clear event.
     *
     * Runs on the caller's context (no dispatcher hop) so [DefaultGameEngine.syncWindow] can invoke
     * it while holding its mutex without stalling under a test Main dispatcher.
     */
    fun recheckSurroundedLocks(state: GameState): LockTransition =
        trySoftResolveLocks(
            state = state,
            candidates = state.chunks.keys
                .filter { state.chunks[it]?.status == ChunkStatus.LOCKED }
                .sortedWith(CHUNK_COORD_ORDER),
        )

    private suspend fun onChunkLocked(
        event: GameEvent.ChunkLocked,
        state: GameState,
    ): LockTransition {
        val chunk = state.chunks[event.chunk] ?: return LockTransition(state)

        // Second lifetime mine hit: hard wipe (§5.4).
        if (chunk.status == ChunkStatus.LOCKED && chunk.everSurrounded) {
            return hardWipe(event.chunk, state)
        }

        // First lock: if the selector is already enclosed by solved territory, soft-resolve now.
        // Without this, locking into a completed ring waits forever for a ChunkCleared that will
        // never fire again (the screenshot bug).
        if (chunk.status == ChunkStatus.LOCKED && !chunk.everSurrounded) {
            return trySoftResolveLocks(state, listOf(event.chunk))
        }

        return LockTransition(state)
    }

    private fun onChunkCleared(
        event: GameEvent.ChunkCleared,
        state: GameState,
    ): LockTransition = trySoftResolveLocks(
        state = state,
        candidates = neighboringChunkCoords(event.chunk).sortedWith(CHUNK_COORD_ORDER),
    )

    private suspend fun hardWipe(coord: ChunkCoord, state: GameState): LockTransition {
        val chunk = state.chunks.getValue(coord)
        val oldFlagCount = chunk.cells.count { it.state == CellState.FLAGGED }
        val rerolled = mineGenerator.reroll(coord, state.chunks).chunks
        val generatedChunk = requireNotNull(rerolled[coord]) {
            "MineGenerator.reroll must return the wiped chunk $coord"
        }

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
            put(coord, wipedChunk)
        }
        val updated = state.copy(
            chunks = chunks,
            meta = state.meta.copy(
                flagsPlaced = (state.meta.flagsPlaced - oldFlagCount).coerceAtLeast(0),
                selectorsWiped = state.meta.selectorsWiped + 1,
            ),
        )
        return LockTransition(updated, listOf(GameEvent.ChunkWiped(coord)))
    }

    private fun trySoftResolveLocks(
        state: GameState,
        candidates: Collection<ChunkCoord>,
    ): LockTransition {
        var chunks = state.chunks
        var meta = state.meta
        val emitted = mutableListOf<GameEvent>()
        val pending = ArrayDeque(candidates)
        val visited = hashSetOf<ChunkCoord>()

        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            if (!visited.add(candidate)) continue
            val locked = chunks[candidate] ?: continue
            if (locked.status != ChunkStatus.LOCKED) continue
            if (locked.everSurrounded) continue
            if (!isSurrounded(candidate, chunks)) continue

            chunks = softResolve(candidate, chunks)
            emitted += GameEvent.ChunkSoftResolved(candidate)

            val completion = maybeCompleteAfterSoftResolve(candidate, chunks, meta)
            chunks = completion.chunks
            meta = completion.meta
            if (completion.cleared) {
                emitted += GameEvent.ChunkCleared(candidate)
                pending.addAll(neighboringChunkCoords(candidate).sortedWith(CHUNK_COORD_ORDER))
            }
        }

        val stateChanged = chunks !== state.chunks || meta != state.meta
        return LockTransition(
            state = if (!stateChanged) state else state.copy(chunks = chunks, meta = meta),
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

    private fun maybeCompleteAfterSoftResolve(
        coord: ChunkCoord,
        chunks: Map<ChunkCoord, Chunk>,
        meta: GameMeta,
    ): SoftResolveCompletion {
        val chunk = chunks.getValue(coord)
        val hiddenNonMineRemaining = chunk.cells.any { it.state == CellState.HIDDEN && !it.isMine }
        if (hiddenNonMineRemaining) {
            return SoftResolveCompletion(chunks, meta, cleared = false)
        }

        val updatedCells = chunk.cells.toMutableList()
        var newlyFlagged = 0
        for ((index, cell) in chunk.cells.withIndex()) {
            if (cell.state == CellState.HIDDEN) {
                updatedCells[index] = cell.copy(state = CellState.FLAGGED)
                newlyFlagged++
            }
        }
        val completed = chunks.toMutableMap().apply {
            put(coord, chunk.copy(cells = updatedCells))
        }
        return SoftResolveCompletion(
            chunks = completed,
            meta = meta.copy(
                flagsPlaced = meta.flagsPlaced + newlyFlagged,
                selectorsCleared = meta.selectorsCleared + 1,
            ),
            cleared = true,
        )
    }

    /**
     * A lock soft-resolves when every non-locked neighbor is [Chunk.isSolved] (same definition as
     * the blue "solved" tint). Peer locks in the same pocket are skipped so a cluster enclosed by
     * solved selectors can unlock together instead of deadlocking on each other.
     */
    private fun isSurrounded(
        coord: ChunkCoord,
        chunks: Map<ChunkCoord, Chunk>,
    ): Boolean {
        val neighbors = neighboringChunkCoords(coord)
        if (neighbors.size != 8) return false
        var clearedNeighbors = 0
        for (neighborCoord in neighbors) {
            val neighbor = chunks[neighborCoord] ?: return false
            if (neighbor.status == ChunkStatus.LOCKED) continue
            if (!neighbor.isSolved) return false
            clearedNeighbors++
        }
        // A pure lock-clump with no solved ring must not unlock itself.
        return clearedNeighbors > 0
    }

    private data class SoftResolveCompletion(
        val chunks: Map<ChunkCoord, Chunk>,
        val meta: GameMeta,
        val cleared: Boolean,
    )
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
