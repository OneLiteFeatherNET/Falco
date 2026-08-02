# Design: a chunk, an instance and a shared instance of Falco's own

Status: proposed · 2026-08-01

Requirements follow the OneLiteFeather requirement-engineering standard: user stories per stage,
acceptance criteria in EARS syntax, MoSCoW priorities. The standard normally lives in Outline; this
one lives in the repository because every requirement names a Java type that lives here too.

## 1. Context

`falco-instance` today is `FalcoInstance extends Instance` (1 119 lines) and
`FalcoChunk extends DynamicChunk` (129 lines). The chunk **declares no field of its own** — it exists
only to re-expose two `protected` lifecycle hooks and to fix a `copy` that forgets `tickableMap`.
Every byte and every cycle a chunk costs therefore sits outside Falco's control, and the README says
so: *no speed gain is claimed and none is measured*.

Two structural consequences follow. `FalcoChunk` and `FalcoLightingChunk` both extend `DynamicChunk`
and are therefore **not combinable** — a chunk that wants Falco's lifecycle *and* Falco's light
cannot be built, only copied. And `FalcoInstance` cannot carry a `SharedInstance`, because
`SharedInstance:22` types its field on the concrete `InstanceContainer`.

This design replaces inheritance with composition at the chunk, gives the instance a container of its
own, and builds a shared instance that repairs the defects of the one Minestom ships.

## 2. What the measurements establish

This section is the spine of the design. Every architectural choice below points at a row here.
Figures marked *scouting* come from a `-Pjmh.quick` run (`fork 1`, 3 iterations) and carry no usable
half-width; they establish direction, not magnitude. The full run is pending.

| # | Finding | Figure | Method |
|---|---|---|---|
| M1 | A fresh chunk, before a single block is set | **6 848 B, 192 objects** | JOL |
| M2 | — of which the section list and everything below it | **5 128 B, 74.9 %** | JOL |
| M3 | — of which both heightmaps, two `short[256]` | **1 120 B, 16.4 %** | JOL |
| M4 | — of which 48 `AtomicBoolean` | 768 B, 11.2 % | JOL |
| M5 | `FalcoChunk` against `DynamicChunk`, all 15 fill variants | **delta 0 B** | JOL |
| M6 | Sharing empty sections instead of allocating 24 | **2 104 against 7 096 B/op, −70 %** | JMH, ±1 B |
| M7 | A generated chunk stays at 15 bpe direct; `optimize()` has no caller in Minestom's main tree | **203 840 against 84 800 B**, factor 2.4 | JOL |
| M8 | Empty section share of a *generated overworld*, finished chunks only | **62.24 %** | census, 441 chunks |
| M9 | — the same world counting unfinished chunks too | 87.48 % | census |
| M10 | — a void hub world, for contrast | 99.61 % | census |
| M11 | Flyweight saving at the measured share | **2 911 B/chunk, 11.4 MB over 4 096** | JOL |
| M12 | Palette break-even, indirect against direct | **between 192 and 224 entries** | JOL |
| M13 | A sorted `int[]` reverse index against fastutil | **16× slower** at 256 entries | JMH, scouting |
| M14 | Viewer cache leak: entries per chunk construction on an `InstanceContainer` | **1, linear, never removed** | unit test |
| M15 | — its cost per copy | 257 B/op, constant across content | JMH, ±0.6 B |
| M16 | A full chunk resend at view distance 10 | **765 ms, 86.5 MB** | JMH, scouting |
| M17 | `setBlock` under 8 threads, disjoint chunks / with a block handler | 6.2× / 7.3× | JMH, scouting |

Three of these corrected the research that preceded them, and the corrections are the reason the
figures are here rather than the estimates: the flyweight was assumed to act at a 90 % empty share
and acts at 62 % (M8), the 48 `AtomicBoolean` were assumed to be the largest avoidable item and are
a fifth of the sections (M4 against M2), and the sorted reverse index was proposed as an improvement
and is a regression (M13).

## 3. Goals and non-goals

**Goals**

* A chunk that owns its storage, so that M2, M3 and M7 become reachable at all.
* A chunk that is combinable with Falco's light instead of mutually exclusive with it.
* An instance that keeps the lock granularity it already has (M17) and does not inherit M14.
* A shared instance with per-instance state, repairing the aliasing defects of Minestom's.
* Splitting `FalcoInstance` along its responsibilities, so that its lifecycle steps become testable
  and measurable individually.

**Non-goals**

* **Replacing `Palette`.** `public sealed interface Palette permits PaletteImpl` is closed by the
  verifier, and M12 says Minestom's choice of representation is sound anyway.
* **Removing the instance monitor from a container that carries a shared instance.** See §4.4 — it
  cannot be done safely, and the reason is a `private` modifier in a foreign class.
