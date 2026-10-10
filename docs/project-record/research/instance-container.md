# Research: a multithreaded `InstanceContainer` replacement

**Question.** Build an `InstanceContainer` that is "1:1 compatible with Minestom" but faster and more
maintainable than the original, developed with DRY, SOLID and TDD, using current Java 25 features.
This page records what that investigation found; it describes neither shipped behaviour nor a
benchmark result.

**Method.** Agents reading the Minestom sources at `2026.06.20-26.1.2`, `javap -v` and reflection
against the binary jar of that version, and probe code compiled and run against it. The probes live
outside this repository and are not checked in. The concurrency section additionally quotes figures
from standalone probes whose machine, run configuration and uncertainty were not recorded — those are
marked where they appear and must not be read as benchmark results.

**Established, and durable.** That `Instance` and `InstanceContainer` are not sealed and can be
extended from a foreign package, proven end to end against a real `MinecraftServer.init()`. That
seven `instanceof` / cast sites in Minestom couple behaviour to the concrete `InstanceContainer`
type, four of them silently. That the chunk lifecycle hooks are `protected` and unreachable from a
foreign package. That the chunk and entity tick parallelism lives in the global `ThreadDispatcher`
of `ServerProcessImpl`, not in the instance, so replacing the instance cannot make ticking faster.
These are checkable against the same jar and do not depend on who ran the investigation.

**Open, or not established.** Every performance figure below. None was produced by the JMH harness,
none has a recorded machine or run configuration, and none can be reproduced from this repository.
The direction of the locking findings is supported by the structure of the code; the magnitudes are
not supported by anything a reader can re-run.

**True at.** Written 2026-07-31, entered the repository at
[`fc0aef5`](https://github.com/OneLiteFeatherNET/Falco/commit/fc0aef5), against Minestom
`2026.06.20-26.1.2`. The structural claims were re-checked against that jar at `ca79507`. **Nothing
in this document was implemented as written** — what `falco-instance` became is described in
[Why falco-instance exists](../../explanation/why-falco-instance-exists.md), and the module claims no speed
advantage.

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
scheduler, event node, snapshots). The 776 lines and the 19 abstract methods were re-checked at
`ca79507`; the method and field counts were not, and are the investigation's own figures.

## Why "1:1 compatible" is not reachable

Seven `instanceof` / cast sites in the whole Minestom codebase couple behaviour to the concrete
`InstanceContainer` type — `SharedInstance.java:143`, `:145` and `:154`, `InstanceManager.java:121`,
`Chunk.java:74`, `AbsoluteBlockBatch.java:144` and `ChunkBatch.java:235`, at version
`2026.06.20-26.1.2`, re-checked at `ca79507`. Four of them silently take a different path for a
foreign implementation — no exception, no warning:

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
threading. The findings split cleanly into what is readable from the source and what rests on a
probe, and they are separated here for that reason.

Readable from the source, and therefore the part that carries:

- **The instance monitor serialises all block writes.** `setBlock` holds it for the whole write, so
  writes to unrelated chunks cannot proceed in parallel.
- **`LightingChunk` takes the same instance monitor**, so a relight blocks every writer in the
  instance for its duration.
- **4 of 9 mutable fields are unsynchronised** (verified with `javap`).
- **`currentlyChangingBlocks` is a `HashMap` guarded by two different locks**; `changingBlockLock`
  protects nothing.
- **The chunk locks are `assert`s** — without `-ea` they are inert in production, and Minestom
  violates them itself.
- **`Long2ObjectSyncMap` rebuilds its dirty map after a promotion.** `InstanceContainer:77` holds its
  chunks in one; `Long2ObjectSyncMapImpl.dirtyLocked` (flare-fastutil 2.0.1, `:487-495`) allocates a
  fresh map and copies every non-expunged entry of the read map into it. That is O(loaded chunks) on
  the first write after a promotion, and chunk loading and unloading are writes.

From standalone probes, with no recorded machine, run configuration or uncertainty, and **not
reproducible from this repository**:

| Figure | What it was said to show |
| --- | --- |
| ≈400 000× | how much longer a `setBlock` waited behind a relight holding the instance monitor, in a constructed worst case |
| ≈1 600 000× | the same for a `setBlock().join()` executed under the instance monitor |
| up to 19.8× slower | `Long2ObjectSyncMap` against `ConcurrentHashMap` in the probe's chunk-streaming access pattern |

Read the first two as "the wait is unbounded in the pathological case", which is what the structure
of the code establishes on its own, and not as factors. The third is explicitly discarded by
[Why falco-instance exists](../../explanation/why-falco-instance-exists.md): it came from a probe outside this
repository, no instance timing has ever been produced here, and no argument in the documentation relies on it.
Whether either structure wins for chunk streaming is unmeasured — [Instance
Performance](instance-performance-research.md) specifies the benchmark that would settle it. Instance
benchmark classes exist since 2026-08-03; none has been run as a baseline, so the sentence still
holds for the reason it always did.

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

## What was built instead

`falco-instance` follows the first three recommendations and not the fourth: the generator path was
implemented after all. At `ca79507`, `FalcoInstance.setGenerator` stores the generator, `generator()`
returns it, and `generateChunk` runs it over clones of the chunk's palettes so a generator that fails
halfway leaves nothing behind (`FalcoInstance.java:840-858`, `:875-895`, `:922`ff).

<!-- TODO(maintainer): Rationale: Instances and Chunks still says "generateChunk always throws", and
     the README says it in one place and the opposite in another. The source is the generator path
     above; both pages need the sentence removed. -->

What the module does **not** do is claim a speed advantage — the reason is the dispatcher finding
above, and it is stated in that form in the README and in
[Why falco-instance exists](../../explanation/why-falco-instance-exists.md). Where a gain could exist at all,
and what would have to be measured before anyone said so, is catalogued in [Instance
Performance](instance-performance-research.md), which is a hypothesis and labelled as one.

## References

- Minestom `2026.06.20-26.1.2`, sources jar from the Gradle cache: `instance/InstanceContainer.java`,
  `instance/Instance.java`, `instance/SharedInstance.java:143`, `:145`, `:154`,
  `instance/InstanceManager.java:121`, `instance/Chunk.java:74`,
  `instance/batch/AbsoluteBlockBatch.java:144`, `instance/batch/ChunkBatch.java:235`.
- `space.vectrix.flare:flare-fastutil:2.0.1`, `Long2ObjectSyncMapImpl.java:487-495`.
- [`FalcoInstance`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java)
  — what the module became.
- The sweep that extended the four coupling sites to twelve, and found `EntityTracker` sealed, is in
  [Research: Shared Instances and Batches](shared-instances-and-batches.md).
