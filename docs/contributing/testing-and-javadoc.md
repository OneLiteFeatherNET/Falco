# Testing and Javadoc

How to reproduce a test run, what the `-Werror` doclint policy actually enforces and where it
applies, and why the test heap is pinned. All of it is configured in the root
[`build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/build.gradle.kts) and
reaches every subproject except `falco-bom`, which has no sources to test or document (see [Build
Setup](build-setup.md)). What the tests assert is not on this page; the per-module test counts and what
they cover are in [Project Status](../project-record/project-status.md).

## Reproducing a test run

```
./gradlew test     # compile and run the tests
./gradlew check    # the above, plus the per-module verification wired in below
./gradlew build    # the above, plus assembling the artefacts
```

`check` is the interesting one, because what it pulls in differs per module:

| Module | What `check` runs beyond `test` |
| --- | --- |
| `falco-anvil`, `falco-light`, `falco-instance` | `jacocoTestReport`, as a finalizer of `test`, and `checkApiCompatibility` against the last release (see [Build Setup](build-setup.md#binary-compatibility-against-the-last-release)) |
| `falco-demo` | `jacocoTestReport`, and `javadoc` — wired in explicitly, see below |
| `falco-benchmarks` | `compileJmhJava` — the benchmarks are compiled, never executed (see [Benchmarks and Demo](benchmarks-and-demo.md)) |
| `falco-archunit` | `jacocoTestReport`, over a module whose only source set is `test` (see [Architecture Rules](architecture-rules.md)) |
| `falco-bom` | nothing; a `java-platform` project has no test task |

`falco-archunit` is where `check` earns the most per second it costs. Its 39 tests import the
compiled classes of the other modules and assert properties of them, so they take no server, no
region file and no measurable time, and they catch a class of mistake the other modules structurally
cannot — a `public` method carrying a package-private type compiles and passes every test that lives
in the same package.

The run is reproducible in the sense that matters for a build: the JDK is not whichever one launched
Gradle. The root build pins a Java 25 toolchain and `options.release = 25` (see [Build
Setup](build-setup.md)), and `.github/workflows/build-pr.yml` requests Temurin 25 from the shared
`gradle-build-pr.yml@v2.4.0` workflow with `run-tests: true`, so a pull request runs the same
compiler and the same test JVM version a local `./gradlew check` does.

Every test JVM is started with one system property:

```kotlin
jvmArgs("-Dminestom.inside-test=true")
```

It is Minestom's own flag, not one this project invents. At `2026.06.20-26.1.2` it is read into
`ServerFlag.INSIDE_TEST` (`server/ServerFlag.java:79`) and has two effects the tests depend on: a
dynamic registry never freezes, because `DynamicRegistryImpl.canFreeze()` returns false while it is
set (`server/registry/DynamicRegistryImpl.java:328`), and `ConnectionManager` spawns a player
synchronously and drops its virtual-thread assertions (`server/network/ConnectionManager.java:174`,
`:192`, `:227`, `:354`). Without it a test that registers a block or spawns a player after
`MinecraftServer.init()` behaves differently from the same code in a test harness that sets it.

## What `-Werror` on doclint actually enforces

```kotlin
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}
```

This is the only Javadoc option the build sets. Everything else is the standard doclet's default, and
the defaults are what define the policy:

- **doclint is on with all groups** (`accessibility`, `html`, `missing`, `reference`, `syntax`) —
  the default of the standard doclet, not something this build enables.
- **the visibility level is `-protected`**, also the default. So the rule is *public and protected*
  API, not literally every member: a private field with no comment is not a doclint warning and does
  not fail anything.
- **`-Werror` turns every warning into an error**, so a missing `@param`, a broken `@link`, a
  malformed `<p>` or an undocumented public method fails the task and therefore the build.
- **only `main` is covered.** Gradle's `javadoc` task documents the main source set; there is no
  Javadoc task over `src/test/java` or `src/jmh/java`, so the policy says nothing about test or
  benchmark sources.

The convention is only as good as its enforcement: without `-Werror`, a missing comment produces a
doclint warning that shows up once in a CI log and is then never read again. Turning warnings into
build failures is what makes the rule hold in practice rather than in intent only.

## Which modules actually run Javadoc

`-Werror` is configured for every `Javadoc` task in every module that has one, but a task that never
runs enforces nothing. Whether an ordinary `./gradlew build` reaches it differs per module:

| Module | Javadoc runs during `build`? | Why |
| --- | --- | --- |
| `falco-anvil`, `falco-light`, `falco-instance` | yes | `withJavadocJar()` wires `javadoc` into `assemble` (see [Publishing](publishing.md)) |
| `falco-demo` | yes | `check` depends on `javadoc`, wired explicitly in its own build file |
| `falco-benchmarks` | **no** | neither mechanism applies; only `compileJmhJava` is added to `check` |
| `falco-archunit` | n/a | a `Javadoc` task exists but has no `main` sources to document |
| `falco-bom` | n/a | no Java plugin, so no `Javadoc` task exists |

`falco-demo` is never published, so it has no `withJavadocJar()`, and left alone the "Javadoc on
every public and protected element" convention would have held for the three library modules and
quietly not for it. `falco-demo/build.gradle.kts` restores it:

```kotlin
tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}
```

`falco-benchmarks` has no equivalent line, so its Javadoc is not checked by a normal build. That is a
gap, not a decision documented anywhere in the build.

## Test heap size

```kotlin
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    jvmArgs("-Dminestom.inside-test=true")
    testLogging {
        events("passed", "skipped", "failed")
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}
```

The chunk loader tests allocate payloads of roughly one mebibyte each to cover the external chunk
file path — `RegionFileConcurrencyTest` sizes them as `MAX_SECTORS_PER_CHUNK * SECTOR_SIZE + 1`,
one byte past what a region file's location entry can address. With `org.gradle.parallel=true` in
`gradle.properties`, several such test JVMs run alongside each other and alongside the Gradle daemon
during one build. Without an explicit heap size the test JVM can die from memory pressure under that
load, and the recorded reason for pinning it is that the failure surfaced misleadingly — as an
`EOFException` from a truncated read rather than as an out-of-memory error or a clean test failure.
Setting `maxHeapSize = "1g"` explicitly removes that flakiness.

<!-- TODO(maintainer): the EOFException failure mode is carried over from the original page and has
     no commit message, issue or test to cite. Either attach the reference or drop the clause and
     keep only "can die from memory pressure", which the payload size alone supports. -->

`maxHeapSize` is a ceiling applied to each test JVM separately, not to the build as a whole.

## Coverage

Every subproject that runs tests also finalizes them with a JaCoCo report:

```kotlin
tasks.withType<JacocoReport>().configureEach {
    dependsOn(tasks.withType<Test>())
    reports {
        xml.required.set(true)
        csv.required.set(true)
    }
}
```

The `finalizedBy` in the `Test` block and the `dependsOn` here are two halves of the same wiring:
the finalizer makes a report follow every test run, and the dependency makes an explicitly requested
`jacocoTestReport` run the tests first rather than report on stale execution data. XML and CSV are
requested because they are the formats a coverage service consumes; the HTML report stays on for
reading locally.
