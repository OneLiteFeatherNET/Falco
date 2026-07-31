# Why Falco has its own Anvil chunk loader

Minestom already ships a working Anvil chunk loader. Writing a second one is expensive, it is a
maintenance liability against a moving upstream, and it starts from behind on everything the first
one already does. This document answers why it was done anyway, why the result is shaped as a
three-stage pipeline rather than a faster version of the same structure, and where the decision does
not pay off. It is written for someone deciding whether to trust this code, and for whoever on this
side of the fence proposes to reopen one of these questions in a year.

Two kinds of statement appear below and are marked wherever it matters. A **structural** claim is
something read in the source of one implementation or the other; it says what the code does, not what
it costs. A **measured** claim names its benchmark, its parameters and its spread. Minestom is cited
by file and line at the pinned version `2026.06.20-26.1.2` [1], read from the published sources jar;
Falco is cited by member, because these sources move [5].

---

## What the built-in loader actually does

Starting with the case for *not* writing this, because it is stronger than the comparison table in
[`anvil-chunk-loader.md`](../anvil-chunk-loader.md) makes it look.

`net.minestom.server.instance.anvil.AnvilLoader` reads and writes the Anvil region format, resolves
block and biome palettes against the server registries, restores heightmaps from NBT
(`instance/anvil/AnvilLoader.java:140`), handles `level.dat` through `loadInstance` and
`saveInstance` (`:96`, `:332`), and — the part Falco still does not match — keeps every chunk-level
tag it does not itself understand in the chunk's tag handler and writes it back on save (`:144-151`).
A vanilla world saved through `AnvilLoader` keeps its `structures`, its `block_ticks` and its
`fluid_ticks`. The same world saved through `FalcoAnvilLoader` loses all of them, because `snapshot`
builds a fixed key set and nothing else survives (`FalcoAnvilLoader#snapshot`) [5].

So the honest starting position is that the built-in loader is more complete as a world tool, and
that this loader is the narrower instrument. What it is narrower *for* is the subject of the rest of
this document.

## Where the parallelism is nominal rather than real

`AnvilLoader` reports both parallel capabilities as available:

```java
public boolean supportsParallelLoading() { return true; }   // AnvilLoader.java:587-589
public boolean supportsParallelSaving()  { return true; }   // AnvilLoader.java:592-594
```

`InstanceContainer.retrieveChunk` reacts to the first of these by starting a virtual thread per chunk
(`instance/InstanceContainer.java:362-372`). The threads are real. What they are allowed to do
concurrently is the question.

Every load goes through `RegionFile.readChunkData`, and that method holds one `ReentrantLock` —
declared at `instance/anvil/RegionFile.java:42`, one instance per open region file — across its
entire body (`:67-92`):

```java
lock.lock();
try {
    ...
    file.seek((long) (location >> 8) * SECTOR_SIZE);   // :73
    int length = file.readInt();                       // :74
    int compressionType = file.readByte();             // :75
    byte[] data = new byte[length - 1];
    file.read(data);                                   // :85
    return TAG_READER.read(new ByteArrayInputStream(data), compression);   // :88
} finally { lock.unlock(); }
```

The last line is the one that matters. `BinaryTagIO.Reader.read` inflates the payload **and** parses
it into a `CompoundBinaryTag`; both happen at `:88`, inside the lock. So the seek, the byte transfer,
the zlib inflate and the NBT parse are one critical section per region file. This is a statement
about the source, not a measurement.

The size of the resource being guarded is what turns that into a practical problem. A region file
addresses 32×32 chunks [2], so a server loading a spawn area, a lobby or any contiguous piece of
world is dispatching many virtual threads at one lock. They queue. Adding threads adds queue, not
throughput. `supportsParallelLoading() == true` is therefore true of the dispatch and not of the
work.

