package com.infinite.minesweeper.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Board and chrome colors for the gold-on-dark visual language.
 *
 * Number digits use [NumberPalette]; far-zoom pixels use [LodPalette].
 */
object BoardPalette {
    val Background = Color(0xFF121212)
    val Surface = Color(0xFF1A1A1A)
    val OnSurface = Color(0xFFE8E8E8)
    val AccentGold = Color(0xFFD4AF37)

    val CellHidden = Color(0xFF2C2C34)
    val CellHiddenHighlight = Color(0xFF3A3A44)
    val CellHiddenShadow = Color(0xFF1A1A20)
    val CellRevealed = Color(0xFF1C1C22)
    val CellGridLine = Color(0xFF2A2A30)

    val Flag = Color(0xFFE53935)
    val FlagPole = Color(0xFFE0E0E0)
    val MineBody = Color(0xFFE8E8E8)
    val MineExploded = Color(0xFFC62828)
    val LockedOverlay = Color(0x99B71C1C)
    val WipeFlash = Color(0xFFFFEB3B)

    val HudMuted = Color(0xFF9E9E9E)
}

/**
 * Distinct gold-on-dark colors for revealed adjacency counts 1–8.
 * Index 0 is unused (empty revealed cells draw no digit).
 */
object NumberPalette {
    val One = Color(0xFFFFD54F)
    val Two = Color(0xFF81C784)
    val Three = Color(0xFFFF8A65)
    val Four = Color(0xFF64B5F6)
    val Five = Color(0xFFE57373)
    val Six = Color(0xFF4DB6AC)
    val Seven = Color(0xFFF5F5F5)
    val Eight = Color(0xFFBDBDBD)

    private val byCount: Array<Color> = arrayOf(
        Color.Transparent,
        One,
        Two,
        Three,
        Four,
        Five,
        Six,
        Seven,
        Eight,
    )

    fun colorFor(adjacentMines: Int): Color {
        require(adjacentMines in 0..8) {
            "adjacentMines must be in 0..8, but was $adjacentMines"
        }
        return byCount[adjacentMines]
    }
}

/**
 * LOD far-zoom pixel colors (plan §8). Flags only — never mine locations.
 */
object LodPalette {
    val Hidden = Color(0xFF000000)
    val Revealed = Color(0xFF9E9E9E)
    val Flagged = Color(0xFFE53935)
    val CompletedChunk = Color(0xFF9E9E9E)
    val LockedChunk = Color(0xFFE53935)
}
