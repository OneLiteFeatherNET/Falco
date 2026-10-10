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

## Opt-in only

Nothing switches to a Falco type by itself. A server keeps using whatever loader, light path and
instance it uses today until it constructs the Falco equivalent explicitly. A migration engine on
the classpath migrates nothing until a caller selects a `ChunkMigrationMode`.

Related: [Reference Modules and coordinates](modules-and-coordinates.md) · [Reference Supported versions](supported-versions.md) ·
[Build Setup](../contributing/build-setup.md) for what `checkApiCompatibility` does
