package com.infinite.minesweeper.ui.board

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.infinite.minesweeper.core.model.CHUNK_SIDE_LENGTH
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.ui.theme.BoardDimens
import kotlin.math.ceil
import kotlin.math.floor

private const val DEFAULT_RENDER_MARGIN_CHUNKS = 1

/**
 * Mutable viewport whose world coordinates are expressed in cells.
 *
 * The center/zoom fields use Compose snapshot state, but all calculations accept primitive values
 * and are directly JVM-testable. Pan distances and viewport dimensions are physical pixels.
 */
@Stable
class ViewportState(
    initialCenterX: Double = 0.0,
    initialCenterY: Double = 0.0,
    initialZoom: Double = 1.0,
    minZoom: Double = BoardDimens.MinZoom.toDouble(),
    val maxZoom: Double = BoardDimens.MaxZoom.toDouble(),
) {
    var centerX by mutableDoubleStateOf(initialCenterX)
        private set

    var centerY by mutableDoubleStateOf(initialCenterY)
        private set

    private var minZoomState by mutableDoubleStateOf(minZoom)
    val minZoom: Double get() = minZoomState

    var zoom by mutableDoubleStateOf(initialZoom.coerceIn(minZoom, maxZoom))
        private set

    var viewportWidthPx by mutableDoubleStateOf(0.0)
        private set

    var viewportHeightPx by mutableDoubleStateOf(0.0)
        private set

    init {
        require(initialCenterX.isFinite()) { "initialCenterX must be finite" }
        require(initialCenterY.isFinite()) { "initialCenterY must be finite" }
        require(initialZoom.isFinite() && initialZoom > 0.0) {
            "initialZoom must be finite and greater than zero"
        }
        require(minZoom.isFinite() && minZoom > 0.0) {
            "minZoom must be finite and greater than zero"
        }
        require(maxZoom.isFinite() && maxZoom >= minZoom) {
            "maxZoom must be finite and at least minZoom"
        }
    }

    fun updateViewportSize(widthPx: Double, heightPx: Double) {
        require(widthPx.isFinite() && widthPx >= 0.0) {
            "widthPx must be finite and non-negative"
        }
        require(heightPx.isFinite() && heightPx >= 0.0) {
            "heightPx must be finite and non-negative"
        }
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
    }

    /**
     * Updates the zoom-out floor and re-clamps the current zoom into the new range.
     * Used when explored selector extent unlocks further zoom-out.
     */
    fun updateMinZoom(value: Double) {
        require(value.isFinite() && value > 0.0) {
            "minZoom must be finite and greater than zero"
        }
        require(value <= maxZoom) { "minZoom must not exceed maxZoom" }
        minZoomState = value
        zoom = zoom.coerceIn(minZoomState, maxZoom)
    }

    fun moveTo(centerX: Double, centerY: Double, zoom: Double = this.zoom) {
        require(centerX.isFinite()) { "centerX must be finite" }
        require(centerY.isFinite()) { "centerY must be finite" }
        require(zoom.isFinite() && zoom > 0.0) {
            "zoom must be finite and greater than zero"
        }
        this.centerX = centerX
        this.centerY = centerY
        this.zoom = zoom.coerceIn(minZoomState, maxZoom)
    }

    /**
     * Applies one transform-gesture update while keeping the world point beneath [centroidX] and
     * [centroidY] anchored beneath the fingers. Positive pan moves board content right/down.
     */
    fun applyTransformGesture(
        centroidX: Double,
        centroidY: Double,
        panX: Double,
        panY: Double,
        zoomChange: Double,
        baseCellSizePx: Double,
    ) {
        require(
            centroidX.isFinite() &&
                centroidY.isFinite() &&
                panX.isFinite() &&
                panY.isFinite() &&
                zoomChange.isFinite() &&
                baseCellSizePx.isFinite(),
        ) { "Gesture values must be finite" }
        require(zoomChange > 0.0) { "zoomChange must be greater than zero" }
        require(baseCellSizePx > 0.0) { "baseCellSizePx must be greater than zero" }

        val oldPixelsPerCell = baseCellSizePx * zoom
        val newZoom = (zoom * zoomChange).coerceIn(minZoomState, maxZoom)
        val newPixelsPerCell = baseCellSizePx * newZoom
        val screenCenterX = viewportWidthPx / 2.0
        val screenCenterY = viewportHeightPx / 2.0
        val anchoredWorldX = centerX + (centroidX - screenCenterX) / oldPixelsPerCell
        val anchoredWorldY = centerY + (centroidY - screenCenterY) / oldPixelsPerCell

        centerX = anchoredWorldX -
            (centroidX + panX - screenCenterX) / newPixelsPerCell
        centerY = anchoredWorldY -
            (centroidY + panY - screenCenterY) / newPixelsPerCell
        zoom = newZoom
    }

    fun visibleChunkBounds(
        baseCellSizePx: Double,
        renderMarginChunks: Int = DEFAULT_RENDER_MARGIN_CHUNKS,
    ): ChunkBounds? = calculateVisibleChunkBounds(
        centerX = centerX,
        centerY = centerY,
        zoom = zoom,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        baseCellSizePx = baseCellSizePx,
        renderMarginChunks = renderMarginChunks,
    )

    fun visibleChunks(
        baseCellSizePx: Double,
        renderMarginChunks: Int = DEFAULT_RENDER_MARGIN_CHUNKS,
    ): Set<ChunkCoord> = visibleChunkBounds(baseCellSizePx, renderMarginChunks)?.toSet().orEmpty()
}

