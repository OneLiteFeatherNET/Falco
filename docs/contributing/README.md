# Contributing

What a change to Falco is built, tested and released with: the toolchain and library versions the
build declares, the Gradle commands that compile, test and benchmark it, the conventions the build
enforces, and what a push to `main` publishes. It covers neither the design of the loader and the
engine — that is [How the Anvil loader is built](../explanation/how-the-anvil-loader-is-built.md) and [How the light engine works](../explanation/how-the-light-engine-works.md) — nor any
measured result, which is [Measured results](../reference/measured-results.md). It is written for someone changing
the code rather than for someone consuming the published artefacts.

## Contents

- [Environment](#environment)
- [Working on this](#working-on-this)
- [Conventions](#conventions)
- [Releasing and snapshots](#releasing-and-snapshots)
- [Opening a pull request](#opening-a-pull-request)
- [References](#references)

What a first build needs beyond the commands below is documented on pages of its own rather than
repeated here. A Java 25 toolchain and OneLiteFeather Maven credentials are what `./gradlew build`
fails without, and both are in [Build Falco from source](../how-to/build-falco-from-source.md). What the build is
made of, module by module, is [Build Setup](build-setup.md). What a test run reports, what `-Werror` on
doclint actually enforces and how to repeat a run are in
[Testing and Javadoc](testing-and-javadoc.md#reproducing-a-test-run); the rules the `falco-archunit`
module checks on every build are in [Architecture Rules](architecture-rules.md).

---

## Environment

Everything in this table is declared in `settings.gradle.kts`, `build.gradle.kts` or
`gradle/wrapper/gradle-wrapper.properties` and is checkable there. The *Pinned here* column matters
for anyone reproducing a measurement: a version that is resolved rather than pinned can change under
a republication of the BOM without a single commit in this repository.

| | | Pinned here |
| --- | --- | --- |
| Java | 25 (toolchain and `release`), no `--enable-preview` anywhere | yes, in the root `build.gradle.kts` |
| Minestom | `2026.09.12-26.2`, `compileOnly` | **no** — `library("minestom", …).withoutVersion()`, resolved through `net.onelitefeather:mycelium-bom` `1.8.7` |
| Adventure | `5.2.0`, `adventure-nbt` used directly — Minestom speaks `CompoundBinaryTag` natively, so there is no conversion layer | yes, `adventure-bom` `5.2.0` |
| Annotations | `org.jetbrains:annotations:26.1.0` | yes |
| Tests | JUnit 6.1.3, Cyano `0.7.6` (`MicrotusExtension`) for anything needing a server | **no** — both `withoutVersion()`, resolved through `mycelium-bom` `1.8.7` |
| Benchmarks | JMH 1.37 via `me.champeau.jmh` 0.7.3, in the separate `falco-benchmarks` module | yes, both |
| `fastutil` | `8.5.19` | yes |
| Architecture | ArchUnit 1.5.1, in the separate `falco-archunit` module | yes |
| Build | Gradle 9.8.1, eight modules on one shared version | yes |

The eight modules are `falco-anvil`, `falco-light`, `falco-instance`, `falco-migration`,
`falco-bom`, `falco-benchmarks`, `falco-demo` and `falco-archunit`; `falco-anvil`, `falco-light`,
`falco-instance` and `falco-migration` are published, `falco-bom` publishes only version constraints
for `falco-anvil`, `falco-light` and `falco-instance`, and `falco-benchmarks`, `falco-demo` and
`falco-archunit` are not published at all.

**The Minestom version is the one that would invalidate a comparison silently.** Every head-to-head
table under [Measured](../reference/measured-results.md#measured) measures the Minestom of the day the run happened,
and that version is decided by `mycelium-bom` `1.8.7`, not by anything in this repository.
`2026.09.12-26.2` is what it resolves to today; a third party reproducing a number has to check that
theirs resolves to the same thing before comparing.

`adventure-nbt`, `jetbrains-annotations` and `fastutil` are declared explicitly rather than taken
transitively. The first two only reach the classpath through `compileOnly(minestom)`, so any direct
use of them would compile by coincidence. `fastutil` is `runtime` scope in Minestom's POM and is
needed by the light equivalence test and the comparison benchmarks.

## Working on this

```bash
./gradlew build                                  # compile, javadoc, tests — javadoc failures break the build
./gradlew :falco-anvil:test --tests "*Region*"   # a subset
./gradlew :falco-benchmarks:jmhJar               # build the benchmarks (they never run during build)
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar ScalingBenchmark -f 1 -wi 2 -i 3

# Compare the two loaders on a world of your own: drop it into falco-demo/world first.
# Run both with the SAME -Pthreads, or the two numbers answer different questions.
./gradlew :falco-demo:runFalcoLoader    -Pthreads=4 -Pchunks=256
./gradlew :falco-demo:runMinestomLoader -Pthreads=4 -Pchunks=256
```

A benchmark can also be run from Actions rather than locally — the **Benchmark** workflow, profile
`custom` for one filter or `full` for the whole suite. It uploads `results.json`, the command line as
executed and a filled-in provenance line, which is what makes a run quotable later; a local run
records none of that unless you write it down yourself. What it cannot do is stand in for the machine
behind the published tables, and
[Run the JMH benchmark suite](../how-to/run-the-jmh-benchmark-suite.md#or-from-ci-when-the-run-record-matters-more-than-the-machine) says why.

Two things that will otherwise cost an hour:

- **Do not run two Gradle builds in the same checkout at once.** They corrupt `build/test-results`
  and surface as `EOFException`, `NoClassDefFoundError` or missing `jacoco/test.exec` — failures
  that look like real test breakage. `rm -rf build` and rerun.
- **JMH allows one instance at a time.** A crashed run leaves `/tmp/jmh.lock` behind and every later
  run fails with *"Another JMH instance might be running"*. Delete the file.

## Conventions

Match these; the build enforces some of them.

- **Javadoc on every class and method**, with `@param` / `@return` / `@throws`. `withJavadocJar()`
  means an incomplete comment fails CI. Class comments explain *why*, not *what*, and carry
  `@author` / `@version` / `@since`.
- **Never write `@NotNull`.** Packages carry `@NotNullByDefault` in `package-info.java`; only
  `@Nullable`, `@Contract` and `@UnmodifiableView` appear explicitly.
- **Test-first, strictly.** Every type here was built by writing a failing test, confirming it fails
  for the right reason, then implementing. Several bugs in this branch were found precisely because
  a test was written before the code.
- Tests are package-private, named `test<What><Expectation>`, use plain JUnit assertions, and avoid
  `@Nested`. Anything needing a server uses `@ExtendWith(MicrotusExtension.class)`.
- Commits follow conventional commits, scoped `(anvil)`, `(light)`, `(instance)`, `(map)` — the four
  named by
  [`.github/pull_request_template.md`](https://github.com/OneLiteFeatherNET/Falco/blob/main/.github/pull_request_template.md),
  which is where that list is maintained.

## Releasing and snapshots

All published modules share one version. It lives on a single line of the root `build.gradle.kts`,
Release Please rewrites that line from the conventional commits above, and everything else follows
from it.

| Push to `main` | Version | Endpoint |
| --- | --- | --- |
| Merges the Release Please PR | the released one, `1.0.0` at `144a6098` | `https://repo.onelitefeather.dev/releases` |
| Anything else | the next patch with a suffix, `1.0.1-SNAPSHOT` | `https://repo.onelitefeather.dev/snapshots` |

The two version strings are the values at `ca79507` and are illustrations of the mechanism rather
than a statement about what the repositories hold today; the authoritative value is the
`version = …` line of the root `build.gradle.kts`, which Release Please rewrites.

`.github/workflows/release-please.yml` holds both publish jobs. They hang off the same
`release_created` output with opposite conditions, so a push publishes a release or a snapshot and
never both. A second workflow on `push` cannot see whether a release was cut and would double-publish.

The snapshot version is derived inside the build rather than passed in, because a `-Pversion=…`
would be overwritten by the `version = …` assignment that runs after the property is read.
`-Psnapshot` instead makes the root build bump the patch of the released version and append
`-SNAPSHOT`; the publishing block already picks its endpoint from that string. The flag reaches
Gradle through the `build-task` and `publish-task` inputs of the reusable workflow, which are handed
to `./gradlew` verbatim — it offers no input for properties or environment. Check the result with
`./gradlew -Psnapshot properties | grep '^version:'`.

Both endpoints are public, so a consumer of an artefact needs no credentials. Both exist on the
Reposilite instance and both have been written to: `/releases` served `0.2.1` of `falco-anvil` and
`falco-light`, the two modules published at that time, and `/snapshots` served `0.2.2-SNAPSHOT`, put
there by the first push after that release. That was checked once, by hand, at the time; it is not
verifiable from this repository and no later check is recorded. `falco-instance` shipped in `0.3.0`
and `falco-bom` was added after it was cut, so the set of published coordinates has grown since that
check.

## Opening a pull request

[`.github/pull_request_template.md`](https://github.com/OneLiteFeatherNET/Falco/blob/main/.github/pull_request_template.md)
is the form a pull request opens with, and it is the shortest statement of what review looks for.
Four of its checks decide most of the outcome.

- **The title of the pull request is the Conventional Commit.** It becomes the squash-commit message,
  and Release Please derives the next version and the changelog entry from it, by the mechanism under
  [Releasing and snapshots](#releasing-and-snapshots). The template names four scopes — `(anvil)`,
  `(light)`, `(instance)` and `(map)` — which is one more than [Conventions](#conventions) above
  lists; `falco-instance` is the module the fourth one names. A breaking change needs a `!` or a
  `BREAKING CHANGE:` footer.
- **A test was written first and watched fail**, package-private, named `test<What><Expectation>`,
  with plain JUnit assertions. That is the same rule [Conventions](#conventions) states, restated as
  a checkbox a reviewer ticks.
- **The concurrency section is filled in or deleted.** It asks which state the change adds or shares
  and what guards it, and it asks for a `*ConcurrencyTest` wherever shared state is touched. The
  template gives its own reason: every defect this project has found in its own code so far was a
  race that failed silently.
- **The measurements section is filled in or deleted.** It asks for JMH figures at the configured
  forks and iterations rather than a smoke run, with the error margins, the machine, the JVM and the
  thread count named — the same fields every provenance line in
  [Measured results](../reference/measured-results.md) carries.

[`.github/workflows/build-pr.yml`](https://github.com/OneLiteFeatherNET/Falco/blob/main/.github/workflows/build-pr.yml)
is what then runs on the branch. It is a thin caller of the shared
`OneLiteFeatherNET/workflows/.github/workflows/gradle-build-pr.yml` job at the version pinned in
[`.github/workflows/build-pr.yml`](https://github.com/OneLiteFeatherNET/Falco/blob/main/.github/workflows/build-pr.yml), pinned to
Java 25 on Temurin with `run-tests: true`, so a pull request is checked by the same build the first
command under [Working on this](#working-on-this) runs locally. A second workflow,
`close_invalid_prs.yml`, calls the organisation's `close-invalid-prs` job when a pull request is
opened.

Review ownership is in `.github/CODEOWNERS`. Every path is owned by
`@OneLiteFeatherNET/falco-maintainers`; the root build scripts, the Gradle wrapper, the Release
Please files, `CHANGELOG.md` and `.github/` itself are listed a second time, so that widening the
default owner later cannot silently widen who approves a release.

## References

- The versions in [Environment](#environment) are declared in
  [`settings.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/settings.gradle.kts)
  for the version catalogue and the eight modules, in
  [`build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/build.gradle.kts) for the
  toolchain, the release target and the single `version = …` line, and in
  [`gradle/wrapper/gradle-wrapper.properties`](https://github.com/OneLiteFeatherNET/Falco/blob/main/gradle/wrapper/gradle-wrapper.properties)
  for the Gradle version. Which of them are pinned here and which are resolved through a BOM is the
  *Pinned here* column of that table, and the BOM side is
  [Dependency Management](dependency-management.md).
- Both publish jobs hang off
  [`.github/workflows/release-please.yml`](https://github.com/OneLiteFeatherNET/Falco/blob/main/.github/workflows/release-please.yml).
  What the release configuration declares and how the snapshot version is derived inside the build is
  [Versioning and Releases](versioning-and-releases.md); which modules publish what, and how `falco-bom`
  differs from the other two kinds of project, is [Publishing](publishing.md).
- Building the benchmarks is one of the commands under [Working on this](#working-on-this). Running
  them, and what a result of the suite can and cannot mean, is [What the benchmarks establish](../explanation/what-the-benchmarks-establish.md); the two
  demo servers the last two commands start are [Benchmarks and Demo](benchmarks-and-demo.md).
- The state of the project the conventions above were arrived at in — the structural facts, the
  decisions, the defects and the open items — is [Project Status](../project-record/project-status.md), and the tables it
  used to carry are [Measured results](../reference/measured-results.md).
