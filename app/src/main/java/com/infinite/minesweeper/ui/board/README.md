# T6, T7, and T12: board surface

- T6 owns `BoardCanvas.kt` and `CellDrawer.kt`.
- T7 owns `ViewportState.kt` and adds pan, zoom, and culling to the canvas.
- T12 owns `LodRenderer.kt`.

Keep reusable cache logic in `core/cache`, not in this package.
