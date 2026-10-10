# Publishing

How Falco's five published artefacts (`falco-anvil`, `falco-light`, `falco-instance`,
`falco-migration`, `falco-bom`)
reach a Maven repository, and why `falco-bom` is configured differently from the three library
modules despite sharing most of the setup. Which version string a publish carries is [Versioning and
Releases](versioning-and-releases.md); which repositories the build resolves *against* is [Dependency
Management](dependency-management.md).

The whole configuration lives in the second half of
[`build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/build.gradle.kts) at the
repository root, plus the four constraint lines in
[`falco-bom/build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-bom/build.gradle.kts).
No module configures its own publication.

## Which modules publish

```kotlin
val publishedModules = listOf(project(":falco-anvil"), project(":falco-light"),
    project(":falco-instance"), project(":falco-migration"), project(":falco-bom"))
```

`falco-benchmarks`, `falco-demo` and `falco-archunit` deliberately never apply `maven-publish`, so a
`./gradlew publish` at the root passes over them without needing an explicit task exclusion — they
are simply absent from this list. See [Benchmarks and Demo](benchmarks-and-demo.md) for what the first
two are for, and [Architecture Rules](architecture-rules.md) for the third.

## One shared repository configuration

```kotlin
configure(publishedModules) {
    apply(plugin = "maven-publish")

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                authentication {
                    credentials(PasswordCredentials::class) {
                        username = System.getenv("ONELITEFEATHER_MAVEN_USERNAME")
                        password = System.getenv("ONELITEFEATHER_MAVEN_PASSWORD")
                    }
                }
                name = "OneLiteFeatherRepository"
                url = if (project.version.toString().contains("SNAPSHOT")) {
                    uri("https://repo.onelitefeather.dev/snapshots")
                } else {
                    uri("https://repo.onelitefeather.dev/releases")
                }
            }
        }
    }
}
```

The repository is the one thing all five published modules share, regardless of what they publish or
how: a library module ships a jar built from `components["java"]`, `falco-bom` ships only a POM built
from `components["javaPlatform"]`, but both land on the same host behind the same release/snapshot
switch and the same credentials. Configuring that once here, rather than once per module, keeps a
future change to the repository (a new host, a different credential scheme) a one-line edit instead
of a four-line one — which is also why this block applies `maven-publish` itself, rather than leaving
each module to apply it before configuring its own publication.

The two endpoints a build publishes to, `/releases` and `/snapshots`, are the ones a consumer reads
from without credentials: the install snippets in the
[README](https://github.com/OneLiteFeatherNET/Falco#readme) declare
`maven("https://repo.onelitefeather.dev/releases")` with no `credentials` block. They are a different
path on the same host from `…/onelitefeather`, the credentialed repository the build resolves
against (see [Dependency Management](dependency-management.md)). Which of the two a publish goes to is
decided purely by whether `SNAPSHOT` appears in the version string — see [Versioning and
Releases](versioning-and-releases.md) for how that string is produced.

Note where the credentials come from: `System.getenv`, not a Gradle property. This is the opposite of
the resolution repository in `settings.gradle.kts`, which falls back to Gradle properties outside CI
— a publish is meant to happen on CI, so a local `./gradlew publish` with the two environment
variables unset hands the plugin a null username and fails rather than quietly pushing something.
The release workflow is what actually invokes it, described below.

## What gets published differs by project kind

```kotlin
configure(listOf(project(":falco-anvil"), project(":falco-light"), project(":falco-instance"))) {
    extensions.configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
    }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

project(":falco-bom") {
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("maven") {
            from(components["javaPlatform"])
        }
    }
}
```

This part of the configuration stays split from the shared repository block above because what gets
published genuinely differs by project kind. The three library modules carry a `JavaPluginExtension`
to request sources and Javadoc jars and publish the `java` software component. `falco-bom` has neither
a `JavaPluginExtension` nor anything to document or compile — a platform module's only published file
is the POM its `javaPlatform` component becomes, so asking it for `withJavadocJar()` /
`withSourcesJar()` would fail outright with an unknown-extension error rather than doing nothing.

`withJavadocJar()` has a second effect that matters outside publishing: it wires the `javadoc` task
into `assemble`, which is what makes the `-Werror` doclint policy run on these four modules during
an ordinary `./gradlew build`. [Testing and Javadoc](testing-and-javadoc.md) covers what that policy
enforces and which modules it reaches.

## `falco-bom`: pinning by project reference, not by version string

`falco-bom/build.gradle.kts` itself is short — a `java-platform` project carries no sources and no
compiled output of its own, only constraints:

```kotlin
dependencies {
    constraints {
        api(project(":falco-anvil"))
        api(project(":falco-light"))
        api(project(":falco-instance"))
    }
}
```

These are written as project references rather than `"net.onelitefeather:falco-anvil:<version>"`
string literals so the pinned version can never drift from the one the sibling module actually builds
as. A hardcoded string would need to be kept in sync by hand on every release; a project reference
makes Gradle read `project(":falco-anvil").version` itself when it writes the constraint — which, per
the root build's `subprojects` block (see [Build Setup](build-setup.md)), is exactly `rootProject.version`,
the same single line Release Please rewrites.

## What triggers a publish

Nothing in the build triggers one; `.github/workflows/release-please.yml` does. Every push to `main`
runs that workflow, and exactly one of its two publish jobs fires:

| Job | Condition | Gradle invocation |
| --- | --- | --- |
| `publish` | `release_created == 'true'` | `publish` |
| `publish-snapshot` | `release_created != 'true'` | `-Psnapshot build`, then `-Psnapshot publish` |

Both jobs call `OneLiteFeatherNET/workflows/.github/workflows/gradle-publish.yml@v2.4.0` with
`java-version: "25"` and `secrets: inherit`, which is where the two `ONELITEFEATHER_MAVEN_*` values
the block above reads come from. The conditions are exact opposites of the same output, so a push
publishes either a release or a snapshot and never both. [Versioning and
Releases](versioning-and-releases.md) covers what `-Psnapshot` does to the version string and why the
release job is not triggered by a tag.