data class ChunkBounds(
    val minCx: Int,
    val minCy: Int,
    val maxCx: Int,
    val maxCy: Int,
) {
    init {
        require(minCx <= maxCx) { "minCx must not exceed maxCx" }
        require(minCy <= maxCy) { "minCy must not exceed maxCy" }
    }

    operator fun contains(coord: ChunkCoord): Boolean =
        coord.cx in minCx..maxCx && coord.cy in minCy..maxCy

    fun toSet(): Set<ChunkCoord> = buildSet {
        for (cy in minCy..maxCy) {
            for (cx in minCx..maxCx) {
                add(ChunkCoord(cx, cy))
            }
        }
    }
}

fun calculateVisibleChunkBounds(
    centerX: Double,
    centerY: Double,
    zoom: Double,
    viewportWidthPx: Double,
    viewportHeightPx: Double,
    baseCellSizePx: Double,
    renderMarginChunks: Int = DEFAULT_RENDER_MARGIN_CHUNKS,
): ChunkBounds? {
    require(
        centerX.isFinite() &&
            centerY.isFinite() &&
            zoom.isFinite() &&
            viewportWidthPx.isFinite() &&
            viewportHeightPx.isFinite() &&
            baseCellSizePx.isFinite(),
    ) { "Viewport values must be finite" }
    require(zoom > 0.0) { "zoom must be greater than zero" }
    require(viewportWidthPx >= 0.0 && viewportHeightPx >= 0.0) {
        "Viewport dimensions must be non-negative"
    }
    require(baseCellSizePx > 0.0) { "baseCellSizePx must be greater than zero" }
    require(renderMarginChunks >= 0) { "renderMarginChunks must be non-negative" }
    if (viewportWidthPx == 0.0 || viewportHeightPx == 0.0) return null

    val pixelsPerCell = baseCellSizePx * zoom
    val halfWidthCells = viewportWidthPx / pixelsPerCell / 2.0
    val halfHeightCells = viewportHeightPx / pixelsPerCell / 2.0
    val minCx = floor((centerX - halfWidthCells) / CHUNK_SIDE_LENGTH).toLong()
    val minCy = floor((centerY - halfHeightCells) / CHUNK_SIDE_LENGTH).toLong()
    // The viewport's maximum edge is exclusive: touching a chunk boundary does not make the
    // chunk on the other side visible.
    val maxCx = ceil((centerX + halfWidthCells) / CHUNK_SIDE_LENGTH).toLong() - 1L
    val maxCy = ceil((centerY + halfHeightCells) / CHUNK_SIDE_LENGTH).toLong() - 1L

    return ChunkBounds(
        minCx = saturatedInt(minCx).toLong()
            .minus(renderMarginChunks)
            .coerceAtLeast(Int.MIN_VALUE.toLong())
            .toInt(),
        minCy = saturatedInt(minCy).toLong()
            .minus(renderMarginChunks)
            .coerceAtLeast(Int.MIN_VALUE.toLong())
            .toInt(),
        maxCx = saturatedInt(maxCx).toLong()
            .plus(renderMarginChunks)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt(),
        maxCy = saturatedInt(maxCy).toLong()
            .plus(renderMarginChunks)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt(),
    )
}

