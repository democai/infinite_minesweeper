# T2: chunk blob codec

`ChunkBlobCodec` packs exactly 64 cells into 64 bytes (bit 0 = LSB):

| Bits | Value |
|---|---|
| 0–1 | hidden `00`, revealed `01`, flagged `10`, exploded `11` |
| 2 | mine presence |
| 3–6 | adjacent-mine count, 0–8 |
| 7 | reserved and written as zero |

Both `pack`/`unpack` and `encode`/`decode` are available. Decoding rejects adjacency
values outside 0–8.
