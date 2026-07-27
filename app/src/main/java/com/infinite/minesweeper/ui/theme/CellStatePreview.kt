package com.infinite.minesweeper.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual reference sheet of every cell appearance T6 must draw.
 *
 * Not wired into gameplay — Studio / device previews only.
 */
@Composable
fun CellStatePreview(modifier: Modifier = Modifier) {
    val cell = BoardDimens.BaseCellSizeDp.dp
    Column(
        modifier = modifier
            .background(BoardPalette.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Infinite Minesweeper",
            style = MaterialTheme.typography.titleMedium,
            color = BoardPalette.AccentGold,
        )
        Text(
            text = "X: -76 Y: 59",
            style = MaterialTheme.typography.labelLarge,
            color = BoardPalette.OnSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudCounter(caption = "Flags", value = "12")
            HudCounter(caption = "Cleared", value = "3")
            HudCounter(caption = "Locked", value = "1")
            HudCounter(caption = "Wiped", value = "0")
        }

        SectionTitle("Full-detail cells")
        Row(
            horizontalArrangement = Arrangement.spacedBy(BoardDimens.PreviewCellGapDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabeledCell(label = "Hidden", size = cell) { HiddenCell(size = cell) }
            LabeledCell(label = "Empty", size = cell) { RevealedCell(adjacent = 0, size = cell) }
            LabeledCell(label = "Flag", size = cell) { FlaggedCell(size = cell) }
            LabeledCell(label = "Mine", size = cell) { ExplodedCell(size = cell) }
            LabeledCell(label = "Locked", size = cell) {
                Box {
                    HiddenCell(size = cell)
                    Box(
                        modifier = Modifier
                            .size(cell)
                            .background(BoardPalette.LockedOverlay),
                    )
                }
            }
        }

        SectionTitle("Numbers 1–8")
        Row(horizontalArrangement = Arrangement.spacedBy(BoardDimens.PreviewCellGapDp.dp)) {
            for (n in 1..8) {
                LabeledCell(label = n.toString(), size = cell) {
                    RevealedCell(adjacent = n, size = cell)
                }
            }
        }

        SectionTitle("LOD pixels (plan §8)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LodSwatch(label = "Hidden", color = LodPalette.Hidden)
            LodSwatch(label = "Revealed", color = LodPalette.Revealed)
            LodSwatch(label = "Flagged", color = LodPalette.Flagged)
            LodSwatch(label = "Done", color = LodPalette.CompletedChunk)
            LodSwatch(label = "Locked", color = LodPalette.LockedChunk)
        }

        Text(
            text = "LOD when cell < ${BoardDimens.LodThresholdDp.toInt()} dp · " +
                "base cell ${BoardDimens.BaseCellSizeDp.toInt()} dp",
            style = MaterialTheme.typography.labelSmall,
            color = BoardPalette.HudMuted,
        )
    }
}

@Composable
private fun HudCounter(caption: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = BoardPalette.AccentGold,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = BoardPalette.HudMuted,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = BoardPalette.HudMuted,
    )
}

@Composable
private fun LabeledCell(
    label: String,
    size: Dp,
    content: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = BoardPalette.HudMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(size),
        )
    }
}

@Composable
private fun HiddenCell(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(BoardPalette.CellHidden)
            .border(1.dp, BoardPalette.CellHiddenHighlight)
            .padding(1.dp)
            .border(1.dp, BoardPalette.CellHiddenShadow),
    )
}

@Composable
private fun RevealedCell(adjacent: Int, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(BoardPalette.CellRevealed)
            .border(BoardDimens.CellGridStrokeDp.dp, BoardPalette.CellGridLine),
        contentAlignment = Alignment.Center,
    ) {
        if (adjacent > 0) {
            Text(
                text = adjacent.toString(),
                color = NumberPalette.colorFor(adjacent),
                fontSize = (BoardDimens.BaseCellSizeDp * CellDigitSizeFraction).sp,
                fontWeight = MaterialTheme.typography.bodyLarge.fontWeight,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FlaggedCell(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(BoardPalette.CellHidden)
            .border(1.dp, BoardPalette.CellHiddenHighlight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "▶",
            color = BoardPalette.Flag,
            fontSize = (BoardDimens.BaseCellSizeDp * 0.45f).sp,
        )
    }
}

@Composable
private fun ExplodedCell(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(BoardPalette.MineExploded)
            .border(1.dp, BoardPalette.CellGridLine),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "●",
            color = BoardPalette.MineBody,
            fontSize = (BoardDimens.BaseCellSizeDp * 0.5f).sp,
        )
    }
}

@Composable
private fun LodSwatch(label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color)
                .border(1.dp, BoardPalette.OnSurface.copy(alpha = 0.35f)),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = BoardPalette.HudMuted,
        )
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 720, name = "Cell state reference")
@Composable
private fun CellStatePreviewPreview() {
    InfiniteMinesweeperTheme {
        CellStatePreview(modifier = Modifier.fillMaxWidth())
    }
}
