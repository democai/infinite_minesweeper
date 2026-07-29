package com.infinite.minesweeper.ui.settings

import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.GameAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputActionMapperTest {

    private val cell = CellCoord(3, 4)

    @Test
    fun defaultBinding_tapOnHidden_flags() {
        val action = InputActionMapper.map(
            gesture = TapKind.TAP,
            cell = cell,
            cellState = CellState.HIDDEN,
            binding = InputBinding.Default,
        )
        assertEquals(GameAction.ToggleFlag(cell), action)
    }

    @Test
    fun defaultBinding_longPressOnHidden_reveals() {
        val action = InputActionMapper.map(
            gesture = TapKind.LONG_PRESS,
            cell = cell,
            cellState = CellState.HIDDEN,
            binding = InputBinding.Default,
        )
        assertEquals(GameAction.Reveal(cell), action)
    }

    @Test
    fun alternateBinding_swapsTapAndLongPressDispatch() {
        val tapAction = InputActionMapper.map(
            gesture = TapKind.TAP,
            cell = cell,
            cellState = CellState.HIDDEN,
            binding = InputBinding.TAP_REVEAL_LONG_PRESS_FLAG,
        )
        val longPressAction = InputActionMapper.map(
            gesture = TapKind.LONG_PRESS,
            cell = cell,
            cellState = CellState.HIDDEN,
            binding = InputBinding.TAP_REVEAL_LONG_PRESS_FLAG,
        )

        assertEquals(GameAction.Reveal(cell), tapAction)
        assertEquals(GameAction.ToggleFlag(cell), longPressAction)
    }

    @Test
    fun tapOnRevealedCell_alwaysChords_regardlessOfBinding() {
        for (binding in InputBinding.entries) {
            val action = InputActionMapper.map(
                gesture = TapKind.TAP,
                cell = cell,
                cellState = CellState.REVEALED,
                binding = binding,
            )
            assertEquals(GameAction.Chord(cell), action)
        }
    }

    @Test
    fun longPressOnRevealedCell_hasNoAction() {
        for (binding in InputBinding.entries) {
            assertNull(
                InputActionMapper.map(
                    gesture = TapKind.LONG_PRESS,
                    cell = cell,
                    cellState = CellState.REVEALED,
                    binding = binding,
                ),
            )
        }
    }

    @Test
    fun revealGestureOnFlaggedCell_isBlocked() {
        val action = InputActionMapper.map(
            gesture = TapKind.TAP,
            cell = cell,
            cellState = CellState.FLAGGED,
            binding = InputBinding.TAP_REVEAL_LONG_PRESS_FLAG,
        )
        assertNull(action)
    }

    @Test
    fun flagGestureOnFlaggedCell_togglesItOff() {
        val action = InputActionMapper.map(
            gesture = TapKind.LONG_PRESS,
            cell = cell,
            cellState = CellState.FLAGGED,
            binding = InputBinding.TAP_REVEAL_LONG_PRESS_FLAG,
        )
        assertEquals(GameAction.ToggleFlag(cell), action)
    }

    @Test
    fun explodedCell_neverProducesAnAction() {
        for (gesture in TapKind.entries) {
            for (binding in InputBinding.entries) {
                assertNull(
                    InputActionMapper.map(
                        gesture = gesture,
                        cell = cell,
                        cellState = CellState.EXPLODED,
                        binding = binding,
                    ),
                )
            }
        }
    }
}
