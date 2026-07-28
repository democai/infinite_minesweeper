# Infinite Minesweeper: Development Tree for Claude Code

Companion to `infinite-minesweeper-plan-v2.md`. Structured so an orchestrating Claude Code session can dispatch subagents on independent branches. Rules for the orchestrator:

- A task may start only when every ID in its **Deps** column is merged and its tests pass.
- Tasks in the same tier with no shared **Owns** files can run as parallel subagents. File ownership is disjoint by design; a subagent never edits a file it does not own, and cross-module needs go through the interfaces defined in T1.
- Every task's Definition of Done includes compiling, its listed tests passing, and `./gradlew test` staying green.
- Logic-heavy tasks (T2, T3, T4, T8, T9, T10) are JVM-testable with no emulator; UI tasks (T6, T7, T11, T12) need a device or emulator for final verification but should still keep viewport math in testable plain classes.

## Dependency graph

```
T0 scaffold
└── T1 contracts (interfaces + data classes)
    ├── T2 coord math + blob codec ──┐
    ├── T3 Room layer ───────────────┤
    ├── T5 theme/palette constants ──┤
    │                                ├── T4 generation engine (needs T2)
    │                                ├── T6 static renderer (needs T2, T5)
    │                                └── T8 game engine: reveal/flood/flag/chord (needs T2, T4)
    ├── T7 viewport: pan/zoom/cull/cache (needs T2, T6)
    ├── T9 lock/wipe mechanic (needs T8)
    ├── T10 persistence wiring (needs T3, T8)
    ├── T11 HUD + settings (needs T5, T8, T10)
    └── T12 LOD bitmap renderer (needs T6, T7, T9)
        └── T13 integration + polish (needs all)
```

Parallelism windows: {T2, T3, T5} together, then {T4, T6} together, then {T7, T8} together, then {T9, T10} together, then {T11, T12} together.

---

## Tier 0: Foundation (sequential, single agent)

### T0. Project scaffold
- **Deps:** none
- **Owns:** Gradle files, `AndroidManifest.xml`, app module skeleton, CI config if any
- **Do:** New Compose project, Kotlin, minSdk 35, Hilt + Room + DataStore dependencies, empty `MainActivity` hosting a placeholder composable, `./gradlew test` and `assembleDebug` green.
- **Done when:** app installs and shows a blank dark screen.

### T1. Shared contracts
- **Deps:** T0
- **Owns:** `core/model/` only: `CellState`, `ChunkStatus`, `ChunkCoord`, `CellCoord`, `GameState`, `GameAction` sealed class, `ChunkRepository` interface, `GameEngine` interface, `MineGenerator` interface
- **Do:** Data classes and interfaces exactly matching plan §3 plus engine-facing interfaces. No implementations. This file set is frozen after merge; any later change to it requires an orchestrator decision, because every subagent codes against it.
- **Done when:** compiles; a short `CONTRACTS.md` documents each interface's threading expectations (which calls are main-safe vs background-only).

## Tier 1: Parallel foundations (3 subagents)

### T2. Coordinate math + blob codec
- **Deps:** T1 | **Owns:** `core/coords/`, `core/codec/`
- **Do:** `cellToChunk` via `floorDiv`, `Math.floorMod` local indices, world↔screen transforms as pure functions; pack/unpack 64-byte chunk blob (2-bit state, 1-bit mine, 4-bit adjacency).
- **Tests:** negative-coordinate edges (x = -1, -8, -9), blob round-trip property test over random chunks, transform inverse identity.

### T3. Room layer
- **Deps:** T1 | **Owns:** `data/db/`
- **Do:** `ChunkEntity`, `GameMetaEntity`, DAOs, database class, `ChunkRepository` implementation over the DAO with a debounced (500 ms) write-behind queue and flush-on-demand.
- **Tests:** Robolectric or in-memory Room: upsert/read chunk, meta round-trip, debounce coalesces N rapid writes into one, explicit flush drains the queue.

### T5. Theme + palette
- **Deps:** T1 | **Owns:** `ui/theme/`
- **Do:** Dark theme, gold-on-dark number palette (distinct colors for 1–8), LOD colors (black/grey/red per plan §8), cell size and zoom-threshold constants, typography for the HUD.
- **Tests:** none beyond compilation; produce a single preview composable showing all cell states as a visual reference for T6.

## Tier 2: Engines and first pixels (2 subagents)

### T4. Mine generation engine
- **Deps:** T1, T2 | **Owns:** `core/generation/`
- **Do:** Seeded `MineGenerator`: seed-hashed per-chunk density (uniform in `[0.156, 0.35]`), first-touch-safe exclusion zone, lazy-neighbor generation, `ensureNeighborsGenerated` for stable border numbers, cross-chunk adjacency computation.
- **Tests:** fixed-seed determinism, exclusion zone never contains a mine, density in-band and seed-deterministic across coords, adjacency correctness on hand-built 3×3-chunk fixtures including chunk-boundary cells.