/**
 * Zoom-out floor from the explored selector AABB and current viewport pixel size.
 *
 * Uses [BoardDimens.MinZoom] as the baseline; when the span of explored selectors is large enough
 * that fitting it requires zooming out further, the floor drops down to [BoardDimens.AbsoluteMinZoom].
 */
fun computeMinZoomFromExploredBounds(
    viewportWidthPx: Double,
    viewportHeightPx: Double,
    baseCellSizePx: Double,
    hasExploredBounds: Boolean,
    exploredMinCx: Int = 0,
    exploredMaxCx: Int = 0,
    exploredMinCy: Int = 0,
    exploredMaxCy: Int = 0,
    baselineMinZoom: Double = BoardDimens.MinZoom.toDouble(),
    absoluteMinZoom: Double = BoardDimens.AbsoluteMinZoom.toDouble(),
): Double {
    require(viewportWidthPx.isFinite() && viewportWidthPx >= 0.0) {
        "viewportWidthPx must be finite and non-negative"
    }
    require(viewportHeightPx.isFinite() && viewportHeightPx >= 0.0) {
        "viewportHeightPx must be finite and non-negative"
    }
    require(baseCellSizePx.isFinite() && baseCellSizePx > 0.0) {
        "baseCellSizePx must be finite and greater than zero"
    }
    require(baselineMinZoom.isFinite() && baselineMinZoom > 0.0) {
        "baselineMinZoom must be finite and greater than zero"
    }
    require(absoluteMinZoom.isFinite() && absoluteMinZoom > 0.0) {
        "absoluteMinZoom must be finite and greater than zero"
    }
    require(absoluteMinZoom <= baselineMinZoom) {
        "absoluteMinZoom must not exceed baselineMinZoom"
    }
    if (viewportWidthPx == 0.0 || viewportHeightPx == 0.0) return baselineMinZoom

    val spanW = if (hasExploredBounds) {
        (exploredMaxCx.toLong() - exploredMinCx.toLong() + 1L).coerceAtLeast(1L)
    } else {
        1L
    }
    val spanH = if (hasExploredBounds) {
        (exploredMaxCy.toLong() - exploredMinCy.toLong() + 1L).coerceAtLeast(1L)
    } else {
        1L
    }
    val fitZoomW = viewportWidthPx / (baseCellSizePx * spanW * CHUNK_SIDE_LENGTH)
    val fitZoomH = viewportHeightPx / (baseCellSizePx * spanH * CHUNK_SIDE_LENGTH)
    val fitZoom = minOf(fitZoomW, fitZoomH)
    return minOf(baselineMinZoom, fitZoom).coerceAtLeast(absoluteMinZoom)
}

@Composable
fun rememberViewportState(
    initialCenterX: Double = 0.0,
    initialCenterY: Double = 0.0,
    initialZoom: Double = 1.0,
): ViewportState = remember {
    ViewportState(
        initialCenterX = initialCenterX,
        initialCenterY = initialCenterY,
        initialZoom = initialZoom,
    )
}

/**
 * Installs pan/pinch handling and keeps [ViewportState]'s pixel dimensions current.
 */
@Composable
fun Modifier.viewportGestures(viewportState: ViewportState): Modifier {
    val baseCellSizePx = with(LocalDensity.current) {
        BoardDimens.BaseCellSizeDp * density
    }.toDouble()
    return this
        .onSizeChanged {
            viewportState.updateViewportSize(
                widthPx = it.width.toDouble(),
                heightPx = it.height.toDouble(),
            )
        }
        .pointerInput(viewportState, baseCellSizePx) {
            detectTransformGestures { centroid: Offset, pan: Offset, gestureZoom: Float, _ ->
                viewportState.applyTransformGesture(
                    centroidX = centroid.x.toDouble(),
                    centroidY = centroid.y.toDouble(),
                    panX = pan.x.toDouble(),
                    panY = pan.y.toDouble(),
                    zoomChange = gestureZoom.toDouble(),
                    baseCellSizePx = baseCellSizePx,
                )
            }
        }
}

private fun saturatedInt(value: Long): Int = value.coerceIn(
    minimumValue = Int.MIN_VALUE.toLong(),
    maximumValue = Int.MAX_VALUE.toLong(),
).toInt()
