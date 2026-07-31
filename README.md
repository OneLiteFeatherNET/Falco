# Falco

A high-performance Anvil chunk loader and light engine for
[Minestom](https://github.com/Minestom/Minestom).

Both replace something the platform already ships, and both exist for the same reason: the versions
that come with the server serialise work that does not have to be serialised, and lose information
that should not be lost.

| Module | What it is |
| --- | --- |
| `falco-anvil` | A `ChunkLoader` for the Anvil region format. Genuinely parallel — reading, decompression and NBT parsing do not share one lock. A read failure throws instead of silently reporting the chunk as absent, so the server cannot overwrite real data with a freshly generated chunk. Unknown blocks and biomes survive a load/save round trip. |
| `falco-light` | A block and sky light engine. Thread-safe per call and not tied to any chunk implementation — results are handed over through `Light#set`, so it works with chunk types Minestom's own engine ignores. |

## What "high-performance" means here

Measured, not asserted. Every figure below comes from a JMH benchmark in this repository, and the
numbers plus the methodology are in [`docs/benchmarks.md`](docs/benchmarks.md) and
[`STATUS.md`](STATUS.md).

- **The light engine is 1.11× to 1.71× faster than Minestom's** across all six measured scenarios
  (1, 8 and 64 light sources × empty and 30 % solid), while producing a **byte-identical** result —
  a test asserts that equivalence on every build, so speed never comes at the cost of correctness.
- **A uniform section costs 0.54 µs instead of 27.9 µs** to encode and 0.54 µs instead of 40.8 µs to
  build an opacity table for — 51× and 76×, with no arrays allocated. Sections of pure air or pure
  stone are the majority of every world.
- **Chunk compression runs 1.83× faster** at zlib level 2 for about 3 % more bytes, and compression
  is 63 % of what a save costs.
- **The loader's advantage is lock granularity, so it appears under contention.** Single-threaded,
  it and Minestom's are level; the gap opens as threads are added, which is what
  `RegionFileComparisonBenchmark` parameterises on. Minestom's `RegionFile` reports
  `supportsParallelLoading() == true` but serialises reading, decompression *and* NBT parsing
  through one `ReentrantLock`.

Where an idea did **not** pay off, that is written down too — see the rejected optimisations in
`STATUS.md`.

Both modules are **experimental**. Every public type carries `@ApiStatus.Experimental`; signatures
and behaviour may still change in a minor release.

## Using it

Both modules are published to the OneLiteFeather Reposilite, which serves them without
authentication:

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.onelitefeather.dev/releases")
    // snapshots: https://repo.onelitefeather.dev/snapshots
}

dependencies {
    implementation("net.onelitefeather:falco-anvil:0.1.0")
    implementation("net.onelitefeather:falco-light:0.1.0")
}
```

<details>
<summary>Maven</summary>

```xml
<repository>
  <id>onelitefeater-repository-releases</id>
  <name>OneLiteFeather Network Reposilite Repository</name>
  <url>https://repo.onelitefeather.dev/releases</url>
</repository>
```

</details>

The modules are independent — take one without the other if that is all you need. Minestom itself is
`compileOnly` here, so you keep control of the version you run.

```java
// The loader takes the world root, not the region directory, and stays alive with the instance.
FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());

instance.setChunkLoader(loader);
instance.enableAutoChunkLoad(true);
```

```java
ChunkLightService lighting = new ChunkLightService();   // one per worker thread

lighting.calculate(chunk);
int level = lighting.blockLightAt(chunk, 8, 40, 8);
```

The light engine works on any chunk, whichever loader produced it.

## Documentation

- [`docs/anvil-chunk-loader.md`](docs/anvil-chunk-loader.md) — what the loader does, how to use it,
  and what it deliberately does not do
- [`docs/light-engine.md`](docs/light-engine.md) — the engine, its guarantees and its limits
- [`docs/benchmarks.md`](docs/benchmarks.md) — how it was measured and against what
- [`STATUS.md`](STATUS.md) — the state of the project and the findings that cost real effort to
  establish
- [`docs/research/`](docs/research) — investigations that shaped the design, including the ones that
  ended in "do not build this"

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
