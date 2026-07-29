package com.infinite.minesweeper.ui.settings

/**
 * The single v1 settings toggle (plan §6): which physical gesture reveals versus flags a cell.
 *
 * Chording is unaffected by this toggle — tapping an already-revealed cell always chords,
 * regardless of binding. See [InputActionMapper].
 */
enum class InputBinding {
    TAP_REVEAL_LONG_PRESS_FLAG,
    TAP_FLAG_LONG_PRESS_REVEAL,
    ;

    companion object {
        val Default: InputBinding = TAP_FLAG_LONG_PRESS_REVEAL
    }
}
