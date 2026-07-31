# Benchmarks

JMH benchmarks for the [Anvil chunk loader](anvil-chunk-loader.md) and the
[light engine](light-engine.md). They live in their own source set, `src/jmh/java`, so nothing they
need ever reaches the classpath of the published library.

> **Read this before quoting a number.** Everything a benchmark prints is a measurement of *one*
> machine, on *one* JVM, under *one* load. None of it belongs in `anvil-chunk-loader.md` or
> `light-engine.md` as an absolute statement. See [What the numbers are not](#what-the-numbers-are-not).

## Running them

```bash
./gradlew jmh                      # every benchmark, full settings
```

That is every measured method for every combination of the parameters its state class declares — 476
configurations at the time of writing, at the forks and iterations the annotations ask for. Budget
well over an hour and do not touch the machine while it runs. The count is dominated by
`ScalingBenchmark`, whose fifteen section counts and five distinct-state counts share one state class
and therefore form a full cross product of 300 configurations on their own.

The full run is not what you want during development. Restrict it to what you are working on:

```bash
# One class
./gradlew jmh -Pjmh.include='BitPackerBenchmark'

# One method
./gradlew jmh -Pjmh.include='ChunkSaveStageBenchmark.codec'

# A regex over several
./gradlew jmh -Pjmh.include='light\..*Propagator.*'
```

The Gradle task writes two files:

| File | Content |
| --- | --- |
| `build/reports/jmh/human.txt` | the console output |
| `build/reports/jmh/results.json` | machine readable, for [JMH Visualizer](https://jmh.morethan.io/) |

### Running the jar directly

The task builds a self-contained benchmark jar, which is the faster way to iterate because it skips
Gradle entirely and accepts every JMH option:

```bash
./gradlew jmhJar
java -jar build/libs/falco-*-jmh.jar 'BitPackerBenchmark.pack' -f 1 -wi 1 -i 1

java -jar build/libs/falco-*-jmh.jar -l          # list every benchmark
java -jar build/libs/falco-*-jmh.jar -h          # every option
```

A quick smoke run — one fork, one warmup iteration, one measurement iteration — is `-f 1 -wi 1 -i 1`.
That is enough to prove a benchmark executes and produces a plausible number. It is **not** enough
to compare two versions of the code; for that, drop the overrides and let the annotations decide.

### Profiling a benchmark

```bash
java -jar build/libs/falco-*-jmh.jar 'PaletteDataBenchmark.encode' -prof gc
java -jar build/libs/falco-*-jmh.jar 'PaletteDataBenchmark.encode' -prof perfasm   # Linux, needs perf
```

`-prof gc` is the one worth reaching for first. Several of the claims in the other two documents are
about *allocation*, not about time, and `gc.alloc.rate.norm` answers those directly.

## Not part of `build`

The benchmarks do not run during `./gradlew build`, `check` or `test`. `jmh` and `jmhJar` are only
reachable when asked for by name. A benchmark run takes long enough that wiring it into the normal
build would make every commit painful, and JMH numbers are too noisy on a shared CI runner to gate
anything on them anyway.

Verify it for yourself:

```bash
./gradlew clean build --dry-run | grep -i jmh    # prints nothing except the compile task
```

`compileJmhJava` *is* part of `build`, and that is on purpose: a benchmark that no longer compiles
after a refactoring should break the build like any other source set.

## Why no Minestom — and the benchmarks where the answer is different

Two kinds of benchmark live here, and the distinction matters when reading a number.

The **library benchmarks** — the large majority — start no server at all. They measure what this
code contributes, with the registry replaced by a fake.

The **comparison benchmarks** measure this implementation against the one Minestom ships with, and
there the point *is* to run the original rather than a stand-in. Three of them need a server for it:

| Benchmark | Server | Why |
| --- | --- | --- |
| `RegionFileComparisonBenchmark` | no | Minestom's `RegionFile` reads no registry, so it runs in a bare fork. The class sits in `net.minestom.server.instance.anvil` because that type is package-private. |
| `ChunkSaveComparisonBenchmark` | `MinecraftServer.init()` | Minestom's `AnvilLoader` reads the biome registry and the block state count in **static** fields, so the class initialiser fails before any measurement unless the registries exist. |
| `LightEngineComparisonBenchmark` | `MinecraftServer.init()` | Measures the original light engine, whose methods are package-private in `net.minestom.server.instance.light`. |
| `LightEngineStageBenchmark` | `MinecraftServer.init()` | Same package and the same reason: it splits both engines into their stages, so it calls the same package-private methods. |

The comparison numbers therefore include what a real registry costs on both sides — which is
correct, because both sides pay it. They are not comparable with the library benchmarks below, which
deliberately exclude it.

Everything from here on applies to the library benchmarks.

Both packages already separate their algorithm from the registries of a running server —
`PaletteEntryResolver` for the codec and `BlockLightSource` for the light engine. The benchmarks
plug fakes into those two interfaces
([`FakePaletteEntryResolver`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/support/FakePaletteEntryResolver.java),
[`FakeBlockLightSource`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/support/FakeBlockLightSource.java)).

This is a deliberate trade. A registry lookup is expensive enough to dominate every one of these
measurements, and a benchmark whose number is 90 % Minestom tells you nothing about the code in
this repository. The consequence is that **no benchmark here reports what a chunk load costs on a
real server.** They report what *this* library contributes to it.

`FakeBlockLightSource` takes a `resolveCost` in [`Blackhole.consumeCPU`](https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/infra/Blackhole.html)
tokens for exactly this reason. `SectionOpacity` exists to resolve each distinct block state once
instead of once per visit, and how much that is worth depends entirely on what a resolution costs.
Measuring it against a free fake would make the cache look like pure overhead, which is the opposite
of what happens against a registry.

## The benchmarks

### Anvil

| Benchmark | Parameters | What it answers |
| --- | --- | --- |
| [`BitPackerBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/BitPackerBenchmark.java) `.pack` `.unpack` `.roundTrip` | `bitsPerEntry` 4, 5, 8, 15 | What the packing loop of one section costs. It runs once per section on every load and every save, so a full-height chunk pays it 24 times. The parameter is the only thing that changes the iteration count per long. |
| [`PaletteDataBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/PaletteDataBenchmark.java) `.encode` `.unpack` `.roundTrip` | `distinctStates` 1, 8, 64, 200 | What collecting a palette costs as the section gets busier. `1` is a section of pure air or pure stone — the majority of every world — and is answered without packing anything. `200` already needs eight bits per entry. |
| [`ChunkCompressionBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/ChunkCompressionBenchmark.java) `.compress` `.decompress` | `compression` ZLIB, GZIP, NONE × `distinctStates` 8, 200 | The most expensive single stage of a chunk transfer, and the one the loader deliberately performs outside every lock. The payload is real serialised chunk NBT built through the same codec the save path uses, not random bytes — random bytes do not compress and would make zlib look far worse than it is. |
| [`RegionFileBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/RegionFileBenchmark.java) `.writeRaw` `.readRaw` `.roundTrip` | – | The byte transfer alone, on an already compressed payload. `readRaw` uses positional channel reads and takes no lock; `writeRaw` takes the region lock for the sector allocation and the header update. |
| [`ChunkSaveStageBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/ChunkSaveStageBenchmark.java) `.snapshot` `.codec` `.codecWithoutCompression` `.transfer` `.full` | `distinctStates` 8, 200 | **The interesting one.** See below. |

`ChunkSaveStageBenchmark` splits a whole chunk save into the three stages it consists of, because
the central claim of the loader is structural and this is what turns it into a number:

| Stage | Lock held |
| --- | --- |
| `snapshot` — copy the section arrays | the read lock of the chunk; a game thread waits here |
| `codec` — palettes, packing, NBT, compression | **none** |
| `transfer` — hand the finished bytes to the region file | the region lock, for the allocation and the header |
| `full` — all three, so the sum can be checked against the whole | – |

`codecWithoutCompression` splits the middle stage again, into its palette half and its zlib half.

The chunk is a [`ChunkColumn`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/support/ChunkColumn.java)
of plain arrays, not a Minestom chunk. A real `Section` needs a started server, and the registry time
would land inside the `codec` stage and hide the very thing the benchmark isolates.

### Light

| Benchmark | Parameters | What it answers |
| --- | --- | --- |
| [`LightNibblesBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/LightNibblesBenchmark.java) `.getUniform` `.getAllocated` `.setUniformUnchanged` `.setAllocating` `.ofArray` | – | What the uniform shortcut is worth. A section whose blocks all carry the same level keeps no array and answers from a field; every other section shifts a nibble out of 2048 bytes. Most sections of a world are completely dark or completely sky-lit, so the shortcut is the common path, not the exception. |
| [`SectionOpacityBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/SectionOpacityBenchmark.java) `.of` | `distinctStates` 1, 8, 64, 200 × `resolveCost` 0, 50 | What "resolve each distinct state once" is worth. `distinctStates` sets how often the cache misses; `resolveCost` sets what a miss costs. At `resolveCost = 0` you are measuring the hash map and the two array writes per block, so the table looks like pure overhead. At `resolveCost = 50` you are measuring what it saves: seven resolutions per distinct state instead of seven per block. |
| [`LightPropagatorBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/LightPropagatorBenchmark.java) `.propagate` | `lightSources` 0, 1, 8, 64 × `occlusionPercent` 0, 25 | How the single-section search scales with the amount of queued positions. `lightSources = 0` is answered without a search at all, which is the case for the overwhelming majority of the sections of a world. `occlusionPercent = 25` is there because solid blocks stop the search early — measuring only an open section reports the worst case and calls it normal. |
| [`ChunkLightPropagatorBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/ChunkLightPropagatorBenchmark.java) `.propagate` `.propagateSky` | `sectionCount` 4, 16, 24 × `lightSourcesPerSection` 1, 8 | The same search across section borders, over a flat map (4), a shallow world (16) and a full-height overworld (24). Both searches are measured because the engine runs both per chunk and they behave very differently: block light is bounded by the amount of emitting blocks, sky light seeds nearly every block of an open column. |

Both propagators keep their working buffers between runs, so the benchmarks reuse one instance for
the whole trial and warm the buffers in `@Setup`. A fresh instance per invocation would measure two
array allocations instead of the search.

`ChunkLightState` and `ChunkLightService` have no benchmarks of their own. They are the
Minestom-facing half of the engine; what they cost is covered by the comparison benchmarks below,
which do start a server.

### Against the implementations Minestom ships with

These are the ones that answer "is this actually better", and they are the reason the
[loader](anvil-chunk-loader.md) and [light engine](light-engine.md) documents can state factors
instead of intentions. Each measures the original, not a reimplementation of it — which is why three
of them live in Minestom packages, where the measured types are package-private.

| Benchmark | Parameters | What it answers |
| --- | --- | --- |
| [`RegionFileComparisonBenchmark`](../falco-benchmarks/src/jmh/java/net/minestom/server/instance/anvil/RegionFileComparisonBenchmark.java) `.falcoRead` `.minestomRead` `.falcoWrite` `.minestomWrite` | `distinctStates` 8, 200, and the JMH thread count | The central claim of the loader: what the lock granularity is worth. Run it with `-t 1` and the two are level; the difference appears only under contention, which is why the thread count is the parameter that matters here. |
| [`ChunkSaveComparisonBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/anvil/ChunkSaveComparisonBenchmark.java) `.falcoSave` `.minestomSave` `.compressFalcoLevel` `.compressMinestomLevel` | `distinctStates` 1, 16, 64, 256, 1024 | Whether the palette handling shows up in a whole chunk save. Both sides run the identical Adventure writer over byte-identical payloads, so the save comparison cannot be won by choosing a cheaper compression level — that variable is measured separately in the two `compress*` methods. |
| [`LightEngineComparisonBenchmark`](../falco-benchmarks/src/jmh/java/net/minestom/server/instance/light/LightEngineComparisonBenchmark.java) `.falco` `.minestom` | `lightSources` 1, 8, 64 × `occlusionPercent` 0, 30 × `emissionMix` UNIFORM, MIXED | By how much each light engine wins, and on what shape of section. All three parameters are needed: the margin moves with each of them. |
| [`LightEngineStageBenchmark`](../falco-benchmarks/src/jmh/java/net/minestom/server/instance/light/LightEngineStageBenchmark.java) `.falcoReadStates` `.falcoOpacity` `.falcoPropagate` `.falcoCollect` `.falcoFull` `.minestomQueue` `.minestomFull` | `lightSources` 1, 8, 64 × `occlusionPercent` 0, 30 | *Why* one of them wins, which the comparison never says. It splits the Falco path into reading the palette, building the opacity table, searching and packing, and the built-in path into building the seed queue and the rest. This is what identified the allocation the opacity table used to make, and it is the source of the stage table in [`light-engine.md`](light-engine.md#where-the-gain-came-from). |

`emissionMix` decides whether every source of the section emits level 15 (`UNIFORM`, glowstone
throughout) or whether the sources differ (`MIXED`: glowstone 15, lantern 15, torch 14, redstone
torch 7, magma block 3, at the same positions drawn from the same seed). The Falco search assumes its
queued positions are ordered by level, which only holds while every source starts at the same one, so
this parameter is what would show whether a bucket queue is worth adding. One cell of the cross
product measures nothing: with a single source `MIXED` places glowstone as well and is a duplicate of
`UNIFORM`. The recommended run therefore leaves it out:

```bash
java -jar build/libs/falco-*-jmh.jar "LightEngineComparisonBenchmark.(falco|minestom)" \
    -p emissionMix=UNIFORM -f 1 -wi 3 -i 5
java -jar build/libs/falco-*-jmh.jar "LightEngineComparisonBenchmark.(falco|minestom)" \
    -p emissionMix=MIXED -p lightSources=8,64 -f 1 -wi 3 -i 5
```

**The comparison verifies the two engines agree before it measures them.** Its `@Setup` runs both
paths over the section it just built and aborts the trial when the 2048 bytes differ, so a faster
number cannot come from computing something else. `LightEngineEquivalenceTest` pins the same property
down in the normal test run, over 54 scenarios. Both are recent: the byte identity was stated in
[`light-engine.md`](light-engine.md) long before anything in the build checked it.

Because three of them start a server, their absolute numbers include registry time and are **not**
comparable with the library benchmarks above. Compare them only against their own counterpart.

### Scaling beyond the sizes anyone uses

[`ScalingBenchmark`](../falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/ScalingBenchmark.java) measures
this library against itself rather than against Minestom, along two axes that the other benchmarks
sample too coarsely to expose a bend in:

| Method | Parameter | What it answers |
| --- | --- | --- |
| `blockLightBySectionCount` `skyLightBySectionCount` | `sectionCount` 1 … 256, fifteen steps | Whether cost per section stays flat as the world grows taller. Block light does across the whole range. Sky light does not: a least-squares fit over the vanilla range (≤ 24 sections) understates the measured cost at 256 sections by about 20 %, while the same method lands within 2 % for block light. |
| `paletteByDistinctStates` `packingByDistinctStates` | `distinctStates` 1 … 1024 | The same question for the codec as a section fills up. |

Fifteen steps rather than three, because the point of this benchmark is to find where a curve stops
being straight — and that is exactly what a coarse parameter set hides.

## Benchmark hygiene

The rules every benchmark in this source set follows:

- **Inputs are built in `@Setup`, never in the measured method.** Otherwise the generator is what
  gets measured.
- **Every measured method returns its result** so the JIT cannot delete the work. Where there is no
  natural result — `RegionFileBenchmark.writeRaw`, `ChunkSaveStageBenchmark.transfer` — the side
  effect on the file is what keeps it alive.
- **`@Fork`, `@Warmup` and `@Measurement` are explicit on every class.** JMH's defaults are fine but
  invisible, and a reader should be able to see how much evidence a number rests on without
  consulting the JMH manual.
- **`@BenchmarkMode` and `@OutputTimeUnit` are set per class.** Everything here is `AverageTime` in
  microseconds, because every operation is a whole-section or whole-chunk unit of work and
  throughput would be the less natural framing.
- **`@State(Scope.Thread)`** everywhere. None of the measured code is designed to be shared between
  threads: `LightPropagator` and `ChunkLightPropagator` are explicitly documented as single-thread
  confined, and the state classes hold mutable buffers.
- **Sweeps, not single accesses.** `LightNibblesBenchmark` walks all 4096 blocks per invocation
  instead of reading one nibble. A single nibble read is a handful of instructions, which is below
  what a harness can separate from its own overhead. Divide by 4096 for the per-access cost — but
  see the caveat below.
- **Deterministic inputs.** Every generator seeds from `BenchmarkConstants.SEED`. Two runs of the
  same benchmark must see byte-identical input, or the difference between them describes the input
  and not the change.

### The one place a sweep is still eliminated

`LightNibblesBenchmark.getUniform` and `.setUniformUnchanged` report times far below one nanosecond
per access. That is not a measurement error and no `Blackhole` fixes it — verified by rerunning with
`-Djmh.blackhole.autoDetect=false`, which produces the same number.

A uniform section answers every read from a single field, whatever the coordinates. The loop body is
therefore loop-invariant, the JIT hoists it out, and the empty loop disappears. `setUniformUnchanged`
goes the same way: writing the level the section already carries returns immediately, the compiler
proves the loop has no effect, and removes it.

These two numbers are **lower bounds, not per-access costs.** What they legitimately say is that the
uniform path can collapse to a single field read — which is precisely the property the shortcut
exists for. Only `getAllocated` and `setAllocating` divide meaningfully by 4096. Both are kept
because a change that accidentally made the uniform path allocate would show up here instantly.

## What the numbers are not

- **They are not portable.** Core count, CPU frequency scaling, the JIT's mood on the day, the page
  cache and the file system all move these numbers. A result from your laptop says nothing about a
  production host.
- **They are not a chunk load time.** No benchmark here includes registry lookups, chunk allocation,
  or anything else Minestom does around this code. See [Why no Minestom](#why-no-minestom).
- **`RegionFileBenchmark` is not a storage benchmark.** It runs on a warm page cache and measures
  almost no device time. That is realistic for a server saving the same chunks repeatedly, and
  useless as a statement about a disk.
- **A single `-f 1 -wi 1 -i 1` run proves nothing.** It proves the benchmark executes. Any
  comparison needs the configured forks and iterations, and preferably `-prof gc` alongside.

If a number from here ends up in `anvil-chunk-loader.md` or `light-engine.md`, it needs the machine,
the JVM and the JMH configuration next to it, or it is not a fact — it is a rumour with a decimal
point.

## Notes on the setup

| Piece | Version | Why |
| --- | --- | --- |
| [`me.champeau.jmh`](https://github.com/melix/jmh-gradle-plugin) | `0.7.3` | Latest release. Gives the benchmarks their own source set so JMH never lands on the main or test classpath of a published library. |
| `org.openjdk.jmh:jmh-core` | `1.37` | Latest release. **Not managed by `mycelium-bom`** — the BOM covers adventure, minestom, cyano, junit and mockito only — so both versions are pinned explicitly in `settings.gradle.kts`. |
| `net.kyori:adventure-bom` | `5.1.1` | The main source set gets adventure through `compileOnly(minestom)`, which never reaches a runtime classpath. The benchmarks run their code for real and need adventure at runtime, so they import the platform directly. **Keep this in sync with the version Minestom resolves to** — check with `./gradlew dependencyInsight --configuration compileClasspath --dependency adventure-nbt`. |

The plugin generates the harness classes with its bytecode generator. No `jmhAnnotationProcessor` is
declared on purpose: with both present, the two generators emit the same classes and the jar ends up
with two copies of every benchmark.

On Java 25, JMH 1.37 prints a warning about `sun.misc.Unsafe::objectFieldOffset` being terminally
deprecated. It is harmless and comes from JMH itself, not from this repository.
