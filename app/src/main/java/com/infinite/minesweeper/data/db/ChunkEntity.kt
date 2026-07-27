package com.infinite.minesweeper.data.db

import androidx.room.Entity
import com.infinite.minesweeper.core.model.ChunkStatus

@Entity(tableName = "chunks", primaryKeys = ["cx", "cy"])
data class ChunkEntity(
    val cx: Int,
    val cy: Int,
    val generated: Boolean,
    val cellsBlob: ByteArray,
    val status: ChunkStatus,
    val everSurrounded: Boolean,
    val lockedAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkEntity) return false
        return cx == other.cx &&
            cy == other.cy &&
            generated == other.generated &&
            cellsBlob.contentEquals(other.cellsBlob) &&
            status == other.status &&
            everSurrounded == other.everSurrounded &&
            lockedAt == other.lockedAt
    }

    override fun hashCode(): Int {
        var result = cx
        result = 31 * result + cy
        result = 31 * result + generated.hashCode()
        result = 31 * result + cellsBlob.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + everSurrounded.hashCode()
        result = 31 * result + (lockedAt?.hashCode() ?: 0)
        return result
    }
}
