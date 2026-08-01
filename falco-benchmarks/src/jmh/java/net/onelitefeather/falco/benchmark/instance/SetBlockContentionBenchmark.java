package net.onelitefeather.falco.benchmark.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import net.onelitefeather.falco.instance.FalcoInstance;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.ThreadParams;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * The {@link SetBlockContentionBenchmark} class measures how many block writes per second an
 * {@link InstanceContainer} and a {@link FalcoInstance} accept while several threads write at once.
 * <p>
 * There is exactly one difference between the two sides that this module can defend with a number, and
 * this is it. {@code InstanceContainer#UNSAFE_setBlock} is declared {@code private synchronized}, so the
 * monitor it takes is the monitor of the whole instance, and it keeps that monitor over the placement
 * rule, over {@code BlockHandler#onPlace} and {@code onDestroy}, over up to six recursive neighbour
 * updates, over the serialisation of the block change packet to every viewer and over the event
 * dispatch. The read write lock every chunk carries since the pinned Minestom build does not soften
 * that: {@code chunk.lockWriteLock()} is taken <em>inside</em> the monitor, so the effective write
 * granularity of a Minestom world is the world, not the chunk. {@code FalcoInstance#writeBlock} holds
 * the write lock of the touched chunk and nothing else, and it runs the neighbour updates, the packets
 * and the event after releasing it.
 * </p>
 * <p>
 * That is a claim about a lock, and a claim about a lock is only worth what a thread count sweep says
 * about it. A single thread cannot tell a coarse lock from a fine one, because an uncontended monitor
 * costs almost nothing; the two sides are expected to sit on top of each other at one thread and to
 * separate as threads are added. The interesting output of this class is therefore not a pair of
 * numbers but a pair of curves over {@code -t 1,2,4,8,16}.
 * </p>
 *
 * <h2>Why the mode deviates from the convention of this module</h2>
 * <p>
 * Every other benchmark here reports {@code Mode.AverageTime} in microseconds, and this one reports
 * {@code Mode.Throughput} in operations per second. The deviation is deliberate and it is about what
 * the number has to add up to. Under contention the quantity that matters is the work the whole system
 * completes, and throughput is the only mode in which that is directly readable: JMH sums the
 * operations of all threads, so the number of a sixteen thread run is the number of the system, and
 * comparing it against the one thread run of the same arm gives the scaling factor the claim is about.
 * {@code AverageTime} would report the latency of a single writer, which under a contended monitor
 * degrades by roughly the thread count on both sides and hides whether any additional work got done at
 * all. Two arms can have the identical average latency while one of them completes sixteen times as
 * much.
 * </p>
 *
 * <h2>The three scenarios</h2>
 * <p>
 * {@link Scenario#DISJOINT_CHUNKS} gives every thread a chunk of its own. Nothing in the data forces
 * these writers to wait for each other, so whatever serialisation the measurement shows is the lock
 * and only the lock. This is the scenario the hypothesis is written for.
 * </p>
 * <p>
 * {@link Scenario#SAME_CHUNK} puts every thread into chunk {@code 0:0}, at a position of its own. The
 * chunk write lock of Falco now excludes the same set of writers the instance monitor of Minestom
 * excludes, so the two critical sections are equally <em>wide</em>. What still differs is how long they
 * are <em>held</em>: Falco releases before the neighbour updates, the packet and the event, the
 * container releases after them. The scenario therefore separates the two halves of the claim, and it
 * is the scenario in which the costs Falco pays for its granularity are offset by nothing at all — if
 * Falco loses anywhere it loses here. It is included because a benchmark that only ran the case its
 * hypothesis predicts is not a measurement but an illustration.
 * </p>
 * <p>
 * {@link Scenario#DISJOINT_CHUNKS_WITH_HANDLER} is {@link Scenario#DISJOINT_CHUNKS} with the two
 * written blocks carrying a {@link BlockHandler} whose {@code onPlace} burns roughly two microseconds
 * of processor time. The two scenarios differ in that handler and in nothing else — same chunks, same
 * positions, same block states, same fill, same neighbour updates — so the difference between them is
 * attributable to the handler alone. This is the argument rather than a variant of it: the monitor of
 * the container is held across {@code onPlace}, which means the hold time of the lock that serialises
 * every block write of the world is set by code the container does not own and cannot bound. Two
 * microseconds is not an adversarial number. It is what a handler that touches a database, a region
 * file or a scoreboard costs, and it is far below what a handler that logs costs.
 * </p>
 *
 * <h2>Why the state is shared and not per thread</h2>
 * <p>
 * The convention of this module is {@code @State(Scope.Thread)}, and it cannot hold here. The subject
 * of the measurement is a lock which is per instance, so giving every thread its own instance would
 * remove the thing being measured and leave a benchmark that reports the same number for both arms and
 * calls it a tie. The instances and the chunks are therefore shared, and the only per thread state is
 * the slot index and the write parity, which live in {@link Writer}.
 * </p>
 *
 * <h2>Why the written block alternates</h2>
 * <p>
 * Both implementations carry the same guard against a handler that destroys its own block: a map from
 * position to the block currently being written, consulted before the write and returning early when
 * the same block is written to the same position again. Minestom clears that map in
 * {@code InstanceContainer#tick}, Falco in its own tick. A benchmark harness runs no tick loop —
 * {@code MinecraftServer.init()} does not start one — so a benchmark that wrote one constant block to
 * one constant position would take the early return on the second operation and measure a map lookup
 * for the rest of the run, on both sides, and would report an enormous and completely fictional
 * throughput.
 * </p>
 * <p>
 * Each thread therefore alternates between two block states at its position. The alternation makes the
 * guard miss every time, which is the honest path, and it keeps the guard map bounded at one entry per
 * written position rather than letting it grow for the whole run.
 * </p>
 *
 * <h2>What Falco pays for its granularity, and why this benchmark can show it</h2>
 * <p>
 * The finer lock is not free, and the two costs are visible in the code rather than assumed. The guard
 * map of the container is a plain {@link java.util.HashMap} and it is allowed to be one precisely
 * because the monitor already excludes every other writer; the guard map of Falco is a
 * {@code ConcurrentHashMap} and it is touched by every thread before the chunk lock is taken, so Falco
 * pays a concurrent put per write where the container pays a plain one. Falco also resolves its chunk
 * through a {@code ConcurrentHashMap<Long, Chunk>}, which boxes the key on every write, where the
 * container uses a primitive keyed map. Under {@link Scenario#SAME_CHUNK} neither of those costs is
 * offset by anything, so if Falco loses anywhere it will lose there. That is a result about the
 * present implementation and it is reported as one.
 * </p>
 *
 * <h2>What a losing result would mean</h2>
 * <p>
 * This benchmark measures an advantage of the {@code falco-instance} module as it exists today. It is
 * not evidence for a planned reimplementation and must not be cited as such. Nothing here is arranged
 * to make Falco win: both arms run through the public {@code Instance#setBlock(int, int, int, Block)},
 * both get chunks built by the same fixture and filled with the same seeded content, both write the
 * same two block states at the same coordinates, and both run with block updates enabled, which is the
 * setting under which the container holds its monitor across the neighbour recursion and Falco does
 * not. If the curves do not separate under {@link Scenario#DISJOINT_CHUNKS}, then the monitor is not
 * the bottleneck this module claims it is, and the correct conclusion is that the claim was wrong — not
 * that the harness needs adjusting.
 * </p>
 *
 * <h2>What the numbers are and are not</h2>
 * <p>
 * No result is recorded in this javadoc. The hypothesis is that the two arms sit on top of each other
 * at one thread in every scenario, that the container arm stays roughly flat from one thread to sixteen
 * under {@link Scenario#DISJOINT_CHUNKS} while the Falco arm rises, that the two arms stay far closer
 * to each other under {@link Scenario#SAME_CHUNK} because the shorter hold time is all that is left of
 * the difference there, and that the gap under {@link Scenario#DISJOINT_CHUNKS_WITH_HANDLER} grows with
 * the burn because the container adds the handler cost to a hold time every other writer waits behind.
 * A hypothesis is what all of that is until the run exists.
 * </p>
 * <p>
 * {@link Blackhole#consumeCPU(long)} burns a number of tokens, not a number of nanoseconds; the
 * relation is linear but the constant is a property of the machine. The benchmark calibrates itself:
 * at one thread, the reciprocal throughput of {@link Scenario#DISJOINT_CHUNKS_WITH_HANDLER} minus the
 * reciprocal throughput of {@link Scenario#DISJOINT_CHUNKS} is the actual per operation cost of the
 * burn on the machine that produced the run, and that difference belongs next to any citation of the
 * handler scenario.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * A server is started, so the fork count drops to one and the heap is raised, as the convention of this
 * module prescribes for that case. The thread count is the axis and has to be swept explicitly, because
 * {@code @Threads} is not a parameter JMH can cross:
 * </p>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:jmhJar
 * for t in 1 2 4 8 16; do
 *   java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
 *       "SetBlockContentionBenchmark.(minestom|falco)" \
 *       -t $t -f 1 -wi 5 -i 5 -prof gc -jvmArgs "-Xms2g -Xmx2g" \
 *       -rff setblock-contention-t$t.json -rf json
 * done
 * }</pre>
 * <p>
 * The claim is about a monitor, so one run should also carry the evidence that it is a monitor rather
 * than merely something slow. The JFR profiler records the Java monitor blocked events, and the class
 * that owns the monitor appears in them by name:
 * </p>
 * <pre>{@code
 * java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
 *     "SetBlockContentionBenchmark.minestom" -p scenario=DISJOINT_CHUNKS_WITH_HANDLER \
 *     -t 16 -f 1 -wi 5 -i 5 -jvmArgs "-Xms2g -Xmx2g" \
 *     -prof "jfr:configName=profile;dir=build/jfr"
 * }</pre>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(1)
public class SetBlockContentionBenchmark {

    /**
     * The block Y every thread writes at.
     * <p>
     * Well inside the world bounds and well below the surface the fill produces, which keeps both
     * heightmaps on their cheap branch: a solid block written below the current height compares and
     * returns, while a block written at the current height sends the heightmap back down the column
     * with a palette scan per step. The measured operation is supposed to be a block write, not a
     * heightmap rescan, and it has to be the same block write on both sides.
     * </p>
     */
    private static final int BLOCK_Y = 64;

    /**
     * The lowest chunk relative X and Z a thread may write at.
     * <p>
     * One block away from the chunk border on every side. Block updates are enabled, so every write
     * asks its six neighbours whether they want to reshape themselves; a position on the border would
     * send that question into a neighbouring chunk, which means a second chunk lock on the Falco side
     * and a chunk lookup that may miss on both. Keeping the writes off the border keeps the operation
     * of the two arms identical and keeps the scenario axis meaningful.
     * </p>
     */
    private static final int LOCAL_MIN = 1;

    /**
     * The amount of chunk relative positions a thread slot may be spread over per axis.
     */
    private static final int LOCAL_SPAN = Chunk.CHUNK_SIZE_X - 2 * LOCAL_MIN;

    /**
     * The processor tokens {@link BurnHandler} burns per placement.
     * <p>
     * Roughly two microseconds on typical hardware. The exact figure is a property of the machine and
     * is read off the run rather than assumed; see the class javadoc.
     * </p>
     */
    private static final long BURN_TOKENS = 2000L;

    /**
     * The amount of distinct block states the chunks are filled with before the measurement.
     * <p>
     * A chunk that was never filled holds a palette with {@code bitsPerEntry == 0} and no backing
     * array, so the first write into each section pays a resize and every write after it runs against
     * a palette no loaded world ever has. Sixteen states put every section into the indirect mode a
     * real chunk is in, which is the mode the measured writes should meet.
     * </p>
     */
    private static final int FILL_STATES = 16;

    /**
     * The arrangement of the writers over the chunks, and whether their blocks carry a handler.
     */
    public enum Scenario {

        /**
         * Every thread writes into a chunk of its own, and the written blocks carry no handler.
         * <p>
         * The scenario that isolates the lock. Two writes into two chunks share no data at all, so a
         * throughput that stops rising with the thread count has only one remaining explanation.
         * </p>
         */
        DISJOINT_CHUNKS,

        /**
         * Every thread writes into chunk {@code 0:0}, each at a position of its own.
         * <p>
         * The control. Here the chunk write lock of Falco excludes exactly the writers the instance
         * monitor of Minestom excludes, so the width of the two critical sections is the same and only
         * their hold time is not. Whatever remains of the difference in this scenario is the shorter
         * hold, and whatever the finer mechanism costs is unmasked, because nothing here offsets it.
         * </p>
         */
        SAME_CHUNK,

        /**
         * Like {@link #DISJOINT_CHUNKS}, with a {@link BlockHandler} that burns processor time in
         * {@code onPlace}.
         * <p>
         * The handler runs inside the chunk write lock on both sides, because both call
         * {@code Chunk#setBlock} there. On the Falco side that is the lock of one chunk and the other
         * writers are untouched; on the container side it is inside the instance monitor, so every
         * writer of the world waits for it. Whatever this scenario shows over
         * {@link #DISJOINT_CHUNKS} is the amount by which foreign code sets the hold time of a lock
         * that is not foreign code's to set.
         * </p>
         */
        DISJOINT_CHUNKS_WITH_HANDLER
    }

    /**
     * The arrangement the measured writes run under.
     */
    @Param({"DISJOINT_CHUNKS", "SAME_CHUNK", "DISJOINT_CHUNKS_WITH_HANDLER"})
    public Scenario scenario;

    private InstanceContainer container;
    private FalcoInstance falco;
    private Block[] blocks;
    private int[] slotX;
    private int[] slotZ;
    private @Nullable BurnHandler handler;
    private long placementsAfterSetup;

    /**
     * Builds both instances, gives every thread slot its chunk and its position, proves that the two
     * sides hold the same world and that a write actually lands, and leaves the guard maps in the state
     * the first measured operation expects.
     * <p>
     * The order of the steps is the point of the method. The chunks are loaded and filled first, then
     * every slot is primed with the same two writes on both sides, and only then are the two sides
     * compared. Comparing before the priming would prove that two empty worlds are equal, which is
     * true and useless; comparing after it proves that the very writes the benchmark is about produce
     * the same result on both implementations. The comparison throws, so a trial that would have
     * published a faster number for a different world stops instead.
     * </p>
     *
     * @param params the parameters of the trial, read for the thread count
     * @throws IllegalStateException if the thread count exceeds the positions a chunk offers, if a
     *                               primed write did not land, if the two sides disagree about their
     *                               content or if the handler of the scenario was never called
     */
    @Setup(Level.Trial)
    public void setUp(BenchmarkParams params) {
        final int threads = params.getThreads();

        if (threads > LOCAL_SPAN * LOCAL_SPAN) {
            throw new IllegalStateException("A chunk offers " + (LOCAL_SPAN * LOCAL_SPAN)
                    + " positions away from its border, so " + threads
                    + " threads cannot be given a position of their own");
        }
        MinestomChunks.ensureServer();
        this.container = MinestomChunks.newContainer();
        this.falco = MinestomChunks.newFalcoInstance();
        this.handler = this.scenario == Scenario.DISJOINT_CHUNKS_WITH_HANDLER ? new BurnHandler() : null;
        this.blocks = blocksOf(this.handler);
        this.slotX = new int[threads];
        this.slotZ = new int[threads];

        final boolean sameChunk = this.scenario == Scenario.SAME_CHUNK;
        final int chunks = sameChunk ? 1 : threads;
        final Chunk[] minestomChunks = new Chunk[chunks];
        final Chunk[] falcoChunks = new Chunk[chunks];

        for (int chunkX = 0; chunkX < chunks; chunkX++) {
            minestomChunks[chunkX] = MinestomChunks.loadChunk(this.container, chunkX, 0);
            falcoChunks[chunkX] = MinestomChunks.loadChunk(this.falco, chunkX, 0);
            MinestomChunks.fill(minestomChunks[chunkX], FILL_STATES, MinestomChunks.FillShape.RANDOM_RUNS);
            MinestomChunks.fill(falcoChunks[chunkX], FILL_STATES, MinestomChunks.FillShape.RANDOM_RUNS);
        }
        for (int slot = 0; slot < threads; slot++) {
            final int chunkX = sameChunk ? 0 : slot;
            this.slotX[slot] = chunkX * Chunk.CHUNK_SIZE_X + LOCAL_MIN + slot % LOCAL_SPAN;
            this.slotZ[slot] = LOCAL_MIN + slot / LOCAL_SPAN;
            prime(slot);
        }
        for (int chunkX = 0; chunkX < chunks; chunkX++) {
            MinestomChunks.assertSameBlocks(minestomChunks[chunkX], falcoChunks[chunkX]);
        }
        verifyHandler(threads);
    }

    /**
     * Releases both instances and verifies that the handler of the scenario kept being called.
     * <p>
     * The release has to happen even when the verification fails, otherwise a failing trial leaves two
     * registered instances and their chunks behind for every trial that follows in the same fork.
     * </p>
     *
     * @throws IllegalStateException if the handler of the scenario was not called during the trial
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        try {
            final BurnHandler burnHandler = this.handler;

            if (burnHandler != null && burnHandler.placements() <= this.placementsAfterSetup) {
                throw new IllegalStateException("The handler was called " + this.placementsAfterSetup
                        + " times during the setup and not once during the trial, so the scenario "
                        + this.scenario + " measured the same thing as " + Scenario.DISJOINT_CHUNKS);
            }
        } finally {
            MinestomChunks.release(this.container);
            MinestomChunks.release(this.falco);
        }
    }

    /**
     * Measures a block write into the {@link InstanceContainer} of Minestom.
     * <p>
     * The call goes through {@code Instance#setBlock(int, int, int, Block)}, which enables block
     * updates. That is the path a placement of a player takes and it is the path over which the
     * container holds its monitor across the neighbour recursion.
     * </p>
     *
     * @param writer the slot and the write parity of the calling thread
     */
    @Benchmark
    public void minestom(Writer writer) {
        final int slot = writer.slot;
        this.container.setBlock(this.slotX[slot], BLOCK_Y, this.slotZ[slot], this.blocks[writer.next()]);
    }

    /**
     * Measures the same block write into the {@link FalcoInstance}.
     *
     * @param writer the slot and the write parity of the calling thread
     */
    @Benchmark
    public void falco(Writer writer) {
        final int slot = writer.slot;
        this.falco.setBlock(this.slotX[slot], BLOCK_Y, this.slotZ[slot], this.blocks[writer.next()]);
    }

    /**
     * Writes the alternating sequence of a slot once into both instances and verifies that it landed.
     * <p>
     * The readback is not a formality. Both implementations return early when the same block is written
     * to the same position twice without a tick in between, and a harness runs no tick, so a benchmark
     * whose writes are swallowed by that guard would measure a map lookup while looking perfectly
     * healthy. Priming with both states and checking that the second one is what the instance answers
     * with proves that the guard is missing rather than hitting, and it leaves the guard map holding
     * the second state so that the first measured operation, which writes the first state, misses too.
     * </p>
     *
     * @param slot the thread slot to prime
     * @throws IllegalStateException if an instance does not hold the last written block afterwards
     */
    private void prime(int slot) {
        final int x = this.slotX[slot];
        final int z = this.slotZ[slot];

        for (Block block : this.blocks) {
            this.container.setBlock(x, BLOCK_Y, z, block);
            this.falco.setBlock(x, BLOCK_Y, z, block);
        }
        final Block expected = this.blocks[this.blocks.length - 1];
        requireHolds("InstanceContainer", this.container.getBlock(x, BLOCK_Y, z), expected, x, z);
        requireHolds("FalcoInstance", this.falco.getBlock(x, BLOCK_Y, z), expected, x, z);
    }

    /**
     * Verifies that a primed write is visible in the instance that received it.
     *
     * @param instance the name of the instance for the failure message
     * @param actual   the block the instance answers with
     * @param expected the block that was written last
     * @param x        the block X that was written
     * @param z        the block Z that was written
     * @throws IllegalStateException if the instance holds a different block
     */
    private static void requireHolds(String instance, Block actual, Block expected, int x, int z) {
        if (expected.equals(actual)) {
            return;
        }
        throw new IllegalStateException("The " + instance + " holds " + actual.key().asString() + " at x=" + x
                + " y=" + BLOCK_Y + " z=" + z + " instead of the " + expected.key().asString()
                + " that was written there, so the write was swallowed and the measurement would not"
                + " write a block at all");
    }

    /**
     * Verifies that the handler of the scenario was wired to the written blocks, and records how often
     * it ran during the setup.
     * <p>
     * A scenario that carries a handler which is never called is silently the scenario without one, and
     * the difference between the two is the entire argument of this class. The check is exact enough to
     * catch that and loose enough to survive a neighbour update that writes an extra block: every
     * primed write must have produced at least one placement, on both sides.
     * </p>
     *
     * @param threads the thread count of the trial
     * @throws IllegalStateException if the scenario carries a handler that was not called, or if a
     *                               scenario without one wrote blocks that carry a handler anyway
     */
    private void verifyHandler(int threads) {
        final BurnHandler burnHandler = this.handler;

        if (burnHandler == null) {
            if (this.blocks[0].handler() != null || this.blocks[1].handler() != null) {
                throw new IllegalStateException("The scenario " + this.scenario + " writes blocks which carry"
                        + " a handler, so it measures the same thing as " + Scenario.DISJOINT_CHUNKS_WITH_HANDLER);
            }
            return;
        }
        final long expected = (long) threads * this.blocks.length * 2L;
        this.placementsAfterSetup = burnHandler.placements();

        if (this.placementsAfterSetup < expected) {
            throw new IllegalStateException("The setup wrote " + expected + " blocks carrying a handler but the"
                    + " handler was called " + this.placementsAfterSetup + " times, so the scenario "
                    + this.scenario + " does not measure the cost of a handler");
        }
    }

    /**
     * Returns the two block states the writers alternate between.
     * <p>
     * Stone and dirt: both solid, neither a block entity, and neither of them owns a block placement
     * rule, so an enabled block update walks its six neighbours and changes nothing. That keeps the
     * measured operation a single block write rather than a recursion of unknown depth, and it keeps
     * the recursion depth the same on both arms, which is what makes the arms comparable at all.
     * </p>
     *
     * @param handler the handler to attach to both states, null for the scenarios without one
     * @return the two states, in the order the writers cycle through them
     */
    private static Block[] blocksOf(@Nullable BlockHandler handler) {
        if (handler == null) {
            return new Block[]{Block.STONE, Block.DIRT};
        }
        return new Block[]{Block.STONE.withHandler(handler), Block.DIRT.withHandler(handler)};
    }

    /**
     * The per thread part of the state: which slot a thread owns and which of the two blocks it writes
     * next.
     * <p>
     * Everything else in this benchmark is shared on purpose, because the lock under measurement is
     * shared. These two fields must not be, and they must not sit in an array indexed by thread either:
     * sixteen threads incrementing sixteen adjacent array slots would trade the cache line of that
     * array between all of them on every operation and would report that as the cost of the block
     * write. A JMH thread state is padded, allocated per thread and therefore free of that.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    @State(Scope.Thread)
    public static class Writer {

        /**
         * The slot of the owning thread, which selects its chunk and its position.
         */
        int slot;

        /**
         * The amount of writes the owning thread has performed.
         */
        int writes;

        /**
         * Reads the slot of the owning thread from the harness.
         *
         * @param params the thread parameters of the owning thread
         */
        @Setup(Level.Trial)
        public void setUp(ThreadParams params) {
            this.slot = params.getThreadIndex();
        }

        /**
         * Returns the index of the block to write next and advances the alternation.
         *
         * @return {@code 0} or {@code 1}, alternating
         */
        int next() {
            return this.writes++ & 1;
        }
    }

    /**
     * A {@link BlockHandler} that burns processor time when a block carrying it is placed.
     * <p>
     * The burn sits in {@code onPlace} alone. {@code onDestroy} is reached as well from the second
     * write of a position onwards, because the block being replaced carries the same handler, and it is
     * left empty so that one measured operation costs exactly one burn. A burn in both would double the
     * cost of an operation without making the scenario say anything it does not already say.
     * </p>
     * <p>
     * The counter is a {@link LongAdder} rather than an {@link java.util.concurrent.atomic.AtomicLong}
     * because sixteen threads incrementing one atomic would be a second contention point sitting inside
     * the one under measurement. Even striped it is not free, and it is not pretended to be: it is only
     * present in the scenario that also burns two microseconds per call, where it is several orders of
     * magnitude below the noise it is measured against. The scenarios without a handler touch it not at
     * all.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    private static final class BurnHandler implements BlockHandler {

        /**
         * The key this handler is known by.
         */
        private static final Key KEY = Key.key("falco", "benchmark_burn");

        /**
         * How often {@link #onPlace(Placement)} has run.
         */
        private final LongAdder placements = new LongAdder();

        @Override
        public void onPlace(Placement placement) {
            this.placements.increment();
            Blackhole.consumeCPU(BURN_TOKENS);
        }

        @Override
        public Key getKey() {
            return KEY;
        }

        /**
         * Returns how often this handler has been called for a placement.
         *
         * @return the amount of placements this handler has seen
         */
        long placements() {
            return this.placements.sum();
        }
    }
}
