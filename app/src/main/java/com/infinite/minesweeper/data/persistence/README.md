# T10: persistence wiring

Binds a live `GameState` stream to T3's `ChunkRepository`: `ViewportSnapshot` decouples this
package from the Compose-observable `ui.board.ViewportState`, `GamePersistenceCoordinator` tracks
which chunks are actually dirty and merges viewport/zoom into `GameMeta` before saving, and
`restoreGameState` hydrates a cold start from only a bounded window of chunks around the saved
viewport rather than the whole table. Room schema details remain in `data/db`.

`GamePersistenceCoordinator` is sourced from a plain `StateFlow<GameState>` rather than
`GameEngine` directly because T9's `LockAndWipeMechanic` mutates state outside the engine's own
flow; the integration layer (T13) decides which flow is authoritative for a session and passes
that in. Callers invoke `flush()` from lifecycle `onStop`/process-death hooks.
