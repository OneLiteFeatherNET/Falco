# Dependency management

How Falco resolves and declares its dependencies: where repositories are configured, where the
version catalog lives and what its non-obvious entries are for, why several dependencies are
`compileOnly` rather than `implementation`, and how updates arrive. It does not cover what is
published — that is [Publishing](publishing.md) — or how the plugins are applied, which is [Build
Setup](build-setup.md).

Two files carry all of it:
[`settings.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/settings.gradle.kts)
and
[`renovate.json`](https://github.com/OneLiteFeatherNET/Falco/blob/main/renovate.json).

## Repositories live only in `settings.gradle.kts`

All repository configuration is centralised in `dependencyResolutionManagement` in
`settings.gradle.kts`. No module build file declares its own `repositories { }` block — see [Build
Setup](build-setup.md) for why a project-level block would be actively harmful here (under Gradle's
default `RepositoriesMode.PREFER_PROJECT` it wins outright, and the settings-level declaration is
then ignored for that module).

Two repositories are declared: Maven Central, and the OneLiteFeather repository at
`https://repo.onelitefeather.dev/onelitefeather`, which is where `net.minestom:minestom`,
`net.onelitefeather:cyano` and `net.onelitefeather:mycelium-bom` come from. That repository takes
credentials, and where they come from depends on the environment:

```kotlin
if (System.getenv("CI") != null) {
    credentials {
        username = System.getenv("ONELITEFEATHER_MAVEN_USERNAME")
        password = System.getenv("ONELITEFEATHER_MAVEN_PASSWORD")
    }
} else {
    credentials(PasswordCredentials::class)
    authentication {
        create<BasicAuthentication>("basic")
    }
}
```

On CI the two environment variables are read directly, because that is what the shared workflow
passes in through `secrets: inherit`. Locally, `credentials(PasswordCredentials::class)` makes
Gradle look for `OneLiteFeatherRepositoryUsername` and `OneLiteFeatherRepositoryPassword` in the
usual Gradle property sources — a personal `~/.gradle/gradle.properties` rather than a shell
environment, so a contributor's credentials never live in this repository. This is the repository
the build resolves *against*; it is a different path on the same host from the two endpoints the
build publishes *to*, which are described in [Publishing](publishing.md).

## The version catalog is declared inline, not in a TOML file

There is no `gradle/libs.versions.toml` in this repository. The `libs` catalog is created inside
`settings.gradle.kts` under `dependencyResolutionManagement { versionCatalogs { create("libs") { … }
} }`. A reader looking for the conventional TOML file will not find one, and a tool that only
understands the conventional file will not see these entries.

## Notable version catalog entries

Most catalog entries are unremarkable. These are the ones whose reason is not visible from the entry
itself. Where a version is stated below it is the one the catalog declares today.

- **`jmh` (`1.37`) and `jmhPlugin` (`0.7.3`).** The mycelium BOM manages `net.minestom:minestom`,
  `net.kyori:adventure-text-minimessage`, `net.onelitefeather:cyano`, Mockito and the JUnit BOM, and
  nothing else — checkable in the published `mycelium-bom` POM. JMH is not among them, so both the
  benchmark harness and the Gradle JMH plugin need an explicit version here.
- **`slf4j` (`2.0.18`) and `annotations` (`26.1.0`).** Not managed by the mycelium BOM either, for
  the same reason and with the same consequence.
- **`adventureBom` (`5.1.1`).** The library modules receive Adventure transitively through Minestom,
  which is `compileOnly` and therefore never reaches a runtime classpath (see below). The benchmarks
  module runs its code for real and needs Adventure at runtime, so it imports the Adventure platform
  directly. The value matches what Minestom pulls in — `net.minestom:minestom:2026.06.20-26.1.2`
  declares `net.kyori:adventure-nbt` at version `5.1.1` — but nothing in the build asserts that the
  two agree, so a Minestom upgrade that moves Adventure has to be followed here by hand.
- **`slf4j.simple`.** Only `falco-demo` needs an SLF4J binding, and it takes it as `runtimeOnly`. The
  library modules depend on `slf4j-api` alone and leave the choice of binding to the consumer; a demo
  that actually runs would otherwise greet the user with "No SLF4J providers were found" before
  printing anything of its own.
- **`fastutil` (`8.5.18`, explicit version).** Minestom declares `it.unimi.dsi:fastutil` at
  **runtime** scope, so it never reaches a *compile* classpath through Minestom. `falco-instance`
  compiles against fastutil types, and `falco-light`'s equivalence test and the comparison benchmarks
  call Minestom methods that take a fastutil collection, so the dependency has to be named
  explicitly — and naming it means pinning it. `8.5.18` is the same version Minestom
  `2026.06.20-26.1.2` declares.

Six entries are declared with `withoutVersion()` — `minestom`, `adventure.nbt`, `cyano` and the
three JUnit coordinates. Their versions come from a platform at use site: the mycelium BOM for
Minestom, Cyano and JUnit, and Minestom's own dependency graph for `adventure-nbt`. The consequence
worth knowing is that **no Minestom version string appears anywhere in this repository**: it is
whatever `net.onelitefeather:mycelium-bom:1.7.2` manages, which today is `2026.06.20-26.1.2`. What
that costs is traceability — an upgrade to the mycelium BOM moves Minestom, and therefore moves the
Minestom side of every comparison this project publishes, through a diff that mentions only a BOM
version. Anyone reproducing a measured result therefore needs the Minestom version stated alongside
the result; it cannot be read off a build file in this repository.

## Why Minestom, fastutil and adventure-nbt are `compileOnly`

Across `falco-anvil`, `falco-light` and `falco-instance`, three dependencies are declared
`compileOnly` rather than `implementation`:

```kotlin
compileOnly(libs.adventure.nbt)
compileOnly(libs.annotations)
compileOnly(libs.minestom)
```

Minestom itself is what a consumer of these libraries always already has on their classpath — a
library that bundled its own copy would risk a classpath conflict with whichever Minestom version the
consuming server actually runs. `adventure-nbt` and the JetBrains `annotations` follow the same logic:
`adventure-nbt` is supplied transitively by Minestom, which declares it at compile scope, and the
JetBrains annotations are `CLASS`-retention artefacts that are never needed at runtime at all, so
bundling either again would be redundant at best and a version clash at worst.

The practical consequence for consumers: **anything that depends on `falco-anvil`, `falco-light`, or
`falco-instance` must already have Minestom (and, indirectly, Adventure) on its own runtime
classpath.** That is true for essentially every project this library is meant to be used in, since
none of them make sense without Minestom present.

`falco-instance` additionally marks `fastutil` `compileOnly`, for the same reason as the version
catalog note above: the block entry maps of `DynamicChunk` are fastutil types, and copying a chunk has
to touch them, but Minestom only supplies fastutil at runtime scope so `falco-instance` names it
explicitly to have it during compilation.

The three library modules declare the same coordinates again as `testImplementation`, because
`compileOnly` is not inherited by the test compile or test runtime classpath and the tests need a
real one. `falco-demo` needs no such repetition: it takes Minestom as `implementation`, which the
test source set does inherit.

## Dynamic versions are cached for a minute

```kotlin
configurations.all {
    resolutionStrategy.cacheDynamicVersionsFor(1, "minutes")
}
```

Declared in the root build for every module except `falco-bom`. It shortens Gradle's default
24-hour cache for dynamic versions to one minute, so a rebuild picks up a freshly published version
rather than a cached answer. It has no effect on any coordinate the catalog declares today, none of
which is a range or a `+`; it matters for a snapshot or a dynamic version introduced later, and for
resolving against a repository that has just received a publish.

## Updates arrive through Renovate

`renovate.json` extends two OneLiteFeather presets and adds one custom manager. The presets set the
organisation-wide policy and the reviewer team; the flavour preset for Minestom only fixes version
ordering for a pinned `net.minestom:minestom`, which is inert here for the reason above — Falco
pins no Minestom version of its own — and is kept for the moment one is pinned.

The custom manager covers the install snippets in the README:

```json
"matchStrings": [
  "(?:implementation|platform)\\(\"(?<depName>net\\.onelitefeather:falco-(?:anvil|instance|light|bom)):(?<currentValue>[^\"]+)\"\\)"
]
```

It exists so a released version number in a copy-paste snippet cannot fall behind the release it
claims to be. The module name is captured rather than templated, so a module is covered the moment a
snippet mentions it; `falco-benchmarks`, `falco-demo` and `falco-archunit` are deliberately absent
because they are never published (see [Benchmarks and Demo](benchmarks-and-demo.md) and [Architecture
Rules](architecture-rules.md)). Both call forms are matched because
a consumer declares the BOM inside `platform("…")` and a library inside `implementation("…")`.

The rule's own `description` in `renovate.json` records the two traps it was written around, and is
the authority if this paragraph and that file ever disagree.
