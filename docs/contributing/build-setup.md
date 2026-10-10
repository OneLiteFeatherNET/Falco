# Build setup

How Falco's Gradle build is put together: what the root project is for, which plugin each module
gets and from where, and why `falco-bom` needs an exception in nearly every block. This page covers
the build wiring only — version and release mechanics are in [Versioning and
Releases](versioning-and-releases.md), the publishing configuration in [Publishing](publishing.md), and
dependency resolution in [Dependency Management](dependency-management.md). It is written for someone
about to change a build file, not for someone consuming the library.

Everything below is checkable in four files:
[`build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/build.gradle.kts),
[`settings.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/settings.gradle.kts),
[`gradle.properties`](https://github.com/OneLiteFeatherNET/Falco/blob/main/gradle.properties) and
the eight module scripts. Gradle itself is pinned by the wrapper at `9.8.1`, in
[`gradle/wrapper/gradle-wrapper.properties`](https://github.com/OneLiteFeatherNET/Falco/blob/main/gradle/wrapper/gradle-wrapper.properties),
so `./gradlew` is the only supported way to run any of it.

## The root project has no sources

`build.gradle.kts` at the repository root has no `plugins { }` block at all and carries no sources.
This is deliberate: the root project exists only to hold the shared version and the configuration
that every module inherits. Applying `java-library` there would create an empty, publishable `falco`
artefact next to the real ones, which nobody wants to consume.

## Modules

| Module | Published | Plugins, and where they are applied |
| --- | --- | --- |
| `falco-anvil` | yes | `java-library`, `jacoco`, `maven-publish` — all from the root |
| `falco-light` | yes | `java-library`, `jacoco`, `maven-publish` — all from the root |
| `falco-instance` | yes | `java-library`, `jacoco`, `maven-publish` — all from the root |
| `falco-bom` | yes | `java-platform`, `maven-publish` — both from the root |
| `falco-benchmarks` | no | `java-library`, `jacoco` from the root; `me.champeau.jmh` from its own script |
| `falco-demo` | no | `java-library`, `jacoco` from the root |
| `falco-archunit` | no | `java-library`, `jacoco` from the root |

`falco-anvil`, `falco-light` and `falco-instance` are the three library modules the project actually
ships. `falco-bom` is a Maven BOM pinning those three to one version (see
[Publishing](publishing.md)). `falco-benchmarks` and `falco-demo` are tooling modules that are never
published — see [Benchmarks and Demo](benchmarks-and-demo.md). `falco-archunit` is never published
either and is the odd one out: it has no `main` source set at all, only tests, and it exists to hold
the architecture rules over the other modules — see [Architecture Rules](architecture-rules.md).

`falco-benchmarks/build.gradle.kts` holds the only `plugins { }` block in the repository, a single
`alias(libs.plugins.jmh)`. Every other plugin in the table is applied imperatively from the root, so
the eight module scripts contain dependencies, a `description`, and — in `falco-benchmarks` and
`falco-demo` — task wiring, and nothing else.

`falco-archunit` gets `java-library` and `jacoco` from the root like every non-BOM module, and both
are largely inert there: with no `main` sources the `javadoc` and `jar` tasks have nothing to do, and
the JaCoCo report covers a source set that does not exist. That is wiring the module inherits rather
than wiring it needs, and it costs nothing to leave in place.

## One toolchain, one release target

```kotlin
extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}
```

Both settings are declared in the root build and reach every module except `falco-bom`. They answer
two different questions: the toolchain decides which JDK compiles, tests and runs, while
`options.release` decides which class file version and which JDK API surface the compiler will
accept. Stating both keeps the two decoupled — raising the toolchain to a newer JDK later still
produces artefacts a Java 25 runtime can load, and a call into an API added after 25 fails at
compile time rather than at a consumer's startup. Continuous integration asks for the same version
explicitly: `.github/workflows/build-pr.yml` passes `java-version: "25"` and
`java-distribution: "temurin"` to the shared build workflow.

## Applying plugins: why `falco-bom` is special-cased

The root build's `subprojects` block applies `java-library` and `jacoco` to every subproject except
`falco-bom`:

```kotlin
configure(subprojects - project(":falco-bom")) {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    ...
}
```

`java-library` and `java-platform` are mutually exclusive Gradle plugins — applying both to one
project fails the build — and a `java-platform` project has no sources, so there is nothing for a
toolchain, a compiler, or a test task to do there anyway. Subtracting `falco-bom` from the subproject
list here, rather than guarding every `apply()` call inside the block with an `if (name !=
"falco-bom")`, keeps the block itself unaware that an exception exists: a plugin added to it later
inherits the exclusion for free, and the diff against the pre-BOM version of the file is a one-line
change of scope rather than a scattering of conditionals through the body.

`java-platform` itself is applied to `falco-bom` from the root build, in the same place
`java-library` is applied to every other subproject:

```kotlin
project(":falco-bom") {
    apply(plugin = "java-platform")
}
```

This is not a style choice but an evaluation-order requirement. A `project(":falco-bom") { }` block
in the root script runs while the root project is being configured, which is before
`falco-bom/build.gradle.kts` is evaluated at all. If `java-platform` were applied in the module's own
script instead, the `javaPlatform` software component it registers would not exist yet by the time
the root script's publishing block — further down the same file, and therefore still part of the
root project's configuration — calls `from(components["javaPlatform"])`.

## Group and version apply to every subproject, including `falco-bom`

```kotlin
subprojects {
    group = "net.onelitefeather"
    version = rootProject.version
}
```

This assignment sits outside the `java-library` configuration block precisely so it also reaches
`falco-bom`. The BOM pins its siblings by project reference rather than by version string (see
[Publishing](publishing.md)), and that only produces the right numbers in the generated POM if
`falco-bom` carries the same version its siblings do.

## No repositories are declared per-project

None of the eight module build files declares a `repositories { }` block. Repositories come
exclusively from `dependencyResolutionManagement` in `settings.gradle.kts`, and that file does not
set `repositoriesMode`, so Gradle's default `RepositoriesMode.PREFER_PROJECT` applies: the moment a
module declared its own block, that block would win and the settings-level declaration would be
ignored for that module — silently dropping the OneLiteFeather repository that Minestom, Cyano and
the mycelium BOM are resolved from. See [Dependency Management](dependency-management.md) for what
lives in that block.

## Build-wide Gradle settings

`gradle.properties` configures how a build runs rather than what it produces:

```properties
org.gradle.caching=false
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx2G -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
```

`org.gradle.parallel=true` lets Gradle execute independent modules at the same time, so one
`./gradlew build` runs several test JVMs alongside each other — which is why the test heap is pinned
rather than left to the default. The `jvmargs` line sizes the Gradle daemon, not those test JVMs;
each test JVM is a separate process and gets its heap from the `Test` task configuration. The build
cache is off, so a task's result is never restored from a previous build's output. How to reproduce
a test run, what the `-Werror` doclint policy enforces, and where the test heap is set are in
[Testing and Javadoc](testing-and-javadoc.md).

## Binary compatibility against the last release

`checkApiCompatibility` compares the jar of each published library module against the version in
`apiBaselineVersion` and fails the build on a binary-incompatible change. It hangs off `check`, so
`./gradlew build` runs it. `falco-bom` is excluded: a `java-platform` has no classes to compare.

```properties
apiBaselineVersion=1.0.0
```

**This property is raised by hand after every release.** Release Please rewrites `version` through
its `x-release-please-version` marker and knows nothing about this one. Forgetting it compares
against an older baseline, which is stricter than intended rather than quieter — the failure mode
errs on the safe side, but the property is the one line of this setup that a release can leave stale.

Two details of the configuration exist for a reason and should survive anyone tidying it up. Both
were found by deliberately removing a `public` method and watching the check stay green.

**The baseline is resolved through a detached configuration.** Gradle substitutes an external
dependency with a project of the same group and name from the same build, and it does so regardless
of version — so `net.onelitefeather:falco-anvil:1.0.0` resolves to `project ':falco-anvil'`, and the
comparison becomes the local jar against itself. Neither
`resolutionStrategy.useGlobalDependencySubstitutionRules = false` nor an explicit
`dependencySubstitution` rule prevents it. A detached configuration is not part of the project
graph, which is what makes it work; a named configuration, however tidy, brings the substitution
back.

**The new side is `tasks.named<Jar>("jar").flatMap { it.archiveFile }`, not the task itself.** Handing
japicmp the task rather than its archive produces the same silent pass.

A `doFirst` guard fails the task if the baseline ever resolves into this build's own `build/libs`.
Both mistakes above had one signature — a green check that verified nothing — and a compatibility
gate that cannot fail is worse than none, because it reads as coverage.

## Credentials and a 401 during resolution

Building Falco needs `ONELITEFEATHER_MAVEN_USERNAME` and `ONELITEFEATHER_MAVEN_PASSWORD`, because
Minestom and `mycelium-bom` are resolved from an authenticated endpoint. A 401 during dependency
resolution means those two variables are unset or wrong. It is the failure that actually occurs, not a
repository outage. Consumers of the published artefacts never see it, since their endpoints are public;
see [Publishing](publishing.md) for how the credentials are wired into the build.