* **Off-heap section storage.** A footprint trade, not a CPU gain; `Arena.ofShared().close()` is a
  global handshake and a chunk is touched by loader, tick and network threads.
* **Any preview or incubator language feature.** Vector API is still incubating and would force
  `--add-modules` on every consumer of a published library; structured concurrency and stable values
  are preview; value classes do not exist in 25 at all.

## 4. Architecture

### 4.1 Bridge: the chunk stops inheriting its storage

A new chunk type extends `Chunk` — `public abstract`, with `getSections()` and `getSection(int)`
abstract — and holds a `BlockStorage` as its implementation side. Lifecycle, viewers and packet
building stay on the abstraction side; the memory layout becomes replaceable without touching a chunk
class. Two inheritance branches that could not be combined become two parts that compose.

Minestom's `Section` and `PaletteImpl` are **not** replaced. They are materialised lazily at the three
boundaries that demand them: packet serialisation, the light engine, and the anvil writer. The saving
of M6 lives inside those boundaries. The cost is stated rather than hidden: any caller of
`getSection` or `getSections` forces materialisation, so the saving is one on the block accessor
path, not one that survives an arbitrary caller reaching into the chunk.

### 4.2 Storage: flyweight, and the item nobody counted

Empty sections point at one process-wide `EMPTY` and materialise on first write (M6, M11). The
materialisation allocates a fresh section rather than cloning the flyweight, because `Section#clone`
rebuilds light through `skyLight.set(...)` and would install valid-looking light on a section that has
none.

Then, in order of measured size: heightmaps allocated lazily (M3 — the second largest item, and one
the preceding research never listed), the 48 flags folded into one packed field (M4), `tickableMap`
dropped as a subset of `entries` with identical keys and identical references, and the chunk `UUID`
removed — `grep getIdentifier` finds only its declaration in all of Minestom.

Larger than all of those together: **calling `optimize()` once after generation** (M7).

### 4.3 Instance: keep what works, fix what leaks

`FalcoInstance` already holds the chunk write lock across the write and runs `updateNeighbours`,
`sendPacketToViewers` and `EventDispatcher.call` after releasing it, where `InstanceContainer` holds
the monitor of the whole instance across all three and across arbitrary `BlockHandler` code (M17). It
already uses a `ConcurrentHashMap` where Minestom's `changingBlockLock` guards nothing — `clear()`
from `tick()` races `put`/`get` under a different lock. Both stay.

Added: the viewer cache entry is **removed on unload**. Falco escapes the growth of M14 only because
it is not an `InstanceContainer` and receives the `List.of()` singleton; it never clears the entry
either, and one entry per chunk position survives for the life of the process.

Also added, and explicitly **not** sold as a performance change: a primitive chunk index map instead
of `ConcurrentHashMap<Long, Chunk>`. The boxing is real; the cost is not established, because
`getChunk` is reached on chunk change rather than per block — the `ChunkCache` memoises in between.

`FalcoInstance` is split behind a thin facade into `ChunkRegistry`, `ChunkLifecycle`, `BlockWriter`
and `ChunkPersistence`. The concrete gain is testability: `publishChunk` and `completeLoad` are
`private` today and reachable only through a full load. The facade must hold no state of its own, or
it is the same class with delegation in front of it.

### 4.4 Shared instance: built here, and honest about one wall

`FalcoSharedInstance extends SharedInstance`. Nothing in `SharedInstance` is `final`, so every
delegating method can be replaced; and `areLinked` compares `getInstanceContainer()` rather than
testing for a specific class, so a subclass keeps the fast path that avoids a full resend. M16 is
what makes that worth insisting on: 765 ms and 86.5 MB against zero.

Repaired: `setGenerator`, `setChunkSupplier` and `enableAutoChunkLoad` keep per-instance state instead
of aliasing the container, and `saveInstance` persists this instance's tags instead of silently
persisting the container's (`InstanceContainer:293` passes `this`).

**The wall:** the block owner must be an `InstanceContainer`, and its monitor cannot be removed.
`UNSAFE_setBlock` is `private synchronized` and is called from four places — `:135` from `setBlock`,
`:223`, `:250`, and `:756` from the neighbour update. Overriding `setBlock` bypasses one of them and
leaves the other three on the private path: two write paths over the same data, one synchronised and
one not. That is a race of our own making. Shared worlds therefore pay the monitor and keep the
chunk-level gains; a world that needs throughput uses `FalcoInstance` without sharing.

**This is the carve-out for NFR-006, and it is stated here because the two would otherwise
contradict each other.** NFR-006 requires a chunk lock rather than an instance monitor. It binds the
instances this project implements. It cannot bind an `InstanceContainer`, whose monitor is private
and whose write paths are not reachable from outside — demanding it there would make stage 4
unbuildable rather than better.

