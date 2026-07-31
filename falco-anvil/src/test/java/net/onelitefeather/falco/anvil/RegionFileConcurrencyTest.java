package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stresses the thread safety of the region file container.
 * <p>
 * The whole design claim of {@link RegionFile} is that a read never takes a lock and still only ever
 * returns a state which really existed, while a writer guards no more than the sector allocation,
 * the header entry and the switch between the two storage locations of a chunk. Every test in this
 * class is built so that it fails when that claim breaks: the payloads carry a marker byte in every
 * single byte, the sector table of the finished file is checked for overlapping ranges, and the file
 * is reopened afterwards so the header has to describe a layout which can be rebuilt.
 * </p>
 * <p>
 * A test which merely starts many threads and asserts that nothing was thrown would pass on a
 * broken implementation, so none of the tests here stop at that. Each of them names the corruption
 * it detects in its own comment.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class RegionFileConcurrencyTest extends FileTestBase {

    /**
     * The time a latch is waited for before the test is considered stuck.
     */
    private static final long AWAIT_SECONDS = 60L;

    /**
     * The amount of chunks which are written concurrently in the disjointness test.
     */
    private static final int DISJOINT_CHUNK_COUNT = 64;

    /**
     * The amount of chunks the torn read test rewrites while readers hammer the file.
     */
    private static final int TORN_CHUNK_COUNT = 8;

    /**
     * The amount of threads which read while the torn read test rewrites the chunks.
     */
    private static final int TORN_READER_COUNT = 8;

    /**
     * The sector count of every version the torn read test writes.
     * <p>
     * The values are chosen so the allocator can never hand out a sector twice. All eight version
     * zero payloads occupy eight sectors in total, which is less than the nine sectors a single
     * version one payload needs, and version zero plus version one occupy eighty sectors in total,
     * which is less than the eighty one sectors a single version two payload needs. Together with
     * the barrier which holds every writer until the last one finished its version one, a freed
     * range can therefore never satisfy a later allocation, not even when every freed range is
     * adjacent and merges into one gap.
     * </p>
     * <p>
     * That property is what lets this test compute the final size of the file down to the byte and
     * assert it. It is not a workaround: a reader which observes a recycled range has to see a
     * single consistent version just as well, which
     * {@link #testReadersNeverObserveASectorWhichWasRecycledWhileTheyRead()} drives on purpose.
     * Keeping the two apart only means that a failure here names the torn read and a failure there
     * names the recycled range.
     * </p>
     */
    private static final int[] TORN_SECTORS = {1, 9, 81};

    /**
     * The amount of sectors the file of the torn read test spans once every version was written.
     * The header occupies two sectors, version zero eight, version one seventy two and version two
     * six hundred and forty eight.
     */
    private static final int TORN_TOTAL_SECTORS = 2 + 8 + 72 + 648;

    /**
     * The payload sizes a single chunk cycles through in the grow and shrink test.
     * The last entry crosses the limit of {@link RegionConstants#MAX_SECTORS_PER_CHUNK} sectors and
     * therefore moves the chunk into an external file.
     */
    private static final int[] CYCLE_SIZES = {
            RegionConstants.SECTOR_SIZE - 5,
            RegionConstants.SECTOR_SIZE * 3 - 5,
            RegionConstants.SECTOR_SIZE * 2 - 5,
            RegionConstants.MAX_SECTORS_PER_CHUNK * RegionConstants.SECTOR_SIZE + 1
    };

    /**
     * The amount of times a scenario which depends on an interleaving is repeated.
     * A single run of such a scenario does not always hit the window a broken implementation opens,
     * so the scenario is repeated on a fresh file until a defect is practically certain to show.
     */
    private static final int ATTEMPTS = 4;

    /**
     * The sector count of the large version the recycling test writes.
     * The value is the largest one a location entry can address, which makes the payload read of a
     * reader as long as the format allows and therefore widens the window a recycled range needs.
     */
    private static final int RECYCLE_LARGE_SECTORS = RegionConstants.MAX_SECTORS_PER_CHUNK;

    /**
     * The sector count of the small version the recycling test writes.
     * Shrinking the chunk to a single sector is what frees the large range in the first place.
     */
    private static final int RECYCLE_SMALL_SECTORS = 1;

    /**
     * The byte every large version of the observed chunk of the recycling test carries.
     */
    private static final byte RECYCLE_LARGE_MARKER = (byte) 0x51;

    /**
     * The byte every small version of the observed chunk of the recycling test carries.
     */
    private static final byte RECYCLE_SMALL_MARKER = (byte) 0x52;

    /**
     * The amount of threads which compete for the freed range in the recycling test.
     * Every one of them writes exactly as many sectors as the observed chunk frees, so the first fit
     * strategy of the allocator hands the freed range to one of them.
     */
    private static final int RECYCLE_FILLER_COUNT = 4;

    /**
     * The amount of threads which read the observed chunk of the recycling test.
     */
    private static final int RECYCLE_READER_COUNT = 8;

    /**
     * The amount of versions every writer of the recycling test produces.
     */
    private static final int RECYCLE_ROUNDS = 60;

    /**
     * The amount of times the recycling scenario is repeated on a fresh file.
     */
    private static final int RECYCLE_ATTEMPTS = 2;

    /**
     * The length of the payload which does not fit into a location entry and therefore lives in an
     * external file next to the region file.
     */
    private static final int EXTERNAL_PAYLOAD_LENGTH = RegionConstants.MAX_SECTORS_PER_CHUNK * RegionConstants.SECTOR_SIZE + 1;

    /**
     * The length of the payload which is small enough to stay inside the region file.
     */
    private static final int INLINE_PAYLOAD_LENGTH = RegionConstants.SECTOR_SIZE - 5;

    /**
     * The byte the external payload of the storage switch test carries.
     */
    private static final byte EXTERNAL_MARKER = (byte) 0x61;

    /**
     * The byte the inline payload of the storage switch test carries.
     */
    private static final byte INLINE_MARKER = (byte) 0x62;

    /**
     * The amount of versions every writer of the storage switch test produces.
     */
    private static final int SWITCH_ROUNDS = 40;

    /**
     * The amount of threads which read the chunk of the storage switch test.
     */
    private static final int SWITCH_READER_COUNT = 4;

    /**
     * Creates the path of a region file of the given attempt inside the temporary directory.
     *
     * @param attempt the index of the attempt the file belongs to
     * @return the path of the region file
     */
    private Path regionPath(int attempt) {
        return this.tempDir.resolve("r.0." + attempt + ".mca");
    }

    @Test
    void testConcurrentWritesToDistinctChunksKeepEverySectorRangeDisjoint() throws IOException, InterruptedException, ExecutionException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            writeDistinctChunksConcurrently(regionPath(attempt));
        }
    }

    /**
     * Writes every chunk of a region file from its own thread and verifies the result.
     *
     * @param path the path of the region file to work on
     * @throws IOException          if the region file cannot be used
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a writer failed
     */
    private void writeDistinctChunksConcurrently(Path path) throws IOException, InterruptedException, ExecutionException {
        // A lost update inside the sector allocator hands the same sectors to two chunks. The
        // payloads then overwrite each other, which the marker bytes expose, and the location table
        // ends up with two entries pointing into the same range, which the disjointness check
        // exposes even when the payload check happens to survive.
        List<byte[]> payloads = new ArrayList<>(DISJOINT_CHUNK_COUNT);

        for (int index = 0; index < DISJOINT_CHUNK_COUNT; index++) {
            payloads.add(marked((index % 3 + 1) * RegionConstants.SECTOR_SIZE - 5, (byte) (index + 1)));
        }

        CountDownLatch start = new CountDownLatch(1);

        try (RegionFile region = RegionFile.open(path);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(DISJOINT_CHUNK_COUNT);

            for (int index = 0; index < DISJOINT_CHUNK_COUNT; index++) {
                int chunk = index;
                futures.add(executor.submit(() -> {
                    awaitStart(start);
                    region.writeRaw(chunk % 32, chunk / 32, ChunkCompression.ZLIB, payloads.get(chunk));
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);

            for (int index = 0; index < DISJOINT_CHUNK_COUNT; index++) {
                assertArrayEquals(payloads.get(index), read(region, index % 32, index / 32), "chunk " + index + " was corrupted");
            }
        }

        assertSectorTableIsDisjoint(path);

        try (RegionFile reopened = RegionFile.open(path)) {
            for (int index = 0; index < DISJOINT_CHUNK_COUNT; index++) {
                assertArrayEquals(payloads.get(index), read(reopened, index % 32, index / 32), "chunk " + index + " did not survive the reopen");
            }
        }
    }

    @Test
    void testConcurrentReadersNeverObserveATornPayload() throws IOException, InterruptedException, ExecutionException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            readWhileTheChunksAreRewritten(regionPath(attempt));
        }
    }

    /**
     * Rewrites every chunk of a region file twice while readers keep reading it.
     *
     * @param path the path of the region file to work on
     * @throws IOException          if the region file cannot be used
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a worker failed
     */
    private void readWhileTheChunksAreRewritten(Path path) throws IOException, InterruptedException, ExecutionException {
        // Every byte of a payload encodes the chunk and the version it belongs to. A reader which
        // observes a mix of two versions therefore sees two different byte values in one payload,
        // which is exactly what an in place update of a chunk would produce. The readers are
        // released before the writers so every one of them is guaranteed to observe the old version
        // at least once, which proves that the test really reads across the transition.
        Queue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicIntegerArray observations = new AtomicIntegerArray(TORN_SECTORS.length);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch warmedUp = new CountDownLatch(TORN_READER_COUNT);
        CountDownLatch grown = new CountDownLatch(TORN_CHUNK_COUNT);
        CountDownLatch written = new CountDownLatch(TORN_CHUNK_COUNT);

        try (RegionFile region = RegionFile.open(path);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int chunk = 0; chunk < TORN_CHUNK_COUNT; chunk++) {
                region.writeRaw(chunk, 0, ChunkCompression.ZLIB, tornPayload(chunk, 0));
            }

            List<Future<?>> futures = new ArrayList<>(TORN_READER_COUNT + TORN_CHUNK_COUNT);

            for (int reader = 0; reader < TORN_READER_COUNT; reader++) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    // The first pass has to finish before the writers are released, so it is
                    // performed outside of the loop and reports its end even when it failed.
                    try {
                        for (int chunk = 0; chunk < TORN_CHUNK_COUNT; chunk++) {
                            inspectTornRead(region.readRaw(chunk, 0), chunk, failures, observations);
                        }
                    } finally {
                        warmedUp.countDown();
                    }

                    // The state of the latch is read before the pass, so the pass which observes the
                    // finished writers runs completely after the last write. Every reader therefore
                    // sees the final version of every chunk, which makes the counters below an
                    // assertion instead of a coincidence.
                    boolean last = false;

                    while (!last) {
                        last = written.getCount() == 0;

                        for (int chunk = 0; chunk < TORN_CHUNK_COUNT; chunk++) {
                            inspectTornRead(region.readRaw(chunk, 0), chunk, failures, observations);
                        }
                    }
                    return null;
                }));
            }

            for (int chunk = 0; chunk < TORN_CHUNK_COUNT; chunk++) {
                int index = chunk;
                futures.add(executor.submit(() -> {
                    awaitStart(start);
                    awaitStart(warmedUp);

                    try {
                        // Every writer has to finish its first version before any of them starts the
                        // second one. A range which was freed by a second version is exactly as
                        // large as a first version needs, so without this barrier the allocator
                        // would hand a recycled range to a first version and the file would no
                        // longer end up at the size this test computes below. The recycled range
                        // itself is driven by the test which is named for it.
                        try {
                            region.writeRaw(index, 0, ChunkCompression.ZLIB, tornPayload(index, 1));
                        } finally {
                            grown.countDown();
                        }
                        awaitStart(grown);
                        region.writeRaw(index, 0, ChunkCompression.ZLIB, tornPayload(index, 2));
                    } finally {
                        written.countDown();
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);

            for (int chunk = 0; chunk < TORN_CHUNK_COUNT; chunk++) {
                assertArrayEquals(tornPayload(chunk, TORN_SECTORS.length - 1), read(region, chunk, 0), "chunk " + chunk + " lost its last version");
            }
        }

        assertTrue(failures.isEmpty(), "a reader observed a payload which is not a single version: " + failures);
        assertTrue(
                observations.get(0) >= TORN_READER_COUNT * TORN_CHUNK_COUNT,
                "every reader has to observe the old version before the writers are released but only "
                        + observations.get(0) + " reads saw it"
        );
        assertTrue(
                observations.get(TORN_SECTORS.length - 1) >= TORN_READER_COUNT * TORN_CHUNK_COUNT,
                "every reader has to observe the final version after the writers finished but only "
                        + observations.get(TORN_SECTORS.length - 1) + " reads saw it"
        );
        assertEquals(
                (long) TORN_TOTAL_SECTORS * RegionConstants.SECTOR_SIZE, Files.size(path),
                "the barrier of this test rules every recycled sector out, so the file has to end at the computed size"
        );
        assertSectorTableIsDisjoint(path);
    }

    @Test
    void testReadersNeverObserveASectorWhichWasRecycledWhileTheyRead() throws IOException, InterruptedException, ExecutionException {
        for (int attempt = 0; attempt < RECYCLE_ATTEMPTS; attempt++) {
            readWhileTheSectorsAreRecycled(regionPath(attempt));
        }
    }

    /**
     * Shrinks and grows a single chunk while other chunks compete for the range it frees.
     * <p>
     * This is the scenario the torn read test deliberately keeps out of its way with its barrier.
     * The observed chunk alternates between the largest payload a location entry can address and a
     * single sector, so every shrink frees a large range. The filler chunks request exactly that
     * many sectors, so the first fit strategy of the allocator hands the freed range to one of them
     * while a reader may still be somewhere inside it. A reader must never see those foreign bytes,
     * must never see a header field of a foreign chunk and must never lose the chunk.
     * </p>
     *
     * @param path the path of the region file to work on
     * @throws IOException          if the region file cannot be used
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a worker failed
     */
    private void readWhileTheSectorsAreRecycled(Path path) throws IOException, InterruptedException, ExecutionException {
        byte[] large = marked(RECYCLE_LARGE_SECTORS * RegionConstants.SECTOR_SIZE - 5, RECYCLE_LARGE_MARKER);
        byte[] small = marked(RECYCLE_SMALL_SECTORS * RegionConstants.SECTOR_SIZE - 5, RECYCLE_SMALL_MARKER);
        Queue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch written = new CountDownLatch(1 + RECYCLE_FILLER_COUNT);

        try (RegionFile region = RegionFile.open(path);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            region.writeRaw(0, 0, ChunkCompression.ZLIB, large);

            List<Future<?>> futures = new ArrayList<>(1 + RECYCLE_FILLER_COUNT + RECYCLE_READER_COUNT);

            futures.add(executor.submit(() -> {
                awaitStart(start);

                try {
                    for (int round = 0; round < RECYCLE_ROUNDS; round++) {
                        region.writeRaw(0, 0, ChunkCompression.ZLIB, small);
                        region.writeRaw(0, 0, ChunkCompression.ZLIB, large);
                    }
                } finally {
                    written.countDown();
                }
                return null;
            }));

            for (int filler = 0; filler < RECYCLE_FILLER_COUNT; filler++) {
                byte[] payload = marked(RECYCLE_LARGE_SECTORS * RegionConstants.SECTOR_SIZE - 5, (byte) (0x80 + filler));
                int chunk = filler + 1;
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    try {
                        for (int round = 0; round < RECYCLE_ROUNDS * 2; round++) {
                            region.writeRaw(chunk, 0, ChunkCompression.ZLIB, payload);
                        }
                    } finally {
                        written.countDown();
                    }
                    return null;
                }));
            }

            for (int reader = 0; reader < RECYCLE_READER_COUNT; reader++) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    while (written.getCount() > 0) {
                        inspectRecycledRead(region, failures);
                        reads.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);

            assertArrayEquals(large, read(region, 0, 0), "the observed chunk lost its last version");
        }

        assertTrue(failures.isEmpty(), "a reader observed bytes of a recycled sector: " + failures);
        assertTrue(reads.get() > 0, "no reader ever read the observed chunk");
        assertSectorTableIsDisjoint(path);
    }

    @Test
    void testConcurrentStorageSwitchesKeepTheExternalFileAndTheHeaderInSync() throws IOException, InterruptedException, ExecutionException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            switchStorageConcurrently(regionPath(attempt), attempt);
        }
    }

    /**
     * Writes the same chunk from two threads where one payload needs an external file and the other
     * one does not.
     * <p>
     * The header entry decides where a reader looks for the payload, so the entry and the external
     * file have to change together. A writer which creates the file before it owns the header, or
     * removes it after it gave the header up, lets the other writer slip in between: the header then
     * claims an external payload while the file behind it is already gone, which breaks the chunk
     * for good instead of only for the moment.
     * </p>
     *
     * @param path   the path of the region file to work on
     * @param chunkZ the chunk z coordinate the attempt uses so its external file is its own
     * @throws IOException          if the region file cannot be used
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a worker failed
     */
    private void switchStorageConcurrently(Path path, int chunkZ) throws IOException, InterruptedException, ExecutionException {
        byte[] external = marked(EXTERNAL_PAYLOAD_LENGTH, EXTERNAL_MARKER);
        byte[] inline = marked(INLINE_PAYLOAD_LENGTH, INLINE_MARKER);
        Queue<String> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch written = new CountDownLatch(1);

        try (RegionFile region = RegionFile.open(path);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            region.writeRaw(0, chunkZ, ChunkCompression.ZLIB, inline);

            List<Future<?>> futures = new ArrayList<>(2 + SWITCH_READER_COUNT);

            // The external payload is three orders of magnitude larger than the inline one, so a
            // fixed round count would let the inline writer finish long before the first one even
            // reached its second round. The inline writer therefore keeps going until the external
            // one is done, which keeps both of them in the same window for the whole run.
            futures.add(executor.submit(() -> {
                awaitStart(start);

                try {
                    for (int round = 0; round < SWITCH_ROUNDS; round++) {
                        region.writeRaw(0, chunkZ, ChunkCompression.ZLIB, external);
                    }
                } finally {
                    written.countDown();
                }
                return null;
            }));

            futures.add(executor.submit(() -> {
                awaitStart(start);

                while (written.getCount() > 0) {
                    region.writeRaw(0, chunkZ, ChunkCompression.ZLIB, inline);
                }
                return null;
            }));

            for (int reader = 0; reader < SWITCH_READER_COUNT; reader++) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    while (written.getCount() > 0) {
                        inspectSwitchedRead(region, chunkZ, failures);
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);

            inspectSwitchedRead(region, chunkZ, failures);
        }

        assertTrue(failures.isEmpty(), "a reader could not follow the storage the header points at: " + failures);

        Path externalFile = this.tempDir.resolve("c.0." + chunkZ + ".mcc");
        assertEquals(
                ChunkCompression.isExternal(storedScheme(path, 0, chunkZ)), Files.exists(externalFile),
                "the header entry and the external file of the chunk describe a different storage"
        );

        try (RegionFile reopened = RegionFile.open(path)) {
            byte[] payload = read(reopened, 0, chunkZ);

            assertTrue(
                    Arrays.equals(external, payload) || Arrays.equals(inline, payload),
                    "the chunk holds " + payload.length + " bytes which belong to no version"
            );
        }
    }

    @Test
    void testConcurrentGrowAndShrinkCyclesKeepTheFileConsistent() throws IOException, InterruptedException, ExecutionException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            growAndShrinkConcurrently(regionPath(attempt), attempt);
        }
    }

    /**
     * Rewrites every chunk of a region file with changing payload sizes and verifies the result.
     * The chunk z coordinate differs per attempt so the external files of two attempts cannot
     * collide inside the shared temporary directory.
     *
     * @param path   the path of the region file to work on
     * @param chunkZ the chunk z coordinate every chunk of the attempt uses
     * @throws IOException          if the region file cannot be used
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a writer failed
     */
    private void growAndShrinkConcurrently(Path path, int chunkZ) throws IOException, InterruptedException, ExecutionException {
        // Every chunk is owned by exactly one thread, so the order of its own writes is defined
        // while the writes of the different chunks overlap. The sizes cross both a sector boundary
        // and the limit of an inline chunk, so the allocator has to free and reuse ranges of very
        // different lengths while other threads allocate. A missing lock lets two of those ranges
        // overlap, and a lost external file makes the payload unreadable.
        int chunkCount = 4;
        int rounds = 3;
        CountDownLatch start = new CountDownLatch(1);

        try (RegionFile region = RegionFile.open(path);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(chunkCount);

            for (int index = 0; index < chunkCount; index++) {
                int chunk = index;
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    for (int round = 0; round < rounds; round++) {
                        for (int size = 0; size < CYCLE_SIZES.length; size++) {
                            region.writeRaw(chunk, chunkZ, ChunkCompression.ZLIB, marked(CYCLE_SIZES[size], (byte) (chunk * 16 + round * 4 + size)));
                        }
                    }
                    region.writeRaw(chunk, chunkZ, ChunkCompression.ZLIB, marked(CYCLE_SIZES[chunk], (byte) (100 + chunk)));
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);
        }

        assertSectorTableIsDisjoint(path);

        try (RegionFile reopened = RegionFile.open(path)) {
            for (int chunk = 0; chunk < chunkCount; chunk++) {
                byte[] expected = marked(CYCLE_SIZES[chunk], (byte) (100 + chunk));

                assertArrayEquals(expected, read(reopened, chunk, chunkZ), "chunk " + chunk + " does not hold its last payload");
                assertEquals(
                        isExternal(CYCLE_SIZES[chunk]), Files.exists(this.tempDir.resolve("c." + chunk + "." + chunkZ + ".mcc")),
                        "the external file of the chunk " + chunk + " does not match its last payload"
                );
            }
        }
    }

    @Test
    void testClosingDuringConcurrentAccessFailsCleanlyAndKeepsTheFileReadable() throws IOException, InterruptedException, ExecutionException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            closeDuringConcurrentAccess(regionPath(attempt));
        }
    }

    /**
     * Closes a region file while readers and writers work on it and verifies the result.
     *
     * @param path the path of the region file to work on
     * @throws IOException          if the region file cannot be used
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a worker failed for an unexpected reason
     */
    private void closeDuringConcurrentAccess(Path path) throws IOException, InterruptedException, ExecutionException {
        // Closing a file while other threads work on it must never leave a header entry which
        // points at a range the file does not hold. Every worker has to report the shutdown as an
        // IOException, because any other exception type would reach a caller that only expects an
        // input output failure. The file is reopened afterwards, which rebuilds the sector usage
        // from the header and therefore rejects a layout which was destroyed by the close.
        int prefilled = 16;
        int writerCount = 4;
        int readerCount = 8;
        List<byte[]> payloads = new ArrayList<>(prefilled + writerCount);

        for (int index = 0; index < prefilled + writerCount; index++) {
            payloads.add(marked(RegionConstants.SECTOR_SIZE - 5, (byte) (index + 1)));
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch working = new CountDownLatch(writerCount + readerCount);
        AtomicInteger completedOperations = new AtomicInteger();
        List<AtomicReference<Throwable>> failures = new ArrayList<>(writerCount + readerCount);

        try (RegionFile region = RegionFile.open(path);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < prefilled; index++) {
                region.writeRaw(index, 0, ChunkCompression.ZLIB, payloads.get(index));
            }

            List<Future<?>> futures = new ArrayList<>(writerCount + readerCount);

            for (int index = 0; index < writerCount + readerCount; index++) {
                boolean writer = index < writerCount;
                int slot = writer ? prefilled + index : index - writerCount;
                AtomicReference<Throwable> failure = new AtomicReference<>();
                failures.add(failure);

                futures.add(executor.submit(() -> {
                    awaitStart(start);
                    boolean reported = false;

                    try {
                        while (true) {
                            if (writer) {
                                region.writeRaw(slot, 0, ChunkCompression.ZLIB, payloads.get(slot));
                            } else {
                                assertNotNull(region.readRaw(slot % prefilled, 0));
                            }
                            completedOperations.incrementAndGet();

                            if (!reported) {
                                reported = true;
                                working.countDown();
                            }
                        }
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        if (!reported) {
                            working.countDown();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitStart(working);
            region.close();
            awaitAll(futures);
        }

        assertTrue(completedOperations.get() >= writerCount + readerCount, "every worker has to run at least once before the close");

        for (int index = 0; index < failures.size(); index++) {
            Throwable failure = failures.get(index).get();

            assertNotNull(failure, "the worker " + index + " never noticed the closed file");
            assertInstanceOf(IOException.class, failure, "the worker " + index + " reported " + failure);
        }

        assertSectorTableIsDisjoint(path);

        try (RegionFile reopened = RegionFile.open(path)) {
            for (int index = 0; index < prefilled; index++) {
                assertArrayEquals(payloads.get(index), read(reopened, index, 0), "the close destroyed the chunk " + index);
            }
            for (int index = prefilled; index < prefilled + writerCount; index++) {
                RegionFile.RawChunk chunk = reopened.readRaw(index, 0);

                if (chunk != null) {
                    assertArrayEquals(payloads.get(index), chunk.payload(), "the close truncated the chunk " + index);
                }
            }
        }
    }

    /**
     * Builds a payload in which every byte carries the given marker.
     * A payload of a single repeated byte turns any mix of two payloads into a mismatch, no matter
     * at which offset the two were mixed.
     *
     * @param length the amount of bytes the payload holds
     * @param marker the byte every position of the payload carries
     * @return the created payload
     */
    private static byte[] marked(int length, byte marker) {
        byte[] payload = new byte[length];
        Arrays.fill(payload, marker);
        return payload;
    }

    /**
     * Builds the payload of a version of a chunk of the torn read test.
     *
     * @param chunk   the index of the chunk
     * @param version the index of the version
     * @return the created payload
     */
    private static byte[] tornPayload(int chunk, int version) {
        return marked(TORN_SECTORS[version] * RegionConstants.SECTOR_SIZE - 5, tornMarker(chunk, version));
    }

    /**
     * Builds the marker byte of a version of a chunk of the torn read test.
     * The marker holds both values so a payload which was written for another chunk is detected as
     * well as a payload which mixes two versions.
     *
     * @param chunk   the index of the chunk
     * @param version the index of the version
     * @return the marker byte of the version
     */
    private static byte tornMarker(int chunk, int version) {
        return (byte) ((chunk << 2) | version);
    }

    /**
     * Verifies that the given chunk holds exactly one version of exactly one chunk.
     * The version is derived from the length of the payload, which differs per version, and every
     * byte has to carry the marker of that version afterwards.
     *
     * @param chunk        the chunk which was read
     * @param chunkIndex   the index of the chunk which was requested
     * @param failures     the queue which collects the description of every violation
     * @param observations the counters which record how often a version was observed
     */
    private static void inspectTornRead(RegionFile.RawChunk chunk, int chunkIndex, Queue<String> failures, AtomicIntegerArray observations) {
        if (chunk == null) {
            failures.add("the chunk " + chunkIndex + " disappeared while it was rewritten");
            return;
        }

        byte[] payload = chunk.payload();
        int version = -1;

        for (int candidate = 0; candidate < TORN_SECTORS.length; candidate++) {
            if (payload.length == TORN_SECTORS[candidate] * RegionConstants.SECTOR_SIZE - 5) {
                version = candidate;
                break;
            }
        }

        if (version < 0) {
            failures.add("the chunk " + chunkIndex + " reported " + payload.length + " bytes which belong to no version");
            return;
        }

        byte expected = tornMarker(chunkIndex, version);

        for (int offset = 0; offset < payload.length; offset++) {
            if (payload[offset] != expected) {
                failures.add(
                        "the chunk " + chunkIndex + " holds " + payload[offset] + " at the offset " + offset
                                + " while its version " + version + " expects " + expected
                );
                return;
            }
        }
        observations.incrementAndGet(version);
    }

    /**
     * Reads the observed chunk of the recycling test once and records every deviation.
     * <p>
     * The chunk only ever holds one of two payloads, so both the length and the marker of a read are
     * known up front. A failure of the read itself is recorded as well, because a header field which
     * was overwritten by a foreign chunk shows up as a rejected length or an unknown scheme.
     * </p>
     *
     * @param region   the region file to read from
     * @param failures the queue which collects the description of every violation
     */
    private static void inspectRecycledRead(RegionFile region, Queue<String> failures) {
        RegionFile.RawChunk chunk;

        try {
            chunk = region.readRaw(0, 0);
        } catch (IOException exception) {
            failures.add("the observed chunk could not be read: " + exception);
            return;
        }

        if (chunk == null) {
            failures.add("the observed chunk disappeared while it was rewritten");
            return;
        }

        byte[] payload = chunk.payload();
        byte expected;

        if (payload.length == RECYCLE_LARGE_SECTORS * RegionConstants.SECTOR_SIZE - 5) {
            expected = RECYCLE_LARGE_MARKER;
        } else if (payload.length == RECYCLE_SMALL_SECTORS * RegionConstants.SECTOR_SIZE - 5) {
            expected = RECYCLE_SMALL_MARKER;
        } else {
            failures.add("the observed chunk reported " + payload.length + " bytes which belong to no version");
            return;
        }

        for (int offset = 0; offset < payload.length; offset++) {
            if (payload[offset] != expected) {
                failures.add(
                        "the observed chunk holds " + payload[offset] + " at the offset " + offset + " of "
                                + payload.length + " while it expects " + expected
                );
                return;
            }
        }
    }

    /**
     * Reads the chunk of the storage switch test once and records every deviation.
     *
     * @param region   the region file to read from
     * @param chunkZ   the chunk z coordinate the attempt uses
     * @param failures the queue which collects the description of every violation
     */
    private static void inspectSwitchedRead(RegionFile region, int chunkZ, Queue<String> failures) {
        RegionFile.RawChunk chunk;

        try {
            chunk = region.readRaw(0, chunkZ);
        } catch (IOException exception) {
            failures.add("the chunk could not be read: " + exception);
            return;
        }

        if (chunk == null) {
            failures.add("the chunk disappeared while it was rewritten");
            return;
        }

        byte[] payload = chunk.payload();
        byte expected;

        if (payload.length == EXTERNAL_PAYLOAD_LENGTH) {
            expected = EXTERNAL_MARKER;
        } else if (payload.length == INLINE_PAYLOAD_LENGTH) {
            expected = INLINE_MARKER;
        } else {
            failures.add("the chunk reported " + payload.length + " bytes which belong to no version");
            return;
        }

        for (int offset = 0; offset < payload.length; offset++) {
            if (payload[offset] != expected) {
                failures.add(
                        "the chunk holds " + payload[offset] + " at the offset " + offset + " of " + payload.length
                                + " while it expects " + expected
                );
                return;
            }
        }
    }

    /**
     * Reads the compression scheme byte a chunk carries straight from the region file on disk.
     *
     * @param path   the path of the region file to inspect
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the scheme byte of the chunk including the external flag
     * @throws IOException if the region file cannot be read
     */
    private static int storedScheme(Path path, int chunkX, int chunkZ) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        int location = ByteBuffer.wrap(bytes).getInt(RegionConstants.locationOffset(RegionConstants.index(chunkX, chunkZ)));

        assertTrue(location != 0, "the chunk " + chunkX + "/" + chunkZ + " has no entry in the location table");
        return bytes[(location >>> 8) * RegionConstants.SECTOR_SIZE + RegionConstants.LENGTH_FIELD_SIZE] & 0xFF;
    }

    /**
     * Checks whether a payload of the given length is stored outside of the region file.
     *
     * @param length the amount of bytes the payload holds
     * @return true if the payload needs an external file, otherwise false
     */
    private static boolean isExternal(int length) {
        return RegionConstants.sectorsFor(RegionConstants.LENGTH_FIELD_SIZE + RegionConstants.COMPRESSION_FIELD_SIZE + length)
                > RegionConstants.MAX_SECTORS_PER_CHUNK;
    }

    /**
     * Reads the payload of a chunk and fails when the chunk is absent.
     *
     * @param region the region file to read from
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the payload of the chunk
     * @throws IOException if the chunk cannot be read
     */
    private static byte[] read(RegionFile region, int chunkX, int chunkZ) throws IOException {
        RegionFile.RawChunk chunk = region.readRaw(chunkX, chunkZ);

        assertNotNull(chunk, "the chunk " + chunkX + "/" + chunkZ + " is missing");
        return chunk.payload();
    }

    /**
     * Verifies that no two entries of the location table describe overlapping sectors.
     * <p>
     * The check reads the header straight from disk instead of asking the region file, so it also
     * covers the case in which the in memory tables and the stored ones drifted apart.
     * </p>
     *
     * @param path the path of the region file to inspect
     * @throws IOException if the region file cannot be read
     */
    private static void assertSectorTableIsDisjoint(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);

        assertEquals(0, bytes.length % RegionConstants.SECTOR_SIZE, "the region file is not aligned to the sector size");

        ByteBuffer header = ByteBuffer.wrap(bytes);
        int totalSectors = bytes.length / RegionConstants.SECTOR_SIZE;
        int[] owner = new int[totalSectors];
        Arrays.fill(owner, -1);

        for (int index = 0; index < RegionConstants.ENTRY_COUNT; index++) {
            int location = header.getInt(RegionConstants.locationOffset(index));

            if (location == 0) {
                continue;
            }

            int offset = location >>> 8;
            int count = location & 0xFF;

            assertTrue(offset >= RegionConstants.HEADER_SECTORS, "the entry " + index + " points into the header at the sector " + offset);
            assertTrue(count > 0, "the entry " + index + " spans no sector at all");
            assertTrue(offset + count <= totalSectors, "the entry " + index + " ends behind the file at the sector " + (offset + count));

            for (int sector = offset; sector < offset + count; sector++) {
                assertEquals(-1, owner[sector], "the sector " + sector + " is claimed by the entries " + owner[sector] + " and " + index);
                owner[sector] = index;
            }
        }
    }

    /**
     * Waits for the given latch and fails when it is not released in time.
     *
     * @param latch the latch to wait for
     */
    private static void awaitStart(CountDownLatch latch) {
        try {
            assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS), "a worker waited too long for its barrier");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("a worker was interrupted while it waited for its barrier");
        }
    }

    /**
     * Waits for every given task and propagates the failure of the first broken one.
     *
     * @param futures the tasks to wait for
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a task failed
     */
    private static void awaitAll(List<Future<?>> futures) throws InterruptedException, ExecutionException {
        for (Future<?> future : futures) {
            future.get();
        }
    }
}
