package com.infinite.minesweeper.ui.theme

/**
 * Board geometry and zoom thresholds used by renderers and the viewport.
 *
 * Values are density-independent pixels (dp). World↔screen math converts via zoom.
 */
object BoardDimens {
    /** Reference on-screen cell edge length at zoom 1.0. */
    const val BaseCellSizeDp: Float = 40f

    /**
     * When the on-screen cell edge falls below this size, switch to LOD bitmaps
     * (plan §8: ~12 dp/cell). Lowered so full-detail rendering persists further into a
     * zoom-out before the overview map takes over.
     */
    const val LodThresholdDp: Float = 6f

    /** Minimum and maximum pinch-zoom multipliers relative to [BaseCellSizeDp]. */
    const val MinZoom: Float = 0.05f
    const val MaxZoom: Float = 4f

    /**
     * On-screen size of the decorative world-origin marker, expressed in board cells so it scales
     * with zoom like every other board element. Clamped to [HomeMarkerMinSizeDp]/[HomeMarkerMaxSizeDp]
     * so it stays a visible landmark at extreme zoom-out without growing unbounded.
     */
    const val HomeMarkerSizeCells: Float = 3f
    const val HomeMarkerMinSizeDp: Float = 10f
    const val HomeMarkerMaxSizeDp: Float = 48f

    /** Hairline between cells at full detail, in dp. */
    const val CellGridStrokeDp: Float = 1f

    /** Outline around each 8×8 selector (chunk), in dp. */
    const val ChunkOutlineStrokeDp: Float = 1.5f

    /** Locked-chunk tint is drawn over the whole chunk; this is the stroke for preview frames. */
    const val PreviewCellGapDp: Float = 2f
}
