# What the benchmarks establish

What the JMH suite in `falco-benchmarks` measures, what a result of it can and cannot mean, and the
threats to validity that apply to every number published from it.

The numbers themselves are on [Reference Measured results](../reference/measured-results.md). To run the suite, see
[How-to Run the JMH benchmark suite](../how-to/run-the-jmh-benchmark-suite.md).

Three questions made this suite exist, and every benchmark in it answers one of them.

1. **Does the structural claim turn into a number?** The loader's claim is about lock granularity and
   the light engine's is about preparation cost. Both are readable in the source; neither is worth
   anything until the part outside the lock, or the part that was removed, is weighed.
2. **Is it actually faster than what Minestom ships?** Not faster than a reimplementation of it, and
   not faster than a description of it. The comparison benchmarks call the original types.
3. **Should a design survive?** `AreaVsPerChunkBenchmark` and `IncrementalVsFullBenchmark` exist to
   kill an idea that does not pay for its complexity. Both were written before the answer was known,
   and the simpler design was the fallback in each case.

### Three kinds of benchmark, and they are not interchangeable

Every table on this page is labelled with one of these, and no number from one kind may be compared
with a number from another.

| Kind | What runs | What a number from it means |
| --- | --- | --- |
| **Library** | No Minestom server. The registry is replaced by a fake and the chunk is plain arrays. | What *this* code contributes to an operation. Not a chunk load time, not comparable with any comparison row. |
| **Comparison** | The real Minestom implementation, on the same inputs, in the same process. Three of the four start a server. | A head-to-head result. Absolute microseconds include registry time on **both** sides, so they are comparable only against their own counterpart. |
| **Decision** | Falco against Falco, on real Minestom chunks. | Which of two Falco designs is cheaper. Says nothing about Minestom and nothing about absolute cost. |

The distinction is not cosmetic. A library benchmark deliberately removes the most expensive thing a
real server does; a comparison benchmark deliberately keeps it. Reading a `44.5 µs` light row as
"lighting a section costs 44.5 µs on a server" is the single most likely misreading of this page, and
it is wrong in both directions at once.

### How to read a row

The figure after a `±` is defined once, in
[What a measurement here means](what-a-measurement-here-means.md) — it is a confidence-interval half-width over the
measurement iterations of a single JVM, not a standard deviation and not a min–max range. Every table
here carries a provenance line naming the class, the parameters, the thread count, the run settings
and that limitation, directly beneath it.

Given that, each comparison in this suite falls into exactly one of four categories. The test is
mechanical: write the faster side as `a ± ea` and the slower as `b ± eb`.

| Label | Test | What may be written |
| --- | --- | --- |
| **Supported** | `a + ea < b − eb` — the intervals do not overlap | The factor, together with the conservative bounds `(b − eb) / (a + ea)` and `(b + eb) / (a − ea)`. Stated plainly; a disjoint pair at this interval width is strong. |
| **No difference resolvable** | The intervals overlap | The direction of the two means, marked as unresolved. Never "the two are equal", and never a factor. |
| **Not usable** | `ea / a` or `eb / b` is 0.5 or more | No factor at any precision. What such a row evidences is dispersion — that the time stopped being predictable — which is a real finding, stated in words. |
| **Derived** | The row is arithmetic over other rows | Labelled as derived, with the errors of its inputs added. If the inputs carry no `±`, the derived row carries no factor either. |

Two consequences a hostile reader should hold us to. The interval width used here is generous, so a
**disjoint pair is a strong result and is stated without hedging** — hedging one of those is as much
a defect as printing a factor on a row that cannot carry one. And an overlapping pair is **not** a
null result: it says the difference was not resolved at this precision, not that there is none. Both
rules are applied to the rows where Falco loses exactly as they are to the rows where it wins.

### What a result of this suite cannot mean

- **It is not portable.** Core count, CPU frequency scaling, the JIT's compilation plan on the day,
  the page cache and the file system all move these numbers. A result from your laptop says nothing
  about a production host, and neither does ours.
