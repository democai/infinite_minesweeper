# T4: mine generation

`SeededMineGenerator` derives an independent random stream for each chunk, so generation order
does not consume shared random state. A first touch generates its 3×3 chunk neighborhood and keeps
the touched cell plus its eight cell-neighbors mine-free, including across chunk boundaries.

`mineDensityFor` hashes `(worldSeed, chunkCoord)` into a uniform density in `[0.156, 0.35]`, so
nearby selectors mix easy and hard rather than ramping with distance from the origin.
`ensureNeighborsGenerated` rolls missing neighbors of a playable chunk (empty exclusion) so border
numbers are final before cells are revealed. `recomputeAdjacency` calculates numbers across chunk
boundaries and is also used to patch already-generated neighbors when the generated frontier
expands or a chunk is re-rolled.
