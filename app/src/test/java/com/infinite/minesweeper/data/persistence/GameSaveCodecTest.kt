package com.infinite.minesweeper.data.persistence

import com.infinite.minesweeper.core.generation.WORLD_SEED
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameMeta
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSaveCodecTest {

    @Test
    fun roundTrip_preservesMetaAndChunks() {
        val chunkA = Chunk(
            coord = ChunkCoord(-2, 3),
            generated = true,
            cells = List(64) { index ->
                when (index) {
                    0 -> Cell(state = CellState.REVEALED, adjacentMines = 2)
                    1 -> Cell(state = CellState.FLAGGED, isMine = true)
                    else -> Cell()
                }
            },
            everSurrounded = true,
        )
        val chunkB = Chunk(
            coord = ChunkCoord(0, 0),
            generated = true,
            cells = List(64) { index ->
                if (index == 5) {
                    Cell(state = CellState.EXPLODED, isMine = true)
                } else {
                    Cell()
                }
            },
            status = ChunkStatus.LOCKED,
            lockedAt = 1_700_000_000L,
        )
        val meta = GameMeta(
            flagsPlaced = 7,
            selectorsCleared = 2,
            selectorsWiped = 1,
            viewportX = -12.5f,
            viewportY = 40f,
            zoom = 1.25f,
            hasEverRevealed = true,
            hasExploredBounds = true,
            exploredMinCx = -2,
            exploredMaxCx = 0,
            exploredMinCy = 0,
            exploredMaxCy = 3,
        )
        val snapshot = GameSaveCodec.Snapshot(
            worldSeed = WORLD_SEED,
            meta = meta,
            chunks = mapOf(chunkA.coord to chunkA, chunkB.coord to chunkB),
        )

        val bytes = GameSaveCodec.encode(snapshot)
        val restored = GameSaveCodec.decode(bytes)

        assertEquals(WORLD_SEED, restored.worldSeed)
        assertEquals(meta, restored.meta)
        assertEquals(2, restored.chunks.size)
        assertEquals(chunkA, restored.chunks[chunkA.coord])
        assertEquals(chunkB, restored.chunks[chunkB.coord])
    }

    @Test
    fun roundTrip_emptyBoard_isValid() {
        val snapshot = GameSaveCodec.Snapshot(
            worldSeed = WORLD_SEED,
            meta = GameMeta(),
            chunks = emptyMap(),
        )
        val restored = GameSaveCodec.decode(GameSaveCodec.encode(snapshot))
        assertEquals(snapshot, restored)
    }

    @Test
    fun decode_rejectsBadMagic() {
        val good = GameSaveCodec.encode(
            GameSaveCodec.Snapshot(WORLD_SEED, GameMeta(), emptyMap()),
        )
        val bad = good.copyOf().also { it[0] = 'X'.code.toByte() }
        val ex = assertThrows(IOException::class.java) { GameSaveCodec.decode(bad) }
        assertTrue(ex.message!!.contains("magic", ignoreCase = true))
    }

    @Test
    fun decode_rejectsWrongVersion() {
        val good = GameSaveCodec.encode(
            GameSaveCodec.Snapshot(WORLD_SEED, GameMeta(), emptyMap()),
        )
        // Format version sits right after 4 magic bytes (little-endian int).
        val bad = good.copyOf().also {
            it[4] = 99
            it[5] = 0
            it[6] = 0
            it[7] = 0
        }
        val ex = assertThrows(IOException::class.java) { GameSaveCodec.decode(bad) }
        assertTrue(ex.message!!.contains("version", ignoreCase = true))
    }

    @Test
    fun decode_rejectsWrongWorldSeed() {
        val good = GameSaveCodec.encode(
            GameSaveCodec.Snapshot(WORLD_SEED, GameMeta(), emptyMap()),
        )
        // worldSeed is the long after magic(4) + version(4).
        val bad = good.copyOf().also {
            for (i in 8 until 16) it[i] = 0
        }
        val ex = assertThrows(IOException::class.java) { GameSaveCodec.decode(bad) }
        assertTrue(ex.message!!.contains("seed", ignoreCase = true))
    }

    @Test
    fun decode_rejectsTruncatedStream() {
        val good = GameSaveCodec.encode(
            GameSaveCodec.Snapshot(
                worldSeed = WORLD_SEED,
                meta = GameMeta(flagsPlaced = 1, hasEverRevealed = true),
                chunks = mapOf(
                    ChunkCoord(1, 1) to Chunk(
                        coord = ChunkCoord(1, 1),
                        generated = true,
                        cells = List(64) { Cell(state = CellState.REVEALED) },
                    ),
                ),
            ),
        )
        val truncated = good.copyOf(good.size / 2)
        assertThrows(Exception::class.java) { GameSaveCodec.decode(truncated) }
    }

    @Test
    fun decode_rejectsTrailingBytes() {
        val good = GameSaveCodec.encode(
            GameSaveCodec.Snapshot(WORLD_SEED, GameMeta(), emptyMap()),
        )
        val withTrailing = good + byteArrayOf(0x01)
        val ex = assertThrows(IOException::class.java) { GameSaveCodec.decode(withTrailing) }
        assertTrue(ex.message!!.contains("Trailing", ignoreCase = true))
    }

    @Test
    fun encode_rejectsMismatchedSeed() {
        assertThrows(IllegalArgumentException::class.java) {
            GameSaveCodec.encode(
                GameSaveCodec.Snapshot(worldSeed = 0L, meta = GameMeta(), chunks = emptyMap()),
            )
        }
    }

    @Test
    fun encode_isByteStableForEmptySave() {
        val a = GameSaveCodec.encode(GameSaveCodec.Snapshot(WORLD_SEED, GameMeta(), emptyMap()))
        val b = GameSaveCodec.encode(GameSaveCodec.Snapshot(WORLD_SEED, GameMeta(), emptyMap()))
        assertArrayEquals(a, b)
    }
}
