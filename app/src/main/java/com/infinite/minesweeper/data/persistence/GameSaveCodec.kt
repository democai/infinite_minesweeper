package com.infinite.minesweeper.data.persistence

import com.infinite.minesweeper.core.codec.ChunkBlobCodec
import com.infinite.minesweeper.core.generation.WORLD_SEED
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameMeta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * Portable binary encoding of board progress (all chunks + game_meta).
 *
 * Format v1 layout (little-endian multi-byte integers via [writeIntLe] / [readIntLe]):
 * - magic `IMSV` (4 ASCII bytes)
 * - format version (`int` = 1)
 * - world seed (`long`)
 * - [GameMeta] fields
 * - chunk count (`int`)
 * - per chunk: cx, cy, status ordinal (byte), flags (byte), lockedAt (`long`, [NO_LOCKED_AT]
 *   when null), 64-byte cells blob
 */
object GameSaveCodec {
    const val FORMAT_VERSION: Int = 1
    val MAGIC: ByteArray = byteArrayOf('I'.code.toByte(), 'M'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte())

    private const val FLAG_GENERATED = 1
    private const val FLAG_EVER_SURROUNDED = 1 shl 1
    private const val NO_LOCKED_AT: Long = Long.MIN_VALUE

    data class Snapshot(
        val worldSeed: Long,
        val meta: GameMeta,
        val chunks: Map<ChunkCoord, Chunk>,
    )

    fun encode(snapshot: Snapshot): ByteArray {
        require(snapshot.worldSeed == WORLD_SEED) {
            "Cannot encode save for world seed ${snapshot.worldSeed}; app seed is $WORLD_SEED"
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeIntLe(FORMAT_VERSION)
            out.writeLongLe(snapshot.worldSeed)
            writeMeta(out, snapshot.meta)
            val chunkList = snapshot.chunks.values.toList()
            out.writeIntLe(chunkList.size)
            for (chunk in chunkList) {
                writeChunk(out, chunk)
            }
            out.flush()
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray, expectedWorldSeed: Long = WORLD_SEED): Snapshot {
        if (bytes.size < MAGIC.size + 4) {
            throw IOException("Save file too short")
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) {
                throw IOException("Not an infinite minesweeper save (bad magic)")
            }
            val version = input.readIntLe()
            if (version != FORMAT_VERSION) {
                throw IOException("Unsupported save format version $version (expected $FORMAT_VERSION)")
            }
            val worldSeed = input.readLongLe()
            if (worldSeed != expectedWorldSeed) {
                throw IOException(
                    "Save world seed $worldSeed does not match this app ($expectedWorldSeed)",
                )
            }
            val meta = readMeta(input)
            val chunkCount = input.readIntLe()
            if (chunkCount < 0) {
                throw IOException("Negative chunk count: $chunkCount")
            }
            val chunks = LinkedHashMap<ChunkCoord, Chunk>(chunkCount)
            repeat(chunkCount) {
                val chunk = readChunk(input)
                chunks[chunk.coord] = chunk
            }
            // Trailing bytes are not allowed — catches truncated or concatenated garbage early.
            if (input.available() > 0) {
                throw IOException("Trailing data after save payload (${input.available()} bytes)")
            }
            Snapshot(worldSeed = worldSeed, meta = meta, chunks = chunks)
        }
    }

    private fun writeMeta(out: DataOutputStream, meta: GameMeta) {
        out.writeIntLe(meta.flagsPlaced)
        out.writeIntLe(meta.selectorsCleared)
        out.writeIntLe(meta.selectorsWiped)
        out.writeFloatLe(meta.viewportX)
        out.writeFloatLe(meta.viewportY)
        out.writeFloatLe(meta.zoom)
        out.writeBoolean(meta.hasEverRevealed)
        out.writeBoolean(meta.hasExploredBounds)
        out.writeIntLe(meta.exploredMinCx)
        out.writeIntLe(meta.exploredMaxCx)
        out.writeIntLe(meta.exploredMinCy)
        out.writeIntLe(meta.exploredMaxCy)
    }

    private fun readMeta(input: DataInputStream): GameMeta =
        GameMeta(
            flagsPlaced = input.readIntLe(),
            selectorsCleared = input.readIntLe(),
            selectorsWiped = input.readIntLe(),
            viewportX = input.readFloatLe(),
            viewportY = input.readFloatLe(),
            zoom = input.readFloatLe(),
            hasEverRevealed = input.readBoolean(),
            hasExploredBounds = input.readBoolean(),
            exploredMinCx = input.readIntLe(),
            exploredMaxCx = input.readIntLe(),
            exploredMinCy = input.readIntLe(),
            exploredMaxCy = input.readIntLe(),
        )

    private fun writeChunk(out: DataOutputStream, chunk: Chunk) {
        out.writeIntLe(chunk.coord.cx)
        out.writeIntLe(chunk.coord.cy)
        out.writeByte(chunk.status.ordinal)
        var flags = 0
        if (chunk.generated) flags = flags or FLAG_GENERATED
        if (chunk.everSurrounded) flags = flags or FLAG_EVER_SURROUNDED
        out.writeByte(flags)
        out.writeLongLe(chunk.lockedAt ?: NO_LOCKED_AT)
        val blob = ChunkBlobCodec.pack(chunk.cells)
        require(blob.size == ChunkBlobCodec.BLOB_SIZE)
        out.write(blob)
    }

    private fun readChunk(input: DataInputStream): Chunk {
        val cx = input.readIntLe()
        val cy = input.readIntLe()
        val statusOrdinal = input.readUnsignedByte()
        val status = ChunkStatus.entries.getOrNull(statusOrdinal)
            ?: throw IOException("Unknown chunk status ordinal $statusOrdinal")
        val flags = input.readUnsignedByte()
        val generated = flags and FLAG_GENERATED != 0
        val everSurrounded = flags and FLAG_EVER_SURROUNDED != 0
        val lockedAtRaw = input.readLongLe()
        val lockedAt = lockedAtRaw.takeUnless { it == NO_LOCKED_AT }
        val blob = ByteArray(ChunkBlobCodec.BLOB_SIZE)
        try {
            input.readFully(blob)
        } catch (e: EOFException) {
            throw IOException("Truncated cell blob at chunk ($cx, $cy)", e)
        }
        return Chunk(
            coord = ChunkCoord(cx, cy),
            generated = generated,
            cells = ChunkBlobCodec.unpack(blob),
            status = status,
            everSurrounded = everSurrounded,
            lockedAt = lockedAt,
        )
    }

    // DataOutputStream is big-endian; force little-endian multi-byte values per format v1.

    private fun DataOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun DataOutputStream.writeLongLe(value: Long) {
        writeIntLe(value.toInt())
        writeIntLe((value ushr 32).toInt())
    }

    private fun DataOutputStream.writeFloatLe(value: Float) {
        writeIntLe(value.toBits())
    }

    private fun DataInputStream.readIntLe(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        val b3 = readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun DataInputStream.readLongLe(): Long {
        val lo = readIntLe().toLong() and 0xFFFF_FFFFL
        val hi = readIntLe().toLong()
        return lo or (hi shl 32)
    }

    private fun DataInputStream.readFloatLe(): Float = Float.fromBits(readIntLe())
}
