package com.infinite.minesweeper.core.engine

import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.ChunkCoord
import kotlin.math.abs

/**
 * The up-to-8 cell coordinates orthogonally/diagonally adjacent to [cell].
 *
 * Coordinates that would overflow [Int] are omitted rather than wrapped, matching the codec and
 * generator's treatment of the range limits.
 */
internal fun neighbors8(cell: CellCoord): List<CellCoord> = buildList(8) {
    for (dy in -1..1) {
        for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = cell.x.toLong() + dx
            val ny = cell.y.toLong() + dy
            if (nx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                ny in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            ) {
                add(CellCoord(nx.toInt(), ny.toInt()))
            }
        }
    }
}

/**
 * Chebyshev (chessboard) distance between two chunk coordinates, used to bound cascade growth.
 */
internal fun chebyshevChunkDistance(a: ChunkCoord, b: ChunkCoord): Long = maxOf(
    abs(a.cx.toLong() - b.cx.toLong()),
    abs(a.cy.toLong() - b.cy.toLong()),
)
