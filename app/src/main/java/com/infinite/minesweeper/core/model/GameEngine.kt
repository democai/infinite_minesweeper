package com.infinite.minesweeper.core.model

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface GameEngine {
    val state: StateFlow<GameState>

    /**
     * Discrete transitions used by mechanics and animations that must not be inferred from
     * conflated state snapshots.
     */
    val events: SharedFlow<GameEvent>

    /**
     * Applies a player action. Implementations perform expensive generation and flood-fill work
     * away from the caller's dispatcher and return after the action has been fully applied.
     */
    suspend fun dispatch(action: GameAction)
}
