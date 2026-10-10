# Run the JMH benchmark suite

Execute the benchmarks in `falco-benchmarks` on your own machine. A normal `build` never runs them.

**Before you start:** a working build ([How-to Build Falco from source](build-falco-from-source.md)). What the results can and
cannot mean is [Explanation What the benchmarks establish](../explanation/what-the-benchmarks-establish.md) — read it before quoting a number.

```bash
./gradlew jmh                      # every benchmark, full settings
```

That is every measured method for every combination of the parameters its state class declares — 50
methods over 488 configurations at commit `ca79507`, at the forks and iterations the annotations ask
for. Budget well over an hour and do not touch the machine while it runs. The count is dominated by
`ScalingBenchmark`, whose fifteen section counts and five distinct-state counts share one state class
and therefore form a full cross product of 300 configurations on their own.

## Or from CI, when the run record matters more than the machine

The **Benchmark** workflow
([`.github/workflows/benchmark.yml`](https://github.com/OneLiteFeatherNET/Falco/blob/main/.github/workflows/benchmark.yml))
runs the same jar on demand and uploads four things: `results.json`, the readable output, **the exact
command line as executed**, and a filled-in provenance line. Two profiles:

| | What it does |
| --- | --- |
| `custom` | one JMH filter in one job — for re-running a single table |
| `full` | the whole suite across sixteen shards, about half an hour where a sequential run is five |

**Its purpose is provenance, not precision.** Many of the gaps listed on this page are of the form
*no run record was kept* rather than *the number is wrong*, and those close with a run that documents
itself even from a machine that measures worse. Leave `forks` empty to keep each class annotation,
which is what re-running a published table needs.

**What its numbers may not be used for.** A shared runner is not the machine the published tables
came from, and the difference is not only in absolute terms: the same light comparison measures 1.35×
there against 1.13× here. A ratio is no more portable than an absolute figure, only more stable when
the hardware is fixed — see
[What a measurement here means](../explanation/what-a-measurement-here-means.md#the-limits-stated-without-softening). CI figures
therefore belong in their own table with their own provenance line, never merged into one measured
elsewhere.

One thing the workflow enforces rather than advises: **a shard is a whole class.** Shards run on
separate runners, so splitting a comparison class would put `falcoRead` and `minestomRead` on
different CPUs and the resulting ratio would measure the hardware. That happened once, at 2.13×
against the 1.13× one machine gives.

The full run is not what you want during development. Restrict it to what you are working on:

```bash
# One class
./gradlew jmh -Pjmh.include='BitPackerBenchmark'

# One method
./gradlew jmh -Pjmh.include='ChunkSaveStageBenchmark.codec'

# A regex over several
./gradlew jmh -Pjmh.include='light\..*Propagator.*'
```

The Gradle task writes two files:

| File | Content |
| --- | --- |
| `build/reports/jmh/human.txt` | the console output |
| `build/reports/jmh/results.json` | machine readable, for [JMH Visualizer](https://jmh.morethan.io/) |

Both land under `build/`, which is gitignored. **No run's output is committed in this repository**,
which is why every provenance line above says so.

## Running the jar directly

The task builds a self-contained benchmark jar, which is the faster way to iterate because it skips
Gradle entirely and accepts every JMH option:

```bash
./gradlew jmhJar
java -jar build/libs/falco-*-jmh.jar 'BitPackerBenchmark.pack' -f 1 -wi 1 -i 1

java -jar build/libs/falco-*-jmh.jar -l          # list every benchmark
java -jar build/libs/falco-*-jmh.jar -h          # every option
```

A quick smoke run — one fork, one warmup iteration, one measurement iteration — is `-f 1 -wi 1 -i 1`.
That is enough to prove a benchmark executes and produces a plausible number. It is **not** enough
to compare two versions of the code; for that, drop the overrides and let the annotations decide.

JMH allows one instance at a time. A crashed run leaves `/tmp/jmh.lock` behind and every later run
fails with *"Another JMH instance might be running"*; delete the file.

## Profiling a benchmark

```bash
java -jar build/libs/falco-*-jmh.jar 'PaletteDataBenchmark.encode' -prof gc
java -jar build/libs/falco-*-jmh.jar 'PaletteDataBenchmark.encode' -prof perfasm   # Linux, needs perf
```

`-prof gc` is the one worth reaching for first. Several of the claims on the other pages are about
*allocation*, not about time, and `gc.alloc.rate.norm` answers those directly — and does so far more
reproducibly than any timing here.


## Why `build` does not run them

The benchmarks do not run during `./gradlew build`, `check` or `test`. `jmh` and `jmhJar` are only
reachable when asked for by name. A benchmark run takes long enough that wiring it into the normal
build would make every commit painful, and JMH numbers are too noisy on a shared CI runner to gate
anything on them anyway.

Verify it for yourself:

```bash
./gradlew clean build --dry-run | grep -i jmh    # prints nothing except the compile task
```

`compileJmhJava` *is* part of `build`, and that is on purpose: a benchmark that no longer compiles
after a refactoring should break the build like any other source set.


## Check it worked

JMH prints its own summary table at the end of the run, with a mean and a half-width per row. A run
that printed no `±` column was not a measurement.

## If it does not work

On Java 25, JMH 1.37 warns about `sun.misc.Unsafe::objectFieldOffset` being terminally deprecated.
It is harmless and comes from JMH itself.

See also: [How-to Reproduce a published measurement](reproduce-a-published-measurement.md) to re-run a specific published table ·
[Reference Benchmark catalogue](../reference/benchmark-catalogue.md) for every class and its parameters
