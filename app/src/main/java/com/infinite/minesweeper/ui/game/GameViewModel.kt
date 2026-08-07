package com.infinite.minesweeper.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.coords.cellToLocalIndex
import com.infinite.minesweeper.core.engine.DefaultGameEngine
import com.infinite.minesweeper.core.engine.lock.LockAndWipeMechanic
import com.infinite.minesweeper.core.engine.lock.neighboringChunkCoords
import com.infinite.minesweeper.core.generation.SeededMineGenerator
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkRepository
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameEvent
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.data.persistence.GamePersistenceCoordinator
import com.infinite.minesweeper.data.persistence.ViewportSnapshot
import com.infinite.minesweeper.data.persistence.restoreGameState
import com.infinite.minesweeper.ui.settings.InputActionMapper
import com.infinite.minesweeper.ui.settings.InputBinding
import com.infinite.minesweeper.ui.settings.InputBindingPreferences
import com.infinite.minesweeper.ui.settings.TapKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val WORLD_SEED = 0x49_4E_46_4D_49_4E_45L

@HiltViewModel
class GameViewModel @Inject constructor(
    private val repository: ChunkRepository,
    val inputBindingPreferences: InputBindingPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(GameState(isProcessing = true))
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val viewport = MutableStateFlow(ViewportSnapshot(0f, 0f, 1f))
    private var engine: DefaultGameEngine? = null
    private var persistence: GamePersistenceCoordinator? = null
    private var binding: InputBinding = InputBinding.Default
    private val flushMutex = Mutex()

    /** Tracks the current session's engine-state/events collectors and persistence coordinator,
     * so [resetGame] can stop exactly those (and only those) before wiping durable storage. */
    private var sessionJob: Job? = null

    init {
        viewModelScope.launch {
            binding = inputBindingPreferences.binding.first()
            launch {
                inputBindingPreferences.binding.collect { binding = it }
            }
            launch {
                inputBindingPreferences.limitCascadeToSelector.collect { enabled ->
                    engine?.limitCascadeToSelector = enabled
                }
            }
            startSession(fresh = false)
        }
    }

    private suspend fun startSession(fresh: Boolean) {
        val generator = SeededMineGenerator(WORLD_SEED)
        val restored = if (fresh) GameState() else restoreGameState(repository, generator)
        viewport.value = ViewportSnapshot(
            centerX = restored.meta.viewportX,
            centerY = restored.meta.viewportY,
            zoom = restored.meta.zoom,
        )
        val createdEngine = DefaultGameEngine(
            mineGenerator = generator,
            initialState = restored,
            lockAndWipeMechanic = LockAndWipeMechanic(generator),
            loadChunks = { coords -> repository.getChunks(coords) },
        )
        createdEngine.limitCascadeToSelector =
            inputBindingPreferences.limitCascadeToSelector.first()
        engine = createdEngine
        _state.value = createdEngine.state.value

        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        sessionJob = job
        val sessionScope = CoroutineScope(viewModelScope.coroutineContext + job)
        sessionScope.launch {
            createdEngine.state.collect { _state.value = it }
        }
        sessionScope.launch {
            createdEngine.events.collect { _events.emit(it) }
        }
        persistence = GamePersistenceCoordinator(_state, viewport, repository).also {
            sessionScope.launch { it.run() }
        }
    }

    /**
     * Wipes all board/world progress and starts a brand-new game. Stops the current session's
     * collectors first (and waits for them to fully stop) so a write already in flight from the
     * old [GamePersistenceCoordinator] can't land after [ChunkRepository.clearAll] and silently
     * undo the reset.
     */
    fun resetGame() {
        viewModelScope.launch {
            flushMutex.withLock {
                sessionJob?.cancelAndJoin()
                engine = null
                persistence = null
                withContext(NonCancellable) { repository.clearAll() }
                startSession(fresh = true)
            }
        }
    }

    fun dispatch(gesture: TapKind, cell: CellCoord) {
        val current = _state.value
        val coord = cellToChunk(cell)
        val chunk = current.chunks[coord]
        if (current.isProcessing || chunk?.status == ChunkStatus.LOCKED) return
        val cellState = chunk
            ?.takeIf { it.generated }
            ?.cells
            ?.get(cellToLocalIndex(cell))
            ?.state
            ?: CellState.HIDDEN
        val action = InputActionMapper.map(gesture, cell, cellState, binding) ?: return
        val activeEngine = engine ?: return
        viewModelScope.launch {
            // Cascade/chord hydrate via the engine's loadChunks hook. This tap-path rehydrate is
            // still useful so InputActionMapper and the LOCKED guard see the latest cell before
            // dispatch when a fast pan-then-tap races the viewport sync.
            if (chunk == null && activeEngine.state.value.chunks[coord] == null) {
                repository.getChunk(coord)?.let { persisted ->
                    activeEngine.syncWindow(
                        keep = activeEngine.state.value.chunks.keys + coord,
                        hydrated = mapOf(coord to persisted),
                    )
                }
            }
            activeEngine.dispatch(action)
        }
    }

    /** Resets an already-solved selector back to hidden with a fresh mine layout. */
    fun resetSelector(coord: ChunkCoord) {
        val activeEngine = engine ?: return
        viewModelScope.launch { activeEngine.resetSolvedChunk(coord) }
    }

    fun updateViewport(centerX: Double, centerY: Double, zoom: Double) {
        viewport.value = ViewportSnapshot(
            centerX = centerX.toFloat(),
            centerY = centerY.toFloat(),
            zoom = zoom.toFloat(),
        )
    }

    /**
     * Bounds the engine's live chunk map to [keep] as the viewport pans, so a long session
     * exploring outward does not keep every chunk it has ever touched resident in memory (plan
     * §9, dev tree T7/T13). Locked selectors and their 8-neighbor rings stay hydrated so the
     * surround watcher can soft-resolve without the solved ring having been evicted out from
     * under it. Persisted locks outside the live map are pulled in for the same reason.
     */
    fun syncVisibleWindow(keep: Set<ChunkCoord>) {
        val activeEngine = engine ?: return
        viewModelScope.launch {
            val currentChunks = activeEngine.state.value.chunks
            val persistedLocks = repository.getLockedChunks()
            val locked = currentChunks.filterValues { it.status == ChunkStatus.LOCKED }.keys +
                persistedLocks.keys
            val lockHalo = buildSet {
                for (coord in locked) {
                    add(coord)
                    addAll(neighboringChunkCoords(coord))
                }
            }
            val effectiveKeep = keep + lockHalo
            val evicted = currentChunks.keys - effectiveKeep
            if (evicted.isNotEmpty()) {
                repository.saveChunks(evicted.mapNotNull { currentChunks[it] })
            }
            val missing = effectiveKeep - currentChunks.keys
            val hydrated = buildMap {
                putAll(persistedLocks.filterKeys { it in missing })
                val stillMissing = missing - keys
                if (stillMissing.isNotEmpty()) putAll(repository.getChunks(stillMissing))
            }
            activeEngine.syncWindow(effectiveKeep, hydrated)
        }
    }

    /**
     * Fire-and-forget flush for non-critical callers. Prefer [flushNow] from lifecycle `onStop`
     * so the write-behind queue is drained before the process can be killed.
     */
    fun flush() {
        viewModelScope.launch { flushNow() }
    }

    /**
     * Durably persists the live working set and viewport meta, then drains the repository queue.
     * Safe to call from a blocking `onStop` observer: work runs under [NonCancellable] so a
     * cancelling scope cannot drop the final write.
     */
    suspend fun flushNow() {
        flushMutex.withLock {
            withContext(NonCancellable) {
                val snapshot = _state.value
                val viewportSnapshot = viewport.value
                repository.saveChunks(snapshot.chunks.values)
                repository.saveGameMeta(
                    snapshot.meta.copy(
                        viewportX = viewportSnapshot.centerX,
                        viewportY = viewportSnapshot.centerY,
                        zoom = viewportSnapshot.zoom,
                    ),
                )
                repository.flush()
            }
        }
    }
}
