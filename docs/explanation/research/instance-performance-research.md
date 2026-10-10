# Research: could `falco-instance` be faster, and how would anyone know?

**Question.** Is there anywhere a speed or parallelism advantage over Minestom's `InstanceContainer`
could exist for `falco-instance`, what stands in the way, and what would have to be built and
measured before anyone could say? This page is a **hypothesis catalogue**. It is for whoever picks up
that work; it is not for anyone deciding whether to use the module, because it establishes nothing
about how fast the module is.

> **This page contains no performance result, and none may be quoted from it.**
>
> The repository's position on `falco-instance` is, in the words of the
> [README](https://github.com/OneLiteFeatherNET/Falco#readme): **"No speed gain is claimed and none
> is measured"**. [Why falco-instance exists](../why-falco-instance-exists.md) says the same,
> and **this document does not change it.** When it was written there was no instance benchmark in
> the repository at all. Since 2026-08-03 there is: `falco-benchmarks/build.gradle.kts` declares
> `jmhImplementation(project(":falco-instance"))` beside the anvil and light lines, and four JMH
> classes exist for the chunk and the instance. **None has been run as a baseline** — the full run
> takes over two hours and refuses to start above a load average of 1.5 — so there is still no
> instance timing to quote, and every gain claimed below still rests on source reading alone. What
> did arrive is a set of *counts*: object and byte figures from jol, deterministic, published under
> [Counted](../../reference/measured-results.md#counted). They say a chunk is smaller. They say nothing about how long
> anything takes.
>
> Every "gain" below is a **conjecture about an unmeasured quantity**, marked `(estimated)` where it
> is quantitative and named as a mechanism where it is not. Several of them stand next to a cost item
> that is out of reach entirely, and could be invisible against it. Nothing here has been implemented
> either — the measures are proposals, not a changelog.
>
> A sentence lifted from this page and presented as a Falco performance claim would be a
> misrepresentation of the page as well as of the project.

**Method.** Four independent source analyses by agents, reading `falco-instance` and the Minestom
sources at `2026.06.20-26.1.2` (the version `mycelium-bom` 1.7.2 resolves to). No code was written,
no benchmark was run, and no profiler was attached. Where the analyses disagreed with each other or
with the repository's own documentation, the source decides and the disagreement is recorded.

**Established.** Three framing facts that decide where a gain can exist at all: the default server
processes all player-driven block writes on one thread; the work item the analyses judged largest per
write lives in a type that cannot be replaced from outside Minestom; and one proposal the analyses
ranked as cheap and low-risk would silently break the module's core guarantee. Two correctness
findings that hold regardless of performance. Five documentation defects, all in Falco's own text —
four in Javadoc, one in the wiki.

**Open.** Everything else, and that is most of the page. See [Open
questions](#open-questions) for the list the investigation itself refuses to answer.

**True at.** Carried out 2026-08-01 against Falco `0.3.0` and Minestom `2026.06.20-26.1.2`. Line
references into `falco-instance` were re-checked at `ca79507` and hold. Line references to Minestom
sources are prefixed `MS:`; they are accurate for `26.1.2` and shift by a few lines on other
versions.

## Contents

- [The short answer](#the-short-answer)
- [The framing findings](#the-framing-findings)
- [Two correctness findings that matter regardless of performance](#two-correctness-findings-that-matter-regardless-of-performance)
- [What has to be measured first](#what-has-to-be-measured-first)
- [Measures](#measures) — tier A, tier B and tier C
- [What we advise against](#what-we-advise-against)
- [Open questions](#open-questions)
- [Suggested order](#suggested-order)
- [References](#references)

## The short answer

1. **On a default server, all player-driven block writes already run on a single thread.**
   `ServerFlag.DISPATCHER_THREADS` defaults to 1 (`MS:ServerFlag.java:20`), and player packets are
   processed inside the entity tick (`MS:entity/Player.java:378-380`). Falco's finer lock granularity
   is invisible there.
2. **The four items the analyses judged largest in the block-write and chunk-streaming path lie
   outside what any `Instance` implementation controls** — the per-packet viewer scan
   (`MS:instance/EntityTrackerImpl.java:320-352`), palette writes, chunk packet serialisation, and the
   absence of batching. Swapping the instance addresses none of them. That they are out of reach is
   structural and checkable; that they are the largest is a judgement from reading the code, not a
   profile.
3. What is structurally *in reach* — which is not the same as what would be visible: bounding the
   unbounded thread fan-out on load and generate, pulling foreign code out of the chunk write lock,
   moving expensive work off the tick thread onto the load thread, and removing a few shared write
   cells. Whether any of them changes a number nobody has measured.
4. The one proposal the analyses rated as the most plausible candidate for visible tick-time impact —
   moving work off the tick thread onto the load thread — is currently blocked by a type check
   (`FalcoInstance.java:690-695`), for which there is a way out that none of the source analyses found
   ([B3](#b3--dissolve-the-falcoinstance--falcolightingchunk-exclusivity)). "Most plausible candidate"
   is a ranking among conjectures, not a prediction.
5. A result of "the advantage only exists in a configuration almost nobody runs" would not be a
   failure. It would be the first defensible statement on the question at all.

## The framing findings

Three verified facts that determine where a gain can exist at all. Every measure below is prioritised
against them.

### The default server is single-threaded in the tick

`MS:ServerFlag.java:20` defaults `DISPATCHER_THREADS` to 1. `MS:ServerProcessImpl.java:307-318` ticks
instances serially and then uses `dispatcher().updateAndAwait(...)` as a hard barrier.
`MS:entity/Player.java:378-380`: `Player.update` calls `interpretPacketQueue()`, so player packets —
and therefore every block placement — are processed in the entity tick, on the partition's
`TickThread`. With one dispatcher thread, *all* player-driven block writes are serialised before
`FalcoInstance` ever sees them.

The finer lock only pays off when either `-Dminestom.dispatcher-threads` is raised, or the application
writes from its own threads (world generation, schematics, minigame arenas). **Both belong in any
claim of an advantage**, and both must appear as parameters in a benchmark.

### The largest work item per block write is out of reach

The *structure* below is read from the source; the judgement that it is the largest item is the
analyses' own and is not measured.

`MS:instance/EntityTrackerImpl.java:337-352`: `ChunkView.SetImpl` calls `references()` on every
`iterator()`, `size()` and `forEach()` (`:320-330`), which allocates an `Int2ObjectOpenHashMap` and
walks `nearbyEntitiesByChunkRange` across `(2·8+1)² = 289` chunks at the default view distance
(`MS:ServerFlag.java:17`). There is no cache. Every `chunk.sendPacketToViewers(...)`
(`FalcoInstance.java:445`, `:449`) triggers it. The `EntityTracker` is created privately in
`MS:instance/Instance.java:116` and, being a sealed interface, cannot be replaced from outside
Minestom either.

Any micro-allocation optimisation in the write path stands next to this item. Without measurement it
cannot be claimed that one is even visible — which is the reason several proposals below sit in
Tier B rather than Tier A.

## Two correctness findings that matter regardless of performance

### A structurally reachable deadlock

`FalcoInstance.java:429-439` holds the chunk write lock across two calls into foreign code, and both
receive `this`:

```java
chunk.lockWriteLock();
try {
    this.lastBlockChangeTime = System.nanoTime();
    final BlockPlacementRule rule = MinecraftServer.getBlockManager().getBlockPlacementRule(placed);
    if (placement != null && rule != null && doBlockUpdates) {
        placed = Objects.requireNonNullElse(rule.blockPlace(placementState(placement, placed, blockPosition)), Block.AIR);
    }
    chunk.setBlock(x, y, z, placed, placement, destroy);
} finally { chunk.unlockWriteLock(); }
```

- `rule.blockPlace(...)` — the `PlacementState` carries `this` (`:467`, `:471`).
- `chunk.setBlock(..., placement, destroy)` → `MS:instance/DynamicChunk.java:115-129` calls
  `handler.onDestroy` and `handler.onPlace`, both with `instance` in the argument list.

Foreign code that then calls `instance.getBlock(...)` on a *different* chunk takes that chunk's read
lock while the first chunk's write lock is held (`MS:utils/chunk/ChunkCache.java:38-43`). Two
concurrent writers in two chunks whose rules or handlers read each other produce exactly the deadlock
shape that `FalcoInstance.java:441-442` explicitly describes as avoided.

Minestom has the same structure (`MS:instance/InstanceContainer.java:159-215`), but its instance
monitor (`:149`) makes the constellation unreachable. **This is the one place where giving up the
global lock opens a new deadlock form** — and the Javadoc claims the opposite at that very spot.

*Not verified:* whether any shipped vanilla `BlockPlacementRule` or `BlockHandler` actually reads the
world. The shape is structurally reachable; its occurrence in a default configuration is not
demonstrated.

### `loadingChunks` must not be swapped for `Long2ObjectSyncMap`

This is the most dangerous proposal the research produced, and it looked cheap: a drop-in replacement,
effort "S", because every operation used exists in primitive form, `compute` included.

`Long2ObjectSyncMapImpl.compute` (`:251`) delegates on the fast path to `ExpungingEntryImpl.compute`
(`:576-585`), which is a CAS retry loop:

```java
for(;;) {
  final Object previous = this.value;
  if(previous == EXPUNGED) return ...;
  if(UPDATER.compareAndSet(this, previous, next != null ? next : (next = remappingFunction.apply(key, (V) previous))))
    return ...;
}
```

The remapping function is therefore **not mutually excluded** and can run more than once.
`FalcoInstance` does not use `loadingChunks.compute` as a computation, but as a **per-position lock
with side effects**: `chunks.put` + `dispatcher().createPartition` (`:640-646`), `chunks.remove` +
`markUnloaded()` + `deletePartition` (`:730-737`), and the double-check of the chunk map (`:551-559`).
That is exactly the invariant `FalcoInstance.java:165-172` describes and `FalcoInstanceLoadRaceTest`
pins down.

**The swap would break the module's core guarantee silently** — no compiler error, no test failure at
low concurrency. Only `chunks` (`:156`, used with `get`/`put`/`remove` only) would be exchangeable,
and it is mutated *inside* the other map's `compute`.

Related: the Javadoc at `FalcoInstance.java:150-155` justifies the `ConcurrentHashMap` choice by
calling `Long2ObjectSyncMap` a "copy-on-write map". It is a port of Go's `sync.Map` — a volatile read
map without locking plus a dirty map behind a monitor. Copy-on-write happens only on promotion of the
dirty map, not on every write, and its read path is primitive and lock-free. **The stated reason does
not hold.**

## What has to be measured first

Without this section the rest is opinion. Four building blocks, in this order.

### M0 — infrastructure

- `falco-benchmarks/build.gradle.kts` needs `jmhImplementation(project(":falco-instance"))`. It is
  absent today; the benchmark module knows `falco-anvil` and `falco-light` only.
- `benchmark/support/MemoryChunkLoader` — a heap-backed `ChunkLoader`, so a load benchmark measures the
  instance and not the page cache ([What the benchmarks establish](../what-the-benchmarks-establish.md) describes this trap for the region
  file benchmark). Model: the `BarrierLoader` in `FalcoInstanceLoadRaceTest`.
- `benchmark/support/InstanceFixtures` — builds `FalcoInstance` **and** `InstanceContainer` with the
  *same* `ChunkSupplier` (`FalcoChunk::new`) and the same loader. Otherwise two chunk types are
  compared along with the instances. Model: `ChunkSaveComparisonBenchmark`.
- `benchmark/support/InstanceEquivalence` — aborts the trial unless both sides produce the same block
  state and the same number of packets. Without it, "Falco does less" is indistinguishable from "Falco
  is faster". Model: `LightEngineComparisonBenchmark.verifyBothEnginesAgree()`.

No Minestom package is needed. The comparison path is `Instance#setBlock` and `loadChunk`, both public
and overridden on both sides — the package trick used by the light and region-file benchmarks does not
apply here.

### M1 — `ChunkMapComparisonBenchmark`

`ConcurrentHashMap<Long,Chunk>` against `Long2ObjectSyncMap<Chunk>`, in isolation, no server. Cheapest
first. `@Param loadedChunks = {64, 256, 1024, 4096}` (sync map promotion is O(n)), thread count via
`-t`, `@BenchmarkMode({AverageTime, SampleTime})`, always with `-prof gc`. This settles the boxing
question that [Why falco-instance exists](../why-falco-instance-exists.md) leaves open. It
measures `chunks` only — for `loadingChunks` the question is already decided in the negative, see
above.

### M2 — `BlockWriteComparisonBenchmark`

The core claim. `@State(Scope.Benchmark)`, `@Param contention = {DISJOINT, CLUSTERED, SHARED}`,
`@Param doBlockUpdates = {false, true}`, a `ThreadSlot` as `@State(Scope.Thread)` for target
coordinates (model: `RegionFileComparisonBenchmark`). Two traps, both already documented in the repo:

- **Blocks must alternate.** `FalcoInstance.java:425` returns immediately for an identical block at the
  same position within the same tick — a benchmark writing `STONE` twice measures a map lookup.
  `IncrementalVsFullBenchmark` already solves this with a toggle.
- **`SHARED` is the control, not the side case.** Writing into one chunk makes both sides take the
  chunk write lock; they must come out level. If they do not, the `DISJOINT` difference comes from
  something other than lock granularity.

The framing condition must appear as an axis: the number is worthless without "dispatcher threads = n"
and "cores = m" stated next to it.

### M3 — `ChunkLoadComparisonBenchmark` and the scaling series

`.falcoLoad` / `.minestomLoad` plus a load-unload cycle over the `MemoryChunkLoader`. The cycle is the
more important case because it produces the map writes. Then a documented run recipe `-t 1,2,4,8` —
the *shape* of the curve is the statement, not any single point.

The loader's own contention series is the lesson, and it cuts two ways. The same code produces
different-looking rows at 1, 2, 4 and 8 threads — but only the two-thread row survives the project's
own significance rules. At one thread the two intervals overlap and no ordering is resolvable; at
four and at eight threads Minestom's interval half-width exceeds its mean (`11 021 ± 16 470` and
`530 905 ± 1 928 261` µs/op), so those rows carry no factor at any precision and are evidence of lost
*predictability*, not of a ratio. An instance series has to be read the same way from the start. The
rows, their intervals and the rule that disqualifies them are in
[What a measurement here means](../what-a-measurement-here-means.md).

### M4 — percentiles instead of means

None of the 16 existing benchmark classes uses `Mode.SampleTime`; all 16 measure `AverageTime`. Under
lock contention the mean is the least useful statistic. M2 and M3 need p50/p90/p99/p999. In the load
test path, `SampleWindow` (in `falco-demo`) currently stops at `percentile95`.

### M5 — load test, only after M1–M4

A deterministic Cyano test (`InstanceContentionTest`) with platform threads against a threshold, plus
optionally a `--stack=falco-instance` mode in `falco-demo`. Today *both* demo stacks run on
`InstanceContainer`, because `FalcoInstance` cannot hold a `FalcoLightingChunk` — see
[B3](#b3--dissolve-the-falcoinstance--falcolightingchunk-exclusivity). A load test without prior micro
figures shows only *that* something differs, not *why*.

**Honest expected outcome, and it is a guess, not a forecast from data:** with one dispatcher thread
M2 will probably show no delta; with four or more and `DISJOINT`, the analyses expect one, and none
of them can say how large. The loader shows a shape of that kind — level on one thread, ahead on two
— but the loader is a different component with its own measured table, and it constrains what to
expect here only by analogy. Recording the guess in advance is the point: an expectation written down
before the benchmark exists is falsifiable, and one written afterwards is not.

## Measures

### Tier A — worth doing now: the mechanism is clear even though the gain is unmeasured

#### A1 — bound chunk loading and generation

In `FalcoInstance#retrieveChunk` (`:565-566`) and `#generateChunk` (`:880`), introduce an
instance-wide `Semaphore` (`Math.max(availableProcessors, 2)`, as in `FalcoAnvilLoader.java:160` and
`ChunkLightScheduler.java:169`). The permit is acquired *inside* the virtual thread, not before
starting it — otherwise the caller blocks, which is precisely why `ChunkLightScheduler` does it that
way.

*Gain (estimated):* bounds the number of concurrently live staging arrays (one `GenSection[]` per run
with up to 24 cloned palettes plus 24 `Int2ObjectOpenHashMap`, `:925-929`) and the number of blocked
carriers during file I/O. *Visible in:* p99 chunk load time under a join burst at high view distance
(M3), `gc.alloc.rate.norm`, peak thread count via JFR.

*Effort:* S. *Risk:* one real trap — `generateChunk` (`:880-882`) calls `loadChunk(...).join()` inside
its virtual thread. Acquiring the permit before the `join()` makes a waiting thread hold a permit while
waiting for another thread that needs one: self-deadlock under saturating fan-out. **The permit must be
acquired after the `join()`.**

The contradiction this resolves is stated in the repository itself: [Project
Status](../../contributing/project-status.md) records that file I/O does not unmount a virtual thread from its carrier
(JEP 444), so unbounded virtual threads over file work do not scale and must be bounded —
and `FalcoInstance.java:566` is the only place in the repository that does not follow that advice.

#### A2 — pull the placement rule out of the chunk write lock

Evaluate `getBlockPlacementRule` and `rule.blockPlace(placementState(...))` *before*
`chunk.lockWriteLock()` in `#writeBlock` (`:429-439`). Only `chunk.setBlock(...)` — and, until A5, the
timestamp — stay inside.

*Gain:* eliminates one of the two deadlock shapes above and shortens write-lock hold time by a registry
lookup plus a foreign call. *Visible in:* no number, but a test that hangs today — two threads, two
chunks, each with a rule reading the other chunk via `instance.getBlock`. **That test is the actual
value here and should be written before the change.**

*Effort:* S. *Risk:* behavioural change — a rule sees the state immediately before the commit rather
than during it. That is already true for the neighbour updates (`:441-443`), so the change is
consistent with a decision already taken. No Minestom contract breaks: `MS:instance/Chunk.java:95`
requires the write lock for `setBlock`, not for rule evaluation.

**What this does not fix, and that belongs in the docs:** `chunk.setBlock` calls
`handler.onDestroy`/`onPlace` under the write lock (`MS:instance/DynamicChunk.java:115-129`). That
lives in Minestom's chunk and cannot be removed from here. The handler-borne deadlock shape remains.
`FalcoInstance.java:441-442` should say so instead of promising a freedom that only covers neighbours.

#### A3 — close visibility gaps

Make `chunkSupplier` (`:211`) and `chunkLoader` (`:213`) `volatile`. Neither is `final` nor `volatile`
today, both are written from arbitrary threads via `setChunkSupplier` (`:803`) and `setChunkLoader`
(`:831`), and read from virtual loader threads (`:564`, `:663`, `:742`, `:757`, `:763`, `:769`). The
Javadoc explicitly permits the runtime swap (`:821-829`). No speed gain — a correctness gap closes.
Whether it ever bit: *unverified*. *Effort:* S. *Risk:* negligible.

#### A4 — override `getBlock` in `FalcoInstance`

`Instance#getBlock` (`MS:instance/Instance.java:714-717`) goes through a single shared `ChunkCache`
field (`:119`) whose `chunk` field is mutable and non-volatile. Override it with identical semantics
and no shared cache, exactly as `Instance#getBiome` (`:722-729`) already does: `getChunk` +
`lockReadLock` + `chunk.getBlock` + `unlockReadLock`, NPE on an unloaded chunk.

*Gain (estimated):* removes a process-wide shared cache line, written on every `getBlock` by every
thread, from the read-dominated path that Falco's parallel write model creates in the first place. With
two or more threads in different chunks that cache practically never hits and still writes. What is
*proven* is only that a JMM data race disappears — it does not produce wrong results, because
`MS:utils/chunk/ChunkCache.java:29-45` reads the field into a local and validates it against
coordinates afterwards.

*Visible in:* M2 with `doBlockUpdates=true` at ≥2 threads, and `-prof perfnorm` (cache misses).
*Effort:* S. *Risk:* low while semantics stay identical.

#### A5 — defuse `lastBlockChangeTime`

`:431` writes an instance-wide `volatile long` (`:217`) on *every* block write, inside the chunk write
lock where it protects nothing. Two steps: pull the store out of the lock, then either switch to a
`VarHandle` opaque store or — cleaner — keep the timestamp per chunk and compute
`getLastBlockChangeTime()` (`:1083`) as a maximum.

*Gain (estimated):* removes a shared cache line written by every writer plus a `System.nanoTime()` call
from the hottest write path. The field's value is small: only the getter reads it, and Minestom's
batches do not refresh it for a foreign instance anyway (`:86-88`, `:1077-1080`). *Visible in:* M2 with
`DISJOINT` at ≥2 threads. *Effort:* S to M. *Risk:* the documented semantics ("only as a delta against
another reading of the same clock", `:1075-1076`) survive both variants.

#### A6 — allocate event objects behind `hasListener()`

`:451` (every block write), `:616` (every chunk load) and `:740` (every unload) allocate the event
*before* checking for listeners. `MS:event/ListenerHandle.java:24-30` offers `hasListener()` for
exactly this. *Gain (estimated):* one allocation and one map lookup less per block write on a server
without listeners — and it stands next to the 289-chunk viewer scan, so without M2 it cannot be claimed
to be visible. *Effort:* S. *Risk:* low; the handle binds to static process state, so it must be
per-instance and lazy, not a `static final` loaded before server init.

#### A7 — documentation that actively misleads

No runtime gain, but the precondition for the next optimisation decision not resting on a false
premise again. Two of the four source analyses went wrong on exactly these lines.

| Location | What is wrong |
|---|---|
| `FalcoInstance.java:150-155` | "copy-on-write map" — it is a `sync.Map` port with a lock-free, unboxed read path. The stated reason for choosing `ConcurrentHashMap` does not hold. |
| `FalcoInstance.java:441-442` | promises freedom from the two-chunk lock shape but delivers it only for neighbour updates; rule and handler run under the lock. |
| [Why falco-instance exists](../why-falco-instance-exists.md), *What this module deliberately does not attempt* | "`generateChunk` always throws" — stale; at `ca79507` `setGenerator` stores the generator, `generator()` returns it and `generateChunk` runs it (`:840-858`, `:875-895`, `:922-953`), and it is tested. The README says the same thing in one place and the opposite in another. |
| `FalcoChunk.java:126` | copies `tickableMap`, which `MS:instance/DynamicChunk.java:236-241` does not — a silent correction of a Minestom defect, documented nowhere. |
| `FalcoInstance.java:174` | should record that `loadingChunks` is *not* interchangeable with a map whose `compute` is CAS-based. |

### Tier B — worth doing, but needs a measurement or a rebuild first

#### B1 — unbox the chunk map (`chunks` only)

*Precondition:* M1. `:156` to a primitive long structure; `:174` must stay a `ConcurrentHashMap`.
*Risk: high* — `chunks` is mutated *inside* `loadingChunks.compute` (`:553`, `:642`, `:731`). A
structure that locks internally introduces a new lock order. The current order is globally consistent
`loadingChunks → chunks` and therefore deadlock-free; that must not tip.

#### B2 — pre-warm the chunk packet on the load thread

In `#completeLoad` (`:590-617`), materialise the packet once:
`if (falcoChunk.getFullDataPacket() instanceof CachedPacket cached) cached.body(ConnectionState.PLAY);`

*Gain (estimated):* moves heightmap computation, section serialisation and compression off the
requesting player's tick thread (`MS:entity/Player.java:794` → `getFullDataPacket()` →
`MS:network/packet/server/CachedPacket.java:50-58`) onto the already-parallel load thread. The write
lock `createChunkPacket` takes (`MS:instance/DynamicChunk.java:258-263`) is uncontended there because
the chunk is not published yet. **Of all proposals, the most plausible candidate for visible tick-time
impact — and it is not measured.** Feasibility is verified: `CachedPacket.body(ConnectionState)` is
public (`:41-44`) and `ServerFlag.CACHED_PACKET` defaults to on (`MS:ServerFlag.java:50`).

*Risks:* `CachedPacket` is `@ApiStatus.Internal`, so the `instanceof` guard is a deliberate coupling and
belongs in the Javadoc; the cache hangs off a `SoftReference`; and **chunks that are never sent pay for
it**, so the pre-warm must be switchable or a bulk preload turns it into a regression. Without a light
solution the first light pass discards the work immediately (`FalcoLightingChunk.java:157`).

#### B3 — dissolve the `FalcoInstance` / `FalcoLightingChunk` exclusivity

`#requireFalcoChunk` (`:690-695`) demands the *class* `FalcoChunk`; `FalcoLightingChunk.java:80` is
`extends DynamicChunk implements LightUpdateAware`. The combination fails on the first chunk load.
[Project Status](../../contributing/project-status.md) records this under *Two things this project ships that cannot be
used together* and offers only "a chunk that is both", which means a module dependency
`falco-light → falco-instance` and gives up the documented independence of the modules.

**The way out none of the source analyses found:** `Chunk#onLoad()` and `Chunk#unload()` are `protected`
(`MS:instance/Chunk.java:273`, `:308`, re-checked at `ca79507`) — *any subclass in any package may
call them on `this`*. [Project Status](../../contributing/project-status.md) calls these two hooks package-private, which is
the premise that made the exclusivity look unavoidable; they are not, and correcting that is what
opens this route.
`FalcoLightingChunk` already overrides `onLoad` (`:145`). So it suffices to publish an interface in
`falco-instance` (working name `ManagedChunk`, with `markLoaded()`/`markUnloaded()`), have `FalcoChunk`
implement it, and have `requireFalcoChunk` check for it. Since `writeBlock` also needs the six-argument
`setBlock`, first public on `DynamicChunk` (`MS:instance/DynamicChunk.java:75`), the check becomes "is a
`DynamicChunk` **and** a `ManagedChunk`".

A consumer wanting both then writes ten lines in their *own* package:

```java
public final class LightingFalcoChunk extends FalcoLightingChunk implements ManagedChunk {
    public void markLoaded()   { onLoad(); }
    public void markUnloaded() { unload(); }
}
```

without `falco-light` depending on `falco-instance` and without the module boundary falling.

*Gain:* no direct runtime gain — but B4 and the full value of B2 only become reachable through it, and a
documented runtime trap disappears. *Effort:* M — five call sites of `requireFalcoChunk` (`:349`,
`:357`, `:379`, `:598`, `:725`) and three signatures must carry two types instead of one. *Risk:* low;
the guarantee that must not break is that `Chunk#isLoaded()` keeps telling the truth
(`MS:instance/Chunk.java:266-268`). The interface checks the same capability, just not via class
identity — it gets weaker if a consumer implements it without actually calling the hooks, which cannot
be enforced and belongs in the Javadoc.

#### B4 — move lighting into the load path

A `Consumer<Chunk>` hook in `completeLoad` between the load step (`:593-598`) and `publishChunk`
(`:604`), which a `ChunkLightScheduler` can occupy. Today a freshly loaded chunk registers via `onLoad`,
waits up to a tick for `ChunkLightScheduler#onTick`, is computed, and then calls `invalidate()`, which
discards the packet.

*Gain (estimated):* removes the one-tick delay and the duplicated serialisation work. *Effort:* M.
*Depends on:* B3 mandatory, B2 sensibly together.

***The highest risk in this catalogue, and the repository already names it.***
[How the concurrency design works](../how-the-concurrency-design-works.md) records as an open issue that two threads lighting
overlapping 3×3 neighbourhoods can commit a result from an already-stale reading, "which shows up as a
seam rather than as an error". Lighting an unpublished chunk while its neighbours load in parallel
sharpens exactly that case.
**Without a position reservation through the scheduler's `inFlight` set this measure produces visible
light seams. Do not implement it without that safeguard.**

#### B5 — `currentlyChangingBlocks` per chunk instead of per instance

`:209` is an instance-wide `ConcurrentHashMap<BlockVec, Block>`; every block write allocates a
`BlockVec` (`:423`) plus a `get` and a `put` (`:425-426`), cleared each tick (`:1113`). As a per-chunk
guard over `chunkBlockIndex` it would be allocation-free. *Why B and not A:* it stands next to the
289-chunk viewer scan, so measurability is unproven — and it is a correctness mechanism (recursion
guard), so it is test-bound. *Effort:* M. *Risk:* medium; a handler destroying its own block must still
be aborted.

#### B6 — `applyGenerator` stages without a read lock

`:923-929` clones the live palettes without `lockReadLock()`. Correct for the `createChunk` path
(`:669`) where the chunk is unpublished — but on the `generateChunk` path (`:887`) the chunk **is
published and writable by other threads**. A concurrent `setBlock` during the clone races on
`PaletteImpl`, which has no synchronisation at all. Fix: `lockReadLock()` around the staging loop on the
`generateChunk` path. *Why B and not A:* it touches the core promise at `:914-917` ("the write lock is
held only for the commit"), needs the Javadoc pulled along, and needs a test running `generateChunk`
concurrently with `setBlock` — which does not exist today. *Gain:* correctness, not speed.

### Tier C — interesting, but speculative or only on demonstrated need

| # | Measure | Why C |
|---|---|---|
| C1 | Upstream PR to Minestom: make `ThreadProvider<Chunk>` externally configurable | `MS:ServerProcessImpl.java:73`/`:101` hard-wires `ThreadProvider.counter()`; the interface is already `@Experimental` and a `@FunctionalInterface`. A region-based mapping would then be trivial and bring locality. That is a Minestom ticket, not a feature here, and the gain is unproven. |
| C2 | A Falco batch entry point (several blocks, one `MultiBlockChangePacket`) | `Instance#setBlock` is `void` and per block, so batching needs an *additional* API. It would trigger the viewer scan per section instead of per block — the only way to reduce the largest cost item at all. But: new API, new semantics, and `AbsoluteBlockBatch` already exists as a separate path. |
| C3 | Ease the viewer scan | Not reachable: the `EntityTracker` is created privately in `MS:instance/Instance.java:116`. Upstream only. |
| C4 | Cheaper `FalcoChunk#copy` (`:123-126`) | Cold path. `MS:instance/DynamicChunk.java:237` does the same. |
| C5 | `unregister` without a `List.copyOf` per pass (`:298-307`) | Shutdown path. The copy is also the reason the pass count stays finite. |
| C6 | Guard `unregister` against in-flight block writes | Minestom serialises via `synchronized (instance)` (`MS:instance/InstanceManager.java:116`), Falco does not. Today it warns instead of preventing (`:309-310`). Shutdown path, tolerable. |

## What we advise against

Ideas that sound plausible but do not hold here.

1. **Swapping `loadingChunks` for `Long2ObjectSyncMap`.** See above — the most dangerous proposal the
   research produced, and it was labelled effort "S", risk "low".
2. **Rebuilding the chunk map before measuring it.** Three of four analyses ranked `Long` boxing first
   or second. `MS:utils/chunk/ChunkCache.java:31-36` bypasses the lookup entirely under locality, and
   escape analysis may eliminate the boxing. [Rationale: Instances and
   Chunks](../why-falco-instance-exists.md) discards the only number ever quoted for this — the 19.8×
   from [Research: Instance Container](instance-container.md) — as non-reproducible. → M1
   first.
3. **Hoping for more parallelism in the tick.** Ticking does not happen in the instance, and the tick
   barrier (`MS:ServerProcessImpl.java:318`) prevents any overlap. Tick time is a *control* metric for
   this module ("does not get worse"), not one where an advantage is expected. Presenting it as
   evidence would be dishonest.
4. **Promising an advantage on a default configuration.** With one dispatcher thread, player-driven
   block writes are serialised anyway. A number without the dispatcher thread count beside it misleads.
5. **Treating off-tick `Entity#remove` on unload as a defect.** `MS:instance/InstanceContainer.java:272-286`
   does the same, and `synchronized` binds nothing to the tick thread. The real open risk is the
   *concurrency* of two unloads — a test assignment, not a measure.
6. **"Fixing" the recursion guard because it is not atomic.** The non-atomicity makes it more permeable,
   not stricter, and its purpose (intra-thread recursion) is untouched. A `compute` instead of
   `get`+`put` would add a bin lock on the hottest path to solve a problem that does not exist.
7. **`FalcoLightingChunk extends FalcoChunk` as the route to light integration.** The obvious solution
   and the most expensive: it costs a `falco-light → falco-instance` module dependency and inverts the
   documented independence. B3 achieves the same through an interface plus ten lines at the consumer.

Also struck as not applicable to this code: `StampedLock` for the chunk lookup (there is no lock there —
`getChunk` is a `ConcurrentHashMap#get`); `StampedLock` inside the chunk (`MS:instance/Chunk.java:49` is
`private final` and the lock accessors are `final`); additional striping (over an already striped
structure); Folia-style regionalisation (the dispatcher is `final` in the process); `StructuredTaskScope`
and the Vector API (preview/incubator, would force a JVM flag on every consumer of the published
artefacts); `ScopedValue` (there is no `ThreadLocal` to replace — zero hits); a custom palette
(`Palette` is `sealed`); false sharing between chunk states (object graph, not array-of-structs).

## Open questions

Not verifiable from the code. The first entry is not one item among six — it is the state of the
whole document.

- **Every performance gain in this document, without exception.** Not one figure, ranking, tier or
  "gain" line on this page rests on a measurement, and no amount of source reading can supply one.
  Instance benchmarks exist since 2026-08-03 but have never been run as a baseline, so this entry is
  unchanged in substance: the instrument is no longer missing, the run still is.
- Whether a shipped vanilla `BlockPlacementRule` or `BlockHandler` reads the world, making the deadlock
  shape reachable.
- Whether the `Long` boxing in `chunks.get(index)` survives escape analysis.
- Whether `Entity#remove` tolerates two concurrent runs.
- The virtual thread scheduler's carrier ceiling — JDK general knowledge, not checkable here.
- The actual effect of B2 on tick time. There is no benchmark for the send path.

Not examined: `falco-light` beyond `FalcoLightingChunk` and the scheduler's semaphore lines;
`falco-anvil` beyond its semaphore and `supportsParallel*` lines; the `falco-instance` test classes
(taken from the source analyses). The benchmark and status documentation was read only by targeted
grep — at the time of the investigation those still lived in the repository as `docs/benchmarks.md`
and `STATUS.md`; they are now [What the benchmarks establish](../what-the-benchmarks-establish.md) and [Project Status](../../contributing/project-status.md).

## Suggested order

1. **A7** (docs) and **A3** (volatile) — about an hour, removes two false premises and a visibility gap.
2. **A1** (bound) and **A5** (`lastBlockChangeTime`) — each under a day, both pure improvements with no
   contract risk.
3. **A2** (rule out of the lock), with the deadlock test written first. The test is the actual value.
4. **M0 + M1 + M2** — the measurement infrastructure. Nothing beyond this point is decidable without it.
5. **A4** and **A6** alongside 4; M2 measures their effect.
6. **B3 → B2 → B4** as *one* project. Individually each delivers less than all three together, and B4
   only with the neighbourhood safeguard.
7. **B1** and **B5** only if M1 and M2 justify them.
8. **B6** when convenient, together with the missing test.

Step 4 is the load-bearing one. Everything before it is a correctness or documentation fix that
stands on its own; everything after it is conditional on numbers that do not exist yet. Until M0–M2
are built, the honest statement about this module's speed remains the one the repository already
makes: **no speed gain is claimed and none is measured.**

## References

- [`FalcoInstance`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java),
  [`FalcoChunk`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java),
  [`FalcoLightingChunk`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-light/src/main/java/net/onelitefeather/falco/light/FalcoLightingChunk.java)
  — every bare `:nnn` reference on this page is into `FalcoInstance` unless another file is named.
  Re-checked at `ca79507`.
- Minestom `2026.06.20-26.1.2` — every `MS:` reference, relative to `net/minestom/server/`.
- `space.vectrix.flare:flare-fastutil:2.0.1`, `Long2ObjectSyncMapImpl.java:251`, `:487-495`,
  `:576-585` — the CAS `compute` that makes the `loadingChunks` swap unsafe.
- What the module is and what it refuses to do: [Rationale: Instances and
  Chunks](../why-falco-instance-exists.md). Why the instance was built at all, and the dispatcher
  finding this page starts from: [Research: Instance Container](instance-container.md).
- What a Falco benchmark has to carry before its number means anything:
  [What a measurement here means](../what-a-measurement-here-means.md). How to run the existing suite:
  [What the benchmarks establish](../what-the-benchmarks-establish.md).