The structure has a plain historical explanation rather than a careless one: Minestom's `RegionFile`
names Hephaistos as its reference implementation in its own class javadoc (`RegionFile.java:23`) [7],
and it uses `RandomAccessFile`, whose `seek`/`read` pair is stateful and genuinely cannot be shared
between threads without exclusion. Once the file handle is a cursor, a lock around the read is
forced. Everything else ended up inside it because it was already there.

The same pattern appears twice more, and neither is about the region lock:

- **Saving holds the chunk's *write* lock across the whole serialisation** of every section —
  palettes, block entities, biome lookups, packing (`AnvilLoader.java:420-519`). A write lock
  excludes readers, so for the duration of encoding a chunk nothing may look at it.
- **`saveChunks` is not overridden**, so the interface default applies: `phaser.register()` and one
  unbounded virtual thread per chunk (`instance/ChunkLoader.java:62-82`). Its `catch` branch
  (`:71-73`) reports the exception and returns *without* calling `phaser.arriveAndDeregister()`, so a
  single `Throwable` escaping `saveChunk` leaves a registered party that never arrives and
  `phaser.arriveAndAwaitAdvance()` at `:76` blocks for good. That is a liveness defect, read from the
  source and not provoked here.

## What the measurement says, and what it does not

`RegionFileComparisonBenchmark` measures the two region file implementations against each other on
the same stored bytes, through the same Adventure writer, at the same compression level, from a
stored chunk to a parsed `CompoundBinaryTag` [6]. It lives in `net.minestom.server.instance.anvil`
because Minestom's `RegionFile` is package-private. Each benchmark thread is handed a **different**
chunk of the **same** region file, so no two threads contend for one location entry; the only shared
resource is the file and whatever guards it — which is exactly the variable under test.

Conditions: 200 distinct block states, one fork with `-Xms1g -Xmx1g`, 3 warmup and 5 measurement
iterations of one second each, on a 16-core machine that was **not idle**. JMH takes one `-t` per
run, so the thread count was varied by running the benchmark four times. Microseconds per operation,
mean ± error as JMH reported it [4]:

| Threads | Falco read | Minestom read | | Falco write | Minestom write |
| ---: | ---: | ---: | --- | ---: | ---: |
| 1 | 1 203 ± 123 | 1 060 ± 55 | 1.14× **slower** | 2 659 ± 212 | 2 601 ± 100 |
| 2 | 1 181 ± 31 | 2 200 ± 445 | 1.86× faster | 2 637 ± 204 | 2 643 ± 153 |
| 4 | 1 378 ± 84 | 11 021 ± 16 470 | 8.00× faster | 2 672 ± 43 | 2 678 ± 115 |
| 8 | 2 438 ± 252 | 530 905 ± 1 928 261 | not usable | 3 098 ± 658 | 3 102 ± 245 |

Four things follow, two of which do not flatter this loader.

**Single-threaded, Falco is the slower loader.** By 14 % at 200 distinct states, and by 20 % at 8
states, where the payload is smaller and the fixed cost of the pipeline is a larger share of it. This
is not noise hiding a win: the direction is consistent across both parameter values. The pipeline has
a setup cost — the version counter on every entry, the strict NBT accessors, the length validation
against the sector allocation — and with no contention there is nothing to win it back from. The
claim was always that the gain is in lock granularity. This row is what that claim costs when there
is no lock to contend for.

**Writing is a tie at every thread count**, between 1.00× and 1.05× in either direction. Both
implementations take a lock for the sector allocation and the header update, so there is nothing
structural to gain on this path, and the numbers agree. Falco's header write is 8 bytes against
Minestom's full 8 192-byte rewrite (`RegionFile#writeEntry` against `RegionFile.java:182-201`), and
that difference does not show up here at all — it is a crash-window argument, not a throughput one.

**Reading inverts from two threads on**, and the gap then widens with every thread added: 1.86× at
two, 8.00× at four, and beyond arithmetic at eight. Falco's own cost grows by roughly a factor of two
from one thread to eight, which is what several threads sharing one page cache and one disk look
like; Minestom's mean grows by a factor of five hundred over the same range.

