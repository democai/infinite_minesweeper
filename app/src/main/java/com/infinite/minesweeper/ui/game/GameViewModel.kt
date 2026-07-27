package com.infinite.minesweeper.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.coords.cellToLocalIndex
import com.infinite.minesweeper.core.engine.DefaultGameEngine
import com.infinite.minesweeper.core.engine.lock.LockAndWipeMechanic
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    init {
        viewModelScope.launch {
            binding = inputBindingPreferences.binding.first()
            launch {
                inputBindingPreferences.binding.collect { binding = it }
            }

            val restored = restoreGameState(repository)
            viewport.value = ViewportSnapshot(
                centerX = restored.meta.viewportX,
                centerY = restored.meta.viewportY,
                zoom = restored.meta.zoom,
            )
            val generator = SeededMineGenerator(WORLD_SEED)
            val createdEngine = DefaultGameEngine(
                mineGenerator = generator,
                initialState = restored,
                lockAndWipeMechanic = LockAndWipeMechanic(generator),
            )
            engine = createdEngine
            _state.value = restored

            launch {
                createdEngine.state.collect { _state.value = it }
            }
            launch {
                createdEngine.events.collect { _events.emit(it) }
            }

            persistence = GamePersistenceCoordinator(_state, viewport, repository).also {
                launch { it.run() }
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
            // The tapped chunk may have been evicted by syncVisibleWindow (e.g. a fast pan
            // followed immediately by a tap, before the viewport-driven rehydration lands).
            // Rehydrate it from storage first so the engine never mistakes an evicted,
            // already-generated chunk for a brand-new one and re-rolls its layout.
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
     * §9, dev tree T7/T13). Evicted chunks are saved through the repository's write-behind queue
     * before being dropped so [dispatch]'s rehydrate-on-tap fallback and a later re-visit both see
     * their latest state, independent of [GamePersistenceCoordinator]'s own diff timing.
     */
    fun syncVisibleWindow(keep: Set<ChunkCoord>) {
        val activeEngine = engine ?: return
        viewModelScope.launch {
            val currentChunks = activeEngine.state.value.chunks
            val evicted = currentChunks.keys - keep
            if (evicted.isNotEmpty()) {
                repository.saveChunks(evicted.mapNotNull { currentChunks[it] })
            }
            val missing = keep - currentChunks.keys
            val hydrated = if (missing.isEmpty()) emptyMap() else repository.getChunks(missing)
            activeEngine.syncWindow(keep, hydrated)
        }
    }

    fun flush() {
        viewModelScope.launch {
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
