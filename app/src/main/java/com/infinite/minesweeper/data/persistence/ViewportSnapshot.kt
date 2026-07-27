package com.infinite.minesweeper.data.persistence

/**
 * Viewport values persisted alongside [com.infinite.minesweeper.core.model.GameMeta].
 *
 * Kept independent of the Compose-observable `ui.board.ViewportState` so this package carries no
 * UI-framework dependency; callers convert their live viewport into a snapshot whenever it
 * changes and publish it through a `StateFlow`.
 */
data class ViewportSnapshot(
    val centerX: Float,
    val centerY: Float,
    val zoom: Float,
) {
    init {
        require(centerX.isFinite()) { "centerX must be finite" }
        require(centerY.isFinite()) { "centerY must be finite" }
        require(zoom.isFinite() && zoom > 0f) { "zoom must be finite and greater than zero" }
    }
}
