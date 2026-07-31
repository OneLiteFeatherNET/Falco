package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stresses the parts of the light engine which are used from more than one thread.
 * <p>
 * The engine splits its types into two groups. {@link SectionOpacity} is immutable once it is built
 * and is therefore shared between the workers of a chunk batch, while {@link LightPropagator} and
 * {@link ChunkLightPropagator} keep working buffers and are documented as reusable but confined to
 * a single thread. The tests here verify exactly that split: many threads may read one opacity
 * table, and many threads may propagate at the same time as long as every one of them owns its
 * propagator.
 * </p>
 * <p>
 * Sharing a single propagator between threads is deliberately not tested. It is not part of the
 * contract of those types, their buffers are plain arrays which are cleared at the start of a run,
 * and a test which asserted anything about that case would only pin down undefined behaviour. The
 * contract that is worth protecting is the one this class asserts, namely that an independent
 * instance per thread produces the same result as a single threaded run.
 * </p>
 * <p>
 * {@link ChunkLightService} is the other side of that split and does promise to serve many threads
 * at once, which it can only do by giving every call its own propagator.
 * {@link ChunkLightServiceConcurrencyTest} holds it to that promise.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class LightEngineConcurrencyTest {

    /**
     * The time a latch is waited for before the test is considered stuck.
     */
    private static final long AWAIT_SECONDS = 60L;

    /**
     * The state id of a block which neither emits nor occludes light.
     */
    private static final int AIR = 0;

    /**
     * The state id of a block which occludes every face.
     */
    private static final int STONE = 1;

    /**
     * The state id of a block which emits the highest level.
     */
    private static final int LAMP = 2;

    /**
     * The state id of a block which occludes its lower face only.
     */
    private static final int SLAB = 3;

    /**
     * A source which describes the four blocks of the fixtures without touching any registry.
     * <p>
     * Every emitting block of the fixtures carries the same level on purpose. The breadth first
     * search of the engine assumes that the queued positions are ordered by their level, which only
     * holds while every source starts at the same one. Mixing two emission levels makes the search
     * revisit positions and overflow its queue, which is a defect of the propagation itself and has
     * nothing to do with the thread safety these tests are about.
     * </p>
     */
    private static final BlockLightSource SOURCE = new BlockLightSource() {

        @Override
        public int emission(int stateId) {
            return stateId == LAMP ? 15 : 0;
        }

        @Override
        public boolean blocksFace(int stateId, BlockFace face) {
            return switch (stateId) {
                case STONE -> true;
                case SLAB -> face == BlockFace.BOTTOM;
                default -> false;
            };
        }
    };

    @Test
    void testIndependentPropagatorsProduceTheSameResultAsASingleThreadedRun() throws InterruptedException, ExecutionException {
        // The reference is calculated on one thread first. Any state which leaks out of a propagator
        // into a shared place, a static buffer for example, makes the parallel results drift away
        // from that reference. Every thread runs every fixture repeatedly so a result which only
        // breaks under interleaving is hit as well.
        List<SectionOpacity> fixtures = sections(6);
        List<byte[]> expected = new ArrayList<>(fixtures.size());
        List<Boolean> expectedUniform = new ArrayList<>(fixtures.size());
        LightPropagator reference = new LightPropagator();

        for (SectionOpacity fixture : fixtures) {
            LightNibbles light = reference.propagate(fixture);
            expected.add(light.toDenseArray());
            expectedUniform.add(light.isUniform());
        }

        int threadCount = 8;
        int rounds = 12;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(threadCount);

            for (int thread = 0; thread < threadCount; thread++) {
                futures.add(executor.submit(() -> {
                    LightPropagator propagator = new LightPropagator();
                    awaitStart(start);

                    for (int round = 0; round < rounds; round++) {
                        for (int fixture = 0; fixture < fixtures.size(); fixture++) {
                            LightNibbles light = propagator.propagate(fixtures.get(fixture));

                            assertArrayEquals(expected.get(fixture), light.toDenseArray(), "the fixture " + fixture + " drifted in round " + round);
                            assertEquals(expectedUniform.get(fixture), light.isUniform(), "the fixture " + fixture + " changed its storage in round " + round);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);
        }
    }

    @Test
    void testIndependentChunkPropagatorsProduceTheSameResultAsASingleThreadedRun() throws InterruptedException, ExecutionException {
        // The chunk propagator sizes its buffers on the first run and keeps them afterwards, which
        // is the part that would break loudly if an instance were shared. Every thread owns one, so
        // both the block light and the sky light of the same column have to match the reference.
        List<SectionOpacity> column = sections(4);
        ChunkLightPropagator reference = new ChunkLightPropagator();
        List<byte[]> expectedBlock = dense(reference.propagate(column));
        List<byte[]> expectedSky = dense(reference.propagateSky(column));

        int threadCount = 8;
        int rounds = 8;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(threadCount);

            for (int thread = 0; thread < threadCount; thread++) {
                futures.add(executor.submit(() -> {
                    ChunkLightPropagator propagator = new ChunkLightPropagator();
                    awaitStart(start);

                    for (int round = 0; round < rounds; round++) {
                        assertDense(expectedBlock, propagator.propagate(column), "block light", round);
                        assertDense(expectedSky, propagator.propagateSky(column), "sky light", round);
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);
        }
    }

    @Test
    void testConcurrentReadsOfAnOpacityTableStayConsistent() throws InterruptedException, ExecutionException {
        // The table is built once and shared by every worker of a chunk batch, so it has to answer
        // the same thing to every thread forever. A checksum over every position and every face
        // covers the whole table in one value, so a single entry which changed under concurrency
        // makes the checksum of that thread differ from the reference.
        SectionOpacity opacity = sections(1).getFirst();
        long expected = checksum(opacity);
        boolean expectedEmission = opacity.hasEmission();
        boolean expectedTransparency = opacity.isFullyTransparent();

        int threadCount = 16;
        int rounds = 20;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(threadCount);

            for (int thread = 0; thread < threadCount; thread++) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    for (int round = 0; round < rounds; round++) {
                        assertEquals(expected, checksum(opacity), "the opacity table answered differently in round " + round);
                        assertEquals(expectedEmission, opacity.hasEmission());
                        assertEquals(expectedTransparency, opacity.isFullyTransparent());
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);
        }
    }

    @Test
    void testACopyStaysIndependentWhileItsSourceIsMutated() throws InterruptedException, ExecutionException {
        // A propagation hands its result to another thread through a copy, so a copy which still
        // shared the array of its source would let the next mutation of the source rewrite a result
        // that was already published. One thread rewrites the source continuously while the others
        // keep reading the copy, which turns a shared array into an immediate mismatch.
        LightNibbles source = LightNibbles.uniform(0);

        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    source.set(x, y, z, (x + y + z) % 8);
                }
            }
        }

        LightNibbles copy = source.copy();
        byte[] expected = copy.toDenseArray();
        int readerCount = 8;
        int rounds = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch readersDone = new CountDownLatch(readerCount);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(readerCount + 1);

            for (int reader = 0; reader < readerCount; reader++) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    try {
                        for (int round = 0; round < rounds; round++) {
                            assertArrayEquals(expected, copy.toDenseArray(), "the copy changed in round " + round);
                            assertFalse(copy.isUniform(), "the copy must keep its own array");
                            assertEquals(0, copy.get(0, 0, 0), "the copy must not see the mutation of its source");
                        }
                    } finally {
                        readersDone.countDown();
                    }
                    return null;
                }));
            }

            // A single writer is enough and keeps the source itself well defined. The class is
            // documented as not thread safe, so mutating it from several threads at once would only
            // race with itself instead of testing the independence of the copy.
            futures.add(executor.submit(() -> {
                awaitStart(start);

                while (readersDone.getCount() > 0) {
                    for (int y = 0; y < LightNibbles.DIMENSION; y++) {
                        for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                            for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                                source.set(x, y, z, LightNibbles.MAX_LEVEL);
                            }
                        }
                    }
                }
                return null;
            }));
            start.countDown();
            awaitAll(futures);
        }

        assertArrayEquals(expected, copy.toDenseArray(), "the copy has to survive every mutation of its source");
        assertEquals(LightNibbles.MAX_LEVEL, source.get(0, 0, 0), "the source has to carry the mutation");
        assertEquals(0, copy.get(0, 0, 0), "the copy has to keep the value it was created with");
    }

    @Test
    void testACopyOfAUniformSectionStaysUniformWhileItsSourceGrowsAnArray() throws InterruptedException, ExecutionException {
        // A uniform section carries no array at all. Its copy has to stay uniform even though the
        // source allocates one the moment a differing level is written into it.
        LightNibbles source = LightNibbles.uniform(7);
        LightNibbles copy = source.copy();
        int readerCount = 8;
        int rounds = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch readersDone = new CountDownLatch(readerCount);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(readerCount + 1);

            for (int reader = 0; reader < readerCount; reader++) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    try {
                        for (int round = 0; round < rounds; round++) {
                            assertTrue(copy.isUniform(), "the copy of a uniform section must not grow an array");
                            assertEquals(7, copy.get(3, 4, 5), "the copy has to keep its uniform level");
                        }
                    } finally {
                        readersDone.countDown();
                    }
                    return null;
                }));
            }

            futures.add(executor.submit(() -> {
                awaitStart(start);

                while (readersDone.getCount() > 0) {
                    source.set(3, 4, 5, 1);
                    source.fill(7);
                }
                return null;
            }));
            start.countDown();
            awaitAll(futures);
        }

        assertTrue(copy.isUniform());
        assertEquals(7, copy.get(3, 4, 5));
    }

    /**
     * Builds the given amount of sections with a repeatable pseudo random content.
     * The seed is fixed so every run of the test works on the same fixtures.
     *
     * @param count the amount of sections to build
     * @return the created sections
     */
    private static List<SectionOpacity> sections(int count) {
        Random random = new Random(0x5EEDL);
        List<SectionOpacity> sections = new ArrayList<>(count);
        int[] palette = {AIR, AIR, AIR, AIR, STONE, STONE, SLAB, SLAB, LAMP};

        for (int section = 0; section < count; section++) {
            int[] states = new int[LightNibbles.BLOCK_COUNT];

            for (int index = 0; index < states.length; index++) {
                states[index] = palette[random.nextInt(palette.length)];
            }
            sections.add(SectionOpacity.of(states, SOURCE));
        }
        return sections;
    }

    /**
     * Converts the light of every section into its dense byte representation.
     *
     * @param light the light of every section
     * @return the dense bytes of every section
     */
    private static List<byte[]> dense(List<LightNibbles> light) {
        List<byte[]> arrays = new ArrayList<>(light.size());

        for (LightNibbles section : light) {
            arrays.add(section.toDenseArray());
        }
        return arrays;
    }

    /**
     * Compares the light of every section against the expected bytes.
     *
     * @param expected the expected bytes of every section
     * @param actual   the light which was calculated
     * @param label    the name of the pass which produced the light
     * @param round    the round the light was calculated in
     */
    private static void assertDense(List<byte[]> expected, List<LightNibbles> actual, String label, int round) {
        assertEquals(expected.size(), actual.size(), "the " + label + " lost a section in round " + round);

        for (int section = 0; section < expected.size(); section++) {
            assertArrayEquals(expected.get(section), actual.get(section).toDenseArray(), "the " + label + " of the section " + section + " drifted in round " + round);
        }
    }

    /**
     * Folds every answer of the given table into a single value.
     * A table which answers differently for a single position or face changes the result.
     *
     * @param opacity the table to read
     * @return the checksum of the table
     */
    private static long checksum(SectionOpacity opacity) {
        long value = 0L;

        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    value = value * 31L + opacity.emission(x, y, z);

                    for (BlockFace face : BlockFace.values()) {
                        value = value * 31L + (opacity.blocksFace(x, y, z, face) ? 1L : 0L);
                    }
                }
            }
        }
        return value;
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