- **It is not a chunk load time.** No benchmark here includes registry lookups, chunk allocation,
  packet assembly, the scheduler or anything else a server does around this code. They report what
  this library contributes to it.
- **It is not a tail latency.** Every benchmark in the suite is `Mode.AverageTime`; nothing here
  reports a percentile. A server's felt performance is dominated by the worst tick, and the mean is
  only a proxy for it. For the contention rows in particular, the tail is the interesting quantity.
- **It is not a property of the software.** Every published table comes from a single JVM process.
  It is a property of that process on that machine — which is why the *direction* and the *order of
  magnitude* are what the project claims, and why every repeat that has been run is reported.
- **`RegionFileBenchmark` is not a storage benchmark.** It runs on a warm page cache and measures
  almost no device time. Realistic for a server saving the same chunks repeatedly; useless as a
  statement about a disk.


## Threats to validity

The list below is what a reviewer would raise, answered in the order the objections actually bite.
Each says what the threat is and whether the harness does anything about it. Where the answer is
"nothing", that is written as "nothing".

**One fork, on every table above. Not mitigated.** The classes that run at `@Fork(2)` — seven of them
at `a09c71f` — produced no published number between them. All four comparison classes and both
decision classes are `@Fork(1)`, so every table on this page describes dispersion inside one JVM
process. A different JIT compilation plan, a different GC ergonomic decision, a different code layout
between launches — none of that is sampled even once, and no `±` on this page covers it. This is the
largest single limitation of the evidence base. Raising `@Fork` on the six classes that matter costs
wall clock and no code.

**Five cross-run repeats exist, and they are worth more than any interval here.** The register is
[Measured results](../reference/measured-results.md), which collects the measured tables; they are the four-thread
read comparison at two forks and ten iterations (above), the second single-fork run of the whole
loader table published in [How the Anvil loader is built](how-the-anvil-loader-is-built.md), `IncrementalVsFullBenchmark`,
where an independent earlier run gave 1.96× / 6.40× / 3.95× against the published
2.07× / 5.60× / ≈ 3.7×, and the `AreaVsPerChunkBenchmark` nine-chunk row, re-run at 5.37× against
5.47× with overlapping ratio bounds. Those two teach the same lesson and it is the honest
summary of this whole page: **ratios reproduce, third digits do not.** The other two, both on the
loader table, teach a sharper one. On the loader table all four of Falco's intervals overlap between the two runs, while
Minestom's means move by up to 47× and its intervals widen until they constrain nothing — a finding
about reproducibility rather than about speed, and one the project did not set out to make.

**Warmup is thin on the largest benchmarks. Not mitigated and not checked.** Warmup is 3 × 1 s or
5 × 1 s. For a library benchmark running at tens of microseconds per operation that is tens of
thousands of invocations, comfortably past C2 compilation. For the decision benchmarks it is not:
`AreaVsPerChunkBenchmark` at `chunkCount = 16` runs at roughly 11 600 µs/op, which is about 260
warmup invocations in total, and `IncrementalVsFullBenchmark` sky light at roughly 39 600 µs/op gets
about 75. Whether the JIT has reached a steady state on those paths in 75 invocations is genuinely
unknown, and nothing in the harness tests it. Raising `-wi` and showing the number does not move
would settle it.

