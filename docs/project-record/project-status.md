# Status — Anvil chunk loader and light engine

The complete record of what was built, what was measured, what was measured and then rejected, and
what is still open. This is the evidence base the shorter pages summarise; the measured tables it
rests on are [Measured results](../reference/measured-results.md), where every table carries the benchmark class and
method it came from, its parameters and the configuration of the run that produced it, so that a row
can be traced to its source without guessing. It deliberately explains nothing — how the loader and
the engine work is [How the Anvil loader is built](../explanation/how-the-anvil-loader-is-built.md) and [How the light engine works](../explanation/how-the-light-engine-works.md), and how
the project is built and released is [Contributing](../contributing/README.md) — and it is written for someone
auditing the claims rather than for someone using the library.

**Where a measured table appears on more than one page, the copy under
[Measured](../reference/measured-results.md#measured) is the one that is right**, and a correction is made there
first and propagated outwards. The exception is stated on the page that holds it:
[How the Anvil loader is built](../explanation/how-the-anvil-loader-is-built.md) publishes two runs of the loader table that appear nowhere
else. The same holds for the count
of cross-run repeats, which is stated under
[Against the engine Minestom ships with](../reference/measured-results.md#against-the-engine-minestom-ships-with).

> **Read this before quoting a number.** Everything measured here comes from *one* machine, on *one*
> JVM, under *one* load, and none of it is an absolute statement about either implementation. What
> the `±` covers is defined once in [What a measurement here means](../explanation/what-a-measurement-here-means.md); how to re-run
> any of it is in [What the benchmarks establish](../explanation/what-the-benchmarks-establish.md).

`./gradlew build` is green. The test counts behind that, and the command that reproduces them, are
under [What is in the branch](#what-is-in-the-branch).

Everything here is experimental and opt-in. Nothing takes effect in a server that does not construct
a `FalcoAnvilLoader`, a `FalcoInstance`, or reach the light engine either by calling a
`ChunkLightService` itself or by handing an instance the chunk supplier of a `ChunkLightScheduler`.

Both parts were developed inside [Aves](https://github.com/OneLiteFeatherNET/Aves), OneLiteFeather's
utility library, and moved here once it was clear they are server infrastructure rather than
utilities. The history of that development stayed behind; what was learned along the way is written
down in this document and in [Research](research/README.md).

## How to read this page

Four kinds of statement appear here and they are not interchangeable. Each section below holds
predominantly one kind, and where a section mixes them the individual sentence says which it is.

| Kind | Where it lives | What it is worth |
| --- | --- | --- |
| **Measured** | [Measured](../reference/measured-results.md#measured) | A JMH number with a named class, method, parameters and run configuration. Bounded by the `±` and by the conditions in the provenance line beneath its table. |
| **Structural** | [Facts that cost real effort to establish](#facts-that-cost-real-effort-to-establish), [Decisions that shape everything else](#decisions-that-shape-everything-else), [Defects found and fixed](#defects-found-and-fixed) | A property of the source that a reader can check by opening a named file, type or member. Not subject to run-to-run variance at all, and therefore the more durable of the two. |
| **Design intent** | [Where things live](#where-things-live), the prose under each [Measured](../reference/measured-results.md#measured) table | What a construct exists for. Never an outcome, and never evidence that the outcome was achieved. |
| **Open or unverified** | [Open](#open), and every `TODO(maintainer)` comment in the source of this page | Either not built, or stated without a source that a reader can reach. Marked rather than dropped, so that the gap is visible. |

## Contents

- [How to read this page](#how-to-read-this-page)
- [What this is and why](#what-this-is-and-why)
- [Facts that cost real effort to establish](#facts-that-cost-real-effort-to-establish)
- [Decisions that shape everything else](#decisions-that-shape-everything-else)
- [Where things live](#where-things-live)
- [Charts](#charts)
- [What is in the branch](#what-is-in-the-branch)
- [Known deviations from vanilla](#known-deviations-from-vanilla)
- [Defects found and fixed](#defects-found-and-fixed)
- [Open](#open)
- [Investigated and deliberately not built](#investigated-and-deliberately-not-built)
- [Documents](#documents)
- [References](#references)

Two parts of this record have pages of their own. [Measured results](../reference/measured-results.md) carries the
measurement environment and every published table with its provenance line, the environment first
and the optimisations the tables produced last. [Contributing](../contributing/README.md) carries the versions
the build declares, the everyday Gradle commands, the conventions the build enforces, what a push to
`main` publishes, and what a pull request is checked against.

---

## What this is and why

Falco holds three things a Minestom server needs and does not get in this shape from the platform:

1. **An Anvil chunk loader** that replaces `net.minestom.server.instance.anvil.AnvilLoader`. The
   goal was a loader that is genuinely parallel, that does not silently lose data, and that stays
   maintainable — developed test-first, on Java 25, using Adventure NBT and JetBrains annotations.
2. **A light engine**, because once chunks are loaded, lighting is the next thing a server pays for.
   Since `FalcoLightingChunk` it also maintains itself: one call to `setChunkSupplier` and the light
   of a world stays current without a caller deciding when to recompute it.
3. **An instance and its chunk**, because the one thing `InstanceContainer` cannot be talked out of
   is its own lifecycle. **No speed gain is claimed and none is measured** — chunk and entity ticking
   lives in the global `ThreadDispatcher` of the server process, not in the instance. What it buys is
   an unload path that actually runs for a foreign instance, a generator whose failure cannot publish
   a half-built chunk, and a load which cannot race an unload into a chunk nothing can reach.

The motivating observation for the loader is structural, not measured. At Minestom
`2026.06.20-26.1.2`, `AnvilLoader` reports `supportsParallelLoading() == true`, but its `RegionFile`
holds one `ReentrantLock` per region file (`instance/anvil/RegionFile.java:42`) across the seek, the
payload read, the decompression **and** the NBT parse (`:67-92`, parse at `:88`). The parallelism is
therefore largely nominal for chunks that share a region file — a claim anyone can check by opening
that file at that version, without running anything. Moving the CPU work out of the lock rather than
starting more threads is what the three-stage pipeline below exists for; whether it pays is a
separate, measured question, answered under [Measured](../reference/measured-results.md#measured).

## Facts that cost real effort to establish

Verified against the sources or by running probe code. Knowing these prevents repeating the work.

**Minestom's loader interface**
- `ChunkLoader#loadChunk` is **synchronous** — it returns `@Nullable Chunk`, not a future.
  Parallelism happens because Minestom starts a virtual thread per chunk when
  `supportsParallelLoading()` is true. A loader must be thread-safe, not asynchronous.
- The default `saveChunks` starts **one virtual thread per chunk**, unbounded, and its `catch`
  branch never deregisters from the `Phaser`, so one exception hangs it forever. Override it.
- `unloadChunk` is documented as arriving for chunks the loader never loaded, which makes reference
  counting on it unreliable.
- `setChunkLoader` does **not** call `loadInstance` — only the constructor does. `AbstractMapProvider`
  sets the loader afterwards, so `level.dat` is never read there. Pre-existing, not introduced here.

**A behavioural difference between the two Anvil loaders**
- `net.minestom.server.instance.anvil.AnvilLoader(Path, Key)` resolves **only**
  `dimensions/<namespace>/<value>/region`. It has no fallback to `worldRoot/region`, so it reads
  nothing at all from a world in the pre-26.1 layout. The single-argument `AnvilLoader(Path)` is the
  only way to reach such a world with it, and it is deprecated for removal.
  `FalcoAnvilLoader#resolveRegionDirectory` handles both layouts. Found while building falco-demo,
  where a comparison that missed this would have reported a fictional speedup.

**Two things this project shipped that could not be used together — resolved 2026-08-03**
- **`FalcoInstance` and `FalcoLightingChunk` used to be mutually exclusive, and are not any more.**
  `FalcoInstance` refuses any chunk which is not a `FalcoChunk`, because `Chunk#onLoad` and `#unload`
  are package-private and `FalcoChunk` exists to reopen them — while `FalcoLightingChunk` extended
  `DynamicChunk`. Handing a `FalcoInstance` the supplier of a `ChunkLightScheduler` therefore failed
  on the first chunk it loaded, not at startup, and the only options were to run the self-maintaining
  light on an `InstanceContainer` or to drive `ChunkLightService` yourself. It was found while
  building the demo servers; the two features had been developed separately and never met until then.

  `FalcoLightingChunk` now extends `FalcoChunk` and both run together. Closing this was the reason
  the chunk was rewritten to own its storage in the first place — the footprint result came out of
  the same work, but it was not what the work was for. The change cost binary compatibility: the
  class is `final` now, which is recorded as a deliberate break in `gradle/api-breaks.properties`
  along with the baseline it was judged against.
- The way out would be a chunk that is both, which means either `FalcoChunk` growing the light
  behaviour or `FalcoLightingChunk` growing the reopened hooks. Neither is written.

**Which client speaks to which server**
- Minestom `2026.06.20-26.1.2` is Minecraft **26.1.2**, protocol **775**. Read from
  `MinecraftConstants` with `javap -constants` and confirmed against a real server list ping. It is
  worth writing down because nothing in the dependency coordinate says so, and a mismatched client
  fails with a message that names neither side.

**What can and cannot be replaced**
- `Palette` is `sealed ... permits PaletteImpl`. A foreign implementation is a hard compiler error,
  and `Section` is a record holding that exact type. Verified with javac and at runtime.
- `Light` is **not** sealed, and `Section`'s canonical constructor is public — a custom light
  implementation compiles and runs end to end. But `Section.clone()` calls `Light.sky()` / `Light.block()`
  outright, so a custom implementation is silently replaced on copy.
- `Instance` and `InstanceContainer` are not sealed either, but four `instanceof InstanceContainer`
  sites in Minestom make a foreign instance silently take a different path.
- **A fresh `LightingChunk` reports itself unloaded.** It answers `super.isLoaded() && doneInit` and
  sets `doneInit` only in its `protected onLoad()`. Both `ChunkBatch` and `AbsoluteBlockBatch` begin
  with a check on exactly that and return with a warning about an unloaded chunk, so a batch against
  such a chunk silently does nothing. This is why `FalcoLightingChunk` extends `DynamicChunk` rather
  than `LightingChunk`: rebuilding that behaviour would be a defect, not a feature. Measured with a
  probe in [Research: Shared Instances and Batches](research/shared-instances-and-batches.md).

**Claims about other light engines**

Established by reading the sources of those engines, not by measuring anything. None of them is a
performance claim about Falco, and none of them may be quoted as one. Each was taken for a promising
lead first and only stopped being one after it was checked; the checks themselves, with the source
references behind each, are in [Research: Light Engine](research/light-engine.md).

- **The main advantage attributed to Starlight is already here.** Falco's BFS pushes a level onto the
  neighbours of a cell instead of pulling each cell from all six of its own neighbours, which is the
  difference Spottedleaf names as the reason Starlight beats vanilla. This is a structural property
  of both implementations, not a measured figure.
- **Starlight has no "extended nibble arrays with a border".** `SWMRNibbleArray.ARRAY_SIZE` is
  2048 bytes, identical to vanilla's `DataLayer`. What does exist is the flat `sectionCache` /
  `nibbleCache` over 5×5 sections — that is a flat `byte[]` for the column, not a border.
- **Starlight's data-holding gain does not transfer.** It comes from vanilla keeping light in a
  `Long2ObjectOpenHashMap<DataLayer>` and cloning it per tick. Falco never had that structure.
- **The quoted 12× / 28× / 37× figures do not apply.** They compare Minecraft 1.16–1.19 against the
  old vanilla engine. Spottedleaf withdrew the chunk-generation comparison himself and states for
  1.20+ only "Vanilla is still 2x slower, but it is fast enough".
- **Phosphor's optimisations are vanilla-specific bar one.** The transferable part is the block-state
  opacity cache, which is what `SectionOpacity` already is.
- **There is no scientific literature on this problem.** No peer-reviewed work on discrete
  Minecraft-style flood-fill light propagation exists; the reference text is a blog post (Ben Arnold,
  Seed of Andromeda). Voxel cone tracing and VXGI solve continuous radiance and do not transfer.

**Three assumptions of the approved lighting-chunk design that did not hold**

The design in [`docs/superpowers/specs/2026-07-31-falco-lighting-chunk-design.md`](https://github.com/OneLiteFeatherNET/Falco/blob/main/docs/superpowers/specs/2026-07-31-falco-lighting-chunk-design.md)
was signed off before it was built, and three of its statements turned out to be wrong. The document
is left as it was; what replaced it is here.

- **`invalidate()` does not deliver the light.** The spec said the scheduler calls `invalidate()` on
  the finished chunks and "Minestom sends an `UpdateLightPacket` on its own", attaching to the
  mechanism `LightingChunk` supposedly already uses. `DynamicChunk#invalidate` only discards the
  cached full chunk packet, which reaches whoever *receives* the chunk next; `LightingChunk` sends
  its light explicitly from its own `tick`. Without a send of our own, a player already standing in
  the chunk would never see a torch they just placed light up. `LightUpdateAware#onLightUpdated` and
  the `CachedPacket` of `UpdateLightPacket` in `FalcoLightingChunk` are what closes that.
- **One block change makes the 3×3 dirty, not the chunk it happened in.** The spec had `setBlock`
  report only its own chunk while promising seamless chunk borders in the same document; the two
  contradict each other. A lamp at the eastern edge belongs in the light of the chunk east of it,
  and that chunk would never be told. A batch test surfaced it. `FalcoLightingChunk#reportChanged`
  therefore marks the chunk and its eight neighbours, which is also why the ring of an area is read
  but never written — a chunk that has to *change* has to be in the area, not in the ring.
- **The revision counter belongs to the scheduler, not to the chunk.** The spec put a counter on each
  chunk, raised by its own `setBlock`. With neighbours being marked as well, a counter per chunk has
  cases in which the recorded value does not move although the chunk was marked again — two counters
  raised by two different chunks cannot be merged into one number without one. A value that does not
  move reads as "nothing changed", so a stale result would be accepted and the chunk cleared from the
  dirty set. `ChunkLightScheduler` counts *marks* in its own dirty map instead. This matters more here
  than almost anywhere else, because writing light also clears the update flag of the section, so a
  wrong result is never recomputed on its own.

**Library traps**
- `adventure-nbt` 5.1.1: the iterators of `LongArrayBinaryTag`, `IntArrayBinaryTag` and
  `ByteArrayBinaryTag` **skip the last element** (`index < length - 1`). A for-each over packed block
  data corrupts every chunk. Use `size()` + `get(i)`. `NbtReadsTest` documents this as a live check.
- `BinaryTagIO.reader()` caps at 131 082 bytes, far too small for chunk NBT. Use `unlimitedReader()`.
- Every `CompoundBinaryTag` getter silently returns a default for a missing or mistyped key, which
  turns a broken region file into an empty chunk. `NbtReads` exists to make that an error.
- `Block.fromStateId` indexes an array **without a bounds check** and throws for an unknown id
  instead of returning null.

**Test environment**
- `MinecraftServer.getExceptionManager()` throws before `MinecraftServer.init()`. Anything resolving
  a registry in a constructor becomes untestable — this is why the biome resolver is lazy.
- Cyano's exception handler turns a reported exception into a **test failure**. Code that reports to
  the `ExceptionManager` cannot be asserted on by exception type in tests.

**Java 25**
- `StructuredTaskScope` (JEP 505) and `StableValue` (JEP 502) are still **preview** and therefore
  unusable in a published library — preview class files only run on the exact JDK they were built
  with, and would force `--enable-preview` on every consumer. Concurrency here uses
  `Executors.newVirtualThreadPerTaskExecutor()`, `Semaphore` and `Phaser`.
- **The Vector API (JEP 508) is the same trap.** It is the tenth incubator round: without
  `--add-modules jdk.incubator.vector` `javac` already refuses, with it the runtime prints a warning
  that cannot be suppressed, and the JAR specification has no `Add-Modules` attribute to carry the
  flag. Every consumer of the library would have to set a JVM flag, which rules it out regardless of
  what it might buy.
- Scoped Values, record patterns, sealed interfaces, FFM and stream gatherers are final and usable.
- File I/O does **not** unmount a virtual thread from its carrier (JEP 444), so unbounded virtual
  threads over file work do not scale — bound them.

## Decisions that shape everything else

These were explicit calls, not defaults. Changing one means revisiting the work that followed it.
The *Why* column is a mixture of structural argument and measurement; where a cell rests on a number,
the number's source is named in the cell and the table it comes from is under
[Measured](../reference/measured-results.md#measured).

| Decision | Choice | Why |
| --- | --- | --- |
| Format coverage | Core compression plus external `.mcc`, **no** LZ4, no corruption recovery | Covers real worlds without an extra dependency; Minestom fails hard on oversized chunks, which this does not |
| Integration | Opt-in via `ChunkLoaderFactory` | No breaking change; existing providers behave exactly as before |
| Own palette | **Not built** — codec-internal representation only | Structural: `Palette` is `sealed … permits PaletteImpl`. The supporting figure — palette decoding at 4.5 % of the load path, against NBT parse 62 % and inflate 28 % — is from the earlier investigation recorded in [Research: Light Engine](research/light-engine.md), not from this benchmark suite, and carries no interval |
| Own `InstanceContainer` | **Not built.** An own `Instance` was, in `falco-instance` | Subclassing the container is closed — its lifecycle hooks are `protected` members of Minestom's package. Extending `Instance` makes the barrier visible once, at the chunk. No speed is claimed either way: the tick parallelism lives in the global `ThreadDispatcher` |
| Light `Light` implementation | **Not built** — results handed over via `Light#set` | Avoids the `@ApiStatus.Internal` calculation methods and the `Section.clone()` trap |
| Read failure | Throws, never returns `null` | `null` means "absent", so the server regenerates and overwrites real data on the next save |
| Compression level | 2 (`ChunkCompression.DEFAULT_LEVEL`), not the platform default 6 | Two figures, of different weight. Structural: everything a chunk save compresses runs outside every lock (`FalcoAnvilLoader#snapshot` releases the chunk read lock before `encodeSection`), so the level trades file size against wall time and nothing else. Measured: neither half of the trade is one number — the time advantage runs from unresolvable at one distinct state to 2.96× at 1 024, the size cost from 0.08 % to 40.66 % across the same sweep, and they move together, so a cheap size and a large factor never occur at the same parameter value ([Compression level 2 against level 6](../reference/measured-results.md#compression-level-2-against-level-6)) |
| Reader safety in `RegionFile` | Per-entry seqlock | A `ReadWriteLock` would block every reader of a region for the length of a payload write, which is the one thing this loader gains over Minestom's; deferred free brings the contention back through a shared counter and grows the files |
| Region handle lifetime | Usage count, not a retry | A retry only narrows the window and has to be repeated at every call site; counting accesses removes the case instead. The cost is that the open-handle cap now bounds the cache rather than the descriptors |
| Use after `close()` | Throws | Ignoring it loses data during shutdown, and waiting is impossible — Minestom owns the load tasks, so there is nothing to wait on |

## Where things live

```
falco-anvil/          net.onelitefeather.falco.anvil
                      RegionConstants, SectorAllocator, BitPacker, ChunkCompression,
                      RegionFile, NbtReads, PaletteData, PaletteEntryResolver, SectionCodec,
                      BlockPaletteResolver, BiomePaletteResolver, AnvilDiagnostics,
                      FalcoAnvilLoader, ChunkLocation, ServiceResolution,
                      AnvilFault, AnvilChunkException, AnvilFormatException,
                      ChunkDataException, RegionFormatException,
                      ChunkVersionPolicy, DefaultChunkVersionPolicy,
                      UnknownEntryPolicy, DefaultUnknownEntryPolicy,
                      ChunkMigrationMode, ChunkMigrator

falco-light/          net.onelitefeather.falco.light
                      LightNibbles, BlockFace, BlockLightSource, SectionOpacity,
                      LightPropagator, ChunkLightPropagator, ChunkLightState,
                      ChunkLightService, MinestomBlockLightSource,
                      ChunkArea, ChunkLightArea, ChunkLightScheduler,
                      FalcoLightingChunk, LightUpdateAware, ChunkLightListener

falco-instance/       net.onelitefeather.falco.instance
                      FalcoInstance, FalcoChunk, FalcoSharedInstance,
                      FalcoInstanceException, ChunkRegistry, ChunkGeneration,
                      ChunkPersistence, ChunkViewerCache, ChunkLifecycle,
                      ChunkLifecycleEvent, ChunkLifecycleListener,
                      BlockStorage, BlockWriter, SectionBlockStorage,
                      LazySectionBlockStorage, PaletteCompaction

falco-migration/      net.onelitefeather.falco.migration
                      ChunkMigration, MigrationStep, MigrationContext,
                      MigrationException, BlockState, BlockStateRule, BlockStateRules,
                      LegacyBitReader, WorldLayout, FalcoChunkMigrator
                      ...steps: NormaliseBitPacking, CountEntities, UnfoldLevel,
                      RebuildBiomes, SettleYRange, NamespaceStatus,
                      TranslateBlockStates, TranslateBlockEntities,
                      DiscardHeightmapsAndLight

falco-benchmarks/     not published. Holds every benchmark, because ScalingBenchmark measures
                      both modules in one run and BenchmarkConstants / SectionStates are used
                      from both sides. LightEngineComparisonBenchmark and
                      LightEngineStageBenchmark live in net.minestom.server.instance.light
                      because the methods they measure are package-private there

falco-demo/           not published. A runnable comparison of the two loaders on a world the user
                      supplies. Rough figures from one machine, not an instrument: falco-benchmarks
                      stays the place where a number becomes evidence.
```

The four published modules that carry sources are listed type by type above, and the lists are the
*Types* column of [What is in the branch](#what-is-in-the-branch) enumerated: 26 for `falco-anvil`,
15 for `falco-light`, 16 for `falco-instance`, 19 for `falco-migration`. Counting the names is the
check.

`falco-migration` is the only one that depends on another of them — it reads `falco-anvil`'s
exception types and implements its `ChunkMigrator`. That is why it sits outside `ModuleBoundaryTest`'s
`PUBLISHED` set, which asserts that the modules in it do not know each other, and has its own
`MigrationBoundaryTest` instead. **The other rules of `ModuleBoundaryTest` therefore do not reach it**
— a gap that has not been closed and is worth knowing when reading
[Architecture Rules](../contributing/architecture-rules.md).

Each module's `src/test/java` mirrors its own package; `*ConcurrencyTest` are the stress tests, and
`LightEngineEquivalenceTest` pins the byte identity with Minestom. `falco-light` depends on
`falco-anvil` in test scope only, for the one case that runs the engine on a chunk that went through
the loader. `falco-instance` depends on neither of the other two.

**`FalcoLightingChunk` sits in `falco-light`, not in `falco-instance`**, which is worth stating
because the opposite looks natural. It needs the light engine and nothing else — it holds no
computation of its own, it only reports what changed and when a tick happened. Putting it next to
`FalcoInstance` would make one published module depend on another for a type that has no relationship
to an instance implementation. A `FalcoInstance` does not need a lighting chunk, and the lighting
chunk works on any `Instance`, including the `InstanceContainer` the server ships with.

Integration with the map provider of [Aves](https://github.com/OneLiteFeatherNET/Aves) happens
through its `ChunkLoaderFactory`, which is a functional interface — neither library depends on the
other. The usage side of that is in [How the Anvil loader is built](../explanation/how-the-anvil-loader-is-built.md).

Reading order for someone new: `RegionFile` (the byte container), then `FalcoAnvilLoader`
(the three stages), then `SectionOpacity` and `ChunkLightPropagator` for the light side, and
`ChunkLightScheduler` for how that light keeps itself current. `FalcoInstance` is independent of all
of them and can be read on its own.

## Charts

Two sets of charts exist and they are not equivalent.

**The four committed charts** are generated by
[`docs/charts/generate.mjs`](https://github.com/OneLiteFeatherNET/Falco/blob/main/docs/charts/generate.mjs)
and are rendered on [What the benchmarks establish](../explanation/what-the-benchmarks-establish.md). The generator is the authority for what each bar
holds: the values are literals in that file, so a reader can compare a bar against the table it
claims to draw without running anything. The tables they draw are `loader-contention` (from
[The region file against the one Minestom ships with](../reference/measured-results.md#the-region-file-against-the-one-minestom-ships-with)),
`light-engine` and `light-engine-mixed` (from the two light comparison tables) and `save-stages` (from
[Where the time goes when saving a chunk](../reference/measured-results.md#where-the-time-goes-when-saving-a-chunk)).

The generator carries the same rule the tables do: where a table refuses a factor, no bar may print
one. `loader-contention` therefore draws two rows rather than four — the four- and eight-thread rows
are left out, because Minestom's half-width exceeds its own mean on both, and the generator says so
in a comment next to the rows it omits — and it labels the single-thread bar `1.14× slower (not
resolved)`, so the arithmetic of the two means cannot be read off the bar without the verdict
attached to it. The two light charts draw each bar's interval as a whisker, so a pair that resolves
nothing cannot look like a pair that does.

The `save-stages` chart is the one to read against the caveat that travels with its table: its four
segments are 64, 1 356, 2 701 and 17 µs and its subtitle states the 4 138 µs total, which is the sum
[Where the time goes when saving a chunk](../reference/measured-results.md#where-the-time-goes-when-saving-a-chunk)
declines to turn into percentages until `full()` is published. The generator labels the third
segment *NBT serialisation and zlib* and marks it derived, matching the table rather than the label
the row used to carry.

**Four further charts** were produced from these measurements outside this repository and are not
publicly readable, so nothing on this page depends on them. Every number behind them is in the tables
under [Measured](../reference/measured-results.md#measured).

| Chart | Shows |
| --- | --- |
| Scaling and comparison | 1 to 256 sections, and the head-to-head against Minestom. **The head-to-head half predates `69381af`** and shows the factors from before the opacity table was rewritten |
| Optimisation | Where save time goes, the compression trade-off, the uniform-section fast paths |
| Vanilla · Minestom · Falco | 22 behaviours scored against the format reference |
| Concurrency defects | The five races, their failure rates before the fix, and what the fix costs |

A `results.json` from a JMH run feeds [JMH Visualizer](https://jmh.morethan.io/) directly if you want
the same views from a run of your own. **No `results.json` from any run behind this page has been
committed**, so the charts and the tables under [Measured](../reference/measured-results.md#measured) are currently
the only published form of those runs — see
[Measurement environment](../reference/measured-results.md#measurement-environment).

---

## What is in the branch

| Module | Types | Tests | What it does |
| --- | ---: | ---: | --- |
| Module | Types | Tests | What it does |
| --- | ---: | ---: | --- |
| `falco-anvil` | 26 | 22 classes, 276 tests | Reads and writes Anvil region files, replacing `AnvilLoader` |
| `falco-light` | 15 | 22 classes, 223 tests | Computes block light and sky light for a chunk, and keeps it current on its own |
| `falco-instance` | 16 | 29 classes, 259 tests | An `Instance` and its `Chunk`, with a lifecycle that runs for a foreign instance |
| `falco-migration` | 19 | 8 classes, 79 tests | Lifts a stored chunk from the version that wrote it to the one the server runs — see [How world migration works](../explanation/how-world-migration-works.md) |
| `falco-demo` | 19 | 18 classes, 167 tests | Not published. A runnable comparison of the two loaders on a world the user supplies |
| `falco-archunit` | — | 7 classes, 48 tests | Not published, and holds no sources of its own. The architecture rules, which see the other modules only as compiled classes |
| `falco-benchmarks` | 35 files | — | Not published. Benchmarks, in their own source set, never run during a build |
| `falco-bom` | — | — | Not published as code. A Maven BOM whose constraints pin the published modules to one version |

**The totals, added up here so that nobody has to.** The four published modules that carry sources
hold **76 types** (26 + 15 + 16 + 19) and, with `falco-demo`, **95**; `falco-benchmarks` is not added
to that, because its cell counts files in a separate source set rather than types. Test classes add
to **106** across the six modules with tests (22 + 22 + 29 + 8 + 18 + 7). **Executed tests add to 837
for the four published modules** (276 + 223 + 259 + 79), and to **1052** across everything that runs,
once `falco-demo`'s 167 and `falco-archunit`'s 48 are included.

**Every column above was re-derived at `cbd74ccb`** (the 2.1.0 release), from one tree and one test
run, which the previous version of this table could not say: its executed-test counts came from a
build whose commit was not recorded, and its `falco-demo` cell carried no number at all because
nothing reproduced it. Both gaps are closed — the demo's 167 tests are counted the same way as every
other cell.

**How to reproduce these counts.** The *Types* and *files* columns are `find <module>/src/main -name
'*.java' ! -name 'package-info.java' | wc -l` — a working tree with uncommitted files will differ —
and for `falco-benchmarks` the figure counts every file of `src/jmh/java`, its `package-info.java`
files included. The *Tests* column is a test-class count and an executed-test count: `./gradlew test
--rerun-tasks`, then count the result files and the `testcase` elements inside them under
`<module>/build/test-results/test/*.xml`. A `@ParameterizedTest` contributes one class member and
several executed tests, so the two numbers do not have to agree. **The class count is of classes that
produced results, not of `*Test.java` files**, and the two differ: `falco-anvil` has 22 of each here,
but a shared fixture such as `FileTestBase` holds no test method and produces no result file.

<!-- TODO(maintainer): the executed-test counts are from a `./gradlew build` whose commit was not
     recorded, and the falco-demo figure no longer matches the tree. At ca79507 the modules declare
     149 + 9, 187 + 1, 36 + 0 and 139 + 0 (@Test + @ParameterizedTest) members respectively, so 178 /
     189 / 36 are plausible expansions of the first three and the 73 that used to stand in the
     falco-demo cell is not reachable from the 139 that falco-demo now declares. Re-run the build,
     record the commit next to the table, and fill the falco-demo cell from it. Do not adjust the
     other three by hand. -->

`ChunkLoaderFactory`, the opt-in seam, is not counted here: it lives in
[Aves](https://github.com/OneLiteFeatherNET/Aves) rather than in this repository.

### Anvil loader

Three stages, so the expensive work never happens under a lock: the chunk state is copied under its
read lock, the conversion to compressed bytes runs lock-free, and only the transfer into the region
file is guarded. `saveChunks` is grouped per region and bounded by a semaphore rather than starting
one virtual thread per chunk.

Region files use positional `FileChannel` operations, so reads of different chunks proceed in
parallel, and a per-entry seqlock keeps a reader from being handed a sector that was recycled while
it read. Every access registers itself on the handle; a file leaves the cache when the last chunk
this loader read from it is unloaded, and is closed by whichever thread finishes with it last. The
cap on open handles is a backstop on the cache, not on descriptors.

### Light engine

Block light and sky light, across section borders, across chunk borders, and incrementally after a
single block changed. The algorithm has no Minestom dependency — the registry sits behind
`BlockLightSource`, the same separation the Anvil codec uses for palettes — and results are handed to
a chunk through `Light#set`, which is the stable part of that interface rather than its internal
calculation methods.

One `ChunkLightService` serves any number of threads, because it keeps no state between calls. That
is a property worth stating rather than assuming: the working buffers live in a propagator built per
call, and handing several threads one propagator is what made the engine produce silently wrong
light before.

### A chunk that keeps its own light up to date

Using the engine was manual until now: a caller had to notice that a chunk changed, decide when to
recompute and call the service. `FalcoLightingChunk` gives Falco the entry point Minestom sets with
`setChunkSupplier(LightingChunk::new)`, without giving up what the engine gained — the computation
still runs in a service that is thread-safe per call and still hands its result over through
`Light#set`, so it is not welded to one chunk implementation. Minestom's own engine is the more
restricted of the two: it computes light for `LightingChunk` and for nothing else.

```java
ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());
instance.setChunkSupplier(scheduler.supplier());
```

Five types, and only one of them holds behaviour:

| Type | Responsibility |
| --- | --- |
| `FalcoLightingChunk` | Five overrides, none of which computes light. `setBlock` reports the changed *position*, `onLoad` reports a change of unknown extent, `tick` triggers the pass; `invalidate` and `onLightUpdated` serve the cached light packet this chunk sends its viewers. |
| `ChunkLightScheduler` | The dirty set, the once-per-tick trigger, area forming, the executor, back pressure and the staleness rule. All the complexity, at one address. |
| `ChunkArea` | A chunk coordinate pair, and the flood fill that cuts a dirty set into capped connected groups. Pure arithmetic, no Minestom, so both its rules are testable without a server. |
| `ChunkLightArea` | Computes one group in a single pass: reads its chunks plus one ring, exchanges borders until settled, writes back the group and never the ring. Keeps the light of a chunk *on its own*, bounded and least-recently-used, so the next pass replays the reported positions on it instead of searching the chunk again. |
| `LightUpdateAware` | The hook Minestom does not have, so the scheduler can deliver a result without knowing which chunk type it is talking to. |

Five properties of the cycle that are decisions rather than details:

- **Once per tick, not once per chunk.** `Chunk#tick(long)` runs per chunk, but a pass has to see
  every change of the tick before it forms its areas. The tick timestamp is the same value for every
  chunk of one tick, so the scheduler runs its pass for the first chunk reporting a new one. If no
  chunk ticks, nothing happens — which is correct, because then nobody is looking.
- **A change marks the 3×3, and the ring of an area is read but never written.** Those two are the
  same rule seen from both ends: a chunk whose light has to change must be *in* an area, because a
  ring chunk has only seen the light of the area and never what lies on its own far side, so writing
  it back would darken it. This was designed out here from the start and has since been carried back
  into `calculateWithNeighbours`, which now writes only the chunk in the middle of its 3×3 — see
  *Defects found and fixed*.
- **A changed block costs a changed block.** The position of every change is handed to the area
  together with the mark, and the area replays it on the light it already holds for that chunk rather
  than searching the chunk again. The eight neighbours are marked as well, because light crosses
  borders, but nothing of theirs is thrown away: their own blocks did not move, and what reaches them
  across the border is derived again by every pass anyway. A change that cannot be placed — a chunk
  generated, loaded, or written past `setBlock` — is reported as unknown and lit from the block
  states, which is what every change did before. The measured effect is under
  [Measured](../reference/measured-results.md#measured).
- **An area has an upper bound, and the bound is not optional.** One `ChunkLightState` is roughly
  100 KB at the height of an overworld chunk, so sixteen chunks plus their ring is a few megabytes of
  working memory per pass — and every chunk of an area is also read and turned into opacity tables
  inside one tick, which is the cost that actually grows with the area. The flood fill stops at
  `maxAreaSize` (16 by default) and the remainder starts the next area; the seam between two parts
  settles on the following tick, because each part reads the other as its ring.
- **Nothing ever blocks on a computation.** A chunk hands out whatever its sections hold right now,
  which is the previous result while a new one is in flight.
- **One scheduler serves exactly one instance.** The dirty set is keyed by chunk coordinates alone
  and every instance ticks with the same timestamp, so a shared scheduler would light the wrong
  chunks and run its pass for only one instance per tick. A second instance is refused with an
  `IllegalStateException` rather than left to fail as a dark world.

The executor is a constructor argument. The default gives every area its own virtual thread and
bounds them with a semaphore at the processor count — the same shape `FalcoAnvilLoader` uses for its
saves, with the bound taken inside the task rather than around the submission so the chunk that
happened to trigger the pass is not the one that waits. Handing in `Runnable::run` instead makes the
whole cycle synchronous, which is what lets the tests of this path assert on a result after calling
`tick` rather than waiting for one. A server that already runs a pool can hand that over.

How the finished light reaches a client is where this deviates from the design it was built to; see
*Three assumptions of the approved lighting-chunk design that did not hold* above.

### Instance

`FalcoInstance extends Instance` directly rather than `InstanceContainer`: the chunk lifecycle hooks a
subclass would have to override are `protected` members of `net.minestom.server.instance`, so a
subclass outside that package cannot reach them. Starting from `Instance` makes the barrier visible
once, at the chunk, where `FalcoChunk` answers it. The reason the module exists is
`InstanceManager#unregisterInstance`, which unloads chunks only for an `InstanceContainer` and leaves
every chunk, tick partition and entity of any other instance behind.

**The chunk owns its storage, and allocates it on demand.** `FalcoChunk extends Chunk` and holds a
`BlockStorage`; until 2026-08-03 it extended `DynamicChunk` and inherited one. A `DynamicChunk`
allocates all 24 sections in its constructor and builds both heightmaps in field initialisers, so an
empty chunk costs 192 objects and 6 848 bytes before anything is written into it — on a generated
overworld, where 62.24 % of sections hold nothing, most of that is paid for air. A `FalcoChunk`
materialises a section on the first write into it, builds a heightmap on the first question, and
shares one flyweight across every empty section: **25 objects, 840 bytes, −87.7 %**. The full tables,
including what a *filled* chunk does not save and how many sections each operation materialises, are
in [Counted](../reference/measured-results.md#counted). No timing claim goes with any of it.

**`FalcoInstance` delegates rather than does.** It had grown to 1 119 lines covering the registry,
loading, block writing, generation and persistence at once. It now holds exactly four collaborators —
`ChunkRegistry`, `BlockWriter`, `ChunkPersistence`, `ChunkLifecycle` — and `InstanceFacadeTest`
asserts that count, so a fifth field fails the build rather than passing unnoticed.

**A shared instance that stops writing through.** `FalcoSharedInstance extends SharedInstance` and,
like Minestom's, takes an `InstanceContainer` — it is **not** a way to share a `FalcoInstance`, which
remains impossible for the typing reason it always was. What it fixes is that Minestom's
`SharedInstance` delegates `setGenerator`, `setChunkSupplier` and `enableAutoChunkLoad` straight into
the container, so configuring one view reconfigures the world for every other view, and that
`saveInstance` saves the container's tags under the view's name. Each is answered by an override that
keeps the value on the view.

**`FalcoLightingChunk` now extends `FalcoChunk` and is `final`.** That was the point the whole
storage rewrite aimed at — the light engine and the chunk lifecycle on one instance — and it is a
break of binary compatibility, deliberately taken and recorded in `gradle/api-breaks.properties`
with the baseline it was judged against.

**The generator writes into clones.** `applyGenerator` hands the generator copies of the palettes of
every section and moves them over only after it returns; `InstanceContainer#generateChunk` hands over
the live ones and catches whatever the generator throws into the exception manager. A generator that
fails halfway there leaves a chunk that is half built, published and reported as loaded, while the
caller who asked for the chunk is told nothing. Here the failure travels to that caller and the chunk
is exactly as it was. The cost is one palette clone per section, and on the case that matters — a
chunk that is still empty, which is where a generator normally runs — a palette is in single-value
mode and holds no array at all, so the clone is a few bytes. Holding the write lock for the commit
only, rather than across the whole generator, is a side benefit and not the reason.

**`loadingChunks` is also the lock of a chunk position.** Starting a load, publishing its result and
unloading the chunk all happen inside a `ConcurrentHashMap#compute` on the index of that position, so
the three serialise without a monitor over the whole instance. The unload wins and the load throws its
own result away: waiting would block an unload on a disk read for a chunk nobody wants, and aborting
is impossible because the loading thread is already inside foreign code. Minestom lets the two
overlap, and the chunk that loses that race stays in the world with its loaded flag already cleared
and nothing left that could ever unload it.

---

## Known deviations from vanilla

Vanilla defines the Anvil format, so these are gaps in this implementation, not preferences:

| Gap | Consequence |
| --- | --- |
| Heightmaps are neither written nor restored | Minestom at least restores them on load |
| Unknown chunk-level tags are dropped | `structures`, `block_ticks`, `fluid_ticks` and others are lost on save |
| `entities/` and `poi/` are ignored | Saving a vanilla world produces inconsistent world data |
| `level.dat` is not handled | `loadInstance` / `saveInstance` are not overridden |
| No LZ4 (type 4) or custom (type 127) compression | A world written with `region-file-compression=lz4` cannot be read |
| No corruption recovery | A damaged header makes the whole region unreadable |
| An unknown block becomes air | Better than discarding the chunk, but not what a data fixer does |

---

## Defects found and fixed

### In this code

- **Block entities were stored at chunk-local coordinates.** The format specifies world coordinates.
  The round trip through this loader worked anyway because `Chunk#setBlock` masks them, so only a
  test that read the stored NBT directly could catch it. Files were not interchangeable with vanilla.
- **Block handlers were lost on load.** The `id` tag was written on save but discarded on load.
- **The propagation queue could overflow.** It was sized on the assumption that a position is queued
  at most once, which is false when sources of different brightness reach the same area.
- **`FalcoAnvilLoader` could lose a chunk.** A region file could be evicted between obtaining the
  handle and writing to it.
- **The name cap in `AnvilDiagnostics` was a check-then-act**, so racing threads could exceed it.
- **The biome registry was resolved eagerly**, which made a loader impossible to construct before
  `MinecraftServer.init`.
- **`calculateWithNeighbours` darkened the eight chunks it borrowed.** It wrote all nine chunks of
  its 3×3 back, although only the middle one is correct: a ring chunk exchanged light inside the 3×3
  and nowhere else, so whatever it legitimately receives from further out was missing from its result
  and its previously correct light was replaced with a darker one. It now writes only the middle
  chunk and reads the other eight. The middle chunk is provably unaffected by the same reasoning that
  makes the ring wrong — a source outside the 3×3 is at least seventeen blocks from it and no path is
  shorter than the direct distance, so not even level fifteen survives the trip. The fix is therefore
  cheaper than the defect as well as correct, which is rare enough to note. It had been an open item
  for a while, and what closed it was `ChunkLightArea` arriving at the same rule from the other side.
- **`ChunkLightState#update` never refilled a hole that opened six blocks from any light.** When a
  block that had been blocking light was removed, the position carried no light of its own, so the
  retraction found nothing to follow; offering the sources of the chunk again reached nothing either,
  because their neighbours already carry exactly the level they belong at and the search stops at the
  first of them. Light standing six blocks away therefore never travelled into the hole. The sky path
  had it right — `updateSky` seeded the neighbours of the changed position — and `update` did not.
  Both now do. Surfaced by wiring the incremental path into the scheduler, which is what first ran
  `update` over the removal of an ordinary solid block rather than of a source;
  `ChunkLightStateTest#testRemovingABlockingBlockLetsTheLightThrough` pins it down.
- **The byte identity against Minestom was never checked by anything.** This file and the documents
  stated "54 scenarios, byte-identical, zero differing cells" as an established fact. It rested on an
  ad-hoc comparison run once by hand: there was no test, and the benchmark did not verify it either,
  although the documentation said it did. Two agents found this independently at their own end of the
  code. `LightEngineEquivalenceTest` now runs the 54 scenarios on every build, and the benchmark
  checks the 2048 bytes of both engines before each trial. A number cited throughout was hanging on
  nothing, which is the part worth remembering — not that it turned out to hold.

### Three in `FalcoInstance`, all found while closing the load race

Making `loadingChunks` the lock of a chunk position was one change; these three came out of it, each
with its own test. All three are the shape `InstanceContainer` has, reproduced here before it was
noticed that they had been.

- **`unregister` left a zombie chunk behind.** It walked the chunk map only, and a chunk that is
  still being loaded is not in that map yet. The load finished afterwards and published its chunk
  into an instance nothing reaches any more — the permanent zombie
  [Research: Instance Container](research/instance-container.md) describes. Every
  running load is now claimed first, which makes it discard its result, and only then are the chunks
  that are already there unloaded. `FalcoInstanceLoadRaceTest` forces the interleaving instead of
  hoping for it and checks the state of the instance afterwards; a test that only checked no
  exception escaped would have passed on the broken implementation too.
- **`retrieveChunk` read the chunk map outside any guard**, so two loads could start for one
  position. The second chunk then replaced the first one in the map and the first was orphaned:
  still marked as loaded, still holding its tick partition and its viewers, and no longer reachable.
  The chunk map is now read a second time *inside* the `compute` on the position. The work itself
  still starts after that decision and never inside it, because a loader without parallel support
  runs on the calling thread and a nested `compute` on the same map would deadlock.
- **The map write and the tick partition could interleave**, which leaves a partition ticking a chunk
  that was already unloaded, for the rest of the life of the server. Publishing a chunk and creating
  its partition are now one step taken while the position is held, and so are removing it and
  deleting the partition, so an unload of the same position runs entirely before or entirely after.
  The loaded flag is set outside that step on purpose: it calls a hook a subclass may override, and
  foreign code has no business running while a position is held.

### Five races, all of which would have failed silently

Found by taking one known defect and searching the rest of the code for the same shape. The search
turned up no second instance of that exact shape, but four races of other kinds — which is the
reason it was worth doing.

**The failure counts below are from the red run of each test, before its fix.** They are not JMH
measurements and carry no interval; each is the number of failures a named test observed in a named
number of attempts on one machine, which is what makes a race visible at all — a race that fails once
in fifty thousand reads is not something a mean describes. The tests themselves are the reproducible
part: `RegionFileConcurrencyTest`, `FalcoAnvilLoaderConcurrencyTest` and
`AnvilDiagnosticsConcurrencyTest` in `falco-anvil`, `ChunkLightServiceConcurrencyTest`,
`LightEngineConcurrencyTest` and `ChunkLightSchedulerConcurrencyTest` in `falco-light`, all of which
run on every build. A chart of the same figures exists but is not publicly readable, so it is not
linked.

- **`ChunkLightService` shared its scratch buffers.** It kept a `ChunkLightPropagator` in a field,
  so two threads sharing one service shared its `levels` and `queue` arrays. A probe found wrong
  light in ~99 % of concurrent calls. `ChunkLightState` had built one per call all along, which is
  why `calculateWithNeighbours` was never affected.
- **`RegionFile` recycled sectors while readers were still in them.** `readRaw` took the location
  without a lock; a concurrent `writeRaw` freed the old range, which the allocator handed straight
  back out. Readers observed filler markers where their own payload belonged, sometimes the whole
  payload from offset 0. Now a per-entry seqlock: an odd counter means "in progress", and after four
  attempts the reader falls back to the lock so a chunk written in a loop cannot starve it.
- **`.mcc` files were written and deleted outside the header lock.** 371 `NoSuchFileException` and
  ~60 half-read files in 54 679 reads. The payload now goes to a staging file and is moved into
  place with `ATOMIC_MOVE` under the lock, so the bytes stay outside it while the header and the
  file can never disagree.
- **Eviction closed a channel under a running reader.** Chunk tracking starts only *after* decoding,
  so a handle could be closed mid-read: 80 of 480 loads failed with `openRegionLimit = 1`, 15 of 480
  through the unload path, with `ClosedChannelException` thrown from inside `FileChannelImpl.read`.
  Handles now carry a usage count; removing from the cache and closing are separate, and the last
  user closes.
- **`closed` was set but never read.** `loadChunk`, `saveChunk` and `region()` ignored it, so a load
  still running at shutdown opened fresh handles into the map `close()` had just cleared — a
  descriptor leak, and writes into a world already considered closed. They now throw
  `IllegalStateException`, which is a lifecycle error of the caller rather than a data error.

The reason all five mattered is that none of them announced itself: the light path clears the
section's update flag, so the server never recomputes what two threads corrupted, and a read failure
that returns `null` makes Minestom regenerate the chunk and overwrite the real data on the next save.

### A sixth, on Windows only, and older than the five

`RegionFile` wrote and deleted the external `.mcc` file of an oversized chunk in a way that let a
concurrent reader block it. The external file is the one place where the lock-free reads meet a name
in the file system rather than a range inside the region file, and a name is not a POSIX concept.
Under POSIX a deletion detaches the name at once and keeps the unnamed file alive for every open
handle, so nothing is noticed. Windows leaves the name in the directory and only marks the file
*delete-pending*: as long as one reader holds it open, every later open of that name and every move
onto it is denied. An inline writer therefore poisoned the name for the writer that wanted to put a
new external file there.

Fixed in `78e196c`: the file is renamed onto a private name with `ATOMIC_MOVE` and only then deleted,
because a rename detaches the name immediately on both systems. What Windows can still deny briefly
on its own — a handle being torn down, a virus scanner holding the file — is retried for a bounded
time.

**This one is older than the concurrency fixes of this week.** It was already in the last green
commit; there simply was no test that exercised it. The loader was therefore broken on Windows as
soon as an oversized chunk is read while it is being saved. Only the CI runner could show it — on
Linux it is not reproducible, and the platform semantics are what the fix had to be reasoned from.

That is also why `.github/workflows/build-pr.yml` now uploads the test reports as an artifact when a
build fails (`bec8b67`). Gradle's console summary names the test class and the exception type, but
neither the message nor a path nor a stack trace, and on a platform-specific failure that is the
difference between reading the cause and guessing it.

### In Minestom, avoided here

Length field written as `5 + N` instead of `1 + N`; `status` in lower case where the game writes
`Status`; the return value of `read` ignored; an unknown block turning into an NPE that discards the
whole chunk; block entities dropped in uniform sections; the read failure path returning `null`,
which makes the server regenerate the chunk and overwrite the real data on the next save.

---

## Open

Ordered by consequence, not by effort.

**Three entries left this list on 2026-08-02**, and what they have in common is worth more than the
individual results: none of them was closed by building what the entry proposed.

- *Exception hierarchy* ([#21](https://github.com/OneLiteFeatherNET/Falco/pull/21)) stood at the top
  waiting on a decision rather than on work — whether the checked root extends `IOException`. It does
  not: that would have kept every existing `catch (IOException)` catching the new types, including the
  block in `saveChunk` that swallows a failure and leaves the chunk unwritten.
  [Research: Exception Hierarchy](research/exception-hierarchy.md) records where the implementation
  departed from the design, including a package the language does not allow.
- *`calculateWithNeighbours` can commit a stale read*
  ([#23](https://github.com/OneLiteFeatherNET/Falco/pull/23)) ended on "nothing in the API says so
  yet", and that was the whole of the open part. The behaviour is unchanged and deliberately so; the
  class and the method now state the condition and name `ChunkLightScheduler` as the way out, and the
  promise that the scheduler keeps neighbourhoods disjoint finally has a test.
- *The ring does not reach the diagonals*
  ([#24](https://github.com/OneLiteFeatherNET/Falco/pull/24)) was the one real defect of the three: a
  source in a chunk touching the area at a corner arrived as 0, not merely dimmer. The cost of fixing
  it turned out to be bounded in a way the entry did not suggest — the ring grows by the corners of
  its bounding box, so a 4×4 area reads 36 chunks instead of 32.

A fourth left it later the same day, and it is the one exception to the sentence above — it *was*
closed by building what it proposed, though not in the shape it proposed:

- *The opacity tables are rebuilt on every pass*
  ([#34](https://github.com/OneLiteFeatherNET/Falco/pull/34)). The entry asked for an invalidation
  that maps a changed position to a section, on the grounds that a table is per section rather than
  per chunk. What was built invalidates per **chunk**, because rebuilding one section still means
  reading that section's block states and the reading is what the table costs — a per-section bound
  would have bought a fraction of a rebuild at the price of a second index. The entry below is kept
  in full because the measurement in it is what said the mechanism was worth building at all.

A fifth left it on 2026-08-05, and it too was closed by building what it proposed — but the entry
had left one question open, and the answer turned out to be narrower than the entry implied:

- *The air hole is only half closed* ([#49](https://github.com/OneLiteFeatherNET/Falco/pull/49)). The
  entry ended on "the decision is where the distinction belongs", and the first attempt put it in the
  wrong place: refusing any full chunk without a `sections` list broke two existing tests, which
  assert that `versionPolicy(null)` means the pre-1.18 check does not run. The entry's own wording
  already had the boundary right — **neither** `sections` **nor** `Level` — and a chunk carrying
  `Level` stays the version policy's business, so a caller who switched that policy off keeps the
  behaviour they asked for. The entry is kept below in full, because the reproduction in it is what
  made the defect actionable.

### Resolved in #34: the opacity tables are rebuilt on every pass

*The entry as it stood is kept unchanged down to* **What was built** *— the measurement in it is
what decided the mechanism was worth building, and it still describes what the recalculating path
pays.*

`ChunkLightArea#read` called `ChunkLightService#opacityOf` for every chunk of the area *and* of its
ring, on every pass, whether or not anything in that chunk moved. Only the sections a change touched
need a new table; the rest is rebuilt from block states identical to the ones the previous pass read.
A ring chunk that nothing marked is the clearest case: read once per pass, per kind of light, purely
to be handed to a border exchange that will produce the same border it produced last time.

**How much that is, measured:** between a sixth and a fifth of a pass.

| Area | whole pass | opacity tables | share |
| ---: | ---: | ---: | ---: |
| 1 | 5532.4 ± 546.9 µs | 1070.9 ± 49.0 µs | 19.4 % |
| 4 | 9524.1 ± 130.3 µs | 1938.6 ± 130.4 µs | 20.4 % |
| 16 | 28010.8 ± 3050.6 µs | 4397.0 ± 174.6 µs | 15.7 % |

<sub>`AreaPassStageBenchmark.wholePass` / `.opacityTablesOnly`, AMD Ryzen 7 5800X, JDK 25.0.3,
3 forks, 3 warmup and 5 measurement iterations of 1 s, single thread. Both methods run over the
identical chunk set, so the share is an upper bound on what a perfect cache could remove — a real one
pays for lookup, invalidation and eviction, and cannot cache the sections a change touched.</sub>

<sub>A single-fork run of the same benchmark gave 21.4 / 19.4 / 18.8 % and looked like a share that
falls as the area grows. It does not: the three-fork figures are not monotone, and the whole-pass
column moved by up to 25 % between the two runs — 22 296 µs against 28 011 µs at area 16, which is
also where the interval is widest. The trend was an artefact of one fork, and the number worth
quoting is the range, not the slope.</sub>

<sub>**Repeated on 2026-08-02** under the same configuration — three forks, same iteration counts,
same machine — giving 5219.1 ± 223.2 / 1137.2 ± 103.6 µs (**21.8 %**), 9790.1 ± 220.2 /
1981.3 ± 62.9 µs (**20.2 %**) and 27008.1 ± 3519.7 / 4585.9 ± 159.5 µs (**17.0 %**). The headline
range is unchanged, and the repeat confirms the caveat above rather than the table: area 16 moved
again in the whole-pass column, and it is again the row with the widest interval. Neither run was
taken on an idle machine — the repeat carried a load average between 4 and 6 on 16 hardware
threads. What two independent
three-fork runs establish here is the *range*, a sixth to a fifth; no single cell of either run is
worth quoting on its own.</sub>

This entry used to say the tables were "the largest remaining cost block by a wide margin". That was
written before `69381af`, which took the per-section table from 31.33 µs to 8.07 µs and left
`propagate` as the dominant stage at 31.41 µs — the two rows are in
[How the light engine works](../explanation/how-the-light-engine-works.md), and this entry was not pulled along with them. The mechanism proposed
below is sound; the ranking was not.

`propagate` has since moved again, downwards: #28 resolves the opposite face once at class load
instead of once per neighbour, which took the same stage under the same parameters from
31.10 / 31.57 µs to 27.25 / 27.39 µs across two independent three-fork runs per side — **−12.8 %**.
That narrows the gap this paragraph is about, from 3.9× the per-section table to 3.4×, without
changing the ranking it corrects: `propagate` is still the larger of the two, and the tables are
still not "the largest remaining cost block by a wide margin".

Keeping the tables has the shape the kept light already has — a bound, a least-recently-used eviction,
and an invalidation driven by the same reported positions — so the mechanism existed and was reused
rather than invented. **And it had to earn a fifth against the risk it carries:** a wrongly
invalidated table produces light that is never recomputed, because writing light clears the update
flag of the section.

#### What was built, and what it turned out to be worth

The tables now live in the same bounded entry as the kept light, tied to a version that
`recordChange` and `forget` move on. The version is read before the block states and stored with
them, so a change landing between the two costs a rebuild rather than leaving a stale table behind.

Two consequences were not in the entry above and are the larger half of the result. A table is not a
*kind* of light, so the sky pass reuses what the block pass built instead of reading every chunk a
second time. And the ring is where most of the saving is: a ring chunk is read by every area it
borders and written by none, and the eight neighbours of an edit are marked precisely because their
light changed **while their own blocks did not** — which `ChunkLightScheduler#markChanged` already
said in a comment while rebuilding their tables anyway.

Counted over the 3×3 group the scheduler forms around one edit, so 9 chunks plus a ring of 16:

| | tables built, before | after |
| --- | ---: | ---: |
| first block pass over the group | 25 | 25 |
| the sky pass that follows it | 25 | **0** |
| a pass after one block changed | 25 | **1** |
| a pass with nothing changed | 25 | **0** |

A steady tick built **50** table sets and now builds **1**. These are counts and carry no `±`: they
are exact, identical on every machine, and unaffected by load or JIT. `ChunkLightArea#tableBuilds()`
is public so a server can ask the same question of its own world.

<sub>`IncrementalVsFullBenchmark.incremental` measured 11886.76 ± 175.24 → 7246.38 ± 91.72 µs at
`sky=false` and 8510.68 ± 102.30 → 4616.11 ± 203.26 µs at `sky=true`, which is **−39.0 %** and
**−45.8 %**. Those are two `ubuntu-latest` runs of the `Benchmark` workflow on **different CPU
models**, so the absolute figures are not comparable and belong in no published table; what makes
the pair readable is that the same jar also measures `full()`, which this change does not touch and
which moved by 6.0 % and 1.1 % between the two runs. The portable form is the ratio inside one job:
`full` ÷ `incremental` went from 1.76× to 2.71× and from 7.39× to 13.47×. Runs 30754926812 and
30754931454, `custom` profile, 3 forks, JMH 1.37, JVM 25.0.3.</sub>

**What it does not cover.** `compute(…)`, the recalculating path, keeps nothing and still builds
every table on every pass. That is its documented contract — a caller with no revisions to offer has
reported no changes either — so it has no basis to reuse anything, and the share measured in the
table above is still exactly what that path pays. What it costs is memory on top of the light, under
the same bound of chunks but not the same size per chunk: a uniform section holds no arrays at all
and a fully mixed one holds 8 KB, so the figure is a property of the world rather than a constant.

### Resolved in #49: the air hole is only half closed

*The entry as it stood is kept unchanged down to* **What was built** *— the reproduction in it is
what made the defect actionable, and it still describes what the loader did before #49. Read "**The
third one stands unchanged**" below as the state at the time the entry was written.*

[#45](https://github.com/OneLiteFeatherNET/Falco/pull/45) named three decisions that combined to let
a world older than snapshot 21w43a load as air with no error and no log line. It closed the
combination of the first two — the status is looked for on the root, and a chunk without a status
counts as generated — by refusing the pre-21w43a layout outright. **The third one stands
unchanged**: `NbtReads.optionalList` returns `ListBinaryTag.empty()` for a missing or mistyped key
(`NbtReads.java:162-167`), and `decodeSections` still reads `sections` through it
(`FalcoAnvilLoader.java:1506`).

So a chunk carrying `Status: minecraft:full` and **neither** `sections` **nor** `Level` passes the
guard and reaches the caller as air. Reproduced during the review of #45 rather than argued:
`chunksLoaded()` reports 1, while `errors()` and `chunksSkippedAsUnsupported()` report 0 and every
block is `AIR`.

This is a different defect from the one #45 fixed, and the two are worth keeping apart. #45 is about
a world in an *old* format; this is a truncated or damaged chunk in the *current* one. It is
pre-existing, #45 does not worsen it, and nothing in the code, the README or the documentation claims it is
closed. What makes it consequential anyway is the failure mode both share: the caller is handed a
chunk that looks generated, and a save can then write that emptiness back.

The fix is not obviously "make `optionalList` throw" — the method exists precisely so that an absent
optional key is not an error, and other callers depend on that. The decision is where the
distinction belongs: a chunk claiming `minecraft:full` while carrying no sections is contradictory in
a way that an absent optional key generally is not.

**What was built.** `FalcoAnvilLoader#requireSections`, run after the status check and before
anything is decoded. `optionalList` is untouched: it cannot know what its caller considers required,
and the contradiction is only visible where the status has already been read.

The entry left open where the distinction belongs, and the first attempt answered too broadly.
Refusing *any* full chunk without a `sections` list broke `testWithoutAnyPolicyALegacyChunkIsNotChecked`
and `testAPolicyThatAllowsEverythingLetsALegacyChunkThrough` — two tests that assert
`versionPolicy(null)` means the pre-1.18 check does not run. That is a documented promise, and taking
it back silently would have been a second defect of the same kind as the first. The entry's own
wording already had the boundary: **neither** `sections` **nor** `Level`. A chunk carrying `Level` is
the old layout and stays the version policy's business; a chunk carrying neither is a truncated
current one, and only that is refused, with
`ChunkDataException.Reason.MISSING_OR_MISTYPED_KEY`.

Two further boundaries fell out of it. An honestly unfinished chunk carries no sections either, and
for it that is the normal state of a world edge — so it stays a skip, which is also why this check
cannot live in the version policy, which runs before the status is known. And an empty `sections`
list is accepted: an empty list is a statement that this chunk has none, while an absent key is the
absence of a statement, and refusing the empty list would reject legitimately empty chunks written by
other tools.

The tests assert the counters as well as the exception. The defect's signature was a chunk that
counts as loaded while holding nothing, so a fix that threw and still counted it would have left the
misleading half of the report standing.

### 1. Two things that are argued rather than tested

- **Stale header entries.** `locations` and `timestamps` are `AtomicIntegerArray` now, so a reader
  cannot see a stale `0` and turn a present chunk into a regenerated one. That is ruled out by
  construction, not by a test — a JMM staleness window cannot be provoked deterministically, because
  any harness that tries introduces synchronisation edges of its own.
- **The open-handle limit is no longer a hard cap on descriptors.** It bounds the *cached* files;
  a handle in use by a thread stays open beyond it for the duration of that access. Deliberate, and
  documented at the field, but it means the limit is a cache size and not a resource guarantee.

### 2. Smaller items

- **The version floor rests on an unverified span.** [#45](https://github.com/OneLiteFeatherNET/Falco/pull/45)
  sets the floor at DataVersion 2844, the snapshot 21w43a that moved `sections` onto the root, and
  that boundary is belied by `minecraft.wiki/w/Java_Edition_21w43a`. What could **not** be
  established is whether further decoder-relevant format changes lie between 2844 and the 1.18
  release at 2860 — the wiki marks its chunk-format history incomplete for exactly that stretch. The
  loader therefore accepts worlds from 2844 upwards and reads them with the current schema; it has
  no data fixer, so a world in that span is accepted on the strength of a gap in the sources rather
  than on evidence. The layout check catches the pre-21w43a shape regardless, so a wrong floor
  misleads a message rather than changing behaviour.
- **A mistyped `DataVersion` is labelled `-1` in the diagnostics breakdown.**
  `NbtReads.optionalInteger` cannot distinguish "absent" from "wrong tag type" and returns its
  sentinel for both. `requireReadableVersion` makes the distinction itself for the *decision* — a
  missing version loads, a mistyped one is refused — and the exception message names the two cases
  apart, but `unsupportedChunkVersions()` records the mistyped chunk under `"-1"`, where a genuine
  `DataVersion` of `-1` would also land. Both are correctly refused; only the label is imprecise.
- **`FalcoChunk#tick` walks its tickable map without a lock**, and `Int2ObjectOpenHashMap` is not
  thread-safe: a concurrent `setBlock` that rehashes the map while the tick thread walks it can yield
  garbage or spin. Inherited rather than introduced — `DynamicChunk` has the identical race with its
  own map — and Minestom's `Chunk#tick` contract says outright that the method "doesn't necessary
  have to be thread-safe". ArchUnit cannot see it, because the field is `final` and the rule that
  would catch it skips final fields by construction. Fixing it is a design choice with a cost on a
  path that runs for every chunk every tick: take the read lock in `tick`, make the map concurrent,
  or confine writes to the chunk's tick thread.
- **`:falco-benchmarks:test` hangs on macOS and is skipped there.** The test JVM starts and no test
  ever reports; the module writes zero-byte result files and the runner terminates orphan `java`
  processes at the end. It is the only module whose test JVM runs with `allowAttachSelf`,
  `EnableDynamicAgentLoading`, `jol.magicFieldOffset` and a 4 GB heap on a 7 GB runner, which is what
  jol needs to measure retained size — but **which of those hangs on arm64 is not diagnosed**. The
  counted figures are therefore proven on two of three platforms, and a regression that only appears
  on arm64 would pass unnoticed. `-Pfalco.macOsFootprintTests` forces the task back on.
- **No instance benchmark has ever been run as a baseline.** `falco-benchmarks` has gained JMH
  classes for the instance and the chunk, but the full run takes over two hours and refuses to start
  above a load average of 1.5, and no idle machine has been available. Until it has run, no timing
  figure about the instance work belongs in this documentation, and none is in it.
- Border exchange between chunks settles one ring deep; a fully converged result over a large area
  needs the exchange repeated. Both parts that were outright wrong are fixed: writing the ring back,
  and the ring not reaching the diagonal chunks of the area (#24). What remains is the depth of the
  exchange itself, which is deliberate.
- An area split at `maxAreaSize` leaves a seam between its two parts for one tick, because each part
  reads the other as its ring and only the next pass settles it. Deliberate — the alternative is an
  unbounded area — and it is the reason the cap is a constructor argument rather than a constant.
- `ChunkLightScheduler` serves exactly one instance and refuses a second with an
  `IllegalStateException`. That is the honest behaviour given a dirty set keyed by chunk coordinates
  alone, but it means a server with several worlds builds one scheduler per world and nothing in the
  type system says so.
- `FalcoInstance#unregister` gives up after four sweeps and logs what it left behind rather than
  looping until the world stops changing. A caller that keeps requesting chunks during a shutdown is
  a caller error and is not covered; a shutdown that never returns would be worse than one that
  reports a leak.
- A **full** sky propagation still queues every open cell of a column rather than seeding from a
  heightmap. Measured at 79.3 → 55.1 µs in the rebuild, with byte identity verified over 240 worlds.
  An *update* no longer does this — `ChunkLightState` keeps the height at which each column stops the
  sky and walks only the column that moved, which is most of why the incremental sky figure under
  [Measured](../reference/measured-results.md#measured) is the larger of the two gains — but every propagation that
  starts from block states
  still pays it.
- `SectionOpacity` still builds its table unconditionally for non-uniform sections. Since `69381af`
  that costs 8.07 µs instead of 31.33, measured in
  [Where the time goes in the light path](../reference/measured-results.md#where-the-time-goes-in-the-light-path)
  and carrying no
  interval there, and the empty section with one source is no longer the row
  that loses — but the table is still built whether or not it is read more than once. The same waste
  one level up was "was this table already built last tick", and #34 answered that one. This is the
  question *inside* a single pass and is untouched by it: on the recalculating path, and on the
  first pass over any chunk, the table is still built in full before anyone knows how much of it is
  read.
- `seed` walks all 4096 positions a second time only to find the emitters. Writing them during the
  table build saves about 3.6 µs in the rebuild, plus the 2.0 µs of a second `byte[4096]` that then
  becomes unnecessary, at the price of changing the API of `SectionOpacity` and both propagators.
  Both figures are from the rebuild and neither carries an interval. Left open on purpose for that
  reason.
- ~~Column opacity is a `List.get(y >> 4)` plus a virtual call rather than one flat `byte[]`, and
  the search neither skips the direction an entry arrived from nor tests the level before the
  opacity.~~ **Done in [#36](https://github.com/OneLiteFeatherNET/Falco/pull/36)**, and these are
  the first of the rebuild figures to be measured by this suite. The rebuild predicted −26 % on
  searching a whole column, −7 to −16 %, and −6 %, none of them with an interval. The three together
  measure **−27.3 % to −39.7 %** on `ChunkLightPropagatorBenchmark.propagate` and **−7.3 % to
  −22.0 %** on `LightEngineStageBenchmark.falcoPropagate` — every configuration of both faster,
  every interval disjoint. The direction the rebuild gave was right and its magnitude was, if
  anything, understated.

  <sub>Two `custom` runs of the `Benchmark` workflow, three forks, `2375f68a` against `237d60a`,
  both on a 4-core `ubuntu-latest` EPYC 9V74. What makes a shared runner readable here is that the
  same jar and the same job measure stages neither commit touches: `falcoReadStates` moved by
  −0.2 % to +1.7 %, with error bars of a hundredth of a microsecond on ten, and `minestomFull` by
  −0.9 % to +7.6 %. Absolute microseconds from these runs belong in no published table; the
  direction and the margin over the control do. Runs 30758921758 and 30758926454.</sub>

  **Which of the three changes earned how much**, from a third run of the same pair of classes on
  the first two changes alone. The first attempt at this was thrown away: it degraded partway
  through, and the only reason that is known is that the same job measures `minestomFull`, which
  read +64.8 % on a class no commit here touches. The re-run has `minestomFull` inside 1 % and is
  the one quoted.

  | | reordering and the skip | the flat column adds |
  | --- | ---: | ---: |
  | `falcoPropagate`, all six | −9.0 % … −21.5 % | −1.3 % … +1.9 % |
  | `propagate`, all six | −16.3 % … −30.5 % | −8.2 % … −14.5 % |
  | `propagateSky`, 16 and 24 sections | −12.1 % … −19.1 % | −13.1 % … −15.9 % |
  | `propagateSky`, **4 sections** | −10.9 % … −13.5 % | **+11.4 % … +18.2 %** |

  The first row is a control with an expected answer of zero and it delivers zero: the flat column
  lives in `ChunkLightPropagator`, so the section propagator must not see it, and it does not.

  **The last row is a real regression, not noise.** The earlier pair made it look like noise — two
  configurations disagreeing in direction with huge error bars. On a clean run both agree, and the
  tighter of the two reads +11.4 % at ± 7.32. It is sky-only: block light at four sections still
  gains −9.6 % from the flat column, so it is not the fill alone but the fill together with
  `seedSky`, which queues nearly every position of an open column and now reads the flat array where
  a uniform transparent section used to answer from a field without touching memory.

  It was kept, and the reason is arithmetic rather than tolerance: at four sections the sky pass
  costs **+22 µs**, at twenty-four it saves **−733 µs**, and a real overworld chunk is twenty-four
  sections. `sectionCount = 4` brackets the range, it is not a height a world has. The targeted fix
  — leave `seedSky` reading through the sections and keep the flat array for the search — would
  probably recover it at the price of a second code path, and is worth doing only if four-section
  columns turn out to matter to somebody.

---

## Investigated and deliberately not built

The rejections are the part of this record that is hardest to fake and easiest to check, so each one
below states **what it was rejected on**: a measurement, a structural property of the source, or
neither. "Neither" appears once and is written as such rather than dressed up as a finding.

### Replacing parts of Minestom

Three "replace this part of Minestom" questions were researched before any code was written. The
answers differed sharply and none was obvious in advance. These three are questions, not documents:
[Research](research/README.md) catalogues **five** documents, of which two came later and are not part of this
round, and the palette answer below has no document of its own — it is a compiler result, recorded
here and nowhere else.

| Subject | On what basis | Verdict |
| --- | --- | --- |
| **Palette** | Structural, verified with `javac` and at runtime | Impossible. `sealed interface Palette permits PaletteImpl` is a hard compiler error, and `Section` is a record holding that exact type. |
| **`InstanceContainer`** | Structural for the "possible" half, measured for the "pointless" half | Possible but pointless **as asked**. It compiles and runs, but four `instanceof InstanceContainer` sites silently take another path for a foreign type, and the tick parallelism the request targeted lives in the global `ThreadDispatcher`, not in the container. `falco-instance` was built later for a different reason and **still claims none of the speed the original question was about**. What it has since gained is a memory result rather than a throughput one: a chunk that allocates sections on demand retains 25 objects and 840 bytes against 192 and 6 848 — see [Instance](#instance) above and [Counted](../reference/measured-results.md#counted). The verdict on the original question is unchanged, because that question was about time. |
| **Light engine** | Measured, but by a standalone probe rather than by this suite — the load-path breakdown in [Research: Light Engine](research/light-engine.md) put the two workloads that a faster engine touches at 2.2 ms per relit chunk and 0.586 ms per block placement, against 0 % for loading a pre-lit world. That probe recorded no machine, no run configuration and no uncertainty, and cannot be re-run from this repository; [Research](research/README.md) says so of every number in those documents. What the figures established is which workload to look at, not how much was there to win | Possible and worth it — this is what was built. |

The recurring lesson: **sealed-ness decides whether it is possible, and the profile decides whether
it is worth it.** Both have to be checked before designing anything, and neither can be guessed.

### Importing a foreign light algorithm

A later round asked whether an algorithm from another engine would close the gap to Minestom that
existed at the time. None would have: the gap was not in the search but around it, and the stage
benchmark has since supported that — `propagate` measured 33.85 µs before the change, against a
derived 53.9 µs for Minestom's search, and closing the gap took a table without allocations rather
than another algorithm.
[Claims about other light engines](#facts-that-cost-real-effort-to-establish) refutes the individual
leads by reading those engines' sources. The verdicts below exist so that nobody walks the same road
again.

| Subject | On what basis it was rejected | Verdict |
| --- | --- | --- |
| **Starlight, wholesale** | Structural, by reading `SWMRNibbleArray` and the vanilla storage it replaces | Nothing left to take. The push-instead-of-pull BFS is already here, and the remainder of Starlight's gain is specific to how vanilla stores light. |
| **Bit-slicing light levels across voxels** | Neither. **Nothing was measured and nothing structural rules it out** | No precedent in any engine, so it would be an original design with an unproven benefit and no reference implementation to compare against. This is a decision not to spend the effort, not a finding. |
| **Parallelising the BFS of a single chunk** | Measured, from the round of investigation that produced the 50–150 µs figure — a probe figure with no recorded machine or configuration, like every number in [Research](research/README.md). It does not have to carry on its own: the order of magnitude is confirmed by every published row of [Against the engine Minestom ships with](../reference/measured-results.md#against-the-engine-minestom-ships-with), whose twelve *Falco after* and *Minestom* cells put one section's whole light path between 39.3 and 206.6 µs/op, every one of them with a half-width under 5 % of its own mean | Not worth it. Handing a unit of work that size to another thread costs more than it saves. Minestom parallelises across chunks, which is the right granularity. |
| **Vector API** | Structural, and about packaging rather than performance | Ruled out regardless of what it might buy: without `--add-modules jdk.incubator.vector` `javac` refuses, with it the runtime prints an unsuppressable warning, and a JAR manifest has no attribute to carry the flag — so every consumer would have to set it. **No performance measurement was taken, and none would change the answer.** |
| **A bucket queue (Dial)** | Measured in the standalone rebuild only — **no JMH measurement of a bucket queue exists** | No longer undecided for lack of an instrument. The rebuild puts it at 5–7 % *slower* with sources of equal brightness and 32–36 % faster with mixed brightness; both are rebuild figures, so directions rather than factors. Since `0e8fbb5` the JMH suite produces the mixed case as well: in [With sources of mixed brightness](../reference/measured-results.md#with-sources-of-mixed-brightness) the two rows with solid blocks keep a supported lead of about 1.7×, while both open-sky rows fall to overlapping intervals, which is where a bucket queue would be expected to help. The prerequisite is met; the change itself is still not made. |

---

## Documents

| Page | Contents |
| --- | --- |
| [Measured results](../reference/measured-results.md) | The measurement environment, and every published table with the provenance line that states its conditions |
| [Contributing](../contributing/README.md) | The declared versions, the Gradle commands, the conventions the build enforces, and what a push to `main` publishes |
| [How the Anvil loader is built](../explanation/how-the-anvil-loader-is-built.md) | Usage, architecture, 20-row comparison with the built-in loader, limits |
| [How the light engine works](../explanation/how-the-light-engine-works.md) | Usage, design, where resources are saved, limits |
| [What the benchmarks establish](../explanation/what-the-benchmarks-establish.md) | How to run the benchmarks and what each measures |
| [What a measurement here means](../explanation/what-a-measurement-here-means.md) | What the `±` means, what one fork does and does not cover, and why the numbers on this page are believable at the precision they are stated |
| [Research](research/README.md) | The research documents, how each was produced, and how much weight its findings carry — the structural ones do, the probe numbers do not |

## References

Sources for the claims on this page, all readable without running anything.

- Benchmark conditions — the annotations each benchmark class declares, and the command-line
  overrides some runs used instead, are cited beside the tables they belong to, under
  [References](../reference/measured-results.md#references).
- Build and dependency wiring —
  [`settings.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/settings.gradle.kts)
  for the version catalog and the seven modules,
  [`falco-benchmarks/build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/build.gradle.kts)
  for the JMH configuration, `check` depending on `compileJmhJava` and nothing depending on `jmh`.
- Byte identity between the two light engines — the test that runs the 54 scenarios on every build,
  and the check the benchmark performs before each trial, are cited under
  [References](../reference/measured-results.md#references).
- Chart data —
  [`docs/charts/generate.mjs`](https://github.com/OneLiteFeatherNET/Falco/blob/main/docs/charts/generate.mjs),
  where every bar is a literal that can be compared against the table it claims to draw.
- The citation convention this page follows for Minestom and for Falco is stated under
  [References](../reference/measured-results.md#references), and the reason the two differ is in
  [How the Anvil loader is built](../explanation/how-the-anvil-loader-is-built.md).
