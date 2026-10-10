# Run the JMH benchmark suite

Execute the benchmarks in `falco-benchmarks` on your own machine. You end with JMH's summary table and a
`results.json` for the benchmarks you ran. A normal `build` never runs them.

**Before you start:** a working build ([How-to Build Falco from source](build-falco-from-source.md)). What the results can and
cannot mean is [Explanation What the benchmarks establish](../explanation/what-the-benchmarks-establish.md) — read it before quoting a number.

## Run the full suite

1. Run every benchmark with the full settings:

   ```bash
   ./gradlew jmh                      # every benchmark, full settings
   ```

   How many methods and configurations that is, and how long it takes, is in
   [Reference Benchmark catalogue](../reference/benchmark-catalogue.md#size-of-the-full-run).

## Or run it from CI

2. Use the **Benchmark** workflow when the run record matters more than the machine. It runs the same jar on
   demand and uploads the results with the exact command line. Choose the `custom` profile for one table or
   `full` for the whole suite, and leave `forks` empty to keep each class annotation. Its profiles, its
   provenance purpose and the rule that a shard is a whole class are in
   [Contributing Benchmarks and demo](../contributing/benchmarks-and-demo.md#the-ci-benchmark-workflow).

   CI figures come from a shared runner and may not be merged with the published tables. The reason is in
   [Explanation What a measurement here means](../explanation/what-a-measurement-here-means.md#the-limits-stated-without-softening).

## Restrict the run to what you are working on

3. The full run is not what you want during development. Restrict it to one class, one method or a regex:

   ```bash
   # One class
   ./gradlew jmh -Pjmh.include='BitPackerBenchmark'

   # One method
   ./gradlew jmh -Pjmh.include='ChunkSaveStageBenchmark.codec'

   # A regex over several
   ./gradlew jmh -Pjmh.include='light\..*Propagator.*'
   ```

4. Read the two files the Gradle task writes. Both land under `build/`, which is gitignored:

   | File | Content |
   | --- | --- |
   | `build/reports/jmh/human.txt` | the console output |
   | `build/reports/jmh/results.json` | machine readable, for [JMH Visualizer](https://jmh.morethan.io/) |

   No run's output is committed in this repository, which is why every provenance line in the published
   tables says so.

## Run the jar directly

5. The task builds a self-contained benchmark jar, which is the faster way to iterate because it skips
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

## Profile a benchmark

6. Profile allocation first. `-prof gc` is the one worth reaching for first, and why it answers allocation
   claims more reliably than timing is explained in
   [Explanation What the benchmarks establish](../explanation/what-the-benchmarks-establish.md#threats-to-validity).

   ```bash
   java -jar build/libs/falco-*-jmh.jar 'PaletteDataBenchmark.encode' -prof gc
   java -jar build/libs/falco-*-jmh.jar 'PaletteDataBenchmark.encode' -prof perfasm   # Linux, needs perf
   ```

## Why build does not run them

The benchmarks are not part of `./gradlew build`, `check` or `test`; they run only when asked for by name, and
`compileJmhJava` is part of `build` on purpose. The build wiring is in
[Contributing Benchmarks and demo](../contributing/benchmarks-and-demo.md#falco-benchmarks).

## Check it worked

JMH prints its own summary table at the end of the run, with a mean and a half-width per row. A run
that printed no `±` column was not a measurement.

## If it does not work

On Java 25, JMH 1.37 warns about `sun.misc.Unsafe::objectFieldOffset` being terminally deprecated.
It is harmless and comes from JMH itself.

See also: [How-to Reproduce a published measurement](reproduce-a-published-measurement.md) to re-run a specific published table ·
[Reference Benchmark catalogue](../reference/benchmark-catalogue.md) for every class and its parameters
