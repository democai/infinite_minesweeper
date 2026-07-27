# T3: Room data layer

Room entities, DAOs, type converters, `MinesweeperDatabase`, Hilt `DatabaseModule`,
and `RoomChunkRepository` (500 ms write-behind queue + `flush`).

Maps `Chunk` / `GameMeta` ↔ entities via `ChunkMapper` and `core.codec.ChunkBlobCodec`.
