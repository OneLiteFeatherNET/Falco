# Falco

An Anvil chunk loader and a light engine for [Minestom](https://github.com/Minestom/Minestom).

Both replace something the platform already ships, and both exist for the same reason: the versions
that come with the server serialise work that does not have to be serialised, and lose information
that should not be lost.

| Module | What it is |
| --- | --- |
| `falco-anvil` | A `ChunkLoader` for the Anvil region format. Genuinely parallel — reading, decompression and NBT parsing do not share one lock. A read failure throws instead of silently reporting the chunk as absent, so the server cannot overwrite real data with a freshly generated chunk. Unknown blocks and biomes survive a load/save round trip. |
| `falco-light` | A block and sky light engine. Thread-safe per call and not tied to any chunk implementation — results are handed over through `Light#set`, so it works with chunk types Minestom's own engine ignores. Produces byte-identical output to the engine the server ships with, which a test pins down. |

Both are **experimental**. Every public type carries `@ApiStatus.Experimental`; signatures and
behaviour may still change in a minor release.

## Using it

```kotlin
dependencies {
    implementation("net.onelitefeather:falco-anvil:0.1.0")
    implementation("net.onelitefeather:falco-light:0.1.0")
}
```

The modules are independent — take one without the other if that is all you need.

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

## Licence

AGPL-3.0. See [`LICENSE`](LICENSE).
