# How world migration works

Why a world older than the running server loses blocks quietly, what the migration engine does about
it, why its backup cannot be switched off, and where the version numbers in its rules come from.

To act on this, see [How-to Migrate a world from an older version](../how-to/migrate-a-world-from-an-older-version.md); for the modes and floors as a
table, [Reference Migration modes](../reference/migration-modes.md).

> **Experimental.** Every public type of `net.onelitefeather.falco.migration` is annotated
> `@ApiStatus.Experimental`, as are `ChunkMigrationMode` and `ChunkMigrator` in
> `net.onelitefeather.falco.anvil`.
>
> **Off unless asked for.** A deployment that puts no migration engine on the classpath carries no
> migration code at all. `falco-migration` has not been released yet, which is why it is the one
> module excluded from the `checkApiCompatibility` baseline check.

## The problem this solves

A world older than the running server loses whatever the server no longer knows by name, and it does
so quietly. The chain is short and every link is reasonable on its own:

1. A block name that Minecraft has renamed no longer resolves in the registry.
2. [`UnknownEntryPolicy`](../reference/replaceable-policies.md) substitutes air, and the loader
   logs the name **once for the entire world**.
3. The loader stamps the current `DataVersion` onto every chunk it saves.

After a save, the blocks are gone and the world claims to be current. Nothing downstream can tell
that anything was lost.

**How much that is, on one real world.** 8299 region files across two dimensions, 52 GB, every block
name in every chunk palette checked against the Minecraft 26.1.2 registry on 2026-08-05:

| | |
| --- | ---: |
| distinct block names found | 991 |
| names the registry still knows | 989 |
| `minecraft:grass` (renamed to `short_grass`) | 241708 occurrences |
| `minecraft:chain` (renamed to `iron_chain`) | 18248 occurrences |
| **blocks that would have decoded to air** | **260856** |

<sub>An inventory of stored data, not a performance measurement — it belongs to no table on
[Measured results](../reference/measured-results.md). Method: each chunk's `sections[].block_states.palette[].Name`
entries collected from the raw region files, then resolved through `Block.fromKey` on a started
Minestom registry. Reproduce by pointing the same query at your own world; the figures are properties
of that world, not of Falco.</sub>

Two names out of 991 sounds small. 260856 blocks is not, and the failure is silent, permanent after
one save, and concentrated exactly in the chunks nobody has visited since the update.

**A live world holds both forms at once.** That same world contains `grass` *and* `short_grass`,
`chain` *and* `iron_chain`. Paper migrates a chunk when it loads it and writes the migrated form
back, so what has been visited since the update is already converted and what has not still is not.
This is why a rule must never rewrite the name it renames *to*: half the world is already on the far
side of it.

## The two halves

**The engine.**
[`ChunkMigration`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-migration/src/main/java/net/onelitefeather/falco/migration/ChunkMigration.java)
runs a fixed chain of [`MigrationStep`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-migration/src/main/java/net/onelitefeather/falco/migration/MigrationStep.java)s
over one chunk's root compound and stamps the target version onto the result. Its floor is
`MINIMUM_SOURCE_VERSION` (`1519`, the release of Minecraft 1.13), because that release replaced
numeric block ids with the palette format the engine's types speak. **There is no ceiling.** A chunk
newer than the target is declined for not being older, not by a limit of the engine.

**The loader option.** `falco-anvil` names the capability and finds a provider on the classpath:

```java
public interface ChunkMigrator {
    boolean canMigrate(int sourceVersion, int targetVersion);
    CompoundBinaryTag migrate(CompoundBinaryTag data, int targetVersion) throws ChunkDataException;
}
```

The interface lives in `falco-anvil` rather than beside the engine because the dependency only runs
one way — `falco-migration` depends on `falco-anvil`, so the reverse is impossible. This is the same
shape as [`ChunkVersionPolicy` and `UnknownEntryPolicy`](../reference/replaceable-policies.md) and
resolves through the same rules.
[`FalcoChunkMigrator`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-migration/src/main/java/net/onelitefeather/falco/migration/FalcoChunkMigrator.java)
is the adapter that registers the engine, and it holds no rules of its own.

A loader told to migrate with no engine on the classpath **fails to build**. It does not start up and
migrate nothing — that would hide the exact loss the mode was selected to prevent.


`IN_MEMORY` costs time on the chunk loading path a player waits for, and it does not diminish with
uptime the way a cache would: a chunk loaded, unloaded and loaded again is translated twice. What it
buys is that the world on disk is untouched — it can still be opened by the older server it came
from, and a wrong rule cannot damage anything permanently.

`ON_DISK` converges on doing no work at all, at the price of rewriting the world. Afterwards the
world's chunks carry the running server's data version and the older server can no longer read them.
That is the point of the mode, not a side effect, and it is why the backup is not optional.

Selecting any mode other than `OFF` turns on classpath discovery of the migrator by itself. This
differs deliberately from `discoverVersionPolicy()`, where discovery is a separate opt-in: a caller
who has selected a migration mode has already said that chunks are to be migrated, and requiring a
second call would only produce loaders that were configured to migrate and quietly did not.

A loader in either mode says so on startup, with what it costs — both modes are otherwise invisible
from outside.

## The backup

