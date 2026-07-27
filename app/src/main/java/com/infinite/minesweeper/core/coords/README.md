# T2: coordinate math

`ChunkCoordinates.kt` provides negative-safe global cell to chunk/local conversions. Local indices
are row-major (`index = y * 8 + x`), and the inverse helpers validate their ranges.

`WorldScreenTransform.kt` provides Android-free viewport transforms. `worldCenter` maps to the
center of the screen and `pixelsPerWorldUnit` controls scale. Both axes increase right/down to
match board coordinates and Android canvas coordinates.
