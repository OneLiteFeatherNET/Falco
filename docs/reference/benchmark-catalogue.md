# Benchmark catalogue

Every benchmark class in `falco-benchmarks`, its kind, its parameters and what it answers. Plus the
harness versions the suite is built on.

> **This page is a generation target, currently written by hand.**
> Source of truth: the `@Param` annotations and class names under
> [`falco-benchmarks/src/jmh`](https://github.com/OneLiteFeatherNET/Falco/tree/main/falco-benchmarks/src/jmh),
> and the pins in `settings.gradle.kts`.

### Anvil

| Benchmark | Kind | Parameters | What it answers |
| --- | --- | --- | --- |
| [`BitPackerBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/BitPackerBenchmark.java) `.pack` `.unpack` `.roundTrip` | library | `bitsPerEntry` 4, 5, 8, 15 | What the packing loop of one section costs. It runs once per section on every load and every save, so a full-height chunk pays it 24 times. The parameter is the only thing that changes the iteration count per long. |
| [`PaletteDataBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/PaletteDataBenchmark.java) `.encode` `.unpack` `.roundTrip` | library | `distinctStates` 1, 8, 64, 200 | What collecting a palette costs as the section gets busier. `1` is a section of pure air or pure stone — the majority of every world — and is answered without packing anything. `200` already needs eight bits per entry. |
| [`ChunkCompressionBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/ChunkCompressionBenchmark.java) `.compress` `.decompress` | library | `compression` ZLIB, GZIP, NONE × `distinctStates` 8, 200 | The most expensive single stage of a chunk transfer, and the one the loader deliberately performs outside every lock. The payload is real serialised chunk NBT built through the same codec the save path uses, not random bytes — random bytes do not compress and would make zlib look far worse than it is. |
| [`RegionFileBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/RegionFileBenchmark.java) `.writeRaw` `.readRaw` `.roundTrip` | library | – | The byte transfer alone, on an already compressed payload. `readRaw` uses positional channel reads and takes no lock; `writeRaw` takes the region lock for the sector allocation and the header update. Not a statement about a disk: the page cache is warm throughout. |
| [`ChunkSaveStageBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/ChunkSaveStageBenchmark.java) `.snapshot` `.codec` `.codecWithoutCompression` `.transfer` `.full` | library | `distinctStates` 8, 200 | **The interesting one.** It splits a whole chunk save into its stages and records which of them holds a lock, which is what turns the loader's structural claim into a number. The split is set out directly under this table. |

`ChunkSaveStageBenchmark` splits a whole chunk save into the three stages it consists of, because
the central claim of the loader is structural and this is what turns it into a number:

| Stage | Lock held |
| --- | --- |
| `snapshot` — copy the section arrays | the read lock of the chunk; a game thread waits here |
| `codec` — palettes, packing, NBT, compression | **none** |
| `transfer` — hand the finished bytes to the region file | the region lock, for the allocation and the header |
| `full` — all three, so the sum can be checked against the whole | – |

`codecWithoutCompression` splits the middle stage again — but not into a palette half and a zlib
half, as its javadoc says. It returns a `CompoundBinaryTag` and never serialises it, so the
difference against `codec` covers NBT serialisation as well as compression. `full` is what the
decomposition should be checked against, and it has never been published.

The chunk is a [`ChunkColumn`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/support/ChunkColumn.java)
of plain arrays, not a Minestom chunk. A real `Section` needs a started server, and the registry time
would land inside the `codec` stage and hide the very thing the benchmark isolates.

### Light

| Benchmark | Kind | Parameters | What it answers |
| --- | --- | --- | --- |
| [`LightNibblesBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/LightNibblesBenchmark.java) `.getUniform` `.getAllocated` `.setUniformUnchanged` `.setAllocating` `.ofArray` | library | – | What the uniform shortcut is worth. A section whose blocks all carry the same level keeps no array and answers from a field; every other section shifts a nibble out of 2048 bytes. Most sections of a world are completely dark or completely sky-lit, so the shortcut is the common path, not the exception. |
| [`SectionOpacityBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/SectionOpacityBenchmark.java) `.of` | library | `distinctStates` 1, 8, 64, 200 × `resolveCost` 0, 50 | What "resolve each distinct state once" is worth. `distinctStates` sets how often the cache misses; `resolveCost` sets what a miss costs. At `resolveCost = 0` you are measuring the hash map and the two array writes per block, so the table looks like pure overhead. At `resolveCost = 50` you are measuring what it saves: seven resolutions per distinct state instead of seven per block. What 50 tokens corresponds to on a real registry is not established. |
| [`LightPropagatorBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/LightPropagatorBenchmark.java) `.propagate` | library | `lightSources` 0, 1, 8, 64 × `occlusionPercent` 0, 25 | How the single-section search scales with the amount of queued positions. `lightSources = 0` is answered without a search at all, which is the case for the overwhelming majority of the sections of a world. `occlusionPercent = 25` is there because solid blocks stop the search early — measuring only an open section reports the worst case and calls it normal. |
| [`ChunkLightPropagatorBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/ChunkLightPropagatorBenchmark.java) `.propagate` `.propagateSky` | library | `sectionCount` 4, 16, 24 × `lightSourcesPerSection` 1, 8 | The same search across section borders, over a flat map (4), a shallow world (16) and a full-height overworld (24). Both searches are measured because the engine runs both per chunk and they behave very differently: block light is bounded by the amount of emitting blocks, sky light seeds nearly every block of an open column. |
| [`AreaVsPerChunkBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/AreaVsPerChunkBenchmark.java) `.area` `.perChunk` | decision | `chunkCount` 1, 4, 9, 16 | Whether area forming earns its complexity: an area of *n* chunks against *n* separate `calculateWithNeighbours` calls. Written as a decision rather than a report — had it not held, the simpler per-chunk design was the better one and area forming was to be dropped rather than tuned. The `chunkCount = 1` row is the control: a lone chunk has no loaded neighbours, so neither side has a ring to read and the two must come out level. |
| [`IncrementalVsFullBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/IncrementalVsFullBenchmark.java) `.incremental` `.full` | decision | `sky` false, true | Whether replaying a changed position earns the memory the kept light costs. Both sides toggle the same block, compute the same nine chunks plus the same ring and write into the same sections; only the origin of a chunk's light differs. The block is *toggled* rather than placed, so both directions of an incremental update are measured — adding brightness and taking it back — and the world alternates between two states instead of drifting. `sky` is a parameter because a tick pays for both kinds and they gain very differently. |
| [`AreaPassStageBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/AreaPassStageBenchmark.java) `.wholePass` `.opacityTablesOnly` | decision | `chunkCount` 1, 4, 16 | How much of an area pass is spent building opacity tables rather than propagating light. The ratio of the two methods is the share a perfectly effective table cache could remove, an upper bound. It loads and fills the ring around the area, which `AreaVsPerChunkBenchmark` leaves out, because the ring is read once per pass and is the part a table cache would pay for. |

Both propagators keep their working buffers between runs, so the benchmarks reuse one instance for
the whole trial and warm the buffers in `@Setup`. A fresh instance per invocation would measure two
array allocations instead of the search. The residual cost is that the measured path never pays for
growing a buffer, which is realistic for a running server and not for the first chunk after startup.

The last two start a server, unlike everything else in this table, because an area and a scheduler
pass are defined over real chunks of a real instance. They are two of the three classes in the
harness that set no heap flags; the third, `ChunkLookupBenchmark`, has no `@Fork` annotation at all
([Instance](#instance)). Their numbers rest on a default, unrecorded heap configuration.
`ChunkLightService` has no benchmark of its own; what it costs is covered by those two and by the
comparison benchmarks below.

### Instance

| Benchmark | Kind | Parameters | What it answers |
| --- | --- | --- | --- |
| [`ChunkComparisonBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/ChunkComparisonBenchmark.java) `.minestomSetBlock` `.falcoSetBlock` `.minestomGetBlock` `.falcoGetBlock` `.minestomCopy` `.falcoCopy` `.minestomCopyIsolated` `.falcoCopyIsolated` `.minestomHeightmapRefresh` `.falcoHeightmapRefresh` | control | `distinctStates` 1, 2, 16, 64, 256, 1024 × `fillShape` UNIFORM, LAYERED, RANDOM_RUNS | Falco's chunk against Minestom's chunk on `setBlock`, `getBlock`, `copy` and the full heightmap refresh. The expected answer is that the two are indistinguishable, which makes it the control that shows whether the harness is biased before any other chunk number is read. Starts a server. |
| [`ChunkLookupBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/ChunkLookupBenchmark.java) `.boxedLookup` `.primitiveLookup` `.boxedLoadAndUnload` `.primitiveLoadAndUnload` | library | `positions` 289, 1089, 4096 | The boxed chunk index (`Map<Long, Object>`) against the primitive `Long2ObjectSyncMap`, on lookup and on load and unload. Both directions are measured on purpose: the lookup is what the change is for, and the write is where the primitive map is expected to cost more. The boxed arm's allocation includes `HashMap` treeification, not only the box. |
| [`ChunkResendCostBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/ChunkResendCostBenchmark.java) `.buildOneChunkPacket` `.serializeOneChunkPacket` `.buildAndSerializeOneChunkPacket` `.resendViewDistance10` | decision | `content` EMPTY, UNIFORM, TERRAIN, DENSE | What a full chunk resend at view distance 10 costs in time, bytes on the wire and allocated heap. It is the number behind the `SharedInstance` question: whether a player changing instance has to resend chunks that are already on the client. |
| [`GeneratorCommitBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/GeneratorCommitBenchmark.java) `.commitPlain` `.commitOptimized` `.commitGuarded` `.packAlreadyPacked` `.optimizeAlreadyPacked` | library | `distinctStates` 1, 64, 1024 | What `Palette#optimize(Optimization.SIZE)` costs on the palettes of a chunk a generator has just filled, measured next to the copy it follows rather than on its own. The byte side is stated in the class javadoc from an earlier measurement and is not measured again here. |
| [`LazySectionBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/LazySectionBenchmark.java) `.readEmptyEager` `.readEmptyLazy` `.readFullEager` `.readFullLazy` `.steadyWriteEager` `.steadyWriteLazy` `.firstWriteLazy` `.buildSectionsMinestom` `.buildSectionsEager` `.buildSectionsLazy` | decision | `emptyPercent` 0, 62, 90 | What a chunk pays for twenty-four eagerly allocated sections against one shared empty section that is materialised only when something is written into it. Reads, steady writes, the first write and building are measured separately, because the lazy layout is only worth it where the saving outweighs the first-write cost. |
| [`PaletteIndirectGetBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/PaletteIndirectGetBenchmark.java) `.minestomGet` `.arrayGet` `.minestomReverseLookup` `.arrayReverseLookup` `.fastutilIndexGrowth` `.arrayIndexGrowth` | library | `paletteSize` 16, 32, 64, 128, 192, 256 | The indirect read path of Minestom's block palette at the widths between 5 and 8 bits, its reverse lookup and the growth of the palette, each against a plain array structure. Minestom's own palette benchmarks do not cover these three. |
| [`SectionAllocationBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/SectionAllocationBenchmark.java) `.minestomDynamicChunk` `.eagerReplicaLight` `.packedFlagLight` `.sharedUnlitLight` `.sharedAirSection` | library | – | What constructing one chunk costs, split into its sections, the palettes inside them and the light carriers hanging off them, before a single block is written. A fresh Minestom overworld chunk allocates 24 sections, 48 palettes, 48 light carriers and 48 `AtomicBoolean`s. |
| [`SetBlockContentionBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/SetBlockContentionBenchmark.java) `.minestom` `.falco` | library | `scenario` DISJOINT_CHUNKS, SAME_CHUNK, DISJOINT_CHUNKS_WITH_HANDLER | Block writes per second that `InstanceContainer` and `FalcoInstance` accept while several threads write at once. The one difference this module can defend with a number is Minestom's instance-wide monitor in `UNSAFE_setBlock`, which the scenarios expose by separating writes to disjoint chunks from writes to one chunk. Starts a server. |
### Against the implementations Minestom ships with

These are the ones that answer "is this actually better", and they are the reason the
[loader](../explanation/how-the-anvil-loader-is-built.md) and [light engine](../explanation/how-the-light-engine-works.md) documents can state factors
instead of intentions. Each measures the original, not a reimplementation of it — which is why three
of them live in Minestom packages, where the measured types are package-private.

| Benchmark | Parameters | What it answers |
| --- | --- | --- |
| [`RegionFileComparisonBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/minestom/server/instance/anvil/RegionFileComparisonBenchmark.java) `.falcoRead` `.minestomRead` `.falcoWrite` `.minestomWrite` | `distinctStates` 8, 200, and the JMH thread count | The central claim of the loader: what the lock granularity is worth. Run it with `-t 1` and no difference is resolvable; the difference appears only under contention, which is why the thread count is the parameter that matters here. There is no `@Threads` annotation anywhere in the harness, so a plain `./gradlew jmh` measures this at one thread and never exercises the claim at all. |
| [`ChunkSaveComparisonBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/ChunkSaveComparisonBenchmark.java) `.falcoSave` `.minestomSave` `.compressFalcoLevel` `.compressMinestomLevel` | `distinctStates` 1, 16, 64, 256, 1024 | Whether the palette handling shows up in a whole chunk save. Both sides run the identical Adventure writer over byte-identical payloads. The compression level is deliberately **not** equalised, because the levels differ in what the two loaders actually ship — Minestom 6 against `ChunkCompression.DEFAULT_LEVEL = 2` — and the two `compress*` methods exist so that difference can be subtracted out. **Ten of its twenty configurations are published** — four methods over five `distinctStates` levels make twenty, and the ten belonging to `.falcoSave` and `.minestomSave` appear in [How the Anvil loader is built](../explanation/how-the-anvil-loader-is-built.md) under *Measured: saving a chunk*, as five table rows carrying both loaders side by side. Exactly one of those rows resolves a difference, and that row is confounded by the compression level. What is unpublished is the `compress*Level` pair that would remove the confound, without which the palette claim cannot be separated from the zlib level. |
| [`LightEngineComparisonBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/minestom/server/instance/light/LightEngineComparisonBenchmark.java) `.falco` `.minestom` | `lightSources` 1, 8, 64 × `occlusionPercent` 0, 30 × `emissionMix` UNIFORM, MIXED | By how much each light engine wins, and on what shape of section. All three parameters are needed: the margin moves with each of them. |
| [`LightEngineStageBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/minestom/server/instance/light/LightEngineStageBenchmark.java) `.falcoReadStates` `.falcoOpacity` `.falcoPropagate` `.falcoCollect` `.falcoFull` `.minestomQueue` `.minestomFull` | `lightSources` 1, 8, 64 × `occlusionPercent` 0, 30 | *Why* one of them wins, which the comparison never says. It splits the Falco path into reading the palette, building the opacity table, searching and packing, and the built-in path into building the seed queue and the rest. This is what identified the allocation the opacity table used to make, and it is the source of the stage table in [Comparing the light engine with Minestom's](../explanation/comparing-the-light-engine-with-minestoms.md#where-the-gain-came-from). |

`emissionMix` decides whether every source of the section emits level 15 (`UNIFORM`, glowstone
throughout) or whether the sources differ (`MIXED`: glowstone 15, lantern 15, torch 14, redstone
torch 7, magma block 3, at the same positions drawn from the same seed). The Falco search assumes its
queued positions are ordered by level, which only holds while every source starts at the same one, so
this parameter is what would show whether a bucket queue is worth adding. One cell of the cross
product measures nothing: with a single source `MIXED` places glowstone as well and is a duplicate of
`UNIFORM`. The recommended run therefore leaves it out — the commands are in
[Reproducing a published table](../how-to/reproduce-a-published-measurement.md).

**The comparison verifies the two engines agree before it measures them.** Its `@Setup` runs both
paths over the section it just built and aborts the trial when the 2048 bytes differ, so a faster
number cannot come from computing something else. `LightEngineEquivalenceTest` pins the same property
down in the normal test run, over 54 scenarios. Both are recent: the byte identity was stated in
[How the light engine works](../explanation/how-the-light-engine-works.md) long before anything in the build checked it.

Because three of them start a server, their absolute numbers include registry time and are **not**
comparable with the library benchmarks above. Compare them only against their own counterpart.

### Scaling beyond the sizes anyone uses

[`ScalingBenchmark`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/ScalingBenchmark.java) measures
this library against itself rather than against Minestom, along two axes that the other benchmarks
sample too coarsely to expose a bend in:

| Method | Parameter | What it answers |
| --- | --- | --- |
| `blockLightBySectionCount` `skyLightBySectionCount` | `sectionCount` 1 … 256, fifteen steps | Whether cost per section stays flat as the world grows taller. Block light does across the whole range. Sky light does not: a least-squares fit over the vanilla range (≤ 24 sections) understates the measured cost at 256 sections by about 20 %, while the same method lands within 2 % for block light. |
| `paletteByDistinctStates` `packingByDistinctStates` | `distinctStates` 1 … 1024 | The same question for the codec as a section fills up. |

Fifteen steps rather than three, because the point of this benchmark is to find where a curve stops
being straight — and that is exactly what a coarse parameter set hides.

Two things about this class a reader should weigh before quoting it. It is the only class in the
harness that measures **three** iterations rather than five, which makes its intervals the widest in
the suite for a given dispersion — and no table from it is published, so those intervals cannot be
inspected. And the extrapolation claim is a fit with unpublished residuals extended ten times beyond
its support: "about 20 %" is as precise as it can honestly be stated. The durable part of the finding
is the mechanism rather than the number — sky light seeds a queue at every open cell, so its work
grows with the volume it can see, and that is why the curve bends where block light's does not.

The class also contains an unclaimed repeat. `sectionCount` and `distinctStates` share one state
class, but `blockLightBySectionCount` and `skyLightBySectionCount` never read `distinctStates`, and
the two codec methods never read `sectionCount`. Every light measurement in the class is therefore
already performed five times over, and every codec measurement fifteen times, each as an independent
JMH trial with its own warmup. The spread across those repeats is a direct empirical estimate of
exactly the run-to-run variability the single-fork objection is about, and it costs one run and no
code change to recover.


## Notes on the setup

| Piece | Version | Why |
| --- | --- | --- |
| [`me.champeau.jmh`](https://github.com/melix/jmh-gradle-plugin) | `0.7.3` | Latest release. Gives the benchmarks their own source set so JMH never lands on the main or test classpath of a published library. |
| `org.openjdk.jmh:jmh-core` | `1.37` | Latest release. **Not managed by `mycelium-bom`** — the BOM covers adventure, minestom, cyano, junit and mockito only — so both versions are pinned explicitly in `settings.gradle.kts`. |
| `net.kyori:adventure-bom` | `5.2.0` | The main source set gets adventure through `compileOnly(minestom)`, which never reaches a runtime classpath. The benchmarks run their code for real and need adventure at runtime, so they import the platform directly. **Keep this in sync with the version Minestom resolves to** — check with `./gradlew dependencyInsight --configuration compileClasspath --dependency adventure-nbt`. |
| `net.minestom:minestom` | not pinned here | Declared `withoutVersion()` and resolved through `mycelium-bom` `1.8.9`, which resolves `2026.10.07-26.2`. The published comparisons were measured against `2026.06.20-26.1.2`, not against that version; a republication of the BOM moves the Minestom side without any commit in this repository. |

The plugin generates the harness classes with its bytecode generator. No `jmhAnnotationProcessor` is
declared on purpose: with both present, the two generators emit the same classes and the jar ends up
with two copies of every benchmark.

On Java 25, JMH 1.37 prints a warning about `sun.misc.Unsafe::objectFieldOffset` being terminally
deprecated. It is harmless and comes from JMH itself, not from this repository.


Related: [How-to Run the JMH benchmark suite](../how-to/run-the-jmh-benchmark-suite.md) ·
[Explanation What the benchmarks establish](../explanation/what-the-benchmarks-establish.md) · [Reference Measured results](measured-results.md)
