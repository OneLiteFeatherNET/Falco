# Migrate a world from an older version

Lift a world written by an older server to the version you run, so that blocks renamed since do not
quietly become air. You end with a loader that translates the outdated chunks it reads, either in memory
or written back to the world.

**Before you start:** `falco-migration` on the classpath ([How-to Add Falco to your build](add-falco-to-your-build.md)), a
world at `DataVersion` `1519` or above (the floor is in [Reference Migration modes](../reference/migration-modes.md#floors)),
and disk space for a backup. The backup is taken before a region file is first written, and it cannot be
switched off in `ON_DISK` mode.

## Translate on read, leaving the world untouched

1. Select `IN_MEMORY` on the builder. The world root and dimension are the ones from
   [How-to Load an Anvil world](load-an-anvil-world.md):

   ```java
   import net.kyori.adventure.key.Key;
   import net.onelitefeather.falco.anvil.ChunkMigrationMode;
   import net.onelitefeather.falco.anvil.FalcoAnvilLoader;

   import java.nio.file.Path;

   Path worldRoot = Path.of("worlds", "lobby");
   Key dimension = Key.key("minecraft", "overworld");

   FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
           .migration(ChunkMigrationMode.IN_MEMORY)
           .build(worldRoot, dimension);
   ```

   The world on disk stays readable by the older server it came from. The translation is paid again on
   every load of every outdated chunk; the reasons are in
   [Explanation How world migration works](../explanation/how-world-migration-works.md#the-two-halves).

## Translate once and write it back

2. Select `ON_DISK` instead, when you want each chunk translated once and never again. The same
   builder, with the mode changed:

   ```java
   FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
           .migration(ChunkMigrationMode.ON_DISK)
           .build(worldRoot, dimension);
   ```

   **This rewrites the world.** Afterwards its chunks carry the running server's data version and the
   older server can no longer read them.

## Put the backup somewhere else

3. Optionally, move the backup out of the world directory. Nothing removes the backup; this slot only
   moves it:

   ```java
   FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
           .migration(ChunkMigrationMode.ON_DISK)
           .migrationBackup(Path.of("/backups", "lobby"))
           .build(worldRoot, dimension);
   ```

   The default is `<worldRoot>/falco-migration-backup/<dimension>`, beside the region directory and never
   inside it. The path rules are in [Reference Migration modes](../reference/migration-modes.md#backup).

## Check it worked

A loader in either active mode says so on startup, with what the mode costs. Both modes are
otherwise invisible from outside. After an `ON_DISK` run, the backup directory holds a copy of every
region file that was written.

## If it does not work

A loader that **fails to build** after `migration(...)` means no `ChunkMigrator` is on the classpath;
add `falco-migration`. A chunk that is still declined after migration is below `DataVersion` `1519`,
the floor described in [Reference Migration modes](../reference/migration-modes.md#floors): the engine
does not attempt those.

A block that still becomes air had no rule. Migration only fixes renames someone has written down; see
[Explanation How world migration works](../explanation/how-world-migration-works.md#the-rules-and-where-their-numbers-come-from).

See also: [Reference Migration modes](../reference/migration-modes.md) for every mode, slot and floor ·
[Explanation How world migration works](../explanation/how-world-migration-works.md) for why the backup is mandatory ·
[Explanation The chunk version guard](../explanation/the-chunk-version-guard.md)
