package com.infinite.minesweeper.ui.board

import android.graphics.Bitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.infinite.minesweeper.core.cache.ChunkCache
import com.infinite.minesweeper.core.model.CELLS_PER_CHUNK
import com.infinite.minesweeper.core.model.CHUNK_SIDE_LENGTH
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.ui.theme.BoardDimens
import com.infinite.minesweeper.ui.theme.LodPalette
import kotlin.math.roundToInt

/**
 * Far-zoom LOD path (plan §8): each chunk becomes one 8×8 [ImageBitmap], one pixel per cell,
 * drawn with nearest-neighbor scaling. Flags are visible; hidden mine locations are never
 * colored. Chunk-level overrides (completed = flat grey, locked = flat red) are applied before
 * per-cell baking so auto-flagged cleared chunks stay solid grey.
 *
 * Bitmaps bake once and live as [ChunkCache] LOD artifacts; a changed chunk clears its artifact
 * so the next [obtainBitmap] rebakes. Eviction of the cache entry drops the bitmap with it.
 */
object LodRenderer {
    val bitmapSide: Int = CHUNK_SIDE_LENGTH

    private val hiddenArgb: Int = LodPalette.Hidden.toArgb()
    private val revealedArgb: Int = LodPalette.Revealed.toArgb()
    private val flaggedArgb: Int = LodPalette.Flagged.toArgb()
    private val completedArgb: Int = LodPalette.CompletedChunk.toArgb()
    private val lockedArgb: Int = LodPalette.LockedChunk.toArgb()

    /**
     * True when the on-screen cell edge is below the LOD threshold (~12 dp/cell).
     */
    fun shouldUseLod(cellSizeDp: Float): Boolean = cellSizeDp < BoardDimens.LodThresholdDp

    /**
     * Completed selectors have no hidden (or exploded) cells left: every cell is revealed or
     * flagged. Detection uses only player-visible [CellState] values — never [Cell.isMine].
     */
    fun isCompletedChunk(chunk: Chunk): Boolean {
        if (!chunk.generated || chunk.status == ChunkStatus.LOCKED) return false
        return chunk.cells.all { cell ->
            cell.state == CellState.REVEALED || cell.state == CellState.FLAGGED
        }
    }

    /**
     * Packs 64 ARGB8888 pixels in row-major order (`y * 8 + x`). Overrides are applied first.
     */
    fun bakeArgbPixels(chunk: Chunk): IntArray {
        val pixels = IntArray(CELLS_PER_CHUNK)
        bakeArgbPixelsInto(chunk, pixels)
        return pixels
    }

    /**
     * Writes 64 ARGB8888 pixels into [out] (must be length [CELLS_PER_CHUNK]).
     */
    fun bakeArgbPixelsInto(chunk: Chunk, out: IntArray) {
        require(out.size == CELLS_PER_CHUNK) {
            "LOD pixel buffer must hold $CELLS_PER_CHUNK entries, but was ${out.size}"
        }
        when {
            chunk.status == ChunkStatus.LOCKED -> out.fill(lockedArgb)
            isCompletedChunk(chunk) -> out.fill(completedArgb)
            else -> {
                for (index in 0 until CELLS_PER_CHUNK) {
                    out[index] = argbForCell(chunk.cells[index].state)
                }
            }
        }
    }

    /**
     * Bakes a fresh 8×8 [ImageBitmap] for [chunk]. Prefer [obtainBitmap] when a cache is available.
     */
    fun bakeImageBitmap(chunk: Chunk): ImageBitmap {
        val pixels = bakeArgbPixels(chunk)
        val androidBitmap = Bitmap.createBitmap(
            pixels,
            CHUNK_SIDE_LENGTH,
            CHUNK_SIDE_LENGTH,
            Bitmap.Config.ARGB_8888,
        )
        return androidBitmap.asImageBitmap()
    }

    /**
     * Returns the cached LOD bitmap for [chunk], baking and storing one when absent.
     * Callers must serialize cache access on one coroutine context (see [ChunkCache]).
     */
    fun obtainBitmap(cache: ChunkCache<ImageBitmap>, chunk: Chunk): ImageBitmap {
        cache.lodArtifact(chunk.coord)?.let { return it }
        val baked = bakeImageBitmap(chunk)
        cache.setLodArtifact(chunk.coord, baked)
        return baked
    }

    /**
     * Draws [bitmap] scaled to a square of [sizePx] with nearest-neighbor filtering.
     */
    fun DrawScope.drawLodChunk(
        bitmap: ImageBitmap,
        left: Float,
        top: Float,
        sizePx: Float,
    ) {
        val dstSizePx = sizePx.roundToInt().coerceAtLeast(1)
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(dstSizePx, dstSizePx),
            filterQuality = FilterQuality.None,
        )
    }

    private fun argbForCell(state: CellState): Int = when (state) {
        CellState.HIDDEN -> hiddenArgb
        CellState.REVEALED -> revealedArgb
        // Exploded mines are already known to the player; treat like a flag (red), never peek
        // at hidden mine bits for other cells.
        CellState.FLAGGED, CellState.EXPLODED -> flaggedArgb
    }
}
