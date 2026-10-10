# Benchmarks and demo

The build wiring of the two modules that are never published: why they exist as separate modules,
what depends on what, and which tasks they add. This page is about the build files only. What the
benchmarks measure, under which conditions, and what the numbers do and do not license anyone to
conclude is in [What the benchmarks establish](../explanation/what-the-benchmarks-establish.md) and [What a measurement here means](../explanation/what-a-measurement-here-means.md) —
nothing on this page restates a measurement, and no number here is a result.

The two files are
[`falco-benchmarks/build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-benchmarks/build.gradle.kts)
and
[`falco-demo/build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-demo/build.gradle.kts).

## Neither module is published

This is a build fact, checkable in one place. The root build's `publishedModules` list names
`falco-anvil`, `falco-light`, `falco-instance` and `falco-bom`, and `maven-publish` is applied only
to that list (see [Publishing](publishing.md)). `falco-benchmarks` and `falco-demo` are absent from it,
so neither has a `publish` task at all and `./gradlew publish` at the root cannot reach them —
no task exclusion, no `enabled = false`, nothing to keep in sync. Both modules say so in their own
`description`, which is the string that would appear in a POM if one were ever generated.

The same holds for what they pull in: the JMH Gradle plugin, `jmh-core`, the SLF4J binding and
Minestom-at-runtime all live in these two modules and in no published one.

A third module is unpublished for the same reason and is not covered by this page: `falco-archunit`
holds the architecture rules over the other modules and has no `main` sources at all. See
[Architecture Rules](architecture-rules.md).

## `falco-benchmarks`

The module exists because a benchmark that spans both libraries has nowhere else to live.
`ScalingBenchmark` imports `BitPacker` and `PaletteData` from `falco-anvil` and
`ChunkLightPropagator`, `LightNibbles` and `SectionOpacity` from `falco-light` in one class, and the
support classes `BenchmarkConstants` and `SectionStates` are used from both the `benchmark.anvil` and
the `benchmark.light` packages. With a `jmh` source set inside each library module, those would
either have to be duplicated or the two library modules would have to depend on each other just for
benchmarking. Keeping the benchmarks in their own module also keeps the `jmh` Gradle plugin, and
everything it pulls in, out of the published artefacts entirely.

```kotlin
dependencies {
    jmhImplementation(platform(libs.mycelium.bom))
    jmhImplementation(platform(libs.adventure.bom))
    jmhImplementation(project(":falco-anvil"))
    jmhImplementation(project(":falco-light"))
    jmhImplementation(libs.adventure.nbt)
    jmhImplementation(libs.annotations)
    jmhImplementation(libs.jmh.core)
    jmhImplementation(libs.minestom)
    jmhImplementation(libs.fastutil)
}
```

Every dependency is on the `jmh` configuration, so nothing here reaches the `main` or `test`
classpath of this module, let alone another one. Minestom is `implementation`-style rather than
`compileOnly` — the opposite of the library modules (see [Dependency
Management](dependency-management.md)) — because the comparison benchmarks execute Minestom's own code
rather than compiling against it. The Adventure platform is imported for the same reason: benchmark
code runs, so it needs Adventure on the runtime classpath, which the library modules never do.

Note that `falco-instance` is *not* a dependency here: no benchmark measures it.

No JMH annotation processor is declared, deliberately. The JMH Gradle plugin already generates the
benchmark harness classes with its own bytecode generator; declaring the annotation processor as well
would make both mechanisms emit the same classes, leaving the jar with two copies of every benchmark.

```kotlin
jmh {
    jmhVersion.set(libs.versions.jmh)
    includeTests.set(false)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh/human.txt"))

    val include = providers.gradleProperty("jmh.include").orNull

    if (include != null) {
        includes.set(listOf(include))
    }
}
```

`jmhVersion` is pinned from the catalog rather than left to the plugin's default, so the harness that
runs is the JMH version this repository names — `1.37` today (see [Dependency
Management](dependency-management.md)).

`includeTests` is off so that what the plugin scans and puts on the JMH classpath is exactly
`src/jmh/java` and its `jmh` configuration. This module has no `src/test/java` at all, so the setting
changes nothing today; it is there to keep a test source set added later out of a benchmark run
rather than to fix a problem that exists.