**The eight-thread row must not be quoted as a factor.** Its error, ±1 928 261 µs/op, is nearly four
times its mean of 530 905. That is not a measurement of a cost; it is a measurement of the absence of
one. What the row legitimately shows is a loss of predictability — Minestom's read time scatters over
three orders of magnitude while Falco stays inside ±10 % — and for a server that is the worse of the
two failures, because an unpredictable load latency is what a tick budget cannot absorb.

The same caution applies, more weakly, one row up, and this document states it where `STATUS.md`
does not. At four threads Minestom's error of ±16 470 µs/op already exceeds its mean of 11 021, so
**8.00× is a direction, not a stable factor either.** The only row in which Minestom's spread is
smaller than its own mean is the two-thread row, 2 200 ± 445 against Falco's 1 181 ± 31. That row —
1.86× — is the one number from this table that can be quoted as a factor without a footnote. Everything
above it says that the built-in region file stops having a predictable cost, which is a stronger claim
than any multiplier and a worse property to run a server on.

One further honesty note, recorded in `anvil-chunk-loader.md` and worth repeating here: **the
magnitude of the collapse is not explained.** Pure serialisation of a ~1 060 µs operation across four
threads predicts roughly 4 200 µs, not 11 000, and certainly not the six-figure numbers at eight
threads. The direction reproduces across independent runs; the mechanism behind the magnitude —
lock convoying, scheduler interaction, page-cache behaviour under a held lock — has not been
investigated. Do not quote the factor as if it were understood [5].

## Why three stages

The measurement above says where the loss is. It does not say what to do about it, and the obvious
answer — make the lock finer, or swap the `ReentrantLock` for a `ReadWriteLock` — was rejected before
any of this was built.

The design instead splits both directions into three stages, on one rule: **no CPU-bound work happens
while a lock is held.**

```
loadChunk:  read raw bytes  ->  inflate + parse + decode  ->  apply to chunk
            (no region lock)      (no lock at all)             (chunk write lock)

saveChunk:  clone sections  ->  encode + write NBT + deflate  ->  write sectors
            (chunk read lock)     (no lock at all)                 (region lock)
```

`ChunkSaveStageBenchmark` exists to turn that design claim into a number rather than an intention. On
a 24-section chunk with 200 distinct block states [4]:

| Stage | Time | Lock held |
| --- | ---: | --- |
| Snapshot | 64 µs | chunk read lock |
| Codec, without compression | 1 356 µs | none |
| zlib compression | 2 701 µs | none |
| Transfer | 17 µs | region lock |

**About 97 % of a save runs outside any lock**, and compression alone is 63 % of the whole operation.
That second figure is the one that decided the optimisation target: it is why the loader defaults to
zlib level 2 rather than the platform default 6 — 1.83× faster compression for roughly 3 % more
stored bytes [4] — and why no effort went into the palette, which is a small fraction of the same
path.

Three consequences of the split are worth stating explicitly, because each was a decision.

**Load stage 1 takes no lock at all.** `RegionFile#readRaw` uses `FileChannel.read(ByteBuffer, long)`,
which does not mutate the channel position, so two readers of different chunks do not interfere. This
is the structural difference from `RandomAccessFile` that makes the whole thing possible. It also
introduces a hazard that the locked version does not have: a chunk being rewritten frees its sector
range, and the allocator may hand that range straight back out while a reader is still inside it.
That is not hypothetical — it was found as a live defect, with readers observing filler markers where
their own payload belonged [4]. The answer is a per-entry seqlock: each of the 1024 entries carries a
version counter that a writer raises on entry to and exit from its critical section; the reader takes
the counter, rejects an odd one, reads, and takes it again, and after `OPTIMISTIC_ATTEMPTS = 4`
failures falls back to the writer lock so a chunk rewritten in a loop cannot starve it
(`RegionFile#readRaw`).

