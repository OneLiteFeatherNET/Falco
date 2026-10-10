# Measurement

Falco claims factors. The light engine is said to be between 1.11× and 1.71× faster than the one
Minestom ships with, the region file to degrade gently under concurrent readers where the built-in
one does not, and compression at level 2 to be faster than at level 6 by something between nothing
at all and 2.96×, depending on how many distinct block states a chunk holds. This page answers the
only question that matters about such numbers: **why should someone who did not run them believe
them?**
It defines what a JMH figure in this repository is, what the `±` after it does and does not cover,
which parameter values were chosen and why, and — at greater length than the flattering parts —
where the evidence is thin. It does not list the numbers themselves and it is not a set of
instructions.

This is one of three pages and they do not overlap. **This page is *why and how a number was
obtained*.** [What the benchmarks establish](what-the-benchmarks-establish.md) is *what exists and how to run it* — the catalogue, the
command lines, the headline tables. [Measured results](../reference/measured-results.md) under *Measured* is *every
raw number*, including the ones that did not make a headline. Where a definition is needed in more
than one place it is stated here once and linked from there.

The short version: the ratios in this repository are worth trusting where the intervals are disjoint
and a second run agrees, the absolute microseconds are not worth trusting anywhere, two rows of the
loader table must never be quoted as factors at all and a third did not survive its repeat, and a
reader who wants to check any of it can do so in about ten minutes per benchmark.

## Contents

