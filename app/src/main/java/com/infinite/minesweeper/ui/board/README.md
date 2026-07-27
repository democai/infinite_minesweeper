# T6, T7, and T12: board surface

- T6 owns `BoardCanvas.kt` and `CellDrawer.kt`.
- T7 owns `ViewportState.kt` and adds pan, zoom, and culling to the canvas.
- T12 owns `LodRenderer.kt`: 8×8 LOD bake (hidden/revealed/flagged), completed/locked
  overrides, nearest-neighbor draw, and `ChunkCache<ImageBitmap>` obtain/invalidate.

Keep reusable cache logic in `core/cache`, not in this package. T13 wires LOD into the
viewport draw loop when cell size falls below `BoardDimens.LodThresholdDp`.
