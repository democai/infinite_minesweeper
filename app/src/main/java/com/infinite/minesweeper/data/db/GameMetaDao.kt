package com.infinite.minesweeper.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface GameMetaDao {
    @Query("SELECT * FROM game_meta WHERE id = 0 LIMIT 1")
    suspend fun getMeta(): GameMetaEntity?

    @Upsert
    suspend fun upsert(meta: GameMetaEntity)
}
