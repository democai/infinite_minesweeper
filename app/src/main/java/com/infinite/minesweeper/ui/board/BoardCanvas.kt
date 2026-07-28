package com.infinite.minesweeper.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.infinite.minesweeper.core.model.CELLS_PER_CHUNK
import com.infinite.minesweeper.core.model.CHUNK_SIDE_LENGTH
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.ui.settings.LongPressDuration
import com.infinite.minesweeper.ui.theme.BoardDimens
import com.infinite.minesweeper.ui.theme.BoardPalette
import com.infinite.minesweeper.ui.theme.CellDigitSizeFraction
import com.infinite.minesweeper.ui.theme.InfiniteMinesweeperTheme
import kotlin.math.floor
import kotlin.math.min
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T6 visual fixture. It intentionally has no gestures or viewport state.
 */
@Composable
fun BoardCanvas(modifier: Modifier = Modifier) {
    val chunks = remember { staticPreviewChunks() }
    BoardCanvas(chunks = chunks, modifier = modifier)
}

/**
 * Full-detail board renderer shared with the viewport work in T7.
 *
 * The supplied chunks are immutable render input. Cell and chunk positions are calculated from
 * their world chunk coordinates; hidden mine data is never inspected by [CellDrawer].
 */
@Composable
fun BoardCanvas(
    chunks: List<Chunk>,
    modifier: Modifier = Modifier,
) {
    val boardBounds = remember(chunks) { BoardBounds.of(chunks) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 8)

    BoxWithConstraints(
        modifier = modifier.background(BoardPalette.Background),
    ) {
        val cellSize = fittedCellSize(
            availableWidth = maxWidth,
            availableHeight = maxHeight,
            boardColumns = boardBounds.widthInCells,
            boardRows = boardBounds.heightInCells,
        )
        val cellSizePx = with(density) { cellSize.toPx() }
        val gridStrokePx = with(density) { BoardDimens.CellGridStrokeDp.dp.toPx() }
        val numberLayouts = rememberNumberLayouts(
            textMeasurer = textMeasurer,
            cellSize = cellSize,
        )
        val cellDrawer = remember(cellSizePx, gridStrokePx, numberLayouts) {
            CellDrawer(
                cellSizePx = cellSizePx,
                gridStrokePx = gridStrokePx,
                numberLayouts = numberLayouts,
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val boardWidthPx = boardBounds.widthInCells * cellSizePx
            val boardHeightPx = boardBounds.heightInCells * cellSizePx
            val boardLeft = (size.width - boardWidthPx) * 0.5f
            val boardTop = (size.height - boardHeightPx) * 0.5f

            for (chunkIndex in chunks.indices) {
                drawChunk(
                    chunk = chunks[chunkIndex],
                    bounds = boardBounds,
                    boardLeft = boardLeft,
                    boardTop = boardTop,
                    cellSizePx = cellSizePx,
                    cellDrawer = cellDrawer,
                )
            }
        }
    }
}

/**
 * Interactive viewport renderer used from T7 onward.
 *
 * Only chunks intersecting the viewport plus the one-chunk render margin are passed through the
 * draw loop. The original [BoardCanvas] overload remains as the fixed T6 visual fixture.
 */
@Composable
fun ViewportBoardCanvas(
    chunks: Collection<Chunk>,
    viewportState: ViewportState,
    modifier: Modifier = Modifier,
    onTap: (CellCoord) -> Unit = {},
    onLongPress: (CellCoord) -> Unit = {},
    longPressTimeoutMs: Long = LongPressDuration.Default.timeoutMs,
    effect: BoardEffect? = null,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 8)
    val baseCellSizePx = with(density) { BoardDimens.BaseCellSizeDp.dp.toPx() }.toDouble()
    val cellSize = (BoardDimens.BaseCellSizeDp * viewportState.zoom.toFloat()).dp
    val cellSizePx = with(density) { cellSize.toPx() }
    val gridStrokePx = with(density) { BoardDimens.CellGridStrokeDp.dp.toPx() }
    val chunkOutlineStrokePx = with(density) { BoardDimens.ChunkOutlineStrokeDp.dp.toPx() }
    val visibleBounds = viewportState.visibleChunkBounds(baseCellSizePx)
    val visibleChunks = remember(chunks, visibleBounds) {
        if (visibleBounds == null) {
            emptyList()
        } else {
            chunks.filter { it.coord in visibleBounds }
        }
    }
    val numberLayouts = rememberNumberLayouts(
        textMeasurer = textMeasurer,
        cellSize = cellSize,
    )
    val cellDrawer = remember(cellSizePx, gridStrokePx, numberLayouts) {
        CellDrawer(
            cellSizePx = cellSizePx,
            gridStrokePx = gridStrokePx,
            numberLayouts = numberLayouts,
        )
    }
    val lodBitmaps = remember { mutableMapOf<ChunkCoord, Pair<Chunk, ImageBitmap>>() }
    val visibleCoordinates = visibleChunks.mapTo(hashSetOf()) { it.coord }
    lodBitmaps.keys.retainAll(visibleCoordinates)
    val useLod = LodRenderer.shouldUseLod(cellSize.value)
    if (useLod) {
        visibleChunks.forEach { chunk ->
            if (lodBitmaps[chunk.coord]?.first != chunk) {
                lodBitmaps[chunk.coord] = chunk to LodRenderer.bakeImageBitmap(chunk)
            }
        }
    }

    Canvas(
        modifier = modifier
            .background(BoardPalette.Background)
            .boardInputGestures(
                viewportState = viewportState,
                longPressTimeoutMs = longPressTimeoutMs,
                inputEnabled = !useLod,
                onTap = onTap,
                onLongPress = onLongPress,
            )
            .viewportGestures(viewportState),
    ) {
        val screenCenterX = size.width * 0.5f
        val screenCenterY = size.height * 0.5f
        for (chunkIndex in visibleChunks.indices) {
            val chunk = visibleChunks[chunkIndex]
            val chunkLeft = screenCenterX +
                ((chunk.coord.cx.toDouble() * CHUNK_SIDE_LENGTH - viewportState.centerX) * cellSizePx).toFloat()
            val chunkTop = screenCenterY +
                ((chunk.coord.cy.toDouble() * CHUNK_SIDE_LENGTH - viewportState.centerY) * cellSizePx).toFloat()
            val chunkSizePx = CHUNK_SIDE_LENGTH * cellSizePx
            if (useLod) {
                lodBitmaps[chunk.coord]?.second?.let { bitmap ->
                    with(LodRenderer) {
                        drawLodChunk(
                            bitmap = bitmap,
                            left = chunkLeft,
                            top = chunkTop,
                            sizePx = chunkSizePx,
                        )
                    }
                }
            } else {
                drawViewportChunk(
                    chunk = chunk,
                    viewportCenterX = viewportState.centerX,
                    viewportCenterY = viewportState.centerY,
                    screenCenterX = screenCenterX,
                    screenCenterY = screenCenterY,
                    cellSizePx = cellSizePx,
                    cellDrawer = cellDrawer,
                )
                // Solved blue tint is full-detail only (not the zoomed-out map).
                if (LodRenderer.isCompletedChunk(chunk)) {
                    drawRect(
                        color = BoardPalette.SolvedHighlight,
                        topLeft = Offset(chunkLeft, chunkTop),
                        size = Size(chunkSizePx, chunkSizePx),
                    )
                }
            }
            if (effect?.chunk == chunk.coord && effect.alpha > 0f) {
                drawRect(
                    color = effect.color,
                    topLeft = Offset(chunkLeft, chunkTop),
                    size = Size(chunkSizePx, chunkSizePx),
                    alpha = effect.alpha,
                )
            }
            drawChunkOutline(
                left = chunkLeft,
                top = chunkTop,
                sizePx = chunkSizePx,
                strokePx = chunkOutlineStrokePx,
            )
        }
        if (useLod) {
            val markerSizePx = with(density) { BoardDimens.HomeMarkerSizeDp.dp.toPx() }
            // Center of Home selector (chunk 0,0), not the top-left cell at world origin.
            val homeCenterWorld = CHUNK_SIDE_LENGTH / 2.0
            val markerCenterX =
                screenCenterX + ((homeCenterWorld - viewportState.centerX) * cellSizePx).toFloat()
            val markerCenterY =
                screenCenterY + ((homeCenterWorld - viewportState.centerY) * cellSizePx).toFloat()
            drawHomeMarker(centerX = markerCenterX, centerY = markerCenterY, sizePx = markerSizePx)
        }
    }
}