### T6. Static renderer
- **Deps:** T1, T2, T5 | **Owns:** `ui/board/BoardCanvas.kt`, `ui/board/CellDrawer.kt`
- **Do:** Canvas composable drawing a hardcoded set of chunks at full detail: hidden, numbers 0–8, flags, exploded mine, locked overlay. One shared `TextMeasurer`, zero per-frame allocations. No gestures yet.
- **Done when:** side-by-side visual check against the reference screenshot's style passes on emulator.

## Tier 3: Interaction (2 subagents)

### T7. Viewport: pan, zoom, culling, cache
- **Deps:** T2, T6 | **Owns:** `ui/board/ViewportState.kt`, `core/cache/`
- **Do:** `detectTransformGestures` pan + pinch with zoom clamps, visible-chunk computation with 1-chunk render margin, in-memory chunk cache with 3-chunk retention margin and ~512-chunk LRU eviction, dirty-flush-before-evict hook into `ChunkRepository`.
- **Tests:** JVM tests on viewport math (visible set at various zoom/offset), LRU eviction order, dirty chunk flushed before eviction.

### T8. Game engine: reveal, flood-fill, flag, chord
- **Deps:** T2, T4 | **Owns:** `core/engine/`
- **Do:** `GameEngine` implementation: reveal, flag toggle, chording (flag-count match reveals hidden neighbors, cross-chunk allowed), breadth-first flood-fill on a background dispatcher with 16-chunk radius cap and batched diff emission, auto-flag on chunk completion, chunk-cleared transition event stream (consumed later by T9).
- **Tests:** the densest suite in the project. Flood-fill on seeded boards (bounded, correct frontier), cascade crossing into ungenerated chunks triggers generation, chord with correct flags reveals exactly the unflagged neighbors, chord with a wrong flag reveals the mine, auto-flag fires exactly on last non-mine reveal, cap radius honored.

## Tier 4: The custom rules (2 subagents)

### T9. Lock and wipe mechanic
- **Deps:** T8 | **Owns:** `core/engine/lock/`
- **Do:** Plan §5 in full: lock on mine hit, input freeze per locked chunk, neighbor-cleared watcher on T8's transition stream, surrounded detection, first soft resolve (mine removal + adjacency patch), `everSurrounded` flag, hard wipe on any later mine hit (re-roll, reset flag, patch the 1-cell adjacency border in neighboring chunks, increment `selectorsWiped`).
- **Tests:** cascading adjacent locks resolve independently, soft resolve patches neighbor numbers correctly, wipe re-rolls with same-seed determinism, wiped chunk's neighbor border numbers recomputed, revealed neighbor cells keep revealed state with updated numbers, locked neighbor never counts as cleared.

### T10. Persistence wiring
- **Deps:** T3, T8 | **Owns:** `data/persistence/`
- **Do:** Bind engine state to the repository: dirty-chunk tracking, meta save (viewport, zoom, counters), restore-on-launch hydrating only viewport chunks, flush on `onStop`.
- **Tests:** simulate process death mid-session (write, new repository instance, read) and verify full state equality; cold-start restores viewport without loading untouched chunks.

## Tier 5: Surface (2 subagents)

### T11. HUD + settings
- **Deps:** T5, T8, T10 | **Owns:** `ui/hud/`, `ui/settings/`
- **Do:** Top bar per plan §7 (coordinates readout, flags placed, selectors cleared/locked/wiped), DataStore-backed settings screen with the single tap/long-press binding toggle, tap and long-press dispatch into `GameEngine` respecting the toggle.
- **Tests:** counter flows update on engine events; binding toggle swaps dispatch (JVM test on the input mapper).

### T12. LOD bitmap renderer
- **Deps:** T6, T7, T9 | **Owns:** `ui/board/LodRenderer.kt`
- **Do:** Plan §8 final palette: below the ~12 dp/cell threshold, per-chunk 8×8 `ImageBitmap` (black hidden, grey revealed, red flagged; never mines), flat-grey override for completed chunks, flat-red override for locked, nearest-neighbor scaling, bake-once with invalidation on cell change, bitmap evicts with its chunk.
- **Tests:** JVM test of the bake function's pixel output for fixture chunks including the two overrides; verify a completed auto-flagged chunk bakes solid grey, not speckled.

## Tier 6: Integration (single agent)

### T13. Integration, polish, device pass
- **Deps:** all | **Owns:** anything, with orchestrator awareness
- **Do:** Wire ViewModel end to end, lock/unlock/wipe animations (brief flash on wipe), number color pass, zoom-threshold tuning on device, kill-and-restore manual test, sustained-pan memory check (cache stays bounded), frame-time check at far zoom with 100+ visible chunks.
- **Done when:** a full play session (start, cascade, lock, surround, soft resolve, second hit, wipe, restart app, resume) works on device with no state loss.

---

## Orchestrator notes

- The riskiest merges are T9 into T8's engine and T12 into T7's draw loop; schedule those merges when no other subagent is mid-flight on adjacent files.
- If a subagent believes a T1 contract is wrong, it stops and reports rather than editing `core/model/`; the orchestrator amends contracts and re-briefs affected agents.
- Total: 14 tasks, maximum useful parallelism 3 (Tier 1), typical 2. More than 3 concurrent agents adds merge overhead without saving wall-clock time on a codebase this size.
