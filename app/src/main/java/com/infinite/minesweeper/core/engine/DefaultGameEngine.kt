package com.infinite.minesweeper.core.engine

import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.coords.cellToLocalIndex
import com.infinite.minesweeper.core.engine.lock.LockAndWipeMechanic
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameAction
import com.infinite.minesweeper.core.model.GameEngine
import com.infinite.minesweeper.core.model.GameEvent
import com.infinite.minesweeper.core.model.GameMeta
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.core.model.MineGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val DEFAULT_CASCADE_RADIUS_CHUNKS = 16
internal const val DEFAULT_BATCH_SIZE = 64
private const val EVENT_BUFFER_CAPACITY = 64

/**
 * [GameEngine] implementation covering reveal, bounded flood-fill, flag toggling, chording, and
 * auto-flag-on-completion. The optional [lockAndWipeMechanic] lets the app integration layer fold
 * T9's derived transitions back into the same authoritative state before a dispatch completes.
 * Tests and other core-only callers can omit it and observe the original T8 transitions directly.
 *
 * [GameState.chunks] is this engine's entire notion of the board: every chunk it has ever touched,
 * not merely a viewport window. Bounding that window to conserve memory (T7's cache) and
 * rehydrating evicted-but-generated chunks before they are touched again (T10) are integration
 * concerns outside T8's scope; passing a state with an incomplete window for an already-generated
 * region would cause this engine to re-generate it.
 */
class DefaultGameEngine(
    private val mineGenerator: MineGenerator,
    initialState: GameState = GameState(),
    private val lockAndWipeMechanic: LockAndWipeMechanic? = null,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cascadeRadiusChunks: Int = DEFAULT_CASCADE_RADIUS_CHUNKS,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val clock: () -> Long = System::currentTimeMillis,
) : GameEngine {

    private val dispatchMutex = Mutex()

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<GameState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    /**
     * Bounds the live [state] chunk map to [keep], folding in [hydrated] for any newly retained
     * coordinate this engine has not seen yet. This is how the integration layer (T13) keeps a
     * long panning session's memory bounded: [GameState.chunks] otherwise only ever grows (see
     * the class doc). Shares [dispatchMutex] with [dispatch] so a trim can never interleave with
     * an in-flight action and split a cascade's chunk map across two inconsistent snapshots.
     *
     * Callers must durably save any chunk being dropped (its coordinate absent from [keep])
     * before calling this, since a chunk this engine has never touched is indistinguishable from
     * one it touched and forgot: dropping a generated chunk without saving it first would make a
     * later [GameAction] targeting it look first-touch and re-roll a fresh layout over the lost
     * one.
     */
    suspend fun syncWindow(keep: Set<ChunkCoord>, hydrated: Map<ChunkCoord, Chunk> = emptyMap()) {
        dispatchMutex.withLock {
            val current = _state.value
            val trimmed = current.chunks.filterKeys { it in keep }
            _state.value = current.copy(chunks = trimmed + hydrated)
        }
    }

    override suspend fun dispatch(action: GameAction) {
        dispatchMutex.withLock {
            withContext(backgroundDispatcher) {
                val snapshot = _state.value
                val pendingEvents = mutableListOf<GameEvent>()
                val session = EngineSession(
                    chunks = snapshot.chunks.toMutableMap(),
                    meta = snapshot.meta,
                    mineGenerator = mineGenerator,
                    cascadeRadiusChunks = cascadeRadiusChunks,
                    batchSize = batchSize,
                    clock = clock,
                    publish = { chunks, meta ->
                        _state.value = GameState(chunks = chunks, meta = meta, isProcessing = true)
                    },
                    emit = { event -> pendingEvents += event },
                )

                when (action) {
                    is GameAction.Reveal -> session.reveal(action.cell)
                    is GameAction.ToggleFlag -> session.toggleFlag(action.cell)
                    is GameAction.Chord -> session.chord(action.cell)
                }

                var integratedState = GameState(
                    chunks = session.chunks.toMap(),
                    meta = session.meta,
                    isProcessing = false,
                )
                for (event in pendingEvents) {
                    val transition = lockAndWipeMechanic?.process(event, integratedState)
                    if (transition != null) integratedState = transition.state
                    _events.emit(event)
                    transition?.events?.forEach { _events.emit(it) }
                }
                _state.value = integratedState.copy(isProcessing = false)
            }
        }
    }
}

