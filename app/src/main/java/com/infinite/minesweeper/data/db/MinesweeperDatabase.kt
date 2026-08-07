package com.infinite.minesweeper.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ChunkEntity::class, GameMetaEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MinesweeperDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao

    abstract fun gameMetaDao(): GameMetaDao
}
