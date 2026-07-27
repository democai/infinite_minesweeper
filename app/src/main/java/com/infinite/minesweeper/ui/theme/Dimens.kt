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
     * (plan §8: ~12 dp/cell).
     */
    const val LodThresholdDp: Float = 12f

    /** Minimum and maximum pinch-zoom multipliers relative to [BaseCellSizeDp]. */
    const val MinZoom: Float = 0.15f
    const val MaxZoom: Float = 4f

    /** Hairline between cells at full detail, in dp. */
    const val CellGridStrokeDp: Float = 1f

    /** Locked-chunk tint is drawn over the whole chunk; this is the stroke for preview frames. */
    const val PreviewCellGapDp: Float = 2f
}
