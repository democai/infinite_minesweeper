package com.infinite.minesweeper.core.cache

import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkRepository

const val DEFAULT_MAX_CACHED_CHUNKS: Int = 512
const val DEFAULT_RETENTION_MARGIN_CHUNKS: Int = 3

/**
 * Main-safe, access-ordered chunk cache.
 *
 * [LodArtifact] is deliberately generic so the Android-free cache can own an ImageBitmap's
 * lifetime without depending on Compose. Callers should serialize access from one coroutine
 * context. Every operation that can evict is suspending because dirty entries are made durable
 * before removal.
 */
class ChunkCache<LodArtifact : Any>(
    private val repository: ChunkRepository,
    private val maxChunks: Int = DEFAULT_MAX_CACHED_CHUNKS,
    private val retentionMarginChunks: Int = DEFAULT_RETENTION_MARGIN_CHUNKS,
) {
    private data class Entry<LodArtifact>(
        var chunk: Chunk,
        var dirty: Boolean,
        var lodArtifact: LodArtifact?,
    )

    private val entries = LinkedHashMap<ChunkCoord, Entry<LodArtifact>>(
        maxChunks.coerceAtMost(64),
        0.75f,
        true,
    )
    private var retentionBounds: RetentionBounds? = null

    init {
        require(maxChunks > 0) { "maxChunks must be greater than zero" }
        require(retentionMarginChunks >= 0) {
            "retentionMarginChunks must be non-negative"
        }
    }

    val size: Int
        get() = entries.size

    val coordinates: Set<ChunkCoord>
        get() = entries.keys.toSet()

    operator fun get(coord: ChunkCoord): Chunk? = entries[coord]?.chunk

    fun peek(coord: ChunkCoord): Chunk? = entries.entries
        .firstOrNull { it.key == coord }
        ?.value
        ?.chunk

    fun lodArtifact(coord: ChunkCoord): LodArtifact? = entries[coord]?.lodArtifact

    fun setLodArtifact(coord: ChunkCoord, artifact: LodArtifact?) {
        val entry = entries[coord] ?: return
        entry.lodArtifact = artifact
    }

    /**
     * Adds or replaces a chunk. A changed chunk invalidates its baked LOD artifact.
     */
    suspend fun put(chunk: Chunk, dirty: Boolean = false) {
        val existing = entries[chunk.coord]
        if (existing == null) {
            entries[chunk.coord] = Entry(
                chunk = chunk,
                dirty = dirty,
                lodArtifact = null,
            )
        } else {
            if (existing.chunk != chunk) existing.lodArtifact = null
            existing.chunk = chunk
            existing.dirty = existing.dirty || dirty
        }
        trimToSize()
    }

    suspend fun putAll(chunks: Collection<Chunk>, dirty: Boolean = false) {
        for (chunk in chunks) {
            val existing = entries[chunk.coord]
            if (existing == null) {
                entries[chunk.coord] = Entry(chunk, dirty, null)
            } else {
                if (existing.chunk != chunk) existing.lodArtifact = null
                existing.chunk = chunk
                existing.dirty = existing.dirty || dirty
            }
        }
        trimToSize()
    }

    fun markDirty(coord: ChunkCoord) {
        val entry = entries[coord] ?: return
        entry.dirty = true
    }

    fun isDirty(coord: ChunkCoord): Boolean = entries.entries
        .firstOrNull { it.key == coord }
        ?.value
        ?.dirty == true

    /**
     * Hydrates persisted chunks on viewport entry and returns every requested chunk currently
     * available. Coordinates with no saved chunk are omitted.
     */
    suspend fun loadVisibleChunks(visibleChunks: Set<ChunkCoord>): Map<ChunkCoord, Chunk> {
        retentionBounds = RetentionBounds.around(visibleChunks, retentionMarginChunks)
        val result = LinkedHashMap<ChunkCoord, Chunk>(visibleChunks.size)
        val missing = LinkedHashSet<ChunkCoord>()
        for (coord in visibleChunks) {
            val cached = entries[coord]?.chunk
            if (cached == null) {
                missing += coord
            } else {
                result[coord] = cached
            }
        }

        if (missing.isNotEmpty()) {
            val loaded = repository.getChunks(missing)
            for (coord in visibleChunks) {
                val chunk = loaded[coord] ?: continue
                entries[coord] = Entry(chunk = chunk, dirty = false, lodArtifact = null)
                result[coord] = chunk
            }
        }
        trimToSize()
        return result
    }

    /**
     * Updates recency for visible chunks and makes entries outside the viewport plus retention
     * margin eligible for LRU eviction.
     */
    suspend fun updateVisibleChunks(visibleChunks: Set<ChunkCoord>) {
        retentionBounds = RetentionBounds.around(visibleChunks, retentionMarginChunks)
        for (coord in visibleChunks) {
            // LinkedHashMap access updates recency.
            entries[coord]
        }
        trimToSize()
    }

    /**
     * Removes an entry explicitly, preserving the same durability guarantee as LRU eviction.
     */
    suspend fun remove(coord: ChunkCoord): Chunk? {
        val entry = entries.entries.firstOrNull { it.key == coord }?.value ?: return null
        flushIfDirty(entry)
        entries.remove(coord)
        return entry.chunk
    }

    /**
     * Flushes dirty cached values without evicting them.
     */
    suspend fun flushDirty() {
        val dirtyEntries = entries.values.filter { it.dirty }
        if (dirtyEntries.isEmpty()) return
        repository.saveChunks(dirtyEntries.map { it.chunk })
        repository.flush()
        dirtyEntries.forEach { it.dirty = false }
    }

    private suspend fun trimToSize() {
        while (entries.size > maxChunks) {
            val candidate = entries.entries.firstOrNull { (coord, _) ->
                retentionBounds?.contains(coord) != true
            } ?: return
            // Do not remove until persistence succeeds. If save/flush throws, the dirty entry and
            // its LOD artifact remain available for a later retry.
            flushIfDirty(candidate.value)
            entries.remove(candidate.key)
        }
    }

    private suspend fun flushIfDirty(entry: Entry<LodArtifact>) {
        if (!entry.dirty) return
        repository.saveChunk(entry.chunk)
        repository.flush()
        entry.dirty = false
    }

    private data class RetentionBounds(
        val minCx: Long,
        val minCy: Long,
        val maxCx: Long,
        val maxCy: Long,
    ) {
        fun contains(coord: ChunkCoord): Boolean =
            coord.cx.toLong() in minCx..maxCx && coord.cy.toLong() in minCy..maxCy

        companion object {
            fun around(
                coords: Set<ChunkCoord>,
                margin: Int,
            ): RetentionBounds? {
                if (coords.isEmpty()) return null
                var minCx = Int.MAX_VALUE
                var minCy = Int.MAX_VALUE
                var maxCx = Int.MIN_VALUE
                var maxCy = Int.MIN_VALUE
                for (coord in coords) {
                    minCx = minOf(minCx, coord.cx)
                    minCy = minOf(minCy, coord.cy)
                    maxCx = maxOf(maxCx, coord.cx)
                    maxCy = maxOf(maxCy, coord.cy)
                }
                return RetentionBounds(
                    minCx = minCx.toLong() - margin,
                    minCy = minCy.toLong() - margin,
                    maxCx = maxCx.toLong() + margin,
                    maxCy = maxCy.toLong() + margin,
                )
            }
        }
    }
}
