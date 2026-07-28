# Infinite Minesweeper: Build Plan v2

Revision of the original plan after design interview. Three decisions locked in:

| Decision | Answer |
|---|---|
| Meta-layer (currency, boosters, XP, collectibles) | None. Pure minesweeper. |
| Hard reset trigger | Any mine hit in a selector that has ever reached "surrounded" status wipes and re-rolls that selector. |
| v1 quality-of-life features | Chording, configurable tap vs long-press bindings, auto-flag on chunk completion. |

The screenshot serves as a visual/layout reference only (dark theme, gold numbers, coordinate readout, top HUD bar). Its gems, level bar, purchase button, and lightning booster are explicitly out of scope.

---

## 1. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin | |
| UI | Jetpack Compose, single `Canvas` composable for the board | `detectTapGestures` + `detectTransformGestures` on the same pointer input; no XML views |
| Architecture | MVVM, unidirectional data flow | `GameViewModel` exposes `StateFlow<GameState>`; Composables are stateless renderers |
| Async | Coroutines + Flow | Generation, flood-fill continuation, and DB writes off the main thread |
| Persistence | Room (SQLite) | One row per touched chunk, packed blob |
| DI | Hilt | Optional; wire Repository/DAO by hand if you prefer fewer dependencies |
| Min SDK | 35 (Android 15) | Personal-use target, no compat shims |

---

## 2. Coordinates and Chunks

- Cell coordinates: global integer `(x, y)`, unbounded, matching the `X: -76 Y: 59` readout style.
- Chunk ("selector") coordinates: `(cx, cy) = (floorDiv(x, 8), floorDiv(y, 8))`; each chunk is 8×8 cells.
- Local index inside a chunk: `Math.floorMod(x, 8)` and `Math.floorMod(y, 8)` (plain `%` breaks on negatives in Kotlin).

## 3. Data Model (Room)

```kotlin
@Entity(tableName = "chunks", primaryKeys = ["cx", "cy"])
data class ChunkEntity(
    val cx: Int,
    val cy: Int,
    val generated: Boolean,          // mine layout rolled?
    val cellsBlob: ByteArray,        // 64 bytes, 1 byte per cell (see below)
    val status: ChunkStatus,         // NORMAL, LOCKED
    val everSurrounded: Boolean,     // has this chunk ever reached surrounded status?
    val lockedAt: Long? = null
)

enum class ChunkStatus { NORMAL, LOCKED }

@Entity(tableName = "game_meta")
data class GameMetaEntity(
    @PrimaryKey val id: Int = 0,
    val flagsPlaced: Int,
    val selectorsCleared: Int,
    val selectorsWiped: Int,         // hard-reset count, doubles as a difficulty stat
    val viewportX: Float,
    val viewportY: Float,
    val zoom: Float
)
```

Per-cell byte layout: 2 bits state (hidden / revealed / flagged), 1 bit mine, 4 bits adjacency (0–8), 1 bit spare. One packed row per chunk keeps a long session at thousands of rows instead of hundreds of thousands.

`everSurrounded` replaces the v1 `hasBeenSurroundedOnce` and is the single flag the hard-reset rule reads. It resets to `false` when the chunk is wiped and re-rolled.

## 4. Mine Generation

Unchanged from v1 in substance, restated for completeness:

- Reveal-time generation, first-touch-safe: a chunk's mines are rolled the first time a reveal touches it, excluding the tapped cell and its 8 neighbors.
- Lazy-neighbor generation: generating chunk `(cx, cy)` also rolls (without revealing) its 8 neighbors so adjacency counts are correct immediately. No deferred-adjacency bookkeeping.
- Density is a pure function of chunk coordinates: an independent hash of `(worldSeed, cx, cy)`
  mapped uniformly into `[0.156, 0.35]` so nearby selectors mix easy and hard. Base 15.6% matches
  classic intermediate (40 mines on 16×16); the 35% cap keeps the hardest selectors solvable. The
  curve is one function, swappable later without touching storage or generation order.

