# Measurement

Falco claims factors. The loader is said to read 8× faster than the one Minestom ships with at four
threads, the light engine to be between 1.11× and 1.71× faster than the built-in one, compression at
level 2 to be 1.83× faster than at level 6. This document answers the only question that matters
about such numbers: **why should someone who did not run them believe them?** It describes how the
benchmarks are built, what each one deliberately excludes, which parameter values were chosen and
why, and — at greater length than the flattering parts — where the evidence is thin. It is the
methodological companion to [`docs/benchmarks.md`](../benchmarks.md), which is the operating manual;
this document is the argument.

The short version: the ratios in this repository are worth trusting, the absolute microseconds are
not, one row of one table should never be quoted as a factor at all, and a reader who wants to check
any of it can do so in about ten minutes per benchmark.

## Where the harness lives, and why it is not in the library

The benchmarks are a separate Gradle module, `falco-benchmarks`, with a separate source set,
`src/jmh/java`, created by the `me.champeau.jmh` plugin at version `0.7.3` [1] over JMH `1.37` [2].
The module never applies `maven-publish`; the root `build.gradle.kts` enumerates
`falco-anvil`, `falco-light` and `falco-instance` explicitly for publishing, so a `./gradlew publish`
at the root passes over the benchmarks without needing a task exclusion.

Three consequences follow, and all three were the point.

JMH itself never reaches the classpath of a published artefact. `jmh-core` is a compile-scope
dependency of a benchmark, and a benchmark that lived in `src/test` of `falco-light` would put JMH,
`fastutil` and a full Minestom on the test classpath of a library whose main source set deliberately
declares Minestom as `compileOnly`. It also never reaches a consumer's POM.

The benchmarks can depend on things the library must not. `falco-benchmarks` imports the Adventure
BOM directly, because the library gets Adventure through `compileOnly(minestom)` and therefore has it
at compile time only, while a benchmark runs the code for real. It also pulls Minestom and `fastutil`
at runtime scope, which the comparison benchmarks need and the library must not have.

And one module can measure both libraries at once. `ScalingBenchmark` times the Anvil codec and the
light propagator in the same run, and `BenchmarkConstants` and `SectionStates` are used from both
sides. A `jmh` source set inside each library module would have forced either duplication of those
two support classes or a dependency edge between `falco-anvil` and `falco-light` that does not exist
in production. The module's own `build.gradle.kts` says exactly this in a comment above its
`dependencies` block.

### Compiled by every build, executed by none

`falco-benchmarks/build.gradle.kts` wires `compileJmhJava` into `check`, and nothing wires `jmh` into
anything. The asymmetry is deliberate on both ends.

Compilation is part of the build because a benchmark is source code that references the internals of
the library — `PaletteData.encode`, `SectionOpacity.of`, `RegionFile.readRaw` — and a refactoring
that breaks it should fail like any other consumer. A benchmark suite that silently stops compiling
is a benchmark suite nobody will run again.

Execution is not part of the build for two reasons that are worth separating. The first is time: a
full `./gradlew jmh` runs **476 benchmark configurations**, counted from the source as the sum over
all fourteen benchmark classes of the `@Benchmark` methods times the cross product of their `@Param`
values. `ScalingBenchmark` alone contributes 300 of them (4 methods × 15 section counts × 5
distinct-state counts). At the forks and iterations the annotations ask for, that is well over an
hour. The second reason is the one that would still apply if it took a minute: **JMH results from a
shared CI runner cannot gate anything.** Run-to-run variance on a JVM is large enough that JMH treats
it as a first-class concern and dedicates a sample to it [3]; a build agent shared with other jobs
adds a second, larger source of variance on top. A threshold set loose enough not to fail spuriously
on such a machine would be loose enough to let a real regression through.

The consequence is stated rather than hidden: **there is no automated performance regression gate in
this repository.** Every number in `STATUS.md`, `anvil-chunk-loader.md` and `light-engine.md` was
produced by a human running a benchmark by hand and pasting the result. A change that halved the
speed of the light engine would break no build. What does break the build is a change that makes the
two light engines disagree, which is a different property and is covered below.

## Two kinds of benchmark, and why they are not comparable with each other

