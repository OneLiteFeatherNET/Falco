package net.onelitefeather.falco.benchmark.support;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.heightmap.Heightmap;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.instance.FalcoInstance;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * The {@link MinestomChunks} class builds the world every chunk benchmark of this module measures on,
 * and it is the single place that starts a Minestom server to do so.
 * <p>
 * Seven benchmarks compare a chunk of Minestom against a chunk of Falco. All seven need the same four
 * things: a running server process, because a chunk cannot be constructed without a dimension and a
 * block registry; two instances whose configuration differs in nothing but the type under test; a
 * chunk content that is reproducible to the block; and a proof that the two sides really do hold the
 * same content before a single number is taken. Every one of those four is a place where a comparison
 * quietly stops comparing, so all four live here rather than in seven copies.
 * </p>
 *
 * <h2>Why the server start is centralised</h2>
 * <p>
 * The block {@code if (MinecraftServer.process() == null) MinecraftServer.init();} sits copied in four
 * benchmark classes of this module today. It is not thread safe: two {@code @Setup} methods can both
 * read {@code null} and both call {@code init()}, and the second call replaces the {@code ServerProcess}
 * of the first, which quietly detaches every instance and every registry the first one handed out. JMH
 * runs a trial setup per worker thread, so the case is reachable rather than theoretical.
 * {@link #ensureServer()} moves the decision into a class initialiser, where the JVM guarantees that
 * the body runs exactly once and that every other thread waits for it, and hands the process back so a
 * caller can assert on the one it got.
 * </p>
 *
 * <h2>Why the two instances are built side by side</h2>
 * <p>
 * {@link #newContainer()} and {@link #newFalcoInstance()} differ in one line: the class they construct.
 * Same dimension, same loader, same generator, same auto chunk load, same registration with the
 * {@link InstanceManager}. The chunk supplier is left at the default of each type, because that default
 * is the subject of the comparison: {@code DynamicChunk} against {@code FalcoChunk}. Everything else is
 * held equal on purpose — an instance that carries a loader on one side and not on the other produces
 * two chunks whose difference has nothing to do with the type being measured.
 * </p>
 *
 * <h2>Why the fill has a shape</h2>
 * <p>
 * A palette is a compressor. What it costs, in bytes and in time, is decided by two properties of its
 * input: how many distinct values it holds, and how those values are arranged in space. Only the first
 * of the two is usually treated as a parameter, and a benchmark that varies it while drawing every
 * block independently at random measures an input no world has ever produced. Pure per-block randomness
 * gives every block a different neighbour, which is the worst case for run length, for the bit packer
 * and for anything that exploits locality — a change that wins on real terrain would be reported as
 * worthless, and a change that wins here would be reported as a win it never delivers.
 * </p>
 * <p>
 * {@link FillShape} therefore separates the two properties. The state count stays a parameter, and the
 * arrangement becomes a second one with three settings that bracket reality rather than sample it:
 * {@link FillShape#UNIFORM} is the adversarial floor with no spatial structure at all,
 * {@link FillShape#LAYERED} is the frictionless ceiling of perfectly stratified ground, and
 * {@link FillShape#RANDOM_RUNS} sits between them. A result that holds at both ends holds for the
 * worlds in between; a result that only holds at one end has to say which.
 * </p>
 * <p>
 * {@link FillShape#RANDOM_RUNS} is the shape that models real terrain, and it does so because terrain
 * is autocorrelated: a stone block is overwhelmingly likely to be next to another stone block, and the
 * material changes at strata and at cave walls, not at every step. Writing runs of one block along the
 * axis the storage is laid out on reproduces exactly that autocorrelation while keeping the number of
 * distinct states under the control of the caller, which is what makes it comparable to the other two
 * shapes at the same state count.
 * </p>
 *
 * <h2>Why the fixture refuses to hand out air</h2>
 * <p>
 * The most common silent failure in this class of measurement is a benchmark that ends up measuring an
 * empty chunk. An empty palette in Minestom has {@code bitsPerEntry == 0} and no backing array at all,
 * so every access degenerates into returning a single field, every save writes nothing and every
 * footprint collapses to object headers. The numbers look excellent and describe nothing. A fill that
 * silently did not take — wrong Y range, a chunk from a dimension with a different section count, a
 * shape that wrote outside the bounds — produces precisely that, and produces it without an error.
 * {@link #fill(Chunk, int, FillShape)} therefore ends in {@link #assertNotAllAir(Chunk)} and throws
 * rather than returns.
 * </p>
 *
 * <h2>Why equality is proved before the first measurement</h2>
 * <p>
 * This module holds a comparison worthless unless it first shows that both sides produce the same
 * result, the pattern {@code LightEngineComparisonBenchmark#verifyBothEnginesAgree} establishes.
 * {@link #assertSameBlocks(Chunk, Chunk)} is that step for chunks: it walks all
 * {@code 16 * 16 * 16 * sectionCount} positions plus both heightmaps and throws with the first
 * disagreeing position, which aborts the trial instead of publishing a faster number for a different
 * task.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * Nothing in this class is measured; it only builds input. It appears in two kinds of run. The JMH
 * benchmarks that use it need a server, so they run under the raised heap the convention prescribes
 * for that case:
 * </p>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:jmhJar
 * java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar "Chunk.*Benchmark" \
 *     -f 1 -wi 5 -i 5 -prof gc -jvmArgs "-Xms2g -Xmx2g"
 * }</pre>
 * <p>
 * The JOL footprint tests that use it run as ordinary tests, where the build already passes the two
 * flags JOL needs:
 * </p>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest"
 * ./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest" -Pfalco.compactHeaders
 * }</pre>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
public final class MinestomChunks {

    /**
     * The dimension both instances are created in.
     * <p>
     * The overworld is the only dimension whose section count matches the
     * {@link BenchmarkConstants#OVERWORLD_SECTIONS} the estimates of this module are written against,
     * and it is the dimension the numbers are meant to describe.
     * </p>
     */
    public static final RegistryKey<DimensionType> DIMENSION = DimensionType.OVERWORLD;

    /**
     * The shortest run {@link FillShape#RANDOM_RUNS} writes.
     */
    public static final int RUN_LENGTH_MIN = 1;

    /**
     * The longest run {@link FillShape#RANDOM_RUNS} writes.
     * <p>
     * One row of a section along the X axis. A longer maximum would let a single draw cover several
     * rows and turn the shape into a coarser {@link FillShape#LAYERED}, which is already measured
     * separately; a shorter one would never produce a homogeneous row, which real ground does all the
     * time.
     * </p>
     */
    public static final int RUN_LENGTH_MAX = Chunk.CHUNK_SIZE_X;

    /**
     * The amount of blocks a single section holds along one horizontal axis.
     */
    private static final int SECTION_SIZE = Chunk.CHUNK_SECTION_SIZE;

    /**
     * The arrangement of the block states a fill spreads over a chunk.
     * <p>
     * All three shapes draw from the same set of states, which
     * {@link MinestomChunks#distinctBlocks(int)} supplies, and all three write through
     * {@link Chunk#setBlock(int, int, int, Block)} in the same order. They differ in nothing but where
     * a state ends up, which is what makes a pair of runs at the same state count a measurement of the
     * arrangement alone.
     * </p>
     */
    public enum FillShape {

        /**
         * The states are spread evenly over the chunk, one state per block, cycling in storage order.
         * <p>
         * The name says uniformly distributed, not homogeneous: with {@code distinctStates == 1} every
         * block is the same, and with more than one no two neighbours along the X axis are. This is the
         * adversarial floor. There is no run longer than a single block, the palette reaches its full
         * size in every section, and nothing that exploits spatial locality can win here. Every state
         * of the set is guaranteed to appear as long as the set is smaller than the chunk.
         * </p>
         */
        UNIFORM,

        /**
         * Every horizontal layer of the chunk holds a single state, cycling from the bottom upwards.
         * <p>
         * This is the frictionless ceiling and the shape sedimentary ground actually has: bedrock,
         * deepslate, stone, dirt, grass. Each layer is one uninterrupted run of {@code 256} blocks, and
         * a section sees at most {@code 16} of the states no matter how large the set is. A benchmark
         * that only ran this shape would overstate every optimisation that depends on homogeneity,
         * which is why it is never run alone.
         * </p>
         */
        LAYERED,

        /**
         * The chunk is written as a sequence of runs of one state, of a seeded random length between
         * {@link MinestomChunks#RUN_LENGTH_MIN} and {@link MinestomChunks#RUN_LENGTH_MAX}.
         * <p>
         * This is the shape closest to real terrain, and the reason is autocorrelation rather than
         * randomness. Ground changes material at strata and at cave walls, not at every block, so the
         * dominant property of a real section is that a block equals its neighbour. Pure per-block
         * randomness destroys exactly that property and produces an input distribution that no world
         * generator has ever emitted, while still looking like a fair test.
         * </p>
         * <p>
         * The first runs are primed with the states of the set in order, so a chunk filled with this
         * shape holds every state that was asked for rather than however many the draw happened to hit.
         * Without that priming the state count would stop being a controlled axis and start being a
         * random variable, and two shapes at the nominal same count would no longer be comparable.
         * </p>
         */
        RANDOM_RUNS
    }

    /**
     * Blocks the creation of an instance because the class only builds fixtures.
     */
    private MinestomChunks() {
    }

    /**
     * Starts the Minestom server process once and returns it.
     * <p>
     * Idempotent and safe to call from several threads at once. The work sits in the initialiser of a
     * holder class, so the JVM performs it exactly once under its own class initialisation lock and
     * every further caller reads a finished field. An existing process is adopted rather than replaced,
     * so a benchmark that starts the server itself before reaching this method still gets the process
     * it created.
     * </p>
     *
     * @return the running server process
     */
    public static ServerProcess ensureServer() {
        return Server.PROCESS;
    }

    /**
     * Creates and registers an {@link InstanceContainer} with the configuration of this fixture.
     *
     * @return the created container
     */
    public static InstanceContainer newContainer() {
        return newContainer(ChunkLoader.noop());
    }

    /**
     * Creates and registers an {@link InstanceContainer} with the configuration of this fixture.
     * <p>
     * The container is built with the explicit five argument constructor rather than through
     * {@link InstanceManager#createInstanceContainer()} so that it takes the same arguments in the same
     * order as {@link #newFalcoInstance(ChunkLoader)}, and so that the loader is stated on both sides
     * rather than defaulted on one of them.
     * </p>
     *
     * @param loader the loader chunks are read from and written to
     * @return the created container
     */
    public static InstanceContainer newContainer(ChunkLoader loader) {
        final ServerProcess process = ensureServer();
        final InstanceContainer container = new InstanceContainer(
                process, UUID.randomUUID(), DIMENSION, loader, DIMENSION.key());
        container.enableAutoChunkLoad(true);
        process.instance().registerInstance(container);
        return container;
    }

    /**
     * Creates and registers a {@link FalcoInstance} with the configuration of this fixture.
     *
     * @return the created instance
     */
    public static FalcoInstance newFalcoInstance() {
        return newFalcoInstance(ChunkLoader.noop());
    }

    /**
     * Creates and registers a {@link FalcoInstance} with the configuration of this fixture.
     * <p>
     * Every argument matches {@link #newContainer(ChunkLoader)}. What is deliberately not matched is
     * the chunk supplier: both types keep their own default, because the difference between those two
     * defaults is the thing under measurement.
     * </p>
     *
     * @param loader the loader chunks are read from and written to
     * @return the created instance
     */
    public static FalcoInstance newFalcoInstance(ChunkLoader loader) {
        final ServerProcess process = ensureServer();
        final FalcoInstance instance = new FalcoInstance(
                process, UUID.randomUUID(), DIMENSION, loader, DIMENSION.key());
        instance.enableAutoChunkLoad(true);
        process.instance().registerInstance(instance);
        return instance;
    }

    /**
     * Unregisters an instance this fixture created and unloads the chunks it holds.
     * <p>
     * A trial that leaves its instances registered leaks them into every following trial of the same
     * fork, because the {@link InstanceManager} keeps them alive and their chunks with them. That turns
     * a footprint measurement into a measurement of how many trials ran before it.
     * {@code InstanceManager#unregisterInstance} only unloads chunks for an {@link InstanceContainer},
     * so a {@link FalcoInstance} is released through its own {@code unregister} instead, which is the
     * method that exists for exactly this gap.
     * </p>
     *
     * @param instance the instance to release, null is ignored
     */
    public static void release(@Nullable Instance instance) {
        if (instance == null) {
            return;
        }
        final InstanceManager manager = ensureServer().instance();

        if (instance instanceof FalcoInstance falcoInstance) {
            falcoInstance.unregister(manager);
            return;
        }
        if (instance.isRegistered()) {
            manager.unregisterInstance(instance);
        }
    }

    /**
     * Creates a chunk through the supplier of an instance without handing it to that instance.
     * <p>
     * This is the chunk a footprint measurement wants: it exists, it is complete, and nothing else
     * holds a reference to it, so the retained size JOL reports belongs to the chunk rather than to the
     * world around it. A chunk obtained through {@link #loadChunk(Instance, int, int)} is reachable
     * from the chunk map, the tick dispatcher and the entity tracker, and measuring it measures those
     * too.
     * </p>
     *
     * @param instance the instance the chunk is built for
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @return the created chunk, not registered with the instance
     */
    public static Chunk newChunk(Instance instance, int chunkX, int chunkZ) {
        return instance.getChunkSupplier().createChunk(instance, chunkX, chunkZ);
    }

    /**
     * Loads a chunk into an instance and waits for it.
     *
     * @param instance the instance to load the chunk into
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @return the loaded chunk
     */
    public static Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        return instance.loadChunk(chunkX, chunkZ).join();
    }

    /**
     * Returns how many distinct usable blocks the registry holds.
     * <p>
     * The number a benchmark has to compare its state count against before it claims that its input
     * held that many different <em>blocks</em>. Above it, {@link #distinctBlocks(int)} keeps delivering
     * distinct state ids but stops delivering distinct blocks, and the difference matters enough to be
     * readable at runtime rather than only in this javadoc. On the pinned Minestom build the number is
     * {@code 964}, which is below the {@code 1024} the largest planned state count asks for.
     * </p>
     *
     * @return the amount of registered blocks which are neither air nor a block entity
     */
    public static int availableBlocks() {
        return BlockSet.BLOCKS.length;
    }

    /**
     * Returns how many distinct block states this fixture can draw from in total.
     *
     * @return the amount of usable block states, distinct blocks and their further states together
     */
    public static int availableStates() {
        return BlockSet.BLOCKS.length + ExtraStates.STATES.length;
    }

    /**
     * Returns the requested amount of distinct block states, distinct blocks first.
     * <p>
     * Distinct <em>blocks</em> before distinct states of one block, and that order is the point of the
     * method. A palette does not care which state ids it holds, but everything around it does: taking
     * two hundred states of stone would give two hundred ids sitting next to each other in the
     * registry, all with the same handler, the same occlusion and the same behaviour in every heightmap
     * and light computation. That is neither what a world looks like nor what the code paths around the
     * palette meet in production, and a palette benchmark fed that way would report on an input whose
     * only realistic property is its cardinality.
     * </p>
     * <p>
     * Two kinds of block are excluded. Air is excluded because a fixture whose job is to prevent an
     * accidentally empty chunk must not put emptiness into the set in the first place. Block entities
     * are excluded because Minestom does not keep them in the palette at all: {@code DynamicChunk}
     * stores them in a side map keyed by block index, so a chunk filled with chests would measure a
     * hash map with {@code 98304} entries instead of the storage under test, and would do so while
     * looking like a legitimate fill.
     * </p>
     * <p>
     * Those two exclusions leave fewer distinct blocks than the largest planned state count asks for —
     * {@code 964} against {@code 1024} on the pinned build. Rather than cap the axis, the set falls
     * back to the remaining states of the same blocks once the distinct ones run out, again by
     * ascending state id. The fallback is stated here and readable through {@link #availableBlocks()}
     * rather than hidden, because a measurement above that boundary is answering a slightly different
     * question than one below it, and the two halves of such a curve must not be read as one.
     * </p>
     *
     * @param wanted the amount of distinct block states to return
     * @return the blocks, in a stable order across runs
     * @throws IllegalArgumentException if {@code wanted} is smaller than one
     * @throws IllegalStateException    if the registry holds fewer usable states than requested
     */
    public static Block[] distinctBlocks(int wanted) {
        if (wanted < 1) {
            throw new IllegalArgumentException("A fill needs at least one block state, got " + wanted);
        }
        final Block[] blocks = BlockSet.BLOCKS;

        if (wanted <= blocks.length) {
            final Block[] result = new Block[wanted];
            System.arraycopy(blocks, 0, result, 0, wanted);
            return result;
        }
        final Block[] extras = ExtraStates.STATES;
        final int missing = wanted - blocks.length;

        if (missing > extras.length) {
            throw new IllegalStateException("The block registry holds " + blocks.length + " blocks and "
                    + extras.length + " further states which are neither air nor a block entity, so "
                    + wanted + " distinct states cannot be built");
        }
        final Block[] result = new Block[wanted];
        System.arraycopy(blocks, 0, result, 0, blocks.length);
        System.arraycopy(extras, 0, result, blocks.length, missing);
        return result;
    }

    /**
     * Returns the state ids of {@link #distinctBlocks(int)}.
     * <p>
     * For the benchmarks that write into a {@code Palette} directly rather than through a chunk.
     * </p>
     *
     * @param wanted the amount of distinct states to return
     * @return the state ids, in a stable order across runs
     * @throws IllegalArgumentException if {@code wanted} is smaller than one
     * @throws IllegalStateException    if the registry holds fewer usable blocks than requested
     */
    public static int[] distinctStates(int wanted) {
        final Block[] blocks = distinctBlocks(wanted);
        final int[] states = new int[blocks.length];

        for (int index = 0; index < blocks.length; index++) {
            states[index] = blocks[index].stateId();
        }
        return states;
    }

    /**
     * Fills a chunk with the given amount of distinct block states in the given arrangement, using the
     * seed of this module.
     *
     * @param chunk          the chunk to fill
     * @param distinctStates the amount of distinct block states the fill draws from
     * @param shape          the arrangement the states are written in
     * @throws IllegalArgumentException if the state count does not fit the chunk
     * @throws IllegalStateException    if the chunk holds nothing but air afterwards
     */
    public static void fill(Chunk chunk, int distinctStates, FillShape shape) {
        fill(chunk, distinctStates, shape, BenchmarkConstants.SEED);
    }

    /**
     * Fills a chunk with the given amount of distinct block states in the given arrangement.
     * <p>
     * The write order is Y outermost and X innermost, ascending, and that is not an implementation
     * detail. Ascending Y is what keeps the heightmap refresh on its cheap branch: a block that raises
     * the column only compares and stores, while a block written below the current height sends the
     * heightmap back down the column with a palette scan per step. A fill that ran top down would spend
     * most of its time in a code path that a real world reaches when a player mines, not when a chunk
     * is generated, and would make the setup of the benchmark dominate the benchmark.
     * </p>
     * <p>
     * The fill goes through {@link Chunk#setBlock(int, int, int, Block)} rather than writing into the
     * palettes of the sections behind it. Writing into the palettes is faster and is what some existing
     * benchmarks of this module do, but it leaves the heightmaps, the block entity map and the packet
     * cache in a state no sequence of public calls could produce, and it assumes that every chunk under
     * test stores its blocks in Minestom sections at all — which is exactly the assumption a Falco
     * chunk with its own storage would break. The public setter is the only path both sides are
     * guaranteed to share.
     * </p>
     *
     * @param chunk          the chunk to fill
     * @param distinctStates the amount of distinct block states the fill draws from
     * @param shape          the arrangement the states are written in
     * @param seed           the seed {@link FillShape#RANDOM_RUNS} draws its runs from
     * @throws IllegalArgumentException if the state count does not fit the chunk
     * @throws IllegalStateException    if the chunk holds nothing but air afterwards
     */
    public static void fill(Chunk chunk, int distinctStates, FillShape shape, long seed) {
        final int minY = chunk.getMinSection() * SECTION_SIZE;
        final int maxY = chunk.getMaxSection() * SECTION_SIZE;
        final int positions = blockCount(chunk);

        if (distinctStates > positions) {
            throw new IllegalArgumentException("The chunk holds " + positions + " blocks and cannot show "
                    + distinctStates + " distinct states");
        }
        final Block[] blocks = distinctBlocks(distinctStates);

        chunk.lockWriteLock();
        try {
            switch (shape) {
                case UNIFORM -> fillUniform(chunk, blocks, minY, maxY);
                case LAYERED -> fillLayered(chunk, blocks, minY, maxY);
                case RANDOM_RUNS -> fillRandomRuns(chunk, blocks, minY, maxY, seed);
            }
        } finally {
            chunk.unlockWriteLock();
        }
        assertNotAllAir(chunk);
    }

    /**
     * Returns the amount of block positions a chunk holds.
     *
     * @param chunk the chunk to measure
     * @return {@code 16 * 16 * 16} times the section count of the chunk
     */
    public static int blockCount(Chunk chunk) {
        return BenchmarkConstants.BLOCK_ENTRIES * (chunk.getMaxSection() - chunk.getMinSection());
    }

    /**
     * Verifies that a chunk holds at least one block which is not air.
     * <p>
     * The check that stops the most common silent failure of this module. An all air chunk answers
     * every read from a palette with {@code bitsPerEntry == 0} and no backing array, saves to almost
     * nothing and weighs almost nothing, so a benchmark that lost its fill reports the best numbers it
     * will ever produce and reports them for an empty world. The check stops at the first block it
     * finds, so it costs a single read on a chunk that is fine.
     * </p>
     *
     * @param chunk the chunk to check
     * @throws IllegalStateException if every block of the chunk is air
     */
    public static void assertNotAllAir(Chunk chunk) {
        final int minY = chunk.getMinSection() * SECTION_SIZE;
        final int maxY = chunk.getMaxSection() * SECTION_SIZE;

        chunk.lockReadLock();
        try {
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                    for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                        if (!typeAt(chunk, x, y, z).isAir()) {
                            return;
                        }
                    }
                }
            }
        } finally {
            chunk.unlockReadLock();
        }
        throw new IllegalStateException("The chunk " + chunk.getChunkX() + ":" + chunk.getChunkZ()
                + " holds nothing but air over all " + blockCount(chunk)
                + " positions, so a measurement on it would measure an empty palette");
    }

    /**
     * Counts the blocks of a chunk which are not air.
     *
     * @param chunk the chunk to count in
     * @return the amount of non air blocks
     */
    public static int countNonAir(Chunk chunk) {
        final int minY = chunk.getMinSection() * SECTION_SIZE;
        final int maxY = chunk.getMaxSection() * SECTION_SIZE;
        int found = 0;

        chunk.lockReadLock();
        try {
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                    for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                        if (!typeAt(chunk, x, y, z).isAir()) {
                            found++;
                        }
                    }
                }
            }
        } finally {
            chunk.unlockReadLock();
        }
        return found;
    }

    /**
     * Counts how many distinct block states a chunk actually holds.
     * <p>
     * The second half of the anti tautology check, and the one a parametrised benchmark needs: the
     * state count is an axis of the measurement, so a run has to be able to show that the axis was
     * really set. {@link FillShape#LAYERED} in particular cannot reach more than {@code 16} states per
     * section, and a benchmark that reports a curve over {@code 1024} states while every point of it
     * held {@code 384} would be describing a chunk it never built.
     * </p>
     *
     * @param chunk the chunk to count in
     * @return the amount of distinct block state ids, air included
     */
    public static int countDistinctStates(Chunk chunk) {
        final int minY = chunk.getMinSection() * SECTION_SIZE;
        final int maxY = chunk.getMaxSection() * SECTION_SIZE;
        final BitSet seen = new BitSet();

        chunk.lockReadLock();
        try {
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                    for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                        seen.set(typeAt(chunk, x, y, z).stateId());
                    }
                }
            }
        } finally {
            chunk.unlockReadLock();
        }
        return seen.cardinality();
    }

    /**
     * Verifies that two chunks hold the same block at every position and the same heightmaps.
     * <p>
     * This is the equivalence stage a comparison benchmark of this module has to pass before its first
     * measurement, in the shape {@code LightEngineComparisonBenchmark#verifyBothEnginesAgree}
     * established: it throws, so a trial that would have compared two different worlds stops instead of
     * publishing a number.
     * </p>
     * <p>
     * The walk reads with {@link Block.Getter.Condition#NONE} rather than
     * {@link Block.Getter.Condition#TYPE}, which is the stricter of the two. {@code TYPE} answers from
     * the palette alone and would accept two chunks that store the same state ids but disagree about
     * which of them carry nbt or a handler; {@code NONE} consults the block entity map first and falls
     * back to the palette, so one walk covers both places a chunk keeps block data, and
     * {@code Block#equals} compares the state id, the nbt and the handler.
     * </p>
     * <p>
     * Both heightmaps are compared afterwards, per column rather than through their packed form,
     * because a difference in a packed long says only that something is wrong while a difference in a
     * column says where. They are not redundant with the block walk: a heightmap is derived state that
     * is maintained incrementally on every write, so two chunks can hold identical blocks and still
     * disagree about their heights if one of them refreshed differently — and the heightmap is what
     * ends up in the chunk packet.
     * </p>
     *
     * @param expected the chunk that defines the content, usually the Minestom side
     * @param actual   the chunk that has to match it, usually the Falco side
     * @throws IllegalStateException if the two chunks differ in their bounds, in a block or in a height
     */
    public static void assertSameBlocks(Chunk expected, Chunk actual) {
        if (expected.getMinSection() != actual.getMinSection() || expected.getMaxSection() != actual.getMaxSection()) {
            throw new IllegalStateException("The chunks span different sections: expected ["
                    + expected.getMinSection() + ", " + expected.getMaxSection() + ") but got ["
                    + actual.getMinSection() + ", " + actual.getMaxSection() + ")");
        }
        if (expected.getSections().size() != actual.getSections().size()) {
            throw new IllegalStateException("The chunks hold a different amount of sections: expected "
                    + expected.getSections().size() + " but got " + actual.getSections().size());
        }
        final int minY = expected.getMinSection() * SECTION_SIZE;
        final int maxY = expected.getMaxSection() * SECTION_SIZE;

        expected.lockReadLock();
        try {
            actual.lockReadLock();
            try {
                compareBlocks(expected, actual, minY, maxY);
                compareHeightmaps(expected, actual, expected.motionBlockingHeightmap(), actual.motionBlockingHeightmap());
                compareHeightmaps(expected, actual, expected.worldSurfaceHeightmap(), actual.worldSurfaceHeightmap());
            } finally {
                actual.unlockReadLock();
            }
        } finally {
            expected.unlockReadLock();
        }
    }

    /**
     * Writes one state per block, cycling through the set in storage order.
     *
     * @param chunk  the chunk to fill
     * @param blocks the states to spread
     * @param minY   the lowest block Y of the chunk
     * @param maxY   the block Y one above the highest one of the chunk
     */
    private static void fillUniform(Chunk chunk, Block[] blocks, int minY, int maxY) {
        int index = 0;

        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                    chunk.setBlock(x, y, z, blocks[index % blocks.length]);
                    index++;
                }
            }
        }
    }

    /**
     * Writes one state per horizontal layer, cycling through the set from the bottom upwards.
     *
     * @param chunk  the chunk to fill
     * @param blocks the states to spread
     * @param minY   the lowest block Y of the chunk
     * @param maxY   the block Y one above the highest one of the chunk
     */
    private static void fillLayered(Chunk chunk, Block[] blocks, int minY, int maxY) {
        for (int y = minY; y < maxY; y++) {
            final Block block = blocks[(y - minY) % blocks.length];

            for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                    chunk.setBlock(x, y, z, block);
                }
            }
        }
    }

    /**
     * Writes runs of one state along the storage order, of a seeded random length.
     * <p>
     * The first runs are primed with the states of the set in order so that the chunk is guaranteed to
     * hold every state that was requested. Only after the set is exhausted does the state of a run
     * become a draw.
     * </p>
     *
     * @param chunk  the chunk to fill
     * @param blocks the states to spread
     * @param minY   the lowest block Y of the chunk
     * @param maxY   the block Y one above the highest one of the chunk
     * @param seed   the seed the run lengths and the states are drawn from
     */
    private static void fillRandomRuns(Chunk chunk, Block[] blocks, int minY, int maxY, long seed) {
        final Random random = new Random(seed);
        final int span = RUN_LENGTH_MAX - RUN_LENGTH_MIN + 1;
        Block current = blocks[0];
        int remaining = 0;
        int primed = 0;

        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                    if (remaining == 0) {
                        current = primed < blocks.length ? blocks[primed++] : blocks[random.nextInt(blocks.length)];
                        remaining = RUN_LENGTH_MIN + random.nextInt(span);
                    }
                    chunk.setBlock(x, y, z, current);
                    remaining--;
                }
            }
        }
    }

    /**
     * Compares every block of two chunks and throws at the first difference.
     *
     * @param expected the chunk that defines the content
     * @param actual   the chunk that has to match it
     * @param minY     the lowest block Y of both chunks
     * @param maxY     the block Y one above the highest one of both chunks
     * @throws IllegalStateException if the two chunks hold a different block anywhere
     */
    private static void compareBlocks(Chunk expected, Chunk actual, int minY, int maxY) {
        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                    final Block expectedBlock = expected.getBlock(x, y, z, Block.Getter.Condition.NONE);
                    final Block actualBlock = actual.getBlock(x, y, z, Block.Getter.Condition.NONE);

                    if (Objects.equals(expectedBlock, actualBlock)) {
                        continue;
                    }
                    throw new IllegalStateException("The chunks disagree at x=" + x + " y=" + y + " z=" + z
                            + ": " + describe(expected) + " holds " + describe(expectedBlock) + " but "
                            + describe(actual) + " holds " + describe(actualBlock));
                }
            }
        }
    }

    /**
     * Compares two heightmaps column by column and throws at the first difference.
     *
     * @param expectedChunk the chunk the expected heightmap belongs to
     * @param actualChunk   the chunk the actual heightmap belongs to
     * @param expected      the heightmap that defines the heights
     * @param actual        the heightmap that has to match it
     * @throws IllegalStateException if the two heightmaps hold a different height anywhere
     */
    private static void compareHeightmaps(Chunk expectedChunk, Chunk actualChunk,
                                          Heightmap expected, Heightmap actual) {
        if (expected.type() != actual.type()) {
            throw new IllegalStateException("The chunks compare heightmaps of different types: "
                    + expected.type() + " against " + actual.type());
        }
        for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
            for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                final int expectedHeight = expected.getHeight(x, z);
                final int actualHeight = actual.getHeight(x, z);

                if (expectedHeight == actualHeight) {
                    continue;
                }
                throw new IllegalStateException("The chunks disagree on the " + expected.type()
                        + " height of the column x=" + x + " z=" + z + ": " + describe(expectedChunk)
                        + " holds " + expectedHeight + " but " + describe(actualChunk) + " holds " + actualHeight);
            }
        }
    }

    /**
     * Reads the block type at a position of a chunk, treating an absent answer as air.
     * <p>
     * {@link Block.Getter#getBlock(int, int, int, Block.Getter.Condition)} is declared with unknown
     * nullability because {@link Block.Getter.Condition#CACHED} is allowed to answer with nothing. The
     * counters of this class never ask for that condition, but they run against chunk implementations
     * this module is written to compare rather than against one it controls, so the fallback is stated
     * instead of assumed.
     * </p>
     *
     * @param chunk the chunk to read from, with the read lock held by the caller
     * @param x     the block X inside the chunk
     * @param y     the block Y
     * @param z     the block Z inside the chunk
     * @return the block at the position, air if the chunk answered with nothing
     */
    private static Block typeAt(Chunk chunk, int x, int y, int z) {
        return Objects.requireNonNullElse(chunk.getBlock(x, y, z, Block.Getter.Condition.TYPE), Block.AIR);
    }

    /**
     * Names a chunk by its type and position for a failure message.
     *
     * @param chunk the chunk to name
     * @return the name of the chunk
     */
    private static String describe(Chunk chunk) {
        return chunk.getClass().getSimpleName() + "[" + chunk.getChunkX() + ":" + chunk.getChunkZ() + "]";
    }

    /**
     * Names a block by its key and state id for a failure message.
     *
     * @param block the block to name, null if the chunk answered with nothing
     * @return the name of the block
     */
    private static String describe(@Nullable Block block) {
        if (block == null) {
            return "nothing";
        }
        return block.key().asString() + "(" + block.stateId() + ")";
    }

    /**
     * Holds the server process so it is started exactly once.
     * <p>
     * A holder class rather than a synchronised method or a double checked field: class initialisation
     * is the one mechanism the JVM already performs under a lock, exactly once, with the result
     * published safely to every thread that reads the field afterwards. It costs nothing on the reads
     * that follow.
     * </p>
     */
    private static final class Server {

        /**
         * The running server process, adopted if one already exists.
         */
        static final ServerProcess PROCESS = start();

        /**
         * Blocks the creation of an instance because the class only holds the process.
         */
        private Server() {
        }

        /**
         * Returns the running server process, starting one if there is none.
         *
         * @return the running server process
         */
        private static ServerProcess start() {
            final ServerProcess running = MinecraftServer.process();

            if (running != null) {
                return running;
            }
            MinecraftServer.init();
            return MinecraftServer.process();
        }
    }

    /**
     * Holds the blocks a fill draws from so the registry is walked exactly once.
     */
    private static final class BlockSet {

        /**
         * Every registered block which is neither air nor a block entity, by ascending state id.
         */
        static final Block[] BLOCKS = collect();

        /**
         * Blocks the creation of an instance because the class only holds the blocks.
         */
        private BlockSet() {
        }

        /**
         * Walks the block registry and collects the blocks a fill may use.
         * <p>
         * The result is sorted by state id rather than left in registry order, because the registry is
         * backed by a hash map whose iteration order is an implementation detail. Sorting makes the
         * set the same on every run and on every Minestom build that keeps its ids, which is what turns
         * a state count into a reproducible axis.
         * </p>
         *
         * @return the usable blocks, by ascending state id
         */
        private static Block[] collect() {
            ensureServer();
            final List<Block> collected = new ArrayList<>();

            for (Block block : Block.values()) {
                if (usable(block)) {
                    collected.add(block);
                }
            }
            collected.sort(Comparator.comparingInt(Block::stateId));
            return collected.toArray(new Block[0]);
        }
    }

    /**
     * Holds the further states of the usable blocks, built only when a fill asks for more distinct
     * states than there are distinct blocks.
     * <p>
     * Separate from {@link BlockSet} because walking {@code possibleStates()} of every block is far
     * more work than walking the registry, and the state counts a benchmark actually runs are below
     * {@link #availableBlocks()} most of the time. A holder that is never touched is never initialised.
     * </p>
     */
    private static final class ExtraStates {

        /**
         * Every state of a usable block except the default one, by ascending state id.
         */
        static final Block[] STATES = collect();

        /**
         * Blocks the creation of an instance because the class only holds the states.
         */
        private ExtraStates() {
        }

        /**
         * Walks the states of every usable block and collects the ones the block set does not hold.
         *
         * @return the further states, by ascending state id
         */
        private static Block[] collect() {
            final List<Block> collected = new ArrayList<>();

            for (Block block : BlockSet.BLOCKS) {
                for (Block state : block.possibleStates()) {
                    if (state.stateId() != block.stateId()) {
                        collected.add(state);
                    }
                }
            }
            collected.sort(Comparator.comparingInt(Block::stateId));
            return collected.toArray(new Block[0]);
        }
    }

    /**
     * Tells whether a block may take part in a fill.
     *
     * @param block the block to judge
     * @return true if the block is neither air nor a block entity
     */
    private static boolean usable(Block block) {
        return !block.isAir() && !block.registry().isBlockEntity();
    }
}
