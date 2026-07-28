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
) {
    init {
        require(flagsPlaced >= 0) { "flagsPlaced cannot be negative" }
        require(selectorsCleared >= 0) { "selectorsCleared cannot be negative" }
        require(selectorsWiped >= 0) { "selectorsWiped cannot be negative" }
        require(viewportX.isFinite()) { "viewportX must be finite" }
        require(viewportY.isFinite()) { "viewportY must be finite" }
        require(zoom.isFinite() && zoom > 0f) { "zoom must be finite and greater than zero" }
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
