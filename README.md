# Falco

A high-performance Anvil chunk loader and light engine for
[Minestom](https://github.com/Minestom/Minestom), plus an `Instance` implementation that cleans up
after itself.

The first two replace something the platform already ships, and both exist for the same reason: the
versions that come with the server serialise work that does not have to be serialised, and lose
information that should not be lost. The third replaces something that works, for a reason that has
nothing to do with speed — and it claims none.

| Module | What it is |
| --- | --- |
| `falco-anvil` | A `ChunkLoader` for the Anvil region format. Genuinely parallel — reading, decompression and NBT parsing do not share one lock. A read failure throws instead of silently reporting the chunk as absent, so the server cannot overwrite real data with a freshly generated chunk. Unknown blocks and biomes survive a load/save round trip. |
| `falco-light` | A block and sky light engine. Thread-safe per call and not tied to any chunk implementation — results are handed over through `Light#set`, so it works with chunk types Minestom's own engine ignores. Call it yourself, or hand the instance `setChunkSupplier(scheduler.supplier())` and `FalcoLightingChunk` keeps its own light up to date — the entry point Minestom's `LightingChunk` offers, on any `Instance`. |
| `falco-instance` | An `Instance` and its `Chunk`. **No speed gain is claimed and none is measured**: chunk and entity ticking lives in the global `ThreadDispatcher` of the server process, not in the instance, so replacing the instance cannot make ticking faster. What it buys is an unload path of its own — `InstanceManager.unregisterInstance` skips the cleanup for anything that is not an `InstanceContainer` and leaks every chunk the instance ever loaded — and an implementation small enough to read and test. It runs a generator, and more carefully than the original: the generator writes into clones of the chunk's palettes, so one that fails halfway leaves nothing behind rather than a half-built chunk that reports itself loaded. What it cannot do is back a `SharedInstance` — that is a compiler wall, not an omission. |

## What "high-performance" means here

Measured, not asserted. Every figure comes from a JMH benchmark in this repository: the Anvil loader
reads up to **8× faster** than Minestom's under thread contention (roughly level single-threaded,
since the three-stage pipeline has nothing to win back without contention), and the light engine is
**1.11× to 1.71× faster**, with byte-identical output asserted on every build. The charts, the full
tables, the benchmark methodology and the optimisations that were tried and did **not** pay off are
all in the wiki — see
[Benchmarking](https://github.com/OneLiteFeatherNET/Falco/wiki/Benchmarking) and
[Project Status](https://github.com/OneLiteFeatherNET/Falco/wiki/Project-Status).

All three modules are **experimental**. Every public type carries `@ApiStatus.Experimental`;
signatures and behaviour may still change in a minor release.

## Quick start

From nothing to a server that serves a stored world, in four steps. The last one needs no client.

### 1. Declare the dependency

Minestom is `compileOnly` in Falco, so it does not arrive with these artefacts — declare the version
you intend to run yourself.

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.onelitefeather.dev/releases")
}

dependencies {
    implementation("net.onelitefeather:falco-anvil:0.3.0")
    implementation("net.onelitefeather:falco-light:0.3.0")

    // Falco declares no version for this one, on purpose. You pick it.
    implementation("net.minestom:minestom:<version>")
}
```

Maven, snapshots, the third module and the BOM that pins all three are in
[Using it](#using-it) below.

### 2. Write the server

```java
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
import net.onelitefeather.falco.light.ChunkLightService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

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
that needs no listener at all — `instance.setChunkSupplier(scheduler.supplier())`, covered in the
[Light Engine](https://github.com/OneLiteFeatherNET/Falco/wiki/Light-Engine) wiki page.

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
without stored light and after blocks change at runtime. Which case is which, and how to let the
light maintain itself instead of calling it by hand, is spelled out in
[Light Engine](https://github.com/OneLiteFeatherNET/Falco/wiki/Light-Engine).

## Using it

The quick start above shows the short version: two of the three modules, and Minestom itself. This
section adds the third module, the BOM, Maven, and snapshots — how to add it to a build, and nothing
past that. Standalone usage of each module, and how `falco-instance` is wired into a server, are in
the wiki pages linked under [Documentation](#documentation) below.

All three modules are published to the OneLiteFeather Reposilite, which serves them without
authentication. Each carries its own version:

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.onelitefeather.dev/releases")
}

dependencies {
    implementation("net.onelitefeather:falco-anvil:0.3.0")
    implementation("net.onelitefeather:falco-light:0.3.0")
    implementation("net.onelitefeather:falco-instance:0.3.0")
}
```

There is a fourth artefact, `falco-bom`, whose only content is a version for each of the three
above. Imported as a platform it makes that version a single line, so the modules cannot drift apart
into a combination nobody tested — they are built and released together, and the BOM is what says so
to a build that consumes them:

```kotlin
dependencies {
    // Written in the three-argument form on purpose: the README updater in renovate.json rewrites
    // the "group:name:version" form to the latest release, and the BOM has no release yet.
    implementation(platform("net.onelitefeather", "falco-bom", "0.3.1-SNAPSHOT"))

    // No versions here — the platform above carries them. Take only the modules you need; a
    // platform constrains a version for each, it does not pull in one you did not declare.
    implementation("net.onelitefeather:falco-anvil")
    implementation("net.onelitefeather:falco-light")
    implementation("net.onelitefeather:falco-instance")
}
```

The BOM was added after `0.3.0` was cut, so the release endpoint does not serve it yet and the
snapshot coordinate above is the only one that resolves today. From the next release on it is a
normal coordinate like the others, and this snippet should lose the snapshot version along with the
comment explaining it.

<details>
<summary>Snapshots</summary>

Every push to `main` that does not cut a release publishes to a second endpoint, public in the same
way:

```kotlin
repositories {
    maven("https://repo.onelitefeather.dev/snapshots")
}
```

The coordinates stay the same; only the version changes. It is the released version with its patch
bumped and `-SNAPSHOT` appended — so while `0.3.0` is the latest release, the snapshot endpoint
serves `0.3.1-SNAPSHOT`. That version keeps moving as commits land, which is the point of it and
also the reason not to build a release of your own against it.

</details>

<details>
<summary>Maven</summary>

```xml
<repository>
  <id>onelitefeater-repository-releases</id>
  <name>OneLiteFeather Network Reposilite Repository</name>
  <url>https://repo.onelitefeather.dev/releases</url>
</repository>

<repository>
  <id>onelitefeater-repository-snapshots</id>
  <name>OneLiteFeather Network Reposilite Repository</name>
  <url>https://repo.onelitefeather.dev/snapshots</url>
  <snapshots>
    <enabled>true</enabled>
  </snapshots>
</repository>
```

</details>

The modules are independent — take one without the other if that is all you need. Minestom itself is
`compileOnly` here, so you keep control of the version you run.

## Documentation

Everything past this point — how to use each module standalone, the design and its limits, the
measured numbers, the reasoning behind every decision, and the state of the project — lives in the
[wiki](https://github.com/OneLiteFeatherNET/Falco/wiki):

- [Anvil Chunk Loader](https://github.com/OneLiteFeatherNET/Falco/wiki/Anvil-Chunk-Loader) — what the
  loader does, how to use it standalone, and what it deliberately does not do
- [Light Engine](https://github.com/OneLiteFeatherNET/Falco/wiki/Light-Engine) — the engine, calling
  it by hand versus letting a chunk maintain its own light, its guarantees and its limits
- [Benchmarking](https://github.com/OneLiteFeatherNET/Falco/wiki/Benchmarking) — headline results,
  how the JMH suite is run, and what each benchmark measures
- [Project Status](https://github.com/OneLiteFeatherNET/Falco/wiki/Project-Status) — test counts,
  environment, the decisions that shape the design, the full measured numbers, defects found and
  fixed, and what is still open
- [Rationale](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale) — why the project is built
  the way it is: what each decision was weighed against, and where the argument is weaker than the
  headline figures suggest
- [Research](https://github.com/OneLiteFeatherNET/Falco/wiki/Research) — the investigations run
  before writing any code, kept because each answers a question worth asking again

`falco-instance` is used at the point a world is created; how it is registered and unregistered, and
the four places Minestom quietly treats a foreign `Instance` differently, are in
[Rationale: Instances and Chunks](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale-Instances-And-Chunks).

### API documentation

The Reposilite that serves the artefacts renders their Javadoc as well, so every published version
has a page to read in the browser:

| Module | Rendered Javadoc |
| --- | --- |
| `falco-anvil` | [`falco-anvil` 0.3.0](https://repo.onelitefeather.dev/javadoc/releases/net/onelitefeather/falco-anvil/0.3.0) |
| `falco-light` | [`falco-light` 0.3.0](https://repo.onelitefeather.dev/javadoc/releases/net/onelitefeather/falco-light/0.3.0) |
| `falco-instance` | [`falco-instance` 0.3.0](https://repo.onelitefeather.dev/javadoc/releases/net/onelitefeather/falco-instance/0.3.0) |

The same pages also travel with the artefacts, as the `-javadoc.jar` every published module carries
next to its main jar — the copy an IDE fetches through *Download Sources and Documentation* and
attaches to the classes, for offline, in-editor reading. To build the pages from source instead,
`./gradlew javadoc` writes one tree per module to `falco-<module>/build/docs/javadoc/index.html`;
that needs the credentials described under [Building](#building), reading the rendered pages or a
javadoc jar needs none.

## Building

```bash
./gradlew build                      # compile, javadoc, tests
./gradlew :falco-benchmarks:jmhJar   # the benchmarks; a build never runs them
```

Java 25 is required.

**Building from source currently needs OneLiteFeather Maven credentials.** Falco compiles against
Minestom and the internal `mycelium-bom`, which are served from an authenticated endpoint, so
`./gradlew build` fails with a 401 without `ONELITEFEATHER_MAVEN_USERNAME` and
`ONELITEFEATHER_MAVEN_PASSWORD`. Consuming the published artefacts needs none of this — only
building the project itself does.

## Licence

AGPL-3.0. See [`LICENSE`](LICENSE).