The most consequential decision in this suite is that **most of the benchmarks do not start a
Minestom server, and a few must.** Reading a number without knowing which kind it came from produces
a wrong conclusion, so the distinction is drawn here before anything else.

### The library benchmarks: Minestom removed on purpose

Ten of the fourteen classes are library benchmarks, and eleven of the fourteen run in a bare JVM fork
with no server at all. Both libraries were designed so that this is possible: the Anvil codec reaches
the registry only through `PaletteEntryResolver`, and the light engine only through
`BlockLightSource`. The benchmarks plug `FakePaletteEntryResolver` and `FakeBlockLightSource` into
those two seams.

This is a trade, not a free win. A registry lookup is expensive enough to dominate every one of these
measurements, and a benchmark whose result is 90 % Minestom says nothing about the code in this
repository. The cost is that **no library benchmark here reports what a chunk load costs on a real
server.** They report what this library contributes to one. That distinction is repeated in
`docs/benchmarks.md` [4] and it is repeated here because it is the single easiest way to misread the
suite.

The fake is not free, though, and that detail is where the design gets interesting.
`FakeBlockLightSource` takes a `resolveCost` and burns it through `Blackhole.consumeCPU` [5] on every
`emission` and every `blocksFace` call. The reason is that `SectionOpacity` exists to resolve each
distinct block state once instead of once per visit, and **the value of a cache is a function of what
a miss costs.** Measured against a fake that answers in a nanosecond, the opacity table looks like
pure overhead — the hash lookup and two array writes per block, with nothing saved. That is the
opposite of what happens against a real registry. `SectionOpacityBenchmark` therefore takes
`resolveCost` as a parameter with values `0` and `50`, and the two columns answer two different
questions: at `0` you are measuring the table's own cost, at `50` you are measuring what it saves.
Reporting only one of them would be a choice about the conclusion rather than about the measurement.

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
Minestom sources as of `2026.06.20-26.1.2` [6], established by reading them, not by measuring.

`ChunkSaveComparisonBenchmark` is the exception that proves the rule: `AnvilLoader` is public, so it
stays in `net.onelitefeather.falco.benchmark.anvil`. What it cannot avoid is starting a server.
`AnvilLoader` reads the biome registry and the block state count in **static** fields, so merely
touching the class from a bare fork fails in the class initialiser before a single measurement
begins. Its `@Setup` therefore calls `MinecraftServer.init()`, as do both light comparison classes.
Minestom's `RegionFile` reads no registry, which is why the region-file comparison is the one
comparison that runs in a bare fork.

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
bits per entry, and `8` because it is a plausible built-up section.

**Light sources — 0, 1, 8, 64.** `0` is not padding. It is the case in which the search does not run
at all, and it is the state of the overwhelming majority of the sections of a real world. A light
benchmark that only measures lit sections reports the worst case and calls it typical. `64` is where
the searches of separate sources begin to overlap heavily, which is a different regime from `1`.

**Solid fraction — 0 % and 25 %, or 0 % and 30 %.** Solid blocks stop a search early, so an entirely
open section is the upper bound of the work, not the normal case. Both engines get faster with
occlusion; the *margin between them* moves too, and in Falco's favour — from 1.11× at 0 % solid to
1.58× at 30 % with one source [7]. Measuring only open sections would have understated the result;
measuring only occluded ones would have overstated it. Both are in the table.

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

One cell of that cross product measures nothing, and the source says so: with a single source,
`MIXED` places glowstone as well and is byte-for-byte the same section as `UNIFORM`. The recommended
invocations in `docs/benchmarks.md` skip it, which turns twelve scenarios per method into ten.

**Thread count.** This is not a `@Param` at all. JMH takes one `-t` per run, so the four-row table in
`STATUS.md` is **four separate measurement sessions**, not one. That has a consequence stated plainly
below. The thread count is the axis on which the loader's central claim lives — lock granularity —
and `RegionFileComparisonBenchmark`'s javadoc says outright that a single thread cannot show a
difference that only exists between threads.

