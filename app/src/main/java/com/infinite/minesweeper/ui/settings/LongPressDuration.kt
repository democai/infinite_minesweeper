package com.infinite.minesweeper.ui.settings

/**
 * How long the finger must stay down before a long-press gesture fires.
 *
 * Android's platform default is typically ~500 ms; [SHORT] is the snappier option most players
 * want when flagging frequently.
 */
enum class LongPressDuration(val timeoutMs: Long) {
    SHORT(220L),
    MEDIUM(400L),
    LONG(600L),
    ;

    companion object {
        val Default: LongPressDuration = SHORT
    }
}
