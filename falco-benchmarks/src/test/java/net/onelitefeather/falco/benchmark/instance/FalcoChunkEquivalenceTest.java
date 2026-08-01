package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.heightmap.Heightmap;
import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import net.onelitefeather.falco.benchmark.support.MinestomChunks.FillShape;
import net.onelitefeather.falco.instance.FalcoChunk;
import net.onelitefeather.falco.instance.FalcoInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down that the chunk of Falco is indistinguishable from the chunk of Minestom on every
 * operation {@link ChunkComparisonBenchmark} measures, so the control of that benchmark also falls
 * during an ordinary {@code ./gradlew check} instead of only during a JMH run.
 * <p>
 * This test was written while {@code FalcoChunk} still extended {@code DynamicChunk}, added no
 * field and overrode nothing but {@code copy} and two widened lifecycle hooks. The equality it
 * asserts was a structural consequence of that inheritance, and the test existed to catch the day
 * the structure changed. That day has come: since stage 1 of the block storage work
 * {@code FalcoChunk} extends {@code Chunk}, keeps its blocks behind a {@code BlockStorage} field
 * and overrides {@code setBlock}, {@code getBlock} and both heightmap accessors itself.
 * </p>
 * <p>
 * The change makes this file more important, not less. The equality is now a property of two
 * implementations that no longer share a line of code — {@code SectionBlockStorage} reproduces the
 * layout of {@code DynamicChunk} on purpose, but reproducing it is a claim somebody has to check
 * rather than something the compiler can enforce. Every comparison this module publishes rests on
 * that claim, so it is asserted here position by position and heightmap by heightmap instead of
 * being inferred from a class hierarchy that no longer exists.
 * </p>
 * <p>
 * The benchmark takes the same check in its {@code @Setup} and aborts the trial when it fails. That
 * is the right place for it but the wrong moment: a JMH run happens when somebody asks for numbers,
 * which can be weeks after the change that broke the equality, and it happens outside continuous
 * integration. A test brings the same failure forward to the commit that caused it.
 * </p>
 *
 * <h2>What this test covers that the benchmark cannot</h2>
 * <p>
 * Three things. It proves that the comparison can fail at all — a check that never rejects anything
 * proves nothing, so two deliberately different chunks are fed to the very same comparison and it
 * has to reject them. It proves that the two arms are still two different types, which is the one
 * defect that would make every other assertion pass while the whole comparison degenerated into a
 * type against itself. And it proves that the equality does not depend on the seed, by running one
 * shape a second time from an unrelated one.
 * </p>
 *
 * <h2>Why the operations are driven exactly as the benchmark drives them</h2>
 * <p>
 * The point of this test is not that two chunks can be filled identically — the fixture does that
 * and has its own guarantees. The point is that they stay identical through the four operations the
 * benchmark measures, in the order and with the locks the benchmark uses. A scattered write batch
 * that is faithful here but not there would leave the benchmark measuring an unverified state.
 * The batch size, the block rotation and the seed are therefore taken from the same constants, and
 * the scattered positions are drawn distinct for the same reason: a repeated position would let a
 * later write of the batch delete the block an earlier one placed, and the exact state count both
 * sides are checked against would stop being reachable.
 * </p>
 *
 * <h2>Why there is no test extension</h2>
 * <p>
 * The light module starts its server through {@code MicrotusExtension} because its fixtures need
 * nothing else. Here the server is only one of four things that have to be identical on both sides,
 * and the other three — the instances, the chunks and their content — come from
 * {@code MinestomChunks}. Letting the same fixture start the server keeps this test and the
 * benchmark on one code path instead of two, which is the only way the test can claim to guard what
 * the benchmark measures.
 * </p>
 *
 * <h2>Why a chunk with an empty top is tested separately</h2>
 * <p>
 * Because the fixtures above cannot reach the code that decides it. Every one of them fills the
 * chunk from its floor to its build limit, which leaves no section empty, and a scan for the highest
 * non-empty section on such a chunk stops at the first section it looks at whatever it does
 * afterwards. Since stage 2 {@code FalcoChunk} no longer starts its heightmap refresh from
 * {@code Heightmap#getHighestBlockSection(Chunk)} but from a copy of it that reads the storage
 * instead of {@code Chunk#getSection(int)}, and a copy is exactly the kind of code that has to be
 * run against its original on the inputs where the two could differ — a chunk whose top is empty,
 * which is every chunk of a real world.
 * {@link #testTheCopiedHighestSectionScanAgreesWithMinestom(String, int[], int)} supplies those
 * inputs, compares the two start heights on them, and rebuilds both heightmaps from the height each
 * arm computed for itself.
 * </p>
 * <p>
 * It rebuilds them through {@link #refreshHeightmaps(Chunk)} rather than through the chunk, and that
 * is a limitation of Minestom rather than a shortcut. {@code calculateFullHeightmap} is private on
 * both arms; the only public way to reach it is {@code Chunk#invalidate()} followed by a write, and
 * that path recomputes nothing, because it ends in {@code Heightmap#refresh(int)} whose first line
 * returns unless the heightmap's own {@code needsRefresh} is still set — a flag {@code invalidate()}
 * does not touch and nothing public can set again. A full recompute therefore happens exactly once
 * in the life of a chunk, on its first write, and a test that drove it that way would be asserting
 * against heightmaps that were never rebuilt. The per column {@code Heightmap#refresh(int, int, int)}
 * carries no such guard, which is why both this test and the benchmark reproduce the body instead of
 * triggering it.
 * </p>
 *
 * <h2>Running it</h2>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:test --tests "*FalcoChunkEquivalenceTest"
 * }</pre>
 *
 * @author TheMeinerLP
 * @version 1.2.0
 * @since 0.4.0
 */
class FalcoChunkEquivalenceTest {

    /**
     * The amounts of distinct block states a compared chunk is filled with.
     * The axis of {@link ChunkComparisonBenchmark}, repeated so the test covers every point the
     * benchmark will later report a number for.
     */
    private static final int[] DISTINCT_STATES = {1, 2, 16, 64, 256, 1024};

    /**
     * The amount of distinct positions the scattered batch touches.
     * The batch size of {@link ChunkComparisonBenchmark#SCATTER_COUNT}.
     */
    private static final int SCATTER_COUNT = ChunkComparisonBenchmark.SCATTER_COUNT;

    /**
     * The seed the compared chunks are built from, so a failure can be reproduced.
     * The seed of the module, which is the one the benchmark fills with as well.
     */
    private static final long SEED = BenchmarkConstants.SEED;

    /**
     * A second, unrelated seed, so the equality can be shown not to depend on the first one.
     */
    private static final long ALTERNATE_SEED = 20260731L;

    /**
     * The chunk position the compared chunks are created at.
     */
    private static final int CHUNK_POSITION = 0;

    /**
     * The floor of the world the chunks of this test live in.
     */
    private static final int OVERWORLD_MIN_Y = -64;

    /**
     * The build limit of the world the chunks of this test live in.
     */
    private static final int OVERWORLD_MAX_Y = 320;

    private InstanceContainer container;
    private FalcoInstance falco;

    /**
     * Builds the two instances the compared chunks come from.
     */
    @BeforeEach
    void createInstances() {
        MinestomChunks.ensureServer();
        this.container = MinestomChunks.newContainer();
        this.falco = MinestomChunks.newFalcoInstance();
    }

    /**
     * Releases the two instances so they do not leak into the following test.
     */
    @AfterEach
    void releaseInstances() {
        MinestomChunks.release(this.container);
        MinestomChunks.release(this.falco);
    }

    /**
     * Returns every combination of state count and arrangement the benchmark measures.
     *
     * @return the arguments of {@link #testBothChunksSurviveEveryOperationIdentically(int, FillShape)}
     */
    static Stream<Arguments> fixtures() {
        return Stream.of(FillShape.values())
                .flatMap(shape -> Arrays.stream(DISTINCT_STATES)
                        .mapToObj(states -> Arguments.of(states, shape)));
    }

    @ParameterizedTest(name = "{1} with {0} distinct states")
    @MethodSource("fixtures")
    void testBothChunksSurviveEveryOperationIdentically(int distinctStates, FillShape shape) {
        Chunk minestomChunk = MinestomChunks.newChunk(this.container, CHUNK_POSITION, CHUNK_POSITION);
        Chunk falcoChunk = MinestomChunks.newChunk(this.falco, CHUNK_POSITION, CHUNK_POSITION);

        // The arms have to be two different types before anything else is worth asserting; a
        // comparison of a type against itself would pass every check below without measuring one.
        // The two are siblings under Chunk since stage 1, so the first assertion already excludes a
        // FalcoChunk; the second stays because it names what is being excluded and why.
        assertInstanceOf(DynamicChunk.class, minestomChunk);
        assertFalse(minestomChunk instanceof FalcoChunk,
                "the container has to hand out a plain DynamicChunk, otherwise the two arms are one");
        assertInstanceOf(FalcoChunk.class, falcoChunk);

        MinestomChunks.fill(minestomChunk, distinctStates, shape, SEED);
        MinestomChunks.fill(falcoChunk, distinctStates, shape, SEED);
        MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);

        // A chunk of nothing but air answers every read from an empty palette and would compare
        // equal no matter what either type does. The fixture throws on it, so reaching this line
        // already proves the fill took; the count is the stronger statement that it took the
        // parameter as well.
        MinestomChunks.assertNotAllAir(minestomChunk);
        MinestomChunks.assertNotAllAir(falcoChunk);

        int[] scatterX = new int[SCATTER_COUNT];
        int[] scatterY = new int[SCATTER_COUNT];
        int[] scatterZ = new int[SCATTER_COUNT];
        Block[] scatterBlocks = new Block[SCATTER_COUNT];
        buildScatter(minestomChunk, distinctStates, scatterX, scatterY, scatterZ, scatterBlocks);

        writeScatter(minestomChunk, scatterX, scatterY, scatterZ, scatterBlocks);
        writeScatter(falcoChunk, scatterX, scatterY, scatterZ, scatterBlocks);
        MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);

        assertEquals(distinctStates, MinestomChunks.countDistinctStates(minestomChunk),
                "the Minestom chunk holds a different amount of distinct states than the axis asked for");
        assertEquals(distinctStates, MinestomChunks.countDistinctStates(falcoChunk),
                "the Falco chunk holds a different amount of distinct states than the axis asked for");
        assertEquals(MinestomChunks.countNonAir(minestomChunk), MinestomChunks.countNonAir(falcoChunk));

        assertEquals(readScatter(minestomChunk, scatterX, scatterY, scatterZ),
                readScatter(falcoChunk, scatterX, scatterY, scatterZ),
                "the scattered reads of the two chunks disagree");

        // Both heightmaps are thrown away and recomputed from the palettes, which is the operation
        // the benchmark measures and the one place where two chunks holding the same blocks could
        // still end up disagreeing, because a heightmap is derived state.
        assertEquals(refreshHeightmaps(minestomChunk), refreshHeightmaps(falcoChunk),
                "the refreshed heightmaps of the two chunks disagree");
        MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);

        Chunk minestomCopy = copy(minestomChunk, this.container);
        Chunk falcoCopy = copy(falcoChunk, this.falco);

        assertInstanceOf(FalcoChunk.class, falcoCopy,
                "a copy of a FalcoChunk has to stay a FalcoChunk, otherwise its instance can never unload it");
        assertFalse(minestomCopy instanceof FalcoChunk);

        // A copy that is faithful on both sides is what the copy arm of the benchmark assumes; a
        // copy that quietly dropped content would make that arm the fastest of the whole grid.
        MinestomChunks.assertSameBlocks(minestomChunk, minestomCopy);
        MinestomChunks.assertSameBlocks(falcoChunk, falcoCopy);
        MinestomChunks.assertSameBlocks(minestomCopy, falcoCopy);
    }

    @Test
    void testTheComparisonRejectsTwoChunksThatDiffer() {
        // Without this the whole test class could be passing because the comparison accepts
        // everything. Two chunks of the same shape and the same state count, filled from two
        // different seeds, have to be rejected by the very method every other case relies on.
        Chunk first = MinestomChunks.newChunk(this.container, CHUNK_POSITION, CHUNK_POSITION);
        Chunk second = MinestomChunks.newChunk(this.falco, CHUNK_POSITION, CHUNK_POSITION);

        MinestomChunks.fill(first, 16, FillShape.RANDOM_RUNS, SEED);
        MinestomChunks.fill(second, 16, FillShape.RANDOM_RUNS, ALTERNATE_SEED);

        assertThrows(IllegalStateException.class, () -> MinestomChunks.assertSameBlocks(first, second),
                "the comparison accepted two chunks built from different seeds, so it proves nothing");
    }

    /**
     * Returns the fixtures {@link #testTheCopiedHighestSectionScanAgreesWithMinestom(String, int[], int)}
     * runs, as a name, the world heights that hold a full layer of blocks, and the height the scan
     * has to start at.
     * <p>
     * Every expected start height is the arithmetic of {@code Heightmap#getHighestBlockSection}
     * written out by hand for that fixture rather than taken from a run: the build limit of the
     * overworld is {@value #OVERWORLD_MAX_Y}, and the scan subtracts one section of
     * {@code 16} for every section above the highest one holding a block. The floor layer leaves
     * {@code 23} sections empty above it and therefore starts at {@code -48}; the island at
     * {@code 200} sits in the section spanning {@code 192} to {@code 207} and leaves {@code 7},
     * therefore {@code 208}; a layer at the build limit leaves none. The untouched chunk is the
     * degenerate end where every section is empty and the scan walks the whole chunk down to its
     * floor.
     * </p>
     * <p>
     * The last two fixtures differ in nothing but a layer on the floor, far below the island. That
     * pair is what separates a scan that reports the highest non-empty section from one that reports
     * any non-empty section: an implementation walking upwards, or one breaking on the wrong end,
     * agrees with Minestom on every other fixture here and disagrees on that one.
     * </p>
     *
     * @return the arguments of {@link #testTheCopiedHighestSectionScanAgreesWithMinestom(String, int[], int)}
     */
    static Stream<Arguments> emptyTopFixtures() {
        return Stream.of(
                Arguments.of("an untouched chunk", new int[0], OVERWORLD_MIN_Y),
                Arguments.of("a layer on the floor", new int[]{-64}, -48),
                Arguments.of("a layer at the build limit", new int[]{319}, 320),
                Arguments.of("an island at 200 to 203", new int[]{200, 201, 202, 203}, 208),
                Arguments.of("an island at 200 to 203 over a floor", new int[]{-64, 200, 201, 202, 203}, 208)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyTopFixtures")
    void testTheCopiedHighestSectionScanAgreesWithMinestom(String name, int[] filledLayers, int expectedStartY) {
        Chunk minestomChunk = MinestomChunks.newChunk(this.container, CHUNK_POSITION, CHUNK_POSITION);
        Chunk falcoChunk = MinestomChunks.newChunk(this.falco, CHUNK_POSITION, CHUNK_POSITION);
        FalcoChunk typedFalcoChunk = assertInstanceOf(FalcoChunk.class, falcoChunk);

        // The expected start heights are literals derived from these two bounds. A dimension whose
        // extent changed would make every one of them wrong while the comparison of the two arms
        // still passed, so the bounds are asserted rather than assumed.
        assertEquals(OVERWORLD_MIN_Y, minestomChunk.getMinSection() * Chunk.CHUNK_SECTION_SIZE,
                "the fixture no longer starts at the floor the expected start heights were computed for");
        assertEquals(OVERWORLD_MAX_Y, minestomChunk.getMaxSection() * Chunk.CHUNK_SECTION_SIZE,
                "the fixture no longer ends at the build limit the expected start heights were computed for");

        final Block block = MinestomChunks.distinctBlocks(1)[0];
        fillLayers(minestomChunk, filledLayers, block);
        fillLayers(falcoChunk, filledLayers, block);

        // Both start heights are taken before the block walk, because the walk reaches the sections
        // of the Falco chunk through Chunk#getSections() and creates every one of them. The scan
        // under test runs on the storage as the chunk keeps it, which for the empty part of these
        // fixtures is a shared section rather than one of its own.
        minestomChunk.lockReadLock();
        try {
            assertEquals(expectedStartY, Heightmap.getHighestBlockSection(minestomChunk),
                    "Minestom does not start its scan where the fixture says it has to");
        } finally {
            minestomChunk.unlockReadLock();
        }
        falcoChunk.lockReadLock();
        try {
            assertEquals(expectedStartY, typedFalcoChunk.highestBlockSection(),
                    "the copied scan of FalcoChunk starts at a different height than Minestom's");
        } finally {
            falcoChunk.unlockReadLock();
        }
        MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);

        // The scan is compared above and used here. Both chunks rebuild their heightmaps column by
        // column from the start height their own arm computed, which is what turns a wrong start
        // height into a wrong heightmap rather than only into a wrong number: a scan beginning below
        // the top of the fixture cannot see it and reports the floor for every column.
        assertEquals(refreshHeightmaps(minestomChunk), refreshHeightmaps(falcoChunk),
                "the heightmaps rebuilt from the two start heights disagree");
        MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);

        // Equality alone would also hold if both chunks had missed the island and reported their
        // floor, so the height itself is pinned. The world surface heightmap is the one asked,
        // because its predicate is "not air" and therefore says nothing about which block the
        // fixture happened to draw; an empty column reports the minimum height of a heightmap,
        // which Minestom defines as one below the floor of the world.
        final int expectedSurface = filledLayers.length == 0
                ? OVERWORLD_MIN_Y - 1
                : Arrays.stream(filledLayers).max().orElseThrow();
        assertEquals(expectedSurface, minestomChunk.worldSurfaceHeightmap().getHeight(0, 0),
                "the refreshed Minestom heightmap did not find the top of the fixture");
        assertEquals(expectedSurface, falcoChunk.worldSurfaceHeightmap().getHeight(0, 0),
                "the refreshed Falco heightmap did not find the top of the fixture");
    }

    @Test
    void testTheEqualityDoesNotDependOnTheSeed() {
        Chunk minestomChunk = MinestomChunks.newChunk(this.container, CHUNK_POSITION, CHUNK_POSITION);
        Chunk falcoChunk = MinestomChunks.newChunk(this.falco, CHUNK_POSITION, CHUNK_POSITION);

        MinestomChunks.fill(minestomChunk, 64, FillShape.RANDOM_RUNS, ALTERNATE_SEED);
        MinestomChunks.fill(falcoChunk, 64, FillShape.RANDOM_RUNS, ALTERNATE_SEED);

        MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);
        assertEquals(64, MinestomChunks.countDistinctStates(minestomChunk));
    }

    @Test
    void testAChunkThatStayedAirIsRejected() {
        // The anti tautology check of the whole module. An empty chunk holds a palette with no
        // backing array, so every measurement on it collapses to object headers and every
        // comparison on it succeeds. It has to be refused rather than measured.
        Chunk untouched = MinestomChunks.newChunk(this.container, CHUNK_POSITION, CHUNK_POSITION);

        assertEquals(0, MinestomChunks.countNonAir(untouched));
        assertThrows(IllegalStateException.class, () -> MinestomChunks.assertNotAllAir(untouched));

        // Two empty chunks compare equal, which is the trap: the comparison is not what protects
        // this module against an empty fixture, the air check is.
        Chunk otherUntouched = MinestomChunks.newChunk(this.falco, CHUNK_POSITION, CHUNK_POSITION);
        MinestomChunks.assertSameBlocks(untouched, otherUntouched);
    }

    @Test
    void testAFillWithoutAnyStateIsRefused() {
        Chunk chunk = MinestomChunks.newChunk(this.container, CHUNK_POSITION, CHUNK_POSITION);

        assertThrows(IllegalArgumentException.class,
                () -> MinestomChunks.fill(chunk, 0, FillShape.UNIFORM, SEED));
        assertThrows(IllegalArgumentException.class,
                () -> MinestomChunks.fill(chunk, MinestomChunks.blockCount(chunk) + 1, FillShape.UNIFORM, SEED));
    }

    @Test
    void testTheRegistryStillCarriesTheLargestStateCount() {
        // The largest point of the axis is above the amount of distinct blocks the pinned build
        // offers, so the fixture falls back to further states of the same blocks there. That
        // fallback is a documented property of the measurement, not an accident, and a Minestom
        // bump that removed it or widened it should be noticed here rather than in a curve.
        assertTrue(MinestomChunks.availableBlocks() > 0);
        assertTrue(MinestomChunks.availableStates() >= DISTINCT_STATES[DISTINCT_STATES.length - 1],
                "the block registry no longer holds enough states for the largest point of the axis");
        assertEquals(DISTINCT_STATES[DISTINCT_STATES.length - 1],
                MinestomChunks.distinctBlocks(DISTINCT_STATES[DISTINCT_STATES.length - 1]).length);
    }

    /**
     * Draws the distinct positions and the blocks of the scattered batch.
     *
     * @param chunk          the chunk the positions are drawn inside of
     * @param distinctStates the amount of distinct block states the batch draws from
     * @param scatterX       the block X of every position, filled by this method
     * @param scatterY       the block Y of every position, filled by this method
     * @param scatterZ       the block Z of every position, filled by this method
     * @param scatterBlocks  the block of every position, filled by this method
     */
    private static void buildScatter(Chunk chunk, int distinctStates, int[] scatterX, int[] scatterY,
                                     int[] scatterZ, Block[] scatterBlocks) {
        final int minY = chunk.getMinSection() * Chunk.CHUNK_SECTION_SIZE;
        final int height = (chunk.getMaxSection() - chunk.getMinSection()) * Chunk.CHUNK_SECTION_SIZE;
        final Block[] blocks = MinestomChunks.distinctBlocks(distinctStates);
        final Random random = new Random(SEED);
        final BitSet taken = new BitSet(Chunk.CHUNK_SIZE_X * Chunk.CHUNK_SIZE_Z * height);

        for (int index = 0; index < SCATTER_COUNT; index++) {
            int x;
            int y;
            int z;
            int packed;

            do {
                x = random.nextInt(Chunk.CHUNK_SIZE_X);
                y = random.nextInt(height);
                z = random.nextInt(Chunk.CHUNK_SIZE_Z);
                packed = (y * Chunk.CHUNK_SIZE_Z + z) * Chunk.CHUNK_SIZE_X + x;
            } while (taken.get(packed));

            taken.set(packed);
            scatterX[index] = x;
            scatterY[index] = minY + y;
            scatterZ[index] = z;
            scatterBlocks[index] = blocks[index % blocks.length];
        }
    }

    /**
     * Writes a full horizontal layer of one block into a chunk, for every height it is given.
     * <p>
     * A whole layer rather than a single block, because the scan under test asks a palette for its
     * count and a section that holds one block answers the same as one that holds two hundred and
     * fifty six. The layer is what makes the resulting heightmap worth comparing: it gives every
     * column of the chunk the same top, so a column that came out wrong is a column the scan missed
     * rather than a column the fixture never filled.
     * </p>
     *
     * @param chunk  the chunk to write into
     * @param layers the world heights that receive a full layer
     * @param block  the block every layer is written with
     */
    private static void fillLayers(Chunk chunk, int[] layers, Block block) {
        chunk.lockWriteLock();
        try {
            for (int y : layers) {
                for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                    for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                        chunk.setBlock(x, y, z, block);
                    }
                }
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Writes the scattered batch into a chunk, with the write lock the setter demands.
     *
     * @param chunk         the chunk to write into
     * @param scatterX      the block X of every position
     * @param scatterY      the block Y of every position
     * @param scatterZ      the block Z of every position
     * @param scatterBlocks the block of every position
     */
    private static void writeScatter(Chunk chunk, int[] scatterX, int[] scatterY, int[] scatterZ,
                                     Block[] scatterBlocks) {
        chunk.lockWriteLock();
        try {
            for (int index = 0; index < SCATTER_COUNT; index++) {
                chunk.setBlock(scatterX[index], scatterY[index], scatterZ[index], scatterBlocks[index]);
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Reads the scattered batch from a chunk and sums the state ids it finds.
     *
     * @param chunk    the chunk to read from
     * @param scatterX the block X of every position
     * @param scatterY the block Y of every position
     * @param scatterZ the block Z of every position
     * @return the sum of the read state ids
     */
    private static int readScatter(Chunk chunk, int[] scatterX, int[] scatterY, int[] scatterZ) {
        int sum = 0;

        chunk.lockReadLock();
        try {
            for (int index = 0; index < SCATTER_COUNT; index++) {
                final Block block = chunk.getBlock(scatterX[index], scatterY[index], scatterZ[index],
                        Block.Getter.Condition.NONE);
                sum += Objects.requireNonNullElse(block, Block.AIR).stateId();
            }
        } finally {
            chunk.unlockReadLock();
        }
        return sum;
    }

    /**
     * Recomputes both heightmaps of a chunk from its palettes and sums the heights that come out.
     * <p>
     * The same reproduction of the private {@code calculateFullHeightmap} of the chunk it is handed
     * that the benchmark measures, through the per column {@code Heightmap#refresh(int, int, int)}
     * which carries no refresh guard and therefore performs the scan every time it is called.
     * </p>
     * <p>
     * The start of the scan is taken from the arm's own chunk, exactly as the benchmark takes it:
     * {@code DynamicChunk} starts from {@code Heightmap#getHighestBlockSection(Chunk)},
     * {@code FalcoChunk} from {@link FalcoChunk#highestBlockSection()}. Running the static helper on
     * both arms would compare Minestom's scan against itself and would leave the copy inside
     * {@code FalcoChunk} untested by every fixture of this class.
     * </p>
     *
     * @param chunk the chunk to refresh the heightmaps of
     * @return the sum of the refreshed heights of both heightmaps
     */
    private static int refreshHeightmaps(Chunk chunk) {
        int sum = 0;

        chunk.lockWriteLock();
        try {
            final int startY = chunk instanceof FalcoChunk falcoChunk
                    ? falcoChunk.highestBlockSection()
                    : Heightmap.getHighestBlockSection(chunk);
            final Heightmap motionBlocking = chunk.motionBlockingHeightmap();
            final Heightmap worldSurface = chunk.worldSurfaceHeightmap();

            for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                    motionBlocking.refresh(x, z, startY);
                    worldSurface.refresh(x, z, startY);
                }
            }
            for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                    sum += motionBlocking.getHeight(x, z) + worldSurface.getHeight(x, z);
                }
            }
        } finally {
            chunk.unlockWriteLock();
        }
        return sum;
    }

    /**
     * Copies a chunk to a neighbouring position, with the read lock the copy demands.
     *
     * @param chunk    the chunk to copy
     * @param instance the instance the copy is created for
     * @return the created copy
     */
    private static Chunk copy(Chunk chunk, Instance instance) {
        chunk.lockReadLock();
        try {
            return chunk.copy(instance, CHUNK_POSITION + 1, CHUNK_POSITION);
        } finally {
            chunk.unlockReadLock();
        }
    }
}
