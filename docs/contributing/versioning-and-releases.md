# Versioning and releases

How Falco's version number is set, how Release Please updates it, how snapshot builds derive a
version from it, and what a push to `main` actually does. Where the resulting artefacts go is
[Publishing](publishing.md).

Four files carry the whole mechanism:
[`build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/build.gradle.kts),
[`release-please-config.json`](https://github.com/OneLiteFeatherNET/Falco/blob/main/release-please-config.json),
[`.release-please-manifest.json`](https://github.com/OneLiteFeatherNET/Falco/blob/main/.release-please-manifest.json)
and
[`.github/workflows/release-please.yml`](https://github.com/OneLiteFeatherNET/Falco/blob/main/.github/workflows/release-please.yml).

## The one line Release Please rewrites

The version lives in exactly one place in the build, `build.gradle.kts` at the repository root:

```kotlin
version = "1.0.0" // x-release-please-version
```

All four published modules — `falco-anvil`, `falco-light`, `falco-instance` and the BOM that pins
them (see [Build Setup](build-setup.md)) — always release together, so the version is declared once here
and inherited by every subproject through `subprojects { version = rootProject.version }`. Nothing
else in the build carries a version number.

`release-please-config.json` lists `build.gradle.kts` as an `extra-files` entry of type `generic`,
and Release Please's generic updater locates the line to rewrite by searching for the exact marker
comment `// x-release-please-version`. That comment is not documentation — it is the anchor the
release automation depends on. If it is ever removed or reworded, Release Please silently stops
finding the line, the build keeps compiling and testing green, and no release ever updates the
version again.

The second place the number appears is `.release-please-manifest.json`, which is Release Please's own
record of where it left off:

```json
{".":"1.0.0"}
```

Release Please writes both, and neither is edited by hand. The build reads only the first; the
manifest exists so the tool knows the current version without parsing a Gradle script.

## What the release configuration says

| Setting | Value | Consequence |
| --- | --- | --- |
| `release-type` | `simple` | no language-specific updater; the version is carried entirely by the `extra-files` marker above |
| `include-v-in-tag` | `true` | tags are `v1.0.0`, matching the existing `v0.2.0`, `v0.2.1`, `v0.3.0` |
| `include-component-in-tag` | `false` | one component, so no `falco-` prefix on the tag |
| `package-name` | `falco` | |
| `changelog-path` | `CHANGELOG.md` | generated from Conventional Commit subjects |

The file carries two further keys. `pull-request-header` is set to the empty string, which suppresses
the default header on the release pull request. `bootstrap-sha` is `HEAD`, which only applies while
no manifest exists; `.release-please-manifest.json` has existed since the first release, so it has no
effect today.

Because `release-type` is `simple`, the marker comment is the only thing standing between a merged
commit and a correctly versioned artefact. There is no second mechanism that would catch its removal.

## Deriving a snapshot version

```kotlin
if (providers.gradleProperty("snapshot").isPresent) {
    val parts = version.toString().substringBefore('-').split('.')
    require(parts.size == 3) { "cannot derive a snapshot from version '$version'" }
    version = "${parts[0]}.${parts[1]}.${parts[2].toInt() + 1}-SNAPSHOT"
}
```

Every push to `main` that does not cut a release publishes the current state as a snapshot, and the
derivation happens in the build script rather than on the command line. The reason is assignment
order: `-Pversion=…` on the command line sets the project's version *before* the script runs, and the
plain `version = "1.0.0"` assignment above then overwrites it. Instead, the script reads the
already-released version and bumps its patch number by one, appending `-SNAPSHOT`. That keeps Release
Please's single marked line the only place a version number is ever written — the snapshot logic only
reads it.

The `require` is deliberate rather than defensive noise: the derivation only makes sense for a
three-part version, and a build that cannot derive one should fail loudly instead of publishing
something with an unexpected coordinate.

The published artefact's repository is chosen from whether `SNAPSHOT` appears in the resulting
version string; see [Publishing](publishing.md) for how that routes between the releases and snapshots
endpoints.

## What a push to `main` does

`.github/workflows/release-please.yml` runs on every push to `main` and has three jobs. The first
runs `googleapis/release-please-action@v5` against the two configuration files above and exposes
`release_created`, `tag_name` and `version` as outputs. The other two hang off that first output with
conditions that are exact opposites, so a push publishes either a release or a snapshot and never
both:

```yaml
publish:
  if: needs.release-please.outputs.release_created == 'true'
  # publish-task: "publish"

publish-snapshot:
  if: needs.release-please.outputs.release_created != 'true'
  # build-task: "-Psnapshot build", publish-task: "-Psnapshot publish"
```

`-Psnapshot` reaches Gradle inside the task strings because the shared
`OneLiteFeatherNET/workflows/.github/workflows/gradle-publish.yml@v2.4.0` workflow passes them to
`./gradlew` verbatim and offers no separate input for properties or environment variables.

Everything that is not a release therefore ships a snapshot: a merged feature, a documentation fix,
and Release Please opening or updating its own release pull request. The snapshot version is always
one patch ahead of the last release, so `1.0.0` released means `1.0.1-SNAPSHOT` published until
`1.0.1` or `1.1.0` is cut.

There is deliberately no tag-triggered publish workflow next to this one. Release Please tags with
the default `GITHUB_TOKEN`, and a tag pushed by that token does not re-trigger workflows in the same
repository — so a tag-triggered publish would either never fire or race this job. The comment in the
workflow file records the same reasoning and is the authority if the two ever disagree.
