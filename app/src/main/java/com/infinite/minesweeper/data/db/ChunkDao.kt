package com.infinite.minesweeper.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.infinite.minesweeper.core.model.ChunkStatus

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE cx = :cx AND cy = :cy LIMIT 1")
    suspend fun getChunk(cx: Int, cy: Int): ChunkEntity?

    /**
     * Candidate rows for a coordinate set. Callers must filter to the exact `(cx, cy)` pairs
     * they requested — the IN clauses alone can include Cartesian extras.
     */
    @Query(
        """
        SELECT * FROM chunks
        WHERE cx IN (:cxs) AND cy IN (:cys)
        """,
    )
    suspend fun getChunksWhereCxCyIn(cxs: List<Int>, cys: List<Int>): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE status = :status")
    suspend fun getChunksByStatus(status: ChunkStatus): List<ChunkEntity>

    @Upsert
    suspend fun upsert(chunk: ChunkEntity)

    @Upsert
    suspend fun upsertAll(chunks: List<ChunkEntity>)

    @Query("DELETE FROM chunks")
    suspend fun deleteAll()
}
