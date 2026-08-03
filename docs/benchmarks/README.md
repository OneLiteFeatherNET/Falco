# Benchmark baselines

This directory holds the JMH result files every published figure of this project is drawn from, and
the record of the machine each of them was taken on. It is the durable half of `falco-benchmarks`:
the benchmark sources say what is measured and why, these files say what came out.

## Why the results do not live in `build/`

The `jmh` block of `falco-benchmarks/build.gradle.kts` writes to
`build/reports/jmh/results.json` by default, and `./gradlew clean` deletes it. A baseline that a
routine clean removes is not a baseline — the next run has nothing to be compared against, and the
figure in the README turns back into a claim. Result files therefore land here, under version
control, next to the conditions that produced them.

Two Gradle properties make the Gradle path write here as well, so a run started with
`./gradlew :falco-benchmarks:jmh` does not have to be repeated through the jar to be kept:

```
./gradlew :falco-benchmarks:jmh \
    -Pjmh.include=SectionAllocationBenchmark \
    -Pjmh.forks=3 \
    -Pjmh.resultsFile=docs/benchmarks/baseline-2026-08-01/SectionAllocationBenchmark.json \
    -Pjmh.humanFile=docs/benchmarks/baseline-2026-08-01/SectionAllocationBenchmark.human.txt
```

Both paths are resolved against the repository root, and an absolute path is taken as it is.
`-Pjmh.forks` exists alongside them because the classes carry `@Fork(1)` and the Gradle path would
otherwise silently produce a single-fork result while the script produces a three-fork one. It is
applied after `-Pjmh.quick`, so passing both leaves the fork count where `-Pjmh.forks` puts it.

## Layout

```
docs/benchmarks/
  full-run.sh                        the script that produces a baseline
  baseline-<yyyy-mm-dd>/
    conditions.txt                   machine, JVM, commit, configuration, idle answer
    <BenchmarkClass>.json            JMH result, one file per class
    <BenchmarkClass>.human.txt       the printed transcript of the same run
    SetBlockContentionBenchmark-t<N>.json    one file per thread count
```

**One file per benchmark class, never one shared file.** JMH rewrites `-rff` completely on every
invocation rather than appending to it, so a second run into the same path destroys the first. That
is not a hypothetical: the scouting run of 2026-08-01 pointed six invocations at the single
`build/reports/jmh/results-quick.json` the build configures, and only the last of the six survived
in it. The same applies to the thread sweep of `SetBlockContentionBenchmark`, where five processes
run in a row and the fifth would otherwise be the only one left.

## Running a baseline

```
docs/benchmarks/full-run.sh --dry-run     # print every command and the time estimate
docs/benchmarks/full-run.sh               # about 2 h 35 min
```

The script derives its estimate per benchmark class and prints the derivation in its header. Read
it before starting: this is a run measured in hours, not minutes, and it needs the machine to
itself for all of them. It refuses to start above a one minute load average of 1.5 for that reason.
A Gradle build, an IDE indexing pass or a second agent compiling in the same checkout is enough to
change the numbers, and none of it is visible afterwards in the result file.

The script builds the benchmark jar once with Gradle and then runs each measurement as a plain
`java -jar`. That is deliberate. Driving the measurements through `./gradlew :falco-benchmarks:jmh`
keeps a Gradle daemon alive next to every forked measurement JVM, competing for the same cores; the
jar path leaves exactly one JVM running while a benchmark is being measured.

## Comparing a later run against a baseline

Point the new run at a new file, never at the old one:

```
./gradlew :falco-benchmarks:jmhJar
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    'ChunkComparisonBenchmark' -e '\.(minestomCopy|falcoCopy)$' \
    -f 3 -wi 5 -i 5 -prof gc -foe true \
    -rf json -rff /tmp/chunk-comparison-candidate.json
```