data class BoardEffect(
    val chunk: ChunkCoord,
    val color: Color,
    val alpha: Float,
)

/**
 * Installs tap/long-press detection that's distinct from a drag or pinch: a pointer that moves
 * past touch slop, or a second pointer joining mid-gesture, cancels the pending tap/long-press
 * entirely so [ViewportState]'s sibling `.viewportGestures` pan/pinch handling owns the gesture
 * instead (plan: "no marking/revealing while zooming or moving"). [inputEnabled] additionally
 * suppresses dispatch outright while the board is in LOD/overview mode, where a tap is too
 * imprecise to safely reveal or flag a cell.
 */
private fun Modifier.boardInputGestures(
    viewportState: ViewportState,
    longPressTimeoutMs: Long,
    inputEnabled: Boolean,
    onTap: (CellCoord) -> Unit,
    onLongPress: (CellCoord) -> Unit,
): Modifier = pointerInput(viewportState, longPressTimeoutMs, inputEnabled, onTap, onLongPress) {
    fun Offset.toCell(): CellCoord {
        val pixelsPerCell = BoardDimens.BaseCellSizeDp.dp.toPx() * viewportState.zoom
        val worldX = viewportState.centerX + (x - size.width / 2.0) / pixelsPerCell
        val worldY = viewportState.centerY + (y - size.height / 2.0) / pixelsPerCell
        return CellCoord(
            x = floor(worldX).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            y = floor(worldY).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
        )
    }
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        // awaitEachGesture's block already runs with an AwaitPointerEventScope receiver, so
        // awaitPointerEvent() below is called directly — no nested awaitPointerEventScope.
        val down = awaitFirstDown()
        var cancelled = false
        val resolvedBeforeTimeout = withTimeoutOrNull(longPressTimeoutMs) {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) {
                    cancelled = true
                    return@withTimeoutOrNull
                }
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull
                if (exceedsTouchSlop(down.position, change.position, slop)) {
                    cancelled = true
                    return@withTimeoutOrNull
                }
                if (!change.pressed) return@withTimeoutOrNull
            }
        } != null
        when {
            // Slop exceeded or a second pointer joined: this is a drag/pinch, not a tap — let
            // .viewportGestures handle it, don't dispatch anything.
            cancelled -> Unit
            resolvedBeforeTimeout -> if (inputEnabled) onTap(down.position.toCell())
            else -> {
                // Timed out while still down, within slop, single pointer → long-press.
                if (inputEnabled) onLongPress(down.position.toCell())
                waitForUpOrCancellation()
            }
        }
    }
}

