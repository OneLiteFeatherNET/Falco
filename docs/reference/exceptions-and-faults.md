# Exceptions and faults

The exception types `falco-anvil` throws, which ones cross the `ChunkLoader` boundary, and what a
caller can catch.

> **This page is a generation target, currently written by hand, and it is incomplete.**
> Source of truth: the exception types in
> [`net.onelitefeather.falco.anvil`](https://github.com/OneLiteFeatherNET/Falco/tree/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil).
> `RegionFormatException.Reason` declares five constants and `ChunkDataException.Reason` six; only
> the two named below are recorded in this wiki. Read the enums for the full set until the generator
> lands.

## Types

### `AnvilFault`

| | |
| --- | --- |
| Kind | sealed interface |
| Catchable | no — it is not a `Throwable` |
| Carries | `ChunkLocation` |
| Source | [`AnvilFault`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilFault.java) |

The common contract of every Anvil failure, for a pattern switch after a broad catch.

### `AnvilFormatException`

| | |
| --- | --- |
| Kind | checked, abstract, sealed |
| Implements | `AnvilFault` |
| Source | [`AnvilFormatException`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilFormatException.java) |

Root of everything the file itself got wrong.

### `RegionFormatException`

| | |
| --- | --- |
| Kind | checked, final |
| Extends | `AnvilFormatException` |
| Reasons | five constants on `RegionFormatException.Reason` |
| Source | [`RegionFormatException`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/RegionFormatException.java) |

Broken `.mca` structure: header shorter than 8192 bytes, implausible length field, overlapping
sectors, unsupported compression scheme.

### `ChunkDataException`

| | |
| --- | --- |
| Kind | checked, final |
| Extends | `AnvilFormatException` |
| Reasons | six constants on `ChunkDataException.Reason` |
| Source | [`ChunkDataException`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkDataException.java) |

Broken chunk NBT: missing key, wrong type, empty palette, palette index out of range.

### `AnvilChunkException`

| | |
| --- | --- |
| Kind | unchecked, non-sealed |
| Implements | `AnvilFault` |
| Source | [`AnvilChunkException`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilChunkException.java) |

The boundary type. Unchecked so it can cross `ChunkLoader`, which declares no checked exceptions.
This is what `loadChunk` throws; the checked type above is its cause.

### `ChunkLocation`

| | |
| --- | --- |
| Kind | record |
| Components | `(chunkX, chunkZ, region, dimension)` |
| Source | [`ChunkLocation`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkLocation.java) |

The single definition of the log context. `ChunkLocation.toString()` is the one place the context
format is defined.

## Recorded reason constants

| Constant | On | Meaning |
| --- | --- | --- |
| `UNSUPPORTED_CHUNK_VERSION` | `ChunkDataException.Reason` | The chunk is below the version floor, or carries the pre-`21w43a` `Level` layout. |
| `MISSING_OR_MISTYPED_KEY` | `ChunkDataException.Reason` | A key the caller requires is absent or holds the wrong tag type — including a chunk claiming `Status: minecraft:full` while carrying neither `sections` nor `Level`. |

## What each entry point does on failure

| Method | Absent chunk | Unreadable chunk | Closed loader |
| --- | --- | --- | --- |
| `loadChunk` | returns `null` | throws `AnvilChunkException` | throws `IllegalStateException` |
| `saveChunk` | — | logs at `ERROR`, counts, reports to the exception manager; does **not** throw | throws `IllegalStateException` |
| `saveChunks` | — | reports per group in `awaitAll` | throws `IllegalStateException` |

`loadChunk` returns `null` only for three genuinely-absent cases: no region file, no location entry
for the chunk, and a chunk that is present but not fully generated.

## Types that are not Anvil faults

`BitPacker`, `SectorAllocator` and `PaletteData.singleValue` throw `IllegalArgumentException` and
`IllegalStateException` for programmer errors. Real IO stays `java.io.IOException` — `FileChannel`,
`Files` and `channel.force` produce it and nothing wraps it.

Related: [Explanation Why a second Anvil loader](../explanation/why-a-second-anvil-loader.md) for why a read failure throws instead of
returning `null` · [Reference Logging](logging.md) · [How-to Load an Anvil world](../how-to/load-an-anvil-world.md)