**Dead-code elimination. Mitigated, with one exception that is documented rather than hidden.** 40 of
the 50 measured methods return their result, three consume every value through a `Blackhole`, and the
remaining seven perform file I/O that cannot be optimised away. The exception is
`LightNibblesBenchmark.getUniform` and `.setUniformUnchanged`, which are provably eliminated and are
published as lower bounds rather than per-access costs — the detail is in
[The one place a sweep is still eliminated](#the-one-place-a-sweep-is-still-eliminated).

**State carried across a trial. Not mitigated; every `@Setup` in the harness is `Level.Trial`.**
There is no per-iteration or per-invocation setup anywhere. Three consequences worth naming.
`RegionFileBenchmark.writeRaw` and `ChunkSaveStageBenchmark.transfer` write the same chunk coordinate
for the whole trial, which the source argues cannot grow the file without bound because the allocator
frees the old sectors after reserving the new ones. `ChunkSaveComparisonBenchmark` writes through
both loaders for the whole trial, and both keep internal caches that are warm after the first
invocations — this one is not discussed anywhere. `AreaVsPerChunkBenchmark` re-lights the same chunks
for the whole trial, which its javadoc now states — the risk that used to be argued away with a
per-iteration rebuild is avoided by a different mechanism anyway, because JMH runs each `@Benchmark`
method as its own trial with a fresh state instance and `area` therefore never sees `perChunk`'s
output. What re-lighting already lit chunks does to the number is untested.
`IncrementalVsFullBenchmark` toggles its block between two states so the world alternates rather than
drifting, which is the right mitigation and is stated in the source.

**One machine, and it was not idle. Disclosed, not mitigated.** A 16-core machine is the only
hardware fact recorded anywhere. No CPU model, no frequency governor, no memory configuration, no
file system, no kernel. [Measured results](../reference/measured-results.md#measured) records that the machine was
**not idle** during the runs, which is preserved here rather than softened: background load is a live
candidate explanation for the wide error bars on the contention rows.

**One JVM, and it is not recorded.** The toolchain is Java 25 with `options.release = 25`, but the
JVM that *ran* the published benchmarks — vendor, exact build, GC, flags beyond the class-level
`-Xms`/`-Xmx` — is written down nowhere. Two classes, `AreaVsPerChunkBenchmark` and
`IncrementalVsFullBenchmark`, set no heap flags at all, and they are the two with the largest live
set and the most GC sensitivity.

**The Minestom side is not pinned in this repository.** `settings.gradle.kts` declares
`net.minestom:minestom` with `withoutVersion()`; the version is resolved through `mycelium-bom`
`1.7.2`. A republication of that BOM changes the Minestom half of every comparison without a single
commit in Falco. The version the published numbers were measured against is `2026.06.20-26.1.2`,
recorded in [Contributing](../contributing/README.md#environment).

**Synthetic payloads, not real worlds. Partly mitigated, and the mitigations are the interesting
part.** `SectionStates.distinct` writes contiguous runs rather than random scatter, because a random
fill produces a palette access pattern no world shows; `SectionStates.lit` spreads sources evenly
rather than clustering them, because clustered sources do far less work. `ChunkPayloads` builds its
payloads through the real save path, so they compress like chunk data rather than like noise. The
residual threat is unavoidable and stated once: no benchmark here reads a real world, and the mapping
from `distinctStates` 1, 8, 64, 200 to air, underground, surface and heavily built sections is a
plausible assertion, not a survey.

**`resolveCost` is an uncalibrated proxy. Not mitigated.** `FakeBlockLightSource` burns
`Blackhole.consumeCPU(resolveCost)` tokens per resolution with `resolveCost ∈ {0, 50}`. The reasoning
for having it is sound — measuring a cache against a free fake makes the cache look like pure
overhead, which is the opposite of what a registry does — but nothing establishes that 50 tokens is
what a Minestom registry lookup costs. Any claim resting on `resolveCost = 50` is conditional on that
number. This does **not** touch the light comparison tables above, which use the real
`MinestomBlockLightSource` on both sides.

**Derived rows carry the errors of both inputs, and two published ones carry none.** The
`codec − codecWithoutCompression` row above, and the 53.9 µs figure for Minestom's search in
[Measured results](../reference/measured-results.md#measured), which is `minestomFull − minestomQueue` because
Minestom's search consumes the queue it is handed and cannot be measured on a prepared one. Both
techniques are legitimate; neither difference has a published uncertainty.

**488 configurations, each with its own interval.** At that count a handful of intervals will exclude
the true mean by chance alone. Not a serious problem here — the published claims are few and the
effects large — but a reader who wants to count is entitled to the point.

**Allocation figures are the exception to all of the above.** The figures quoted elsewhere from
`-prof gc` — the opacity table going from 74 040 to 8 664 bytes per call — are essentially
deterministic. They are immune to fork count, to machine load and to JIT variance, which makes them
the most robust quantitative evidence the project has. Where an allocation claim and a timing claim
support the same conclusion, the allocation is the one to lead with.


## Why no Minestom, and the benchmarks where the answer is different

The three kinds are introduced [at the top of this page](#three-kinds-of-benchmark-and-they-are-not-interchangeable);
this is what each one actually does.

The **library benchmarks** — the large majority — start no server at all. They measure what this
code contributes, with the registry replaced by a fake.

The **decision benchmarks** start one anyway: `AreaVsPerChunkBenchmark` and
`IncrementalVsFullBenchmark` compare two Falco paths against each other, so no foreign implementation
is involved, but what they measure is defined over chunks of a real instance and there is no fake to
put in its place. Both sides of each pay the same registry, so the comparison is unaffected; the
absolute microseconds are not comparable with the library benchmarks.

The **comparison benchmarks** measure this implementation against the one Minestom ships with, and
there the point *is* to run the original rather than a stand-in. Three of the four need a server for
it:

| Benchmark | Server | Why |
| --- | --- | --- |
| `RegionFileComparisonBenchmark` | no | Minestom's `RegionFile` reads no registry, so it runs in a bare fork. The class sits in `net.minestom.server.instance.anvil` because that type is package-private. |
| `ChunkSaveComparisonBenchmark` | `MinecraftServer.init()` | Minestom's `AnvilLoader` reads the biome registry and the block state count in **static** fields, so the class initialiser fails before any measurement unless the registries exist. |
| `LightEngineComparisonBenchmark` | `MinecraftServer.init()` | Measures the original light engine, whose methods are package-private in `net.minestom.server.instance.light`. |
| `LightEngineStageBenchmark` | `MinecraftServer.init()` | Same package and the same reason: it splits both engines into their stages, so it calls the same package-private methods. |

The comparison numbers therefore include what a real registry costs on both sides — which is
correct, because both sides pay it. They are not comparable with the library benchmarks below, which
deliberately exclude it.

The rest of this section applies to the library benchmarks.

Both packages already separate their algorithm from the registries of a running server —
`PaletteEntryResolver` for the codec and `BlockLightSource` for the light engine. The benchmarks
plug fakes into those two interfaces
([`FakePaletteEntryResolver`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/support/FakePaletteEntryResolver.java),
[`FakeBlockLightSource`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/support/FakeBlockLightSource.java)).

This is a deliberate trade. A registry lookup is expensive enough to dominate every one of these
measurements, and a benchmark whose number is 90 % Minestom tells you nothing about the code in
this repository. The consequence is that **no benchmark here reports what a chunk load costs on a
real server.** They report what *this* library contributes to it.

`FakeBlockLightSource` takes a `resolveCost` in [`Blackhole.consumeCPU`](https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/infra/Blackhole.html)
tokens for exactly this reason. `SectionOpacity` exists to resolve each distinct block state once
instead of once per visit, and how much that is worth depends entirely on what a resolution costs.
Measuring it against a free fake would make the cache look like pure overhead, which is the opposite
of what happens against a registry. What a token is worth against a real registry has never been
calibrated, which is why `resolveCost` appears in the threats above.


## Benchmark hygiene

The rules every benchmark in this source set follows. Where a rule has an exception, the exception is
named here rather than left for a reader to find.

- **Inputs are built in `@Setup`, never in the measured method.** Otherwise the generator is what
  gets measured. Every input is built from a seeded `Random` into an instance field, so no measured
  method has a compile-time-constant input.
- **The work cannot be deleted by the JIT.** Of the 50 measured methods, 40 return their result,
  which JMH consumes; `LightNibblesBenchmark.getUniform`, `.getAllocated` and
  `ScalingBenchmark.packingByDistinctStates` consume every value through a `Blackhole`; and the
  remaining seven — `RegionFileBenchmark.writeRaw`, `ChunkSaveStageBenchmark.transfer` and `.full`,
  `RegionFileComparisonBenchmark.falcoWrite` and `.minestomWrite`,
  `ChunkSaveComparisonBenchmark.falcoSave` and `.minestomSave` — perform file I/O, which is a side
  effect no compiler may remove. One case survives all of this and is documented below.
- **`@Fork`, `@Warmup` and `@Measurement` are explicit on every class**, so a reader can see how much
  evidence a number rests on without consulting the JMH manual. They are **not uniform**: seven
  classes fork twice and nine fork once, warmup is 3 or 5 iterations, measurement is 3 or 5, and the
  iteration time is 1 s except in `ChunkSaveComparisonBenchmark`, which uses 2 s. Copy the values
  from the class you are documenting. Several published tables were additionally produced with
  command-line overrides, which is why each provenance line above states the settings of the run
  rather than of the annotation.
- **`@BenchmarkMode` and `@OutputTimeUnit` are set per class.** Every class is `AverageTime`
  in microseconds, because every operation is a whole-section or whole-chunk unit of work and
  throughput would be the less natural framing. Nothing here reports a percentile.
- **`@State(Scope.Thread)`, except where a thread count is the parameter.**
  `RegionFileComparisonBenchmark` and `ChunkSaveComparisonBenchmark` are `@State(Scope.Benchmark)`
  with a nested `@State(Scope.Thread)` slot, because those two are run at several thread counts and
  the threads must share one region file while writing to different chunks. Everywhere else the
  measured code is single-thread confined — `LightPropagator` and `ChunkLightPropagator` are
  documented as such — and the state classes hold mutable buffers.
- **Sweeps, not single accesses.** `LightNibblesBenchmark` walks all 4096 blocks per invocation
  instead of reading one nibble. A single nibble read is a handful of instructions, which is below
  what a harness can separate from its own overhead. There is no `@OperationsPerInvocation` anywhere,
  so divide by 4096 yourself for the per-access cost. The caveat on that division is
  [The one place a sweep is still eliminated](#the-one-place-a-sweep-is-still-eliminated): two of
  that class's methods report lower bounds rather than per-access costs, because the JIT removes the
  loop entirely.
- **Deterministic inputs, from two seeds.** The library benchmarks seed from
  `BenchmarkConstants.SEED`; the comparison and decision classes use their own `SEED = 20260731`.
  Inputs are byte-identical between two runs of the same benchmark, which is what makes a difference
  between them describe the change and not the input — but the two groups do not share a world, so
  no input is shared across the boundary either.

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



## Sources

- The `±` figure, the fork counts and the limits of this evidence base, stated once and in full:
  [What a measurement here means](what-a-measurement-here-means.md).
- The unabridged tables, including every row that did not reach this page:
  [Measured results](../reference/measured-results.md#measured).
- What the loader and the light engine actually do with the results:
  [How the Anvil loader is built](how-the-anvil-loader-is-built.md) and [How the light engine works](how-the-light-engine-works.md).
- The benchmark sources are canonical for every condition on this page —
  [`falco-benchmarks/src/jmh/java`](https://github.com/OneLiteFeatherNET/Falco/tree/main/falco-benchmarks/src/jmh/java),
  wired up by [`falco-benchmarks/build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/build.gradle.kts)
  and [`settings.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/settings.gradle.kts).
  Where this page and an annotation disagree, the annotation is right and this page is a defect.
</content>
</invoke>

Related: [Explanation What a measurement here means](what-a-measurement-here-means.md) for what the `±` covers ·
[Reference Benchmark catalogue](../reference/benchmark-catalogue.md) · [How-to Reproduce a published measurement](../how-to/reproduce-a-published-measurement.md)