/** True when [current] has moved far enough from [start] to no longer count as a tap. */
internal fun exceedsTouchSlop(start: Offset, current: Offset, slopPx: Float): Boolean =
    (current - start).getDistance() > slopPx

@Composable
private fun rememberNumberLayouts(
    textMeasurer: TextMeasurer,
    cellSize: Dp,
): Array<TextLayoutResult?> {
    val digitStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
        fontSize = with(LocalDensity.current) {
            (cellSize * CellDigitSizeFraction).toSp()
        },
    )
    return remember(textMeasurer, digitStyle) {
        arrayOfNulls<TextLayoutResult>(9).also { layouts ->
            for (number in 1..8) {
                layouts[number] = textMeasurer.measure(
                    text = number.toString(),
                    style = digitStyle,
                )
            }
        }
    }
}

private fun DrawScope.drawViewportChunk(
    chunk: Chunk,
    viewportCenterX: Double,
    viewportCenterY: Double,
    screenCenterX: Float,
    screenCenterY: Float,
    cellSizePx: Float,
    cellDrawer: CellDrawer,
) {
    val worldLeft = chunk.coord.cx.toDouble() * CHUNK_SIDE_LENGTH
    val worldTop = chunk.coord.cy.toDouble() * CHUNK_SIDE_LENGTH
    val chunkLeft = screenCenterX + ((worldLeft - viewportCenterX) * cellSizePx).toFloat()
    val chunkTop = screenCenterY + ((worldTop - viewportCenterY) * cellSizePx).toFloat()

    for (index in 0 until CELLS_PER_CHUNK) {
        val localX = index % CHUNK_SIDE_LENGTH
        val localY = index / CHUNK_SIDE_LENGTH
        with(cellDrawer) {
            drawCell(
                cell = chunk.cells[index],
                left = chunkLeft + localX * cellSizePx,
                top = chunkTop + localY * cellSizePx,
            )
        }
    }

    if (chunk.status == ChunkStatus.LOCKED) {
        with(cellDrawer) {
            drawLockedOverlay(
                left = chunkLeft,
                top = chunkTop,
                sizePx = CHUNK_SIDE_LENGTH * cellSizePx,
            )
        }
    }
}

