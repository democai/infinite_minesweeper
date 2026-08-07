# Infinite Minesweeper

An infinite-board Minesweeper for Android (Kotlin, Jetpack Compose). Pure minesweeper, with no currency, boosters, or collectibles. Hitting a mine locks a selector instead of ending the game; clear its neighbors to recover once, then a second hit there wipes the selector and rerolls its interior.

Requires **Android 15 (API 35)** and [just](https://github.com/casey/just).

## Build

```bash
just build    # debug APK (assembleDebug)
just apk      # sideloadable release APK (debug-signed; auto-bumps versionCode)
just test     # JVM unit tests
just lint     # Android lint
just check    # test + lint
just verify   # clean + test + build
```

Or use Gradle directly: `./gradlew assembleDebug`. Open the project in Android Studio and run on a device/emulator (API 35+).

No `.env` file is required.

## Gameplay

The board is unbounded. Cells live in **8×8 selectors**. Pinch to zoom, drag to pan. The HUD shows viewport coordinates, flags placed, and cleared/wiped selector counts.

| Action | Default | Alternate (settings) |
|---|---|---|
| Reveal | Long-press | Tap |
| Flag | Tap | Long-press |

Long-press duration defaults to **Medium** (400 ms); Short and Long are available in settings.

**Basics**

- Numbers show how many mines touch that cell (including across selector borders).
- Tap a revealed number whose flags match its count to **chord** — reveal the remaining neighbors.
- When every safe cell in a selector is revealed, its remaining mines auto-flag.

**Lock and wipe**

1. Reveal a mine → that selector **locks** (red overlay, no input). The game continues elsewhere.
2. Clear all **8 neighbor selectors** → first recovery is soft: the mine is removed and the selector unlocks.
3. Hit a mine again in a selector that has already been surrounded → **hard wipe**: every cell becomes hidden, perimeter mines stay fixed, and the 6×6 interior is re-rolled.

Progress is saved automatically; settings hold the tap/long-press binding and long-press duration.