```kotlin
fun mineDensityFor(seed: Long, cx: Int, cy: Int): Float {
    val u = unitInterval(chunkSeed(seed, cx, cy, DENSITY_SALT)) // in [0, 1)
    return 0.156f + u.toFloat() * (0.35f - 0.156f)
}
```

Before revealing cells in a chunk, its full 8-neighbor ring must already be generated
(`ensureNeighborsGenerated`) so border numbers are final — missing neighbors must not count as
zero mines and then jump when the frontier expands.

### Flood-fill bounds (new in v2)

A 0-cascade can force-generate chunks without limit, and at 15.6% density large cascades happen often enough to matter. Rules:

1. Flood-fill runs on a background dispatcher as a breadth-first queue; the UI thread only receives batched cell-state diffs (e.g. per 64 cells or per 16 ms frame).
2. Hard cap the cascade at a radius of 16 chunks from the tapped cell (a 128×128-cell disk). Cells at the frontier stay hidden; tapping near them later resumes the fill naturally. In practice the cap almost never triggers at base density, but it bounds worst-case work and DB writes.
3. Chunks generated mid-cascade write to Room through the same debounced write-behind path as everything else, not synchronously per chunk.

## 5. Locked-Selector Fail Mechanic (final rules)

1. Revealing a mine locks that chunk: status `LOCKED`, all input in it disabled, dark/red overlay, exploded cell rendered as a mine.
2. A locked chunk resolves when all 8 neighbors are cleared (every non-mine cell revealed; flags irrelevant). A locked neighbor never counts as cleared, so adjacent locked chunks must each be surrounded independently. Cascading locks are intended behavior.
3. On first resolution: the exploded mine is removed, its cell becomes a revealed number, neighbor adjacency counts are patched, status returns to `NORMAL`, and `everSurrounded` is set `true`.
4. **Hard reset (interview decision):** any mine hit, at any later time, in a chunk with `everSurrounded == true` wipes the chunk. All 64 cells revert to hidden, a fresh layout is rolled under the same density function, `everSurrounded` resets to `false`, and `selectorsWiped` increments. There is no second soft resolution, ever. One soft save per chunk per lifetime; after that, mistakes there cost the whole selector.
5. Watcher: whenever a chunk transitions to fully-cleared, check its 8 neighbors for `LOCKED` status and re-evaluate each lock's surrounded condition. This is a cheap adjacency check, no global scan.

Edge case to handle in the wipe: cells in *neighboring* chunks whose adjacency numbers referenced the wiped chunk's old mines must be recomputed (up to a 1-cell border, 32 cells max). Already-revealed neighbor cells keep their revealed state but update their displayed number.

## 6. Input and Quality of Life (interview decisions)

- **Chording:** tapping a revealed number whose flagged-neighbor count equals its value reveals all remaining hidden neighbors. If a flag was wrong, the revealed mine triggers the normal lock (or wipe) rule for its chunk. Chords that span chunk boundaries are allowed and can lock a neighboring chunk.
- **Configurable bindings:** a settings toggle between `tap = reveal, long-press = flag` (default) and the inverse. Stored in `DataStore`, not Room, since it is a preference rather than game state.
- **Auto-flag on completion:** when a chunk's last non-mine cell is revealed, all remaining hidden cells in it (mines by definition) flip to flagged automatically and count toward `flagsPlaced`. This is also the moment the chunk counts as "cleared" for lock resolution, so auto-flag and the §5 watcher fire from the same transition.
- Long-press duration and chord behavior need no further options in v1; keep the settings screen to the one binding toggle.

## 7. HUD

Top bar, in the screenshot's visual style but with only these elements:

- Title / mode label
- Coordinate readout of the viewport center (`X: -76 Y: 59` format)
- Flags placed
- Selectors cleared
- Selectors currently locked (the "uh oh" indicator)
- Selectors wiped (lifetime hard-reset count)

