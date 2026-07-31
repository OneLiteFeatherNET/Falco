# Design: `FalcoLightingChunk` — light that maintains itself

Status: approved, not implemented · 2026-07-31

## The problem

The light engine works today, but using it is manual. A caller has to notice that a chunk changed,
decide when to recompute, and call `ChunkLightService` themselves. Minestom sets a much lower bar:

```java
instance.setChunkSupplier(LightingChunk::new);
```

After that, light maintains itself. This design gives Falco the same entry point, without giving up
what the engine gained: it is thread-safe per call, and it is not tied to a chunk implementation.

Minestom's own engine is the more restricted one here. `LightingChunk extends DynamicChunk`, and
light is only computed for that class — anyone using a different chunk implementation gets no light
at all. Falco hands its result over through `Light#set`, so the same scheduler works for any chunk.

## Goals

- `instance.setChunkSupplier(FalcoLightingChunk::new)` is enough. No further setup.
- Block light and sky light, both maintained automatically after a block changes.
- The work spreads across threads, because the engine was made thread-safe precisely for that.
- No seams at chunk borders.

## Non-goals

- **No custom `Light` implementation.** Results keep going through `Light#set`, which is the stable
  half of that interface. `Section.clone()` calls `Light.sky()` / `Light.block()` outright, so a
  foreign implementation would be silently replaced on copy.
- **No incremental single-block update.** A changed block dirties its whole chunk. The engine has
  `ChunkLightState#update` for finer work; wiring it in is a later step and does not change this
  design.
- **No replacement for `ChunkLightService`.** The scheduler drives that service, it does not
  duplicate it.

## Components

Three types, each with one job.

### `FalcoLightingChunk extends DynamicChunk`

The drop-in. It holds **no** computation logic.

| Override | Purpose |
| --- | --- |
| `setBlock(...)` | Increments its revision and reports itself dirty, then delegates. |
| `onLoad()` | Reports itself dirty, so a freshly loaded chunk gets light. |
| `tick(long)` | Delegates, then lets the scheduler run its once-per-tick pass. |

`DynamicChunk` is a plain `public class`, neither final nor sealed — verified against Minestom
`2026.06.20-26.1.2`.

**`createLightData` is deliberately *not* overridden.** It reads the sections, and those are exactly
what `applyLight` writes into, so the inherited implementation already returns the current state and
already never blocks. An override would add a second path to the same data with nothing to gain.

### `ChunkLightScheduler`

One per `Instance`. Owns the dirty set, forms areas once per tick, and submits them to the executor.
All of the complexity lives here, at one address.

```java
ChunkLightScheduler(ChunkLightService service, Executor executor, int maxAreaSize)
ChunkSupplier supplier()
void markDirty(Chunk chunk)
void onTick(long tick)
```

The executor is injectable. The default bounds virtual threads by `availableProcessors()`; a test
passes a direct executor and gets a fully deterministic run; a server can share its own pool.

### `ChunkLightArea`

One connected group of chunks. Reads the block states of its chunks **plus one ring around them**,
exchanges borders inside the area until nothing changes, writes back only the dirty chunks.

Built on `ChunkLightState` (`blockLight`, `border`, `injectBorder`, `toSections`) rather than on
`ChunkLightService#calculateWithNeighbours`, whose 3×3 neighbourhood is hard-coded in its private
constants. The underlying state type is not restricted to 3×3.

**The ring is read but never written.** Light from outside an area is missing at its edge, so those
chunks would end up too dark. This is the same mistake `calculateWithNeighbours` makes today (see
`STATUS.md`, open item on `calculateWithNeighbours`); here it is designed out from the start.

## The tick cycle

**Triggering.** `Chunk#tick(long time)` runs per chunk, but the scheduler must run once per tick.
`time` is the tick's timestamp and is identical for every chunk within one tick. The scheduler
remembers the last value it saw and runs its pass when a chunk reports a different one. If no chunk
of the instance ticks, nothing happens — which is correct, because then nobody is looking.

**Area forming.** Connected components over the dirty set: chunks sharing an edge belong to the same
area.

**Area size is capped, and the cap is not optional.** One `ChunkLightState` is about 980 KB at 24
sections. A build across 100 connected chunks would form a single area of roughly 100 MB plus its
ring. Areas are therefore split at `maxAreaSize` (default 16 chunks). The seam between two parts
settles on the next tick, because each part reads the other as part of its ring.

**Back-pressure.** If an area takes longer than a tick, the next pass must not start it again.
Chunks under computation stay marked but are skipped.

*Outdated* needs a definition, or the rule cannot be implemented: every chunk carries a change
counter, incremented by `setBlock`. An area records the counter of each of its chunks when it starts
reading. Before writing a chunk back, it compares again — a differing counter means the chunk changed
underneath, so **that chunk's result is discarded and it stays dirty**. Other chunks of the same area
are unaffected and are written normally.

**Sky light** is computed alongside block light, but only where `instance.getCachedDimensionType()`
reports the dimension has skylight. Elsewhere the work would be thrown away.

**Delivery.** Nothing ever blocks on a computation. `createLightData` returns whatever the sections
hold right now — the previous result while a new one is in flight. When a computation finishes, the
scheduler calls `invalidate()` on the affected chunks, and Minestom sends an `UpdateLightPacket` on
its own. This is the mechanism `LightingChunk` already uses; we attach to it rather than inventing
one.

## Errors

An exception in one area must damage neither the tick nor another area. It is caught, reported to
the `ExceptionManager`, and the affected chunks stay dirty instead of being written with half a
result. A chunk already unloaded at write time is skipped.

## Testing

Test-first, as everywhere in this branch.

- **Area forming** is pure coordinate arithmetic and needs no server: connectivity, splitting at the
  cap, and that a split produces parts whose rings overlap.
- **The tick cycle** runs deterministically with a direct executor: dirty on `setBlock`, computed
  once per tick, skipped while a computation is in flight, discarded when outdated.
- **End to end** with `MicrotusExtension`: a block placed at a chunk border lights the neighbour, and
  the result matches what `ChunkLightService` produces for the same area — the scheduler must not
  change the outcome, only when it is computed.
- **Errors:** a failing area leaves its chunks dirty and does not stop the others.

## Acceptance

Beyond green tests, one measurement decides whether the complexity earned its place:

> An area of *n* chunks must be measurably cheaper than *n* separate `calculateWithNeighbours`
> calls.

That is the entire justification for area forming. If it does not hold, the simpler design — one
chunk at a time — is the better one, and this document was wrong.

## Open questions

None. The three decisions this design rests on were made explicitly:

| Decision | Choice | Rejected |
| --- | --- | --- |
| When to compute | Batched per tick | Immediately on `setBlock`; lazily on send |
| Chunk borders | One area per tick, ring read but not written | Chunk alone (seams); 3×3 per chunk (reads the same data nine times) |
| Parallelism | Own pool sized from CPUs, injectable | Minestom's scheduler; synchronous in the tick |
