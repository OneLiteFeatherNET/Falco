package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins down that a single {@link ChunkLightService} may serve many threads at once.
 * <p>
 * A server lights the chunks around a player in parallel and naturally keeps one service for the
 * whole instance, so the shape of that usage decides whether the type is usable at all. The danger
 * is that a broken service fails quietly: the light is written through
 * {@link net.minestom.server.instance.light.Light#set(byte[])}, which clears the update flag of the
 * section, so a wrong result is never recomputed by the server and only ever shows up as a dark
 * patch in a world that nobody can explain.
 * </p>
 * <p>
 * The tests therefore compare against a reference which was calculated one chunk after the other.
 * Every worker owns its own chunk, so nothing the workers do can legitimately interfere; any
 * difference from the reference can only come from state the service kept between two calls. The
 * fixtures put their sources at a different height in every chunk so that two workers which drifted
 * into each other produce visibly different columns instead of accidentally matching results.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class ChunkLightServiceConcurrencyTest {

    /**
     * The time a latch is waited for before the test is considered stuck.
     */
    private static final long AWAIT_SECONDS = 60L;

    /**
     * The amount of workers which use the service at the same time.
     */
    private static final int THREAD_COUNT = 8;

    /**
     * The amount of times every worker recalculates its chunk.
     */
    private static final int ROUNDS = 12;

    @Test
    void testOneServiceCalculatesTheSameBlockLightFromManyThreads(Env env) throws InterruptedException, ExecutionException {
        Instance instance = env.createEmptyInstance();
        List<Chunk> chunks = lampChunks(instance);
        List<List<byte[]>> expected = new ArrayList<>(chunks.size());

        // The reference is taken one chunk after the other. A service which keeps nothing between
        // two calls has to reproduce it no matter how many threads call it.
        for (Chunk chunk : chunks) {
            ChunkLightService reference = new ChunkLightService();
            reference.calculate(chunk);
            expected.add(blockLightOf(chunk));
        }

        ChunkLightService shared = new ChunkLightService();

        run(chunks, worker -> {
            Chunk chunk = chunks.get(worker);

            for (int round = 0; round < ROUNDS; round++) {
                shared.calculate(chunk);
                assertLight(expected.get(worker), blockLightOf(chunk), "block light", worker, round);
            }
        });
    }

    @Test
    void testOneServiceCalculatesTheSameSkyLightFromManyThreads(Env env) throws InterruptedException, ExecutionException {
        Instance instance = env.createEmptyInstance();
        List<Chunk> chunks = ceilingChunks(instance);
        List<List<byte[]>> expected = new ArrayList<>(chunks.size());

        for (Chunk chunk : chunks) {
            ChunkLightService reference = new ChunkLightService();
            reference.calculateSky(chunk);
            expected.add(skyLightOf(chunk));
        }

        ChunkLightService shared = new ChunkLightService();

        run(chunks, worker -> {
            Chunk chunk = chunks.get(worker);

            for (int round = 0; round < ROUNDS; round++) {
                shared.calculateSky(chunk);
                assertLight(expected.get(worker), skyLightOf(chunk), "sky light", worker, round);
            }
        });
    }

    /**
     * The work a single worker performs on the chunk it owns.
     */
    @FunctionalInterface
    private interface Worker {

        /**
         * Runs the work of the worker with the given number.
         *
         * @param worker the number of the worker, which is also the index of its chunk
         */
        void run(int worker);
    }

    /**
     * Starts one worker per chunk and waits for all of them.
     * <p>
     * Every worker waits at the same barrier before it starts, so the calls overlap instead of
     * running one after the other, which is the only way a shared buffer can be observed at all.
     * </p>
     *
     * @param chunks the chunks the workers operate on
     * @param worker the work a single worker performs
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a worker failed
     */
    private static void run(List<Chunk> chunks, Worker worker) throws InterruptedException, ExecutionException {
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(chunks.size());

            for (int index = 0; index < chunks.size(); index++) {
                int number = index;
                futures.add(executor.submit(() -> {
                    awaitStart(start);
                    worker.run(number);
                    return null;
                }));
            }
            start.countDown();

            for (Future<?> future : futures) {
                future.get();
            }
        }
    }

    /**
     * Loads one chunk per worker and puts light sources and a wall into every one of them.
     * <p>
     * The sources sit at a different height in every chunk, so the calculated columns differ from
     * each other, and the wall forces the search around it instead of letting it finish in a
     * straight sphere.
     * </p>
     *
     * @param instance the instance which holds the chunks
     * @return the prepared chunks, one per worker
     */
    private static List<Chunk> lampChunks(Instance instance) {
        List<Chunk> chunks = new ArrayList<>(THREAD_COUNT);

        for (int index = 0; index < THREAD_COUNT; index++) {
            Chunk chunk = instance.loadChunk(index, 0).join();
            int height = 24 + index * 8;

            place(chunk, 8, height, 8, Block.GLOWSTONE);
            place(chunk, 2, height + 5, 12, Block.GLOWSTONE);

            for (int y = height - 6; y <= height + 6; y++) {
                for (int z = 0; z < 16; z++) {
                    place(chunk, 11, y, z, Block.STONE);
                }
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * Loads one chunk per worker and covers every one of them with a ceiling at its own height.
     * <p>
     * A ceiling is what makes a sky light run interesting, because the light falls freely above it
     * and has to spread step by step through the hole that is left open below it.
     * </p>
     *
     * @param instance the instance which holds the chunks
     * @return the prepared chunks, one per worker
     */
    private static List<Chunk> ceilingChunks(Instance instance) {
        List<Chunk> chunks = new ArrayList<>(THREAD_COUNT);

        for (int index = 0; index < THREAD_COUNT; index++) {
            Chunk chunk = instance.loadChunk(index, 0).join();
            int height = 40 + index * 8;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    place(chunk, x, height, z, Block.STONE);
                }
            }
            // A single hole lets the sky light in and makes it spread underneath the ceiling.
            place(chunk, 8, height, 8, Block.AIR);
            chunks.add(chunk);
        }
        return chunks;
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
     * Reads the stored block light of every section of the given chunk.
     *
     * @param chunk the chunk to read
     * @return the stored bytes of every section, ordered from the lowest section upwards
     */
    private static List<byte[]> blockLightOf(Chunk chunk) {
        chunk.lockReadLock();
        try {
            List<Section> sections = chunk.getSections();
            List<byte[]> light = new ArrayList<>(sections.size());

            for (Section section : sections) {
                light.add(section.blockLight().array());
            }
            return light;
        } finally {
            chunk.unlockReadLock();
        }
    }

    /**
     * Reads the stored sky light of every section of the given chunk.
     *
     * @param chunk the chunk to read
     * @return the stored bytes of every section, ordered from the lowest section upwards
     */
    private static List<byte[]> skyLightOf(Chunk chunk) {
        chunk.lockReadLock();
        try {
            List<Section> sections = chunk.getSections();
            List<byte[]> light = new ArrayList<>(sections.size());

            for (Section section : sections) {
                light.add(section.skyLight().array());
            }
            return light;
        } finally {
            chunk.unlockReadLock();
        }
    }

    /**
     * Compares the light of every section against the reference.
     *
     * @param expected the light of every section of the single threaded run
     * @param actual   the light of every section which was calculated by a worker
     * @param label    the name of the pass which produced the light
     * @param worker   the number of the worker which produced the light
     * @param round    the round the light was calculated in
     */
    private static void assertLight(List<byte[]> expected, List<byte[]> actual, String label, int worker, int round) {
        assertEquals(expected.size(), actual.size(), "the " + label + " of the worker " + worker + " lost a section in round " + round);

        for (int section = 0; section < expected.size(); section++) {
            assertArrayEquals(
                    expected.get(section), actual.get(section),
                    "the " + label + " of the section " + section + " of the worker " + worker + " drifted in round " + round
            );
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
}