/**
 * Mutable scratch space for a single [DefaultGameEngine.dispatch] call. Not thread-safe by itself;
 * [DefaultGameEngine] serializes access with [Mutex].
 */
private class EngineSession(
    val chunks: MutableMap<ChunkCoord, Chunk>,
    var meta: GameMeta,
    private val mineGenerator: MineGenerator,
    private val cascadeRadiusChunks: Int,
    private val batchSize: Int,
    private val clock: () -> Long,
    private val publish: suspend (Map<ChunkCoord, Chunk>, GameMeta) -> Unit,
    private val emit: suspend (GameEvent) -> Unit,
) {
    private var unpublishedReveals = 0

    suspend fun reveal(cell: CellCoord) {
        ensureGenerated(cell)
        if (isLocked(cell)) return
        val current = getCell(cell) ?: return
        if (current.state != CellState.HIDDEN) return

        if (current.isMine) {
            explode(cell)
        } else {
            revealCascade(cell)
        }
        publishNow()
    }

    suspend fun toggleFlag(cell: CellCoord) {
        if (isLocked(cell)) return
        val coord = cellToChunk(cell)
        val chunk = chunks.getOrPut(coord) { Chunk(coord = coord) }
        val localIndex = cellToLocalIndex(cell)
        val current = chunk.cells[localIndex]
        val (newState, flagsDelta) = when (current.state) {
            CellState.HIDDEN -> CellState.FLAGGED to 1
            CellState.FLAGGED -> CellState.HIDDEN to -1
            CellState.REVEALED, CellState.EXPLODED -> return
        }

        val updatedCells = chunk.cells.toMutableList()
        updatedCells[localIndex] = current.copy(state = newState)
        chunks[coord] = chunk.copy(cells = updatedCells)
        meta = meta.copy(flagsPlaced = meta.flagsPlaced + flagsDelta)
        publishNow()
    }

    suspend fun chord(cell: CellCoord) {
        ensureGenerated(cell)
        if (isLocked(cell)) return
        val current = getCell(cell) ?: return
        if (current.state != CellState.REVEALED) return

        val neighbors = neighbors8(cell)
        for (neighbor in neighbors) ensureGenerated(neighbor)

        val flaggedCount = neighbors.count { getCell(it)?.state == CellState.FLAGGED }
        if (flaggedCount != current.adjacentMines) return

        // Mines detonate in a first pass, before any safe neighbor is revealed. Revealing a safe
        // neighbor first could cascade through the rest of the chunk and auto-flag-complete it —
        // including auto-flagging the very mine this chord was about to hit — which would let a
        // wrong flag silently defuse the mine instead of triggering the lock.
        for (neighbor in neighbors) {
            if (isLocked(neighbor)) continue
            val neighborCell = getCell(neighbor) ?: continue
            if (neighborCell.state == CellState.HIDDEN && neighborCell.isMine) {
                explode(neighbor)
            }
        }
        for (neighbor in neighbors) {
            // A neighbor chunk locked by the pass above (or already locked beforehand) stops
            // accepting further reveals from this action.
            if (isLocked(neighbor)) continue
            val neighborCell = getCell(neighbor) ?: continue
            if (neighborCell.state == CellState.HIDDEN) {
                revealCascade(start = neighbor, radiusOrigin = cell)
            }
        }
        publishNow()
    }

    private suspend fun revealCascade(start: CellCoord, radiusOrigin: CellCoord = start) {
        val originChunk = cellToChunk(radiusOrigin)
        val visited = hashSetOf(start)
        val queue = ArrayDeque<CellCoord>()
        queue.add(start)

        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            ensureGenerated(cell)
            val current = getCell(cell)
            // A cascade only ever enqueues neighbors of a zero-adjacency cell, which by
            // definition cannot be mines; the mine/null guards are defensive, not load-bearing.
            if (current == null || current.state != CellState.HIDDEN || current.isMine) continue

            revealCell(cell, current)
            unpublishedReveals++
            if (unpublishedReveals >= batchSize) publishNow()

            if (current.adjacentMines == 0) {
                for (neighbor in neighbors8(cell)) {
                    if (neighbor in visited) continue
                    if (chebyshevChunkDistance(cellToChunk(neighbor), originChunk) > cascadeRadiusChunks) {
                        continue
                    }
                    visited += neighbor
                    queue.add(neighbor)
                }
            }
        }
    }

    private suspend fun revealCell(cell: CellCoord, before: Cell) {
        val coord = cellToChunk(cell)
        val chunk = chunks.getValue(coord)
        val localIndex = cellToLocalIndex(cell)
        val updatedCells = chunk.cells.toMutableList()
        updatedCells[localIndex] = before.copy(state = CellState.REVEALED)
        chunks[coord] = chunk.copy(cells = updatedCells)
        maybeAutoFlagAndClear(coord)
    }

    private suspend fun explode(cell: CellCoord) {
        val coord = cellToChunk(cell)
        val chunk = chunks.getValue(coord)
        val localIndex = cellToLocalIndex(cell)
        val updatedCells = chunk.cells.toMutableList()
        updatedCells[localIndex] = updatedCells[localIndex].copy(state = CellState.EXPLODED)
        chunks[coord] = chunk.copy(
            cells = updatedCells,
            status = ChunkStatus.LOCKED,
            lockedAt = clock(),
        )
        emit(GameEvent.ChunkLocked(chunk = coord, explodedCell = cell))
    }

    /**
     * Fires exactly once per chunk lifetime: revealing a cell can only drop the chunk's hidden
     * non-mine count from one to zero a single time, since every cell thereafter is
     * revealed/flagged and never returns to hidden (short of a T9 wipe, which resets the whole
     * chunk and is out of this engine's scope).
     */
    private suspend fun maybeAutoFlagAndClear(coord: ChunkCoord) {
        val chunk = chunks.getValue(coord)
        val hiddenNonMineRemaining = chunk.cells.any { it.state == CellState.HIDDEN && !it.isMine }
        if (hiddenNonMineRemaining) return

        val updatedCells = chunk.cells.toMutableList()
        var newlyFlagged = 0
        for ((index, c) in chunk.cells.withIndex()) {
            if (c.state == CellState.HIDDEN) {
                updatedCells[index] = c.copy(state = CellState.FLAGGED)
                newlyFlagged++
            }
        }
        chunks[coord] = chunk.copy(cells = updatedCells)
        meta = meta.copy(
            flagsPlaced = meta.flagsPlaced + newlyFlagged,
            selectorsCleared = meta.selectorsCleared + 1,
        )
        emit(GameEvent.ChunkCleared(coord))
    }

    private suspend fun ensureGenerated(cell: CellCoord) {
        val coord = cellToChunk(cell)
        if (chunks[coord]?.generated == true) return
        val result = mineGenerator.generateForFirstTouch(cell, chunks)
        chunks.putAll(result.chunks)
    }

    private fun getCell(cell: CellCoord): Cell? {
        val chunk = chunks[cellToChunk(cell)] ?: return null
        if (!chunk.generated) return null
        return chunk.cells[cellToLocalIndex(cell)]
    }

    private fun isLocked(cell: CellCoord): Boolean =
        chunks[cellToChunk(cell)]?.status == ChunkStatus.LOCKED

    private suspend fun publishNow() {
        publish(chunks, meta)
        unpublishedReveals = 0
    }
}
