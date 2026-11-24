# Modules and coordinates

Every published artefact, its coordinate, the repository that serves it and its rendered Javadoc.

> **This page is a generation target, currently written by hand.**
> Source of truth: the `maven-publish` configuration and the version catalog in the
> [Falco repository](https://github.com/OneLiteFeatherNET/Falco). Until the generator lands, the
> version numbers below can go stale — Renovate keeps the snippets in the
> [repository README](https://github.com/OneLiteFeatherNET/Falco#readme) current (its custom manager in
> [`renovate.json`](https://github.com/OneLiteFeatherNET/Falco/blob/main/renovate.json) matches only `README.md`)
> and does not update these pages, so **where this page and the README disagree, the README is right**. The authoritative list
> of published versions is [Releases](https://github.com/OneLiteFeatherNET/Falco/releases).

## Repositories

| | Endpoint | Authentication |
| --- | --- | --- |
| Releases | `https://repo.onelitefeather.dev/releases` | none |
| Snapshots | `https://repo.onelitefeather.dev/snapshots` | none |

Consuming Falco needs no credentials. Only building Falco itself does — see
[How-to Build Falco from source](../how-to/build-falco-from-source.md).

## Artefacts

### `net.onelitefeather:falco-anvil`

| | |
| --- | --- |
| Kind | `java-library` |
| Current version | `3.0.2` <!-- x-release-please-version --> |
| Since | `0.1.0` |
| Javadoc | [latest release](https://repo.onelitefeather.dev/javadoc/releases/net/onelitefeather/falco-anvil/latest) |

An Anvil region-file chunk loader for Minestom.

### `net.onelitefeather:falco-light`

| | |
| --- | --- |
| Kind | `java-library` |
| Current version | `3.0.2` <!-- x-release-please-version --> |
| Since | `0.1.0` |
| Javadoc | [latest release](https://repo.onelitefeather.dev/javadoc/releases/net/onelitefeather/falco-light/latest) |

A block- and sky-light engine for Minestom.

### `net.onelitefeather:falco-instance`

| | |
| --- | --- |
| Kind | `java-library` |
| Current version | `3.0.2` <!-- x-release-please-version --> |
| Since | `0.1.0` |
| Javadoc | [latest release](https://repo.onelitefeather.dev/javadoc/releases/net/onelitefeather/falco-instance/latest) |

An `Instance` implementation that unloads the chunks it loaded, plus a `Chunk` that allocates its
sections on demand.

### `net.onelitefeather:falco-migration`

| | |
| --- | --- |
| Kind | `java-library` |
| Current version | `3.0.2` <!-- x-release-please-version --> |
| Since | `2.0.0` |
| Javadoc | not yet rendered at a stable address |

Lifts a world older than the running server to it. Needed only when loading such a world; see
[How-to Migrate a world from an older version](../how-to/migrate-a-world-from-an-older-version.md).

### `net.onelitefeather:falco-bom`

| | |
| --- | --- |
| Kind | `java-platform` |
| Current version | `3.0.2` <!-- x-release-please-version --> |
| Since | `1.0.0` |
| Javadoc | none — the artefact carries no classes |

Carries one version constraint for each of the three modules `falco-anvil`, `falco-light` and
`falco-instance` ([`falco-bom/build.gradle.kts`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-bom/build.gradle.kts)),
so a build states the version once for those. `falco-migration` is published but is not in the BOM,
so its version is stated on its own dependency line. A platform constrains versions; it does not pull
in a module that was not declared.

`falco-bom` was added after `0.3.0` was cut, so releases before `1.0.0` do not serve it.

## Javadoc address scheme

```
https://repo.onelitefeather.dev/javadoc/<releases|snapshots>/net/onelitefeather/<module>/<version>
```

`latest` is accepted in place of a version and follows the newest one on that endpoint. A version
never published to an endpoint answers 404 rather than showing an older one.

Every published module also carries a `-javadoc.jar` next to its main jar — the copy an IDE fetches
through *Download Sources and Documentation*.

## Snapshot versions

A push to `main` that does not cut a release publishes to the snapshot endpoint under the released
version with its patch bumped and `-SNAPSHOT` appended: while `1.0.0` is the latest release, the
snapshot endpoint serves `1.0.1-SNAPSHOT`. That version keeps moving as commits land.

## Dependencies Falco does not bring

Minestom is `compileOnly` in `falco-anvil`, `falco-light` and `falco-instance`; `falco-migration` and
`falco-bom` declare no Minestom dependency at all. fastutil and flare-fastutil are `compileOnly` in
`falco-instance` only. None of these arrive with the artefacts. A consumer declares its own Minestom
version; Falco declares none.

Related: [How-to Add Falco to your build](../how-to/add-falco-to-your-build.md) · [Explanation Why a second Anvil loader](../explanation/why-a-second-anvil-loader.md)