**`sectionCount` — fifteen values from 1 to 256.** `ScalingBenchmark` is the only benchmark here that
measures the library against itself rather than against Minestom, and its parameter set is
deliberately fine-grained where the others are coarse. The purpose is to find where a curve stops
being straight, and three well-spaced points cannot do that: they will fit a line through anything.
The payoff is concrete. A least-squares fit over the vanilla range (≤ 24 sections) predicts 21 423 µs
of sky light at 256 sections; the measured value is 26 597 µs, so the forecast **understates it by
19.5 %**. For block light the same method lands within 1.8 %. That non-linearity is only known
because the exotic sizes were measured rather than extrapolated from the common ones, and it is what
made incremental heightmap seeding a real open item rather than a micro-optimisation.

**`bitsPerEntry` — 4, 5, 8, 15.** The packing loop's iteration count per `long` is a function of this
and of nothing else, so it is the only axis `BitPackerBenchmark` needs.

**`compression` — ZLIB, GZIP, NONE.** With `NONE` present, the compression stage can be subtracted
from the codec stage rather than estimated.

## What keeps a number honest

The suite follows a set of rules; the ones that are load-bearing are these.

**Inputs are built in `@Setup`, never in a measured method,** and every measured method returns its
result so the JIT cannot delete the work. Both are the standard JMH failure modes — dead-code
elimination and constant folding — and JMH's own samples exist to demonstrate exactly them [8][9].
Where there is no natural result, as in `RegionFileBenchmark.writeRaw` and
`ChunkSaveStageBenchmark.transfer`, the side effect on the file is what keeps the call alive.

**Inputs are deterministic.** Every library generator seeds from `BenchmarkConstants.SEED`
(`0x5DEECE66D`); the three Minestom-package comparison classes seed from their own constant
`20260731L`. Two runs of the same benchmark must see byte-identical input or the difference between
them describes the input rather than the change. The split into two seed constants is untidy but
harmless, since no benchmark compares across the two families.

**Generated sections are shaped like a world, not like noise.** `SectionStates.distinct` writes
states in contiguous runs rather than scattering them, because a real section holds runs of the same
block and a purely random fill produces a palette access pattern no world ever shows.
`SectionStates.lit` spreads light sources by a fixed stride rather than placing them randomly, and
the comment says why: a random placement can cluster the sources, and a cluster performs far less
work than the same count spread out, because the searches overlap immediately. Note that the two
Minestom-package light comparisons do the opposite — they place sources at `random.nextInt` positions
— so `lightSources = 8` does not denote the same section in `LightPropagatorBenchmark` and in
`LightEngineComparisonBenchmark`. This is the second reason those two families' absolute numbers must
not be compared.

**The compression level is held equal where it is not the subject, and measured where it is.**
`RegionFileComparisonBenchmark` writes through the identical Adventure writer at the same level on
both sides, over byte-identical stored payloads, so nothing it reports is a compression level in
disguise. `ChunkSaveComparisonBenchmark` does the reverse on purpose: it measures the two loaders as
they ship, including Minestom's level 6 against Falco's level 2, because that difference is part of
what a user of the loader gets — and it adds two separate calibration methods, `compressFalcoLevel`
and `compressMinestomLevel`, so the level can be subtracted from the save numbers afterwards. Either
choice alone would have been arguable. Making both, and saying which is which, is what makes the
result readable.

**Both light engines must agree before either is timed.** `LightEngineComparisonBenchmark.setUp`
runs both paths over the section it just built, compares the 2048 bytes, and throws — aborting the
trial — if they differ, with a message naming the number of differing blocks, the largest difference
and the coordinates of the first. A faster number must never come from computing something else.
The same property is pinned in the ordinary test run by `LightEngineEquivalenceTest` over 54
scenarios, so `./gradlew build` fails if it is ever lost.

That gate is recent, and its history is the most useful thing in this section. The byte identity over
54 scenarios was stated as an established fact in `STATUS.md` and in `light-engine.md` **for a long
time while nothing in the build checked it.** It rested on an ad-hoc comparison run once by hand;
there was no test, and the benchmark did not verify it either, although the documentation said it
did. Two agents found this independently at their own ends of the code. The claim turned out to be
true, which is precisely not the point: a number cited throughout the documentation was hanging on
nothing. If you are evaluating this repository, that is the failure mode to check for elsewhere in
it, and it is why this document distinguishes measured claims from read-the-source claims at every
turn.

