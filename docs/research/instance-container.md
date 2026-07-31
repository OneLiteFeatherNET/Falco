# Research: a multithreaded `InstanceContainer` replacement

**Question.** Build an `InstanceContainer` that is "1:1 compatible with Minestom" but faster and more
maintainable than the original, developed with DRY, SOLID and TDD, using current Java 25 features.

**Verdict: partial.** It compiles, it registers, it runs — proven end to end. But *1:1 compatible* is
not reachable, and the performance premise of the request is wrong. Nothing has been implemented.

## What is possible

Unlike `Palette`, there is no sealing here. Verified with `javap -v` and reflection against the
actual jar:

| Type | Modifiers | Sealed? |
| --- | --- | --- |
| `Palette` | `public interface` | **yes** — `PermittedSubclasses: PaletteImpl` |
| `Instance` | `public abstract class` | no, `permitted=null` |
| `InstanceContainer` | `public class` | no, not final, not abstract |

Both `class FalcoInstanceA extends InstanceContainer` and `class FalcoInstanceB extends Instance`
compile. `InstanceManager.registerInstance(Instance)` is public, and the `Instance` Javadoc
explicitly invites custom implementations.

An agent wrote a complete `ForeignInstance extends Instance` in a foreign package — all 19 abstract
methods — compiled it and ran it against a real `MinecraftServer.init()`:

```
[1] constructed custom Instance: falcoprobe.ForeignInstance
[2] registerInstance() OK, isRegistered=true
[4] loadChunk(0,0) -> DynamicChunk
[5] getBlock(1,64,1) = minecraft:diamond_block
[6] tick() OK
```

The scale is manageable: `InstanceContainer` is 776 lines, 58 methods, 16 fields. `Instance` itself
contributes 103 methods that are inherited for free (world border, weather, clocks, entity tracker,
scheduler, event node, snapshots).

## Why "1:1 compatible" is not reachable

Seven `instanceof` / cast sites in the whole Minestom codebase couple behaviour to the concrete
`InstanceContainer` type. Four of them silently take a different path for a foreign implementation —
no exception, no warning:

| Site | Effect on a foreign implementation |
| --- | --- |
| `InstanceManager.unregisterInstance` | Chunks are **not** unloaded and partitions not deleted. Reproduced: `before unregister: chunks=1` → `after unregister: chunks STILL loaded = 1`. A real leak. |
| `SharedInstance` | Field and constructor are typed on `InstanceContainer`. Reproduced: `createSharedInstance(customInstance)` → `ClassCastException`. `areLinked` always returns false. |
| `Chunk` constructor | Computes its shared-viewer list via `instance instanceof InstanceContainer`. A foreign instance gives every chunk a permanently empty list, so `SharedInstance` players never see chunk updates. |
| `ChunkBatch` / `AbsoluteBlockBatch` | `refreshLastBlockChangeTime()` is only called on `InstanceContainer`, so batch/copy semantics drift. |

Silent divergence is worse than a compile error, because it surfaces as a bug much later.

There is a second obstacle: **the chunk lifecycle hooks are `protected` and unreachable from a
foreign package.** Deriving from `InstanceContainer` and overriding the hot paths does **not** work.
An escape hatch exists — a custom `Chunk` subclass re-exposing the protected hooks — and it compiles,
but it means the replacement drags a chunk implementation along.

## Why the performance premise is wrong

**The chunk and entity tick parallelism does not live in `InstanceContainer`.** It lives in the global
`ThreadDispatcher` of `ServerProcessImpl`, which defaults to a single thread. A replacement container
changes nothing about it.

What the analysis *did* find is a real and severe concurrency problem — but in locking, not in
threading:

- **The instance monitor serialises all block writes.** `setBlock` scales negatively under contention.
- **`LightingChunk` takes the same instance monitor**, so a relight freezes the entire instance
  (measured factor ≈400,000× in the pathological case).
- **`setBlock().join()` under the instance monitor**: one thread can stall the whole instance
  (≈1,600,000×).
- **4 of 9 mutable fields are unsynchronised** (verified with `javap`).
- **`currentlyChangingBlocks` is a `HashMap` guarded by two different locks**; `changingBlockLock`
  protects nothing.
- **The chunk locks are `assert`s** — without `-ea` they are inert in production, and Minestom
  violates them itself.
- **`chunks` as `Long2ObjectSyncMap`** is the wrong structure for chunk streaming (up to 19.8× slower
  in the measured access pattern).

One earlier suspicion was **refuted**: `retrieveChunk` does not leak `loadingChunks` entries. It does
leave a permanent zombie chunk when `unloadChunk` races a running `loadChunk`, and
`loadChunk().join()` can then hand back a dead chunk.

## Recommendation from the agents

Clarify the goal before building anything. *Cleaner and more maintainable* is very achievable — the
original concentrates nine responsibilities in one class. *Faster through multithreading* is not
achievable this way, because the parallelism sits in the dispatcher.

If it is built, then as `FalcoInstance extends Instance` in `net.onelitefeather.falco.instance`, **not**
as an `InstanceContainer` subclass, and with:

- an own unload path, because `InstanceManager.unregisterInstance` does not clean up foreign types —
  this is the one defect that must not be inherited;
- the protected-chunk barrier solved through an own `Chunk` subclass, not through reflection;
- all four `instanceof` break points captured as explicit versioned compatibility tests, so a
  Minestom upgrade that adds a fifth is caught by CI;
- the generator path *not* reimplemented in a first version.

Worthwhile sub-goals in order: a `setBlock` pipeline without the global `synchronized`, consistent
locking for `currentlyChangingBlocks`, and a chunk map suited to streaming.
