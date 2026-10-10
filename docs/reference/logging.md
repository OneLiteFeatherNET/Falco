# Logging

Which classes log, what the messages look like, how they are throttled and at which level each one
is emitted. For `falco-anvil`.

> **This page is a generation target, currently written by hand.**
> Source of truth: the `LOGGER` fields and call sites in
> [`net.onelitefeather.falco.anvil`](https://github.com/OneLiteFeatherNET/Falco/tree/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil).

## Classes that own a logger

| Class | Logger field | Logs |
| --- | --- | --- |
| [`FalcoAnvilLoader`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java) | `LOGGER` | Every message carrying chunk, region or dimension context. |
| [`BlockPaletteResolver`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/BlockPaletteResolver.java) | `LOGGER` | A substituted block name, once per distinct name. |
| [`BiomePaletteResolver`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/BiomePaletteResolver.java) | `LOGGER` | A substituted biome name, once per distinct name. |

`RegionFile`, `SectorAllocator`, `BitPacker`, `ChunkCompression`, `NbtReads`, `PaletteData`,
`SectionCodec`, `RegionConstants` and `AnvilDiagnostics` own no logger and emit nothing. They throw
with the facts they hold and the loader adds the context.

## Message trailer

| | |
| --- | --- |
| Chunk-scoped messages | `... chunk=[{},{}] region={} dim={}` |
| Region-scoped messages | `... region={} dim={}` |

Example: `Failed to load the chunk chunk=[{},{}] region={} dim={}`.

## Levels

| Level | Emitted for |
| --- | --- |
| `INFO` | Loader opening; a close with no errors; a region file copied to the migration backup directory before its chunks are migrated (`Copied {} to {} before migrating its chunks on disk`, [`FalcoAnvilLoader.java:2203`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)). |
| `WARN` | A throttled data problem; a close with errors; the skip breakdown. |
| `ERROR` | A failed chunk load; a failed chunk save; a failed group in `saveChunks`; a region file that could not be closed. |
| `DEBUG` | A region file opened or closed; the first chunk found with no region file or no entry in one. |
| `TRACE` | Chunk unloads; sections skipped as out of world. |

## Throttling

| Report | Key | Emitted |
| --- | --- | --- |
| `reportUnknownBlock(String)` | block name | first occurrence per distinct name, while fewer than `MAX_TRACKED_NAMES` names are tracked |
| `reportUnknownBiome(String)` | biome name | first occurrence per distinct name, same cap |
| `reportPartialChunk(String)` | the chunk's `Status` value | first chunk per distinct status, same cap |
| `reportUnsupportedChunkVersion(String)` | stored `DataVersion`, or `UNKNOWN_DATA_VERSION` | first occurrence per distinct version |
| `reportMissingRegionFile()` | — | once per loader |
| `reportMissingChunkEntry()` | — | once per loader |
| `reportSectionOutOfRange()` | — | once per loader; not called by the loader |

| Constant | Value | Effect |
| --- | --- | --- |
| [`AnvilDiagnostics.MAX_TRACKED_NAMES`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java) | `64` | Distinct names tracked per category. Past it, further distinct names are substituted without a log line; `unknownBlockCount()` saturates at 64. |
| [`AnvilDiagnostics.UNKNOWN_STATUS`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java) | — | Bucket for a partial chunk whose `Status` could not be read. |
| [`AnvilDiagnostics.UNKNOWN_DATA_VERSION`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java) | `"<none>"` | Bucket for a chunk that carried no `DataVersion` at all. |

Counts rise whether or not a line was emitted. The tracking sets are
`ConcurrentHashMap.newKeySet()`, the status map a `ConcurrentHashMap` and the counters `LongAdder`,
so reporting from many loader threads is safe.

## Close summary

`close()` writes one line: loaded chunks, skipped chunks, saved chunks, errors, distinct unknown
blocks, distinct unknown biomes, plus the region/dimension trailer. Level is `WARN` when the error
count is above zero, `INFO` otherwise.

A second line follows only on a run that skipped something, at `WARN`: the skipped chunks broken
into no region file, no entry in the region file, and not fully generated — with the `Status` values
of the partial chunks and their counts.

## Diagnostic counters

`AnvilDiagnostics` exposes `chunksLoaded()`, `errors()`, `chunksSkippedAsPartial()`,
`chunksSkippedAsUnsupported()`, `unsupportedChunkVersions()` (breakdown by stored version, sorted by
key), `unknownBlockCount()` and `unknownBiomeCount()`.

Related: [Explanation How the Anvil loader is built](../explanation/how-the-anvil-loader-is-built.md) for why the leaf classes stay silent ·
[Reference Exceptions and faults](exceptions-and-faults.md)
