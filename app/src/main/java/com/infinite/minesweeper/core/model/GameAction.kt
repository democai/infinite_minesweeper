package com.infinite.minesweeper.core.model

sealed interface GameAction {
    val cell: CellCoord

    data class Reveal(override val cell: CellCoord) : GameAction

    data class ToggleFlag(override val cell: CellCoord) : GameAction

    data class Chord(override val cell: CellCoord) : GameAction
}

sealed interface GameEvent {
    data class ChunkCleared(val chunk: ChunkCoord) : GameEvent

    data class ChunkLocked(
        val chunk: ChunkCoord,
        val explodedCell: CellCoord,
    ) : GameEvent

    data class ChunkSoftResolved(val chunk: ChunkCoord) : GameEvent

    data class ChunkWiped(val chunk: ChunkCoord) : GameEvent
}
