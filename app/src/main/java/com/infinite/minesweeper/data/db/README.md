# T3: Room data layer

Room entities, DAOs, type converters, `MinesweeperDatabase`, Hilt `DatabaseModule`,
and `RoomChunkRepository` (500 ms coalescing write-behind queue + `flush`).

Maps `Chunk` / `GameMeta` ↔ entities via `ChunkMapper` and `core.codec.ChunkBlobCodec`.
Reads prefer queued (dirty) values so callers observe their own writes before debounce fires.
