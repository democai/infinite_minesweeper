package com.infinite.minesweeper.core.generation

/**
 * Single world seed for [SeededMineGenerator] and save-file identity.
 * Export embeds this value; import rejects files built for a different seed.
 */
const val WORLD_SEED: Long = 0x49_4E_46_4D_49_4E_45L
