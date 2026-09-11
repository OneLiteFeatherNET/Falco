# Contributing to Falco

Falco is an Anvil chunk loader, a light engine and an `Instance` implementation for Minestom, and
all three modules are experimental: every public type carries `@ApiStatus.Experimental`, and
signatures and behaviour may still change in a minor release. This file is the entry point and
nothing more — what the build is made of, which conventions it enforces and what a push to `main`
publishes is [Contributing](https://github.com/OneLiteFeatherNET/Falco/wiki/Contributing) in the
wiki.

## Building

Java 25 is required; the Gradle wrapper in the repository supplies Gradle itself.

```bash
./gradlew build
```

That compiles the modules, runs the tests and builds the Javadoc. An incomplete Javadoc comment
fails the build, so a green `./gradlew build` is also the documentation check.

**A build from source needs OneLiteFeather Maven credentials.** Falco compiles against Minestom and
the internal `mycelium-bom` through `https://repo.onelitefeather.dev/onelitefeather`, which answers
`401` to an anonymous request, so `./gradlew build` fails without them. This affects only work on
Falco itself. The names the credentials are read under are in
[Installation](https://github.com/OneLiteFeatherNET/Falco/wiki/Installation#building-from-source).

**Consuming Falco needs no credentials from this release onwards, and did need them before it.** The
jars were always served without authentication, but a POM is resolved as well as a jar, and up to
2.1.0 every module's POM imported `net.onelitefeather:mycelium-bom:1.7.2` in its
`dependencyManagement`. That version is on neither `https://repo.onelitefeather.dev/releases` — which
carries 1.8.3 and up — nor Maven Central, so a consumer without credentials got
`Could not find net.onelitefeather:mycelium-bom:1.7.2` and no Minestom version to constrain against.
The bump to 1.8.5 is what closes that: measured against a clean Gradle home with only `mavenCentral()`
and the public `releases` endpoint declared, the 1.8.5 import resolves and brings Minestom
`2026.08.28-26.2` with it, where the same probe against 1.7.2 fails.

Running a single test class, building the benchmark jar and starting the two demo servers are
further commands, listed under
[Working on this](https://github.com/OneLiteFeatherNET/Falco/wiki/Contributing#working-on-this).

## Commits

Commits follow Conventional Commits, because Release Please reads them. `release-please-config.json`
declares `"release-type": "simple"` for the root package, writes `CHANGELOG.md`, and carries an
`extra-files` entry for `build.gradle.kts` — the file that holds
`version = "0.3.0" // x-release-please-version`, the single line every published module takes its
version from. A commit subject therefore decides the next version number and the changelog entry it
appears under. The history is in that form throughout: `fix(light): forget chunks that left the
instance`, `test(anvil): cover the two builder slots that were only asserted by symmetry`,
`ci: check binary compatibility against the last release with japicmp`.

The scopes in use are `(anvil)`, `(light)`, `(instance)` and `(map)`, named by
`.github/pull_request_template.md`. A breaking change needs a `!` or a `BREAKING CHANGE:` footer.

## Pull requests

Changes reach `main` through a pull request against `main`, and each is merged as the single squash
commit that carries its number — `git log --oneline` on `main` shows a `(#n)` on every recent
subject, which is what that history looks like from the outside.

Opening one fills `.github/pull_request_template.md`, whose checklist is the shortest statement of
what review looks for: the pull-request title is the Conventional Commit, a test was written first
and watched fail, the concurrency section is filled in or deleted, the measurements section is
filled in or deleted. What each of those four asks for, and why, is
[Opening a pull request](https://github.com/OneLiteFeatherNET/Falco/wiki/Contributing#opening-a-pull-request).
`.github/workflows/build-pr.yml` then runs the same build on the branch that `./gradlew build` runs
locally.

## Documentation

Long-form prose lives in the wiki; the repository holds code, the chart binaries under
`docs/charts`, the build files and pointers into the wiki. A change to any documentation is bound by
the Falco documentation standard, which is checked into neither repository. Its rules, in short: no
measured number is invented, re-rounded or extrapolated; every measured figure carries its
conditions and its uncertainty, and every table of measured values carries a provenance line naming
the benchmark class, the parameters, the forks and the iterations; a losing row, a tie or a
regression stays visible and is named in the prose under its table; British spelling; lines wrapped
at roughly 100 columns.
