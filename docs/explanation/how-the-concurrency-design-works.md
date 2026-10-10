# Why the concurrency design is what it is

This document answers one question: why does `falco-anvil` guard its region files with a per-entry
seqlock, a usage count and a hard refusal after `close()`, rather than with the locks a reviewer
would reach for first — and what did it cost to get that right. It is written for someone deciding
whether to trust this code, and for whoever here is tempted to re-open a settled question in six
months. Every number below names the benchmark or the test it came from; every statement about
somebody else's code names the type, the file, the line and the version it was read at. Where an
argument rests on reading source rather than on measuring, it says so — and most of this page does,
because a concurrency design is argued from mechanism and pinned by tests, not settled by a mean.

Minestom is read at `2026.06.20-26.1.2`, the version this repository compiles against through
`net.onelitefeather:mycelium-bom:1.7.2`. The measured tables quoted here are owned by
[Why a second Anvil loader](why-a-second-anvil-loader.md); what the `±` after a mean covers is stated once,
in [What a measurement here means](what-a-measurement-here-means.md), and is not restated here.

## Contents

- [What the design is protecting](#what-the-design-is-protecting)
- [Why not a `ReadWriteLock`](#why-not-a-readwritelock)
- [Why not deferred free](#why-not-deferred-free)
- [What the seqlock actually does](#what-the-seqlock-actually-does)
- [Region handle lifetime: usage counting instead of a retry](#region-handle-lifetime-usage-counting-instead-of-a-retry)
- [Refusing to serve a closed loader](#refusing-to-serve-a-closed-loader)
- [Bounded fan-out when saving](#bounded-fan-out-when-saving)
- [The defects: five races that would all have failed silently](#the-defects-five-races-that-would-all-have-failed-silently)
- [A sixth defect, on Windows only, and older than the other five](#a-sixth-defect-on-windows-only-and-older-than-the-other-five)
- [The stress tests run on platform threads, and that was not a preference](#the-stress-tests-run-on-platform-threads-and-that-was-not-a-preference)
  — the only section with a subsection
- [What the tests do and do not establish](#what-the-tests-do-and-do-not-establish)
- [Sources](#sources)

## What the design is protecting

The loader exists because Minestom's `AnvilLoader` reports `supportsParallelLoading() == true`
(`instance/anvil/AnvilLoader.java:587-589`) while its region file serialises the expensive part of a
load. In `net.minestom.server.instance.anvil.RegionFile` at version `2026.06.20-26.1.2` a single
`ReentrantLock` field, declared at `instance/anvil/RegionFile.java:42` and held one per open region
file, is taken at the head of `readChunkData` (`:68`) and released in its `finally` block (`:90`),
and between the two the method performs the `seek` (`:73`), the length and compression-type read
(`:74-75`), the payload read (`:85`) **and** `TAG_READER.read(...)` at `:88`, which inflates the
payload and parses the NBT [10]. Reading two chunks of the same region file from two threads therefore
runs the inflate and the parse one after the other. That is a statement about the source, not a
measurement.

What Falco does instead is partly structural and partly measured, and the structural half is the
stronger of the two because it needs no benchmark: reading `FalcoAnvilLoader#saveChunk` shows that
the snapshot holds the chunk's read lock, the transfer holds the region lock, and the codec and the
deflate between them hold nothing. `ChunkSaveStageBenchmark` supplies the weights — snapshot 64 µs,
NBT tree without serialisation or compression 1 356 µs, NBT serialisation and zlib together 2 701 µs
(a **derived** row, `codec` minus `codecWithoutCompression`), transfer 17 µs — which puts **about
98 % of a save outside any lock**. None of those four rows carries a `±`, so none of them supports a
ratio; the derivation and the caveats are in
[Why a second Anvil loader](why-a-second-anvil-loader.md#why-three-stages), which owns that table.

The read side is measured against Minestom directly, in µs/op [2]:

| Threads | Falco read | Minestom read | |
| ---: | ---: | ---: | --- |
| 1 | 1 203 ± 123 | 1 060 ± 55 | intervals overlap |
| 2 | 1 181 ± 31 | 2 200 ± 445 | **1.9× faster** |
| 4 | 1 378 ± 84 | 11 021 ± 16 470 | no factor: ± exceeds the mean |
| 8 | 2 438 ± 252 | 530 905 ± 1 928 261 | no factor: ± is 3.6× the mean |

<sub>`RegionFileComparisonBenchmark.falcoRead` / `.minestomRead`, `distinctStates = 200`, one thread
count per row passed as `-t`, one fork with `-Xms1g -Xmx1g`, annotated 3 warmup and 5 measurement
iterations of 1 s — earlier revisions of this page said 2 s, which is the iteration time of
`ChunkSaveComparisonBenchmark` and not of this class; the time actually passed on the command line
was not recorded — JMH 1.37, one 16-core machine recorded as **not idle**, no `results.json`
committed. One fork: the `±` covers variance between iterations of one JVM, not between JVM
launches — see [What a measurement here means](what-a-measurement-here-means.md). **This is the first of two runs of
this benchmark**; the second is tabulated in
[How the Anvil loader is built](how-the-anvil-loader-is-built.md#measured-concurrent-readers-of-one-region-file) under the
same stated configuration, and its Minestom means differ from these by up to a factor of 47. No row
of one may be held against a row of the other.</sub>

Three things have to be said about that table before it is used as an argument. On a single thread
Falco's mean is the higher of the two and the intervals overlap — `[1 080, 1 326]` against
`[1 005, 1 115]` — so no advantage is resolved in either direction, and the direction of the means is
against this loader at 200 distinct states as it is at 8. The three-stage pipeline has a setup cost
and no contention to win it back from [1]. Second, the two-thread row is the only one from which a
factor may be taken: the intervals are disjoint, and the conservative bounds are 1.45× to 2.30× on a
half-width that is 20 % of Minestom's mean. Third, **neither the four- nor the eight-thread row
supports a multiplier at any precision** — at eight threads the half-width is 3.6 times the mean, and
at four it is 149 % of it, which puts that interval's lower end below zero. What both rows show is a
loss of predictability: Minestom's read time scatters over three orders of magnitude under load while
Falco stays inside ±10 % of its own mean, which for a server is the worse of the two failures. That
is the claim this design rests on, and it is a claim about dispersion, not about a factor.

A separate run at four threads with two forks and ten iterations reports Falco 1 325.6 ± 21.1 against
Minestom 359 690.8 ± 97 498.3 µs/op. Those intervals are disjoint, so the difference is supported and
this is the only higher-fork control run the project has — but the bounds it allows are roughly 200×
to 350×, a range rather than the point estimate of 271 that has been quoted, because Minestom's
half-width is 27 % of its mean. Even that range is a property of one session: the four-thread
Minestom read has been measured at 11 021 ± 16 470 in the run tabulated above and at
302 704 ± 674 429 in the second run recorded in
[How the Anvil loader is built](how-the-anvil-loader-is-built.md#measured-concurrent-readers-of-one-region-file), so the
magnitude moves by more than an order of magnitude between sessions and only the direction and the
loss of predictability reproduce. The mechanism behind the magnitude is not established either: pure
serialisation of the 1 060 µs one-thread read in the table above, across four threads, predicts
roughly 4 200 µs — two orders of magnitude short of any of the contended figures, all of which come
from other sessions. An unexplained factor derived across runs is not a result [3].
<!-- TODO(maintainer): a previous note here claimed the 1 045 µs baseline "appears in no published
     table". It does: 1 045 ± 112 is the one-thread Minestom read of the SECOND
     RegionFileComparisonBenchmark run, published in Anvil-Chunk-Loader, and 1 060 ± 55 is the first
     run's, tabulated above. The 4 200 µs estimate is derived from the first run and compared against
     contended figures from other sessions, which the paragraph now says outright. Re-deriving it
     inside one session would remove the caveat entirely. -->

The single property all of this rests on is therefore narrow and worth naming exactly: **readers of
a region file are never serialised against each other and never delay a writer.** Every decision
below was taken to keep that property, and each of them is a decision because the obvious
alternative would have given it up.

## Why not a `ReadWriteLock`

The reader hazard is real and has to be handled by something. `RegionFile#readRaw` takes no lock and
reads through `FileChannel.read(ByteBuffer, long)`, which does not touch the channel position and is
therefore safe from several threads at once [5]. But a chunk that is rewritten moves to a new sector
range and releases the one it occupied — `writeRaw` calls `SectorAllocator#free` on the previous
location at the end of its critical section — and the allocator hands freed ranges back out
first-fit. A reader that is still inside that range is then reading somebody else's chunk [5].

The reflex is a `ReadWriteLock` per region file: readers share, writers exclude. It solves the
hazard, and it costs exactly the thing the loader is for. The writer's critical section in
`writeRaw` covers the sector allocation, `writeFully` of the whole payload, the move of an external
file into place, and the two four-byte header writes [5]. Under a `ReadWriteLock`, every reader of
every one of the 1024 chunks in that file waits for all of that, because the allocator is
file-global state and any lock protecting it is necessarily file-wide. A read path that waits for a
payload write is the read path Minestom already has; there would be no reason to have written this
one.

A per-entry `ReadWriteLock` — 1024 of them — is the variant that deserves a sentence, because it is
not obviously wrong. A sector range is only ever freed by an operation on the entry that owns it:
`writeRaw` frees `previous`, the previous location of the chunk it is writing, and `delete` frees
the location of the chunk it is deleting [5]. Locking the entry being read would therefore exclude
the hazard. It would also make readers delay the writer of that chunk, which is the second half of
the property above, and it adds a lock-ordering problem against the file-wide lock the allocator
still needs. This paragraph is an argument from the source, not a decision recorded in the working
notes; the recorded decision covers the file-wide variant [1].

## Why not deferred free

The other standard answer is to not validate after the fact but to make the hazard impossible:
never hand a freed range back out until no reader can still be inside it. That requires readers to
announce themselves — an epoch counter, a reader count, a reservation — and freed ranges to sit in
a quarantine until the announcement says they are safe.

It was rejected for two reasons, both recorded as design arguments rather than measurements [1].
The first is that it puts a shared, mutable counter back on the read path. Every read would have to
increment and decrement a cell that every other reader also touches, which is a contended cache line
in exactly the place the design spent its effort making read-only; the contention removed from the
lock comes back through the counter. The second is that the region file grows: a range that cannot
be reused at once forces the allocator to append instead, and the format has no compaction — the
loader documents explicitly that freed sectors are reused but the file is never shrunk [3]. Neither
variant was built and benchmarked, so this is reasoning about the two mechanisms, not a measured
comparison of them.

## What the seqlock actually does

Each of the 1024 entries carries its own version counter in an `AtomicIntegerArray`. A writer raises
the counter on entry to its critical section and again on leaving it, both while holding the lock, so
an odd value marks an entry that is being changed right now. A reader takes the counter, refuses an
odd one, reads the bytes, and takes the counter again; only an unchanged even counter is accepted,
and anything else makes it start over [5].

Three details in that loop are the difference between a working seqlock and a plausible-looking one,
and all three are in `RegionFile#readRaw`:

- **The read is bounded.** After `OPTIMISTIC_ATTEMPTS = 4` failed attempts the reader takes the
  writers' lock and reads under it. Without that fallback a chunk rewritten in a tight loop could
  starve a reader indefinitely. It is the only case in which a reader waits for a writer at all [5].
- **A failed read is not reported unless the version proves it was genuine.** The `catch (IOException)`
  branch rethrows only when the counter is unchanged. Bytes from a recycled range can describe any
  length and any compression scheme, so a read that raced a writer will often fail with a perfectly
  convincing error message, and reporting that error would turn a harmless race into a chunk the
  server considers broken [5].
- **The counter is raised inside the lock on the way out**, before the range freed above can be
  handed to another writer, so no reader can miss the change that invalidates its bytes [5].

The header tables themselves are `AtomicIntegerArray` rather than `int[]`, which rules out a reader
seeing a stale zero location and turning a present chunk into a regenerated one. That is ruled out by
construction and not by a test, and the working notes say so: a Java-memory-model staleness window
cannot be provoked deterministically, because any harness that tries introduces synchronisation edges
of its own [1].

## Region handle lifetime: usage counting instead of a retry

The loader caches open region files and drops them again — when the last chunk it loaded from a file
is unloaded, and when the cache exceeds `DEFAULT_OPEN_REGION_LIMIT` (64) [6]. Both events can happen
while another thread is inside a read or a write of that same file, and the first version of the
code lost chunks that way: a file could be evicted between obtaining the handle and writing to it [1].

The cheap fix for that shape of bug is a retry — notice the closed channel, acquire the file again,
redo the work. It was rejected because a retry only narrows the window instead of removing it, and
because it has to be repeated correctly at every call site [1]. What the code does instead is count
users. `RegionHandle` holds a `users` count and a `retired` flag behind `synchronized`; `acquire`
refuses to register on a retired handle, `retire` marks the handle and reports whether it may be
closed at once, and `release` closes the file when the caller was the last user of an already-retired
handle [6]. Dropping a handle from the cache and closing the file are therefore separate operations,
and the thread that leaves last performs the close. `acquireRegion` loops: if the cached handle
refuses registration it is on its way out, so the loop opens the file anew rather than handing back
a file that is about to be closed [6].

The price is stated rather than hidden. The open-region limit now bounds the *cached* files exactly,
not the open descriptors: a handle in use stays open past the limit for the duration of that single
access [1][3]. It is a cache size, not a resource guarantee, and it is documented at the field as
such.

One more thing in the same method is a deliberate difference from Minestom. `AnvilLoader` opens its
region files inside `alreadyLoaded.computeIfAbsent(...)` at `instance/anvil/AnvilLoader.java:179`,
that is, it constructs a `RegionFile` — which opens the file and reads its header — inside a
`ConcurrentHashMap` mapping function, holding that bin's lock for the whole duration; the call is
additionally wrapped in `perRegionLoadedChunksLock` at `:177`, so the open is serialised across every
region as well [10]. Falco opens outside the mapping function and publishes with `putIfAbsent`,
closing the redundant handle when it loses the race [6]. This is a difference read from the source of
both; neither side of it has been measured.

## Refusing to serve a closed loader

`close()` on the loader runs while the server's own tasks are still in flight, because the loader
reports parallel work as supported and Minestom therefore hands it one virtual thread per chunk [6].
The question is what a load or a save that arrives after that should do. Ignoring it is the
comfortable answer and it is wrong in both directions: ignoring a save drops chunk data during the
very shutdown that is supposed to persist it, and returning `null` from a load reports the chunk as
absent, which makes `InstanceContainer` generate a replacement and overwrite the stored bytes on the
next save [3][12]. Waiting is not available either — Minestom owns the load tasks, so there is
nothing this loader could wait on [1].

So it throws. `loadChunk`, `saveChunk`, `saveChunks` and the region cache all check the flag and
raise `IllegalStateException`, which frames the situation as a lifecycle error of the caller rather
than as a broken chunk; `saveChunk` catches it separately so a shutdown does not inflate the
failed-chunk counter [6]. The close path is ordered so that nothing can escape it: the flag is raised
before the cache is emptied, and `acquireRegion` reads the flag *after* publishing its handle, so
either the publishing thread sees the flag and retires its own handle or the loop in `close()` sees
the handle — the file is closed in both cases and no handle can survive with nobody to close it [6].

`RegionFile` makes the same refusal with a different exception type, and the difference is
intentional. `ensureOpen` there throws `IOException`, because every caller of a region file is
already handling IO failures; `RegionFileConcurrencyTest` asserts exactly this, requiring every
worker that runs into the close to report an `IOException` and nothing else, since any other type
would reach a caller that only expects an IO failure [5][7].

## Bounded fan-out when saving

`ChunkLoader.saveChunks` is an interface default that Minestom's loader does not override
(`instance/ChunkLoader.java:62-82`). It registers a party on a `Phaser` per chunk, starts one virtual
thread per chunk, and in the `catch (Throwable e)` branch at `:71-73` calls
`MinecraftServer.getExceptionManager().handleException(e)` — without `phaser.arriveAndDeregister()`
[11]. A single `Throwable` escaping `saveChunk` therefore leaves a registered party that never
arrives, and the `phaser.arriveAndAwaitAdvance()` at `:76` blocks the saving thread for good. That is
read from the source at `2026.06.20-26.1.2`, not observed in a running server.

Falco overrides it: chunks are grouped by region index, one task per region, and the concurrency is
bounded by a `Semaphore` of `Math.max(Runtime.getRuntime().availableProcessors(), 2)` permits, with
every result collected in `awaitAll` [3][6]. That bound is a judgement rather than a measured
optimum; no other bound was benchmarked. The grouping removes the contention (one thread per chunk
means every chunk of a region competes for that region's lock) and the semaphore bounds how many
chunk snapshots and compressed byte arrays are alive at once. It also inherits nothing from the `Phaser` branch.

## The defects: five races that would all have failed silently

This is the most useful evidence in the repository about whether the tests are worth anything, so it
is reproduced here exactly as recorded, with its origins intact. The five were found by taking one
known defect and searching the rest of the code for the same shape. That search turned up **no second
instance of that exact shape** — and four races of other kinds, which is the reason it was worth
doing [1]. The numbers below are counts from the red run of each test, not statistics from a repeated
experiment. They were also charted, but the chart is not publicly readable and every number behind it
is in the working notes [1].

1. **`ChunkLightService` shared its scratch buffers.** It kept a `ChunkLightPropagator` in a field,
   so two threads using one service shared its `levels` and `queue` arrays. A probe found wrong light
   in **about 99 % of concurrent calls**. `ChunkLightState` had been building one propagator per call
   all along, which is why `calculateWithNeighbours` was never affected [1].
2. **`RegionFile` recycled sectors while readers were still inside them.** `readRaw` took the
   location without a lock, a concurrent `writeRaw` freed the old range, and the allocator handed it
   straight back out. Readers observed filler markers where their own payload belonged, and sometimes
   the whole payload of another chunk from offset 0. This is the defect the per-entry seqlock exists
   for [1].
3. **`.mcc` files were written and deleted outside the header lock.** **371 `NoSuchFileException`
   and about 60 half-read files in 54 679 reads.** The payload now goes to a staging file and is
   moved into place with `ATOMIC_MOVE` under the lock, so the bytes still leave the process outside
   the critical section while the header entry and the file can never disagree [1][5].
4. **Eviction closed a channel under a running reader.** Chunk tracking starts only *after* decoding,
   so a handle could be closed mid-read: **80 of 480 loads failed with `openRegionLimit = 1`, and 15
   of 480 through the unload path**, with `ClosedChannelException` thrown from inside
   `FileChannelImpl.read`. This is the defect the usage count exists for [1].
5. **`closed` was set but never read.** `loadChunk`, `saveChunk` and `region()` ignored it, so a load
   still running at shutdown opened fresh handles into the map that `close()` had just cleared — a
   descriptor leak, and writes into a world already considered closed [1].

The reason all five mattered is that none of them announced itself. The light path clears the
section's update flag when it writes through `Light#set`, so the server never recomputes what two
threads corrupted; and a read failure that returns `null` makes Minestom regenerate the chunk and
overwrite the real data on the next save [1][12]. Every one of these would have shown up as a dark
patch or a missing building weeks later, with nothing in the log.

## A sixth defect, on Windows only, and older than the other five

The external `.mcc` file of an oversized chunk is the one place where the lock-free reads meet a
*name* in the file system rather than a range inside the region file, and a name is not a POSIX
concept [5]. Under POSIX, deleting a file detaches the name immediately and keeps the unnamed file
alive for every open handle, so an inline writer removing a stale `.mcc` while a reader still has it
open is not noticed by anyone. Windows leaves the name in the directory and marks the file
delete-pending: for as long as one reader holds it open, every later open of that name and every move
onto it is denied. An inline writer therefore poisoned the name for the next writer that wanted to
put a new external file there [1].

The fix renames the file onto a private name with `ATOMIC_MOVE` and only then deletes it, because a
rename detaches the name immediately on both systems; what Windows can still deny briefly on its own
— a handle being torn down, a virus scanner holding the file — is retried for a bounded time
(`EXTERNAL_ATTEMPTS = 100` at one millisecond) [1][5]. `RegionFile#removeExternal` and
`RegionFile#placeExternal` carry that reasoning in their Javadoc.

Two things about this one are worth keeping. It is **older than the other five**: it was already in
the last green commit, and the only thing missing was a test that exercised it, so the loader was
broken on Windows from the moment an oversized chunk was read while it was being saved. And only the
CI runner could show it — on Linux it is not reproducible at all, and the fix had to be reasoned from
the platform semantics rather than driven by a failing local test [1]. That is also why the PR
workflow now uploads the test reports as an artifact when a build fails: Gradle's console summary
names the test class and the exception type but neither the message nor a path nor a stack trace,
which on a platform-specific failure is the difference between reading the cause and guessing it [1].

## The stress tests run on platform threads, and that was not a preference

The lesson is recorded in commit `fbb4121`, *"fix(test): stop the concurrency stress tests from
hanging the pipeline"*, and in the class Javadoc of both stress-test classes [9][7][8].

Every reader in `RegionFileConcurrencyTest` keeps reading until a latch reports that the writers are
done. That is a busy wait, and it originally ran on `Executors.newVirtualThreadPerTaskExecutor()`. A
virtual thread that never blocks never releases its carrier, and the number of carriers defaults to
the number of available processors — the JDK documents `jdk.virtualThreadScheduler.parallelism` as
defaulting to the number of available processors [13]. On a two-core CI machine the spinning readers
occupied both carriers, the writers never ran, the latch never reached zero, and the readers spun
forever. `ExecutorService#close` then waited for tasks that could not finish, so the **test JVM hung
instead of failing** — a pipeline ran for hours with the last line of output being the `PASSED` of
the preceding test [9].

It reproduces reliably under `taskset -c 0,1` and not at all on a developer machine with many cores,
which is precisely why it survived. After the change to
`Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory())` the same tests are green in
twelve seconds on two cores, where they had previously not finished inside a 240-second timeout [9].

Nothing is lost by the switch. The work in these tests is file input and output, which does not
unmount a virtual thread from its carrier either — recorded in the working notes with reference to
JEP 444 [1][14] — so the virtual threads were buying nothing here in the first place.

### Why `Thread#yield` does not fix it

The tempting one-line fix is a `Thread.yield()` in the spin loop. It was tried and it did not work:
the commit records that a yielding virtual thread is rescheduled ahead of the parked writers,
confirmed with a thread dump showing every writer parked while the readers spun [9].

The general reason not to reach for it is stronger than that one observation, and it is in the
specification rather than in a scheduler implementation. `Thread.yield` is documented as *"A hint to
the scheduler that the current thread is willing to yield its current use of a processor. The
scheduler is free to ignore this hint"*, followed by *"It is rarely appropriate to use this method"*
[13]. A hint the scheduler may ignore cannot be the thing a test's liveness depends on. Yielding also
does not do what the situation needs: it makes the yielding thread runnable again immediately and
reshuffles the runnable set, while the threads being starved are *parked*, waiting for a signal that
only another task can send. The precise reason the readers kept winning against the writers is a
property of the scheduler's queueing that has not been established here — the observation from the
thread dump is the evidence, the mechanism is not.

The general form of the lesson: a stress test must not depend on the scheduler being fair. On
platform threads the operating system can take the processor away, which is a guarantee; on virtual
threads it is not one.

Both classes additionally carry
`@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)` as a
second line of defence, so a future stall ends as a red test rather than an endless run. The thread
mode is not decoration: the default mode measures the duration after the method returned, which never
happens when it hangs [7][8][9].

## What the tests do and do not establish

The stress tests are worth something because they do not stop at "many threads ran and nothing was
thrown", which passes on a broken implementation. Each payload in `RegionFileConcurrencyTest` is one
byte value repeated, so any mix of two versions is a mismatch at whatever offset it occurred; the
sector table of the finished file is read back from disk and checked for overlapping ranges; and the
file is reopened afterwards so the header has to describe a layout that can be rebuilt [7]. The torn
read test additionally asserts that every reader observed the old version before the writers were
released and the final version after they finished, which is what proves it really read across the
transition rather than only before or after it [7]. On the light side, the concurrency tests compare
against a reference computed one chunk at a time, so any state leaking between threads shows as a
drift from that reference [8].

What they do not establish is stated in the same spirit [1]:

- **Stale header entries** are ruled out by construction, not by a test, as described above.
- **The open-handle limit** bounds the cache, not the descriptors.
- **`calculateWithNeighbours` can commit a stale read.** One `ChunkLightService` may serve any number
  of threads — that is what the light fix established and what `ChunkLightServiceConcurrencyTest`
  holds it to. What it does *not* establish is two threads lighting overlapping neighbourhoods: each
  reads the block states of all nine chunks separately, and a chunk one thread reads as a neighbour
  is a chunk the other may be writing. No memory is corrupted, since every write holds the chunk's
  write lock, but a result can be committed from a read that was already stale, which shows up as a
  seam rather than as an error. Since the method writes only the chunk in the middle of its 3×3, two
  threads on different coordinates no longer write the same sections at all, so the plain
  last-writer-wins case is gone and the stale read is what is left. Whether it happens is up to the
  caller, and nothing in the API says so yet [1].

## Sources

1. [Project Status](../contributing/project-status.md), the working record: established facts, the five races and their
   failure rates, the decision table, and the open items.
2. [Measured results](../reference/measured-results.md), the measurements.
3. [How the Anvil loader is built](how-the-anvil-loader-is-built.md), the loader in detail, including the twenty-row comparison
   with `AnvilLoader` and the second region-file benchmark run, whose figures differ from the ones
   tabulated here.
4. [What the benchmarks establish](what-the-benchmarks-establish.md), benchmark methodology, what each benchmark measures, and what its
   numbers are not; [What a measurement here means](what-a-measurement-here-means.md) for the meaning of `±` and the
   per-class run configurations. Sources:
   https://github.com/OneLiteFeatherNET/Falco/tree/main/falco-benchmarks/src/jmh/java.
5. `falco-anvil/src/main/java/net/onelitefeather/falco/anvil/RegionFile.java` — `readRaw`,
   `readEntry`, `writeRaw`, `placeExternal`, `removeExternal`, `retryWhileDenied`, and the constants
   `OPTIMISTIC_ATTEMPTS`, `EXTERNAL_ATTEMPTS`, `STAGING_SUFFIX`.
6. `falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java` — `acquireRegion`,
   `releaseRegion`, `retire`, `evictRegions`, `close`, `ensureOpen`, `saveChunks`, and the nested
   `RegionHandle`.
7. `falco-anvil/src/test/java/net/onelitefeather/falco/anvil/RegionFileConcurrencyTest.java`,
   including its class Javadoc on why the tests run on platform threads.
8. `falco-light/src/test/java/net/onelitefeather/falco/light/LightEngineConcurrencyTest.java` and
   `ChunkLightServiceConcurrencyTest.java`.
9. Falco commit `fbb4121`, *"fix(test): stop the concurrency stress tests from hanging the pipeline"*,
   whose message records the two-core reproduction, the `Thread#yield` attempt and the thread dump.
10. Minestom, `net/minestom/server/instance/anvil/RegionFile.java`, version `2026.06.20-26.1.2`, read
    from the published sources artifact `net.minestom:minestom:2026.06.20-26.1.2`. Project:
    <https://github.com/Minestom/Minestom>.
11. Minestom, `net/minestom/server/instance/ChunkLoader.java`, same version and artifact — the
    `saveChunks` default with the `Phaser`.
12. Minestom, `net/minestom/server/instance/InstanceContainer.java`, same version — the handling of a
    `null` result from `loadChunk` and of a load future completed exceptionally.
13. *Class `Thread`*, Java SE 25 API documentation, Oracle:
    <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html> — the
    wording of `yield()` and the implementation note on
    `jdk.virtualThreadScheduler.parallelism`.
14. JEP 444: *Virtual Threads*, OpenJDK: <https://openjdk.org/jeps/444>. Cited for the statement that
    file I/O does not unmount a virtual thread from its carrier; that claim is taken from the working
    record [1], which names this JEP as its source. The JEP text was not re-read while writing this
    document.
