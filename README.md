# Falco

[![Release](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo.onelitefeather.dev%2Freleases%2Fnet%2Fonelitefeather%2Ffalco-anvil%2Fmaven-metadata.xml&label=release&color=blue)](https://github.com/OneLiteFeatherNET/Falco/wiki/Installation)
[![Java 25](https://img.shields.io/badge/Java-25-orange)](https://github.com/OneLiteFeatherNET/Falco/wiki/Installation#building-from-source)
[![API status: experimental](https://img.shields.io/badge/API-experimental-yellow)](https://github.com/OneLiteFeatherNET/Falco/wiki/Project-Status)
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
| [`falco-instance`](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale-Instances-And-Chunks) | An `Instance` and its `Chunk`. **No speed gain is claimed and none is measured** — ticking lives in the server's global `ThreadDispatcher`, not in the instance. What it buys is an unload path of its own, where `InstanceManager.unregisterInstance` leaks every chunk a foreign instance ever loaded. It cannot back a `SharedInstance`. |

All three modules are **experimental**. Every public type carries `@ApiStatus.Experimental`;
signatures and behaviour may still change in a minor release.

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

The two-thread figure did not reproduce, and that belongs next to it: an independent run of the same
configuration put Minestom at 103 437 ± 856 306 µs/op there, which carries no factor at all. What
survives both runs is the direction and the loss of predictability, not the 1.9×.

**The light engine is 1.11× to 1.71× faster** over six scenarios, every pair of intervals disjoint,
with byte-identical output asserted on every build.

The figure after a `±` is the half-width of a confidence interval over the measurement iterations of
one JVM launch; where two intervals overlap, no factor is printed. The charts, the full tables, the
methodology and the optimisations that did **not** pay off are in
[Benchmarking](https://github.com/OneLiteFeatherNET/Falco/wiki/Benchmarking) and
[Project Status](https://github.com/OneLiteFeatherNET/Falco/wiki/Project-Status).

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

## Documentation

Everything past the quick start lives in the
[wiki](https://github.com/OneLiteFeatherNET/Falco/wiki):

- [Installation](https://github.com/OneLiteFeatherNET/Falco/wiki/Installation) — all three modules,
  the BOM, Maven, snapshots, the rendered Javadoc, and building from source
- [Anvil Chunk Loader](https://github.com/OneLiteFeatherNET/Falco/wiki/Anvil-Chunk-Loader) — what the
  loader does, how to use it standalone, and what it deliberately does not do
- [Light Engine](https://github.com/OneLiteFeatherNET/Falco/wiki/Light-Engine) — the engine, calling
  it by hand versus letting a chunk maintain its own light, its guarantees and its limits
- [Benchmarking](https://github.com/OneLiteFeatherNET/Falco/wiki/Benchmarking) — what each published
  table does and does not establish, and how a third party can re-perform any of it
- [Project Status](https://github.com/OneLiteFeatherNET/Falco/wiki/Project-Status) — the full
  measured record, the decisions that shape the design, defects found and fixed, and what is open
- [Rationale](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale) — why it is built this way:
  what each decision was weighed against, and where the argument is weaker than the figures suggest
- [Research](https://github.com/OneLiteFeatherNET/Falco/wiki/Research) — the investigations run
  before writing any code, kept because each answers a question worth asking again

## Licence

AGPL-3.0. See [`LICENSE`](LICENSE).