**The harness configuration is explicit on every class.** `@Fork`, `@Warmup` and `@Measurement` are
written out on all fourteen, rather than inherited from JMH's defaults, so a reader can see how much
evidence a number rests on without consulting the JMH manual. Every class also pins its heap with
`jvmArgsAppend = {"-Xms…", "-Xmx…"}`, which removes heap sizing and the resulting GC behaviour as a
free variable between runs.

**State scope follows the resource under test.** Twelve classes are `@State(Scope.Thread)`, because
the measured code is single-thread confined and the state objects hold mutable buffers.
`RegionFileComparisonBenchmark` and `ChunkSaveComparisonBenchmark` are `@State(Scope.Benchmark)` with
a nested `@State(Scope.Thread) ThreadSlot`, and the exception is the whole point of those two: the
shared region file is the contended resource, while each thread must work on a chunk of its own.
Without the slot, every thread would hammer one entry, which measures contention on a chunk rather
than contention on a file — and real parallel loading reads different chunks of the same region.
(`docs/benchmarks.md` states `@State(Scope.Thread)` "everywhere"; that is accurate for the library
benchmarks and not for these two.)

**Sweeps rather than single accesses.** `LightNibblesBenchmark` walks all 4096 blocks per invocation
instead of reading one nibble, because a single nibble read is a handful of instructions — below what
a harness can separate from its own overhead, given that a `nanoTime` call itself costs on the order
of 15–30 ns and its granularity can be far coarser [10].

## Where the harness still loses

Two of the `LightNibblesBenchmark` methods report times far below one nanosecond per access, and this
is stated in the documentation rather than quietly dropped. `getUniform` and `setUniformUnchanged`
operate on a section whose blocks all carry the same level, so every read is answered from a single
field regardless of coordinates. The loop body is loop-invariant, the JIT hoists it, and the empty
loop disappears — the textbook case JMH's loop and dead-code samples exist to warn about [8][11].
Adding a `Blackhole` does not fix it; this was verified by rerunning with
`-Djmh.blackhole.autoDetect=false`, which produces the same number.

Those two numbers are therefore **lower bounds, not per-access costs.** What they legitimately say is
that the uniform path can collapse to a single field read, which is exactly the property the shortcut
exists for. Only `getAllocated` and `setAllocating` divide meaningfully by 4096. Both eliminated
methods are kept anyway, because a change that accidentally made the uniform path allocate would show
up in them instantly.

## The limits, stated without softening

This is the section a reader evaluating the repository should weigh most heavily.

**One machine, and it was not idle.** Every JMH figure in this repository comes from a single machine
— 16 cores, per the note on the region-file table — that `STATUS.md` explicitly records as **not
idle** during the runs. Ratios between two rows measured in the same session are meaningful; absolute
microseconds carry a wide error and are not portable to any other host. Core count, frequency
scaling, page cache state and the file system all move them.

The repository's own documents are not consistent about this. `light-engine.md` introduces its main
comparison table with "on a quiet machine"; `STATUS.md`, which is the working record, says the
machine was not idle. **Take the conservative reading.** Nothing downstream depends on which is true,
because none of these numbers is quoted as an absolute anyway — but a document that asserts both is a
document to read carefully.

**No machine record survives.** JMH writes the JDK build, the VM name and version, the JVM arguments
and the full parameter set into `results.json`. That file lands in `build/`, which is gitignored, and
no run's output is committed. The consequence is precise: these results can be **re-performed**, but
they cannot be **exactly reproduced**, because the CPU model, the OS, the JDK vendor and build, and
the concurrent load of the original sessions are not recorded anywhere in this repository. This is a
real gap and there is no argument for it beyond nobody having done it.

**One fork on every benchmark that produces a headline number.** Seven of the fourteen classes run at
`@Fork(2)`: `BitPackerBenchmark`, `PaletteDataBenchmark`, `ChunkCompressionBenchmark`,
`LightNibblesBenchmark`, `SectionOpacityBenchmark`, `LightPropagatorBenchmark` and
`ChunkLightPropagatorBenchmark`. The other seven run at `@Fork(1)` — and they include every
comparison benchmark and `ScalingBenchmark`. That is backwards from where the risk is. Run-to-run
variance between JVM launches is a distinct source of error from iteration-to-iteration variance
within one launch, arising from profile-guided compilation decisions that differ per process, and a
single fork cannot see it at all [3]. The statistically defensible treatment of such data is to
report a confidence interval across independent runs, not across iterations of one run [12] — this
suite reports the latter. The published `±` figures are therefore **narrower than the true
uncertainty**, on precisely the tables whose conclusions matter most.

