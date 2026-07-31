# Status — Anvil chunk loader and light engine

**465 tests** · `./gradlew build` green. 178 of them in `falco-anvil`, 178 in `falco-light`, 36 in
`falco-instance` and 73 in `falco-demo`.

Everything here is experimental and opt-in. Nothing takes effect in a server that does not construct
a `FalcoAnvilLoader`, a `FalcoInstance`, or reach the light engine either by calling a
`ChunkLightService` itself or by handing an instance the chunk supplier of a `ChunkLightScheduler`.

Both parts were developed inside [Aves](https://github.com/OneLiteFeatherNET/Aves), OneLiteFeather's
utility library, and moved here once it was clear they are server infrastructure rather than
utilities. The history of that development stayed behind; what was learned along the way is written
down in this document and in `docs/research/`.

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

The motivating observation for the loader: Minestom's `AnvilLoader` reports
`supportsParallelLoading() == true`, but its `RegionFile` serialises reading, decompression **and**
NBT parsing through a single `ReentrantLock`. The parallelism is largely nominal. The gain is not in
starting more threads but in moving the CPU work out of the lock — which is what the three-stage
pipeline below does, and what the measurements confirmed.

## Environment

| | |
| --- | --- |
| Java | 25 (toolchain and `release`), no `--enable-preview` anywhere |
| Minestom | `2026.06.20-26.1.2`, `compileOnly` |
| Adventure | `5.1.1`, `adventure-nbt` used directly — Minestom speaks `CompoundBinaryTag` natively, so there is no conversion layer |
| Annotations | `org.jetbrains:annotations:26.1.0` |
| Tests | JUnit 6.1.0, Cyano `0.6.2` (`MicrotusExtension`) for anything needing a server |
| Benchmarks | JMH 1.37 via `me.champeau.jmh` 0.7.3, in the separate `falco-benchmarks` module |
| Build | Gradle 9.6.1, five modules on one shared version |

`adventure-nbt`, `jetbrains-annotations` and `fastutil` are declared explicitly rather than taken
transitively. The first two only reach the classpath through `compileOnly(minestom)`, so any direct
use of them would compile by coincidence. `fastutil` is `runtime` scope in Minestom's POM and is
needed by the light equivalence test and the comparison benchmarks.

## Working on this

```bash
./gradlew build                                  # compile, javadoc, tests — javadoc failures break the build
./gradlew :falco-anvil:test --tests "*Region*"   # a subset
./gradlew :falco-benchmarks:jmhJar               # build the benchmarks (they never run during build)
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar ScalingBenchmark -f 1 -wi 2 -i 3

# Compare the two loaders on a world of your own: drop it into falco-demo/world first.
# Run both with the SAME -Pthreads, or the two numbers answer different questions.
./gradlew :falco-demo:runFalcoLoader    -Pthreads=4 -Pchunks=256
./gradlew :falco-demo:runMinestomLoader -Pthreads=4 -Pchunks=256
```

Two things that will otherwise cost an hour:

- **Do not run two Gradle builds in the same checkout at once.** They corrupt `build/test-results`
  and surface as `EOFException`, `NoClassDefFoundError` or missing `jacoco/test.exec` — failures
  that look like real test breakage. `rm -rf build` and rerun.
- **JMH allows one instance at a time.** A crashed run leaves `/tmp/jmh.lock` behind and every later
  run fails with *"Another JMH instance might be running"*. Delete the file.

## Conventions

Match these; the build enforces some of them.

- **Javadoc on every class and method**, with `@param` / `@return` / `@throws`. `withJavadocJar()`
  means an incomplete comment fails CI. Class comments explain *why*, not *what*, and carry
  `@author` / `@version` / `@since`.
- **Never write `@NotNull`.** Packages carry `@NotNullByDefault` in `package-info.java`; only
  `@Nullable`, `@Contract` and `@UnmodifiableView` appear explicitly.
- **Test-first, strictly.** Every type here was built by writing a failing test, confirming it fails
  for the right reason, then implementing. Several bugs in this branch were found precisely because
  a test was written before the code.
- Tests are package-private, named `test<What><Expectation>`, use plain JUnit assertions, and avoid
  `@Nested`. Anything needing a server uses `@ExtendWith(MicrotusExtension.class)`.
- Commits follow conventional commits, scoped `(anvil)`, `(light)`, `(map)`.

## Releasing and snapshots

Both modules share one version. It lives on a single line of the root `build.gradle.kts`, Release
Please rewrites that line from the conventional commits above, and everything else follows from it.

| Push to `main` | Version | Endpoint |
| --- | --- | --- |
| Merges the Release Please PR | the released one, `0.2.1` | `https://repo.onelitefeather.dev/releases` |
| Anything else | the next patch with a suffix, `0.2.2-SNAPSHOT` | `https://repo.onelitefeather.dev/snapshots` |

`.github/workflows/release-please.yml` holds both publish jobs. They hang off the same
`release_created` output with opposite conditions, so a push publishes a release or a snapshot and
never both. A second workflow on `push` cannot see whether a release was cut and would double-publish.

The snapshot version is derived inside the build rather than passed in, because a `-Pversion=…`
would be overwritten by the `version = …` assignment that runs after the property is read.
`-Psnapshot` instead makes the root build bump the patch of the released version and append
`-SNAPSHOT`; the publishing block already picks its endpoint from that string. The flag reaches
Gradle through the `build-task` and `publish-task` inputs of the reusable workflow, which are handed
to `./gradlew` verbatim — it offers no input for properties or environment. Check the result with
`./gradlew -Psnapshot properties | grep '^version:'`.

Both endpoints are public, so a consumer of an artefact needs no credentials. Both exist on the
Reposilite instance and both have been written to: `/releases` serves `0.2.1` of either module, and
`/snapshots` serves `0.2.2-SNAPSHOT`, put there by the first push after that release.

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
  probe in [`docs/research/shared-instances-and-batches.md`](docs/research/shared-instances-and-batches.md).

**Claims about other light engines**

Established by reading the sources, not by measuring. Each of these was taken for a promising lead
first and only stopped being one after it was checked.

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

The design in [`docs/superpowers/specs/2026-07-31-falco-lighting-chunk-design.md`](docs/superpowers/specs/2026-07-31-falco-lighting-chunk-design.md)
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

| Decision | Choice | Why |
| --- | --- | --- |
| Format coverage | Core compression plus external `.mcc`, **no** LZ4, no corruption recovery | Covers real worlds without an extra dependency; Minestom fails hard on oversized chunks, which this does not |
| Integration | Opt-in via `ChunkLoaderFactory` | No breaking change; existing providers behave exactly as before |
| Own palette | **Not built** — codec-internal representation only | `Palette` is sealed, and it is 4.5 % of the load path anyway |
| Own `InstanceContainer` | **Not built.** An own `Instance` was, in `falco-instance` | Subclassing the container is closed — its lifecycle hooks are `protected` members of Minestom's package. Extending `Instance` makes the barrier visible once, at the chunk. No speed is claimed either way: the tick parallelism lives in the global `ThreadDispatcher` |
| Light `Light` implementation | **Not built** — results handed over via `Light#set` | Avoids the `@ApiStatus.Internal` calculation methods and the `Section.clone()` trap |
| Read failure | Throws, never returns `null` | `null` means "absent", so the server regenerates and overwrites real data on the next save |
| Compression level | 2, not the platform default 6 | 1.83× faster for ~3 % more bytes; compression is 63 % of a save |
| Reader safety in `RegionFile` | Per-entry seqlock | A `ReadWriteLock` would block every reader of a region for the length of a payload write, which is the one thing this loader gains over Minestom's; deferred free brings the contention back through a shared counter and grows the files |
| Region handle lifetime | Usage count, not a retry | A retry only narrows the window and has to be repeated at every call site; counting accesses removes the case instead. The cost is that the open-handle cap now bounds the cache rather than the descriptors |
| Use after `close()` | Throws | Ignoring it loses data during shutdown, and waiting is impossible — Minestom owns the load tasks, so there is nothing to wait on |

## Where things live

```
falco-anvil/          net.onelitefeather.falco.anvil
                      RegionConstants, SectorAllocator, BitPacker, ChunkCompression,
                      RegionFile, NbtReads, PaletteData, PaletteEntryResolver, SectionCodec,
                      BlockPaletteResolver, BiomePaletteResolver, AnvilDiagnostics,
                      FalcoAnvilLoader, AnvilChunkException

falco-light/          net.onelitefeather.falco.light
                      LightNibbles, BlockFace, BlockLightSource, SectionOpacity,
                      LightPropagator, ChunkLightPropagator, ChunkLightState,
                      ChunkLightService, MinestomBlockLightSource,
                      ChunkArea, ChunkLightArea, ChunkLightScheduler,
                      FalcoLightingChunk, LightUpdateAware

falco-instance/       net.onelitefeather.falco.instance
                      FalcoInstance, FalcoChunk, FalcoInstanceException

falco-benchmarks/     not published. Holds every benchmark, because ScalingBenchmark measures
                      both modules in one run and BenchmarkConstants / SectionStates are used
                      from both sides. LightEngineComparisonBenchmark and
                      LightEngineStageBenchmark live in net.minestom.server.instance.light
                      because the methods they measure are package-private there

falco-demo/           not published. A runnable comparison of the two loaders on a world the user
                      supplies. Rough figures from one machine, not an instrument: falco-benchmarks
                      stays the place where a number becomes evidence.
```

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
other. See `docs/anvil-chunk-loader.md`.

Reading order for someone new: `RegionFile` (the byte container), then `FalcoAnvilLoader`
(the three stages), then `SectionOpacity` and `ChunkLightPropagator` for the light side, and
`ChunkLightScheduler` for how that light keeps itself current. `FalcoInstance` is independent of all
of them and can be read on its own.

## Charts

Four charts were produced from these measurements. They live outside this repository and are not
linked here, because they are not publicly readable. Every number behind them is in this document
and in `docs/benchmarks.md`, and `build/reports/jmh/results.json` feeds
[JMH Visualizer](https://jmh.morethan.io/) directly if you want the same views from your own run.

| Chart | Shows |
| --- | --- |
| Scaling and comparison | 1 to 256 sections, and the head-to-head against Minestom. **The head-to-head half predates `69381af`** and shows the factors from before the opacity table was rewritten |
| Optimisation | Where save time goes, the compression trade-off, the uniform-section fast paths |
| Vanilla · Minestom · Falco | 22 behaviours scored against the format reference |
| Concurrency defects | The five races, their failure rates before the fix, and what the fix costs |

---

## What is in the branch

| Module | Types | Tests | What it does |
| --- | ---: | ---: | --- |
| `falco-anvil` | 14 | 13 classes, 178 tests | Reads and writes Anvil region files, replacing `AnvilLoader` |
| `falco-light` | 14 | 18 classes, 178 tests | Computes block light and sky light for a chunk, and keeps it current on its own |
| `falco-instance` | 3 | 5 classes, 36 tests | An `Instance` and its `Chunk`, with a lifecycle that runs for a foreign instance |
| `falco-benchmarks` | 24 files | — | Benchmarks, in their own source set, never run during a build |

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
| `FalcoLightingChunk` | Three overrides, each of which only reports something. `setBlock` and `onLoad` mark, `tick` triggers the pass, `onLightUpdated` sends. |
| `ChunkLightScheduler` | The dirty set, the once-per-tick trigger, area forming, the executor, back pressure and the staleness rule. All the complexity, at one address. |
| `ChunkArea` | A chunk coordinate pair, and the flood fill that cuts a dirty set into capped connected groups. Pure arithmetic, no Minestom, so both its rules are testable without a server. |
| `ChunkLightArea` | Computes one group in a single pass: reads its chunks plus one ring, exchanges borders until settled, writes back the group and never the ring. |
| `LightUpdateAware` | The hook Minestom does not have, so the scheduler can deliver a result without knowing which chunk type it is talking to. |

Five properties of the cycle that are decisions rather than details:

- **Once per tick, not once per chunk.** `Chunk#tick(long)` runs per chunk, but a pass has to see
  every change of the tick before it forms its areas. The tick timestamp is the same value for every
  chunk of one tick, so the scheduler runs its pass for the first chunk reporting a new one. If no
  chunk ticks, nothing happens — which is correct, because then nobody is looking.
- **A change marks the 3×3, and the ring of an area is read but never written.** Those two are the
  same rule seen from both ends: a chunk whose light has to change must be *in* an area, because a
  ring chunk has only seen the light of the area and never what lies on its own far side, so writing
  it back would darken it. This is exactly the defect open item 3 describes for
  `calculateWithNeighbours`, designed out here from the start.
- **An area has an upper bound, and the bound is not optional.** One `ChunkLightState` is roughly
  980 KB, so a build spread across a hundred connected chunks would form one area of about a hundred
  megabytes plus its ring. The flood fill stops at `maxAreaSize` (16 by default) and the remainder
  starts the next area; the seam between two parts settles on the following tick, because each part
  reads the other as its ring.
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

## Measured

All figures from one machine that was **not idle**. Ratios are meaningful, absolute microseconds
carry a wide error. Reproduce with `./gradlew jmhJar` and the benchmark names below — except for the
parts of *Where the time goes in the light path* that are still marked as coming from a standalone
rebuild.

### Where the time goes when saving a chunk

`ChunkSaveStageBenchmark`, 24 sections, 200 block states:

| Stage | Time | Lock held |
| --- | ---: | --- |
| Snapshot | 64 µs | chunk read lock |
| Codec, without compression | 1 356 µs | none |
| zlib compression | 2 701 µs | none |
| Transfer | 17 µs | region lock |

**About 97 % of a save runs outside any lock**, and compression is 63 % of the whole operation. This
is the measurement that turned the design claim into a number, and it is what made compression the
optimisation target.

### The region file against the one Minestom ships with

`RegionFileComparisonBenchmark`, 200 distinct block states, one fork, 3 warmup and 5 measurement
iterations of 2 s, on a 16 core machine. JMH takes one `-t` per run, so the thread count was varied
by running it four times. µs/op:

| Threads | Falco read | Minestom read | | Falco write | Minestom write |
| ---: | ---: | ---: | --- | ---: | ---: |
| 1 | 1 203 ± 123 | 1 060 ± 55 | 1.14× slower | 2 659 ± 212 | 2 601 ± 100 |
| 2 | 1 181 ± 31 | 2 200 ± 445 | 1.86× faster | 2 637 ± 204 | 2 643 ± 153 |
| 4 | 1 378 ± 84 | 11 021 ± 16 470 | 8.00× faster | 2 672 ± 43 | 2 678 ± 115 |
| 8 | 2 438 ± 252 | 530 905 ± 1 928 261 | not usable | 3 098 ± 658 | 3 102 ± 245 |

Three things this settles, two of which are not flattering:

- **Single-threaded, this loader is the slower one** — by 14 % at 200 states and 20 % at 8. The
  three-stage pipeline has a setup cost and no contention to win it back from. The claim was always
  that the gain is in lock granularity; this is what that means when there is no lock to contend for.
- **Writing is a tie at every thread count** (1.00× to 1.05×, either direction). Both take a lock for
  the sector allocation and the header, so there is nothing to gain, and the numbers say so.
- **Reading inverts from two threads on** and keeps going: 1.86×, then 8×. Falco's own time grows by
  a factor of two from one thread to eight while Minestom's grows by a factor of five hundred.

The eight thread row is reported but **must not be quoted as a factor**: its error is nearly four
times its mean. What it shows is a loss of predictability, not a number — Minestom's read time
scatters over three orders of magnitude there, while Falco stays inside ±10 %. For a server that
is the worse failure of the two.

The charts in the README are generated from exactly these figures by `docs/charts/generate.mjs`.

### Against the engine Minestom ships with

`LightEngineComparisonBenchmark`, one section, `-f 1 -wi 5 -i 10`, µs/op. Both engines run to a
**byte-identical** result over all 54 scenarios; since `69381af` that is checked by
`LightEngineEquivalenceTest` on every build and again by the benchmark before each trial, rather
than being asserted from a one-off comparison.

Measured before and after `69381af` in one session on the same machine, with Minestom as the
control:

| Sources | Solid | Falco before | Falco after | Minestom | Before | After |
| ---: | ---: | ---: | ---: | ---: | --- | --- |
| 1 | 0 % | 74.2 ± 1.9 | 44.5 ± 0.6 | 49.4 ± 1.3 | 1.42× slower | 1.11× faster |
| 1 | 30 % | 61.8 ± 1.6 | 39.3 ± 0.8 | 62.0 ± 2.0 | 1.03× slower | 1.58× faster |
| 8 | 0 % | 137.7 ± 7.4 | 98.3 ± 2.4 | 121.1 ± 5.5 | 1.18× slower | 1.23× faster |
| 8 | 30 % | 144.7 ± 1.8 | 119.3 ± 3.5 | 204.2 ± 3.7 | 1.37× faster | 1.71× faster |
| 64 | 0 % | 135.9 ± 2.8 | 109.2 ± 1.6 | 126.5 ± 5.6 | 1.08× slower | 1.16× faster |
| 64 | 30 % | 152.7 ± 1.8 | 122.6 ± 1.3 | 206.6 ± 4.2 | 1.37× faster | 1.68× faster |

Falco is now ahead in all six scenarios instead of two, and the lead on solid blocks grew rather than
being traded for the empty rows. An independent re-run confirms direction and order of magnitude,
not the third digit. This is a result under the conditions named at the top of this section — one
section, one machine that was not idle, sources of equal brightness — and not a general statement
about either engine.

The earlier reading of the pattern was wrong and is worth recording. It said an empty section favours
Minestom because our opacity table is built unconditionally, and a section with solid blocks favours
us because the table is then read many times. The table is still built unconditionally; it now costs
a quarter. The stage breakdown below also shows that **Falco's search was already the faster of the
two before the change** — 33.9 µs against Minestom's 53.9. The entire deficit came from the
preparation, never from the algorithm.

### With sources of mixed brightness

`LightEngineComparisonBenchmark` gained an `emissionMix` parameter in `0e8fbb5`. `MIXED` cycles the
sources through glowstone, lantern, torch, redstone torch and magma block, which the registry gives
15, 15, 14, 7 and 3; positions are drawn identically to `UNIFORM`, so the levels are the only
difference. µs/op:

| Sources | Solid | Falco | Minestom |
| ---: | ---: | ---: | ---: |
| 8 | 0 % | 118.97 ± 8.89 | 126.54 ± 9.55 |
| 8 | 30 % | 116.50 ± 7.63 | 201.46 ± 16.83 |
| 64 | 0 % | 150.42 ± 32.73 | 162.20 ± 3.11 |
| 64 | 30 % | 149.42 ± 12.64 | 252.26 ± 9.28 |

Mixed brightness costs Falco **about 33 %** against `UNIFORM` — 64 sources at 0 % solid go from 112.7
to 150.4 µs — and the lead in that row falls from 1.30× to 1.06×. A position is queued more than
once when sources of different brightness reach it, which is exactly the case a bucket queue is for;
the research predicts −32 to −36 % there. The benchmark can now see it, which is what the decision
under *Investigated and deliberately not built* was waiting for.

### Scaling by world height

`ScalingBenchmark`, 1 to 256 sections:

- **Block light is linear** across the whole range — 33 µs per section at 1 section and at 256.
- **Sky light is not.** Cost per section rises from 84 µs to 104 µs past roughly 64 sections.
- A least-squares fit over the vanilla range (≤ 24 sections) predicts 21 423 µs of sky light at 256
  sections. The measured value is 26 597 µs — **the forecast understates it by 19.5 %**. For block
  light the same method lands within 1.8 %.

Measuring the exotic sizes rather than extrapolating from common ones is the only reason this is
known. The cause of the sky-light curve is named below: seeding queues every open cell.

### Lighting an area against one neighbourhood per chunk

The design of the self-maintaining light made area forming conditional on a single number, and this is
that number. `AreaVsPerChunkBenchmark`, `-f 1 -wi 3 -i 5`, a connected square of chunks with 30 %
solid blocks and four sources each, µs/op:

| Chunks | `area` | `perChunk` | Area is |
| ---: | ---: | ---: | --- |
| 1 | 542.4 ± 15.6 | 546.6 ± 27.4 | level |
| 4 | 2 678.4 ± 45.5 | 10 533.1 ± 162.6 | 3.93× cheaper |
| 9 | 6 408.2 ± 468.2 | 34 390.8 ± 1 738.9 | 5.37× cheaper |
| 16 | 11 607.5 ± 975.2 | 70 639.0 ± 1 195.9 | 6.09× cheaper |

The criterion was that an area of *n* chunks has to be measurably cheaper than *n* separate
`calculateWithNeighbours` calls, and that if it were not, the simpler per-chunk design would be the
better one and area forming should have been dropped rather than tuned until the benchmark agreed. It
holds from four chunks on and the margin grows with the area, which is the shape the argument
predicted: reading the block states of a chunk and building its opacity tables is the expensive part,
and a per-chunk neighbourhood reads every chunk up to nine times while an area reads each of its
chunks and each of its ring chunks exactly once, no matter how large it is.

**The tie at one chunk is the expected result, not a weak one.** A lone chunk has no loaded
neighbours, so neither side has a ring to read and there is nothing for either to save. The row is
worth keeping precisely because it shows the difference is the repeated reading and nothing else.

### Where the time goes in the light path

This section began as a standalone rebuild of the same call structure, run outside the project — not
JMH and not the real code. The part of it that mattered most has since been measured for real:
`69381af` added `LightEngineStageBenchmark`, which times the stages of both engines inside the
project. Where a rebuild estimate has been replaced by a JMH figure that is said below; the rest is
still the rebuild and still carries only its ratios.

**Measured, `LightEngineStageBenchmark`, 1 source, 0 % solid, µs:**

| Stage | Before `69381af` | After |
| --- | ---: | ---: |
| `opacity` — build the table | 31.33 | **8.07** |
| `readStates` | 7.70 | 7.23 |
| `propagate` — the search itself | 33.85 | 31.41 |
| `collect` | 0.24 | 0.23 |
| Total | 77.1 | 46.3 |

Allocation while building the table: 74 040 → 8 664 bytes per call.

Two things fall out of this. The rebuild's estimate for `SectionOpacity.of` — 29.1 µs against 5–7 µs
for a table without boxing — was close enough on both ends; the real path lands at 8.07 µs with a
local linear-probing table over the raw state id. And **the search was never the problem**: at 33.9 µs
`propagate` was already faster than Minestom's 53.9 µs before any of this. The whole deficit against
Minestom sat in the preparation.

The mechanism the rebuild identified was correct. The lambda handed to `computeIfAbsent` captures the
`BlockLightSource`, so a fresh instance is created on every loop iteration, and escape analysis does
not remove it because `computeIfAbsent` is too large to inline — 4096 objects per section, roughly
1.8 MB per chunk column. That is also where the ±19.1 spread against Minestom's ±2.7 came from.

The rest is still **the rebuild: not JMH, not the real code**, run on Temurin 25, best of seven. The
ratios between variants carry; the absolute microseconds are coarser than everything else in this
section. Ordered by the size of the effect, with what has since been built marked as such:

- **Done in `69381af`: the opacity table without a per-block allocation.** The rebuild put this at
  20–45 % of the path; the stage benchmark above is the real figure.
- **Done in `69381af`: `collect()` as one linear nibble pack** instead of writing position by
  position through `LightNibbles.set` and cloning afterwards. The rebuild put it at 9.6 → 1.2 µs in
  the non-uniform case; `LightNibbles.ofLevels` now packs two neighbours at a time and range-checks
  once at the end.
- **Open: seeding sky light from a heightmap** instead of queueing every open column cell: 79.3 →
  55.1 µs, and 81 000 → 19 000 queued positions. Byte identity against the current seeding was
  verified over 240 randomly generated worlds, zero differing cells. This is what the non-linear
  sky-light scaling above is made of.
- **Open, and deliberately so: the seed pass is redundant.** `seed` walks all 4096 positions only to
  find the emitters, which `of` already visits: 3.6 µs, plus 2.0 µs for the second `byte[4096]` that
  then becomes unnecessary. Left out of `69381af` because writing the emitters during the table build
  changes the API of `SectionOpacity` and both propagators for a gain of that size.
- **Open: column opacity as one flat `byte[]`** instead of `List.get(y >> 4)` plus a virtual call:
  −26 % on searching a whole column.
- **Open: skipping the direction an entry arrived from**: −7 to −16 %. **Testing the level before the
  opacity**: −6 %.
- **A bucket queue (Dial)** is 5–7 % *slower* at equal source brightness and 32–36 % faster at mixed
  brightness. The benchmark now produces the mixed case — see *With sources of mixed brightness*
  above, where mixed sources cost about 33 %.
- **`ChunkLightState` allocates about 980 KB of buffers per instance**, and `calculateWithNeighbours`
  builds nine of them — roughly 28 MB of garbage per call. Derived from the buffer sizes, not
  measured with an allocation profiler. This is also the figure the cap on an area size is set from:
  `ChunkLightArea` builds one state per chunk of the area *and* of its ring, so an unbounded area
  over a large build would allocate hundreds of megabytes inside a tick.

### Optimisations these numbers produced

| Change | Effect |
| --- | --- |
| zlib level 2 instead of the platform default 6 | 1.83× faster compression, ~3 % larger files |
| Fast path for uniform sections, palette encode | 27.9 µs → 0.54 µs (**51×**) |
| Fast path for uniform sections, opacity table | 40.8 µs → 0.54 µs (**76×**), and no arrays allocated |
| Linear-probing opacity table over the raw state id, no boxing | 31.33 µs → 8.07 µs, 74 040 → 8 664 bytes per call |
| `LightNibbles.ofLevels` instead of 4096 calls to `set` | `collect` 0.24 µs → 0.23 µs in the stage benchmark; the rebuild had it at 9.6 → 1.2 µs for the non-uniform case |
| One area instead of one 3×3 neighbourhood per chunk | 3.93× at 4 chunks, 6.09× at 16, level at 1 |

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
  [`docs/research/instance-container.md`](docs/research/instance-container.md) describes. Every
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
reason it was worth doing. Numbers below are from the red run of each test; charted
[here](https://claude.ai/code/artifact/9b11a843-8db5-4495-8a95-b0423df28304).

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

### 1. Exception hierarchy

Design complete in [`docs/research/exception-hierarchy.md`](docs/research/exception-hierarchy.md),
six types, not implemented. **One open decision:** whether the checked root extends `IOException`.
Extending it keeps roughly 40 signatures and 14 test assertions untouched; not extending it stops
every existing `catch (IOException)` from silently swallowing the new types. Both arguments hold —
this needs a call, not more analysis.

### 2. `calculateWithNeighbours` is last-writer-wins across chunks

One service may now serve any number of threads, which is what the light fix established. What it
does **not** establish is two threads lighting *overlapping neighbourhoods*: each reads the block
states of all nine chunks separately, then both write into the same sections. Neither corrupts
memory — every write holds the chunk's write lock — but the later writer wins on the basis of a read
that may already be stale, which shows up as a seam rather than as an error. Whether that happens is
up to the caller; nothing in the API says so yet.

**Narrowed, not closed.** A caller going through `ChunkLightScheduler` is no longer exposed to this:
a chunk under computation stays marked but is not submitted a second time, and a chunk whose mark
count moved while its area ran has its result discarded rather than written. `ChunkLightArea` says
the same thing at its own level — it is safe to share between threads, but two threads computing
*overlapping* areas is not made safe there either, and the scheduler is what keeps that from
happening. The item stands for everyone who calls the service directly.

### 3. `calculateWithNeighbours` darkens the eight chunks it borrows

It writes **all nine** chunks back at the end. The eight ring chunks only exchanged light inside the
3×3, so the light they legitimately receive from chunks outside the 3×3 is missing from their result.
Their previously correct light is overwritten with a darker one.

The middle chunk is not affected, and provably so: a source in chunk (2,0) is at least 17 blocks from
the middle chunk, and no path can be shorter than the direct distance, so level 15 does not survive
the trip. Writing back only the middle chunk would therefore be **cheaper than the current behaviour
and correct at the same time**. The argument is a derivation from the per-block decay, not a
measurement — the byte-identity tests cover a single chunk, not the ring around it.

This is the concrete form of what was filed under smaller items as "border exchange settles one ring
deep": not an imprecision, a defect with a known fix.

**Still open, and the fix now exists next to it.** `ChunkLightArea` reads its ring and writes only
its area, which is exactly the correction this item asks for, and
`ChunkLightAreaTest#testChunksOutsideTheAreaKeepTheirLight` pins it down. That does not fix
`calculateWithNeighbours`, which is unchanged and is still what a direct caller of the service gets —
including `AreaVsPerChunkBenchmark`, whose `perChunk` side is the darkening path and is measured as
such. The method is now the older of two ways to do the same thing, and the newer one is right.

### 4. The scheduler recomputes whole chunks for a single block change

`ChunkLightState#update` exists, is tested against a full recalculation block for block, and handles
the hard direction — retracting the glow of a source that disappeared and letting the legitimate
light of everything else back in. The scheduler does not use it. A block change marks its chunk and
the eight around it, and the next pass recomputes those chunks from their block states.

This was a declared non-goal of the design rather than an oversight: the entry point had to exist
before the granularity behind it was worth tuning. It is recorded here because the engine already
carries the cheaper path, so the gap is a wiring job and not a piece of missing work. What makes it
more than a micro-optimisation is the area cap — every chunk of an area holds a `ChunkLightState` of
roughly 980 KB, and a placed torch currently pays for nine of them.

### 5. Two things that are argued rather than tested

- **Stale header entries.** `locations` and `timestamps` are `AtomicIntegerArray` now, so a reader
  cannot see a stale `0` and turn a present chunk into a regenerated one. That is ruled out by
  construction, not by a test — a JMM staleness window cannot be provoked deterministically, because
  any harness that tries introduces synchronisation edges of its own.
- **The open-handle limit is no longer a hard cap on descriptors.** It bounds the *cached* files;
  a handle in use by a thread stays open beyond it for the duration of that access. Deliberate, and
  documented at the field, but it means the limit is a cache size and not a resource guarantee.

### 6. Smaller items

- Border exchange between chunks settles one ring deep; a fully converged result over a large area
  needs the exchange repeated. Item 3 is the part of this that is outright wrong today.
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
- Sky light updates re-seed open columns rather than tracking a heightmap incrementally. Measured at
  79.3 → 55.1 µs in the rebuild, with byte identity verified over 240 worlds.
- `SectionOpacity` still builds its table unconditionally for non-uniform sections. Since `69381af`
  that costs 8.07 µs instead of 31.33, and the empty section with one source is no longer the row
  that loses — but the table is still built whether or not it is read more than once.
- `seed` walks all 4096 positions a second time only to find the emitters. Writing them during the
  table build saves about 3.6 µs and one `byte[4096]`, at the price of changing the API of
  `SectionOpacity` and both propagators. Left open on purpose for that reason.
- Column opacity is a `List.get(y >> 4)` plus a virtual call rather than one flat `byte[]` (−26 % on
  a whole column in the rebuild), and the search neither skips the direction an entry arrived from
  (−7 to −16 %) nor tests the level before the opacity (−6 %).

---

## Investigated and deliberately not built

### Replacing parts of Minestom

Three "replace this part of Minestom" questions were researched before any code was written. The
answers differed sharply and none was obvious in advance — see [`docs/research/`](docs/research/).

| Subject | Verdict |
| --- | --- |
| **Palette** | Impossible. `sealed interface Palette permits PaletteImpl` is a hard compiler error, and `Section` is a record holding that exact type. |
| **`InstanceContainer`** | Possible but pointless **as asked**. It compiles and runs, but four `instanceof InstanceContainer` sites silently take another path for a foreign type, and the tick parallelism the request targeted lives in the global `ThreadDispatcher`, not in the container. `falco-instance` was built later for a different reason and still claims none of the speed the original question was about — see *Instance* above. |
| **Light engine** | Possible and worth it — this is what was built. |

The recurring lesson: **sealed-ness decides whether it is possible, and the profile decides whether
it is worth it.** Both have to be checked before designing anything, and neither can be guessed.

### Importing a foreign light algorithm

A later round asked whether an algorithm from another engine would close the gap to Minestom that
existed at the time. None would have: the gap was not in the search but around it, and the stage
benchmark has since confirmed that — `propagate` was already faster than Minestom's search, and
closing the gap took a table without allocations, not another algorithm. *Claims about other light
engines* refutes the individual leads. The verdicts below exist so that nobody walks the same road
again.

| Subject | Verdict |
| --- | --- |
| **Starlight, wholesale** | Nothing left to take. The push-instead-of-pull BFS is already here, and the remainder of Starlight's gain is specific to how vanilla stores light. |
| **Bit-slicing light levels across voxels** | No precedent in any engine. It would be an original design with an unproven benefit. |
| **Parallelising the BFS of a single chunk** | Not worth it. The work is 50–150 µs; handing it to another thread costs more than it saves. Minestom parallelises across chunks, which is the right granularity. |
| **Vector API** | Ruled out by packaging rather than by performance — it would force a JVM flag on every consumer. |
| **A bucket queue (Dial)** | No longer undecided for lack of a measurement. It loses 5–7 % at equal source brightness and wins 32–36 % at mixed brightness, and since `0e8fbb5` the benchmark produces the mixed case: mixed sources cost Falco about 33 % and shrink the lead in that row from 1.30× to 1.06×. The prerequisite is met; the change itself is still not made. |

---

## Documents

| File | Contents |
| --- | --- |
| [`docs/anvil-chunk-loader.md`](docs/anvil-chunk-loader.md) | Usage, architecture, 20-row comparison with the built-in loader, limits |
| [`docs/light-engine.md`](docs/light-engine.md) | Usage, design, where resources are saved, limits |
| [`docs/benchmarks.md`](docs/benchmarks.md) | How to run the benchmarks and what each measures |
| [`docs/research/`](docs/research/) | The three investigations, with both positions where agents disagreed |
