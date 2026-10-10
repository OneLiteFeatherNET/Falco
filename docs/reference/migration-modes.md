# Migration modes

The `ChunkMigrationMode` constants, what each costs, and the settings around the backup.

> **This page is a generation target, currently written by hand.**
> Source of truth:
> [`ChunkMigrationMode`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkMigrationMode.java)
> and the `falco-migration` engine.

## `ChunkMigrationMode`

| Mode | Translates | Writes to the world | Cost |
| --- | --- | --- | --- |
| `OFF` *(default)* | nothing | no | none; identical to before the option existed |
| `IN_MEMORY` | on every read | **no** | paid again on every load of every outdated chunk |
| `ON_DISK` | on every read | **yes** | paid once per chunk, then never again |

| | |
| --- | --- |
| Builder slot | `migration(ChunkMigrationMode)` |
| Discovery | selecting any mode other than `OFF` turns on `ChunkMigrator` discovery by itself |
| Missing engine | the loader fails to build |
| Order | migration runs **before** `ChunkVersionPolicy` |
| Startup | a loader in either active mode logs the mode and its cost |

After `ON_DISK`, the world's chunks carry the running server's data version and the older server can
no longer read them.

## Backup

| | |
| --- | --- |
| Can be disabled | **no** |
| Path slot | `migrationBackup(Path)` |
| Default path | `<worldRoot>/falco-migration-backup/<dimension>` |
| Granularity | per region file, immediately before that file is first written |
| Mechanism | written under a `.partial` name, then moved atomically |
| Existing backup | never overwritten |
| Removal | none; Falco never deletes a backup, so it stays until it is removed by hand |

A world whose chunks are all current is never copied at all.

## Floors

| | |
| --- | --- |
| Lowest `DataVersion` the engine accepts | `1519` (Minecraft 1.13, the flattening) |
| Below it | declined, not attempted |
| Covers | blocks, biomes, block entities |
| Does not cover | entities, Bedrock worlds, downgrades |

## Rule versions

Rules live in
[`BlockStateRules`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-migration/src/main/java/net/onelitefeather/falco/migration/BlockStateRules.java).
Each carries a `since()` — the `DataVersion` the change happened in — and applies exactly when
`since() > sourceVersion`. **`since()` names the snapshot, not the release.** Every number in that
file carries its source in a comment beside it.

Related: [How-to Migrate a world from an older version](../how-to/migrate-a-world-from-an-older-version.md) ·
[Explanation How world migration works](../explanation/how-world-migration-works.md) · [Reference Supported versions](supported-versions.md)
