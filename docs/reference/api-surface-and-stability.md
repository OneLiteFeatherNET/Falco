# API surface and stability

Which packages are public, what stability each carries, and which types are annotated
`@ApiStatus.Experimental`.

> **This page is a generation target, currently written by hand.**
> Source of truth: the `@ApiStatus` annotations on the public types of the
> [Falco repository](https://github.com/OneLiteFeatherNET/Falco). The per-type list below is what a
> generator would replace first.

## Per package

| Package | Module | Stability | Binary-compatibility baseline |
| --- | --- | --- | --- |
| `net.onelitefeather.falco.anvil` | `falco-anvil` | experimental — all 24 public types annotated | checked against the last release |
| `net.onelitefeather.falco.light` | `falco-light` | experimental — all public types annotated | checked against the last release |
| `net.onelitefeather.falco.instance` | `falco-instance` | see the module Javadoc | checked against the last release |
| `net.onelitefeather.falco.migration` | `falco-migration` | experimental — all public types annotated | **excluded** — not yet released |

**Experimental means:** signatures, class layout and behaviour may change in a minor release. Do not
rely on these types in code you cannot adapt.

`ChunkMigrationMode` and `ChunkMigrator` live in `net.onelitefeather.falco.anvil` but belong to the
migration feature, and carry the same experimental annotation.

## `net.onelitefeather.falco.anvil` — the annotated types

[`FalcoAnvilLoader`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java),
[`RegionFile`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/RegionFile.java),
[`RegionConstants`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/RegionConstants.java),
[`ChunkCompression`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkCompression.java),
[`BitPacker`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/BitPacker.java),
[`PaletteData`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/PaletteData.java),
[`PaletteEntryResolver`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/PaletteEntryResolver.java),
[`BlockPaletteResolver`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/BlockPaletteResolver.java),
[`BiomePaletteResolver`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/BiomePaletteResolver.java),
[`NbtReads`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/NbtReads.java),
[`SectionCodec`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/SectionCodec.java),
[`AnvilDiagnostics`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java),
[`AnvilChunkException`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilChunkException.java),
[`AnvilFault`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilFault.java),
[`AnvilFormatException`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilFormatException.java),
[`ChunkDataException`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkDataException.java),
[`ChunkLocation`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkLocation.java),
[`RegionFormatException`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/RegionFormatException.java),
[`ChunkVersionPolicy`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkVersionPolicy.java),
[`DefaultChunkVersionPolicy`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/DefaultChunkVersionPolicy.java),
[`UnknownEntryPolicy`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/UnknownEntryPolicy.java),
[`DefaultUnknownEntryPolicy`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/DefaultUnknownEntryPolicy.java),
[`ChunkMigrationMode`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkMigrationMode.java) and
[`ChunkMigrator`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkMigrator.java).
(The package-private `SectorAllocator` and `ServiceResolution` carry no annotation and are not among
the twenty-four.)

## Entry points and signatures

| Type | Declaration | Notes |
| --- | --- | --- |
| `FalcoAnvilLoader` | `public FalcoAnvilLoader(Path worldRoot, Key dimension)` | Uses classpath discovery for both the version policy and the unknown-entry policy. |
| `FalcoAnvilLoader` | `public FalcoAnvilLoader(Path worldRoot, Key dimension, int openRegionLimit)` | The number of region files held open. The two-argument form uses `FalcoAnvilLoader.DEFAULT_OPEN_REGION_LIMIT`, which is `64`. |
| `FalcoAnvilLoader.builder()` | `Builder#build(Path worldRoot, Key dimension)` | Each slot is a setter on `FalcoAnvilLoader.Builder`. The migration slots are described in [Migration modes](migration-modes.md), the policy slots in [Replaceable policies](replaceable-policies.md). |
| `FalcoInstance` | `public FalcoInstance(UUID uuid, RegistryKey<DimensionType> dimensionType)`, `public static Builder builder(RegistryKey<DimensionType> dimensionType)` | The builder is immutable: every method returns a new one. |
| `FalcoSharedInstance` | `public FalcoSharedInstance(UUID uuid, InstanceContainer instanceContainer)` | The container must already be registered. |
| `ChunkLightScheduler` | `public ChunkLightScheduler(ChunkLightService service)` | One scheduler per instance; a second instance is refused with `IllegalStateException`. |

## Chunk lifecycle listener

`ChunkLifecycleListener` has a default for each method, so an implementation overrides only what it needs:

```java
instance.lifecycle().addListener(new ChunkLifecycleListener() {
    @Override
    public void onPublish(ChunkLifecycleEvent event) { }   // visible to the world, before onLoad

    @Override
    public void onLoad(ChunkLifecycleEvent event) { }

    @Override
    public void onTick(ChunkLifecycleEvent event) { }

    @Override
    public void onUnload(ChunkLifecycleEvent event) { }

    @Override
    public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) { }
});
```

The builder shortcut `FalcoInstance.Builder#chunkLifecycle(Consumer<Chunk>, Consumer<Chunk>)` covers the load
and unload callbacks only.

## Read-only section access

`getSections()` and `getSection(int)` materialise every section they hand back, because Minestom's contract
lets a caller write into the result. On a lazy chunk that turns a read into 24 allocations.
`Heightmap#getHeight` does the same for the sections it descends through. The storage of a `FalcoChunk` is
reached through `chunk.storage()`, which offers read-only counterparts:

| To read | Use | Not |
| --- | --- | --- |
| one section | `view(int)` | `section(int)` |
| all sections | `views()` | `sections()` |
| how many exist | `materialisedSections()` | — |
| whether one is still the shared empty | `shared(int)` | — |

## Light engine types

| Type | Responsibility |
| --- | --- |
| `FalcoLightingChunk` | The drop-in, a `DynamicChunk` subclass. Five overrides and no computation: `setBlock` reports the changed position, `onLoad` reports a change of unknown extent, `tick` triggers the pass, `invalidate` drops the cached light packet, and `onLightUpdated` — its half of `LightUpdateAware` — resends it. |
| `ChunkLightScheduler` | Everything else — the dirty set, the once-per-tick trigger, area forming, the executor, back pressure and the staleness rule — so a reader looking for the behaviour finds it in one place. |
| `ChunkArea` | A chunk coordinate pair, and the flood fill that cuts a dirty set into capped connected groups. Pure arithmetic, no Minestom. |
| `ChunkLightArea` | Computes one group in a single pass: reads its chunks plus one ring, exchanges borders until settled, writes back the group and never the ring. Keeps the light of each chunk it has computed, so the next pass replays the reported changes on it. |
| `LightUpdateAware` | The hook Minestom does not have, so a computed result can be delivered without knowing which chunk type it belongs to. |

## Opt-in only

Nothing switches to a Falco type by itself. A server keeps using whatever loader, light path and
instance it uses today until it constructs the Falco equivalent explicitly. A migration engine on
the classpath migrates nothing until a caller selects a `ChunkMigrationMode`.

Related: [Reference Modules and coordinates](modules-and-coordinates.md) · [Reference Supported versions](supported-versions.md) ·
[Build Setup](../contributing/build-setup.md) for what `checkApiCompatibility` does