No global "mines remaining" counter; it cannot exist on an infinite board. No score, gems, levels, or boosters.

## 8. Rendering

- Draw only chunks intersecting the viewport rectangle plus a 1-chunk margin.
- **Level-of-detail path (final palette):** below a zoom threshold where a cell is under ~12 dp, stop drawing per-cell numbers and glyphs entirely. Each chunk renders as a literal 8×8-pixel `ImageBitmap`, one pixel per cell, drawn scaled with nearest-neighbor filtering (`FilterQuality.None`) as a single `drawImage` call:
  - Per-cell colors: black = hidden, grey = revealed, red = flagged. Flags only, never actual mine locations; coloring hidden mines would let the player read the board from the overview.
  - Chunk-level overrides (checked before per-cell baking): a completed chunk draws as one flat grey rect, a locked chunk as one flat red rect. The completed override is required, not cosmetic: auto-flag on completion means finished chunks contain flagged cells and would otherwise render grey with red speckles instead of solid grey. The locked override stays unambiguous because a legitimate chunk can never be 100% flagged (it is never 100% mines).
  - Reading the far view: solid grey = conquered territory, solid red = locked failures, black/grey/red speckle = active frontier.
  - Bitmaps bake once per chunk, cache alongside the chunk, and invalidate only when a cell in that chunk changes.
  - You cannot meaningfully play at this zoom anyway; the far view exists as a pixel-art map of explored territory.
- One shared `TextMeasurer` and pre-built `Paint`/brush objects; zero allocations inside the draw pass.
- Cell states to render: hidden, revealed 0–8 (color-coded numbers), flagged, exploded mine, locked-overlay tint, wipe animation (brief flash on hard reset so the punishment reads clearly).

## 9. Memory Management (new in v2)

- In-memory chunk cache keyed by `(cx, cy)`, populated on viewport entry, deserialized from the packed blob.
- Eviction: chunks outside the viewport plus a 3-chunk margin become eviction candidates; evict least-recently-visible when the cache exceeds ~512 chunks (≈32 KB of cell data plus object overhead, comfortably small, but the bound prevents unbounded growth in a long panning session).
- Dirty chunks are flushed to Room before eviction; the write-behind queue guarantees ordering. LOD bitmaps evict with their chunk and are cheap to re-bake on re-entry (64 pixel writes).

## 10. Persistence

- Actions mutate in-memory state immediately; a debounced writer flushes dirty chunks and `game_meta` to Room every 500 ms or on lifecycle `onStop`, whichever comes first.
- On launch: read `game_meta`, restore viewport and zoom, hydrate only the chunks the restored viewport needs. No full-table load.

## 11. Build Order

1. Coordinate math, packed blob (de)serialization, Room schema. Unit tests for `floorMod` edges, blob round-trips, and density function values.
2. Generation: first-touch-safe roll, lazy neighbors, cross-chunk adjacency. Test with fixed RNG seeds.
3. Static renderer: fixed chunk set on Canvas, dark theme, both LOD levels.
4. Pan, zoom, culling, chunk cache with eviction.
5. Reveal, bounded async flood-fill, flag, chording, binding toggle.
6. Lock mechanic: lock, watcher, soft resolve, hard-reset wipe with neighbor-number patching, auto-flag on completion. This step gets the densest test coverage; the wipe's cross-chunk adjacency patch and the cascading-lock resolution order are the two likeliest sources of subtle bugs.
7. HUD counters and persistence wiring; kill-and-restore testing.
8. Polish: number color palette, lock/unlock/wipe animations, settings screen.

Steps 1, 2, and 6 are pure-logic and fully testable without a device; write those tests as you go rather than after.

## 12. Out of Scope for v1

Recorded so they do not creep back in: currency and purchases, boosters, XP or level bar, collectible pickups on the board, teleport button, minimap, cloud sync, sound. Any of these can be layered on later without schema changes except collectibles, which would need a per-chunk spare-bit or side table.
