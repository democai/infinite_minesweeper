# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An infinite-board Minesweeper for Android (Kotlin, Jetpack Compose, min/target/compile SDK 35). Pure minesweeper — no currency, boosters, XP, or collectibles. Two design docs at the repo root are the source of truth for intended behavior and should be consulted for anything not obvious from the code:

- `Infinite minesweeper plan.md` — the full functional spec (coordinates/chunking, generation, the lock-and-wipe fail mechanic, input bindings, HUD, rendering/LOD, memory management, persistence).
- `Infinite minesweeper dev tree.md` — the task breakdown (T0–T13) with dependency graph and per-task file ownership; useful for understanding why the package layout is split the way it is.

## Commands

Use the `justfile` recipes (wrap `./gradlew`):

```
just build   # assembleDebug
just test    # ./gradlew test (JVM unit tests, includes Robolectric)
just lint    # lintDebug
just check   # test + lintDebug
just clean   # ./gradlew clean
just verify  # clean + test + build — the full gate
```

Run a single test class or method directly with Gradle when iterating:

```
./gradlew test --tests "com.infinite.minesweeper.core.engine.DefaultGameEngineTest"
./gradlew test --tests "com.infinite.minesweeper.core.engine.DefaultGameEngineTest.someTestMethod"
```

No `.env` variables are required (`justfile` sets `dotenv-load := false`).

## Architecture

MVVM with unidirectional data flow. `GameViewModel` exposes state derived from `GameEngine.state: StateFlow<GameState>`; Compose UI is stateless renderers driven by that flow plus a `BoardCanvas` for board drawing (no XML views, gestures via `detectTapGestures`/`detectTransformGestures`). DI is Hilt. Persistence is Room with a debounced write-behind queue; preferences (input binding) live in DataStore, not Room.

### Package layout and the contracts they implement

- `core/model/` — frozen, persistence- and UI-independent contracts: `GameEngine`, `ChunkRepository`, `MineGenerator` interfaces; `GameState`, `GameMeta`, `Chunk`, `Cell`, `ChunkCoord`/`CellCoord`, `GameAction`/`GameEvent` sealed types. **Read `core/model/CONTRACTS.md` before touching threading assumptions** — it documents exactly which calls are main-safe vs background-only for each interface. In short: `GameEngine.dispatch` is main-safe but must internally push generation/flood-fill to a background dispatcher; every `ChunkRepository` method is main-safe and owns its own dispatcher switching; `MineGenerator.generateForFirstTouch`/`ensureNeighborsGenerated`/`reroll` are background-only.
- `core/coords/` — pure coordinate math (`floorDiv`/`floorMod` cell↔chunk conversion; negative coordinates need `floorMod`, not `%`) and world↔screen transforms.
- `core/codec/` — packs/unpacks the 64-byte per-chunk cell blob (2-bit state, 1-bit mine, 4-bit adjacency, 1 spare bit per cell).
- `core/generation/` — `SeededMineGenerator`: deterministic, seed-based generation. Density is an independent hash of `(worldSeed, chunkCoord)` mapped uniformly into `[0.156, 0.35]` (easy/hard mix per selector, no distance ramp). First-touch-safe (excludes the tapped cell and its 8 neighbors) and generates lazy neighbors so adjacency is correct without deferred bookkeeping; `ensureNeighborsGenerated` expands the neighbor ring before reveals so border numbers stay stable.
- `core/engine/` — `DefaultGameEngine`: reveal, flag, chording, breadth-first flood-fill on a background dispatcher (hard-capped at 16-chunk radius, batched diffs to the UI). `core/engine/lock/LockAndWipeMechanic` implements the game's signature mechanic (see below).
- `core/cache/` — in-memory `ChunkCache` keyed by `(cx, cy)`, LRU-evicted around a ~512-chunk bound with a 3-chunk retention margin beyond the viewport; dirty chunks flush to the repository before eviction.
- `data/db/` — Room: `ChunkEntity`/`GameMetaEntity`, DAOs, `MinesweeperDatabase`, `RoomChunkRepository` (the `ChunkRepository` impl — debounced ~500ms write-behind, explicit `flush()`, `getLockedChunks()` for cold-start surround rechecks, `clearAll()` for full reset).
- `data/persistence/` — binds engine state to the repository: dirty-chunk tracking, `game_meta` save/restore, viewport-only hydration on cold start (never a full-table load).
- `ui/board/` — `BoardCanvas`/`CellDrawer` (full-detail rendering), `ViewportState` (pan/zoom/culling), `LodRenderer` (see below).
- `ui/hud/`, `ui/settings/`, `ui/theme/`, `ui/game/` (`GameScreen`/`GameViewModel`) — standard Compose layers wired via Hilt (`ui/game/GameModule.kt`).

### The lock-and-wipe mechanic (the game's core rule, plan §5)

Revealing a mine locks its chunk (status `LOCKED`, input disabled, red overlay) rather than ending the game. A locked chunk resolves once all 8 neighbor chunks are fully cleared (a locked neighbor never counts as cleared — cascading locks are intended). **First resolution is soft**: the mine is removed, the cell becomes a number, neighbor adjacency is patched, `everSurrounded` becomes `true`. **Any later mine hit in a chunk with `everSurrounded == true` is a hard wipe**: all 64 cells go back to hidden, the chunk is re-rolled (same generator, deterministic), `everSurrounded` resets to `false`, `selectorsWiped` increments. There is exactly one soft save per chunk, ever. Wipes must patch the 1-cell adjacency border in neighboring chunks whose numbers referenced the wiped chunk's old mines, while already-revealed neighbor cells keep their revealed state and just update their displayed number.

### Rendering LOD (plan §8)

Below a ~12dp/cell zoom threshold, `LodRenderer` stops drawing per-cell glyphs and bakes each chunk as an 8×8 `ImageBitmap` (one pixel per cell: black=hidden, grey=revealed, red=flagged — flags only, never actual mine locations). Completed chunks override to a flat grey rect and locked chunks to a flat red rect (required, not cosmetic — auto-flag-on-completion means a "cleared" chunk is full of flagged cells and would otherwise speckle). Bitmaps are baked once, cached with the chunk, and invalidated only when a cell in that chunk changes; they evict alongside their chunk from `ChunkCache`.

### Contract stability

`core/model/` is treated as a frozen interface boundary per the dev tree: other packages code against it, and changes there ripple everywhere, so treat edits to those files as higher-risk than edits elsewhere and re-check `CONTRACTS.md` stays accurate.