A full `./gradlew :falco-benchmarks:jmh` run executes every `@Benchmark` method at every combination
of its `@Param` values — the inventory is in [What the benchmarks establish](../explanation/what-the-benchmarks-establish.md) — which is far more than
anybody wants while working on one of them. `-Pjmh.include='BitPackerBenchmark.pack'` narrows the run
to a regular expression without editing this file. The two output files land under
`falco-benchmarks/build/reports/jmh/`; neither is committed to the repository, which is why
[What a measurement here means](../explanation/what-a-measurement-here-means.md) lists the raw run records as a reproducibility gap
rather than pointing at a file here.

Finally, the benchmark sources are compiled by a normal build and executed by none:

```kotlin
tasks {
    named("check") {
        dependsOn(named("compileJmhJava"))
    }
}
```

A benchmark that stopped compiling after a refactoring elsewhere in the codebase should fail the
build like any other source set would. Nothing in the build depends on the `jmh` task itself, so
`./gradlew build` never runs a benchmark and a published artefact never contains one.

## The CI benchmark workflow

The **Benchmark** workflow
([`.github/workflows/benchmark.yml`](https://github.com/OneLiteFeatherNET/Falco/blob/main/.github/workflows/benchmark.yml))
runs the same jar on demand and uploads four things: `results.json`, the readable output, **the exact
command line as executed**, and a filled-in provenance line. Two profiles:

| | What it does |
| --- | --- |
| `custom` | one JMH filter in one job — for re-running a single table |
| `full` | the whole suite across sixteen shards, about half an hour where a sequential run is five |

Its purpose is provenance, not precision. Many of the gaps listed in
[Reference Measured results](../reference/measured-results.md) are of the form *no run record was kept* rather
than *the number is wrong*, and those close with a run that documents itself even from a machine that measures
worse. Leave `forks` empty to keep each class annotation, which is what re-running a published table needs.

The workflow enforces one rule rather than advising it: **a shard is a whole class.** Shards run on separate
runners, so splitting a comparison class would put `falcoRead` and `minestomRead` on different CPUs and the
resulting ratio would measure the hardware. That happened once, at 2.13× against the 1.13× one machine gives.

A shared runner is not the machine the published tables came from, and the difference is not only in absolute
terms: the same light comparison measures 1.35× there against 1.13× here. CI figures therefore belong in their
own table with their own provenance line, never merged into one measured elsewhere; the reasoning is in
[Explanation What a measurement here means](../explanation/what-a-measurement-here-means.md#the-limits-stated-without-softening).

## `falco-demo`

`falco-demo` is a runnable comparison of the Falco and the Minestom chunk loader on the user's own
world. It is deliberately absent from the root build's publish list, for the same reason as
`falco-benchmarks`: it is a tool for someone who wants to check the loader's behaviour on their own
hardware, not something a consumer of the library should ever resolve as a dependency. What a run of
it produces is not a JMH result and is not comparable with anything in
[What the benchmarks establish](../explanation/what-the-benchmarks-establish.md).

```kotlin
dependencies {
    implementation(platform(libs.mycelium.bom))
    implementation(project(":falco-anvil"))
    implementation(project(":falco-light"))
    implementation(project(":falco-instance"))
    implementation(libs.minestom)
    implementation(libs.slf4j.api)

    compileOnly(libs.annotations)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
```

Unlike the library modules, where Minestom is `compileOnly` because the consumer picks the version
(see [Dependency Management](dependency-management.md)), here it is the opposite: this module starts a
real server and calls the loader Minestom ships with, so it needs Minestom on the runtime classpath,
which `implementation` provides. `slf4j-simple` is the binding the library modules deliberately do
not choose for anybody, taken here as `runtimeOnly` because the demo is the one thing in this
repository that is actually run by a user.

`falco-light` is included because the Falco server hands its instance a `FalcoLightingChunk` through
`ChunkLightScheduler` — the half of the stack a pure loading comparison cannot show, and whether the
light of a streamed-in chunk looks right is usually the first thing anybody notices while flying
around. `falco-instance` is included even though nothing in the demo runs it: `ServerStack` imports
`FalcoInstance` so it can name the type in its Javadoc and in the log line that explains why the
demo leaves it out, and a reader following that explanation should land on the real type rather than
on a string that was accurate only when it was written.

### The measurement tasks

```kotlin
tasks.register<JavaExec>("runFalcoLoader") { configureDemo("falco") }
tasks.register<JavaExec>("runMinestomLoader") { configureDemo("minestom") }
```

Both run the identical main class, `net.onelitefeather.falco.demo.ChunkLoadDemo`, with the identical
configuration; the `--loader` value is the only thing that differs. Anything else would make the
comparison meaningless, so the shared configuration lives in one place (`configureDemo`) and the two
task registrations only pass a different argument.

A few details inside `configureDemo` are worth calling out:

- The JVM used for the run is pinned explicitly through a `JavaToolchainService` launcher at
  language version 25, rather than whatever JVM happens to run the Gradle daemon, so the run doesn't
  carry an unreported variable.
- The world directory is passed as an explicit system property (`falco.demo.world`) rather than
  relied upon via the working directory, so the demo doesn't silently depend on how it was launched
  (a run started from an IDE would otherwise look in a different place).
- Options such as `threads`, `chunks`, `warmup`, `rounds`, and `dimension` are read as Gradle
  properties through a `CommandLineArgumentProvider`, so a run reads
  `./gradlew :falco-demo:runFalcoLoader -Pthreads=8`. A property that is not set contributes no
  argument at all, leaving the demo's own defaults in charge.
- `isIgnoreExitValue` is deliberately left at its default (`false`) for the measurement tasks.
  `ChunkLoadDemo.main` ends every case it handles itself — an unparseable command line, a missing
  world, a world whose region files mark no chunk as written — with a printed explanation and a plain
  return, which exits zero; the path that completes a measurement ends with an explicit
  `System.exit(0)`, because the Minestom registries start threads that would otherwise keep the JVM
  alive after the report. A non-zero exit status therefore really is a defect and should fail the
  build rather than scroll past unnoticed.

### The server tasks

```kotlin
tasks.register<JavaExec>("runFalcoServer") { configureServer("falco") }
tasks.register<JavaExec>("runMinestomServer") { configureServer("minestom") }
```

This is the second half of the module: a joinable server somebody can judge by eye, sharing the world
directory, toolchain, and option style with the measurement tasks above — for the same reason those
two share theirs. The comparison is only meaningful if the two servers differ in the stack
(`--stack=falco` against `--stack=minestom`) and in nothing else, so everything except `--stack` is
configured once in `configureServer`.

The view distance is set as a system property, not a command-line option read by the demo's own
argument parser:

```kotlin
val viewDistance = providers.gradleProperty("viewDistance").orElse("10")
...
systemProperty("minestom.chunk-view-distance", viewDistance.get())
```

This is forced by Minestom itself. At `2026.06.20-26.1.2`, `server/ServerFlag.java:17` declares
`public static final int CHUNK_VIEW_DISTANCE = intProperty("minestom.chunk-view-distance", 8)` — a
`static final` field read once when the class is initialised, which happens before `main()` gets a
chance to apply anything. A `-PviewDistance` value that only reached the demo's own option parser
would be logged correctly while the server itself silently used the default of 8.

Unlike the measurement tasks, `isIgnoreExitValue` is set to `true` for the server tasks. The normal
way to stop a long-running server is a signal (Ctrl-C), and a signal leaves the JVM exit code at 130
or 143 regardless of how cleanly the process shut down. Without ignoring that exit value, every
session stopped with Ctrl-C would end in a red "BUILD FAILED" printed underneath the shutdown lines
that actually say the loader closed properly. A genuine crash still prints its stack trace to the
same console, which is the output anyone watching a running server would be reading anyway, so
nothing is lost by ignoring the exit code here.

Both server tasks also fix the SLF4J Simple output format — level `info`, timestamps as `HH:mm:ss` —
through three more system properties, so two sessions logged side by side are comparable line for
line.

### Why the extensions are looked up by type

`falco-demo/build.gradle.kts` accesses `sourceSets` and `javaToolchains` through
`extensions.getByType<...>()` rather than through Kotlin's generated type-safe accessors:

```kotlin
val mainSourceSet = extensions.getByType<SourceSetContainer>().named("main")
val toolchains = extensions.getByType<JavaToolchainService>()
```

The `java-library` plugin is applied to this module from the root build's `subprojects` block (see
[Build Setup](build-setup.md)) rather than from a `plugins { }` block in this file, and relying on
accessor generation across that boundary is exactly the kind of thing that silently breaks on a
Gradle upgrade.
