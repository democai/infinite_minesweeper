# Infinite Minesweeper

An infinite-board Minesweeper for Android (Kotlin, Jetpack Compose). Pure minesweeper — no currency, boosters, or collectibles. Hitting a mine locks a chunk instead of ending the game; clear its neighbors to recover (once), then a second hit there wipes and re-rolls that chunk.

Requires **Android 15 (API 35)** and [just](https://github.com/casey/just).

## Build

```bash
just build    # debug APK (assembleDebug)
just apk      # sideloadable release APK (debug-signed)
just test     # JVM unit tests
just lint     # Android lint
just check    # test + lint
just verify   # clean + test + build
```

Or use Gradle directly: `./gradlew assembleDebug`. Open the project in Android Studio and run on a device/emulator (API 35+).

No `.env` file is required.

## Gameplay

The board is unbounded. Cells live in **8×8 chunks** ("selectors"). Pinch to zoom, drag to pan. The HUD shows viewport coordinates, flags placed, and cleared/wiped chunk counts.

| Action | Default | Alternate (settings) |
|---|---|---|
| Reveal | Tap | Long-press |
| Flag | Long-press | Tap |

**Basics**

- Numbers show how many mines touch that cell (including across chunk borders).
- Tap a revealed number whose flags match its count to **chord** — reveal the remaining neighbors.
- When a chunk is fully cleared, remaining mines auto-flag.

**Lock and wipe**

1. Reveal a mine → that chunk **locks** (red overlay, no input). The game continues elsewhere.
2. Clear all **8 neighbor chunks** → first recovery is soft: the mine is removed and the chunk unlocks.
3. Hit a mine again in a chunk that has already been unlocked this way → **hard wipe**: the whole chunk resets and is re-rolled. One soft save per chunk, ever.

Progress is saved automatically; settings hold the tap/long-press binding preference.