**There is no way to switch it off.** `migrationBackup(Path)` sets where it goes; nothing removes it.
`ON_DISK` replaces stored chunks, a rule that turns out to be wrong is only discovered afterwards,
and by then the original is the only thing that can undo it. A world already backed up elsewhere pays
for a second copy — that cost is accepted, because the alternative is a flag whose only purpose is to
make an irreversible mistake reachable.

Three properties, each for a reason:

- **Per region file, immediately before that file is first written**, not for the whole world at
  startup. A world whose chunks are all current is never copied at all.
- **Through a `.partial` name and an atomic move.** A copy interrupted half way would otherwise sit
  there under the right name, look complete, and be skipped by the next run.
- **Beside the region directory**, at `<worldRoot>/falco-migration-backup/<dimension>` by default —
  never inside it. A region file copied into the directory the loader reads would be read back as
  world data, and the backup would become part of the world it was taken to protect.

An existing backup is never overwritten. The earlier copy is the older original; replacing it with a
file this run may already have migrated would throw away the last untouched copy.

## Why migration runs before the version guard

The [version guard](the-chunk-version-guard.md#the-version-floor) refuses a chunk below
`minimumDataVersion` and one still in the pre-1.18 `Level` layout. Migrating is precisely what turns
such a chunk into one the guard accepts — the `UnfoldLevel` step moves the contents onto the root,
and the engine stamps the target version.

Running the guard first would therefore reject every world this option exists to rescue, and the
option would only ever help worlds that never needed it. The order is pinned by a test that loads the
same chunk twice: refused without migration, loaded with it.

## The rules, and where their numbers come from

Migration only fixes what someone has written down. A block whose renamed or removed name has no rule
still becomes air after migration, because nothing in the engine can guess its replacement. The rules
are therefore the part of the engine that needs maintenance, and every number in the rule file carries
its source in a comment beside it.

[`BlockStateRules`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-migration/src/main/java/net/onelitefeather/falco/migration/BlockStateRules.java)
holds the block-state changes. Each rule carries a `since()`: the `DataVersion` the change happened
in, so a rule applies exactly when `since() > sourceVersion`.

**`since()` names the snapshot, not the release.** Two of the numbers in that file were wrong on
first writing in the same way — the final release's data version was used where the change had
already shipped in a snapshot some versions earlier. Every number in the file now carries its source
in a comment beside it, and where that source is a wiki page, the comment says so.

**Where a document was not trusted, the world was measured instead.** For `chain` → `iron_chain` the
version came from the data: every chunk's block names correlated with that same chunk's stored
`DataVersion`, across 1399 nether region files.

| `DataVersion` | Block found | Chunks |
| ---: | --- | ---: |
| 3465, 3578, 3955, 4435 | `minecraft:chain` | 4930 |
| 4556 | `minecraft:iron_chain` | 189 |

No chunk carries both, so the change happened in **(4435, 4556]** — and that world holds nothing from
between those versions, so the data cannot resolve it further.

`4556`, the upper bound, is the safe end of that interval to pick:

- **Too high** only lets the rule inspect chunks that no longer contain the old name, where its
  predicate does not match and nothing happens.
- **Too low** leaves the old name standing in every chunk between the true version and the chosen
  one — and a name the server does not know is what becomes air.

The test for that rule asserts the boundary with the two measured versions rather than round numbers,
so moving `since()` off its evidence fails it.

## Testing

- `ChunkMigrationModeTest` (`falco-anvil`) drives all three modes against real region files and the
  production loader: that `OFF` never consults a migrator, that `IN_MEMORY` leaves the file
  byte-identical, that `ON_DISK` writes back and a second run then finds nothing to do, that the
  backup is the untouched original and lies outside the region directory, and that migration runs
  before the guard.
- `FalcoChunkMigratorTest` (`falco-migration`) pins the adapter: the floor, the absent ceiling, the
  translation of the engine's unchecked failure into the loader's checked one, and that the service
  is actually registered.
- `MigrationRoundTripTest` runs a genuine 1.13 chunk through the whole chain into a real region file
  and back out through `FalcoAnvilLoader`. It exists because a defect once namespaced a chunk status
  without translating its value, and every test that asserted key by key against an in-memory
  compound missed it.

Each of these was checked by injecting the defect it exists to catch — the migration moved behind the
guard, `IN_MEMORY` writing to disk, the backup skipped, an existing backup overwritten, the service
registration removed, and `since()` moved off its measurement. Every one was caught by its own test
and by no other. See [Contributing](../contributing/README.md) for why that step is not optional here.

## References


- [`ChunkMigrationMode`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkMigrationMode.java)
  and [`ChunkMigrator`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkMigrator.java)
- [`ChunkMigration`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-migration/src/main/java/net/onelitefeather/falco/migration/ChunkMigration.java),
  [`BlockStateRules`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-migration/src/main/java/net/onelitefeather/falco/migration/BlockStateRules.java)
  and [`WorldLayout`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-migration/src/main/java/net/onelitefeather/falco/migration/WorldLayout.java)
- [How the Anvil loader is built](how-the-anvil-loader-is-built.md) — the loader this plugs into, its version floor and its
  other two policies
- [Project Status](../project-record/project-status.md) — what is open, and what was investigated and not built

Related: [Explanation The chunk version guard](the-chunk-version-guard.md) · [Explanation Scope and non-goals](scope-and-non-goals.md) ·
[Reference Migration modes](../reference/migration-modes.md)
