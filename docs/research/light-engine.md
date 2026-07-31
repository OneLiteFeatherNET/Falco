# Research: a faster, lower-memory light engine

**Question.** Implement light calculation ourselves — faster and using less memory than Minestom's —
with TDD, SOLID, DRY and current Java 25 features.

**Verdict: feasible, and a prototype was measurably faster with bit-identical output. But for
pre-lit worlds the code path does not execute at all.** Nothing has been implemented in the project.

## Feasibility: proven, not assumed

`Light` is **not** sealed (`Light.java:13`, `public interface Light`), unlike `Palette`. All nine
methods are implementable. `Section` is a `public record` with a public canonical constructor, so
`new Section(blockPalette, biomePalette, myLight, myLight)` compiles and runs.

An agent proved this end to end: a custom `Light` writing the marker byte `0xCD` reached the
`LightData` record that goes to the client:

```
chunk class = e2e.E2E$FalcoChunk
section skyLight class = e2e.E2E$MarkerLight
LightData skyLight entries=2 blockLight entries=24
first blockLight[0]=0xcd len=2048
calculateInternal calls=48 calculateExternal calls=0
```

Coupling is minimal: across all 1454 Minestom sources there are exactly **two** `instanceof BlockLight`
/ `instanceof SkyLight` sites, both inside the stock implementations themselves, and **zero** explicit
casts. `LightingChunk` and `Section` work purely against the interface. If a custom chunk supplier is
the only one in use, those two lines never execute.

Injection path: `Section` record constructor + the `protected LightingChunk(Instance, int, int,
List<Section>)` constructor + `InstanceContainer.setChunkSupplier`.

## The finding that decides it

> **When loading pre-lit Anvil worlds, the light engine does not run at all.**

`AnvilLoader` reads `SkyLight`/`BlockLight` straight from the NBT and calls `section.skyLight().set(...)`.
Our loader does exactly the same. Measured: `after invalidate() requiresUpdate=true` →
`after set(2048) requiresUpdate=false`. Since `LightingChunk.createLightData` only relights when
`requiresUpdate()` is true, a pre-lit chunk is never recomputed.

For loading pre-built maps from region files the light engine is therefore **0 %** of the load path,
next to the measured NBT parse 62 %, inflate 28 %, palette 4.5 %. A faster engine speeds that up by
exactly nothing.

Real cost exists in two other workloads, both measured:

| Workload | Cost |
| --- | --- |
| Relight of a generated chunk (no stored light) | 2.2 ms/chunk, ≈92 µs/section |
| Runtime `setBlock` (placing a torch) | 0.586 ms per placement |

## What a custom engine would gain

A prototype was benchmarked against the real, package-private `LightCompute.compute`:

| Scenario | Minestom | Prototype | Factor | Divergence |
| --- | --- | --- | --- | --- |
| Realistic block mix (air/stone/leaves/water/glass/dirt) | 576.1 µs | 99.3 µs | **5.8×** | 0 of 4096 cells |
| Adversarial (slabs, stairs, snow, farmland, dirt path, glowstone) | 659.1 µs | 212.9 µs | **3.1×** | 0 of 4096 cells |

The lever is not the algorithm but the occlusion check. Minestom re-reads the source block in each of
the six directions and resolves palette → `Block.fromStateId` → `registry().occlusionShape()` →
`isOccluded` live. Measured over 3,000,000 iterations: **live 10.95 ns/op versus 0.91 ns/op for a
precomputed bitset — factor 12**, with a 512-byte table per section and zero divergence.

Minestom's `compute` is a plain FIFO BFS over packed shorts (`[4bit level][4bit y][4bit z][4bit x]`),
using a primitive `ShortArrayFIFOQueue` — no boxing, already clean. Measured allocation is 5.5 KB to
33.7 KB per section, partly because the queue starts at capacity 4 and doubles about 11 times.

**A correction to the original assumption:** Starlight does *not* use a bucket queue. Its
`TECHNICAL_DETAILS.md` states the queue is a plain FIFO. Its actual principles are a per-entry mask of
which neighbours still need checking ("Vanilla checks ALL 6 neighbours, Starlight checks JUST ONE"),
a per-entry flag whether a shape check is needed at all, and a bitset of guaranteed-opacity-0 blocks
for skylight seeding.

## Traps

- **13.70 % of all block types have directional occlusion.** Of 1168 types: 364 uniformly opaque,
  644 uniformly transparent, **160 directional** — slabs, stairs, snow, farmland, dirt paths,
  lecterns, daylight detectors, stonecutters. A first draft using a single top-face bitset produced
  472 of 4096 wrong cells. A naive one-bitset design is simply incorrect.
- **`Section.clone()` and `LightingChunk.copy()` silently fall back to Minestom's light.**
  `Section.java:27-28` calls `Light.sky()` / `Light.block()` outright. Verified:
  `clone() skyLight impl = net.minestom.server.instance.light.SkyLight`. **This affects the existing
  Anvil loader**, whose save snapshot uses `section.clone()`. `copy()` would have to be overridden or
  the system degrades back unnoticed.
- `calculateInternal` / `calculateExternal` are `@ApiStatus.Internal`, so the adapter surface can
  change between Minestom versions. Keep the core engine Minestom-free and the adapter thin — risk
  management, not purity.
- Minestom's `content` / `contentPropagation` fields are non-volatile and published across threads.
  Do not try to fix Minestom's concurrency model along the way.
- Dead code in the hot path: a `Set<Light> sections` is filled and never read.

## Recommendation from the agents

Build it only if the goal is **block-placement latency** (0.586 ms per torch) or relighting generated
worlds — not chunk load throughput, where it contributes nothing. Keep the core engine free of
Minestom types, and write the parity test against `Light.block()` / `Light.sky()` **first**, before a
single line of engine code; it is demonstrably feasible and is what makes the 3–6× credible rather
than merely claimed. Realistic effort: several days for engine, adapter and test suite, plus ongoing
maintenance against an `@Internal` API.

Independently of the decision: the analysis suggests adding the light array length check to the Anvil
loader that Minestom performs.
