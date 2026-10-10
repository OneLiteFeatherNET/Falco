# Research: a faster, lower-memory light engine

**Question.** Implement light calculation ourselves — faster and using less memory than Minestom's —
with TDD, SOLID, DRY and current Java 25 features. This page is the investigation that preceded
`falco-light`; it is not a description of the shipped engine, and none of its figures are the
project's published benchmark numbers.

**Method.** Reading the Minestom sources at `2026.06.20-26.1.2` (1 454 files) and running probe code
against the jar of that version, including a prototype engine timed against the real, package-private
`LightCompute.compute`. The probes live outside this repository and are not checked in. **The timings
below are not JMH results.** There is no recorded machine, no fork count, no warmup or iteration
configuration and no uncertainty for any of them.

**Established, and durable.** That `Light` is not sealed and can be implemented from a foreign
package, proven end to end through to the `LightData` record sent to the client. That Minestom's
coupling to its own light types is two `instanceof` sites and no casts. That a pre-lit Anvil chunk is
never relit, because `Light#set` clears the update flag `LightingChunk` tests before recomputing.
That 160 of 1 168 block types have directional occlusion, so a single-bitset occlusion table is
incorrect rather than merely approximate. Each of these is checkable against the same jar.

**Open, or not established.** Every timing. 5.8×, 3.1×, 12×, 2.2 ms, 0.586 ms, 62 / 28 / 4.5 %,
5.5 KB–33.7 KB — all from unrepeated probe runs with unrecorded conditions. They are kept because
each has a structural mechanism named alongside it, and the mechanism is the part that carries.
No claim on this page may be quoted as a benchmark result.

