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

Measured, not asserted. Every figure below comes from a JMH benchmark in this repository; the
methodology is in [`docs/benchmarks.md`](docs/benchmarks.md), the full numbers in
[`STATUS.md`](STATUS.md). Expand a section for the chart and the table behind it.

<details>
<summary><b>Anvil loader as threads compete</b> — level on one thread, 8× on four</summary>

<br>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/charts/loader-contention-dark.svg">
  <img alt="Ratio bars around a baseline of 1.0. On one thread Falco reads 1.14 times slower than Minestom; on two threads it is 1.86 times faster and on four threads 8 times faster." src="docs/charts/loader-contention-light.svg">
</picture>

Reading one chunk, 200 distinct block states, µs/op:

| Threads | Falco | Minestom | |
| ---: | ---: | ---: | --- |
| 1 | 1 203 ± 123 | 1 060 ± 55 | 1.14× **slower** |
| 2 | 1 181 ± 31 | 2 200 ± 445 | 1.86× faster |
| 4 | 1 378 ± 84 | 11 021 ± 16 470 | 8.00× faster |
| 8 | 2 438 ± 252 | 530 905 ± 1 928 261 | see below |

**Single-threaded, Falco is the slower one.** The three-stage pipeline costs something to set up, and
with no contention there is nothing to win back. From two threads on, the picture inverts, and it
keeps inverting: Falco's own time grows by a factor of two from one thread to eight, Minestom's by
a factor of five hundred.

The eight-thread row is **not a usable figure** — its error bar is nearly four times its mean.
What it does show is that Minestom's read time stops being predictable under load, which for a
server is worse than being slow. Falco's error bar stays at ten percent of its mean throughout.

**Writing is a tie** (1.00× to 1.05× either way, at every thread count). Both implementations take a
lock to allocate sectors and update the header, so there is nothing to gain there — and claiming
otherwise would be easy and wrong.

<sub>Measured on a 16-core machine, JMH with one fork, 3 warmup and 5 measurement iterations of 2 s.
One fork is few; treat the direction as solid and the exact factors as indicative.</sub>

</details>

<details>
<summary><b>Light engine against Minestom's</b> — 1.11× to 1.71× faster, byte-identical output</summary>

<br>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/charts/light-engine-dark.svg">
  <img alt="Grouped bars comparing Falco and Minestom light engines across six scenarios in microseconds per operation. Falco is faster in every one, by 1.11x to 1.71x." src="docs/charts/light-engine-light.svg">
</picture>

| Sources | Solid | Falco | Minestom | |
| ---: | ---: | ---: | ---: | --- |
| 1 | 0 % | 44.5 ± 0.6 | 49.4 ± 1.3 | 1.11× faster |
| 1 | 30 % | 39.3 ± 0.8 | 62.0 ± 2.0 | 1.58× faster |
| 8 | 0 % | 98.3 ± 2.4 | 121.1 ± 5.5 | 1.23× faster |
| 8 | 30 % | 119.3 ± 3.5 | 204.2 ± 3.7 | **1.71× faster** |
| 64 | 0 % | 109.2 ± 1.6 | 126.5 ± 5.6 | 1.16× faster |
| 64 | 30 % | 122.6 ± 1.3 | 206.6 ± 4.2 | 1.68× faster |

The lead grows exactly where a real world is hardest — with solid blocks in the section, which is
every chunk that is not open sky. Both engines produce the same bytes, and
`LightEngineEquivalenceTest` asserts that on every build, so this is not speed bought with accuracy.

</details>

<details>
<summary><b>The same, with sources of mixed brightness</b> — the honest case</summary>

<br>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/charts/light-engine-mixed-dark.svg">
  <img alt="Grouped bars for mixed-brightness light sources. Falco leads in all four scenarios, most clearly at 30 percent solid blocks." src="docs/charts/light-engine-mixed-light.svg">
</picture>

| Sources | Solid | Falco | Minestom | |
| ---: | ---: | ---: | ---: | --- |
| 8 | 0 % | 118.97 ± 8.89 | 126.54 ± 9.55 | 1.06× faster |
| 8 | 30 % | 116.50 ± 7.63 | 201.46 ± 16.83 | 1.73× faster |
| 64 | 0 % | 150.42 ± 32.73 | 162.20 ± 3.11 | 1.08× faster |
| 64 | 30 % | 149.42 ± 12.64 | 252.26 ± 9.28 | 1.69× faster |

Sources of differing brightness make a position get queued more than once, which costs Falco about
33 % against uniform sources and shrinks the lead in the open-sky rows to a few percent. It is
reported here rather than left out, because a benchmark that only shows its best case is worth
nothing.

</details>

<details>
<summary><b>Where a save spends its time</b> — 97 % of it outside any lock</summary>

<br>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/charts/save-stages-dark.svg">
  <img alt="A single stacked bar splitting a chunk save into four stages. Snapshot and transfer hold a lock and take 81 microseconds together; codec and compression hold none and take 4057." src="docs/charts/save-stages-light.svg">
</picture>

| Stage | Time | Lock held |
| --- | ---: | --- |
| Snapshot | 64 µs | the read lock of the chunk |
| Codec, without compression | 1 356 µs | none |
| zlib compression | 2 701 µs | none |
| Transfer | 17 µs | the region lock |

This is the design claim as a number. Minestom's `RegionFile` reports
`supportsParallelLoading() == true` but serialises reading, decompression **and** NBT parsing
through a single `ReentrantLock`, so its parallelism is largely nominal. Moving that work out of the
lock is what the three-stage pipeline is for. Compression being 63 % of a save is also what made it
the optimisation target: at zlib level 2 it runs **1.83× faster** for about 3 % more bytes.

</details>

<details>
<summary><b>Two fast paths worth 51× and 76×</b></summary>

<br>

A section of pure air or pure stone carries a palette of one entry, and the majority of every world
looks like that. Detecting the case up front rather than walking 4 096 blocks:

| Fast path for a uniform section | Before | After | |
| --- | ---: | ---: | --- |
| Palette encode | 27.9 µs | 0.54 µs | **51×** |
| Opacity table | 40.8 µs | 0.54 µs | **76×**, and no arrays allocated |

</details>

Where an idea did **not** pay off, that is written down too — see the rejected optimisations in
`STATUS.md`, including the bucket queue that loses 5–7 % at equal source brightness.

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