One control run exists and is worth more than the table it sits under: the four-thread read
comparison was repeated with two forks and ten iterations, giving Falco 1 325.6 ± 21.1 µs/op against
Minestom 359 690.8 ± 97 498.3 µs/op [13]. Direction and order of magnitude held. That is the point
that carries the loader's claim, not the single-fork row.

**The thread series is four sessions, not one curve.** Because JMH takes one `-t` per run, the rows
of the region-file table were produced by four separate invocations at different times. Nothing
guarantees the machine was in the same state for all four, and nothing in the data would reveal it if
it was not.

**The eight-thread loader row is not a factor and must never be quoted as one.**

| Threads | Falco read | Minestom read | |
| ---: | ---: | ---: | --- |
| 1 | 1 203 ± 123 | 1 060 ± 55 | 1.14× **slower** |
| 2 | 1 181 ± 31 | 2 200 ± 445 | 1.86× faster |
| 4 | 1 378 ± 84 | 11 021 ± 16 470 | 8.00× faster |
| 8 | 2 438 ± 252 | **530 905 ± 1 928 261** | not usable |

`RegionFileComparisonBenchmark`, 200 distinct block states, one fork, 3 warmup and 5 measurement
iterations of 2 s, 16 cores, µs/op [13]. On the eight-thread row the error is 1 928 261 against a
mean of 530 905 — **3.6 times the mean.** An interval that wide over a quantity that cannot be
negative is not a measurement of a duration; it is a measurement of a distribution with a very long
tail. The correct statement is therefore qualitative: *Minestom's read time loses predictability
under eight concurrent readers, scattering over three orders of magnitude, while Falco stays inside
about ±10 %.* For a server that is the worse of the two failures — but it is a statement about
variance, not a factor. The four-thread row has the same problem in milder form (± 16 470 on a mean
of 11 021, 1.5× the mean) and the 8.00× there should be read as "an order of magnitude", not as 8.

**Single-threaded, this loader is the slower one.** By 14 % at 200 distinct states and 20 % at 8. The
three-stage pipeline has a setup cost and, with no contention, nothing to win it back from. The claim
was always that the gain is in lock granularity; this row is what that means when there is no lock to
contend for. **Writing is a tie at every thread count** (1.00× to 1.05×, in either direction), because
both implementations take a lock for the sector allocation and the header — there is nothing to gain
and the numbers say so.

**The size of the read collapse is not explained.** A second run of the same comparison, recorded in
`anvil-chunk-loader.md` [14], gives different magnitudes with the same shape. Pure serialisation of a
1 045 µs operation across four threads predicts roughly 4 200 µs, not 360 000. The measured effect
exceeds that by almost two orders of magnitude, which points at lock convoying or scheduler
interaction rather than plain queueing. The direction reproduces across two independent runs and the
magnitude is stable — the *mechanism* behind the magnitude has not been investigated. The factor
should not be quoted as though it were understood.

**Two tables in the light documentation were measured at different settings.** The six-scenario
`UNIFORM` table was run with `-f 1 -wi 5 -i 10`; the four-scenario `MIXED` table with `-f 1 -wi 3
-i 5`, which is the class annotation. They are not directly comparable, and `light-engine.md` handles
this correctly rather than by accident: its statement that mixed sources cost Falco about a third is
anchored against a `UNIFORM` re-run at the *same* short settings (112.7 µs at 64 sources, 0 % solid),
not against the 109.2 µs of the longer run. If you re-derive that comparison from the two published
tables, you will get a different answer and it will be wrong.

**A number of light-path figures are not JMH results at all.** The section *Where the time goes in
the light path* in `STATUS.md` began as a standalone rebuild of the same call structure, run outside
the project on Temurin 25, best of seven — not JMH and not the real code. The most important part of
it has since been replaced by real measurements from `LightEngineStageBenchmark`, and `STATUS.md`
marks which. The remainder still carries **ratios only**; its absolute microseconds are coarser than
everything else in the repository. The estimates of the open optimisations — heightmap seeding at
79.3 → 55.1 µs, flat column opacity at −26 %, direction skipping at −7 to −16 % — are all of this
kind. Treat them as the reason to build a benchmark, not as a benchmark result.