**True at.** Written 2026-07-31, entered the repository at
[`fc0aef5`](https://github.com/OneLiteFeatherNET/Falco/commit/fc0aef5), against Minestom
`2026.06.20-26.1.2`. The structural claims were re-checked against that jar at `ca79507`; one line
reference had drifted and is corrected in place. **The engine described here was never built as
described** — what `falco-light` is, and what it measures, is on [How the light engine works](../how-the-light-engine-works.md) and
[What the benchmarks establish](../what-the-benchmarks-establish.md).

## Feasibility: proven, not assumed

`Light` is **not** sealed (`Light.java:13`, `public interface Light`), unlike `Palette`. All nine
methods are implementable. `Section` is a `public record` with a public canonical constructor, so
`new Section(blockPalette, biomePalette, myLight, myLight)` compiles and runs. Both re-checked at
`ca79507`: `Light` declares nine instance methods and no `permits` clause, and `Section` is
`public record Section(Palette blockPalette, Palette biomePalette, Light skyLight, Light blockLight)`.

An agent proved this end to end: a custom `Light` writing the marker byte `0xCD` reached the
`LightData` record that goes to the client:

```
chunk class = e2e.E2E$FalcoChunk
section skyLight class = e2e.E2E$MarkerLight
LightData skyLight entries=2 blockLight entries=24
first blockLight[0]=0xcd len=2048
calculateInternal calls=48 calculateExternal calls=0
```

Coupling is minimal: across all 1 454 Minestom sources there are exactly **two** `instanceof
BlockLight` / `instanceof SkyLight` sites — `light/SkyLight.java:123` and `light/BlockLight.java:123`,
both inside the stock implementations themselves — and **zero** explicit casts. Re-checked at
`ca79507`. `LightingChunk` and `Section` work purely against the interface. If a custom chunk
supplier is the only one in use, those two lines never execute.

Injection path: `Section` record constructor + the `protected LightingChunk(Instance, int, int,
List<Section>)` constructor + `InstanceContainer.setChunkSupplier`.

## The finding that decides it

> **When loading pre-lit Anvil worlds, the light engine does not run at all.**

`AnvilLoader` reads `SkyLight`/`BlockLight` straight from the NBT and calls `section.skyLight().set(...)`.
Our loader does exactly the same. Measured: `after invalidate() requiresUpdate=true` →
`after set(2048) requiresUpdate=false`. Since `LightingChunk.createLightData` only relights when
`requiresUpdate()` is true, a pre-lit chunk is never recomputed.

For loading pre-built maps from region files the light engine is therefore **0 %** of the load path.
The 0 % is structural — the code does not run — and does not depend on any timing. Next to it the
investigation's own breakdown of what the load path does cost: NBT parse 62 %, inflate 28 %, palette
4.5 %, from an unrepeated probe with no recorded conditions. A faster engine speeds that up by
exactly nothing, and that conclusion follows from the 0 %, not from the breakdown.

Real cost exists in two other workloads, both from the same probe and with the same limits — one run,
no recorded machine, no uncertainty:

| Workload | Probe figure |
| --- | --- |
| Relight of a generated chunk (no stored light) | 2.2 ms/chunk, ≈92 µs/section |
| Runtime `setBlock` (placing a torch) | 0.586 ms per placement |

<sub>Standalone probe against Minestom `2026.06.20-26.1.2`, not JMH, not committed, one run, machine
and JVM unrecorded, no uncertainty. Use these to decide *whether the workload is in your profile*,
not to compare against anything.</sub>

## What a custom engine would gain

A prototype was timed against the real, package-private `LightCompute.compute`:

| Scenario | Minestom | Prototype | Factor | Divergence |
| --- | --- | --- | --- | --- |
| Realistic block mix (air/stone/leaves/water/glass/dirt) | 576.1 µs | 99.3 µs | **5.8× faster** | 0 of 4096 cells |
| Adversarial (slabs, stairs, snow, farmland, dirt path, glowstone) | 659.1 µs | 212.9 µs | **3.1× faster** | 0 of 4096 cells |

<sub>Prototype engine against `net.minestom.server.instance.light.LightCompute.compute` at
`2026.06.20-26.1.2`, standalone probe, **not JMH**: no fork count, no warmup or measurement
iterations, no machine recorded, no uncertainty on either column, single run, not committed. The
factors carry no interval and must not be quoted as a benchmark result. The **divergence column is
the durable half of this table** — it is a correctness property, not a timing, and it says both
engines produced identical output for all 4 096 cells.</sub>

The lever is not the algorithm but the occlusion check. Minestom re-reads the source block in each of
the six directions and resolves palette → `Block.fromStateId` → `registry().occlusionShape()` →
`isOccluded` live. Over 3 000 000 probe iterations: **live 10.95 ns/op against 0.91 ns/op for a
precomputed bitset — a factor of 12 in favour of the table**, with a 512-byte table per section and
zero divergence. Same limits as above: one run, no uncertainty. The mechanism is what makes the
direction credible — a table lookup replaces a registry resolution — and the mechanism survives
whatever the exact factor turns out to be.

Minestom's `compute` is a plain FIFO BFS over packed shorts (`[4bit level][4bit y][4bit z][4bit x]`),
using a primitive `ShortArrayFIFOQueue` — no boxing, already clean. The probe reports allocation of
5.5 KB to 33.7 KB per section, partly because the queue starts at capacity 4 and doubles about 11
times.

**A correction to the original assumption:** Starlight does *not* use a bucket queue. Its
`TECHNICAL_DETAILS.md` states the queue is a plain FIFO. Its actual principles are a per-entry mask of
which neighbours still need checking ("Vanilla checks ALL 6 neighbours, Starlight checks JUST ONE"),
a per-entry flag whether a shape check is needed at all, and a bitset of guaranteed-opacity-0 blocks
for skylight seeding.

## Traps

- **13.70 % of all block types have directional occlusion.** Of 1 168 types: 364 uniformly opaque,
  644 uniformly transparent, **160 directional** — slabs, stairs, snow, farmland, dirt paths,
  lecterns, daylight detectors, stonecutters. The three counts sum to 1 168 and 160 / 1 168 is
  13.70 %, so the figure is a census of the registry rather than a sample of it. A first draft using a
  single top-face bitset produced 472 of 4 096 wrong cells. A naive one-bitset design is simply
  incorrect — and this, not any timing, is the finding that shaped the engine.
- **`Section.clone()` and `LightingChunk.copy()` silently fall back to Minestom's light.**
  `Section.java:26-27` calls `Light.sky()` / `Light.block()` outright — the original note said
  `:27-28`, corrected here against `2026.06.20-26.1.2` at `ca79507`. Verified in the probe:
  `clone() skyLight impl = net.minestom.server.instance.light.SkyLight`. **This affects the existing
  Anvil loader**, whose save snapshot uses `section.clone()`. `copy()` would have to be overridden or
  the system degrades back unnoticed.
- `calculateInternal` / `calculateExternal` are `@ApiStatus.Internal`, so the adapter surface can
  change between Minestom versions. Keep the core engine Minestom-free and the adapter thin — risk
  management, not purity.
- Minestom's `content` / `contentPropagation` fields are non-volatile and published across threads.
  Do not try to fix Minestom's concurrency model along the way.
- Dead code in the hot path: `LightingChunk.flushQueue` builds a `Set<Light> sections`
  (`LightingChunk.java:362`) that is filled and never read. Still present at `ca79507`.

## Recommendation from the agents

Build it only if the goal is **block-placement latency** (0.586 ms per torch in the probe) or
relighting generated worlds — not chunk load throughput, where it contributes nothing. Keep the core
engine free of Minestom types, and write the parity test against `Light.block()` / `Light.sky()`
**first**, before a single line of engine code; it is demonstrably feasible, and it is the thing that
turns a one-run probe figure into a claim anyone can check, because a faster engine that computes
something else is not a faster engine. Realistic effort: several days for engine, adapter and test
suite, plus ongoing maintenance against an `@Internal` API.

Independently of the decision: the analysis suggests adding the light array length check to the Anvil
loader that Minestom performs.

## What was built, and how it differs from this page

`falco-light` was built and the parity-test-first recommendation was followed:
`LightEngineEquivalenceTest` pins byte-identical output against Minestom's engine over 54 scenarios
on every build, and `LightEngineComparisonBenchmark` verifies the same property in `@Setup` and
aborts the trial if the two engines disagree. That is the mechanism this page asked for, and it is
why the shipped comparison has evidence this page does not.

**Nothing on this page is that evidence.** The published light-engine figures come from JMH classes
with stated parameters, iteration counts and intervals, and they are on
[What the benchmarks establish](../what-the-benchmarks-establish.md) and [Measured results](../../reference/measured-results.md). The 5.8× and 3.1× here are a
prototype that was never merged, measured by a probe that was never committed. The two sets of
numbers are not comparable and neither supports the other.

## References

- Minestom `2026.06.20-26.1.2`, sources jar from the Gradle cache: `instance/light/Light.java:13`,
  `instance/light/SkyLight.java:123`, `instance/light/BlockLight.java:123`,
  `instance/light/LightCompute.java`, `instance/Section.java:26-27`,
  `instance/LightingChunk.java:362`, `:419`. Re-checked at `ca79507`.
- Starlight, `TECHNICAL_DETAILS.md` — the source that refuted the bucket-queue assumption.
- What the shipped engine does and what it guarantees: [How the light engine works](../how-the-light-engine-works.md).
- Why `falco-light` exists at all, with each claim labelled by how it was established:
  [Why a custom light engine](../why-a-custom-light-engine.md).
