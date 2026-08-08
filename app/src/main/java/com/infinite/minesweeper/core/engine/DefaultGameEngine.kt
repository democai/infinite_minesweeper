package com.infinite.minesweeper.core.engine

import com.infinite.minesweeper.core.coords.chunkLocalToCell
import com.infinite.minesweeper.core.coords.localIndexToCoord
import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.coords.cellToLocalIndex
import com.infinite.minesweeper.core.engine.lock.LockAndWipeMechanic
import com.infinite.minesweeper.core.engine.lock.neighboringChunkCoords
import com.infinite.minesweeper.core.generation.recomputeAdjacency
import com.infinite.minesweeper.core.model.CHUNK_SIDE_LENGTH
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
 * [GameState.chunks] is a viewport-bounded working set in the integrated app (plan §9). Cascades
 * and chords can still reach outside that window (plan §4 radius 16), so [loadChunks] must return
 * any previously persisted layout before [MineGenerator.generateForFirstTouch] is allowed to roll
 * a fresh one — otherwise an evicted explored chunk is indistinguishable from terra incognita and
 * gets silently re-rolled.
 */
class DefaultGameEngine(
    private val mineGenerator: MineGenerator,
    initialState: GameState = GameState(),
    private val lockAndWipeMechanic: LockAndWipeMechanic? = null,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cascadeRadiusChunks: Int = DEFAULT_CASCADE_RADIUS_CHUNKS,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val loadChunks: suspend (Set<ChunkCoord>) -> Map<ChunkCoord, Chunk> = { emptyMap() },
) : GameEngine {

    private val dispatchMutex = Mutex()

    private val _state = MutableStateFlow(seedExploredBounds(initialState))
    override val state: StateFlow<GameState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    /**
     * When true, 0-cascade stops at the start cell's selector boundary. Runtime-updatable so the
     * settings preference can flip without recreating the engine session.
     */
    @Volatile
    var limitCascadeToSelector: Boolean = false

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
            var meta = current.meta
            for (coord in hydrated.keys) {
                meta = meta.expandExplored(coord)
            }
            for (coord in trimmed.keys) {
                meta = meta.expandExplored(coord)
            }
            var next = current.copy(chunks = trimmed + hydrated, meta = meta)
            // Heal old saves / hydrated frontiers whose revealed cells still lack outer-ring
            // neighbors (provisional adjacency counted missing chunks as zero mines).
            next = repairRevealedNeighborhoods(next)
            // Hydrating a cleared neighbor can make an already-locked selector surrounded; resolve
            // those immediately so the player doesn't need a fresh clear event to unblock them.
            val mechanic = lockAndWipeMechanic
            if (mechanic != null) {
                val transition = mechanic.recheckSurroundedLocks(next)
                next = transition.state
                // tryEmit: never suspend while holding dispatchMutex (avoids test-dispatcher stalls).
                transition.events.forEach { _events.tryEmit(it) }
            }
            _state.value = next
        }
    }

    /**
     * Ensures every chunk that already has explored cells also has its 8 neighbors generated and
     * adjacency patched, then returns the updated state. Used on restore and viewport hydrate so
     * wrong border numbers never reach the UI.
     */
    private suspend fun repairRevealedNeighborhoods(state: GameState): GameState {
        val chunks = state.chunks.toMutableMap()
        var meta = state.meta
        var changed = false
        for (chunk in state.chunks.values) {
            if (!chunk.hasExploredCells()) continue
            val missing = neighboringChunkCoords(chunk.coord)
                .filter { chunks[it]?.generated != true }
                .toSet()
            if (missing.isNotEmpty()) {
                for ((loadedCoord, loaded) in loadChunks(missing)) {
                    if (loaded.generated && chunks[loadedCoord]?.generated != true) {
                        chunks[loadedCoord] = loaded
                        meta = meta.expandExplored(loadedCoord)
                        changed = true
                    }
                }
            }
            val result = mineGenerator.ensureNeighborsGenerated(chunk.coord, chunks)
            if (result.chunks.isNotEmpty()) {
                chunks.putAll(result.chunks)
                for (coord in result.chunks.keys) {
                    meta = meta.expandExplored(coord)
                }
                changed = true
            }
        }
        return if (changed) state.copy(chunks = chunks, meta = meta) else state
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
                    limitCascadeToSelector = limitCascadeToSelector,
                    batchSize = batchSize,
                    clock = clock,
                    loadChunks = loadChunks,
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
                // Soft-resolve can complete a chunk and emit ChunkCleared; feed derived events back
                // through the mechanic so neighboring locks re-evaluate in the same dispatch.
                val eventQueue = ArrayDeque(pendingEvents)
                while (eventQueue.isNotEmpty()) {
                    val event = eventQueue.removeFirst()
                    val transition = lockAndWipeMechanic?.process(event, integratedState)
                    if (transition != null) {
                        integratedState = transition.state
                        eventQueue.addAll(transition.events)
                    }
                    _events.emit(event)
                }
                _state.value = integratedState.copy(isProcessing = false)
            }
        }
    }

    /**
     * Player-initiated reset of an already-solved selector: preserves its mine perimeter, rerolls
     * its 6x6 interior, and returns every cell to hidden. This mirrors [LockAndWipeMechanic]'s hard
     * wipe except it does not increment [GameMeta.selectorsWiped], which tracks mine-triggered
     * hard wipes rather than deliberate resets.
     * No-ops if the chunk is missing or not [Chunk.isSolved] (e.g. a stale UI request racing a
     * state change).
     */
    suspend fun resetSolvedChunk(coord: ChunkCoord) {
        dispatchMutex.withLock {
            withContext(backgroundDispatcher) {
                val snapshot = _state.value
                val chunk = snapshot.chunks[coord] ?: return@withContext
                if (!chunk.isSolved) return@withContext

                val oldFlagCount = chunk.cells.count { it.state == CellState.FLAGGED }
                val rerolled = mineGenerator.reroll(coord, snapshot.chunks).chunks
                val generatedChunk = requireNotNull(rerolled[coord]) {
                    "MineGenerator.reroll must return the reset chunk $coord"
                }
                val resetChunk = generatedChunk.copy(
                    cells = generatedChunk.cells.map { it.copy(state = CellState.HIDDEN) },
                    status = ChunkStatus.NORMAL,
                    everSurrounded = false,
                    lockedAt = null,
                )
                val chunks = snapshot.chunks.toMutableMap().apply {
                    putAll(rerolled)
                    put(coord, resetChunk)
                }
                var meta = snapshot.meta.copy(
                    flagsPlaced = (snapshot.meta.flagsPlaced - oldFlagCount).coerceAtLeast(0),
                )
                for (chunkCoord in chunks.keys) {
                    meta = meta.expandExplored(chunkCoord)
                }
                _state.value = snapshot.copy(chunks = chunks, meta = meta)
                _events.emit(GameEvent.ChunkWiped(coord))
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
    private val limitCascadeToSelector: Boolean,
    private val batchSize: Int,
    private val clock: () -> Long,
    private val loadChunks: suspend (Set<ChunkCoord>) -> Map<ChunkCoord, Chunk>,
    private val publish: suspend (Map<ChunkCoord, Chunk>, GameMeta) -> Unit,
    private val emit: suspend (GameEvent) -> Unit,
) {
    private var unpublishedReveals = 0

    private fun putChunk(coord: ChunkCoord, chunk: Chunk) {
        chunks[coord] = chunk
        meta = meta.expandExplored(coord)
    }

    private fun putChunks(newChunks: Map<ChunkCoord, Chunk>) {
        for ((coord, chunk) in newChunks) {
            putChunk(coord, chunk)
        }
    }

    suspend fun reveal(cell: CellCoord) {
        ensureGenerated(cell)
        ensureRevealReady(cellToChunk(cell))
        if (isLocked(cell)) return
        val current = getCell(cell) ?: return
        if (current.state != CellState.HIDDEN) return
        // Every board's first-ever reveal is exempt (nothing has been explored yet to be
        // adjacent to); every reveal after that must satisfy isPlayable.
        if (meta.hasEverRevealed && !isPlayable(cell)) return

        if (!meta.hasEverRevealed) meta = meta.copy(hasEverRevealed = true)
        if (current.isMine) {
            explode(cell)
        } else {
            revealCascade(cell)
        }
        publishNow()
    }

    suspend fun toggleFlag(cell: CellCoord) {
        ensureGenerated(cell)
        if (isLocked(cell)) return
        val coord = cellToChunk(cell)
        val chunk = chunks.getValue(coord)
        // Solved selectors are immutable: a solved chunk has no HIDDEN cells left, so this only
        // ever bites the FLAGGED -> HIDDEN (unflag) direction, but it must not be re-openable.
        if (chunk.isSolved) return
        // Flagging is never bootstrap-exempt: there's always something to be adjacent to by the
        // time flagging is meaningful (it requires a HIDDEN cell to already exist as a target).
        if (!isPlayable(cell)) return
        val localIndex = cellToLocalIndex(cell)
        val current = chunk.cells[localIndex]
        val (newState, flagsDelta) = when (current.state) {
            CellState.HIDDEN -> CellState.FLAGGED to 1
            CellState.FLAGGED -> CellState.HIDDEN to -1
            CellState.REVEALED, CellState.EXPLODED -> return
        }

        val updatedCells = chunk.cells.toMutableList()
        updatedCells[localIndex] = current.copy(state = newState)
        putChunk(coord, chunk.copy(cells = updatedCells))
        meta = meta.copy(flagsPlaced = meta.flagsPlaced + flagsDelta)
        maybeCompleteFromPerfectFlags(coord)
        publishNow()
    }

    suspend fun chord(cell: CellCoord) {
        ensureGenerated(cell)
        ensureRevealReady(cellToChunk(cell))
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

    /**
     * True when [cell] may be touched by a direct player action: it's Moore-adjacent to a
     * [CellState.REVEALED] cell, or it qualifies for the bounded "solved ring" exception (a
     * pocket of hidden cells fully enclosed by already-solved territory, which would otherwise
     * be permanently unreachable — e.g. an interior cell whose entire neighborhood is flagged
     * mines). Cascaded/chorded reveals are trusted consequences of one already-gated action and
     * must not call this per cell.
     */
    private fun isPlayable(cell: CellCoord): Boolean {
        if (neighbors8(cell).any { getCell(it)?.state == CellState.REVEALED }) return true
        return isInSolvedRing(cell)
    }

    private fun isInSolvedRing(cell: CellCoord): Boolean {
        val coord = cellToChunk(cell)
        if (isChunkSolvedRing(coord)) return true
        return floodFillEnclosed(cell, coord)
    }

    /**
     * Fast path: mirrors [com.infinite.minesweeper.core.engine.lock.LockAndWipeMechanic]'s
     * surrounded-selector check — a chunk whose 8 neighbors are all solved (locked peers
     * skipped, same as there) has every one of its hidden cells playable without the more
     * expensive per-cell fallback below.
     */
    private fun isChunkSolvedRing(coord: ChunkCoord): Boolean {
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
    private fun floodFillEnclosed(start: CellCoord, homeChunk: ChunkCoord): Boolean {
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

    private suspend fun revealCascade(start: CellCoord, radiusOrigin: CellCoord = start) {
        val originChunk = cellToChunk(radiusOrigin)
        val startChunk = cellToChunk(start)
        val visited = hashSetOf(start)
        val queue = ArrayDeque<CellCoord>()
        queue.add(start)

        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            ensureGenerated(cell)
            ensureRevealReady(cellToChunk(cell))
            var current = getCell(cell)
            // A cascade only ever enqueues neighbors of a zero-adjacency cell, which by
            // definition cannot be mines; the mine/null guards are defensive, not load-bearing.
            if (current == null || current.state != CellState.HIDDEN || current.isMine) continue
            protectNewlyPlayablePristineNeighbors(cell)
            current = getCell(cell)
            if (current == null || current.state != CellState.HIDDEN || current.isMine) continue

            revealCell(cell, current)
            unpublishedReveals++
            if (unpublishedReveals >= batchSize) publishNow()

            if (current.adjacentMines == 0) {
                for (neighbor in neighbors8(cell)) {
                    if (neighbor in visited) continue
                    val neighborChunk = cellToChunk(neighbor)
                    if (limitCascadeToSelector) {
                        if (neighborChunk != startChunk) continue
                    } else if (chebyshevChunkDistance(neighborChunk, originChunk) > cascadeRadiusChunks) {
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
        putChunk(coord, chunk.copy(cells = updatedCells))
        maybeAutoFlagAndClear(coord)
    }

    private suspend fun explode(cell: CellCoord) {
        val coord = cellToChunk(cell)
        val chunk = chunks.getValue(coord)
        val localIndex = cellToLocalIndex(cell)
        val updatedCells = chunk.cells.toMutableList()
        updatedCells[localIndex] = updatedCells[localIndex].copy(state = CellState.EXPLODED)
        putChunk(
            coord,
            chunk.copy(
                cells = updatedCells,
                status = ChunkStatus.LOCKED,
                lockedAt = clock(),
            ),
        )
        emit(GameEvent.ChunkLocked(chunk = coord, explodedCell = cell))
    }

    /**
     * Fires exactly once per chunk lifetime: revealing a cell can make every safe cell revealed
     * only once, since completed cells never return to hidden before a whole-selector wipe.
     */
    private suspend fun maybeAutoFlagAndClear(coord: ChunkCoord) {
        val chunk = chunks.getValue(coord)
        if (!chunk.allSafeCellsRevealed) return

        val updatedCells = chunk.cells.toMutableList()
        var newlyFlagged = 0
        for ((index, c) in chunk.cells.withIndex()) {
            if (c.state == CellState.HIDDEN) {
                updatedCells[index] = c.copy(state = CellState.FLAGGED)
                newlyFlagged++
            }
        }
        val completed = chunk.copy(cells = updatedCells)
        if (!completed.isSolved) return

        putChunk(coord, completed)
        meta = meta.copy(
            flagsPlaced = meta.flagsPlaced + newlyFlagged,
            selectorsCleared = meta.selectorsCleared + 1,
        )
        emit(GameEvent.ChunkCleared(coord))
    }

    /**
     * When every mine in the selector is flagged and no non-mine is flagged, the remaining hidden
     * safe cells are revealed and the selector is marked cleared. Uses ground-truth [Cell.isMine]
     * so a perfect flag set auto-solves the chunk without forcing the player to chord every number.
     */
    private suspend fun maybeCompleteFromPerfectFlags(coord: ChunkCoord) {
        val chunk = chunks[coord] ?: return
        if (!chunk.generated || chunk.status == ChunkStatus.LOCKED) return

        var mineCount = 0
        var flaggedMines = 0
        var flaggedNonMines = 0
        var hiddenSafe = 0
        for (cell in chunk.cells) {
            if (cell.isMine) {
                mineCount++
                if (cell.state == CellState.FLAGGED) flaggedMines++
            } else {
                when (cell.state) {
                    CellState.FLAGGED -> flaggedNonMines++
                    CellState.HIDDEN -> hiddenSafe++
                    CellState.REVEALED, CellState.EXPLODED -> Unit
                }
            }
        }
        if (mineCount == 0 || flaggedNonMines > 0 || flaggedMines != mineCount || hiddenSafe == 0) {
            return
        }

        for (index in chunk.cells.indices) {
            val cell = chunks.getValue(coord).cells[index]
            if (cell.state == CellState.HIDDEN && !cell.isMine) {
                val world = chunkLocalToCell(coord, localIndexToCoord(index))
                revealCascade(start = world, radiusOrigin = world)
            }
        }
    }

    private suspend fun ensureGenerated(cell: CellCoord) {
        val coord = cellToChunk(cell)
        if (chunks[coord]?.generated == true) return

        // Pull this chunk and its 8 neighbors from durable storage first. generateForFirstTouch
        // rolls every ungenerated neighbor in that 3×3; skipping the hydrate step would re-roll
        // explored-but-evicted neighbors and corrupt adjacency on the frontier.
        hydrateNeighborhood(coord)
        if (chunks[coord]?.generated == true) return

        val result = mineGenerator.generateForFirstTouch(cell, chunks)
        putChunks(result.chunks)
    }

    /**
     * Before revealing (or chording / zero-flooding) in [coord], ensure its full 8-neighbor ring
     * is generated so border [Cell.adjacentMines] values are final — not provisional zeros from
     * missing outer chunks.
     */
    private suspend fun ensureRevealReady(coord: ChunkCoord) {
        if (chunks[coord]?.generated != true) return
        val neighbors = neighboringChunkCoords(coord)
        if (neighbors.all { chunks[it]?.generated == true }) return

        hydrateMissing(neighbors.filter { chunks[it]?.generated != true }.toSet())
        if (neighbors.all { chunks[it]?.generated == true }) return

        val result = mineGenerator.ensureNeighborsGenerated(coord, chunks)
        putChunks(result.chunks)
    }

    private suspend fun hydrateNeighborhood(coord: ChunkCoord) {
        val toLoad = buildSet {
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val cx = coord.cx.toLong() + dx
                    val cy = coord.cy.toLong() + dy
                    if (cx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                        cy in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                    ) {
                        val neighbor = ChunkCoord(cx.toInt(), cy.toInt())
                        if (chunks[neighbor]?.generated != true) add(neighbor)
                    }
                }
            }
        }
        hydrateMissing(toLoad)
    }

    private suspend fun hydrateMissing(toLoad: Set<ChunkCoord>) {
        if (toLoad.isEmpty()) return
        for ((loadedCoord, loaded) in loadChunks(toLoad)) {
            if (loaded.generated && chunks[loadedCoord]?.generated != true) {
                putChunk(loadedCoord, loaded)
            }
        }
    }

    /**
     * When [revealedCell] becomes revealed, some cells in an untouched neighboring selector may
     * become directly playable for the first time. Keep those cells mine-free by relocating any
     * such mines elsewhere inside the untouched selector before the new clue is committed.
     */
    private fun protectNewlyPlayablePristineNeighbors(revealedCell: CellCoord) {
        val protectedByChunk = buildMap<ChunkCoord, MutableSet<Int>> {
            for (neighbor in neighbors8(revealedCell)) {
                val coord = cellToChunk(neighbor)
                val chunk = chunks[coord] ?: continue
                if (!chunk.isPristine()) continue
                if (neighbors8(neighbor).any { it != revealedCell && getCell(it)?.state == CellState.REVEALED }) {
                    continue
                }
                getOrPut(coord) { linkedSetOf() } += cellToLocalIndex(neighbor)
            }
        }
        if (protectedByChunk.isEmpty()) return

        for ((coord, protectedIndices) in protectedByChunk) {
            protectPristineChunk(coord, protectedIndices, revealedCell)
        }
    }

    private fun protectPristineChunk(
        coord: ChunkCoord,
        protectedIndices: Set<Int>,
        upcomingReveal: CellCoord,
    ) {
        val chunk = chunks[coord] ?: return
        if (!chunk.isPristine()) return

        val mineIndicesToMove = protectedIndices.filter { index -> chunk.cells[index].isMine }
        if (mineIndicesToMove.isEmpty()) return

        val destinationIndices = chunk.cells.indices.filter { index ->
            index !in protectedIndices &&
                !chunk.cells[index].isMine &&
                !isAdjacentToAnyReveal(chunkLocalToCell(coord, localIndexToCoord(index)), upcomingReveal)
        }
        check(destinationIndices.size >= mineIndicesToMove.size) {
            "Not enough safe relocation cells in pristine chunk $coord"
        }

        val updatedCells = chunk.cells.toMutableList()
        for ((sourceIndex, destinationIndex) in mineIndicesToMove.sorted().zip(destinationIndices.sorted())) {
            updatedCells[sourceIndex] = updatedCells[sourceIndex].copy(isMine = false)
            updatedCells[destinationIndex] = updatedCells[destinationIndex].copy(isMine = true)
        }

        val merged = chunks.toMutableMap().apply {
            put(coord, chunk.copy(cells = updatedCells))
        }
        val targets = (neighboringChunkCoords(coord) + coord)
            .filterTo(linkedSetOf()) { merged[it]?.generated == true }
        val patched = recomputeAdjacency(merged, targets)
        for (target in targets) {
            putChunk(target, requireNotNull(patched[target]))
        }
    }

    private fun isAdjacentToAnyReveal(
        cell: CellCoord,
        upcomingReveal: CellCoord,
    ): Boolean = neighbors8(cell).any { neighbor ->
        neighbor == upcomingReveal || getCell(neighbor)?.state == CellState.REVEALED
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

private fun Chunk.hasExploredCells(): Boolean =
    generated && cells.any { it.state == CellState.REVEALED || it.state == CellState.EXPLODED }

private fun Chunk.isPristine(): Boolean =
    generated && cells.all { it.state == CellState.HIDDEN }

/** Seeds [GameMeta] explored AABB from any chunks already present in [state]. */
private fun seedExploredBounds(state: GameState): GameState {
    if (state.chunks.isEmpty()) return state
    var meta = state.meta
    for (coord in state.chunks.keys) {
        meta = meta.expandExplored(coord)
    }
    return if (meta == state.meta) state else state.copy(meta = meta)
}
