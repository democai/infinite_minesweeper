package com.infinite.minesweeper.ui.settings

import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.GameAction

/** The two gestures the board's pointer input distinguishes. */
enum class TapKind { TAP, LONG_PRESS }

private enum class ResolvedInput { REVEAL, FLAG }

/**
 * Maps a raw gesture into a [GameAction], respecting the configured [InputBinding].
 *
 * Pure and dependency-free so the binding toggle's effect on dispatch is JVM-testable without a
 * device (dev tree T11 test requirement). Kept in `ui.settings` rather than `core.engine` because
 * the mapping decision itself — which physical gesture means what — is a UI/preference concern;
 * the produced [GameAction] is all [com.infinite.minesweeper.core.model.GameEngine] ever sees.
 */
object InputActionMapper {
    fun map(
        gesture: TapKind,
        cell: CellCoord,
        cellState: CellState,
        binding: InputBinding,
    ): GameAction? = when (cellState) {
        // Already resolved (exploded mine in a locked chunk); nothing left to dispatch here. The
        // engine also disables the chunk's input, but the mapper stays defensive independently.
        CellState.EXPLODED -> null

        // Chording is gesture-specific, not binding-specific: a tap on a revealed number always
        // chords, and long-press on a revealed cell has no defined action (plan §6).
        CellState.REVEALED -> if (gesture == TapKind.TAP) GameAction.Chord(cell) else null

        CellState.HIDDEN, CellState.FLAGGED -> when (binding.resolve(gesture)) {
            // A flagged cell blocks reveal until unflagged, matching classic minesweeper input
            // safety — the gesture bound to "reveal" is a no-op on a cell the player flagged.
            ResolvedInput.REVEAL -> if (cellState == CellState.HIDDEN) GameAction.Reveal(cell) else null
            ResolvedInput.FLAG -> GameAction.ToggleFlag(cell)
        }
    }

    private fun InputBinding.resolve(gesture: TapKind): ResolvedInput = when (this) {
        InputBinding.TAP_REVEAL_LONG_PRESS_FLAG ->
            if (gesture == TapKind.TAP) ResolvedInput.REVEAL else ResolvedInput.FLAG
        InputBinding.TAP_FLAG_LONG_PRESS_REVEAL ->
            if (gesture == TapKind.TAP) ResolvedInput.FLAG else ResolvedInput.REVEAL
    }
}
