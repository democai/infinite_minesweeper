package com.infinite.minesweeper.core.model

/**
 * The persisted, board-independent portion of a game session.
 */
data class GameMeta(
    val flagsPlaced: Int = 0,
    val selectorsCleared: Int = 0,
    val selectorsWiped: Int = 0,
    val viewportX: Float = 0f,
    val viewportY: Float = 0f,
    val zoom: Float = 1f,
    /**
     * True once any cell anywhere has ever been revealed. Durable (not derived from the
     * viewport-bounded [GameState.chunks] window) so a cold jump to unexplored territory can't
     * be confused with a brand-new board just because prior progress has been evicted from the
     * live window. Gates the one-time adjacency-rule bootstrap exemption in `DefaultGameEngine`.
     */
    val hasEverRevealed: Boolean = false,
    /**
     * Axis-aligned bounding box of every selector ever present in the working set or restored
     * from storage. Durable so zoom-out can grow with explored extent even after viewport
     * eviction has trimmed [GameState.chunks].
     */
    val hasExploredBounds: Boolean = false,
    val exploredMinCx: Int = 0,
    val exploredMaxCx: Int = 0,
    val exploredMinCy: Int = 0,
    val exploredMaxCy: Int = 0,
) {
    init {
        require(flagsPlaced >= 0) { "flagsPlaced cannot be negative" }
        require(selectorsCleared >= 0) { "selectorsCleared cannot be negative" }
        require(selectorsWiped >= 0) { "selectorsWiped cannot be negative" }
        require(viewportX.isFinite()) { "viewportX must be finite" }
        require(viewportY.isFinite()) { "viewportY must be finite" }
        require(zoom.isFinite() && zoom > 0f) { "zoom must be finite and greater than zero" }
        if (hasExploredBounds) {
            require(exploredMinCx <= exploredMaxCx) {
                "exploredMinCx must not exceed exploredMaxCx"
            }
            require(exploredMinCy <= exploredMaxCy) {
                "exploredMinCy must not exceed exploredMaxCy"
            }
        }
    }

    /** Expands the explored AABB to include [coord], or seeds it when bounds were empty. */
    fun expandExplored(coord: ChunkCoord): GameMeta {
        if (!hasExploredBounds) {
            return copy(
                hasExploredBounds = true,
                exploredMinCx = coord.cx,
                exploredMaxCx = coord.cx,
                exploredMinCy = coord.cy,
                exploredMaxCy = coord.cy,
            )
        }
        return copy(
            exploredMinCx = minOf(exploredMinCx, coord.cx),
            exploredMaxCx = maxOf(exploredMaxCx, coord.cx),
            exploredMinCy = minOf(exploredMinCy, coord.cy),
            exploredMaxCy = maxOf(exploredMaxCy, coord.cy),
        )
    }
}

/**
 * Immutable snapshot consumed by the ViewModel and UI.
 *
 * [chunks] intentionally contains only the currently hydrated working set, never the whole
 * infinite board.
 */
data class GameState(
    val chunks: Map<ChunkCoord, Chunk> = emptyMap(),
    val meta: GameMeta = GameMeta(),
    val isProcessing: Boolean = false,
) {
    val selectorsLocked: Int
        get() = chunks.values.count { it.status == ChunkStatus.LOCKED }
}
