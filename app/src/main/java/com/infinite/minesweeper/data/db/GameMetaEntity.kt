package com.infinite.minesweeper.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_meta")
data class GameMetaEntity(
    @PrimaryKey val id: Int = 0,
    val flagsPlaced: Int,
    val selectorsCleared: Int,
    val selectorsWiped: Int,
    val viewportX: Float,
    val viewportY: Float,
    val zoom: Float,
)
