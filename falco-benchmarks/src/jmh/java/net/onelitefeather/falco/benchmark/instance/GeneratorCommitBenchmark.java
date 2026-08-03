package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.palette.Palette;
import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import net.onelitefeather.falco.instance.FalcoInstance;
import net.onelitefeather.falco.instance.PaletteCompaction;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The {@link GeneratorCommitBenchmark} class measures what it costs to call
 * {@code Palette#optimize(Optimization.SIZE)} on the palettes of a chunk a generator has just
 * filled, and states that cost next to the copy it follows rather than on its own.
 * <p>
 * The byte side of this question is already answered and is not measured again here. A chunk whose
 * sections all ended up in direct mode retains {@code 203 840} bytes against {@code 84 800} for the
 * same content stored indirectly, a factor of {@code 2,4}, and {@code Palette#optimize} has no caller
 * anywhere in the main source tree of Minestom. What nothing in this repository states is the price
 * in time. A saving of more than half the memory of a generated chunk is worth a great deal of time,
 * but "a great deal" is not a measurement, and this stage refuses to book a gain whose cost is
 * unknown.
 * </p>
 * <p>
 * What the byte figure does not say, and what this class had to establish before it could measure
 * anything, is when the conversion between those two widths is available at all.
 * {@code PaletteImpl#downsizeWithPalette} opens with
 * {@code if (newBpe >= bpe || newBpe > maxBitsPerEntry) return;}, and {@code maxBitsPerEntry} is
 * {@code 8} for blocks. A section that went direct because it genuinely holds more than the
 * {@code 256} distinct states an indirect palette can index therefore cannot be brought back, no
 * matter how much time is spent on it. {@code optimize} recovers width only where the palette is
 * wider than its own content needs. The three parameter values below are chosen to put one trial on
 * each side of that line, and the reader of the numbers has to carry the distinction: the factor of
 * {@code 2,4} is what the two widths cost, not what this call can move between them.
 * </p>
 *
 * <h2>The five arms and why the middle ones exist</h2>
 * <p>
 * {@link #commitPlain()} copies the staged palettes into the sections of a chunk, which is what
 * {@code ChunkGeneration#apply} does today. {@link #commitOptimized()} does the same and then
 * optimises each palette it wrote. The difference between the two is the whole answer, and it is a
 * difference rather than an absolute on purpose: a number for {@code optimize} alone would be
 * compared against nothing, while the commit is the step it was added to.
 * </p>
 * <p>
 * {@link #commitGuarded()} is the arm this stage ships. It asks {@code PaletteCompaction} whether the
 * palette it just wrote can still be narrowed and calls {@code optimize} only then, which is a
 * bounded sample of the entries against the full walk {@code optimize} would do before it could reach
 * the same conclusion. Its distance from {@link #commitOptimized()} at {@code 1024} distinct states is
 * what the guard saves, and its distance at {@code 64} is what the guard costs where it decides to go
 * ahead; both belong in the same table, because a guard is only worth reporting with the price it
 * charges the case it does not help.
 * </p>
 * <p>
 * {@link #optimizeAlreadyPacked()} is the control. It optimises palettes which are already at their
 * minimum width, which is the case a server pays on every chunk whose generator did not produce a
 * wide palette in the first place. Except where those palettes collapsed to the single value mode,
 * where {@code optimize} returns on its opening {@code bitsPerEntry == 0}, {@code PaletteImpl#optimize}
 * still walks all four thousand and ninety six entries through {@code getAll} to collect the unique
 * values before it can decide that there is nothing to do, so this arm is not free and its distance
 * from zero is what a generator pays for chunks the optimisation cannot help.
 * </p>
 *
 * <h2>Why the state count is the axis</h2>
 * <p>
 * {@code PaletteImpl#optimize} branches on the number of distinct values it finds: one value collapses
 * to the single value mode through {@code fill}, and anything else goes to {@code downsizeWithPalette}
 * under {@code Optimization.SIZE}. The cost of the collection walk is the same in both cases and the
 * cost of the rewrite is not, so a single state count would answer one of the two questions and hide
 * the other. The axis is the one every other chunk benchmark of this module uses, cut down to the
 * three points that separate the branches.
 * </p>
 * <p>
 * The third point separates one branch further, and it is the one the plan needs most.
 * {@code 1024} distinct states put every section of the fixture past the indirect ceiling, so
 * {@code downsizeWithPalette} returns on its guard and the arm pays the walk and the set for a rewrite
 * that never happens. At {@code 1024} the control and the optimised commit therefore measure the same
 * work, and that they come out equal is the readout, not a defect: it is the case in which the
 * optimisation costs its full price and returns nothing.
 * </p>
 *
 * <h2>Why the fixture is re-staged before it is measured</h2>
 * <p>
 * The chunk this class fills is filled through {@code Chunk#setBlock}, and the palettes that come out
 * of it are not the palettes Task 6 will optimise.
 * {@link #asAGeneratorWouldHaveWrittenIt(Palette)} states the difference and removes it by rewriting
 * the content through {@code Palette#setAll}, the method a generator's commit ends in. It is worth
 * naming in one line here as well: a palette grown one block at a time carries at most a bit of slack,
 * while a palette a generator wrote is at the direct width whatever it holds. Measuring the first and
 * reporting it as the second would have understated both what the optimisation costs and what it
 * returns, at two of the three points on the axis. The widths the staging produces are {@code 0}
 * against {@code 0} at one state, {@code 15} against {@code 6} at sixty four, and {@code 15} against
 * {@code 15} at one thousand and twenty four.
 * </p>
 *
 * <h2>Running it</h2>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:jmhJar
 * java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
 *     "GeneratorCommitBenchmark" -p distinctStates=1,64,1024 -f 3 -wi 5 -i 5 -prof gc
 * }</pre>
 * <p>
 * {@code -prof gc} is not optional. {@code downsizeWithPalette} allocates a new backing array and the
 * allocation is part of what the optimisation costs, so a run without the profiler reports half the
 * price.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.2.1
 * @since 0.4.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class GeneratorCommitBenchmark {

    /**
     * The amount of distinct block states the staged palettes are filled from.
     */
    @Param({"1", "64", "1024"})
    public int distinctStates;

    /**
     * The instance the fixture chunks are built in.
     */
    private FalcoInstance instance;

    /**
     * The palettes at the width a generator would have left them, which every arm copies from and
     * never writes to.
     */
    private List<Palette> staged;

    /**
     * The same palettes already reduced to their minimum width, for the control arm.
     */
    private List<Palette> packed;

    /**
     * The sections the arms commit into, rebuilt per invocation is too slow, so they are reused and
     * overwritten; a commit is a full overwrite of every entry, so nothing carries over.
     */
    private Section[] target;

    @Setup(Level.Trial)
    public void setUp() {
        MinestomChunks.ensureServer();
        this.instance = MinestomChunks.newFalcoInstance();

        final Chunk source = MinestomChunks.newChunk(this.instance, 0, 0);
        MinestomChunks.fill(source, this.distinctStates, MinestomChunks.FillShape.RANDOM_RUNS,
                BenchmarkConstants.SEED);

        final List<Section> sections = source.getSections();

        if (sections.size() != BenchmarkConstants.OVERWORLD_SECTIONS) {
            throw new IllegalStateException("The fixture chunk holds " + sections.size()
                    + " sections but the benchmark is written for "
                    + BenchmarkConstants.OVERWORLD_SECTIONS);
        }
        this.staged = new ArrayList<>(sections.size());
        this.packed = new ArrayList<>(sections.size());
        this.target = new Section[sections.size()];

        for (int index = 0; index < sections.size(); index++) {
            final Palette blocks = sections.get(index).blockPalette();

            final Palette alreadyPacked = blocks.clone();
            alreadyPacked.optimize(Palette.Optimization.SIZE);

            this.staged.add(asAGeneratorWouldHaveWrittenIt(blocks));
            this.packed.add(alreadyPacked);
            this.target[index] = new Section();
        }
        verifyTheFixtureIsStagedLikeAGenerator();
    }

    /**
     * Rewrites the content of a section into a fresh palette the way a generator writes it.
     * <p>
     * The fixture is built through {@code Chunk#setBlock}, because that is the only write path every
     * chunk type of this module shares, and a palette grown one block at a time is never much wider
     * than its content needs. A generator does not write that way and does not leave that shape.
     * {@code UnitModifier#setAllRelative} ends in {@code PaletteImpl#setAll}, and that method decides
     * between two branches after it has read the whole supplier into its cache: a supplier which
     * answered one constant value goes to {@code fill(fillValue)} and leaves the palette in the single
     * value mode, and every other supplier goes to {@code makeDirect()} — unconditionally, without
     * looking at how many distinct values it actually saw. A generated section is therefore at the
     * direct width because of how it was written, not because of what it holds, and that is the whole
     * reason {@code optimize} has something to reclaim after a generation and almost nothing to
     * reclaim after a sequence of block writes.
     * </p>
     * <p>
     * Staging the fixture at the width {@code setBlock} produced would measure the optimisation on
     * input Task 6 will never hand it. So this method does not imitate either branch — it takes the
     * same door the generator takes. {@code Palette#setAll(EntrySupplier)} is public API, the fresh
     * {@code Palette.blocks()} below is the palette an unwritten section carries, and the supplier is
     * the content of the fixture section. Which of the two branches runs is Minestom's decision here,
     * not this class's, and that is what makes {@link #verifyTheFixtureIsStagedLikeAGenerator()} a
     * real guard rather than a restatement of a constant: were {@code setAll} to stop calling
     * {@code makeDirect}, the staged width would follow it and the guard would say so. Re-enacting the
     * branches through {@code Optimization.SPEED} and {@code Optimization.SIZE} — the shape this method
     * had before — would have produced the same two widths by construction and could not have noticed.
     * </p>
     *
     * @param generated the palette of the fixture section, read and never written
     * @return a new palette holding the same content at the width {@code PaletteImpl#setAll} leaves it
     */
    private static Palette asAGeneratorWouldHaveWrittenIt(Palette generated) {
        final Palette written = Palette.blocks();

        written.setAll(generated::get);
        return written;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        MinestomChunks.release(this.instance);
        this.instance = null;
    }

    /**
     * Measures the commit as {@code ChunkGeneration#apply} performs it today.
     *
     * @return the sections that were written, so that nothing can be eliminated
     */
    @Benchmark
    public Section[] commitPlain() {
        for (int index = 0; index < this.target.length; index++) {
            this.target[index].blockPalette().copyFrom(this.staged.get(index));
        }
        return this.target;
    }

    /**
     * Measures the same commit with the optimisation this stage adds after it.
     *
     * @return the sections that were written, so that nothing can be eliminated
     */
    @Benchmark
    public Section[] commitOptimized() {
        for (int index = 0; index < this.target.length; index++) {
            final Palette palette = this.target[index].blockPalette();
            palette.copyFrom(this.staged.get(index));
            palette.optimize(Palette.Optimization.SIZE);
        }
        return this.target;
    }

    /**
     * Measures the same commit with the guarded optimisation this stage ships.
     *
     * @return the sections that were written, so that nothing can be eliminated
     */
    @Benchmark
    public Section[] commitGuarded() {
        for (int index = 0; index < this.target.length; index++) {
            final Palette palette = this.target[index].blockPalette();
            palette.copyFrom(this.staged.get(index));
            PaletteCompaction.packBlocks(palette);
        }
        return this.target;
    }

    /**
     * Measures the guard on palettes that are already at their minimum width.
     * <p>
     * The pair of this arm and {@link #optimizeAlreadyPacked()} is the case a server meets most often
     * and the one the plain {@code optimize} call handles worst. A palette that is already as narrow as
     * its content allows still costs the full walk before {@code downsizeWithPalette} can say so, while
     * the guard needs only enough entries to see that the count is past what the next mode down could
     * index — a handful, for a palette that is already at the minimum width.
     * </p>
     *
     * @return the sections that were written, so that nothing can be eliminated
     */
    @Benchmark
    public Section[] packAlreadyPacked() {
        for (int index = 0; index < this.target.length; index++) {
            final Palette palette = this.target[index].blockPalette();
            palette.copyFrom(this.packed.get(index));
            PaletteCompaction.packBlocks(palette);
        }
        return this.target;
    }

    /**
     * Measures the optimisation of palettes that are already at their minimum width.
     *
     * @return the sections that were written, so that nothing can be eliminated
     */
    @Benchmark
    public Section[] optimizeAlreadyPacked() {
        for (int index = 0; index < this.target.length; index++) {
            final Palette palette = this.target[index].blockPalette();
            palette.copyFrom(this.packed.get(index));
            palette.optimize(Palette.Optimization.SIZE);
        }
        return this.target;
    }

    /**
     * Refuses a fixture whose palettes are not at a width a generator leaves.
     * <p>
     * This is the guard the benchmark actually needs, and it replaced one that demanded a narrowing.
     * Demanding a narrowing looks like the stricter check and is the weaker one. It passes on the
     * fixture {@code Chunk#setBlock} produces, where a palette carries a bit of slack and duly narrows
     * by that bit, and it aborts the {@code 1024} state trial, where nothing narrows because nothing
     * can — which is the single most decision relevant point on the axis. It would therefore have
     * waved through the first draft of this class, whose numbers were the cost of shaving one bit off
     * an indirect palette reported as the cost of packing a direct one.
     * </p>
     * <p>
     * What a generator leaves is not a range but two values. {@code PaletteImpl#setAll} sends a
     * constant supplier to {@code fill} and everything else to {@code makeDirect}, so every staged
     * palette must be either in the single value mode or at the direct width, and any third width means
     * the staging stopped producing what a generator produces. Because
     * {@link #asAGeneratorWouldHaveWrittenIt(Palette)} calls {@code setAll} itself rather than
     * re-enacting its two arms, that covers both ways this can happen: the staging call being dropped,
     * and {@code setAll} in a future Minestom no longer widening what it is handed. Both are silent
     * failures that turn every number of this class back into a measurement of the wrong input, so both
     * stop the run here.
     * </p>
     * <p>
     * One blind spot is left and is named rather than papered over: at {@code 1024} distinct states the
     * fixture is already at the direct width before it is staged, so the two shapes coincide and no
     * width can tell them apart. The guard therefore speaks at {@code 1} and at {@code 64} and is
     * silent at {@code 1024}. That is enough — a staging that stopped widening would be caught at the
     * two lower points of the same run — but it is not the same as a guard that watches every point.
     * </p>
     *
     * @throws IllegalStateException if a staged palette is at neither the single value mode nor the
     *                               direct width, which means the fixture is no longer the shape a
     *                               generator hands to the commit
     */
    private void verifyTheFixtureIsStagedLikeAGenerator() {
        for (int index = 0; index < this.staged.size(); index++) {
            final int width = this.staged.get(index).bitsPerEntry();

            if (width != 0 && width != Palette.BLOCK_PALETTE_DIRECT_BITS) {
                throw new IllegalStateException("The staged palette of section " + index + " is "
                        + width + " bits wide at " + this.distinctStates + " distinct states, but a "
                        + "generator leaves a section either in the single value mode or at the direct "
                        + "width of " + Palette.BLOCK_PALETTE_DIRECT_BITS + "; this fixture is the "
                        + "shape Chunk#setBlock produces, not the shape the commit is handed, and its "
                        + "numbers would understate both what the optimisation costs and what it saves");
            }
        }
    }
}
