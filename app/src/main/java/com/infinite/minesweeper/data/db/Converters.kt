package com.infinite.minesweeper.data.db

import androidx.room.TypeConverter
import com.infinite.minesweeper.core.model.ChunkStatus

class Converters {
    @TypeConverter
    fun fromChunkStatus(status: ChunkStatus): String = status.name

    @TypeConverter
    fun toChunkStatus(value: String): ChunkStatus = ChunkStatus.valueOf(value)
}
