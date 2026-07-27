# T5: theme and palette

Owns colors, dimensions, HUD typography, and the cell-state visual reference.

| File | Contents |
|---|---|
| `Color.kt` | `BoardPalette`, `NumberPalette` (1–8), `LodPalette` (plan §8) |
| `Dimens.kt` | Base cell size, LOD threshold (~12 dp), zoom clamps |
| `Type.kt` | `HudTypography` + digit size fraction for Canvas |
| `Theme.kt` | Dark Material3 theme wiring gold accent + HUD type |
| `CellStatePreview.kt` | `@Preview` reference sheet for T6 (all cell states + LOD) |

T6 should read constants from these objects — do not hard-code colors in the board drawer.