A `ReadWriteLock` would have been the smaller change and was rejected for a specific reason: it
blocks every reader of a region for the length of a payload write, which is precisely the property
this loader exists to avoid. The alternative of deferring sector frees was rejected too — it brings
the contention back through a shared counter and grows the files [4].

**Save stage 1 takes the chunk's read lock, not its write lock.** Minestom holds the write lock across
the entire serialisation (`AnvilLoader.java:420-519`); Falco holds the read lock only long enough to
clone every `Section` and collect the block entities (`FalcoAnvilLoader#snapshot`), and everything
after that works on the clones. The chunk stays readable while it is being serialised. The price is
an allocation Minestom does not make — the palette representation is a value record, so a snapshot
allocates where Minestom mutates in place [5]. That trade is the whole point, and it is a trade.

**`saveChunks` is overridden.** Chunks are grouped by region index, one task per region, bounded by a
`Semaphore` sized to the available processors (`FalcoAnvilLoader#saveChunks`,
`FalcoAnvilLoader.saveLimit`), with every result collected in `FalcoAnvilLoader#awaitAll`. This fixes
two independent problems in the inherited default: the `Phaser` liveness defect described above, and
the fact that one thread per chunk means every chunk of a region contends for that region's lock
while all of their snapshots are alive at once. The bound is not cosmetic — file I/O does not unmount
a virtual thread from its carrier, so unbounded virtual threads over file work do not scale and have
to be bounded explicitly (recorded in [4] against JEP 444; not re-measured here).

### What the three stages do not buy

They do not make NBT parsing faster: both implementations use adventure-nbt 5.1.1. They do not make
compression faster: both use `java.util.zip`, and deflate dominates the save path in either. They do
not help a single-threaded workload at all, as the one-thread row above says outright. The design is
one bet — that the expensive work should not be serialised — and the benchmark is the test of that
bet, not of the loader in general.

## Why a read failure throws instead of returning `null`

This is the decision with the largest consequence per line of code, and it is not about performance.

`ChunkLoader.loadChunk` is declared `@Nullable Chunk` with the javadoc "the chunk, or null if not
present" (`instance/ChunkLoader.java:41-43`). `null` means *absent*, and `InstanceContainer` acts on
that meaning: in `retrieveChunk`, the consumer that receives the loader's result begins

```java
if (chunk == null) {
    // Loader couldn't load the chunk, generate it
    chunk = createChunk(chunkX, chunkZ);
    chunk.onGenerate();
}
cacheChunk(chunk);
```

(`instance/InstanceContainer.java:335-347`). The generated replacement is a normal, dirty, cached
chunk. The next `saveChunk` writes it over the stored bytes.

`AnvilLoader.loadChunk` collapses every failure into that path:

```java
try {
    return loadMCA(instance, chunkX, chunkZ);
} catch (Exception e) {
    MinecraftServer.getExceptionManager().handleException(e);
    return null;                      // AnvilLoader.java:115-120
}
```

The `catch` is `Exception`, so a transient IO error, a temporarily unavailable mount, a truncated
region file, an unsupported compression scheme and a parser bug all produce the same result: the
chunk is reported absent, regenerated, and destroyed on the next save. The operator sees one handled
exception and a world that looks intact.

Two of the entries in that set are not hypothetical, and both are visible in the same file:

- `Objects.requireNonNull(Block.fromKey(blockName), "Unknown block " + blockName)`
  (`AnvilLoader.java:263`). One modded or newer-version block name anywhere in the chunk throws an
  NPE out of `loadSections`, which the `catch (Exception)` above swallows. The chunk is regenerated.
- `file.read(data)` at `RegionFile.java:85` discards its return value. `RandomAccessFile.read` may
  return fewer bytes than requested; the tail of `data` then stays zero-filled and is handed to the
  NBT parser, which fails, which lands in the same `catch`.

