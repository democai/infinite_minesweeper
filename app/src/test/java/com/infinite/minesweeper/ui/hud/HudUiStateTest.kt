package com.infinite.minesweeper.ui.hud

import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameMeta
import com.infinite.minesweeper.core.model.GameState
import org.junit.Assert.assertEquals
import org.junit.Test

class HudUiStateTest {

    @Test
    fun formatSelectorFromHomeLabel_marksOriginAsHome() {
        assertEquals("SEL: Home", formatSelectorFromHomeLabel(ChunkCoord(0, 0)))
    }

    @Test
    fun formatSelectorFromHomeLabel_usesSignedOffsetsFromHome() {
        // Display CY is north-positive: internal cy=-1 (north of Home) → HUD +1.
        assertEquals("SEL: +2, +1", formatSelectorFromHomeLabel(ChunkCoord(2, -1)))
        assertEquals("SEL: -3, -4", formatSelectorFromHomeLabel(ChunkCoord(-3, 4)))
    }

    @Test
    fun toHudUiState_readsCountersAndSelectorFromViewport() {
        val state = GameState(
            meta = GameMeta(flagsPlaced = 3, selectorsCleared = 2),
        )

        // Cell (20, -10) sits in chunk (2, -2) — 8 cells per selector.
        // HUD negates Y: chunk cy -2 → SEL +2.
        val hud = state.toHudUiState(viewportCenterX = 20.0, viewportCenterY = -10.0)

        assertEquals("SEL: +2, +2", hud.selectorLabel)
        assertEquals(3, hud.flagsPlaced)
        assertEquals(2, hud.selectorsCleared)
        assertEquals(0, hud.selectorsLocked)
    }

    @Test
    fun toHudUiState_countsLockedChunksLive() {
        val chunks = mapOf(
            ChunkCoord(0, 0) to Chunk(coord = ChunkCoord(0, 0), status = ChunkStatus.LOCKED),
            ChunkCoord(1, 0) to Chunk(coord = ChunkCoord(1, 0), status = ChunkStatus.LOCKED),
            ChunkCoord(0, 1) to Chunk(coord = ChunkCoord(0, 1), status = ChunkStatus.NORMAL),
        )

        val hud = GameState(chunks = chunks).toHudUiState(0.0, 0.0)

        assertEquals(2, hud.selectorsLocked)
        assertEquals("SEL: Home", hud.selectorLabel)
    }

    @Test
    fun toHudUiState_updatesAcrossSuccessiveEngineTransitions() {
        // Simulates the sequence of GameState emissions an engine produces as events fire:
        // a reveal places a flag, then a mine hit locks a chunk, then it resolves.
        var state = GameState()
        val afterFlag = state.copy(meta = state.meta.copy(flagsPlaced = 1)).also { state = it }
        val afterLock = state.copy(
            chunks = mapOf(ChunkCoord(0, 0) to Chunk(coord = ChunkCoord(0, 0), status = ChunkStatus.LOCKED)),
        ).also { state = it }
        val afterResolve = state.copy(
            chunks = mapOf(ChunkCoord(0, 0) to Chunk(coord = ChunkCoord(0, 0), status = ChunkStatus.NORMAL)),
            meta = state.meta.copy(selectorsCleared = 1),
        ).also { state = it }

        assertEquals(1, afterFlag.toHudUiState(0.0, 0.0).flagsPlaced)
        assertEquals(1, afterLock.toHudUiState(0.0, 0.0).selectorsLocked)
        assertEquals(0, afterResolve.toHudUiState(0.0, 0.0).selectorsLocked)
        assertEquals(1, afterResolve.toHudUiState(0.0, 0.0).selectorsCleared)
    }
}
