# T4: mine generation

`SeededMineGenerator` derives an independent random stream for each chunk, so generation order
does not consume shared random state. A first touch generates its 3×3 chunk neighborhood and keeps
the touched cell plus its eight cell-neighbors mine-free, including across chunk boundaries.

`mineDensityForChunk` implements the Chebyshev-distance density curve. `recomputeAdjacency`
calculates numbers across chunk boundaries and is also used to patch already-generated neighbors
when the generated frontier expands or a chunk is re-rolled.