The governing rule Falco adopted instead: **a chunk that exists on disk but cannot be read must not
be reported as absent.** `FalcoAnvilLoader#loadChunk` returns `null` for exactly three cases, all of
which genuinely mean absent — no region file (`FalcoAnvilLoader#acquireRegion` without `create`), no
location entry for the chunk (`RegionFile#readEntry`), and a chunk present but not fully generated
(`FalcoAnvilLoader#isFullyGenerated`, which matches the intent of the `Status` field [3]). Every
other failure is logged with the chunk, region and dimension, reported to the exception manager and
rethrown as `AnvilChunkException` (`FalcoAnvilLoader#failedLoad`). `InstanceContainer` then completes
the load future exceptionally (`InstanceContainer.java:367-371`) instead of generating, the chunk
stays unloaded, and the bytes on disk are untouched. `AnvilChunkException` is unchecked because the
`ChunkLoader` interface declares no checked exceptions.

Note the asymmetry, because it is deliberate rather than an oversight: **`saveChunk` does not
throw.** It logs at error level, counts the failure and reports it (`FalcoAnvilLoader#saveChunk`). A
failed save has already lost the in-memory state either way, and propagating would additionally
abort the save of every other chunk in the batch. The one exception is a call on a **closed** loader,
which is a lifecycle error of the caller rather than a broken chunk and propagates as
`IllegalStateException`. Returning `null` there would report a chunk as absent during shutdown, which
is the same data-destroying contract in a different costume; waiting was not an option either,
because Minestom owns the load tasks and there is nothing to wait on [4].

The same reasoning drives the strict NBT layer. Every `CompoundBinaryTag` getter returns a default
for a missing or mistyped key, so `sectionData.getCompound("block_states")` on a truncated section
yields an empty compound and the section loads as air (`AnvilLoader.java:239`, `:242-249`). `NbtReads`
exists so that a missing or mistyped key is an `IOException` naming the key, the expected type and
the actual type — which converts a silent rewrite of real data into a failed load [5].

## The format is the constraint

None of the above is a free design space. Anvil is defined by the game, and every structural decision
in `RegionFile` is a consequence of the on-disk layout rather than a preference.

A region file begins with two 4 KiB sectors: a location table and a timestamp table, each with one
4-byte entry for all 32×32 chunks [2]. A location entry packs a three-byte sector offset with a
one-byte sector count, which is why `RegionConstants.MAX_SECTORS_PER_CHUNK` is 255 and why a chunk
payload cannot exceed roughly 1 MiB in place. Everything about the loader's concurrency follows from
this shape:

- **A chunk's storage is a range of sectors that can move.** Rewriting a chunk frees its old range,
  and the allocator may reuse it immediately. Lock-free reads are only correct because the location
  entry carries a version counter — the seqlock described above exists because the format allows a
  chunk to relocate, not because a lock was disliked.
- **The location table is the index, and it is the thing that must not tear.** Falco updates the
  4-byte location and 4-byte timestamp of one entry (`RegionFile#writeEntry`); Minestom rewrites all
  8 192 bytes on every dirty save (`RegionFile.java:182-201`, called at `:131`). Beyond the write
  volume, a crash during the full rewrite can damage entries of chunks that were not being saved. An
  8-byte update cannot.
- **The one-byte sector count is a hard ceiling, and the format's own answer to it is an external
  file.** When the compression scheme byte has 128 added to it, the payload lives in
  `c.<x>.<z>.mcc` next to the region file instead of in the sectors [2]. Minestom does not implement
  this. `Check.stateCondition(sectorCount >= SECTOR_1MB, ...)` at `RegionFile.java:102` throws
  `IllegalStateException` with the message *Chunk data is too large to fit in a region file*, and it
  propagates out of `saveChunk`'s `IOException`-only catch (`AnvilLoader.java:402`), so an oversized
  chunk simply cannot be saved at all. Falco writes the external file, sets
  `ChunkCompression.EXTERNAL_FLAG = 0x80`, and deletes a stale `.mcc` when a chunk shrinks again.

