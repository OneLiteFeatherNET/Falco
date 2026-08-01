package net.onelitefeather.falco.anvil;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.exception.ExceptionHandler;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stresses the chunk loader with several region files which are used from many threads at once.
 * <p>
 * The loader reports parallel loading and parallel saving as supported, so a running server hands it
 * work from many threads. The tests here verify the two properties that promise depends on: no chunk
 * may be lost while other chunks of the same region file are written, and the amount of region files
 * the loader keeps open has to stay below its limit even when every thread opens a new one.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoAnvilLoaderConcurrencyTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");

    /**
     * The time a latch is waited for before the test is considered stuck.
     */
    private static final long AWAIT_SECONDS = 60L;

    /**
     * The time {@code close()} is given to return while another thread holds the monitor of the
     * loader.
     * <p>
     * Deliberately far shorter than {@link #AWAIT_SECONDS}: this latch is only reached in the
     * failing case, and a regression should report itself in seconds rather than stall the build
     * for a minute.
     * </p>
     */
    private static final long CLOSE_SECONDS = 5L;

    /**
     * The amount of region files the chunks of the first test are spread over.
     */
    private static final int REGION_COUNT = 4;

    /**
     * The amount of chunks every region file of the first test holds.
     */
    private static final int CHUNKS_PER_REGION = 4;

    @TempDir
    private Path worldRoot;

    /**
     * Creates a loader for the temporary world of the test.
     *
     * @return the created loader
     */
    private FalcoAnvilLoader loader() {
        return new FalcoAnvilLoader(this.worldRoot, OVERWORLD);
    }

    @Test
    void testConcurrentSavesAndLoadsOverSeveralRegionsLoseNoChunk(Env env) throws IOException, InterruptedException, ExecutionException {
        // Half of the chunks are already on disk and are only read while the other half is written.
        // Reading and writing therefore meet inside the same region files, which is the situation a
        // server produces while it streams chunks in and out. A region file which mixed the two
        // would either hand back a payload of the wrong chunk or fail to decompress it, and both
        // show up as a missing marker block or as a counted error.
        Instance instance = env.createEmptyInstance(loader());
        List<Chunk> chunks = createChunks(instance);
        List<Chunk> stored = new ArrayList<>();
        List<List<Chunk>> groups = new ArrayList<>();

        for (int region = 0; region < REGION_COUNT; region++) {
            List<Chunk> group = new ArrayList<>();

            for (int local = 0; local < CHUNKS_PER_REGION; local++) {
                Chunk chunk = chunks.get(region * CHUNKS_PER_REGION + local);

                if (local < CHUNKS_PER_REGION / 2) {
                    stored.add(chunk);
                } else {
                    group.add(chunk);
                }
            }
            groups.add(group);
        }

        CountDownLatch start = new CountDownLatch(1);

        try (FalcoAnvilLoader loader = loader();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Chunk chunk : stored) {
                loader.saveChunk(chunk);
            }

            List<Future<?>> futures = new ArrayList<>(groups.size() + stored.size());

            for (List<Chunk> group : groups) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);
                    loader.saveChunks(group);
                    return null;
                }));
            }
            for (Chunk chunk : stored) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);
                    assertNotNull(
                            loader.loadChunk(instance, chunk.getChunkX(), chunk.getChunkZ()),
                            "the chunk " + chunk.getChunkX() + "/" + chunk.getChunkZ() + " vanished while other chunks were written"
                    );
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);

            assertEquals(0, loader.diagnostics().errors(), "no chunk may fail while the loader works in parallel");
            assertEquals(chunks.size(), loader.diagnostics().chunksSaved());
            assertEquals(stored.size(), loader.diagnostics().chunksLoaded());
            assertEquals(REGION_COUNT, loader.openRegionCount(), "the loader has to hold exactly one file per region");
            assertTrue(loader.openRegionCount() <= FalcoAnvilLoader.DEFAULT_OPEN_REGION_LIMIT);
        }

        try (FalcoAnvilLoader reader = loader()) {
            for (int index = 0; index < chunks.size(); index++) {
                Chunk chunk = chunks.get(index);
                Chunk loaded = reader.loadChunk(instance, chunk.getChunkX(), chunk.getChunkZ());

                assertNotNull(loaded, "the chunk " + chunk.getChunkX() + "/" + chunk.getChunkZ() + " is missing");
                assertMarker(loaded, index);
            }
            assertEquals(0, reader.diagnostics().errors());
        }
    }

    @Test
    void testTheOpenRegionLimitHoldsWhileManyThreadsOpenRegions(Env env) throws IOException, InterruptedException, ExecutionException {
        // Every thread works in a region file of its own, so every one of them opens a new file and
        // forces the loader to evict another one. A limit which is only respected by a single thread
        // would let the amount of open files grow with the amount of threads, which is exactly the
        // file descriptor leak the limit exists to prevent.
        // An eviction drops a file from the cache but never closes it under the thread which is
        // writing to it, so every save has to succeed even though the limit is hit constantly. A
        // failure would be reported to the exception manager, which the environment turns into a
        // failed test on its own.
        int regionCount = 8;
        int limit = 2;
        Instance instance = env.createEmptyInstance(loader());
        List<Chunk> chunks = new ArrayList<>(regionCount);

        for (int region = 0; region < regionCount; region++) {
            Chunk chunk = instance.loadChunk(region * 32, 0).join();
            place(chunk, 0, 40, 0, Block.STONE);
            chunks.add(chunk);
        }

        CountDownLatch start = new CountDownLatch(1);

        try (FalcoAnvilLoader loader = new FalcoAnvilLoader(this.worldRoot, OVERWORLD, limit)) {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(regionCount);

                for (Chunk chunk : chunks) {
                    futures.add(executor.submit(() -> {
                        awaitStart(start);
                        loader.saveChunk(chunk);
                        return null;
                    }));
                }
                start.countDown();
                awaitAll(futures);
            }

            assertTrue(
                    loader.openRegionCount() <= limit,
                    "the loader has to stay below its limit of " + limit + " files but held " + loader.openRegionCount()
            );
            assertEquals(0, loader.diagnostics().errors(), "an eviction may not make the save of another thread fail");
            assertEquals(regionCount, loader.diagnostics().chunksSaved(), "every chunk has to be written");
            assertTrue(loader.openRegionCount() <= limit, "the limit has to hold after the concurrent pass as well");
        }

        try (FalcoAnvilLoader reader = loader()) {
            for (Chunk chunk : chunks) {
                assertNotNull(
                        reader.loadChunk(instance, chunk.getChunkX(), chunk.getChunkZ()),
                        "the chunk " + chunk.getChunkX() + "/" + chunk.getChunkZ() + " was lost"
                );
            }
        }
    }

    @Test
    void testLoadingSurvivesTheEvictionOfItsRegionFile(Env env) throws IOException, InterruptedException, ExecutionException {
        // Every thread reads from a region file of its own while the limit allows a single open
        // file, so every load evicts the file another thread is about to read from. The loader owns
        // that eviction, the file on disk is intact, and a read which fails here loses a chunk the
        // server would then regenerate over the stored data.
        int regionCount = 8;
        int rounds = 60;
        Instance instance = env.createEmptyInstance(loader());
        List<Chunk> chunks = storeChunkPerRegion(instance, regionCount);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        ExceptionHandler previous = MinecraftServer.getExceptionManager().getExceptionHandler();

        try (FalcoAnvilLoader loader = new FalcoAnvilLoader(this.worldRoot, OVERWORLD, 1)) {
            // A failed load reports to the exception manager, which the environment turns into a
            // failed test before the assertion below could describe what went wrong.
            MinecraftServer.getExceptionManager().setExceptionHandler(_ -> {
            });

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(chunks.size());

                for (Chunk chunk : chunks) {
                    futures.add(executor.submit(() -> {
                        awaitStart(start);
                        readRepeatedly(loader, instance, chunk, rounds, failures, false);
                        return null;
                    }));
                }
                start.countDown();
                awaitAll(futures);
            } finally {
                MinecraftServer.getExceptionManager().setExceptionHandler(previous);
            }
        }
        assertNoFailure(failures, "a load may not fail because another thread evicted its region file");
    }

    @Test
    void testLoadingSurvivesTheUnloadOfAnotherChunkOfTheSameRegion(Env env) throws IOException, InterruptedException, ExecutionException {
        // Every chunk of this test lives in the same region file. A thread which unloads the last
        // chunk the loader tracks closes that file, and the loader only starts tracking a chunk
        // after it has been read, so a reader which is still ahead of its own registration loses
        // the handle it already holds.
        int chunkCount = 8;
        int rounds = 60;
        Instance instance = env.createEmptyInstance(loader());
        List<Chunk> chunks = new ArrayList<>(chunkCount);

        for (int chunkX = 0; chunkX < chunkCount; chunkX++) {
            Chunk chunk = instance.loadChunk(chunkX, 0).join();
            place(chunk, 0, 40, 0, Block.STONE);
            chunks.add(chunk);
        }

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunks(chunks);
        }

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        ExceptionHandler previous = MinecraftServer.getExceptionManager().getExceptionHandler();

        try (FalcoAnvilLoader loader = loader()) {
            MinecraftServer.getExceptionManager().setExceptionHandler(_ -> {
            });

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(chunks.size());

                for (Chunk chunk : chunks) {
                    futures.add(executor.submit(() -> {
                        awaitStart(start);
                        readRepeatedly(loader, instance, chunk, rounds, failures, true);
                        return null;
                    }));
                }
                start.countDown();
                awaitAll(futures);
            } finally {
                MinecraftServer.getExceptionManager().setExceptionHandler(previous);
            }
        }
        assertNoFailure(failures, "a load may not fail because another thread unloaded a chunk of the same region");
    }

    /**
     * A caller who holds the monitor of the loader cannot stop it from closing.
     * <p>
     * The loader is handed to the server and from there to arbitrary code, so any of it may write
     * {@code synchronized (loader)} — over a batch of saves, for instance. While {@code close()}
     * carried the {@code synchronized} modifier it locked on that same monitor, and such a caller
     * blocked the shutdown of a loader whose own Javadoc says it is closed while chunk tasks are
     * still in flight. The lock is private now, and this test is what distinguishes the two: with
     * the modifier back on {@code close()} it fails on the latch below rather than passing quietly.
     * </p>
     * <p>
     * The threads are platform threads on purpose. What is under test is the monitor, and a
     * platform thread parks on it with no scheduler in between.
     * </p>
     *
     * @throws InterruptedException if the test thread is interrupted while waiting for a latch
     */
    @Test
    void testCloseReturnsWhileAnotherThreadHoldsTheLoaderMonitor() throws InterruptedException {
        FalcoAnvilLoader loader = loader();

        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            synchronized (loader) {
                monitorHeld.countDown();
                awaitQuietly(releaseMonitor, AWAIT_SECONDS);
            }
        }, "loader-monitor-holder");

        Thread closer = new Thread(() -> {
            try {
                loader.close();
            } catch (Throwable throwable) {
                closeFailure.set(throwable);
            } finally {
                closeReturned.countDown();
            }
        }, "loader-closer");

        holder.start();
        assertTrue(monitorHeld.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                "the holder thread never entered synchronized (loader)");

        try {
            closer.start();
            assertTrue(closeReturned.await(CLOSE_SECONDS, TimeUnit.SECONDS),
                    "close() did not return within " + CLOSE_SECONDS + " s while another thread held "
                            + "synchronized (loader), so the loader is sharing its own monitor with "
                            + "its callers");
        } finally {
            releaseMonitor.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            holder.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }

        Throwable failure = closeFailure.get();

        if (failure != null) {
            fail("close() failed instead of completing: " + failure);
        }
    }

    /**
     * Waits for the given latch and restores the interrupt flag instead of failing the thread.
     *
     * @param latch   the latch to wait for
     * @param seconds the amount of seconds to wait at most
     */
    private static void awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes a single chunk into every one of the given amount of region files.
     *
     * @param instance    the instance which owns the chunks
     * @param regionCount the amount of region files to fill
     * @return the written chunks in the order of their region
     * @throws IOException if a chunk cannot be written
     */
    private List<Chunk> storeChunkPerRegion(Instance instance, int regionCount) throws IOException {
        List<Chunk> chunks = new ArrayList<>(regionCount);

        for (int region = 0; region < regionCount; region++) {
            Chunk chunk = instance.loadChunk(region * 32, 0).join();
            place(chunk, 0, 40, 0, Block.STONE);
            chunks.add(chunk);
        }

        try (FalcoAnvilLoader writer = loader()) {
            for (Chunk chunk : chunks) {
                writer.saveChunk(chunk);
            }
        }
        return chunks;
    }

    /**
     * Reads the given chunk repeatedly and records every failure instead of throwing it.
     * The failures are collected so the assertion of the test can describe all of them instead of
     * being replaced by the first exception a worker throws.
     *
     * @param loader   the loader to read through
     * @param instance the instance which owns the chunk
     * @param chunk    the chunk to read
     * @param rounds   the amount of reads to perform
     * @param failures the list which receives the failures
     * @param unload   whether the chunk is unloaded again after every read
     */
    private static void readRepeatedly(
            FalcoAnvilLoader loader, Instance instance, Chunk chunk, int rounds, List<Throwable> failures, boolean unload
    ) {
        for (int round = 0; round < rounds; round++) {
            try {
                Chunk loaded = loader.loadChunk(instance, chunk.getChunkX(), chunk.getChunkZ());

                if (loaded == null) {
                    failures.add(new IllegalStateException(
                            "the chunk " + chunk.getChunkX() + "/" + chunk.getChunkZ() + " was reported as absent"
                    ));
                    continue;
                }
                if (unload) {
                    loader.unloadChunk(loaded);
                }
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
    }

    /**
     * Fails the calling test when at least one worker recorded a failure.
     *
     * @param failures the recorded failures
     * @param message  the message which describes the expectation
     */
    private static void assertNoFailure(List<Throwable> failures, String message) {
        if (failures.isEmpty()) {
            return;
        }
        fail(message + " but " + failures.size() + " of them did, the first one was: " + failures.getFirst(), failures.getFirst());
    }

    /**
     * Creates the chunks of the first test and marks every one of them with its own block.
     * The chunks are spread over {@link #REGION_COUNT} region files so the threads of the test meet
     * inside the same files instead of working on separate ones.
     *
     * @param instance the instance which owns the chunks
     * @return the created chunks in the order of their index
     */
    private static List<Chunk> createChunks(Instance instance) {
        List<Chunk> chunks = new ArrayList<>(REGION_COUNT * CHUNKS_PER_REGION);

        for (int region = 0; region < REGION_COUNT; region++) {
            for (int local = 0; local < CHUNKS_PER_REGION; local++) {
                int chunkX = (region % 2) * 32 + local % 2;
                int chunkZ = (region / 2) * 32 + local / 2;
                Chunk chunk = instance.loadChunk(chunkX, chunkZ).join();

                place(chunk, 0, 40, 0, Block.STONE);
                place(chunk, chunks.size(), 41, 0, Block.DIRT);
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /**
     * Verifies that the given chunk carries the marker of the given index and nothing else.
     * The marker sits at a different position per chunk, so a chunk which received the payload of
     * another one is detected instead of only being detected as present.
     *
     * @param chunk the chunk to inspect
     * @param index the index the chunk was created with
     */
    private static void assertMarker(Chunk chunk, int index) {
        assertEquals(Block.STONE, blockAt(chunk, 0, 40, 0), "the chunk " + index + " lost its shared marker");
        assertEquals(Block.DIRT, blockAt(chunk, index, 41, 0), "the chunk " + index + " lost its own marker");
        assertEquals(
                Block.AIR, blockAt(chunk, (index + 1) % (REGION_COUNT * CHUNKS_PER_REGION), 41, 0),
                "the chunk " + index + " carries the marker of another chunk"
        );
    }

    /**
     * Places a block in the given chunk while holding its write lock.
     *
     * @param chunk the chunk which receives the block
     * @param x     the x coordinate inside the chunk
     * @param y     the y coordinate of the block
     * @param z     the z coordinate inside the chunk
     * @param block the block to place
     */
    private static void place(Chunk chunk, int x, int y, int z, Block block) {
        chunk.lockWriteLock();
        try {
            chunk.setBlock(x, y, z, block);
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Reads a block of the given chunk while holding its read lock.
     *
     * @param chunk the chunk to read
     * @param x     the x coordinate inside the chunk
     * @param y     the y coordinate of the block
     * @param z     the z coordinate inside the chunk
     * @return the block at the given position
     */
    private static Block blockAt(Chunk chunk, int x, int y, int z) {
        chunk.lockReadLock();
        try {
            return chunk.getBlock(x, y, z);
        } finally {
            chunk.unlockReadLock();
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