**One allocation figure is arithmetic, not measurement, and it has now been quoted wrongly twice.**
"`ChunkLightState` allocates about 980 KB of buffers per instance" was derived from the declared
buffer sizes, and the sentence that followed it — "`calculateWithNeighbours` builds nine of them,
roughly 28 MB of garbage per call" — did not even follow from that: nine times 980 KB is 8.8 MB. The
figure has since moved, because the working queues stopped being sized for one entry per position of
the chunk; a state is now about 100 KB. That number is arithmetic in exactly the same way, and
`STATUS.md` marks it as such. It has still not been checked with an allocation profiler. `-prof gc`
and its `gc.alloc.rate.norm` counter would settle it in a single run [15]; that run has not been made.
Where allocation *was* measured that way — the opacity table going from 74 040 to 8 664 bytes per
call — the figure is a real one. The lesson is not that the estimate was off but that a derived
number was carried forward through three documents without anyone re-doing the multiplication.

**`RegionFileBenchmark` is not a storage benchmark.** It runs on a warm page cache and measures
almost no device time. That is realistic for a server saving the same chunks repeatedly and useless
as a statement about a disk.

**No benchmark reports a chunk load time.** Not one figure in this repository answers "how long does
it take my server to load a chunk". The library benchmarks exclude Minestom on purpose; the
comparison benchmarks include it but measure a region file or a light section, not the loading path
of a running instance.

## Reproducing this

Nothing here needs a special machine. It needs an idle one, and about ten minutes per benchmark.

```bash
./gradlew :falco-benchmarks:jmhJar
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar -l    # list every benchmark
```

The self-contained jar is the faster path than `./gradlew jmh`, because it skips Gradle entirely and
accepts every JMH command-line option. The three tables quoted above come from:

```bash
# The loader under contention. One run per thread count; -t takes a single value.
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    "RegionFileComparisonBenchmark.(falco|minestom)Read" -f 1 -wi 3 -i 5 -t 4 -p distinctStates=200

# The light engine, equal source brightness. This is the six-row table.
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    "LightEngineComparisonBenchmark.(falco|minestom)" -p emissionMix=UNIFORM -f 1 -wi 5 -i 10

# Mixed source brightness. lightSources=1 is skipped because MIXED degenerates to UNIFORM there.
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    "LightEngineComparisonBenchmark.(falco|minestom)" \
    -p emissionMix=MIXED -p lightSources=8,64 -f 1 -wi 3 -i 5

# Where a save spends its time, and how much of it is outside a lock.
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar ChunkSaveStageBenchmark
```

