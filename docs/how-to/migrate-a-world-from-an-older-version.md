# Migrate a world from an older version

Lift a world written by an older server to the version you run, so that blocks renamed since do not
quietly become air.

**Before you start:** `falco-migration` on the classpath ([How-to Add Falco to your build](add-falco-to-your-build.md)), a
world at `DataVersion` `1519` or above, and disk space for a backup — it cannot be switched off.

## Translate on read, leaving the world untouched

```java
FalcoAnvilLoader.builder()
    .migration(ChunkMigrationMode.IN_MEMORY)
    .build(worldRoot, dimension);
```

The world on disk stays readable by the older server it came from, and a wrong rule cannot damage
anything permanently. The translation is paid again on every load of every outdated chunk.

## Translate once and write it back

```java
FalcoAnvilLoader.builder()
    .migration(ChunkMigrationMode.ON_DISK)
    .build(worldRoot, dimension);
```

Each chunk is translated once and then never again. **This rewrites the world.** Afterwards its
chunks carry the running server's data version and the older server can no longer read them.

## Put the backup somewhere else

```java
FalcoAnvilLoader.builder()
    .migration(ChunkMigrationMode.ON_DISK)
    .migrationBackup(Path.of("/backups", "lobby"))
    .build(worldRoot, dimension);
```

Nothing removes the backup — this slot only moves it. The default is
`<worldRoot>/falco-migration-backup/<dimension>`, beside the region directory and never inside it.

## Check it worked

A loader in either active mode says so on startup, with what the mode costs. Both modes are
otherwise invisible from outside. After an `ON_DISK` run, the backup directory holds a copy of every
region file that was written.

## If it does not work

A loader that **fails to build** after `migration(...)` means no `ChunkMigrator` is on the classpath;
add `falco-migration`. A chunk that is still declined after migration is below `DataVersion` `1519`
— the engine does not attempt those.

A block that still becomes air had no rule. Migration only fixes renames someone has written down.

See also: [Reference Migration modes](../reference/migration-modes.md) for every mode, slot and floor ·
[Explanation How world migration works](../explanation/how-world-migration-works.md) for why the backup is mandatory ·
[Explanation The chunk version guard](../explanation/the-chunk-version-guard.md)