Then read the two files side by side — [JMH Visualizer](https://jmh.morethan.io/) takes several at
once — and read `conditions.txt` first. A comparison across two machines, two JVM builds or two
governor settings is not a comparison. Nothing in the JSON warns about it; the conditions file is
the only place that information exists.

## What is in a baseline and what is not

The run covers six benchmark classes at three forks, five warmup and five measurement iterations of
one second, with `-prof gc`. One thing is deliberately kept out of it, and one thing is deliberately
filed under a name that says it may not be quoted.

**`ChunkComparisonBenchmark.minestomCopy` and `.falcoCopy` are not part of the baseline.** They are
documented in their own javadoc as non-comparable: constructing a chunk for an `InstanceContainer`
leaves an entry in a viewer cache that nothing removes and that no later key ever matches, so
`minestomCopy` reports a copy plus a hash map that grows for the length of the trial.
`ChunkViewerCacheLeakTest` establishes the mechanism. The comparable pair is
`minestomCopyIsolated` against `falcoCopyIsolated`, and it is in the baseline.

The scouting run shows why the two arms cannot be a baseline even taken on their own terms. Their
per iteration values rise during the measurement — at `distinctStates = 64` the three iterations of
the `minestomCopy` fork read 296, 313 and 364 µs/op, and at 1024 they read 282, 304 and 390 µs/op,
a rise of 23 % and 38 % — while every control arm on the same fork is flat to within 2 %
(`falcoSetBlock` at 1024: 106.86, 107.19, 106.64). A quantity that grows while it is being measured
does not have a value; it has a slope, and the mean printed for it is a function of how long the
iteration ran. Changing `-i` changes the answer.

Their allocation column, in contrast, is exact and is worth having: `minestomCopy` minus
`falcoCopy` in `gc.alloc.rate.norm` was 257.1, 257.3 and 257.3 B/op at `distinctStates` 1, 64 and
1024 — constant across the axis, at an error below 0.6 B. That is the per copy cost of the leak, it
is the same whatever the chunk holds, and it is the number worth publishing about this pair.

The two arms are therefore run, once, under `--with-leak-arms`, into a file named
`ChunkComparisonBenchmark-viewer-cache-leak-NOT-A-BASELINE.json`. The name is the warning, because
the file will outlive the conversation that produced it.

## What has to be recorded next to a number

`conditions.txt` is written by the script and answers every field
[the performance report form](../../.github/ISSUE_TEMPLATE/performance-report.yml) asks reporters
for: CPU model and core count, the JMH thread count, the JVM vendor and version, the operating
system, the Falco commit, the exact configuration, and whether the machine was idle. The last one
is the only field the script cannot fill in, and it is left as an open question at the end of the
file. Answer it before quoting anything from the run. The figures currently on the wiki's Project
Status page were taken on a machine that was not idle and say so, which is the only reason they are
still usable.

## The tests of this module do not run on macOS

`:falco-benchmarks:test` is skipped on macOS and only there. Everything else in the repository runs
on all three runners as before; this module is the exception, and Gradle prints the reason next to
the `SKIPPED` marker rather than passing over it silently.

**What was observed.** On 2026-08-03 the macOS job of both open pull requests stopped in
`:falco-benchmarks:test` and never came back. The other five modules — instance, light, anvil, demo,
archunit — completed and wrote all 85 result files; this module wrote
`in-progress-results-generic.bin` and `output-events.bin` at zero bytes, meaning the test JVM had
been started and no test had reported anything at all. The job was silent for 31 minutes before it
was cancelled, and the runner then terminated four orphan `java` processes. The same commit builds
in 3m30s on ubuntu and 5m10s on windows, and the same macOS runner builds `main` green in 2m1s, so
neither the runner nor the workflow is what differs.

**Why this module and no other.** It is the only one whose test JVM is started with
`-Djdk.attach.allowAttachSelf=true`, `-XX:+EnableDynamicAgentLoading`, `-Djol.magicFieldOffset=true`
and an explicit `UseCompactObjectHeaders` setting, and with a 4 GB heap on a runner that has 7 GB.
Those exist because jol measures retained size by attaching to its own VM. Which of them is the one
that hangs on arm64 has not been established — the module is excluded, the cause is not diagnosed,
and this paragraph says so rather than implying otherwise.

**What is given up.** These are the tests that carry the central claim of the storage work:
`ChunkFootprintTest` measures the 25 objects and 840 bytes of a fresh chunk, `PaletteFootprintTest`
the palette break-even, `FalcoChunkEquivalenceTest` the behavioural equality against Minestom. They
keep running on ubuntu and windows in every pull request, so the claim stays covered on two of three
platforms — but a regression that only shows on arm64 would now pass unnoticed. The figures were
never platform independent to begin with: retained size depends on the object header layout, which
is what `UseCompactObjectHeaders` switches, so a number taken on arm64 was never interchangeable
with the published one.

**To run them on macOS anyway**, for instance to work on the hang:

```bash
./gradlew :falco-benchmarks:test -Pfalco.macOsFootprintTests
```

The property forces the task on regardless of the operating system. Expect it to hang until the
cause is found.