The external file is also where the format's constraint and the operating system's collide, and it
is worth recording because the mechanism is not obvious. The `.mcc` is the one place where a
lock-free read meets a *name in the file system* rather than a range inside the region file, and a
name is not a POSIX concept. Under POSIX a deletion detaches the name at once and keeps the unnamed
file alive for every open handle, so nothing is noticed. Windows leaves the name in the directory and
marks the file delete-pending: while any reader holds it open, every later open of that name and
every move onto it is denied. The result was a loader that broke on Windows as soon as an oversized
chunk was read while being saved — present in the last green commit, invisible on Linux, and only
reproducible on the CI runner. The fix renames onto a private name with `ATOMIC_MOVE` before
deleting, and the header entry plus the rename now happen together inside the region lock, with only
the bytes written outside it into a staging file [4]. This is reasoning from platform semantics, not
a measurement.

Two more format details were settled the same way and are recorded here so they are not re-argued:

- **The chunk length field is `1 + N`, not `5 + N`.** The format defines it as the compression byte
  plus the payload. Minestom writes `CHUNK_HEADER_LENGTH = 4 + 1` plus the payload
  (`RegionFile.java:31`, `:99`, written at `:123`) and compensates in its own reader by reading
  `length - 1` bytes (`:84`). Its files are therefore self-consistent, but every chunk it writes
  declares four bytes more than it holds, and a spec-conforming reader over-reads into sector padding
  — or past the allocation, when the payload ends within four bytes of a sector boundary.
- **LZ4 (type 4) and custom compression (type 127) are not supported, on purpose.**
  `ChunkCompression#fromId` accepts gzip, zlib and none, and rejects anything else with an explicit
  message rather than misreading it as another scheme. A world written with
  `region-file-compression=lz4` cannot be read by this loader. That is a real limitation, taken
  deliberately to avoid an extra dependency [5].

## Unknown blocks and biomes, and what "surviving a round trip" means here

The requirement is easy to state and easy to overstate, so both halves belong in the same section.

**Why it matters.** A server that loads a world containing block or biome names its registry does not
know — a world from a newer game version, a world touched by a modded tool, a world with a datapack
biome that is no longer installed — must not respond by destroying the parts it *does* understand.
Minestom's response is the `requireNonNull` at `AnvilLoader.java:263`: an unknown block name raises
an NPE out of `loadSections`, the `catch (Exception)` at `:117-120` turns it into `null`, and
`InstanceContainer` regenerates the whole chunk over data that was intact. One name the registry has
never seen costs the entire chunk, and then costs it permanently on the next save. Biomes take the
opposite and equally unhelpful route: an unknown biome silently becomes `PLAINS_ID` with no report at
all (`AnvilLoader.java:294-296`), so a world referencing missing biomes is quietly rewritten to
plains.

**What Falco actually does, stated plainly.** `BlockPaletteResolver#toId` substitutes
`Block.AIR.stateId()` and reports the name once through the diagnostics;
`BiomePaletteResolver#toId` substitutes plains and reports the name once. So the chunk survives, and
the surrounding world survives — but **the unknown block does not survive the round trip.** It is air
on load and it is air on the next save. The same is true of the biome, which becomes plains. This is
listed in `STATUS.md` under known deviations from vanilla with exactly that framing: "better than
discarding the chunk, but not what a data fixer does" [4].

The difference from Minestom is therefore one block state against one chunk, plus a log line and a
counter against silence. That is a real improvement and it is not the same thing as preservation. A
loader that genuinely preserved unknown entries would have to keep the original palette entry
alongside the resolved state id and write the original back on save, which nothing in this codebase
does today. Two further honest limits sit next to it:

- **There is no DataFixer and no `DataVersion` migration.** `MinecraftServer.DATA_VERSION` is written
  into every saved chunk, but the stored `DataVersion` of a chunk being read is never inspected. Data
  from an older world version is interpreted with the current schema [5].
