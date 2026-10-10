# Reproduce a published measurement

Re-run one of the tables on [Reference Measured results](../reference/measured-results.md) on your own machine and compare. This is
the procedure a third party uses to check a published claim.

**Before you start:** the benchmark jar ([How-to Run the JMH benchmark suite](run-the-jmh-benchmark-suite.md)). Read
[Explanation What the benchmarks establish](../explanation/what-the-benchmarks-establish.md) first — the threats to validity there decide what your
result means.

Everything above can be re-performed. It cannot yet be reproduced exactly, because the environment of
the original runs was not recorded — that gap is listed below rather than papered over. What follows
is enough for a third party to run the same configurations and say whether their machine agrees.

Build the jar once:

```bash
./gradlew :falco-benchmarks:jmhJar
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar -l    # list every benchmark
```

Then, one command per published table. These are the configurations the tables above state; where a
table's run settings were not recorded, the class annotation is used and the command says so.

```bash
# Loader under contention. One run per thread count -- -t takes a single value, so this is four
# separate runs, and the four rows of that table were produced that way.
for t in 1 2 4 8; do
  java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
      "RegionFileComparisonBenchmark.(falco|minestom)(Read|Write)" \
      -p distinctStates=200 -f 1 -wi 3 -i 5 -t "$t" \
      -rf json -rff "loader-contention-t$t.json"
done

# The two-fork control for the four-thread row. This is the run the loader's claim rests on.
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    "RegionFileComparisonBenchmark.(falco|minestom)Read" \
    -p distinctStates=200 -f 2 -wi 5 -i 10 -t 4 -rf json -rff loader-contention-2fork.json

# Light engine, equal source brightness. The six-row table.
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    "LightEngineComparisonBenchmark.(falco|minestom)" \
    -p emissionMix=UNIFORM -f 1 -wi 5 -i 10 -rf json -rff light-uniform.json

# Mixed source brightness. lightSources=1 is skipped because MIXED degenerates to UNIFORM there.
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    "LightEngineComparisonBenchmark.(falco|minestom)" \
    -p emissionMix=MIXED -p lightSources=8,64 -f 1 -wi 3 -i 5 -rf json -rff light-mixed.json

# Where a save spends its time. Annotation settings; the original run's command line is unrecorded.
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

## What to record about your machine

None of this is filled in for the published tables, and no value below may be guessed at from the
outside. A replication that records it is more useful than the original.

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

<!-- TODO(maintainer): fill the same table in for the published runs, from the machine they came
     from, and commit the results.json files. Leave a field as TBD rather than guessing — a wrong
     value is worse than a missing one. The only facts currently recorded anywhere are: 16 cores,
     machine not idle, JMH 1.37, Java 25 toolchain, Minestom 2026.06.20-26.1.2. -->

## What agreement would look like

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
A fifth that behaves differently from any of them is worth hearing about.


See also: [Reference Measured results](../reference/measured-results.md) for the table you are reproducing and its provenance ·
[Explanation What a measurement here means](../explanation/what-a-measurement-here-means.md) for what the `±` bounds and what it does not