private fun DrawScope.drawChunk(
    chunk: Chunk,
    bounds: BoardBounds,
    boardLeft: Float,
    boardTop: Float,
    cellSizePx: Float,
    cellDrawer: CellDrawer,
) {
    val chunkLeft = boardLeft +
        (chunk.coord.cx - bounds.minChunkX) * CHUNK_SIDE_LENGTH * cellSizePx
    val chunkTop = boardTop +
        (chunk.coord.cy - bounds.minChunkY) * CHUNK_SIDE_LENGTH * cellSizePx

    for (index in 0 until CELLS_PER_CHUNK) {
        val localX = index % CHUNK_SIDE_LENGTH
        val localY = index / CHUNK_SIDE_LENGTH
        with(cellDrawer) {
            drawCell(
                cell = chunk.cells[index],
                left = chunkLeft + localX * cellSizePx,
                top = chunkTop + localY * cellSizePx,
            )
        }
    }

    if (chunk.status == ChunkStatus.LOCKED) {
        with(cellDrawer) {
            drawLockedOverlay(
                left = chunkLeft,
                top = chunkTop,
                sizePx = CHUNK_SIDE_LENGTH * cellSizePx,
            )
        }
    }
    drawChunkOutline(
        left = chunkLeft,
        top = chunkTop,
        sizePx = CHUNK_SIDE_LENGTH * cellSizePx,
        strokePx = cellDrawer.gridStrokeWidthPx,
    )
}

/**
 * Small decorative house glyph centered in the Home selector (chunk 0,0), visible only in
 * overview/LOD mode as a landmark for orienting a large explored world. Purely visual: overview
 * mode has no tap dispatch at all (see [boardInputGestures]'s `inputEnabled`), so this never
 * needs hit-testing.
 */
private fun DrawScope.drawHomeMarker(centerX: Float, centerY: Float, sizePx: Float) {
    val half = sizePx * 0.5f
    val roofPeakY = centerY - half
    val eaveY = centerY - half * 0.15f
    val bodyBottom = centerY + half
    val left = centerX - half
    val right = centerX + half

    val roof = Path().apply {
        moveTo(centerX, roofPeakY)
        lineTo(right, eaveY)
        lineTo(left, eaveY)
        close()
    }
    drawPath(path = roof, color = BoardPalette.AccentGold)
    drawRect(
        color = BoardPalette.AccentGold,
        topLeft = Offset(left + half * 0.15f, eaveY),
        size = Size((right - left) - sizePx * 0.3f, bodyBottom - eaveY),
    )
    val doorWidth = sizePx * 0.22f
    val doorHeight = (bodyBottom - eaveY) * 0.55f
    drawRect(
        color = BoardPalette.Background,
        topLeft = Offset(centerX - doorWidth * 0.5f, bodyBottom - doorHeight),
        size = Size(doorWidth, doorHeight),
    )
}