### 4.5 Lifecycle: listeners instead of template methods

`ChunkLifecycleListener` with `onLoad`, `onPublish`, `onTick` and `onUnload` replaces the four hooks
`FalcoLightingChunk` occupies by inheritance. Today a second extension beside light is impossible.
The event is built lazily, so that zero listeners cost nothing.

## 5. Stages

Storage first, because §2 puts nearly every byte there, and shared last, because it depends most on
the storage model.

| Stage | Content | Depends on |
|---|---|---|
| 1 | `BlockStorage` and the bridge chunk, without the flyweight | — |
| 2 | Flyweight, lazy heightmaps, packed flags, `optimize()` after generation | 1 |
| 3 | Facade split of `FalcoInstance`, viewer cache cleanup, lifecycle listeners | 1 |
| 4 | `FalcoSharedInstance` | **US-1.05** |

Stage 4 rests on US-1.05 rather than on stage 3: a shared instance needs its block owner to be an
`InstanceContainer` (§4.4), so the Falco chunk has to work inside one before a shared instance can be
built over it. That is why US-1.05 is a Must and not the convenience it reads like.

**Two corrections, made while planning stage 1.** Both were requirements placed in a stage that
cannot satisfy them, and both moved rather than being weakened.

*Combining the lifecycle with Falco's light* was a stage 1 story. The bridge alone does not achieve
it, because `FalcoLightingChunk` still extends `DynamicChunk` and the combination needs the listener
stage 3 introduces. It is now US-3.06.

*Producing Minestom's types only at the boundary* was also a stage 1 story, and stage 1 does the
opposite on purpose: its storage holds sections eagerly so that the chunk measures identical to
`DynamicChunk` and the bridge is proven free before anything moves. Holding none is what the
flyweight buys, so the requirement is now US-2.09.

Stage 1 buys the seam the later stages need. It delivers no saving, and it must not appear to.

## 6. User stories

### Stage 1 — storage behind a bridge

| ID | Story | Acceptance criterion (EARS) | API | Priority |
|---|---|---|---|---|
| US-1.01 | As a developer I want a chunk that owns its storage, so that its memory layout is mine to change | While a chunk is loaded, shall every block read and write reach `BlockStorage` rather than an inherited section list | `BlockStorage` | Must |
| US-1.03 | As a developer I want equivalence proven against `DynamicChunk`, so that a faster number never comes from computing something else | When a comparison benchmark starts, shall it verify all `16·16·16·sectionCount` positions and both heightmaps and abort the trial on any difference | `MinestomChunks#assertSameBlocks` | Must |
| US-1.05 | As an operator I want the chunk usable inside a plain `InstanceContainer`, so that shared worlds are possible at all | When `setChunkSupplier` is given the Falco chunk, shall a container load and unload it correctly | `InstanceContainer#setChunkSupplier` | Must |

### Stage 2 — the memory that §2 measured

| ID | Story | Acceptance criterion (EARS) | API | Priority |
|---|---|---|---|---|
| US-2.01 | As an operator I want empty sections shared, so that a world costs what its terrain costs | While a section holds nothing but air, shall the chunk hold no section object of its own for it | `BlockStorage` | Must |
| US-2.02 | As an operator I want the first write to a shared section to be affordable | When a block is written into a shared empty section, shall the chunk materialise exactly that one section and leave the others shared | `BlockStorage` | Must |
| US-2.03 | As an operator I want generated chunks to shrink, so that generation does not cost 2.4× forever | When a generator has finished a chunk, shall the instance optimise its palettes before the chunk is published | `Palette#optimize` | Must |
| US-2.04 | As a developer I want heightmaps allocated on demand | While no heightmap has been requested or refreshed, shall the chunk hold no heightmap array | `Chunk#motionBlockingHeightmap` | Should |
| US-2.05 | As a developer I want the flags packed | While a chunk exists, shall it hold at most one object carrying the per-section send flags | — | Should |
| US-2.06 | As a developer I want the duplicate tickable map gone | When a block with a handler is placed or removed, shall exactly one map be updated | — | Should |
| US-2.07 | As a developer I want reading an empty section to be no slower than today | When a read reaches a shared empty section, shall it return without touching a palette | `BlockStorage` | Must |
| US-2.09 | As a developer I want Minestom's types produced only at the boundary, so that the saving is not undone internally | When `getSection` is called, shall the chunk materialise a Minestom `Section` and, while it is not called, shall it hold none for an empty section | `Chunk#getSection` | Must |
| US-2.08 | As a developer I want the chunk `UUID` gone, since nothing reads it | While a chunk exists, shall it hold no identifier that no caller consumes | `Chunk#getIdentifier` | Could |

### Stage 3 — instance, structure and the leak