If you want the numbers to be worth more than ours, raise the fork count. `-f 5` on any comparison
benchmark costs five times the wall clock and produces the only kind of error bar that actually
covers run-to-run variance [3][12]. Add `-prof gc` to any allocation claim [15]. The Gradle task
writes `build/reports/jmh/results.json`, which [JMH Visualizer](https://jmh.morethan.io/) [16] reads
directly if you want the same views from your own run.

Two practical traps, both of which cost an hour the first time:

- **JMH allows one instance at a time.** A crashed run leaves `/tmp/jmh.lock` behind, and every later
  run fails with *"Another JMH instance might be running"*. Delete the file.
- **Do not run two Gradle builds in the same checkout at once.** They corrupt `build/test-results`
  and surface as `EOFException`, `NoClassDefFoundError` or a missing `jacoco/test.exec` — failures
  that look like real breakage. `rm -rf build` and rerun.

On Java 25, JMH 1.37 prints a warning about `sun.misc.Unsafe::objectFieldOffset` being terminally
deprecated. It comes from JMH itself, not from this repository, and it is harmless.

## What this all adds up to

The honest summary is narrower than the headline factors, and it is the summary that should be
carried into any decision about using this code.

What the measurements support well: that about 97 % of a chunk save runs outside any lock, and that
compression is 63 % of it — this is a stage decomposition on one machine, and the ratio between
stages is exactly the kind of quantity a single machine measures reliably. That Falco's region file
degrades gently under concurrent readers while Minestom's does not, in direction and order of
magnitude, confirmed by two independent runs and one two-fork control. That the light engine is ahead
of the built-in one in all six uniform-brightness scenarios and all four mixed-brightness ones, with
byte-identical output verified before every trial and on every build.

What they do not support: any absolute microsecond figure as a property of anything but that machine
on that day; any factor derived from the eight-thread row; any claim about what a chunk costs on a
real server; and the 28 MB allocation figure, which is arithmetic awaiting a profiler run.

What is missing: a second machine, more than one fork on the benchmarks that matter, a committed
`results.json` carrying the environment, and an automated regression gate. None of these is hard.
None has been done.

---

## Sources

1. **jmh-gradle-plugin**, Cédric Champeau. Gradle plugin giving JMH benchmarks their own `src/jmh`
   source set and a self-contained benchmark jar. Version `0.7.3` in this repository.
   <https://github.com/melix/jmh-gradle-plugin>
2. **JMH — Java Microbenchmark Harness**, OpenJDK. "A Java harness for building, running, and
   analysing nano/micro/milli/macro benchmarks written in Java and other languages targeting the
   JVM." Version `1.37` in this repository. <https://github.com/openjdk/jmh>
3. **`JMHSample_12_Forking` and `JMHSample_13_RunToRun`**, OpenJDK JMH samples. Why benchmarks run in
   forked JVMs and why variance between JVM launches is distinct from variance between iterations.
   <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
4. **`docs/benchmarks.md`**, this repository. How to run each benchmark, what each measures, and the
   hygiene rules the suite follows.
5. **`org.openjdk.jmh.infra.Blackhole`**, JMH API documentation — including `consumeCPU(long)`, used
   here to give a fake registry lookup a configurable cost.
   <https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/infra/Blackhole.html>
6. **Minestom**, version `2026.06.20-26.1.2`. The package-private types measured by the comparison
   benchmarks are `net.minestom.server.instance.anvil.RegionFile`,
   `net.minestom.server.instance.light.BlockLight#buildInternalQueue` and
   `net.minestom.server.instance.light.LightCompute#compute`. Established by reading the sources.
   <https://github.com/Minestom/Minestom>
7. **`docs/light-engine.md`**, this repository. The six-scenario comparison against the built-in
   light engine, the stage breakdown, and the mixed-brightness table.
8. **`JMHSample_08_DeadCode`**, OpenJDK JMH samples. Why a measured method must return its result.
   <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
9. **`JMHSample_10_ConstantFold`**, OpenJDK JMH samples. Why inputs must not be compile-time
   constants and must be built in `@Setup`.
   <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
10. **"Nanotrusting the Nanotime"**, Aleksey Shipilëv, 2014. On the latency and granularity of
    `System.nanoTime`, and why measurements shorter than the timer's granularity cannot be obtained
    reliably. <https://shipilev.net/blog/2014/nanotrusting-nanotime/>
11. **`JMHSample_11_Loops` and `JMHSample_34_SafeLooping`**, OpenJDK JMH samples. Why a loop inside a
    measured method can be hoisted or eliminated, and how to write one that is not.
    <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
12. **"Statistically Rigorous Java Performance Evaluation"**, Andy Georges, Dries Buytaert, Lieven
    Eeckhout. OOPSLA 2007, pages 57–76. Establishes that JVM performance results require confidence
    intervals over independent runs rather than point estimates, and that non-determinism between JVM
    invocations is a dominant error source. DOI [10.1145/1297027.1297033](https://doi.org/10.1145/1297027.1297033)
13. **`STATUS.md`**, this repository. The working record: the region-file comparison table, the
    light-engine tables, the stage decomposition, and the distinction between measured facts and
    facts established by reading source.
14. **`docs/anvil-chunk-loader.md`**, this repository. A second, independent run of the region-file
    comparison, and the note that the magnitude of Minestom's collapse under contention is not
    understood.
15. **`JMHSample_35_Profilers`**, OpenJDK JMH samples. On `-prof gc` and the normalised allocation
    counter `gc.alloc.rate.norm`.
    <https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples>
16. **JMH Visualizer**. Renders a JMH `results.json` in the browser; no upload, the file is read
    locally. <https://jmh.morethan.io/>