/** Faint gold frame around an 8×8 selector so chunk boundaries stay readable while zoomed in. */
private fun DrawScope.drawChunkOutline(
    left: Float,
    top: Float,
    sizePx: Float,
    strokePx: Float,
) {
    val inset = strokePx * 0.5f
    drawRect(
        color = BoardPalette.ChunkOutline,
        topLeft = Offset(left + inset, top + inset),
        size = Size(sizePx - strokePx, sizePx - strokePx),
        style = Stroke(width = strokePx),
    )
}

private fun fittedCellSize(
    availableWidth: Dp,
    availableHeight: Dp,
    boardColumns: Int,
    boardRows: Int,
): Dp {
    val widthLimited = if (availableWidth.value.isFinite()) {
        availableWidth.value / boardColumns
    } else {
        BoardDimens.BaseCellSizeDp
    }
    val heightLimited = if (availableHeight.value.isFinite()) {
        availableHeight.value / boardRows
    } else {
        BoardDimens.BaseCellSizeDp
    }
    return min(
        BoardDimens.BaseCellSizeDp,
        min(widthLimited, heightLimited),
    ).coerceAtLeast(1f).dp
}

private data class BoardBounds(
    val minChunkX: Int,
    val minChunkY: Int,
    val maxChunkX: Int,
    val maxChunkY: Int,
) {
    val widthInCells: Int = (maxChunkX - minChunkX + 1) * CHUNK_SIDE_LENGTH
    val heightInCells: Int = (maxChunkY - minChunkY + 1) * CHUNK_SIDE_LENGTH

    companion object {
        fun of(chunks: List<Chunk>): BoardBounds {
            if (chunks.isEmpty()) {
                return BoardBounds(0, 0, 0, 0)
            }

            var minX = chunks[0].coord.cx
            var minY = chunks[0].coord.cy
            var maxX = minX
            var maxY = minY
            for (index in 1 until chunks.size) {
                val coord = chunks[index].coord
                if (coord.cx < minX) minX = coord.cx
                if (coord.cy < minY) minY = coord.cy
                if (coord.cx > maxX) maxX = coord.cx
                if (coord.cy > maxY) maxY = coord.cy
            }
            return BoardBounds(minX, minY, maxX, maxY)
        }
    }
}

private fun staticPreviewChunks(): List<Chunk> {
    val normalCells = MutableList(CELLS_PER_CHUNK) { index ->
        when (index) {
            0 -> Cell(state = CellState.HIDDEN)
            1 -> Cell(state = CellState.FLAGGED, isMine = true)
            in 2..7 -> Cell(
                state = CellState.REVEALED,
                adjacentMines = index - 2,
            )
            in 8..10 -> Cell(
                state = CellState.REVEALED,
                adjacentMines = index - 2,
            )
            else -> Cell(
                state = if ((index + index / CHUNK_SIDE_LENGTH) % 3 == 0) {
                    CellState.REVEALED
                } else {
                    CellState.HIDDEN
                },
                adjacentMines = if (index % 7 == 0) 2 else 0,
            )
        }
    }

    val lockedCells = MutableList(CELLS_PER_CHUNK) { index ->
        Cell(
            state = if (index % 5 == 0) CellState.REVEALED else CellState.HIDDEN,
            adjacentMines = if (index % 5 == 0) (index % 3) + 1 else 0,
        )
    }
    lockedCells[27] = Cell(
        state = CellState.EXPLODED,
        isMine = true,
    )

    return listOf(
        Chunk(
            coord = ChunkCoord(cx = -1, cy = 0),
            generated = true,
            cells = normalCells,
        ),
        Chunk(
            coord = ChunkCoord(cx = 0, cy = 0),
            generated = true,
            cells = lockedCells,
            status = ChunkStatus.LOCKED,
            lockedAt = 1L,
        ),
    )
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFF121212,
    widthDp = 720,
    heightDp = 420,
    name = "T6 static full-detail board",
)
@Composable
private fun BoardCanvasPreview() {
    InfiniteMinesweeperTheme {
        BoardCanvas(modifier = Modifier.fillMaxSize())
    }
}
