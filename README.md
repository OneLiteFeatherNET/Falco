# Falco

[![API status: experimental](https://img.shields.io/badge/API-experimental-yellow)](https://github.com/OneLiteFeatherNET/Falco/wiki/Project-Status)
[![Release](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo.onelitefeather.dev%2Freleases%2Fnet%2Fonelitefeather%2Ffalco-anvil%2Fmaven-metadata.xml&label=release&color=blue)](https://github.com/OneLiteFeatherNET/Falco/wiki/Installation)
[![Java 25](https://img.shields.io/badge/Java-25-orange)](https://github.com/OneLiteFeatherNET/Falco/wiki/Installation#building-from-source)
[![Documentation](https://img.shields.io/badge/docs-wiki-lightgrey)](https://github.com/OneLiteFeatherNET/Falco/wiki)
[![Licence: AGPL-3.0](https://img.shields.io/badge/licence-AGPL--3.0-lightgrey)](LICENSE)

A high-performance Anvil chunk loader and light engine for
[Minestom](https://github.com/Minestom/Minestom), plus an `Instance` implementation that cleans up
after itself.

The first two replace something the platform already ships, and both exist for the same reason: the
versions that come with the server serialise work that does not have to be serialised, and lose
information that should not be lost. The third replaces something that works, for a reason that has
nothing to do with speed — and it claims none.

| Module | What it is |
| --- | --- |
| [`falco-anvil`](https://github.com/OneLiteFeatherNET/Falco/wiki/Anvil-Chunk-Loader) | A `ChunkLoader` for the Anvil region format. Genuinely parallel: reading, decompression and NBT parsing do not share one lock. A read failure throws instead of reporting the chunk as absent, so the server cannot overwrite real data with a freshly generated chunk. |
| [`falco-light`](https://github.com/OneLiteFeatherNET/Falco/wiki/Light-Engine) | A block and sky light engine. Thread-safe per call and tied to no chunk implementation, so it works with chunk types Minestom's own engine ignores. Call it yourself, or let a chunk keep its own light up to date. |
| [`falco-instance`](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale-Instances-And-Chunks) | An `Instance` and its `Chunk`. **No speed gain is claimed and none is measured** — ticking lives in the server's global `ThreadDispatcher`, not in the instance. What it buys is an unload path of its own, where `InstanceManager.unregisterInstance` leaks every chunk a foreign instance ever loaded. It cannot back a `SharedInstance`; shared worlds are served by `FalcoSharedInstance` on a plain container instead. |

All three modules are **experimental**. Every public type carries `@ApiStatus.Experimental`;
signatures and behaviour may still change in a minor release.

## Quick start

From nothing to a server that serves a stored world, in four steps. The last one needs no client.

### 1. Declare the dependency

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.onelitefeather.dev/releases")
}

dependencies {
    implementation("net.onelitefeather:falco-anvil:0.3.0")
    implementation("net.onelitefeather:falco-light:0.3.0")

    // Minestom is compileOnly in Falco, so it does not arrive with these
    // artefacts. Falco declares no version for it, on purpose. You pick it.
    // The version Falco compiles and measures against is recorded in the wiki:
    // https://github.com/OneLiteFeatherNET/Falco/wiki/Contributing#environment
    implementation("net.minestom:minestom:<version>")
}
```

The third module, the BOM that pins all three, Maven and snapshots are in
[Installation](https://github.com/OneLiteFeatherNET/Falco/wiki/Installation).

### 2. Write the server

```java
public final class Bootstrap {

    public static void main(String[] args) {
        MinecraftServer server = MinecraftServer.init();

        InstanceContainer instance = MinecraftServer.getInstanceManager()
                .createInstanceContainer(DimensionType.OVERWORLD);

        // The world root, not worlds/lobby/region. The instance keeps the loader for as long as it
        // lives, so it is not closed here — that happens on shutdown, at the bottom.
        FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());
        instance.setChunkLoader(loader);
        instance.enableAutoChunkLoad(true);

        // One service is enough. It keeps nothing between calls and may be used from any number of
        // threads, which is what lets the chunks around several players be lit at the same time.
        ChunkLightService lighting = new ChunkLightService();
        MinecraftServer.getGlobalEventHandler().addListener(InstanceChunkLoadEvent.class, event ->
                lighting.calculateWithNeighbours(event.getInstance(), event.getChunkX(), event.getChunkZ()));

        MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0, 64, 0));
        });

        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
            instance.saveChunksToStorage().join();
            try {
                loader.close();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });

        server.start("0.0.0.0", 25565);
    }
}
```

The listener is the explicit route: you decide which chunks are lit and when. There is a shorter one
that needs no listener at all — `instance.setChunkSupplier(scheduler.supplier())`, covered in
[Light Engine](https://github.com/OneLiteFeatherNET/Falco/wiki/Light-Engine).

**That shorter route needs `falco-instance` on the classpath as well**, and the two lines above are
not enough for it. The chunks the supplier produces are `FalcoChunk`s — which is what lets one chunk
carry Falco's light *and* Falco's lifecycle instead of forcing a choice between them — and
`falco-instance` is `compileOnly` in `falco-light`, so it does not arrive with the artefact. Add
`implementation("net.onelitefeather:falco-instance:<version>")` next to the two above before calling
`supplier()`; everything else in `falco-light`, including the `lighting.calculate` route used here,
works without it.

### 3. Put a world where the loader looks

`worlds/lobby/` is the **world root** — the directory that contains `region/`, or
`dimensions/<namespace>/<value>/region/` in the 26.1 layout. The loader resolves the dimension
directory first and falls back to the older one. Point it at `region/` itself and it will find
nothing. `level.dat` is not read, so a directory holding only region files is enough.

Run the class and connect to `localhost:25565`. Chunks are read as you walk, `close()` on shutdown
flushes every open region file and writes the summary line.

### 4. Check it without a client

The same two operations without a listener around them, on a chunk of your choosing:

```java
Chunk chunk = instance.loadChunk(0, 0).join();
lighting.calculate(chunk);

int level = lighting.blockLightAt(chunk, 8, 40, 8);
System.out.println("block light at 8/40/8 is " + level);
```

A non-zero level for a lit position means the loader read the chunk and the engine lit it.

One honest note about the lighting in both steps. If the region files already carry light, it
recomputes what is already stored, because loading applies the stored arrays and clears the update
flag — for a pre-lit world the engine is doing work nobody asked for. It earns its keep on worlds
without stored light and after blocks change at runtime. Which case is which is spelled out in
[Light Engine](https://github.com/OneLiteFeatherNET/Falco/wiki/Light-Engine).

## Shared worlds

Shared worlds are the one case `FalcoInstance` cannot serve, because `SharedInstance` takes an
`InstanceContainer` and nothing else. `FalcoSharedInstance` accepts that and builds on the container
instead:

```java
InstanceManager manager = MinecraftServer.getInstanceManager();
InstanceContainer world = manager.createInstanceContainer();
world.setChunkSupplier(FalcoChunk::new);

// Not manager.createSharedInstance(world): that factory always builds Minestom's own type.
FalcoSharedInstance view = new FalcoSharedInstance(UUID.randomUUID(), world);
manager.registerSharedInstance(view);
```

The view keeps its own generator, chunk supplier and auto-load setting, where Minestom's writes all
three through to the container and lets one view reconfigure another. Its tags were always its own —
`Instance` gives every instance a `TagHandler` and `SharedInstance` does not override it — but
`saveInstance()` handed the loader the container, so they were never written; here the view's own
data is what the loader is given.

What none of that changes is who owns the blocks: `setBlock` reaches the container, and the container
serialises every write on its own monitor, because the method that performs the write is
`private synchronized` and is reached from three further places that an override cannot follow. A
world built this way keeps what the Falco chunk saves and keeps the container's write path; a world
that needs the write path uses `FalcoInstance` and gives up sharing.

The per-view generator and chunk supplier are a repair and not a capability: they stop one view from
reconfiguring another, and nothing inside Minestom reads them, because the container creates every
chunk from its own. The reasoning is in
[Rationale: Instances and Chunks](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale-Instances-And-Chunks).

## What "high-performance" means here

Measured, not asserted. Every figure comes from a JMH benchmark in this repository and is quoted
with the condition it was measured under.

**The Anvil loader is 1.9× faster on two threads** — 1 181 ± 31 against 2 200 ± 445 µs/op, reading
one chunk of 200 distinct block states. On one thread the intervals overlap and nothing is resolved
in either direction, which is the expected result: the claim is about lock granularity, and with one
reader there is no lock to contend for. At four and eight threads no factor can be quoted at all,
because Minestom's half-width there exceeds its own mean. What those rows establish is qualitative
and, for a server, the worse finding: under that load its read time stops being predictable, while
Falco's stays at or below about a tenth of its mean at every thread count. Writing shows no
resolvable difference anywhere.

<sub>`RegionFileComparisonBenchmark.falcoRead` / `.minestomRead`, `distinctStates = 200`,
`@State(Scope.Benchmark)` with a per-thread chunk slot, two threads (`-t 2`; JMH takes one `-t` per
run, so each thread count is a run of its own), one fork, `-Xms1g -Xmx1g`, 3 warmup and 5
measurement iterations, both annotated at 1 s; earlier documentation of this table said 2 s and no
`-r` was recorded on any command line, so the run's iteration time is unrecorded and the annotation
value stands here. JMH 1.37, one 16-core machine recorded as not idle, run commit and run date not
recorded, no `results.json` committed. The conservative bounds on this row are 1.45× to 2.30×
faster, the wider of the two relative half-widths being 20 %, which holds the factor to one decimal.
One fork — the `±` covers variance between iterations of one JVM, not between JVM launches, defined
once in [Rationale: Measurement](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale-Measurement#the-interval-after-a-number).
The full four-row read and write table is owned by
[Measured Results](https://github.com/OneLiteFeatherNET/Falco/wiki/Measured-Results#the-region-file-against-the-one-minestom-ships-with).</sub>

The two-thread figure did not reproduce, and that belongs next to it: an independent run of the same
configuration put Minestom at 103 437 ± 856 306 µs/op there, which carries no factor at all. What
survives both runs is the direction and the loss of predictability, not the 1.9×.

<sub>An independent second run of the same configuration, and not an erratum for the run above.
`RegionFileComparisonBenchmark.falcoRead` / `.minestomRead`, `distinctStates = 200`, two threads
(`-t 2`, one thread count per run), one fork, `-Xms1g -Xmx1g`, recorded as `-wi 3 -i 5`, the class
annotating 1 s for both and no `-r` appearing on the recorded command line, so the run's iteration
time is unrecorded. JMH 1.37, one 16-core machine recorded as not idle, run commit and run date not
recorded, no `results.json` committed. Minestom's half-width is 8.3 times its own mean here, which
carries no factor at any precision; Falco reproduced at 1 174 ± 71 µs/op against the 1 181 ± 31 of
the first run. One fork — the `±` covers variance between iterations of one JVM, not between JVM
launches, defined once in
[Rationale: Measurement](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale-Measurement#the-interval-after-a-number).
All four rows of this run are in
[Anvil Chunk Loader](https://github.com/OneLiteFeatherNET/Falco/wiki/Anvil-Chunk-Loader#measured-concurrent-readers-of-one-region-file).</sub>

**The light engine is 1.11× to 1.71× faster** over six scenarios, every pair of intervals disjoint,
with byte-identical output asserted on every build.

<sub>`LightEngineComparisonBenchmark.falco` / `.minestom`, one section, `emissionMix = UNIFORM`,
`lightSources` 1, 8 and 64 against `occlusionPercent` 0 and 30 — the six scenarios — one thread,
one fork, `-Xms512m -Xmx512m`, run as `-f 1 -wi 5 -i 10`, which overrides the class annotation of
3 warmup and 5 measurement iterations; no `-r` was recorded and the class annotates 1 s per
iteration. JMH 1.37, one 16-core machine recorded as not idle, measured at commit `69381af`, run
date not recorded, no `results.json` committed. All six pairs of intervals are disjoint and the
wider relative half-width of each pair is under 5 %, which is what admits two decimals; the
conservative bounds are 1.07× to 1.16× faster on the narrowest row and 1.63× to 1.80× faster on the
widest. One fork — the `±` covers variance between iterations of one JVM, not between JVM launches,
defined once in
[Rationale: Measurement](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale-Measurement#the-interval-after-a-number).
The six rows and their bounds are owned by
[Measured Results](https://github.com/OneLiteFeatherNET/Falco/wiki/Measured-Results#against-the-engine-minestom-ships-with).</sub>

The figure after a `±` is [the half-width of a confidence interval over the measurement iterations of
one JVM launch](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale-Measurement); where two
intervals overlap, no factor is printed. The charts and the methodology are in
[Benchmarking](https://github.com/OneLiteFeatherNET/Falco/wiki/Benchmarking), the full tables in
[Measured Results](https://github.com/OneLiteFeatherNET/Falco/wiki/Measured-Results), and the
optimisations that did **not** pay off in
[Project Status](https://github.com/OneLiteFeatherNET/Falco/wiki/Project-Status).

## Documentation

Everything past the quick start lives in the
[wiki](https://github.com/OneLiteFeatherNET/Falco/wiki):

- [Installation](https://github.com/OneLiteFeatherNET/Falco/wiki/Installation) — all three modules,
  the BOM, Maven, snapshots, the rendered Javadoc, and building from source
- [Anvil Chunk Loader](https://github.com/OneLiteFeatherNET/Falco/wiki/Anvil-Chunk-Loader) — what the
  loader does, how to use it standalone, and what it deliberately does not do
- [Light Engine](https://github.com/OneLiteFeatherNET/Falco/wiki/Light-Engine) — the engine, calling
  it by hand versus letting a chunk maintain its own light, its guarantees and its limits
- [Measured Results](https://github.com/OneLiteFeatherNET/Falco/wiki/Measured-Results) — the
  measurement environment and every published table with its provenance: the benchmark class and
  methods, the parameter values and the run configuration behind each row
- [Benchmarking](https://github.com/OneLiteFeatherNET/Falco/wiki/Benchmarking) — what each published
  table does and does not establish, and how a third party can re-perform any of it
- [Project Status](https://github.com/OneLiteFeatherNET/Falco/wiki/Project-Status) — the decisions
  that shape the design, what is in the branch, defects found and fixed, and what is open
- [Rationale](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale) — why it is built this way:
  what each decision was weighed against, and where the argument is weaker than the figures suggest
- [Research](https://github.com/OneLiteFeatherNET/Falco/wiki/Research) — the investigations run
  before writing any code, kept because each answers a question worth asking again
- [Contributing](https://github.com/OneLiteFeatherNET/Falco/wiki/Contributing) — what a change is
  built, tested and released with: the toolchain and library versions, the Gradle commands, the
  conventions the build enforces, what a push to `main` publishes, and what review looks for
- [Build Setup](https://github.com/OneLiteFeatherNET/Falco/wiki/Build-Setup) — how the Gradle build
  is put together, and where the rest of it is documented: dependency management, publishing,
  versioning and releases, tests and Javadoc, the benchmark and demo modules, the architecture rules

The shortest path from a clone to a green build, and the one credential problem that stops it, are in
[`CONTRIBUTING.md`](CONTRIBUTING.md) in this repository.

## Licence

AGPL-3.0. See [`LICENSE`](LICENSE).
