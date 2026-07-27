# T9: lock and wipe mechanic

`LockAndWipeMechanic` consumes the discrete transitions emitted by T8. The integration layer must
serialize `process(event, state)` with player actions and publish the returned state before the next
action.

- A first mine hit remains locked until every one of the eight neighboring chunks is generated,
  unlocked, and has every non-mine cell revealed.
- A `ChunkCleared` transition rechecks every neighboring lock. Soft resolution removes the
  exploded mine, reveals that cell, marks `everSurrounded`, and recomputes adjacency for the
  selector and its one-chunk neighborhood without changing neighboring cell states.
- A later `ChunkLocked` transition in a selector whose `everSurrounded` bit is set invokes the
  generator's deterministic reroll, clears all local cell states and flags, applies its neighboring
  adjacency patches, and increments `selectorsWiped`.

The returned `ChunkSoftResolved` and `ChunkWiped` events are intended for persistence, HUD, and
animation consumers.
