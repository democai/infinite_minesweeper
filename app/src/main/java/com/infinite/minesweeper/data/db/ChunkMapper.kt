package com.infinite.minesweeper.data.db

import com.infinite.minesweeper.core.codec.ChunkBlobCodec
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.GameMeta

internal object ChunkMapper {
    fun toEntity(chunk: Chunk): ChunkEntity =
        ChunkEntity(
            cx = chunk.coord.cx,
            cy = chunk.coord.cy,
            generated = chunk.generated,
            cellsBlob = ChunkBlobCodec.pack(chunk.cells),
            status = chunk.status,
            everSurrounded = chunk.everSurrounded,
            lockedAt = chunk.lockedAt,
        )

    fun toDomain(entity: ChunkEntity): Chunk =
        Chunk(
            coord = ChunkCoord(entity.cx, entity.cy),
            generated = entity.generated,
            cells = ChunkBlobCodec.unpack(entity.cellsBlob),
            status = entity.status,
            everSurrounded = entity.everSurrounded,
            lockedAt = entity.lockedAt,
        )

    fun toEntity(meta: GameMeta): GameMetaEntity =
        GameMetaEntity(
            id = 0,
            flagsPlaced = meta.flagsPlaced,
            selectorsCleared = meta.selectorsCleared,
            selectorsWiped = meta.selectorsWiped,
            viewportX = meta.viewportX,
            viewportY = meta.viewportY,
            zoom = meta.zoom,
            hasEverRevealed = meta.hasEverRevealed,
            hasExploredBounds = meta.hasExploredBounds,
            exploredMinCx = meta.exploredMinCx,
            exploredMaxCx = meta.exploredMaxCx,
            exploredMinCy = meta.exploredMinCy,
            exploredMaxCy = meta.exploredMaxCy,
        )

    fun toDomain(entity: GameMetaEntity): GameMeta =
        GameMeta(
            flagsPlaced = entity.flagsPlaced,
            selectorsCleared = entity.selectorsCleared,
            selectorsWiped = entity.selectorsWiped,
            viewportX = entity.viewportX,
            viewportY = entity.viewportY,
            zoom = entity.zoom,
            hasEverRevealed = entity.hasEverRevealed,
            hasExploredBounds = entity.hasExploredBounds,
            exploredMinCx = entity.exploredMinCx,
            exploredMaxCx = entity.exploredMaxCx,
            exploredMinCy = entity.exploredMinCy,
            exploredMaxCy = entity.exploredMaxCy,
        )
}
