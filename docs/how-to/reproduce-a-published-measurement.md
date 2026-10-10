# Reproduce a published measurement

Re-run one of the tables on [Reference Measured results](../reference/measured-results.md) on your own machine and compare. You end
with one result file per published table, recorded together with a description of your machine. This is
the procedure a third party uses to check a published claim.

**Before you start:** the benchmark jar ([How-to Run the JMH benchmark suite](run-the-jmh-benchmark-suite.md)). Read
[Explanation What the benchmarks establish](../explanation/what-the-benchmarks-establish.md) first — the threats to validity there decide what your
result means. What the published tables can and cannot be compared against is listed in
[Reference Measured results](../reference/measured-results.md#measurement-environment).

## Build the jar and list the benchmarks

1. Build the jar once and list every benchmark:

   ```bash
   ./gradlew :falco-benchmarks:jmhJar
   java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar -l    # list every benchmark
   ```

## Run the loader table

2. Run the loader under contention. This is one run per thread count — `-t` takes a single value, so the
   four rows of that table were produced by four separate runs:

   ```bash
   for t in 1 2 4 8; do
     java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
         "RegionFileComparisonBenchmark.(falco|minestom)(Read|Write)" \
         -p distinctStates=200 -f 1 -wi 3 -i 5 -t "$t" \
         -rf json -rff "loader-contention-t$t.json"
   done
   ```

3. Run the two-fork control for the four-thread row. This is the run the loader's claim rests on:

   ```bash
   java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
       "RegionFileComparisonBenchmark.(falco|minestom)Read" \
       -p distinctStates=200 -f 2 -wi 5 -i 10 -t 4 -rf json -rff loader-contention-2fork.json
   ```

## Run the light tables

4. Run the light engine at equal source brightness. This is the six-row table:

   ```bash
   java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
       "LightEngineComparisonBenchmark.(falco|minestom)" \
       -p emissionMix=UNIFORM -f 1 -wi 5 -i 10 -rf json -rff light-uniform.json
   ```

5. Run the light engine at mixed source brightness. `lightSources=1` is skipped because `MIXED` degenerates
   to `UNIFORM` there:

   ```bash
   java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
       "LightEngineComparisonBenchmark.(falco|minestom)" \
       -p emissionMix=MIXED -p lightSources=8,64 -f 1 -wi 3 -i 5 -rf json -rff light-mixed.json
   ```

6. Run the save stages, which show where a save spends its time. The annotation settings are used here; the
   original run's command line is unrecorded:

   ```bash
   java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
       ChunkSaveStageBenchmark -p distinctStates=200 -f 1 -wi 5 -i 5 \
       -rf json -rff save-stages.json
   ```

`-rf json -rff <file>` is what makes a run citable: JMH writes the JDK build, the VM name and version,
the JVM arguments, every parameter and every iteration into that file.
[JMH Visualizer](https://jmh.morethan.io/) reads it directly. A `./gradlew jmh` run writes the same
thing to `build/reports/jmh/results.json` instead.

If you want your numbers to be worth more than ours, raise the fork count. `-f 5` on any comparison
benchmark costs five times the wall clock and produces the only kind of interval that covers
run-to-run variance at all. Add `-prof gc` to anything you intend to quote as an allocation.

## Record your machine

7. Fill in the table below for your run, and keep the `results.json` of every run above. None of this is
   filled in for the published tables, and no value below may be guessed at from the outside. A
   replication that records it is more useful than the original. Leave a field as TBD rather than guessing;
   a wrong value is worse than a missing one.

   | | |
   | --- | --- |
   | CPU | model, stepping, physical cores, SMT on or off |
   | Frequency policy | turbo on or off, governor or P-state driver |
   | RAM | size and speed |
   | Storage and file system | both region benchmarks touch it |
   | OS and kernel | distribution and version |
   | JVM | vendor, full version string, GC, all flags |
   | Machine state | idle or not, and what else was running |
   | Commit | the Falco commit, and the Minestom version `mycelium-bom` resolved |
   | Run date | per table |
   | Raw results | the `results.json` of every run above |

## Compare with the published table

8. Compare your ratios with the published ones. Absolute microseconds are not expected to agree. The
   criteria for *supported*, *no difference resolvable* and *not usable* rows, and what a disagreement means,
   are stated in advance in
   [Explanation What a measurement here means](../explanation/what-a-measurement-here-means.md#what-agreement-would-look-like).

## Check it worked

Each run wrote its `results.json`, and the summary of each run has a `±` column. A run without one was not a
measurement, as [How-to Run the JMH benchmark suite](run-the-jmh-benchmark-suite.md#check-it-worked) says.

See also: [Reference Measured results](../reference/measured-results.md) for the table you are reproducing and its provenance ·
[Explanation What a measurement here means](../explanation/what-a-measurement-here-means.md) for what the `±` bounds and what it does not
