# Replaceable policies

The three services `FalcoAnvilLoader` consults, their interfaces, their shipped defaults, and how
each is supplied.

> **This page is a generation target, currently written by hand.**
> Source of truth: the policy interfaces and their `META-INF/services` registrations in
> [`falco-anvil`](https://github.com/OneLiteFeatherNET/Falco/tree/main/falco-anvil).

## `ChunkVersionPolicy`

| | |
| --- | --- |
| Method | `void check(CompoundBinaryTag data, int minimumDataVersion) throws ChunkDataException` |
| Shipped default | [`DefaultChunkVersionPolicy`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/DefaultChunkVersionPolicy.java) |
| Registered by | `falco-anvil`, in its own `META-INF/services` |
| Builder slots | `versionPolicy(ChunkVersionPolicy)`, `discoverVersionPolicy()` |
| Can be disabled | yes — `versionPolicy(null)` |
| Consulted | once per load, after the raw compound is parsed, before anything is decoded |
| Source | [`ChunkVersionPolicy`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkVersionPolicy.java) |

Decides whether a chunk is readable at all. A policy only decides and throws; it does not count or
log — `FalcoAnvilLoader#checkVersion` does both regardless of which policy ran.

## `UnknownEntryPolicy`

| | |
| --- | --- |
| Methods | `String onUnknownBlock(String name, @Nullable CompoundBinaryTag properties)`, `String onUnknownBiome(String name)` |
| Returns | a replacement *name*, not an id |
| Shipped default | [`DefaultUnknownEntryPolicy`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/DefaultUnknownEntryPolicy.java) — `"minecraft:air"` and `"minecraft:plains"` |
| Registered by | `falco-anvil`, in its own `META-INF/services` |
| Builder slots | `unknownEntryPolicy(UnknownEntryPolicy)`, `discoverUnknownEntryPolicy()` |
| Can be disabled | no — `unknownEntryPolicy(null)` falls back to the default |
| Consulted | by `BlockPaletteResolver#toId` and `BiomePaletteResolver#toId` |
| Source | [`UnknownEntryPolicy`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/UnknownEntryPolicy.java) |

Decides what an unknown palette entry becomes. Throwing `AnvilChunkException` from either method
fails the chunk instead of carrying a substitute. The policy is not asked twice: if the name it
returns is itself unknown, the resolver throws `IllegalStateException`. The resolvers report the
name to `AnvilDiagnostics` and the log regardless of what the policy returns.

## `ChunkMigrator`

| | |
| --- | --- |
| Shipped default | none in `falco-anvil` — the engine lives in `falco-migration` |
| Builder slot | selecting a `ChunkMigrationMode` |
| Discovery | turned on by selecting a mode; no second call needed |
| Consulted | only when a mode is selected |
| Missing provider | the loader fails to build rather than migrating nothing |
| Source | [`ChunkMigrator`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkMigrator.java) |

Translates a chunk an older version wrote, rather than deciding what to do about it. Runs *before*
`ChunkVersionPolicy`. See [Reference Migration modes](migration-modes.md).

## Resolution rules

Implemented in
[`ServiceResolution`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ServiceResolution.java)
and shared by all three.

| Rule | Behaviour |
| --- | --- |
| Shipped default vs one foreign provider | the shipped default steps aside |
| Two *foreign* providers | throws, naming both classes |
| Builder slot and discovery both set | throws `IllegalStateException` |
| Any call to `versionPolicy(...)` / `unknownEntryPolicy(...)` | turns discovery off for that policy, including when passed `null` |
| Classloader used for discovery | the service's own, never the thread context classloader |
| When resolution runs | once, when the loader is built — not per chunk |

The opening log line of the loader reports which version policy was resolved, or the word `none`.

Related: [How-to Replace the version and unknown-entry policies](../how-to/replace-the-version-and-unknown-entry-policies.md) ·
[Explanation The chunk version guard](../explanation/the-chunk-version-guard.md)