- **Diagnostics saturate.** `AnvilDiagnostics` tracks at most `MAX_TRACKED_NAMES = 64` distinct names
  per category, so once 64 distinct unknown block names have been seen, further distinct names are
  substituted silently and `unknownBlockCount()` stops rising [5]. The cap exists because a heavily
  modded or corrupt world can contain an unbounded number of distinct names; the consequence is that
  the counter is a floor, not a total.

The conclusion a reader should draw is narrow. Use this loader for worlds the server owns. It will
not destroy a chunk because of a name it does not recognise, and it will tell you the name. It is not
a migration tool and it is not an editor for vanilla worlds you intend to open in the client again —
for that, the built-in loader's preservation of unknown chunk tags and `level.dat` is the better
behaviour and is documented as such above.

## Where this leaves the decision

The loader is worth having when several threads read chunks of the same region concurrently, when a
read failure must stay visible rather than be silently replaced by a generated chunk, and when a
world may contain blocks or biomes the server does not know. It is worth less than nothing when
chunks are loaded one at a time — it is measurably slower there — and it is the wrong tool for a
world that has to remain interchangeable with vanilla, because it drops every chunk-level tag it does
not itself write.

It is also opt-in by construction. Nothing switches to it: a server keeps whatever loader it uses
until it constructs a `FalcoAnvilLoader` explicitly, or passes a `ChunkLoaderFactory` to the map
provider of Aves [8], which is a functional interface neither library depends on the other for. Every
public type of the package is `@ApiStatus.Experimental` [5].

---

## Sources

1. **Minestom** — source repository, https://github.com/Minestom/Minestom. All Minestom file and line
   references in this document are to version `2026.06.20-26.1.2`, read from the published sources
   jar `net.minestom:minestom:2026.06.20-26.1.2`. Files cited:
   `net/minestom/server/instance/anvil/AnvilLoader.java`,
   `net/minestom/server/instance/anvil/RegionFile.java`,
   `net/minestom/server/instance/ChunkLoader.java`,
   `net/minestom/server/instance/InstanceContainer.java`.
2. **Region file format** — Minecraft Wiki, https://minecraft.wiki/w/Region_file_format. Source for
   the sector size, the two header sectors, the location-entry layout, the one-byte sector count and
   the external `.mcc` mechanism at compression scheme + 128.
3. **Chunk format** — Minecraft Wiki, https://minecraft.wiki/w/Chunk_format. Source for the
   chunk-level tag set (`DataVersion`, `xPos`, `zPos`, `yPos`, `Status`, `sections`,
   `block_entities`, `Heightmaps`, `structures`, `block_ticks`, `fluid_ticks`) and for the
   `palette` / `data` layout of a section.
4. **`STATUS.md`** — this repository. The working record: benchmark results with their conditions,
   the decision table, the defects found and fixed, and the claims that were investigated and
   refuted.
5. **`docs/anvil-chunk-loader.md`** — this repository. The loader in detail: the twenty-row comparison
   with the built-in loader, the error-handling contract, the logging schema and the explicit list of
   what the loader does not do.
6. **`docs/benchmarks.md`** — this repository. Benchmark methodology, what each benchmark measures,
   and the standing caveats about what the numbers are not.
7. **Hephaistos** — `RegionFile.kt`, named by Minestom's own `RegionFile` class javadoc
   (`RegionFile.java:23`) as its reference implementation, at
   https://github.com/Minestom/Hephaistos/blob/master/common/src/main/kotlin/org/jglrxavpok/hephaistos/mca/RegionFile.kt.
   Cited here as the attribution Minestom itself gives; the linked file was not independently
   inspected for this document.
8. **Aves** — OneLiteFeather's utility library, https://github.com/OneLiteFeatherNET/Aves. The map
   provider that can be handed a `ChunkLoaderFactory`.
