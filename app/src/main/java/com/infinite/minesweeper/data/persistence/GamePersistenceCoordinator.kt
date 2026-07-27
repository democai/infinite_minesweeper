package com.infinite.minesweeper.data.persistence

import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkRepository
import com.infinite.minesweeper.core.model.GameMeta
import com.infinite.minesweeper.core.model.GameState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * Binds a live [GameState] stream to a [ChunkRepository].
 *
 * Sourced from a plain `StateFlow<GameState>` rather than
 * [com.infinite.minesweeper.core.model.GameEngine] directly: T9's lock/wipe mechanic mutates
 * state outside the engine's own flow (see `core.engine.lock.LockAndWipeMechanic`), so whichever
 * flow is authoritative for a session is the integration layer's (T13) call, not this
 * coordinator's.
 *
 * Every combined emission is diffed against the last snapshot this coordinator durably wrote, so
 * [run] only asks the repository to save chunks that actually changed, and only re-saves
 * [GameMeta] when its merge with the latest [ViewportSnapshot] differs from what was last
 * written. The repository (T3) owns debouncing that write; this coordinator only decides what is
 * dirty.
 */
class GamePersistenceCoordinator(
    private val gameState: StateFlow<GameState>,
    private val viewport: StateFlow<ViewportSnapshot>,
    private val repository: ChunkRepository,
) {
    private var lastPersistedChunks: Map<ChunkCoord, Chunk> = emptyMap()
    private var lastPersistedMeta: GameMeta? = null

    /**
     * Suspends for the coordinator's lifetime, persisting dirty chunks and meta as they occur.
     * Callers launch this in a session-scoped coroutine (e.g. `viewModelScope`) and cancel it on
     * teardown; it never completes on its own since both source flows are unbounded.
     */
    suspend fun run() {
        combine(gameState, viewport) { state, viewportSnapshot -> state to viewportSnapshot }
            .collect { (state, viewportSnapshot) ->
                persistDirtyChunks(state.chunks)
                persistMetaIfChanged(state.meta, viewportSnapshot)
            }
    }

    /**
     * Flushes every queued write through to durable storage. Callers invoke this from lifecycle
     * `onStop`/process-death hooks after their last dispatched action.
     */
    suspend fun flush() {
        repository.flush()
    }

    private suspend fun persistDirtyChunks(chunks: Map<ChunkCoord, Chunk>) {
        val dirty = if (lastPersistedChunks.isEmpty()) {
            chunks
        } else {
            chunks.filter { (coord, chunk) -> lastPersistedChunks[coord] != chunk }
        }
        if (dirty.isEmpty()) return
        repository.saveChunks(dirty.values)
        lastPersistedChunks = chunks
    }

    private suspend fun persistMetaIfChanged(meta: GameMeta, viewportSnapshot: ViewportSnapshot) {
        val merged = meta.copy(
            viewportX = viewportSnapshot.centerX,
            viewportY = viewportSnapshot.centerY,
            zoom = viewportSnapshot.zoom,
        )
        if (merged == lastPersistedMeta) return
        repository.saveGameMeta(merged)
        lastPersistedMeta = merged
    }
}
