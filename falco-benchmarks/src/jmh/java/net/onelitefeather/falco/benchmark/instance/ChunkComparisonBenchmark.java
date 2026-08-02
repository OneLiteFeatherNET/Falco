package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.heightmap.Heightmap;
import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import net.onelitefeather.falco.instance.FalcoChunk;
import net.onelitefeather.falco.instance.FalcoInstance;
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
import org.openjdk.jmh.annotations.Warmup;

import java.util.BitSet;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The {@link ChunkComparisonBenchmark} class measures the chunk of Falco against the chunk of
 * Minestom on the four operations a chunk spends its life in, and it is the control that decides
 * whether every other chunk measurement of this module means anything.
 * <p>
 * The expected result is not "Falco is faster" but "the two sides are indistinguishable", and that
 * is what makes the class valuable: it is the only measurement in this module whose correct answer
 * is known in advance, so a bias of the harness has to surface here rather than later, when a
 * prototype with its own storage runs against the same scaffolding and the bias would be read as a
 * property of the prototype.
 * </p>
 * <p>
 * Why that answer is known in advance changed with stage 1 of the block storage work, and the change
 * is worth stating precisely. {@code FalcoChunk} used to extend {@code DynamicChunk}, declare no
 * field of its own and override neither {@code setBlock} nor {@code getBlock} nor the heightmaps, so
 * the two arms executed the same bytecode and their equality was a fact about the class hierarchy.
 * Today it extends {@code Chunk}, holds a {@code BlockStorage} field and overrides all four. The
 * bodies of those overrides are the bodies of {@code DynamicChunk} with the palette access moved
 * behind the field, so the two arms still do the same work on the same data — but that is now a
 * property somebody maintains rather than one the compiler enforces.
 * </p>
 *
 * <h2>Which storage the Falco arm runs on, and why the arms are still comparable on it</h2>
 * <p>
 * {@code LazySectionBlockStorage}, since stage 2 of the same work made it the default of
 * {@code FalcoChunk}. That storage hands out one shared, empty {@code Section} for every section the
 * chunk has never written into and only creates a private one on the first write, which is a
 * different layout from the one {@code DynamicChunk} has and not the eager
 * {@code SectionBlockStorage} this paragraph named while stage 1 was the newest thing on the branch.
 * </p>
 * <p>
 * It does not cost the comparison, and the reason is a property of the fixture rather than of the
 * storage. {@code MinestomChunks#fill} walks {@code y} from the floor of the chunk to its build
 * limit and writes a block at every one of the {@code 98304} positions, whatever the shape; the air
 * check that follows it refuses a chunk that came out empty. Every section of the Falco arm is
 * therefore private and populated before the first measured invocation, and a lazy storage whose
 * sections have all been written into holds exactly what an eager one holds. What the two arms
 * measure is two full chunks, once through the field of {@code DynamicChunk} and once through the
 * seam of {@code FalcoChunk}.
 * </p>
 * <p>
 * The condition that buys that is also the limit of these numbers and belongs next to them: this
 * class only ever measures a fully materialised chunk. It says nothing about what stage 2 was built
 * for — a chunk whose empty sections were never created — and no figure from here may be quoted for
 * or against that. {@code SectionMaterialisationTest} counts the sections a chunk holds and
 * {@code ChunkFootprintTest} weighs them; those two are where the saving is stated.
 * </p>
 * <p>
 * What follows for a reader of these numbers is that a difference between the arms is no longer
 * automatically a defect in the harness. It can still be one, and it can now equally be a finding
 * about the chunk — that the seam costs time — and the two have to be told apart before either is
 * reported. Two other measurements are what make that possible.
 * {@code FalcoChunkEquivalenceTest} shows that both sides hold the same blocks and the same
 * heightmaps after exactly these operations, so a difference is not a difference in content, and
 * {@code ChunkFootprintTest} weighs the seam, which is a fixed per chunk cost of a field and the
 * objects behind it — so a difference that grows with the block count is not the seam whatever that
 * test currently reports. What that test may no longer be quoted for is the stage 1 sentence that
 * the two chunks retain the same objects: the lazy layout holds fewer, by construction, and the
 * per class figures are restated there rather than here. Neither of the two measurements would
 * notice a harness that drives the two arms differently, which is why the checks below still exist
 * and still abort the trial.
 * </p>
 * <p>
 * The class deliberately does not live in {@code net.minestom.server.instance}. Two benchmarks of
 * this module do, because the members they measure are package-private and unreachable otherwise.
 * Nothing here needs that: {@code Chunk#setBlock}, {@code Chunk#getBlock}, {@code Chunk#copy},
 * {@code Chunk#motionBlockingHeightmap()}, {@code Heightmap#refresh(int, int, int)} and
 * {@code Heightmap#getHighestBlockSection(Chunk)} are all public. Splitting a package of the server
 * across two artifacts is a cost that is only worth paying when there is no other way in, and here
 * there is one.
 * </p>
 *
 * <h2>The four operations, and why these four</h2>
 * <p>
 * A chunk is written to, read from, copied and asked for its heightmaps, and those four paths have
 * four different cost structures. {@code setBlock} is a palette write plus a hash probe per block
 * map plus two incremental heightmap updates — two probes on the Minestom arm, which keeps a second
 * map of its tickable blocks, and one on the Falco arm, which keeps a counter instead.
 * {@code getBlock} is a palette read behind a guard over the block
 * entity map. {@code copy} clones every section, which is the one operation whose cost is dominated
 * by allocation rather than by work. The full heightmap refresh is a top-down palette scan over all
 * {@code 256} columns and is the most expensive of the four by a wide margin. A candidate storage
 * that wins on one of them can easily lose on another — a denser packing usually pays for its
 * density in the read, and a lazier section usually pays for its laziness in the first write — so
 * an argument built on a single operation would be an argument about whichever operation the author
 * happened to pick.
 * </p>
 *
 * <h2>Why the writes are scattered and why they are a fixed set</h2>
 * <p>
 * The fill of the fixture walks the chunk in storage order, which is the order a generator writes
 * in and the friendliest order the storage will ever see. Measuring {@code setBlock} the same way
 * would measure the generator once more. A player, a plugin and a world edit hit positions that
 * share neither a section nor a cache line, so the measured batch is drawn from the whole volume of
 * the chunk instead, without repeating a position: a repeated position would be a guaranteed cache
 * hit that no scattered access pattern produces, and it would let a later write of the batch undo
 * an earlier one.
 * </p>
 * <p>
 * The batch is built once and then written unchanged on every invocation, with the same block at
 * the same position every time. That makes the whole benchmark idempotent after its first pass: the
 * chunk reaches a fixed point during warmup and stays there, so the last measured invocation runs
 * on exactly the same chunk as the first. A batch of freshly drawn blocks would instead grow the
 * palette of a section a little on every pass, and the measurement would slowly drift into
 * describing a chunk that no {@code @Setup} ever verified.
 * </p>
 * <p>
 * Because the fixed point rather than the fill is what gets measured, the batch is applied once
 * during the setup and the equality of the two sides is proved again afterwards. The chunk the
 * first measured invocation touches is therefore a chunk that has been walked position by position
 * and found identical on both arms.
 * </p>
 * <p>
 * The perturbation this costs is stated rather than hidden. The batch rewrites
 * {@value #SCATTER_COUNT} of the {@code 98304} positions of an overworld chunk, roughly four
 * percent, which breaks a run of {@link MinestomChunks.FillShape#LAYERED} or
 * {@link MinestomChunks.FillShape#RANDOM_RUNS} wherever it lands. The shapes stay clearly distinct
 * at that rate, but a reader comparing these numbers against a benchmark that fills and does not
 * perturb should know that the arrangement here is the arrangement of the fill plus a four percent
 * scatter.
 * </p>
 *
 * <h2>Why the state count is exact after the setup</h2>
 * <p>
 * {@link MinestomChunks.FillShape#LAYERED} cannot show more than {@code 16} states per section and
 * therefore no more than {@code 384} over a full height chunk, so a fill asking for {@code 1024}
 * states leaves {@code 640} of them unplaced. The scatter batch closes that gap as a side effect:
 * it holds {@value #SCATTER_COUNT} distinct positions and cycles through a set of at most
 * {@code 1024} blocks, so every block of the set is written at least four times and none of those
 * writes can be overwritten by another member of the batch. After the setup every parameter
 * combination holds exactly as many distinct states as it asked for, and
 * {@link #verifyStateCount()} checks that with an exception rather than trusting it.
 * </p>
 * <p>
 * What that check cannot do is make the arrangement mean the same thing at that one point. At
 * {@code distinctStates == 1024} under {@code LAYERED}, {@code 384} of the states sit as layers and
 * the remaining {@code 640} sit as isolated scattered blocks. The point is a legitimate measurement
 * of a chunk, it is simply not a measurement of a layered chunk, and a curve drawn through it has
 * to say so.
 * </p>
 *
 * <h2>Both chunks have to agree before anything is measured</h2>
 * <p>
 * This module holds a comparison worthless unless it first shows that both sides produce the same
 * result, the pattern {@code LightEngineComparisonBenchmark#verifyBothEnginesAgree} established.
 * {@link #setUp()} does that twice — once for the fill and once for the fixed point the
 * measurements actually run on — through {@code MinestomChunks#assertSameBlocks}, which walks all
 * {@code 16 * 16 * 16 * sectionCount} positions plus both heightmaps and throws with the first
 * disagreeing position. A throwing setup aborts the trial, which is the point: a number that came
 * from comparing two different worlds must never reach the results file.
 * </p>
 * <p>
 * Three further checks guard the failures that a block walk alone would not catch.
 * {@link #verifyArms()} asserts that the two instances really handed out a {@code DynamicChunk} and
 * a {@code FalcoChunk}, because a changed chunk supplier on either side would turn the whole
 * benchmark into a comparison of a type against itself and would do so while every other assertion
 * still passed. {@code MinestomChunks#assertNotAllAir} rejects a chunk whose fill silently did not
 * take, which is the failure mode that produces the best numbers this benchmark will ever report
 * and reports them for an empty palette. And {@link #tearDown()} repeats that air check after the
 * measurements, so a benchmark method that emptied the chunk it was measuring is caught by the same
 * rule that would have caught an empty fill.
 * </p>
 *
 * <h2>How to read the numbers</h2>
 * <p>
 * The measured operation of {@link #minestomSetBlock()}, {@link #falcoSetBlock()},
 * {@link #minestomGetBlock()} and {@link #falcoGetBlock()} is the whole batch of
 * {@value #SCATTER_COUNT} blocks, not a single block. That is a deliberate departure from an
 * {@code @OperationsPerInvocation} split: this module reports in microseconds throughout, and a
 * per-block figure would print as five leading zeroes and lose every digit that carries
 * information. Divide a reported figure by {@value #SCATTER_COUNT} and multiply by {@code 1000} to
 * get nanoseconds per block. {@link #minestomCopy()}, {@link #falcoCopy()},
 * {@link #minestomHeightmapRefresh()} and {@link #falcoHeightmapRefresh()} measure one whole chunk
 * operation each, so their figures need no conversion.
 * </p>
 * <p>
 * {@code gc.alloc.rate.norm} is the second half of the result and for the copy it is the more
 * important half: the build enables the {@code gc} profiler for every run, and the claim this
 * benchmark exists to support is about bytes as much as about time. The retained size that claim
 * also needs is not measurable here — an allocation rate is not a footprint — and is taken by the
 * JOL tests of B-01 alongside these runs.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * The full grid is {@code 6 x 3} parameter combinations over the {@code 10} methods of this class,
 * of which {@code 8} belong in a baseline; that is {@code 144} scenarios, and at the three forks a
 * citable run needs it is {@code 81} minutes of wall clock. The figure is derived from the scouting
 * run rather than guessed: {@code 3} forks of {@code 5 + 5} iterations of one second, plus the
 * {@code 1,3 s} per fork that run measured for this class, is {@code 33,9 s} a scenario. Both axes
 * are worth having, but they are rarely worth having at once: the state count is the axis that
 * decides how a palette behaves, and
 * the shape is the axis that decides whether a result generalises beyond one kind of world. The
 * recommended pair of runs takes the state count under the shape closest to real terrain and the
 * shape at the two ends of the state count:
 * </p>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:jmhJar
 * java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar "ChunkComparisonBenchmark" \
 *     -p fillShape=RANDOM_RUNS -f 3 -wi 5 -i 5 -prof gc
 * java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar "ChunkComparisonBenchmark" \
 *     -p fillShape=UNIFORM,LAYERED -p distinctStates=1,1024 -f 3 -wi 5 -i 5 -prof gc
 * }</pre>
 * <p>
 * The full grid, for the run that produces the published table. It is driven by
 * {@code docs/benchmarks/full-run.sh}, which is where it belongs rather than in a shell history,
 * and it excludes the two arms that may not be quoted:
 * </p>
 * <pre>{@code
 * java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar "ChunkComparisonBenchmark" \
 *     -e '\.(minestomCopy|falcoCopy)$' \
 *     -f 3 -wi 5 -i 5 -prof gc -foe true -rf json \
 *     -rff docs/benchmarks/baseline-<date>/ChunkComparisonBenchmark.json
 * }</pre>
 * <p>
 * The exclusion is anchored at the end of the method name so that {@link #minestomCopyIsolated()}
 * and {@link #falcoCopyIsolated()}, the pair whose ratio does mean something, stay in. The two
 * excluded arms are run separately, into a file whose name says it is not a baseline; see
 * {@code docs/benchmarks/README.md} for what may and may not be taken from that run.
 * </p>
 *
 * <h2>Why three forks and not one</h2>
 * <p>
 * The heap is raised over the {@code 512m} the module uses elsewhere because a server process, two
 * instances and two full height chunks live in the fork, and because {@link #minestomCopy()}
 * allocates a complete chunk on every invocation.
 * </p>
 * <p>
 * The fork count was {@code 1}, on the argument that a second fork doubles a startup far more
 * expensive than the measurement it precedes. The scouting run of 2026-08-01 measured that startup
 * and it is not: twenty four combinations of this class at {@code -f 1 -wi 2 -i 3} took
 * {@code 151 s} of wall clock against {@code 120 s} of iterations, which puts the whole start —
 * JVM launch, {@code MinecraftServer.init()}, both instances, both chunks and the equality proof of
 * {@link #setUp()} — at about {@code 1,3 s} per fork. A fork at the configuration above runs
 * {@code 10 s} of iterations, so the start is thirteen percent of it and the argument for a single
 * fork does not survive its own measurement.
 * </p>
 * <p>
 * What one fork does cost is the ability to see anything at all about variance between JVM
 * launches, and this project has a documented case of that mattering: a two-thread row of
 * {@code RegionFileComparisonBenchmark} in the README did not reproduce on an independent run of
 * the identical configuration, moving from a usable interval to a half width {@code 8,3} times its
 * own mean. At one fork the {@code +-} JMH prints covers variance between iterations of one launch
 * and is silent about the rest. Three is the smallest count at which a disagreeing fork has a
 * majority to disagree with, and JMH keeps the per fork raw data in the JSON so it can be read
 * rather than guessed at.
 * </p>
 * <p>
 * {@code -jvmArgs} is not passed. The heap is already in the {@code @Fork} annotation as
 * {@code jvmArgsAppend}, and restating it on the command line replaces the inherited base arguments
 * rather than adding to them, which is a different JVM than the annotation describes.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.4.0
 * @since 0.4.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class ChunkComparisonBenchmark {

    /**
     * The amount of distinct positions the scattered read and write batches touch.
     * <p>
     * One section worth of positions, spread over the whole chunk rather than over one section.
     * The count is large enough that a single invocation crosses every section of a full height
     * chunk several times and cannot sit in one cache line, and small enough that a batch stays far
     * below the {@code 98304} positions of the chunk, so the measured access pattern stays sparse
     * instead of degenerating into a full walk. It matches the batch size of B-14 so the two
     * benchmarks can be read against each other.
     * </p>
     */
    public static final int SCATTER_COUNT = 4096;

    /**
     * The chunk position both measured chunks are created at.
     */
    private static final int CHUNK_POSITION = 0;

    /**
     * The amount of distinct block states the measured chunk holds.
     * <p>
     * The lower end is where a palette degenerates: a chunk of one state stores no backing array at
     * all. The upper end is above the {@code 964} distinct blocks the registry of the pinned build
     * offers, so the fixture falls back to further states of the same blocks there — the two halves
     * of a curve drawn over this axis answer a slightly different question and must not be read as
     * one.
     * </p>
     */
    @Param({"1", "2", "16", "64", "256", "1024"})
    public int distinctStates;

    /**
     * The arrangement the states are written in.
     */
    @Param({"UNIFORM", "LAYERED", "RANDOM_RUNS"})
    public MinestomChunks.FillShape fillShape;

    private InstanceContainer container;
    private FalcoInstance falco;
    private Chunk minestomChunk;
    private Chunk falcoChunk;
    private int[] scatterX;
    private int[] scatterY;
    private int[] scatterZ;
    private Block[] scatterBlocks;

    /**
     * Builds the two chunks, brings them to the state the measurements run on and proves that they
     * are identical.
     * <p>
     * The order is the argument of this method. The two instances are built first, then the two
     * chunks, then the arms are checked so a changed chunk supplier fails before anything expensive
     * happens. Both chunks are filled from the same seed and compared position by position, which
     * covers the fixture. The scatter batch is then applied to both and the comparison is repeated,
     * which covers the fixed point the measured invocations actually run on. Only then are the two
     * anti tautology checks taken, because both of them are statements about the finished state.
     * </p>
     *
     * @throws IllegalStateException if the two chunks are not of the expected types, hold different
     *                               content, hold nothing but air or disagree on their state count
     */
    @Setup(Level.Trial)
    public void setUp() {
        MinestomChunks.ensureServer();
        this.container = MinestomChunks.newContainer();
        this.falco = MinestomChunks.newFalcoInstance();
        this.minestomChunk = MinestomChunks.newChunk(this.container, CHUNK_POSITION, CHUNK_POSITION);
        this.falcoChunk = MinestomChunks.newChunk(this.falco, CHUNK_POSITION, CHUNK_POSITION);
        verifyArms();

        MinestomChunks.fill(this.minestomChunk, this.distinctStates, this.fillShape);
        MinestomChunks.fill(this.falcoChunk, this.distinctStates, this.fillShape);
        MinestomChunks.assertSameBlocks(this.minestomChunk, this.falcoChunk);

        buildScatter();
        writeScatter(this.minestomChunk);
        writeScatter(this.falcoChunk);
        MinestomChunks.assertSameBlocks(this.minestomChunk, this.falcoChunk);

        MinestomChunks.assertNotAllAir(this.minestomChunk);
        MinestomChunks.assertNotAllAir(this.falcoChunk);
        verifyStateCount();
    }

    /**
     * Checks that the measured chunks still hold blocks and releases the two instances.
     * <p>
     * The air check is repeated here rather than only in the setup because the measured operations
     * write. A benchmark method that ended up clearing the chunk it measures would report the
     * fastest numbers of the whole grid, and nothing in the setup could have seen it. An instance
     * that stays registered leaks into every following trial of the fork together with its chunks,
     * which is why the release is unconditional.
     * </p>
     *
     * @throws IllegalStateException if a measured chunk holds nothing but air afterwards
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        try {
            MinestomChunks.assertNotAllAir(this.minestomChunk);
            MinestomChunks.assertNotAllAir(this.falcoChunk);
        } finally {
            MinestomChunks.release(this.container);
            MinestomChunks.release(this.falco);
        }
    }

    /**
     * Measures a scattered batch of {@value #SCATTER_COUNT} writes into a {@code DynamicChunk}.
     */
    @Benchmark
    public void minestomSetBlock() {
        writeScatter(this.minestomChunk);
    }

    /**
     * Measures a scattered batch of {@value #SCATTER_COUNT} writes into a {@code FalcoChunk}.
     */
    @Benchmark
    public void falcoSetBlock() {
        writeScatter(this.falcoChunk);
    }

    /**
     * Measures a scattered batch of {@value #SCATTER_COUNT} reads from a {@code DynamicChunk}.
     *
     * @return the sum of the read state ids
     */
    @Benchmark
    public int minestomGetBlock() {
        return readScatter(this.minestomChunk);
    }

    /**
     * Measures a scattered batch of {@value #SCATTER_COUNT} reads from a {@code FalcoChunk}.
     *
     * @return the sum of the read state ids
     */
    @Benchmark
    public int falcoGetBlock() {
        return readScatter(this.falcoChunk);
    }

    /**
     * Measures a full copy of a {@code DynamicChunk}.
     *
     * <h2>This arm is not comparable to {@link #falcoCopy()} and its number must not be quoted</h2>
     * <p>
     * A copy constructs a chunk, and constructing a chunk for an {@code InstanceContainer} leaves an
     * entry in the viewer cache of its entity tracker that nothing removes. The cache is keyed by a
     * record whose {@code equals} compares the shared instance list by identity, and
     * {@code InstanceContainer#getSharedInstances} hands out a fresh {@code unmodifiableList} every
     * time, so no key ever matches one already there. Every invocation of this method therefore
     * inserts, and because that record leaves the value based {@code hashCode} in force while no two
     * keys compare equal, the insertions pile into a single bin.
     * </p>
     * <p>
     * What this method reports is consequently the cost of a copy plus the cost of a hash map that
     * grows for the length of the trial. {@link #falcoCopy()} does not pay it, for the sole reason
     * that a {@code FalcoInstance} is not an {@code InstanceContainer} and is handed the
     * {@code List.of()} singleton instead, whose identity is stable. The two implementations differ
     * only in that the Falco one additionally carries over its tickable counter, one {@code int}
     * assignment, so on the code alone this arm should be the faster of the two, not slower by more
     * than an order of magnitude.
     * </p>
     * <p>
     * {@code ChunkViewerCacheLeakTest} establishes the mechanism and its linearity.
     * {@link #minestomCopyIsolated()} is the arm that separates the copy from the leak, and it and
     * {@link #falcoCopyIsolated()} are the only pair here whose ratio means anything.
     * </p>
     *
     * <h2>Why this arm is kept out of the baseline run</h2>
     * <p>
     * The time this arm reports is not a value but a slope, and the scouting run of 2026-08-01 shows
     * it directly. Its three measurement iterations read {@code 296}, {@code 313} and
     * {@code 364 us/op} at {@code distinctStates = 64} and {@code 282}, {@code 304} and
     * {@code 390 us/op} at {@code 1024} — rises of {@code 23 %} and {@code 38 %} within one fork —
     * while every control arm on the same fork was flat to within two percent
     * ({@link #falcoSetBlock()} at {@code 1024}: {@code 106,86}, {@code 107,19}, {@code 106,64}).
     * The map is still growing while the mean is being taken, so the mean depends on how long the
     * iteration ran and a different {@code -i} produces a different answer. The same run shows the
     * arm is not measuring a copy at all: its mean barely moves across the state count
     * ({@code 363}, {@code 325}, {@code 325 us/op}) where {@link #falcoCopy()} scales with the
     * content it copies ({@code 7,8}, {@code 16,2}, {@code 22,1 us/op}).
     * </p>
     * <p>
     * The allocation column of this arm is a different matter and is worth publishing. This arm
     * minus {@link #falcoCopy()} in {@code gc.alloc.rate.norm} was {@code 257,1}, {@code 257,3} and
     * {@code 257,3 B/op} at the three measured state counts, at an error below {@code 0,6 B}: the
     * per copy cost of the leak, constant in what the chunk holds. {@code docs/benchmarks/full-run.sh}
     * therefore runs this arm and {@link #falcoCopy()} only under {@code --with-leak-arms}, into a
     * separate file whose name records that its time column is not a baseline.
     * </p>
     *
     * @return the created copy
     */
    @Benchmark
    public Chunk minestomCopy() {
        return copy(this.minestomChunk, this.container);
    }

    /**
     * Measures a full copy of a {@code FalcoChunk}.
     * <p>
     * Read the note on {@link #minestomCopy()} before comparing the two. This arm is the one that is
     * free of the viewer cache leak, which makes the pair incomparable rather than making this side
     * fast. Taken on its own the number is sound; taken as a ratio it is not.
     * </p>
     *
     * @return the created copy
     */
    @Benchmark
    public Chunk falcoCopy() {
        return copy(this.falcoChunk, this.falco);
    }

    /**
     * Measures a full copy of a {@code DynamicChunk} into an instance that is not a container.
     *
     * <h2>Why the destination is the Falco instance</h2>
     * <p>
     * {@link #minestomCopy()} and {@link #falcoCopy()} cannot be divided by one another, because the
     * first pays for a viewer cache entry that leaks on every chunk construction and the second does
     * not. The leak is a property of the destination instance rather than of the chunk being copied:
     * a chunk asks the entity tracker of the instance it is being built for, and only an
     * {@code InstanceContainer} hands that tracker a list whose identity changes each time.
     * </p>
     * <p>
     * {@code Chunk#copy(Instance, int, int)} takes the destination as a parameter, so both
     * implementations can be pointed at the same instance that is not a container. Both then pay the
     * same cache cost, which is one lookup that hits, and what remains between them is the work the
     * two implementations actually do. That is what this arm and {@link #falcoCopyIsolated()}
     * measure, and they are the only pair of copy arms in this class whose ratio means anything.
     * </p>
     * <p>
     * The isolation costs realism and says so: a server copies a chunk within its own world, and if
     * that world is an {@code InstanceContainer} it really does pay what {@link #minestomCopy()}
     * reports. The pair is kept for that reason. This arm answers what a copy costs, the other one
     * answers what it costs today.
     * </p>
     *
     * @return the created copy
     */
    @Benchmark
    public Chunk minestomCopyIsolated() {
        return copy(this.minestomChunk, this.falco);
    }

    /**
     * Measures a full copy of a {@code FalcoChunk} into an instance that is not a container.
     * <p>
     * The counterpart of {@link #minestomCopyIsolated()}, against which it is comparable. On the
     * code alone this arm is expected to be the slower of the two by a margin too small to resolve,
     * because {@code FalcoChunk#copy} additionally carries over its tickable counter — one
     * {@code int} assignment — while {@code DynamicChunk#copy} copies only {@code entries}. Until
     * this task the extra work was a whole {@code Int2ObjectOpenHashMap} copy, which is why this
     * paragraph used to expect a small but real margin rather than none. A result in the other
     * direction by more than noise means this pair is measuring something other than the copy as
     * well.
     * </p>
     *
     * @return the created copy
     */
    @Benchmark
    public Chunk falcoCopyIsolated() {
        return copy(this.falcoChunk, this.falco);
    }

    /**
     * Measures a full heightmap refresh of a {@code DynamicChunk}.
     *
     * @return the sum of the refreshed heights of both heightmaps
     */
    @Benchmark
    public int minestomHeightmapRefresh() {
        return refreshHeightmaps(this.minestomChunk);
    }

    /**
     * Measures a full heightmap refresh of a {@code FalcoChunk}.
     *
     * @return the sum of the refreshed heights of both heightmaps
     */
    @Benchmark
    public int falcoHeightmapRefresh() {
        return refreshHeightmaps(this.falcoChunk);
    }

    /**
     * Writes the scatter batch into a chunk.
     * <p>
     * The write lock is taken once around the whole batch rather than once per block, which is what
     * {@code InstanceContainer#UNSAFE_setBlock} does. That is deliberate: an uncontended
     * {@code ReentrantReadWriteLock} acquisition is a compare and swap on a field every write of
     * the batch would touch again, and taking it {@value #SCATTER_COUNT} times would fold a lock
     * measurement into a storage measurement. The lock model is the subject of its own benchmark,
     * B-16, where it is the thing being varied instead of a constant overhead on both arms.
     * </p>
     * <p>
     * The method is shared by both arms on purpose. It is the same code, the same loop and the same
     * call site for {@code DynamicChunk} and for {@code FalcoChunk}, so a difference between the
     * two arms cannot come from the way they are driven. Each benchmark method also runs in its own
     * fork, so the call site sees exactly one receiver type per measurement and neither arm pays
     * for the existence of the other.
     * </p>
     *
     * @param chunk the chunk to write into
     */
    private void writeScatter(Chunk chunk) {
        chunk.lockWriteLock();
        try {
            for (int index = 0; index < SCATTER_COUNT; index++) {
                chunk.setBlock(this.scatterX[index], this.scatterY[index], this.scatterZ[index],
                        this.scatterBlocks[index]);
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Reads the scatter batch from a chunk and sums the state ids it finds.
     * <p>
     * The read runs with {@code Block.Getter.Condition#NONE}, which is the condition
     * {@code Instance#getBlock} uses and therefore the one production reaches. It is also the more
     * expensive of the two: {@code CONDITION#TYPE} answers from the palette alone, while
     * {@code NONE} first consults the block entity map. That map is empty here, because the fixture
     * excludes block entities from every fill, so what the condition really measures is the guard
     * in front of the map — which is exactly the branch a real chunk of plain terrain takes on
     * every read.
     * </p>
     * <p>
     * The sum exists to keep the reads alive. Without a consumed result the whole loop is dead code
     * and a compiler is free to delete it, which would turn the arm into a measurement of an empty
     * loop and would do so silently.
     * </p>
     *
     * @param chunk the chunk to read from
     * @return the sum of the read state ids
     */
    private int readScatter(Chunk chunk) {
        int sum = 0;

        chunk.lockReadLock();
        try {
            for (int index = 0; index < SCATTER_COUNT; index++) {
                final Block block = chunk.getBlock(this.scatterX[index], this.scatterY[index],
                        this.scatterZ[index], Block.Getter.Condition.NONE);
                sum += Objects.requireNonNullElse(block, Block.AIR).stateId();
            }
        } finally {
            chunk.unlockReadLock();
        }
        return sum;
    }

    /**
     * Copies a chunk to a neighbouring position of the same instance.
     * <p>
     * The copy is placed at a position the instance does not hold and is never registered, so it
     * becomes garbage as soon as the blackhole has consumed it. That is the intent: this arm is
     * measured for its allocation as much as for its time, and a copy that stayed reachable would
     * fill the heap of the fork within seconds instead.
     * </p>
     * <p>
     * The read lock is required rather than optional. Both {@code DynamicChunk#copy} and
     * {@code FalcoChunk#copy} open with {@code assertReadLock()}, so a run with assertions enabled
     * would fail here without it.
     * </p>
     *
     * @param chunk    the chunk to copy
     * @param instance the instance the copy is created for
     * @return the created copy
     */
    private Chunk copy(Chunk chunk, Instance instance) {
        chunk.lockReadLock();
        try {
            return chunk.copy(instance, CHUNK_POSITION + 1, CHUNK_POSITION);
        } finally {
            chunk.unlockReadLock();
        }
    }

    /**
     * Recomputes both heightmaps of a chunk from scratch and sums the heights that come out.
     * <p>
     * This is the body of the private {@code calculateFullHeightmap} of whichever chunk it is
     * handed, reproduced through public API. The method itself cannot be called from outside the
     * chunk, and the public
     * {@code Heightmap#refresh(int)} that it uses returns immediately once a heightmap has been
     * refreshed, with no public way to arm it again. {@code Heightmap#refresh(int, int, int)} is
     * the same scan per column without that guard, so driving it over all {@code 256} columns
     * performs the identical work and performs it on every invocation instead of only on the first.
     * </p>
     * <p>
     * Both heightmaps are refreshed because both of them are refreshed by the code being modelled,
     * and because they differ in their predicate rather than in their scan: one counts every block
     * that blocks motion, the other every block that is not air. Measuring only one of them would
     * halve a cost that a real chunk always pays twice.
     * </p>
     * <p>
     * The write lock is the lock {@code calculateFullHeightmap} asserts, so it is the lock taken
     * here, even though the scan itself only reads the palettes and writes into the height array of
     * the heightmap. Modelling the real path matters more than taking the cheaper lock, and it
     * costs a single uncontended acquisition per invocation against a scan of {@code 256} columns.
     * </p>
     * <p>
     * The starting height is recomputed on every invocation rather than cached, because
     * {@code calculateFullHeightmap} recomputes it too. It walks the sections from the top until it
     * meets one with a block in it, which on a filled chunk stops at the first section.
     * </p>
     * <p>
     * Each arm computes that height the way its own chunk computes it, which is the one place where
     * this helper deliberately does not run the same code on both sides.
     * {@code DynamicChunk#calculateFullHeightmap} calls
     * {@code Heightmap#getHighestBlockSection(Chunk)}, which walks the chunk through
     * {@code Chunk#getSection(int)}; {@code FalcoChunk#calculateFullHeightmap} calls
     * {@link FalcoChunk#highestBlockSection()}, which walks its storage through a view and creates
     * nothing. Driving both arms through the static helper would be the same code but the wrong
     * model — it would charge the Falco arm a scan its chunk does not perform, on sections its chunk
     * does not create. The two return the same number on two chunks holding the same blocks, which
     * is asserted rather than assumed in {@code FalcoChunkEquivalenceTest}, and a disagreement
     * surfaces here as a differing sum and aborts the trial.
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
     * Draws the positions and the blocks of the scatter batch.
     * <p>
     * The positions are distinct. A batch that could hit a position twice would measure a
     * guaranteed cache hit on the repetition, which is the opposite of what a scattered access
     * pattern is for, and its second write would delete the block its first write placed — which
     * would break the guarantee that every state of the set survives the batch and would take the
     * exact state count check of {@link #verifyStateCount()} with it. Duplicates are therefore
     * rejected through a bit set over the block index rather than tolerated.
     * </p>
     * <p>
     * The blocks cycle through the same set the fill drew from, so a batch cannot introduce a state
     * that the {@code distinctStates} axis did not ask for. The seed is the one of the module, so
     * the same parameter combination produces the same batch on every run and on every machine.
     * </p>
     */
    private void buildScatter() {
        final int minY = this.minestomChunk.getMinSection() * Chunk.CHUNK_SECTION_SIZE;
        final int height = (this.minestomChunk.getMaxSection() - this.minestomChunk.getMinSection())
                * Chunk.CHUNK_SECTION_SIZE;
        final Block[] blocks = MinestomChunks.distinctBlocks(this.distinctStates);
        final Random random = new Random(BenchmarkConstants.SEED);
        final BitSet taken = new BitSet(Chunk.CHUNK_SIZE_X * Chunk.CHUNK_SIZE_Z * height);

        this.scatterX = new int[SCATTER_COUNT];
        this.scatterY = new int[SCATTER_COUNT];
        this.scatterZ = new int[SCATTER_COUNT];
        this.scatterBlocks = new Block[SCATTER_COUNT];

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
            this.scatterX[index] = x;
            this.scatterY[index] = minY + y;
            this.scatterZ[index] = z;
            this.scatterBlocks[index] = blocks[index % blocks.length];
        }
    }

    /**
     * Verifies that the two instances handed out the two chunk types this benchmark compares.
     * <p>
     * Both chunks come from the default chunk supplier of their instance, which is the subject of
     * the comparison rather than a setting of it. A supplier that changed on either side — in
     * Minestom, in {@code FalcoInstance} or in the fixture — would leave every other assertion of
     * this class intact while the benchmark quietly measured one type against itself and reported
     * two arms that agree perfectly. That is the one failure this benchmark could not survive
     * undetected, because agreement is exactly the result it expects.
     * </p>
     * <p>
     * The Minestom arm used to carry a second clause, {@code || minestomChunk instanceof FalcoChunk},
     * and it was load bearing while {@code FalcoChunk} extended {@code DynamicChunk}: a Falco chunk
     * passed the first check back then. Since stage 1 of the block storage work the two are
     * siblings under {@code Chunk}, so no object can satisfy both tests and the clause could never
     * fire again. It is gone rather than kept as insurance, because a check that cannot fail reads
     * like a check that does.
     * </p>
     *
     * @throws IllegalStateException if either chunk is not of the type its arm claims to measure
     */
    private void verifyArms() {
        if (!(this.minestomChunk instanceof DynamicChunk)) {
            throw new IllegalStateException("The Minestom arm has to measure a plain DynamicChunk but the"
                    + " container handed out a " + this.minestomChunk.getClass().getName());
        }
        if (!(this.falcoChunk instanceof FalcoChunk)) {
            throw new IllegalStateException("The Falco arm has to measure a FalcoChunk but the instance"
                    + " handed out a " + this.falcoChunk.getClass().getName());
        }
    }

    /**
     * Verifies that both chunks hold exactly as many distinct block states as the axis asked for.
     * <p>
     * The second half of the anti tautology check. {@code assertNotAllAir} only rules out the
     * empty chunk; this rules out the chunk that took the fill but not the parameter. A trial that
     * ran at {@code distinctStates == 1024} while its chunk held sixteen would produce a perfectly
     * plausible number for a point of the curve that was never built, and no walk over the blocks
     * would notice, because both sides would hold the same wrong content.
     * </p>
     * <p>
     * The comparison is exact rather than a lower bound, which the scatter batch is what makes
     * possible: it writes every block of the set into a distinct position, so every shape reaches
     * the full count. The fixture counts air as a state, and the fills of this module write to
     * every position of the chunk, so no air is left to inflate the count.
     * </p>
     *
     * @throws IllegalStateException if a chunk holds a different amount of distinct states than
     *                               requested, or if the two chunks disagree
     */
    private void verifyStateCount() {
        final int minestomStates = MinestomChunks.countDistinctStates(this.minestomChunk);
        final int falcoStates = MinestomChunks.countDistinctStates(this.falcoChunk);

        if (minestomStates != falcoStates) {
            throw new IllegalStateException("The chunks hold a different amount of distinct states:"
                    + " DynamicChunk " + minestomStates + " against FalcoChunk " + falcoStates);
        }
        if (minestomStates != this.distinctStates) {
            throw new IllegalStateException("The measured chunks hold " + minestomStates
                    + " distinct block states but the axis asked for " + this.distinctStates
                    + " under the shape " + this.fillShape + ", so the run would report a point of the"
                    + " curve it never built");
        }
    }
}
