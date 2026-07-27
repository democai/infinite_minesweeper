package com.infinite.minesweeper.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.ui.theme.BoardPalette
import com.infinite.minesweeper.ui.theme.NumberPalette

/**
 * Allocation-free full-detail cell painter.
 *
 * All size-dependent geometry, draw styles, and measured number glyphs are created before the
 * draw pass. The single mutable [flagPath] is reused serially by the Canvas draw loop.
 */
internal class CellDrawer(
    private val cellSizePx: Float,
    private val gridStrokePx: Float,
    private val numberLayouts: Array<TextLayoutResult?>,
) {
    private val cellSize = Size(cellSizePx, cellSizePx)
    private val gridStroke = Stroke(width = gridStrokePx)
    private val flagStroke = (cellSizePx * 0.065f).coerceAtLeast(gridStrokePx)
    private val mineStroke = (cellSizePx * 0.055f).coerceAtLeast(gridStrokePx)
    private val flagPath = Path()

    fun DrawScope.drawCell(
        cell: Cell,
        left: Float,
        top: Float,
    ) {
        when (cell.state) {
            CellState.HIDDEN -> drawHidden(left, top)
            CellState.REVEALED -> drawRevealed(cell.adjacentMines, left, top)
            CellState.FLAGGED -> drawFlag(left, top)
            CellState.EXPLODED -> drawExplodedMine(left, top)
        }
    }

    fun DrawScope.drawLockedOverlay(
        left: Float,
        top: Float,
        sizePx: Float,
    ) {
        drawRect(
            color = BoardPalette.LockedOverlay,
            topLeft = Offset(left, top),
            size = Size(sizePx, sizePx),
        )
        drawRect(
            color = BoardPalette.MineExploded,
            topLeft = Offset(left, top),
            size = Size(sizePx, sizePx),
            style = gridStroke,
        )
    }

    private fun DrawScope.drawHidden(left: Float, top: Float) {
        drawRect(
            color = BoardPalette.CellHidden,
            topLeft = Offset(left, top),
            size = cellSize,
        )

        val inset = gridStrokePx * 0.5f
        val right = left + cellSizePx - inset
        val bottom = top + cellSizePx - inset
        drawLine(
            color = BoardPalette.CellHiddenHighlight,
            start = Offset(left + inset, bottom),
            end = Offset(left + inset, top + inset),
            strokeWidth = gridStrokePx,
        )
        drawLine(
            color = BoardPalette.CellHiddenHighlight,
            start = Offset(left + inset, top + inset),
            end = Offset(right, top + inset),
            strokeWidth = gridStrokePx,
        )
        drawLine(
            color = BoardPalette.CellHiddenShadow,
            start = Offset(right, top + inset),
            end = Offset(right, bottom),
            strokeWidth = gridStrokePx,
        )
        drawLine(
            color = BoardPalette.CellHiddenShadow,
            start = Offset(right, bottom),
            end = Offset(left + inset, bottom),
            strokeWidth = gridStrokePx,
        )
    }

    private fun DrawScope.drawRevealed(
        adjacentMines: Int,
        left: Float,
        top: Float,
    ) {
        drawRect(
            color = BoardPalette.CellRevealed,
            topLeft = Offset(left, top),
            size = cellSize,
        )
        drawRect(
            color = BoardPalette.CellGridLine,
            topLeft = Offset(left, top),
            size = cellSize,
            style = gridStroke,
        )

        if (adjacentMines == 0) return

        val layout = numberLayouts[adjacentMines] ?: return
        drawText(
            textLayoutResult = layout,
            color = NumberPalette.colorFor(adjacentMines),
            topLeft = Offset(
                x = left + (cellSizePx - layout.size.width) * 0.5f,
                y = top + (cellSizePx - layout.size.height) * 0.5f,
            ),
        )
    }

    private fun DrawScope.drawFlag(left: Float, top: Float) {
        drawHidden(left, top)

        val poleX = left + cellSizePx * 0.43f
        val poleTop = top + cellSizePx * 0.22f
        val poleBottom = top + cellSizePx * 0.76f
        drawLine(
            color = BoardPalette.FlagPole,
            start = Offset(poleX, poleTop),
            end = Offset(poleX, poleBottom),
            strokeWidth = flagStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = BoardPalette.FlagPole,
            start = Offset(left + cellSizePx * 0.29f, poleBottom),
            end = Offset(left + cellSizePx * 0.62f, poleBottom),
            strokeWidth = flagStroke,
            cap = StrokeCap.Round,
        )

        flagPath.rewind()
        flagPath.moveTo(poleX, poleTop)
        flagPath.lineTo(left + cellSizePx * 0.73f, top + cellSizePx * 0.36f)
        flagPath.lineTo(poleX, top + cellSizePx * 0.49f)
        flagPath.close()
        drawPath(path = flagPath, color = BoardPalette.Flag)
    }

    private fun DrawScope.drawExplodedMine(left: Float, top: Float) {
        drawRect(
            color = BoardPalette.MineExploded,
            topLeft = Offset(left, top),
            size = cellSize,
        )
        drawRect(
            color = BoardPalette.CellGridLine,
            topLeft = Offset(left, top),
            size = cellSize,
            style = gridStroke,
        )

        val centerX = left + cellSizePx * 0.5f
        val centerY = top + cellSizePx * 0.5f
        val innerRadius = cellSizePx * 0.18f
        val spikeInner = cellSizePx * 0.23f
        val spikeOuter = cellSizePx * 0.35f

        drawLine(
            color = BoardPalette.MineBody,
            start = Offset(centerX - spikeOuter, centerY),
            end = Offset(centerX - spikeInner, centerY),
            strokeWidth = mineStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = BoardPalette.MineBody,
            start = Offset(centerX + spikeInner, centerY),
            end = Offset(centerX + spikeOuter, centerY),
            strokeWidth = mineStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = BoardPalette.MineBody,
            start = Offset(centerX, centerY - spikeOuter),
            end = Offset(centerX, centerY - spikeInner),
            strokeWidth = mineStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = BoardPalette.MineBody,
            start = Offset(centerX, centerY + spikeInner),
            end = Offset(centerX, centerY + spikeOuter),
            strokeWidth = mineStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = BoardPalette.MineBody,
            start = Offset(
                centerX - spikeOuter * 0.72f,
                centerY - spikeOuter * 0.72f,
            ),
            end = Offset(
                centerX - spikeInner * 0.72f,
                centerY - spikeInner * 0.72f,
            ),
            strokeWidth = mineStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = BoardPalette.MineBody,
            start = Offset(
                centerX + spikeInner * 0.72f,
                centerY + spikeInner * 0.72f,
            ),
            end = Offset(
                centerX + spikeOuter * 0.72f,
                centerY + spikeOuter * 0.72f,
            ),
            strokeWidth = mineStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = BoardPalette.MineBody,
            start = Offset(
                centerX + spikeInner * 0.72f,
                centerY - spikeInner * 0.72f,
            ),
            end = Offset(
                centerX + spikeOuter * 0.72f,
                centerY - spikeOuter * 0.72f,
            ),
            strokeWidth = mineStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = BoardPalette.MineBody,
            start = Offset(
                centerX - spikeInner * 0.72f,
                centerY + spikeInner * 0.72f,
            ),
            end = Offset(
                centerX - spikeOuter * 0.72f,
                centerY + spikeOuter * 0.72f,
            ),
            strokeWidth = mineStroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = BoardPalette.MineBody,
            radius = innerRadius,
            center = Offset(centerX, centerY),
        )
        drawCircle(
            color = BoardPalette.CellHiddenShadow,
            radius = innerRadius * 0.28f,
            center = Offset(
                centerX - innerRadius * 0.32f,
                centerY - innerRadius * 0.32f,
            ),
        )
    }
}
