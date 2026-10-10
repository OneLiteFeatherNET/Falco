# Scope and non-goals

What Falco deliberately does not do, per module, stated plainly. Each of these is a reason to keep
using another loader, another engine or another tool — and knowing them up front prevents a class of
bug report.

Falco is four independent modules with no dependency between them in either direction. None of them
is a world-management layer: `falco-anvil` handles the `region/` directory of a single dimension and
nothing else, `falco-light` computes light and stores nothing, `falco-instance` cleans up after
itself and claims no speed advantage, and `falco-migration` translates chunks forward and refuses
everything else.

## The Anvil chunk loader

Stated plainly, because each of these is a reason to keep using another loader or another tool.

* **No `entities/` or `poi/` region handling.** Only the `region/` directory is read and written.
  Entity and point-of-interest region files of a vanilla world are ignored and are neither migrated
  nor kept in sync. The Minestom `AnvilLoader` does not handle them either.
* **No DataFixer and no DataVersion migration.** `MinecraftServer.DATA_VERSION` is written into
  every saved chunk ([`FalcoAnvilLoader.dataVersion`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java),
  written by [`FalcoAnvilLoader#snapshot`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)),
  and the stored `DataVersion` of a chunk being read is now inspected — but only far enough to
  reject one below the floor described in [The version floor](the-chunk-version-guard.md#the-version-floor). A chunk that
  clears the floor is read with the current schema regardless of how old its `DataVersion` actually
  is; nothing here runs a DataFixer or reinterprets older data under its own schema. Convert worlds
  with the vanilla client or another tool first.
* **No LZ4 (compression type 4) and no custom compression (type 127).** `ChunkCompression.fromId`
  accepts gzip (1), zlib (2) and uncompressed (3), with the external bit `0x80` masked off; every
  other id raises `The compression scheme <id> is not supported. Only gzip (1), zlib (2) and none (3)
  can be read` ([`ChunkCompression#fromId`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkCompression.java)).
  Unsupported compression therefore fails with an explicit error instead of being misread as
  another scheme. Saving always uses zlib
  ([`FalcoAnvilLoader#saveChunk`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)).
* **No corruption recovery and no header rebuilding.** A header shorter than 8192 bytes, or a
  location table with overlapping sector ranges, fails the open
  ([`RegionFile#readHeader`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/RegionFile.java),
  [`SectorAllocator#reserve`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/SectorAllocator.java)).
  There is no scan-and-repair mode, no orphaned-sector reclamation and no defragmentation; freed
  sectors are reused but the file is never shrunk
  ([`SectorAllocator#free`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/SectorAllocator.java)).
* **No `level.dat` handling.** `loadInstance` and `saveInstance` are not overridden, so world
  metadata (seed, spawn, game rules, world age) is neither read nor written. `LastUpdate` is
  written as a constant `0`
  ([`FalcoAnvilLoader#snapshot`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)).
* **No preservation of unknown chunk-level tags.** `snapshot` writes a fixed key set
  ([`FalcoAnvilLoader#snapshot`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)),
  so `Heightmaps`, `structures`, `block_ticks`, `fluid_ticks` and everything else present in a
  vanilla chunk are lost when that chunk is saved. Heightmaps are not restored on load either.
  Use this loader for worlds the server owns, not as an editor for vanilla worlds you intend to
  open in the client again.
* **No configurable compression level.** `compressionLevel` is assigned
  `ChunkCompression.DEFAULT_LEVEL` in the constructor and is never read from anywhere else
  ([`FalcoAnvilLoader`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)).
  There is no constructor parameter and no setter for it, so a server that wants level 6 on disk
  cannot ask this loader for it. The level is a deliberate default and currently a fixed one.
* **No loading of partially generated chunks.** A chunk whose `Status` is not `minecraft:full` is
  reported as absent — `loadChunk` returns `null` after a throttled warning
  ([`FalcoAnvilLoader#isFullyGenerated`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)).
  `InstanceContainer` then generates a replacement (`instance/InstanceContainer.java:336-343`), which
  the next save writes over the partial chunk. This is the one place where the loader accepts the
  overwrite it otherwise exists to prevent, and it does so on purpose: a partially generated chunk
  has no meaningful contents to preserve, and Minestom's `AnvilLoader` skips it as well
  (`instance/anvil/AnvilLoader.java:133-142`). A world mid-generation is still not something to point
  either loader at.
* **No light computation.** Stored `SkyLight` and `BlockLight` arrays are read into the section and
  written back out unchanged
  ([`FalcoAnvilLoader.DecodedSection#applyTo`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java),
  [`FalcoAnvilLoader#encodeSection`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)).
  A chunk stored without light stays dark until something else lights it; the
  [How the light engine works](how-the-light-engine-works.md) is that something, and it is a separate module with no dependency in
  either direction.
* **No durability guarantee per save, and no crash consistency.** A save writes the payload and the
  8-byte header entry through the channel and returns; nothing is forced to the device.
  `RegionFile.flush` is `channel.force(false)` and it is called at exactly two places — when a
  region file is evicted or unloaded, and when the loader is closed
  ([`RegionFile#flush`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/RegionFile.java),
  [`FalcoAnvilLoader#closeQuietly`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java),
  [`FalcoAnvilLoader#close`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)).
  A power loss between a `saveChunk` and that point loses the save, and because the payload and the
  header entry are two writes with no barrier between them, it can also leave an entry pointing at
  sectors whose bytes never reached the device. The region lock orders writers against each other;
  it orders nothing against the page cache. There is no journal, no write-ahead log and no
  torn-write detection on read beyond the length and sector-range checks. This is not a regression
  against the built-in loader — Minestom opens its file `"rw"` and its `RegionFile.close()` closes
  without syncing (`instance/anvil/RegionFile.java`) — but neither loader is safe against an
  unclean shutdown, and a server that needs that needs a snapshot on the storage layer.
* **No cross-loader coordination.** Two `FalcoAnvilLoader` instances over the same region directory
  share no sector allocator, no region cache and no lock. The per-entry version counters and the
  region lock coordinate threads inside one loader, not processes and not two loaders. One loader per
  dimension directory, and nothing else writing that directory while the server runs.


## The light engine

- **Sky light is never compared with Minestom's.** Every comparison on this page runs against
  `BlockLight.buildInternalQueue` and `LightCompute.compute`, which are the built-in *block* light
  path. `LightEngineEquivalenceTest` compares block light only, and
  `LightEngineComparisonBenchmark.falco` / `.minestom` measure block light only. Falco's sky light
  is checked against Falco's own full recalculation — that is what `SkyLightUpdateTest` and
  `SkyLightTest` do — and against nothing else. No claim of byte identity with the built-in engine
  is made for sky light on this page, and none may be quoted from it.
- **No `Light` implementation.** The engine deliberately does not implement
  `net.minestom.server.instance.light.Light`. It writes its result through `set` instead, which
  avoids depending on the internal calculation methods of that interface.
- **The exchange covers one ring of chunks.** `calculateWithNeighbours` settles the chunk and the
  eight positions around it, and an area settles its own chunks against a ring. That is enough for
  the chunks being written, but the ring itself is not settled against its own neighbours further
  out.
- **The ring of an area holds no diagonals.** It is built from the face neighbours of every chunk of
  the area, so a source in a chunk that touches the area only at a corner is missing from the result.
  `calculateWithNeighbours` reads its diagonals and does not have this gap, which is why it was not
  deprecated in favour of the area.
- **An area rebuilds every opacity table on every pass**, for each of its chunks and each of its ring
  chunks, whether or not anything in them moved. Now that the light itself is kept between passes,
  this is the largest remaining cost of a pass.
- **An area split at the cap leaves a seam for one tick.** Each part reads the other as its ring, so
  the following pass settles it.
- **`Section.clone()` discards foreign light.** Should an adapter be built later, note that
  `Section.clone()` calls `Light.sky()` / `Light.block()` outright, so any custom implementation is
  silently replaced on copy. `LightingChunk.copy()` would have to be overridden.
- **`LightPropagator` and `ChunkLightPropagator` are thread confined.** Both keep their level buffer
  and queue in instance fields and clear them per run
  ([`LightPropagator`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-light/src/main/java/net/onelitefeather/falco/light/LightPropagator.java)),
  so one instance per worker thread and never a shared one. `ChunkLightService` is the exception and
  is safe to share, because it builds a propagator inside every call. Nothing enforces the
  distinction at compile time; getting it wrong produces wrong light rather than an exception, which
  is the failure mode this engine has already been bitten by once.
- **One `ChunkLightScheduler` serves one instance.** A second instance handed to the same scheduler is
  refused with an `IllegalStateException`, because the dirty set is keyed by chunk coordinates alone
  and every instance of a server ticks with the same timestamp
  ([`ChunkLightScheduler`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightScheduler.java)).
  A server with many worlds needs one scheduler per world, and the memory bound is therefore per
  world and not global.
- **The kept light is capped and the cap is not adaptive.** `ChunkLightArea.DEFAULT_MAX_CACHED_CHUNKS`
  is 128 and `ChunkLightScheduler.DEFAULT_MAX_AREA_SIZE` is 16, both plain constants with no
  measurement behind either value. Dropping an entry costs a full propagation, which is correct but
  not free; no benchmark in this repository sweeps either constant.
- **Nothing here reports what a real server pays.** Every measurement on this page is a per-section or
  per-chunk average time from a JMH harness, and `Mode.AverageTime` reports a mean. A server's felt
  lighting cost is a tail — the tick that took too long — and no percentile is measured anywhere in
  the suite. The comparison rows are valid against their own counterparts and against nothing else.


## The instance and chunk

- **No `SharedInstance` onto a `FalcoInstance`.** Structural, see above.
- **Foreign chunk types are refused.** A `ChunkSupplier` producing anything but a `FalcoChunk` is
  rejected with a message naming the cause, because such a chunk would be accepted everywhere except
  the unload path and would then report itself as loaded forever.
- **`FalcoChunk#tick` walks its tickable map without a lock.** A concurrent `setBlock` that rehashes
  the map while the tick thread walks it can yield garbage or spin. Inherited rather than introduced
  — `DynamicChunk` has the identical race — and Minestom's `Chunk#tick` contract says the method
  "doesn't necessary have to be thread-safe". Open, and listed as such in
  [Project Status](../project-record/project-status.md#open).
- **No throughput measurement exists.** Benchmark classes for the instance and the chunk are in
  `falco-benchmarks`, but none has been run as a baseline, so there is no timing to quote.


## World migration


- **No downgrade.** The engine lifts a chunk forward. Going back is a projection with a substitution
  policy the upgrade path has no use for, and it is not built.
- **Nothing below Minecraft 1.13.** `DataVersion` under `1519` predates the flattening and holds
  numeric block ids these types do not speak. Such a chunk is declined, not attempted.
- **No entities, and no Bedrock.** The engine covers blocks, biomes and block entities. Bedrock is a
  different storage format entirely and is out of scope; see [Project Status](../project-record/project-status.md).
- **No command line tool yet.** The engine and the loader option exist; a standalone converter does
  not.
- **It cannot invent a rule it does not have.** Migration only fixes renames someone has written
  down. An unknown block with no rule still becomes air, and still gets exactly one log line.


Related: [Explanation Choosing between Falco and the built-in loader](choosing-between-falco-and-the-built-in-loader.md) ·
[Explanation Why falco-instance exists](why-falco-instance-exists.md) · [Explanation What a measurement here means](what-a-measurement-here-means.md) ·
[Project Status](../project-record/project-status.md) for what is open rather than out of scope
