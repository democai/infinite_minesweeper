# Development layout

This tree mirrors the ownership boundaries in `Infinite minesweeper dev tree.md`.
Code shared across tasks must communicate through the frozen contracts in `core/model`.

| Task | Production package | Expected responsibility |
|---|---|---|
| T2 | `core/coords` | Cell/chunk coordinate math and world/screen transforms |
| T2 | `core/codec` | Packed 64-byte chunk blob codec |
| T3 | `data/db` | Room entities, DAOs, database, and repository implementation |
| T4 | `core/generation` | Seeded mine generation and adjacency calculation |
| T5 | `ui/theme` | Theme, palette, dimensions, typography, and visual reference preview |
| T6 | `ui/board` | `BoardCanvas.kt` and `CellDrawer.kt` |
| T7 | `ui/board` | `ViewportState.kt` and gesture/culling integration |
| T7 | `core/cache` | Bounded chunk and LOD-bitmap cache |
| T8 | `core/engine` | Reveal, flood-fill, flag, chord, and completion engine |
| T9 | `core/engine/lock` | Lock, surrounded resolution, and wipe rules |
| T10 | `data/persistence` | Engine/repository binding, restore, dirty tracking, lifecycle flush |
| T11 | `ui/hud` | HUD composables and counter presentation |
| T11 | `ui/settings` | Binding preference storage, settings UI, and input mapping |
| T12 | `ui/board` | `LodRenderer.kt` |
| T13 | `ui/game` and app root | `GameViewModel`, end-to-end wiring, and integration polish |

JVM tests mirror these packages beneath
`app/src/test/java/com/infinite/minesweeper`. Device/Compose tests belong beneath
`app/src/androidTest/java/com/infinite/minesweeper`.

Do not place Room annotations, Compose types, Android lifecycle types, or implementation-specific
mutable state in `core/model`.
