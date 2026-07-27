# T7: viewport cache

`ChunkCache` is an access-ordered, bounded cache with a default limit of 512 chunks. Entries within
three chunks of the current visible rectangle are protected from eviction; older entries outside
that retention window are evicted first.

The cache is generic over its LOD artifact so this package stays Android-free. T12 can use
`ChunkCache<ImageBitmap>` and the baked bitmap will be invalidated when its chunk changes and
discarded with the chunk. Dirty chunks are enqueued and `ChunkRepository.flush()` completes before
the entry is removed. Cache access should be serialized on one coroutine context.