- [What a JMH number here is](#what-a-jmh-number-here-is)
- [Where the harness lives, and why it is not in the
  library](#where-the-harness-lives-and-why-it-is-not-in-the-library)
- [Three kinds of benchmark, and why they are not comparable with each
  other](#three-kinds-of-benchmark-and-why-they-are-not-comparable-with-each-other)
- [The parameters, and why these values](#the-parameters-and-why-these-values)
- [What keeps a number honest](#what-keeps-a-number-honest)
- [Where the harness still loses](#where-the-harness-still-loses)
- [The limits, stated without softening](#the-limits-stated-without-softening) — one bolded lead-in
  per limit
- [Reproducing this](#reproducing-this)
- [What this all adds up to](#what-this-all-adds-up-to)
- [Sources](#sources)

## What a JMH number here is

Every benchmark class carries `@BenchmarkMode(Mode.AverageTime)` and
`@OutputTimeUnit(TimeUnit.MICROSECONDS)`. There is no exception and no class inherits a default. A
published figure is therefore **the arithmetic mean time of one invocation of one measured method, in
microseconds**, and nothing else.

`AverageTime` rather than `Throughput` because the unit of work here is a section or a chunk, not an
event: `LightPropagatorBenchmark` lights one section, `ChunkSaveStageBenchmark` saves one chunk. A
mean time per section is the quantity a tick budget is spent in, and the ratio of two mean times is
the quantity a comparison is about. `Throughput` would report the reciprocal and would have to be
inverted before any of it could be read.

The cost of that choice is that a mean says nothing about a tail. A server's felt performance is
dominated by its worst ticks, and this harness reports no percentile anywhere. `Mode.SampleTime`
would produce them; it is not used. For the contention claim in particular, the tail is the
interesting quantity and the mean is standing in for it. There is also no `@OperationsPerInvocation`
in the suite, so the sweeps that walk 4 096 blocks report the time of the whole sweep and the
division is left to the reader, with the caveat below about the two methods for which it is invalid.

### The interval after a number

> The figure after the `±` in JMH output is the **half-width of the 99.9 % confidence interval of
> the mean**, computed with a t-distribution over the measurement iterations of the run. It is not a
> standard deviation and not a min/max spread. At `@Fork(1)` it describes dispersion **inside one
> JVM**: run-to-run variance between JVM launches — a different JIT compilation plan, different GC
> ergonomics, different code layout, a different address-space randomisation — is not represented in
> it at all. The published interval is therefore narrower than the true uncertainty, on precisely
> the tables whose conclusions matter most.

This is not an interpretation. `Result#getScoreError` returns `statistics.getMeanErrorAt(0.999)`, and
`AbstractStatistics#getMeanErrorAt` computes `t(1 − (1 − confidence)/2, n − 1) × s / √n` [1], where
`n` is the number of measurement iterations pooled across all forks and `s` their sample standard
deviation. The same quantity is the `scoreError` field of `results.json`.

The definition lives here and nowhere else. Every table in [What the benchmarks establish](what-the-benchmarks-establish.md) and
[Measured results](../reference/measured-results.md) points back at this section rather than restating it.

**What it bounds:** the sampling uncertainty of the mean of the iterations that were measured, on the
assumption that those iterations are independent draws from one stationary distribution.

**What it does not bound:** run-to-run variance between JVM launches, which at one fork is not
sampled even once; systematic bias, because warmup that was too short or a state that drifted moves
the mean without widening the interval; and machine state, because a steady background load produces
a tight interval around an inflated number. A narrow `±` on a biased measurement is a narrow interval
around the wrong value.

### Reading a row: three tests anyone can apply

The multiplier `t(0.9995, n − 1)` is generous at the sample sizes used here, and knowing how generous
is what makes a row checkable rather than a matter of trust.

| Measurement iterations | Where | df | `t(0.9995, df)` |
| ---: | --- | ---: | ---: |
| 3 | `ScalingBenchmark`, the only class with three | 2 | 31.6 |
| 5 | the annotated default of every class that produced a published table | 4 | 8.61 |
| 10 | the `-i 10` runs of the light comparison and `IncrementalVsFullBenchmark` | 9 | 4.78 |

Given two reported values `a ± Δa` and `b ± Δb` with `a < b`, three tests decide what may be written,
and they are applied in this order to every comparison on every page of this documentation.

**Is the row usable at all?** If either half-width is at least half its own mean, no factor may be
quoted, not hedged and not in parentheses. At `Δb/b ≥ 0.5` the lower end of that interval is within a
factor of two of zero, and at `Δb/b ≥ 1` it is negative: the measurement does not constrain the value.
Such a row still says something — that the quantity stopped being predictable — and that is what it
gets written up as.

**Do the intervals touch?** If `a + Δa < b − Δb` they are disjoint and the difference is supported for
that run; it may be stated in plain words. If they overlap, the row is indicative only and the words
*measurably*, *is faster* and a bare `×` factor are not available for it. An overlap is equally not a
demonstration of equality — "no difference is resolvable at this precision" is the honest form, and it
protects Falco on the rows where Falco loses exactly as much as it constrains Falco on the rows where
it wins.

**How many digits does the ratio deserve?** Never more than the wider of the two relative half-widths
supports, counted in decimal places: under 1 % three decimals, 1 % to 10 % two, 10 % to 50 % one
decimal with the wider relative error named in the same sentence, and above 50 % no factor at all.
So `1.71×` is admissible on a pair whose wider relative half-width is 4 %, and `1.9×` — one decimal,
not one significant figure — is what a 20 % half-width leaves of a 1.86 point estimate. For a
disjoint row the conservative bounds `(b − Δb)/(a + Δa)` and `(b + Δb)/(a − Δa)` say more than a
point estimate and are what this documentation prefers.

Because the multiplier is so generous at five iterations, **two non-overlapping 99.9 % intervals are a
strong result.** Where Falco's interval is disjoint from Minestom's the finding is well supported and
is stated plainly. Hedging such a row is as much a documentation defect as printing a factor on a row
that does not carry one.

## Where the harness lives, and why it is not in the library

The benchmarks are a separate Gradle module, `falco-benchmarks`, with a separate source set,
`src/jmh/java`, created by the `me.champeau.jmh` plugin at version `0.7.3` [2] over JMH `1.37` [3].
The module never applies `maven-publish`; the root `build.gradle.kts` enumerates `falco-anvil`,
`falco-light`, `falco-instance` and `falco-bom` explicitly for publishing, so a `./gradlew publish` at
the root passes over the benchmarks without needing a task exclusion.

Three consequences follow, and all three were the point.

JMH itself never reaches the classpath of a published artefact. `jmh-core` is a dependency of the
`jmh` source set, and a benchmark that lived in `src/test` of `falco-light` would put JMH, `fastutil`
and a full Minestom on the test classpath of a library whose main source set deliberately declares
Minestom as `compileOnly`. It also never reaches a consumer's POM.

The benchmarks can depend on things the library must not. `falco-benchmarks` imports the Adventure
BOM directly, because the library gets Adventure through `compileOnly(minestom)` and therefore has it
at compile time only, while a benchmark runs the code for real. Minestom and `fastutil` are on the
benchmark classpath for the same reason, and the library must not have either.

And one module can measure both libraries at once. `ScalingBenchmark` times the Anvil codec and the
light propagator in the same run, and `BenchmarkConstants` and `SectionStates` are used from both
sides. A `jmh` source set inside each library module would have forced either duplication of those
two support classes or a dependency edge between `falco-anvil` and `falco-light` that does not exist
in production.

### Compiled by every build, executed by none

`falco-benchmarks/build.gradle.kts` wires `compileJmhJava` into `check`, and nothing wires `jmh` into
anything. The asymmetry is deliberate on both ends.

Compilation is part of the build because a benchmark is source code that references the internals of
the library — `PaletteData.encode`, `SectionOpacity.of`, `RegionFile.readRaw` — and a refactoring
that breaks it should fail like any other consumer. A benchmark suite that silently stops compiling
is a benchmark suite nobody will run again.

Execution is not part of the build for two reasons that are worth separating. The first is time: a
full `./gradlew jmh` runs **488 benchmark configurations**, counted from the source at commit
`ca79507` as the sum over all sixteen benchmark classes of the fifty `@Benchmark` methods times the
cross product of their `@Param` values. `ScalingBenchmark` alone contributes 300 of them (4 methods ×
15 section counts × 5 distinct-state counts). At the forks and iterations the annotations ask for,
that is well over an hour. The second reason is the one that would still apply if it took a minute:
**JMH results from a shared CI runner cannot gate anything.** Run-to-run variance on a JVM is large
enough that JMH treats it as a first-class concern and dedicates a sample to it [4]; a build agent
shared with other jobs adds a second, larger source of variance on top. A threshold set loose enough
not to fail spuriously on such a machine would be loose enough to let a real regression through.

The consequence is stated rather than hidden: **there is no automated performance regression gate in
this repository.** Every number in [Measured results](../reference/measured-results.md),
[How the Anvil loader is built](how-the-anvil-loader-is-built.md) and [How the light engine works](how-the-light-engine-works.md) was produced by a human
running a benchmark by hand and pasting the result. A change that halved the speed of the light
engine would break no build. What does break the build is a change that makes the two light engines
disagree, which is a different property and is covered below.

## Three kinds of benchmark, and why they are not comparable with each other

The most consequential decision in this suite is that **most of the benchmarks do not start a
Minestom server, and six must.** Reading a number without knowing which kind it came from produces a
wrong conclusion, so the distinction is drawn here before anything else.

### The library benchmarks: Minestom removed on purpose

Ten of the seventeen classes are library benchmarks, and eleven run in a bare JVM fork with no server
at all, both counted at `a09c71f`. Both libraries were designed so that this is possible: the Anvil
codec reaches the registry only through `PaletteEntryResolver`, and the light engine only through
`BlockLightSource`. The benchmarks plug `FakePaletteEntryResolver` and `FakeBlockLightSource` into
those two seams, and neither fake touches Minestom at all.

**That design holds at the section level and not above it.** `ChunkLightArea#compute` takes an
`Instance`, so there is no seam to substitute: every benchmark of the instance-level area pass starts
a server, and three of the six server-starting classes are now Falco-against-Falco light benchmarks
rather than comparisons against Minestom. The claim is therefore about the codec and the propagator,
which is where it was made, and not about everything built on them since.

This is a trade, not a free win. A registry lookup is expensive enough to dominate every one of these
measurements, and a benchmark whose result is 90 % Minestom says nothing about the code in this
repository. The cost is that **no library benchmark here reports what a chunk load costs on a real
server.** They report what this library contributes to one. That distinction is repeated in
[What the benchmarks establish](what-the-benchmarks-establish.md) and it is repeated here because it is the single easiest way to misread
the suite.

The fake is not free, though, and that detail is where the design gets interesting.
`FakeBlockLightSource` takes a `resolveCost` and burns it through `Blackhole.consumeCPU` [5][6] on
every `emission` and every `blocksFace` call. The reason is that `SectionOpacity` exists to resolve
each distinct block state once instead of once per visit, and **the value of a cache is a function of
what a miss costs.** Measured against a fake that answers in a nanosecond, the opacity table looks
like pure overhead — the hash lookup and two array writes per block, with nothing saved. That is the
opposite of what happens against a real registry. `SectionOpacityBenchmark` therefore takes
`resolveCost` as a parameter with values `0` and `50`, and the two columns answer two different
questions: at `0` you are measuring the table's own cost, at `50` you are measuring what it saves.
Reporting only one of them would be a choice about the conclusion rather than about the measurement.

What nothing in this repository establishes is that fifty tokens is what a Minestom registry lookup
costs. A token is a few cycles and the cost is close to linear in the count [6], so `resolveCost = 50`
is a few hundred cycles of plausible-looking work, not a calibrated stand-in. **Any statement of the
form "the opacity table saves X" that rests on `resolveCost = 50` is conditional on a calibration
nobody has performed.** Measuring `MinestomBlockLightSource.emission` once and stating how many tokens
it corresponds to would settle it in a single run. This does not touch
`LightEngineComparisonBenchmark`, which uses the real `MinestomBlockLightSource` on both sides — the
strongest light numbers in the repository are free of this threat.

### The comparison benchmarks: the original, not a reimplementation

Four classes measure Falco against the code Minestom actually ships, and there the point is to run
the original rather than a stand-in. `ChunkSaveComparisonBenchmark`, `LightEngineComparisonBenchmark`
and `LightEngineStageBenchmark` therefore start a real server and pay the registry cost — but both
sides pay it, which is what makes the ratio meaningful. Their absolute numbers are **not** comparable
with the library benchmarks above; compare each only against its own counterpart in the same table.
`RegionFileComparisonBenchmark` is the fourth, and it needs no server, for the reason given below.

Three of them live in `net.minestom.server.instance.*` packages, and the reason is not stylistic:

| Class | Package | Measured member | Why the package |
| --- | --- | --- | --- |
| `RegionFileComparisonBenchmark` | `net.minestom.server.instance.anvil` | `RegionFile#readChunkData`, `#writeChunkData` | `net.minestom.server.instance.anvil.RegionFile` is package-private |
| `LightEngineComparisonBenchmark` | `net.minestom.server.instance.light` | `BlockLight.buildInternalQueue`, `LightCompute.compute` | both methods are package-private |
| `LightEngineStageBenchmark` | `net.minestom.server.instance.light` | the same two methods, split into stages | the same reason |

Placing a class in a foreign project's package is a thing one should have to justify, so: the
alternative is to copy the measured code into our own package and measure the copy. A copy is a
different compilation unit, it will drift from the original at the next Minestom release without
anybody noticing, and it removes the one property that makes a comparison worth publishing — that the
thing on the other side of the table is the thing a user actually runs. Package placement is the only
mechanism the language offers for reaching a package-private member without reflection, and
reflection would have added its own overhead into the measurement. This is a statement about the
Minestom sources as of `2026.06.20-26.1.2` [7], established by reading them, not by measuring.

`ChunkSaveComparisonBenchmark` is the exception that proves the rule: `AnvilLoader` is public, so it
stays in `net.onelitefeather.falco.benchmark.anvil`. What it cannot avoid is starting a server.
`AnvilLoader` reads the biome registry and the block state count in **static** fields, so merely
touching the class from a bare fork fails in the class initialiser before a single measurement
begins. Its `@Setup` therefore calls `MinecraftServer.init()`, as do both light comparison classes.
Minestom's `RegionFile` reads no registry, which is why the region-file comparison is the one
comparison that runs in a bare fork.

### The decision benchmarks: Falco against Falco

Three classes measure Falco against Falco and exist to kill a design if the number does not hold. Two
of them weigh one path against an alternative: `AreaVsPerChunkBenchmark` weighs one area pass over
*n* chunks against *n* `calculateWithNeighbours` calls, and `IncrementalVsFullBenchmark` weighs
replaying one changed block against relighting the 3×3 from block states. The third,
`AreaPassStageBenchmark`, is shaped differently and is worth naming as such: its second method is a
*step inside* the first rather than an alternative to it, and the ratio between them bounds what a
perfectly effective opacity-table cache could remove. It sits in this family because nothing foreign
is involved, not because it fits the shape.

No foreign implementation is involved in any of the three, but what they measure is defined over
chunks of a real instance and there is no fake to put in its place, so all three call
`MinecraftServer.init()`. Both sides of each pay the same registry, so the comparisons are
unaffected; their absolute microseconds belong to a third family and are comparable with neither of
the other two. (`net/onelitefeather/falco/benchmark/light/package-info.java` still says that no class
in that package starts a server. That is stale in the source; these three do.)

Two of them — `AreaVsPerChunkBenchmark` and `IncrementalVsFullBenchmark` — are also the only classes
in the suite with **no heap flags at all**, `@Fork(1)` with no `jvmArgsAppend`, where every other
class pins `-Xms` equal to `-Xmx`. They build a 5×5 or 4×4 world of real Minestom chunks, and that
was once the largest live set in the harness. It is not any more: `AreaPassStageBenchmark` loads a
4×4 field *plus its ring*, so 36 real chunks, and it pins `-Xms2g -Xmx2g`. Their numbers still rest
on a default, unrecorded heap configuration, and the precedent for closing that gap now sits in the
same package.

## The parameters, and why these values

Every `@Param` in this suite exists because the answer moves along that axis. That sounds obvious and
is not: a parameter whose values all produce the same number multiplies the runtime of a suite by its
cardinality and buys nothing, and a missing parameter turns one point on a curve into a claim about
the whole curve.

**`distinctStates` — 1, 8, 64, 200, and separately 1, 16, 64, 256, 1024.** This is the size of a
section's palette, and it is the axis on which the two save paths differ *structurally*, not by a
constant. Minestom's `AnvilLoader` deduplicates a palette entry with a linear search over an
`IntArrayList`, so its cost grows with the product of block count and palette size; Falco
deduplicates through a hash map, so its cost grows with the block count alone. A single measurement
point cannot distinguish those two shapes — a series over the palette size can, which is why
`ChunkSaveComparisonBenchmark` runs five values up to 1024 rather than the two the other Anvil
benchmarks use. The value `1` is the control: a uniform section that both sides recognise and skip.
The value `200` in the smaller set is chosen because it is the first common value that forces eight
bits per entry, and `8` because it is a plausible built-up section. That these levels correspond to
air, underground, surface vegetation and heavily built sections is an argument, not a survey of real
worlds, and it is not evidence for anything by itself.

**Light sources — 0, 1, 8, 64.** `0` is not padding. It is the case in which the search does not run
at all, and it is the state of the overwhelming majority of the sections of a real world. A light
benchmark that only measures lit sections reports the worst case and calls it typical. `64` is where
the searches of separate sources begin to overlap heavily, which is a different regime from `1`.

**Solid fraction — 0 % and 25 %, or 0 % and 30 %.** Solid blocks stop a search early, so an entirely
open section is the upper bound of the work, not the normal case. Both engines get faster with
occlusion; the *margin between them* moves too, and in Falco's favour — from 1.11× at 0 % solid to
1.58× at 30 % with one source [8]. Measuring only open sections would have understated the result;
measuring only occluded ones would have overstated it. Both are in the table. `occlusionPercent` is a
benchmark parameter and not a claim about how much of a real world is solid.

Note that the value differs between the two families: the library benchmark `LightPropagatorBenchmark`
uses `0` and `25`, the two Minestom-package comparisons use `0` and `30`. This is one of several
reasons the two families' numbers must not be laid next to each other.

**`emissionMix` — UNIFORM and MIXED.** This one was added late, in `0e8fbb5`, and it is the clearest
example of a parameter that exists because a decision depended on it. Falco's breadth-first search
assumes its queued positions arrive in order of level, which holds only while every source starts at
the same level. A bucket queue (Dial's algorithm) would remove that assumption at the price of a
bucket per level, and the research put it at 5–7 % *slower* with equal brightness and 32–36 % faster
with mixed brightness. With only `UNIFORM` in the suite, adding a bucket queue would have shown up
purely as a regression. `MIXED` cycles the sources through glowstone, lantern, torch, redstone torch
and magma block — levels 15, 15, 14, 7 and 3 from the block registry — at the same positions drawn
from the same seed, so the levels are the only difference between the two runs. The set is chosen
deliberately: it spans almost the full range so the search meets the worst spread a builder can
produce without exotic blocks, and it contains two pairs that are equal or adjacent (15/15, 15/14),
because a set of only distant levels would overstate the effect.

One cell of that cross product measures nothing, and the source says so: glowstone comes first in the
cycle, so with a single source `MIXED` places glowstone as well and builds a byte-for-byte identical
section. The recommended invocations in [What the benchmarks establish](what-the-benchmarks-establish.md) skip it, which turns twelve
scenarios per method into ten.

**Thread count.** This is not a `@Param` at all, and there is no `@Threads` annotation anywhere in the
harness. JMH takes one `-t` per run, so the four-row loader table in
[Measured results](../reference/measured-results.md) is **four separate measurement sessions**, not one. That has a
consequence stated plainly below. It also means a plain `./gradlew jmh` measures every comparison at
one thread and therefore never exercises the loader's central claim, which is about lock
granularity — `RegionFileComparisonBenchmark`'s javadoc says outright that a single thread cannot
show a difference that only exists between threads.

**`sectionCount` — fifteen values from 1 to 256.** `ScalingBenchmark` is the only benchmark here that
measures the library against itself along a curve rather than at a point, and its parameter set is
deliberately fine-grained where the others are coarse. The purpose is to find where a curve stops
being straight, and three well-spaced points cannot do that: they will fit a line through anything.
The payoff is concrete. A least-squares fit over the vanilla range (≤ 24 sections) predicts 21 423 µs
of sky light at 256 sections; the measured value is 26 597 µs, so the forecast falls about a fifth
short, while the same method lands within about 2 % for block light. That non-linearity is only known
because the exotic sizes were measured rather than extrapolated from the common ones, and it is what
made incremental heightmap seeding a real open item rather than a micro-optimisation. The shortfall
is a derived quantity: the residuals of the fit are not published, neither endpoint carries an error
bar, and `ScalingBenchmark` is the one class in the suite that measures **three** iterations rather
than five, which is the widest multiplier in the harness. The direction is the durable part of that
claim, and the mechanism behind it — seeding a queue at every open cell — is independent of the fit.

**`bitsPerEntry` — 4, 5, 8, 15.** The packing loop's iteration count per `long` is a function of this
and of nothing else, so it is the only axis `BitPackerBenchmark` needs.

**`compression` — ZLIB, GZIP, NONE.** With `NONE` present, the compression stage can be subtracted
from the codec stage rather than estimated.

## What keeps a number honest

The suite follows a set of rules; the ones that are load-bearing are these.

**Inputs are built in `@Setup`, never in a measured method,** and the result of a measured method is
never simply dropped. Both are the standard JMH failure modes — dead-code elimination and constant
folding — and JMH's own samples exist to demonstrate exactly them [9][10]. Concretely, dead-code
elimination in this harness would look like a measured method computing a value nothing reads: the
JIT proves the computation has no effect and deletes it, and the benchmark reports the cost of an
empty method. Three shapes prevent it here. Forty of the fifty measured methods return their result,
which JMH consumes. Three take a `Blackhole` and consume every value they produce —
`LightNibblesBenchmark.getUniform` and `.getAllocated`, and `ScalingBenchmark.packingByDistinctStates`.
The remaining seven are `void` and rely on a side effect that cannot be eliminated, all of them file
I/O: `RegionFileBenchmark.writeRaw`, `ChunkSaveStageBenchmark.transfer` and `.full`,
`RegionFileComparisonBenchmark.falcoWrite` and `.minestomWrite`, and
`ChunkSaveComparisonBenchmark.falcoSave` and `.minestomSave`. The three counts close at 50, and
`grep -c '@Benchmark$'` over `falco-benchmarks/src/jmh/java` is what they are counted from.
Constant folding is closed off by
construction: every input is built in `@Setup` from a seeded `Random` and stored in an instance field,
so no measured method has a compile-time-constant argument, and `BitPackerBenchmark` reads
`this.bitsPerEntry`, a `@Param` field, which JMH deliberately keeps non-constant. There is no
`@CompilerControl` anywhere in the harness, which is not needed for the shapes measured here but is
worth knowing: nothing forces inlining decisions to be the same on the Falco and the Minestom side of
a comparison.

**Inputs are deterministic.** The library generators — `SectionStates`, `ChunkColumn` and everything
built on them, `RegionFileComparisonBenchmark` included — seed from `BenchmarkConstants.SEED`
(`0x5DEECE66D`). Five classes seed from their own constant `20260731` instead:
`ChunkSaveComparisonBenchmark`, `LightEngineComparisonBenchmark`, `LightEngineStageBenchmark`,
`AreaVsPerChunkBenchmark` and `IncrementalVsFullBenchmark`. Two runs of the same benchmark must see
byte-identical input or the difference between them describes the input rather than the change. The
split into two seed constants is untidy but harmless, since no benchmark compares across the two
groups — and it is worth stating precisely, because "every generator seeds from `BenchmarkConstants
.SEED`" is a sentence a reader can falsify by opening one file.

**Generated sections are shaped like a world, not like noise.** `SectionStates.distinct` writes
states in contiguous runs rather than scattering them, because a real section holds runs of the same
block and a purely random fill produces a palette access pattern no world ever shows.
`SectionStates.lit` spreads light sources by a fixed stride rather than placing them randomly, and
the comment says why: a random placement can cluster the sources, and a cluster performs far less
work than the same count spread out, because the searches overlap immediately. `ChunkPayloads` builds
its payloads through the real save path, so they compress like chunk data rather than like
incompressible noise. Note that the two Minestom-package light comparisons do the opposite of
`SectionStates.lit` — they place sources at `random.nextInt` positions — so `lightSources = 8` does
not denote the same section in `LightPropagatorBenchmark` and in `LightEngineComparisonBenchmark`.
This is the second reason those two families' absolute numbers must not be compared. No benchmark in
the suite reads a real world.

**The compression level is held equal where it is not the subject, and measured where it is.**
`RegionFileComparisonBenchmark` writes through the identical Adventure writer at the same level on
both sides, over byte-identical stored payloads, so nothing it reports is a compression level in
disguise. `ChunkSaveComparisonBenchmark` does the reverse on purpose: it measures the two loaders as
they ship, including Minestom's level 6 against Falco's `ChunkCompression.DEFAULT_LEVEL`, which is 2,
because that difference is part of what a user of the loader gets — and it adds two separate
calibration methods, `compressFalcoLevel` and `compressMinestomLevel`, so the level can be subtracted
from the save numbers afterwards. Either choice alone would have been arguable. Declaring the
asymmetry and shipping the instrument that measures it is what makes the result readable.

**Both light engines must agree before either is timed.** `LightEngineComparisonBenchmark.setUp`
runs both paths over the section it just built, compares the 2048 bytes, and throws — aborting the
trial — if they differ, with a message naming the number of differing blocks, the largest difference
and the coordinates of the first. A faster number must never come from computing something else.
The same property is pinned in the ordinary test run by `LightEngineEquivalenceTest`, which compares
nine source counts against six occlusion levels — 54 scenarios — and additionally asserts that a
section holding sources did not come back dark, so that no scenario passes by comparing two empty
arrays. `./gradlew build` fails if the property is ever lost. This is a fact about the code rather
than a measurement, and it cannot be dismissed as noise.

That gate is recent, and its history is the most useful thing in this section. The byte identity over
54 scenarios was stated as an established fact in the status document and in the light-engine
document **for a long time while nothing in the build checked it.** It rested on an ad-hoc comparison
run once by hand; there was no test, and the benchmark did not verify it either, although the
documentation said it did. Two agents found this independently at their own ends of the code. The
claim turned out to be true, which is precisely not the point: a number cited throughout the
documentation was hanging on nothing. If you are evaluating this repository, that is the failure mode
to check for elsewhere in it, and it is why this document distinguishes measured claims from
read-the-source claims at every turn.

**The harness configuration is explicit on every class.** `@Fork`, `@Warmup` and `@Measurement` are
written out on all sixteen, rather than inherited from JMH's defaults, so a reader can see how much
evidence a number rests on without consulting the JMH manual. The values are **not** uniform and must
be copied from the class being documented rather than generalised: warmup is three or five
iterations, measurement is three or five, and iteration time is one second everywhere except
`ChunkSaveComparisonBenchmark`, which uses two. Fifteen of the seventeen classes at `a09c71f` pin
their heap
with `jvmArgsAppend = {"-Xms…", "-Xmx…"}`, which removes heap sizing and the resulting GC behaviour
as a free variable between runs; `AreaVsPerChunkBenchmark` and `IncrementalVsFullBenchmark` are the
two that do not.

Explicit annotations only help while the published run used them, and most of the published runs did
not. Of the seven tables in [Measured results](../reference/measured-results.md) that carry JMH figures, one matches
its annotation, two were deliberately run at more measurement iterations than the annotation asks
for and say so, one states an iteration time the annotation contradicts, and three state no
configuration at all. The ones that bear on a published claim are named below.

**State scope follows the resource under test.** Fourteen classes are `@State(Scope.Thread)`, because
the measured code is single-thread confined and the state objects hold mutable buffers.
`RegionFileComparisonBenchmark` and `ChunkSaveComparisonBenchmark` are `@State(Scope.Benchmark)` with
a nested `@State(Scope.Thread) ThreadSlot`, and the exception is the whole point of those two: the
shared region file is the contended resource, while each thread must work on a chunk of its own.
Without the slot, every thread would hammer one entry, which measures contention on a chunk rather
than contention on a file — and real parallel loading reads different chunks of the same region.
([What the benchmarks establish](what-the-benchmarks-establish.md) states `@State(Scope.Thread)` "everywhere"; that is accurate for the
library benchmarks and not for these two.)

**Sweeps rather than single accesses.** `LightNibblesBenchmark` walks all 4096 blocks per invocation
instead of reading one nibble, because a single nibble read is a handful of instructions — below what
a harness can separate from its own overhead, given that a `nanoTime` call itself costs 15–30 ns at
best and its granularity can be far coarser and varies wildly between platforms [11].

**Every `@Setup` in the harness is `Level.Trial`.** There is no `Level.Iteration` or
`Level.Invocation` setup anywhere and only three `@TearDown`s, all of them file cleanup. This is what
keeps generation out of the measurement, and it has a cost that belongs in the limits below rather
than here: state built once is state that can drift across a whole trial. What it does *not* cost is
contamination between methods — JMH runs each `@Benchmark` as its own trial with a fresh state
instance and a fresh `@Setup`, so `area` never sees what `perChunk` left behind, and no claim on any
of these pages depends on assuming otherwise.

## Where the harness still loses

Two of the `LightNibblesBenchmark` methods report times far below one nanosecond per access, and this
is stated in the documentation rather than quietly dropped. `getUniform` and `setUniformUnchanged`
operate on a section whose blocks all carry the same level, so every read is answered from a single
field regardless of coordinates. The loop body is loop-invariant, the JIT hoists it, and the empty
loop disappears — the textbook case JMH's loop and dead-code samples exist to warn about [9][12].
Adding a `Blackhole` does not fix it; this was verified by rerunning with
`-Djmh.blackhole.autoDetect=false`, which produces the same number.

Those two numbers are therefore **lower bounds, not per-access costs.** What they legitimately say is
that the uniform path can collapse to a single field read, which is exactly the property the shortcut
exists for. Only `getAllocated` and `setAllocating` divide meaningfully by 4096. Both eliminated
methods are kept anyway, because a change that accidentally made the uniform path allocate would show
up in them instantly.

This is the one place in the suite where a known elimination survives, it is named in the source, in
[What the benchmarks establish](what-the-benchmarks-establish.md) and here, and the affected numbers are labelled everywhere they appear.
That is the treatment every other soft spot on this page is measured against.

## The limits, stated without softening

This is the section a reader evaluating the repository should weigh most heavily.

**One machine, and it was not idle.** Every JMH figure in this repository comes from a single machine
— 16 cores, per the note on the region-file table — that
[Measured results](../reference/measured-results.md#measurement-environment) explicitly records as **not idle**
during the runs. Whether those 16 are physical cores or eight with SMT is not recorded, which
matters directly for the contention series. Ratios between two rows measured in the same session are
meaningful; absolute microseconds carry a wide error and are not portable to any other host. Core
count, frequency scaling, page cache state and the file system all move them.

The repository's own documents are not consistent about this. [How the light engine works](how-the-light-engine-works.md) introduces
its main comparison table with "on a quiet machine";
[Measured results](../reference/measured-results.md#measurement-environment), which records the measurement
environment, says the machine was not idle. **Take the conservative reading.** Nothing downstream
depends on which is true, because none of these numbers is quoted as an absolute anyway — but a
document that asserts both is a document to read carefully. A steady background load is also a live
candidate explanation for the widest error bars in the loader table, and it should be named as one
rather than left for a reader to raise.

**No machine record survives.** JMH writes the JDK build, the VM name and version, the JVM arguments
and the full parameter set into `results.json`. That file lands in `build/`, which is gitignored, and
no run's output is committed. The consequence is precise: these results can be **re-performed**, but
they cannot be **exactly reproduced**. What a third party would need and does not have is a specific
list: CPU model and stepping, whether SMT is on, the frequency governor, RAM, the storage device and
file system, the OS and kernel, and above all the JVM that ran the benchmarks — vendor, full version
string and garbage collector. The Java 25 toolchain is recorded, but the toolchain is not the JVM that
ran anything, and Temurin 25 is named only for the standalone rebuild described below, which is not
JMH. Neither the git commit nor the date of most runs is recorded either. This is a real gap and there
is no argument for it beyond nobody having done it.

One version is less pinned than it looks. `settings.gradle.kts` declares Minestom with
`withoutVersion()` and resolves it through `mycelium-bom` 1.7.2, so the `2026.06.20-26.1.2` recorded
in [Contributing](../contributing/README.md#environment) is what that BOM resolved to on the day, not a version
this repository fixes. A BOM republication changes the Minestom side of every comparison without a
single commit in Falco. JMH `1.37`, the plugin `0.7.3` and Adventure `5.1.1` are pinned explicitly.

**One fork on every benchmark that produces a headline number.** Seven of the seventeen classes at
`a09c71f` run at
`@Fork(2)`: `BitPackerBenchmark`, `PaletteDataBenchmark`, `ChunkCompressionBenchmark`,
`LightNibblesBenchmark`, `SectionOpacityBenchmark`, `LightPropagatorBenchmark` and
`ChunkLightPropagatorBenchmark`. The other nine run at `@Fork(1)` — and they include every comparison
benchmark, both decision benchmarks and `ScalingBenchmark`. **Every published table in this documentation comes
from a `@Fork(1)` class**, so the single-fork limitation applies to all of them in full, while the
seven classes that do sample a second JVM launch are exactly the seven that produced no published
table. That is backwards from where the risk is. Run-to-run variance between JVM launches is a
distinct source of error from iteration-to-iteration variance within one launch, arising from
profile-guided compilation decisions that differ per process, and a single fork cannot see it at all
[4]. The statistically defensible treatment of such data is to report a confidence interval across
independent runs rather than across iterations of one run [13] — this suite reports the latter. The
published `±` figures are therefore **narrower than the true uncertainty**, on precisely the tables
whose conclusions matter most.

**Four genuine repeats exist, and they are the answer to the objection above.** The register of them
is [Measured results](../reference/measured-results.md), which collects the measured tables.
`IncrementalVsFullBenchmark` was run independently twice, and the incremental path came out cheaper
by 1.96× / 6.40× / 3.95× on the earlier run against 2.07× / 5.60× / ≈ 3.7× on the published one.
`AreaVsPerChunkBenchmark` at nine chunks put the area pass 5.37× cheaper and, on an independent
re-run, 5.47× cheaper, on overlapping ratio intervals. The whole four-row loader table was run a
second time at the same settings [14], and there the two runs part company: all four of Falco's
intervals overlap between them, while Minestom's mean at two threads moves from 2 200 to 103 437 and
its interval widens until it constrains nothing. And the four-thread read comparison was repeated at
a higher fork count — two forks and ten iterations, Falco 1 325.6 ± 21.1 µs/op against Minestom
359 690.8 ± 97 498.3 µs/op [14] — which is the only run in this project whose `±` covers more than
one JVM launch, and the point on which the loader's claim rests rather than the single-fork row.

A fifth repeat exists and has not been collected: `ScalingBenchmark` declares `sectionCount` and
`distinctStates` in one state class, but `blockLightBySectionCount` and `skyLightBySectionCount`
never read `distinctStates`, so every light measurement in that class is already performed five
times over five irrelevant parameter levels, with independent warmups each time. The spread across
them is a direct empirical estimate of exactly the run-to-run variability at issue, and it costs one
benchmark run and no code change to recover.

Two of the five collected repeats teach the honest one-line summary of this entire evidence base:
**ratios reproduce, third digits do not.** The two loader repeats teach something sharper, which the
project did not set out to establish and which is stronger than any factor it withdrew: **Falco's
read time is reproducible across JVM launches and Minestom's is not.**

**But "ratios reproduce" is bounded by the machine, and every repeat above was run on the same one.**
The first run of the on-demand CI workflow measured `LightEngineComparisonBenchmark` at two forks on
a four-core AMD EPYC 7763 runner and put the `UNIFORM` 64 / 0 % pair at 131.96 ± 1.81 against
177.48 ± 0.68 µs/op — **1.35× faster**, where the same comparison on the sixteen-core machine that
produced the published tables gives 1.13× and 1.16×. Both are tight, both are disjoint, and they
disagree by more than either interval admits. The ratio is not a property of the two algorithms
alone; it is a property of the two algorithms **on a given cache hierarchy and memory system**, and
it moves when that changes.

Two consequences follow, and the second is the useful one. Nothing in this documentation may be read as
holding on hardware other than what its provenance line names — a factor is no more portable than an
absolute figure, only more stable when the machine is held fixed. And an interval alone cannot warn
anybody of this: the CI figures are *narrower* than the published ones (Minestom's half-width there
is 0.68 against 5.60) while being further from the truth about any other machine. **Precision and
generality are separate properties, and the `±` measures only the first.**

<sub>`LightEngineComparisonBenchmark`, two forks, `-p lightSources=64 -wi 5 -i 10`, JMH 1.37, JVM
25.0.3, one 4-core `ubuntu-latest` runner (AMD EPYC 7763), load average 0.31 before the run and 1.26
after — that reading covers the runner VM only and says nothing about the neighbours sharing its
host, commit `0ceeece`, run on 2026-08-02, `results.json` attached to the workflow run. This is a
single run on a shared runner and is quoted here for the comparison between machines, not as a
figure about either implementation.</sub>

**Warmup may be too short on the largest benchmarks, and nothing checks it.** Warmup is three or five
iterations of one second. For the small library benchmarks — a 4 096-entry loop at tens of µs/op, so
tens of thousands of invocations per second — three seconds is comfortably past C2 compilation. For
the large comparison benchmarks it is much thinner. `AreaVsPerChunkBenchmark` at 16 chunks runs at
roughly 11 600 µs/op, so three seconds of warmup is on the order of 260 invocations in total, each of
them traversing a large and mostly cold object graph; `IncrementalVsFullBenchmark` sky light at
roughly 39 600 µs/op gets about 75. Whether C2 has reached a steady state in 75 invocations of such a
path is genuinely unknown, the harness does not check it, and no published table states that it was
checked. Raising `-wi` and showing the number does not move would settle it.

**State built once per trial can drift across it.** Because every `@Setup` is `Level.Trial`,
`RegionFileBenchmark.writeRaw` and `ChunkSaveStageBenchmark.transfer` write the same chunk coordinate
on every invocation for the whole trial — the allocator frees the old sectors after reserving the new
ones, so the file alternates between two sector ranges rather than growing without bound, and the
source argues this. `IncrementalVsFullBenchmark` toggles one block between two states each
invocation, which keeps the world alternating rather than drifting, and says so. Two cases are not
discussed anywhere: `ChunkSaveComparisonBenchmark` writes through both loaders for a whole trial and
both keep internal caches that are warm after the first invocations, and `AreaVsPerChunkBenchmark`
re-lights the same chunks for the whole trial, which its javadoc states since the correction of
2026-08-02 — it had claimed a per-iteration rebuild the annotations never provided. The conclusion it
supports survives, because a fourfold to sixfold gap is far outside anything repeated lighting of the
same chunks could produce; what that repetition does to the third digit has not been measured.

**The thread series is four sessions, not one curve.** Because JMH takes one `-t` per run, the rows
of the region-file table were produced by four separate invocations at different times. Nothing
guarantees the machine was in the same state for all four, and nothing in the data would reveal it if
it was not.

**Two rows of the loader table carry no factor at all, and a third resolves no difference. Only the
two-thread row supports one.**

| Threads | Falco read | Minestom read | |
| ---: | ---: | ---: | --- |
| 1 | 1 203 ± 123 | 1 060 ± 55 | intervals overlap — no difference resolvable |
| 2 | 1 181 ± 31 | 2 200 ± 445 | 1.9× faster |
| 4 | 1 378 ± 84 | 11 021 ± 16 470 | not usable as a factor |
| 8 | 2 438 ± 252 | **530 905 ± 1 928 261** | not usable as a factor |

<sub>`RegionFileComparisonBenchmark.falcoRead` / `.minestomRead`, `distinctStates = 200`, one thread
count per row via `-t`, one fork, 3 warmup and 5 measurement iterations of 1 s per the class
annotation — earlier documentation of this table recorded 2 s and no `-r` override is recorded, so
the iteration time of the published run is unrecorded — JMH 1.37, one 16-core machine
recorded as not idle, no `results.json` committed, µs/op. One fork: the `±` covers variance between
iterations of one JVM, not between JVM launches — see
[What the `±` is](#the-interval-after-a-number). This table is reproduced from
[Measured results](../reference/measured-results.md#the-region-file-against-the-one-minestom-ships-with), which owns
it; any correction is made there first.</sub>

<!-- TODO(maintainer): the 2 s iteration time on this table contradicts @Warmup/@Measurement(time = 1)
     on RegionFileComparisonBenchmark. Either the run passed -r 2 and that was not recorded, or the
     "2 s" was copied from ChunkSaveComparisonBenchmark, which is the class that really uses time = 2.
     Confirm from a run record; do not guess. -->

On the eight-thread row the error is 1 928 261 against a mean of 530 905 — **3.6 times the mean.** An
interval that wide over a quantity that cannot be negative is not a measurement of a duration; it is a
measurement of a distribution with a very long tail. The four-thread row fails the same test: 16 470
against 11 021 is **1.5 times the mean**, so its lower bound is −5 449 and the measurement does not
constrain the value. The same rule that disqualifies row 8 disqualifies row 4, and applying it to only
one of them is exactly what a hostile reader is looking for. What both rows legitimately show is the
same thing and it is more interesting than a factor: *Minestom's read time loses predictability under
concurrent readers, scattering over orders of magnitude, while Falco stays inside about ±10 %.* For a
server that is the worse of the two failures — but it is a statement about variance, and the
two-fork control run above is what carries the magnitude.

The two-thread row survives every test **within its own run**, and is the one row the independent
repeat did not reproduce: the intervals `[1 150, 1 212]` and `[1 755, 2 645]` do not touch, so the
difference is supported for this run. Minestom's half-width is 20 % of its mean, which is what limits
the ratio to one decimal — the conservative bounds from the two intervals put Falco between 1.45× and
2.30× faster. The second run of the identical configuration, published in
[How the Anvil loader is built](how-the-anvil-loader-is-built.md) [14], puts Minestom at 103 437 ± 856 306 µs/op on this row
instead of 2 200 ± 445 — a half-width 8.3 times its own mean, which carries no factor at all. The
1.9× is therefore a statement about one run. What both runs agree on is the direction and the
dispersion, and Falco's four numbers reproduce across them where Minestom's do not.

**Single-threaded, this loader is not the faster one, and the difference is not resolved either.** At
one thread Falco reads at 1 203 ± 123 against Minestom's 1 060 ± 55, so Falco's mean is higher, but
`[1 080, 1 326]` and `[1 005, 1 115]` overlap and the gap is not resolvable at this precision. The
same holds at 8 distinct states. The three-stage pipeline has a setup cost and, with no contention,
nothing to win it back from; the claim was always that the gain is in lock granularity, and this row
is what that means when there is no lock to contend for. **Writing shows no resolvable difference at
any thread count**, all four intervals overlapping, which is what the structure predicts: both
implementations take a lock for the sector allocation and the header. The mechanism is why a tie is
the expected outcome; the numbers are consistent with it and do not by themselves prove equality.

**The size of the read collapse is not explained.** A second run of the same comparison, recorded in
[How the Anvil loader is built](how-the-anvil-loader-is-built.md) [14], reproduces the direction but not the magnitudes. At
two threads the shape changes from *resolvable factor* to *no factor admissible*; at four threads
Minestom's mean moves from 11 021 to 302 704 µs/op and at eight from 530 905 to 297 075. Pure
serialisation of a 1 045 µs operation across four threads predicts roughly 4 200 µs, not 360 000. The
measured effect exceeds that by almost two orders of magnitude, which points at lock convoying or
scheduler interaction rather than plain queueing. The direction reproduces across two independent runs
— the *mechanism* behind the magnitude has not been investigated, and the magnitude should not be
quoted as though it were understood.

**Two tables in the light documentation were measured at different settings.** The six-scenario
`UNIFORM` table was run with `-f 1 -wi 5 -i 10`; the four-scenario `MIXED` table with `-f 1 -wi 3
-i 5`, which is the class annotation. Ten iterations against five is a materially different sample —
`t(0.9995, 9)` is 4.78 against 8.61 — so the two tables are not directly comparable and neither their
absolute values nor their spreads should be laid side by side. That incomparability is what made the
old statement about mixed sources untenable: it anchored "about a third" against a `UNIFORM` value of
112.7 µs at 64 sources and 0 % solid which appeared in no published table, while the corresponding
row of the longer run reads 109.2 ± 1.6. The 112.7 has been dropped and nothing put in its place —
substituting 109.2 would have crossed exactly the boundary this paragraph draws.

The replacement measures both cells **in one run at one setting**, which removes the problem rather
than working around it: 109.88 ± 1.47 against 155.73 ± 8.44, a rise of 41.7 % with bounds of 32.3 %
to 51.4 %, set out under
[With sources of mixed brightness](../reference/measured-results.md#with-sources-of-mixed-brightness). It is a
two-fork run, so its interval covers more than one JVM launch — the only figure on this subject of
which that is true.


**Several published figures carry no uncertainty at all, and no rule above can be applied to them.**
The four save-stage rows, the five rows of the light stage table, the two fast-path rows and every
`ScalingBenchmark` claim are point estimates with unknown spread. The compression level used to
belong on that list and no longer does: `compressFalcoLevel` against `compressMinestomLevel` has been
run at two forks over all five `distinctStates` values, so the figure now carries intervals, bounds
and a provenance line, and the "1.83× for about 3 % more bytes" it replaced turned out to match no
parameter value at all — see
[Compression level 2 against level 6](../reference/measured-results.md#compression-level-2-against-level-6). A ratio
computed from two figures without intervals has no defensible precision, and where one is quoted it
should be read as an order of magnitude. Two of them additionally need relabelling rather than
hedging. The save-stage row printed
as "zlib compression" is the difference `codec − codecWithoutCompression`, and since `codec()` is
`compress(serialize(encode(…)))` while `codecWithoutCompression()` is `encode(…)` alone and returns a
`CompoundBinaryTag` that is never serialised, that difference contains **NBT serialisation as well as
zlib** — the number is correct for what it measures and the label is not. The fast-path table headed
*Before / After* compares `distinctStates = 200` against `distinctStates = 1`: two different inputs to
the same code, not two versions of the code, and the body text around it already describes it
correctly.

Where the save decomposition is concerned this matters less than it looks, because the load-bearing
claim is not a measurement at all. Which stage holds which lock is a property of `FalcoAnvilLoader`
that a reader can establish by opening it: the snapshot runs under the chunk read lock, the transfer
under the region lock, and the codec — palette building, NBT serialisation and compression — under
neither. The stage times say how much weight sits on the lock-free side; the structure is what makes
the claim true, and a structural claim cannot be dismissed as noise.

**One class is published only in the half that cannot settle its own claim.**
`ChunkSaveComparisonBenchmark` exists to test the sharpest falsifiable claim in the Anvil work —
that Minestom's palette deduplication grows with `blockCount × paletteSize` while Falco's grows with
`blockCount` alone — over `distinctStates` 1, 16, 64, 256 and 1024, with two calibration methods
built in so the compression-level difference can be subtracted out. Twenty configurations exist and
five of them are published: the `falcoSave` / `minestomSave` series at those five `distinctStates`
levels, in [How the Anvil loader is built](how-the-anvil-loader-is-built.md) under *Measured: saving a chunk*. Exactly one of
those five rows resolves a difference, and that row is confounded by the compression level, because
both loaders are measured as they ship and they ship different ones. What is still unpublished is
the pair the class carries to remove that confound, `compressFalcoLevel` and
`compressMinestomLevel`, without which the palette claim cannot be separated from the zlib level.
That subtraction is the gap, and it costs one run.

<!-- TODO(maintainer): the save-stage percentages do not follow from the published rows —
     64 + 17 = 81 locked against 1 356 + 2 701 = 4 057 unlocked gives 98.0 % outside the lock and
     65.3 % for the serialise-and-compress row, not 97 % and 63 %. Either show the arithmetic that
     produces the published figures (ChunkSaveStageBenchmark.full as the denominator would), or
     restate them. full() exists precisely so the decomposition can be checked, and is not
     published. -->

**A number of light-path figures are not JMH results at all.** The section *Where the time goes in the
light path* in [Measured results](../reference/measured-results.md) began as a standalone rebuild of the same call
structure, run outside the project on Temurin 25, best of seven — not JMH and not the real code. The
most important part of it has since been replaced by real measurements from
`LightEngineStageBenchmark`, and the page marks which. The remainder still carries **ratios only**;
its absolute microseconds are coarser than everything else in the repository. The estimates of the
open optimisations — heightmap seeding at 79.3 → 55.1 µs, flat column opacity at −26 %, direction
skipping at −7 to −16 % — are all of this kind. Treat them as the reason to build a benchmark, not as
a benchmark result. The "±19.1 spread against Minestom's ±2.7" quoted in the same section appears in
no published table and cannot be traced.

**Some allocation figures are arithmetic and some are measurements, and the difference is worth
knowing.** Allocation counts are essentially deterministic: they are immune to fork count, to machine
load and to JIT variance, which makes them the most robust quantitative evidence this project has.
Where the number came from `-prof gc` and its `gc.alloc.rate.norm` counter [15] — the opacity table
going from 74 040 to 8 664 bytes per call — it is a real measurement and stronger than any timing on
these pages. Where it was derived from declared buffer sizes it is not. "`ChunkLightState` allocates
about 980 KB of buffers per instance" was arithmetic, and the sentence that followed it — "roughly 28
MB of garbage per call" for the nine states `calculateWithNeighbours` builds — did not even follow
from that, since nine times 980 KB is 8.8 MB. Both figures have since moved and been corrected in
place; the current pair, about 100 KB per state and roughly 900 KB per call, is arithmetic in exactly
the same way and [Measured results](../reference/measured-results.md) marks it as such. It has still not been checked
with a profiler, and one `-prof gc` run would settle it. The lesson is not that the estimate was off
but that a derived number was carried forward through three documents without anyone re-doing the
multiplication.

**`RegionFileBenchmark` is not a storage benchmark.** It runs on a warm page cache and measures
almost no device time. That is realistic for a server saving the same chunks repeatedly and useless
as a statement about a disk.

**No benchmark reports a chunk load time.** Not one figure in this repository answers "how long does
it take my server to load a chunk". The library benchmarks exclude Minestom on purpose; the
comparison benchmarks include it but measure a region file or a light section, not the loading path
of a running instance.

**Nothing corrects for multiple comparisons.** A full run produces 488 configurations, each with its
own 99.9 % interval, and at that count a handful of intervals will exclude their true mean by chance
alone. This is not a serious problem here, because the published claims are few and the supported
effects are large — but a reader who wants to count is entitled to know that nobody adjusted for it.

## Reproducing this

Nothing here needs a special machine. It needs an idle one, and about ten minutes per benchmark. The
command lines for every published table live in one place — [How-to Reproduce a published measurement](../how-to/reproduce-a-published-measurement.md) — and are not repeated here, so that a correction to one is made once.

What a reproduction of yours establishes, and what it does not, is the subject of the rest of this
page.

### What agreement would look like

A replication is not expected to reproduce these numbers. It is expected to reproduce these
conclusions, and the criteria are stated in advance so that neither side gets to decide after the
fact.

- **A row marked *supported* agrees** if the direction is the same and your conservative ratio bounds
  overlap the ones printed in the table. It **disagrees** if the direction reverses, or if your
  bounds are disjoint from ours — which would mean one of the two machines is measuring something the
  other is not.
- **A row marked *no difference resolvable* agrees** if your intervals also overlap, and is a *new*
  result if yours are disjoint. Resolving a difference we could not is an improvement on this page,
  not a contradiction of it.
- **A row marked *not usable* agrees** if your slower side is also dispersed — an error bar that is a
  large fraction of its own mean. A run in which Minestom's read time stays tight under four or eight
  concurrent readers would be a genuine refutation of the loader's central claim, and it is the
  single most valuable measurement anyone outside this project could contribute.
- **Absolute microseconds are not expected to agree at all.** Do not report a mismatch in them as a
  failure to reproduce; report the ratio.

Ratios reproduce, third digits do not. Two of the five repeats this project has run behaved that
way; on the other two, both on the loader table, Falco's numbers reproduced and Minestom's did not.
A fifth that behaves differently from any of them is worth hearing about. The repeats themselves are
listed under [Reference Measured results](../reference/measured-results.md#measurement-environment).

## What this all adds up to

The honest summary is narrower than the headline factors, and it is the summary that should be
carried into any decision about using this code.

What the measurements support well: that the light engine is ahead of the built-in one in all six
uniform-brightness scenarios and in both mixed-brightness scenarios with occlusion, on disjoint
intervals, with byte-identical output verified before every trial and on every build. That one area
pass is cheaper than one neighbourhood per chunk from four chunks upward, and that replaying a changed
block is cheaper than relighting the chunks, both on disjoint intervals and both reproduced on an
independent re-run. That Falco's region file degrades gently under concurrent readers while
Minestom's does not — in direction, and as a loss of predictability rather than as a factor —
confirmed by two independent single-fork runs and one two-fork control, and reinforced by the fact
that Falco's four read figures reproduce across those runs and Minestom's do not. That a chunk save
spends the great majority of its time outside any lock, which is a
stage decomposition on one machine and, more importantly, a property of `FalcoAnvilLoader` that can be
established by reading it.

What they do not support: any absolute microsecond figure as a property of anything but that machine
on that day; any factor from the four- or eight-thread read rows; the single-thread read row in either
direction; any claim about what a chunk costs on a real server; and the current allocation arithmetic,
which is awaiting a profiler run.

What is missing: a second machine, more than one fork on the benchmarks that matter, a committed
`results.json` carrying the environment, the `ChunkSaveComparisonBenchmark.compress*Level` pair that
would separate the palette claim from the compression level, and an automated regression gate. None
of these is hard. None has been done.

---

## Sources

1. **JMH `Result#getScoreError` and `AbstractStatistics#getMeanErrorAt`**, OpenJDK JMH. The reported
   `±` is `statistics.getMeanErrorAt(0.999)`, computed as
   `t(1 − (1 − confidence)/2, n − 1) × s / √n` over the measurement iterations. This is the
   definition the section [What the `±` is](#the-interval-after-a-number) states.
   <https://github.com/openjdk/jmh/blob/master/jmh-core/src/main/java/org/openjdk/jmh/results/Result.java>
2. **jmh-gradle-plugin**, Cédric Champeau. Gradle plugin giving JMH benchmarks their own `src/jmh`
   source set and a `jmhJar` task producing "a self-contained benchmark jar containing the JMH
   runtime and your compiled benchmark classes". Version `0.7.3` in this repository.
   <https://github.com/melix/jmh-gradle-plugin>
3. **JMH — Java Microbenchmark Harness**, OpenJDK. "A Java harness for building, running, and
   analysing nano/micro/milli/macro benchmarks written in Java and other languages targeting the
   JVM." Version `1.37` in this repository. <https://github.com/openjdk/jmh>
4. **`JMHSample_12_Forking` and `JMHSample_13_RunToRun`**, OpenJDK JMH samples. Why benchmarks run in
   forked JVMs and why variance between JVM launches is distinct from variance between iterations.
   <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
5. **`org.openjdk.jmh.infra.Blackhole`**, JMH API documentation — including `consumeCPU(long)`, used
   here to give a fake registry lookup a configurable cost.
   <https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/infra/Blackhole.html>
6. **`JMHSample_21_ConsumeCPU`**, OpenJDK JMH samples. "The single token is just a few cycles, and
   the more tokens you request, then more work is spent (almost linearly)" — the basis for reading
   `resolveCost = 50` as a few hundred cycles rather than as a calibrated registry lookup.
   <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
7. **Minestom**, resolved through `net.onelitefeather:mycelium-bom:1.7.2` rather than pinned in this
   repository; [Contributing](../contributing/README.md#environment) records the resolved version as
   `2026.06.20-26.1.2`. The package-private types measured by the comparison benchmarks are
   `net.minestom.server.instance.anvil.RegionFile`,
   `net.minestom.server.instance.light.BlockLight#buildInternalQueue` and
   `net.minestom.server.instance.light.LightCompute#compute`. Established by reading the sources.
   <https://github.com/Minestom/Minestom>
8. **[How the light engine works](how-the-light-engine-works.md)**, this documentation. The six-scenario comparison against the built-in
   light engine, the stage breakdown, and the mixed-brightness table.
9. **`JMHSample_08_DeadCode`**, OpenJDK JMH samples. Why the result of a measured method must be
   returned or consumed.
   <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
10. **`JMHSample_10_ConstantFold`**, OpenJDK JMH samples. Why inputs must not be compile-time
    constants and must be built in `@Setup`.
    <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
11. **"Nanotrusting the Nanotime"**, Aleksey Shipilëv, 2014. On the latency and granularity of
    `System.nanoTime`: "at best, you can hope for 15-30 ns per call" and "at best, you can hope for
    30 ns resolution, and it varies wildly between platforms".
    <https://shipilev.net/blog/2014/nanotrusting-nanotime/>
12. **`JMHSample_11_Loops` and `JMHSample_34_SafeLooping`**, OpenJDK JMH samples. Why a loop inside a
    measured method can be hoisted or eliminated, and how to write one that is not.
    <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
13. **"Statistically Rigorous Java Performance Evaluation"**, Andy Georges, Dries Buytaert, Lieven
    Eeckhout. OOPSLA 2007, pages 57–76. "Non-determinism at run-time causes the execution time of a
    Java program to differ from run to run", from JIT compilation driven by timer-based method
    sampling, thread scheduling, garbage collection and system effects; the paper surveys the
    prevalent methodologies, shows they can be misleading, and argues for statistically rigorous
    analysis over multiple VM invocations.
    DOI [10.1145/1297027.1297033](https://doi.org/10.1145/1297027.1297033)
14. **[How the Anvil loader is built](how-the-anvil-loader-is-built.md)**, this documentation. A second, independent run of the
    region-file comparison, the two-fork ten-iteration control run at four threads, and the note that
    the magnitude of Minestom's collapse under contention is not understood.
15. **`JMHSample_35_Profilers`**, OpenJDK JMH samples. On `-prof gc` and the normalised allocation
    counter `gc.alloc.rate.norm`.
    <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
16. **JMH Visualizer**. Renders a JMH `results.json` in the browser; "serverless architecture — all
    happens locally in your browser", so the file is not uploaded. <https://jmh.morethan.io/>