| ID | Story | Acceptance criterion (EARS) | API | Priority |
|---|---|---|---|---|
| US-3.01 | As an operator I want unloading to leave nothing behind, so that a long-running server does not grow | When a chunk is unloaded, shall its viewer cache entry be removed | `EntityTracker#viewable` | Must |
| US-3.02 | As a developer I want the instance split along its responsibilities, so that its steps can be tested one at a time | When a chunk is published, shall that step be reachable and assertable without driving a full load | `ChunkLifecycle` | Must |
| US-3.03 | As a developer I want more than one lifecycle extension | When two listeners are registered, shall both be notified on every transition | `ChunkLifecycleListener` | Must |
| US-3.06 | As a developer I want the chunk combinable with Falco's light, so that I do not have to choose | When both the lifecycle and the light extension are installed, shall a single chunk instance serve both | `ChunkLifecycleListener` | Must |
| US-3.04 | As a developer I want no cost when nothing listens | While no listener is registered, shall a lifecycle transition allocate nothing | `ChunkLifecycleListener` | Should |
| US-3.05 | As a developer I want the chunk index unboxed | When a chunk is looked up, shall no `Long` be allocated | — | Could |

### Stage 4 — shared instance

| ID | Story | Acceptance criterion (EARS) | API | Priority |
|---|---|---|---|---|
| US-4.01 | As an operator I want the resend fast path kept, since a resend costs 765 ms | When a player moves between a shared instance and its container, shall `areLinked` report them linked | `SharedInstance#areLinked` | Must |
| US-4.02 | As a developer I want per-instance generators, so that one shared world does not reconfigure another | When `setGenerator` is called on a shared instance, shall no other instance observe the change | `Instance#setGenerator` | Must |
| US-4.03 | As an operator I want my tags persisted | When `saveInstance` is called on a shared instance, shall that instance's tags be written | `Instance#saveInstance` | Must |
| US-4.04 | As a developer I want the monitor limitation documented rather than worked around | While a shared instance is in use, shall the documentation state that writes serialise on the container | — | Must |

## 7. Non-functional requirements

| ID | Category | Requirement (EARS) | Priority |
|---|---|---|---|
| NFR-001 | Compatibility | The modules shall compile and run against the pinned Minestom version without reflection, `--add-opens` or an open module. | Must |
| NFR-002 | Compatibility | The modules shall use only language and JDK features that are final in Java 25; no preview and no incubator feature shall be required to build or run. | Must |
| NFR-003 | Measurement | If a performance claim is published, then shall a JMH or JOL measurement in this repository support it, stated with its conditions. | Must |
| NFR-004 | Measurement | While a comparison benchmark runs, shall it fail rather than report a number if the two sides disagree on their result. | Must |
| NFR-005 | Correctness | When a chunk read fails, shall the failure reach the caller instead of being reported as an absent chunk. | Must |
| NFR-006 | Concurrency | While a block is written through an instance **this project implements**, shall the lock held be the lock of the chunk it touches, not a monitor over the instance. See §4.4 — it cannot bind a Minestom `InstanceContainer`, whose write paths are private. | Must |
| NFR-007 | Memory | The chunk shall allocate no object per block read on any path. | Must |
| NFR-008 | Operations | The chunk shall not require `-XX:+UseCompactObjectHeaders`; where the flag helps, the gain shall be stated per class and measured, never as a percentage. | Should |
| NFR-009 | API | Every new public type shall carry `@ApiStatus.Experimental` while the module is experimental. | Must |

## 8. Open questions and risks

| Question / risk | Status |
|---|---|
| The full JMH run is pending; M13, M16 and M17 rest on scouting figures without usable half-widths. Direction is established, magnitude is not. | open |
| M8 rests on 441 finished chunks around one spawn. An ocean or a mountain range would give a different share, and the flyweight's value moves with it. | open |
| Materialisation at the three boundaries may undo the saving for workloads that call `getSection` often. No workload has been measured for how often that happens. | open |
| `optimize()` after generation costs time that has not been measured against the generation itself. | open |
| Whether the facade split can stay thin, or whether it re-accumulates state, can only be judged once written. | open |

## 9. Acceptance criteria

- [ ] A chunk exists that holds its own storage and passes position-by-position equivalence against `DynamicChunk`
- [ ] Falco's lifecycle and Falco's light are installed on one chunk instance at the same time
- [ ] A JOL measurement shows the footprint of a chunk at the measured empty share, with and without compact object headers
- [ ] The viewer cache of an instance does not grow across a load/unload cycle
- [ ] `publishChunk` and `completeLoad` are reachable in a test without driving a full load
- [ ] A shared instance keeps its own generator, chunk supplier and tags, and `areLinked` reports it linked
- [ ] Every figure quoted in the README or the wiki names the benchmark that produced it and the configuration it ran under
