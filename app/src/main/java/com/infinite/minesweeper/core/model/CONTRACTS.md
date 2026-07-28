# Core contracts

These types are persistence- and UI-independent. Room entities map to `Chunk` and `GameMeta`;
renderers consume immutable `GameState` snapshots.

## Threading

- `GameEngine.state` and `GameEngine.events` may be collected on the main thread.
- `GameEngine.dispatch` is main-safe. Implementations must move mine generation, flood-fill, and
  other expensive work to a background dispatcher before doing it.
- Every `ChunkRepository` function is main-safe. Its implementation owns database dispatcher
  switching. `saveChunk`, `saveChunks`, and `saveGameMeta` enqueue/coalesce writes; `flush` waits
  until all writes queued before the call are durable. `getLockedChunks` returns every
  currently-locked selector for cold-start surround rechecks.
- `MineGenerator.mineDensityFor` is a small, pure calculation and is main-safe.
- `MineGenerator.generateForFirstTouch`, `MineGenerator.ensureNeighborsGenerated`, and
  `MineGenerator.reroll` are background-only. The engine is responsible for calling them from a
  background dispatcher. Generator implementations are deterministic for the same configured seed
  and equivalent inputs.

## Ownership and mutability

- `Chunk`, `Cell`, `GameMeta`, and `GameState` are immutable values. Implementations publish new
  values rather than mutating lists or maps retained by callers.
- `GameState.chunks` is the hydrated cache/window, not every saved chunk.
- A `GenerationResult` contains every chunk changed by generation, including adjacency-only
  changes to neighbors. Callers must merge the full result.
- `GameEngine.events` carries one-shot transitions. Long-lived truth always lives in `state`.
