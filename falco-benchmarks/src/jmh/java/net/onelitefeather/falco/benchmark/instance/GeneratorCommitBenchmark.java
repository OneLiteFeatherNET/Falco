package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.palette.Palette;
import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
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
 * <h2>The three arms and why the middle one exists</h2>
 * <p>
 * {@link #commitPlain()} copies the staged palettes into the sections of a chunk, which is what
 * {@code FalcoInstance#applyGenerator} does today. {@link #commitOptimized()} does the same and then
 * optimises each palette it wrote. The difference between the two is the whole answer, and it is a
 * difference rather than an absolute on purpose: a number for {@code optimize} alone would be
 * compared against nothing, while the commit is the step it was added to.
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
 * {@link #widthAGeneratorWouldLeave(Palette)} states the difference and reproduces it, and it is worth
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
 * @version 1.0.0
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

            final Palette stagedPalette = blocks.clone();
            stagedPalette.optimize(widthAGeneratorWouldLeave(alreadyPacked));

            this.staged.add(stagedPalette);
            this.packed.add(alreadyPacked);
            this.target[index] = new Section();
        }
        verifyTheFixtureIsWide();
    }

    /**
     * Answers which width a generator would have left a section of this content at.
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
     * input Task 6 will never hand it. The two branches are reproduced here through the only public
     * door to them: {@code Optimization.SPEED} is {@code makeDirect}, and {@code Optimization.SIZE} on
     * a single valued palette is the {@code fill} the constant branch performs.
     * </p>
     *
     * @param alreadyPacked the same content already reduced to its minimum width, which is in the
     *                      single value mode exactly when a generator's constant branch would have been
     * @return {@link Palette.Optimization#SIZE} for a section a generator would have filled with one
     *         value, {@link Palette.Optimization#SPEED} for every section it would have made direct
     */
    private static Palette.Optimization widthAGeneratorWouldLeave(Palette alreadyPacked) {
        return alreadyPacked.bitsPerEntry() == 0
                ? Palette.Optimization.SIZE
                : Palette.Optimization.SPEED;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        MinestomChunks.release(this.instance);
        this.instance = null;
    }

    /**
     * Measures the commit as {@code FalcoInstance#applyGenerator} performs it today.
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
     * Refuses a fixture that is neither of the two cases this benchmark reports.
     * <p>
     * A trial is worth running when the optimisation narrows something, and it is worth running when
     * the optimisation provably cannot narrow anything because every staged palette already sits at the
     * direct width, above the {@code maxBitsPerEntry} ceiling {@code downsizeWithPalette} refuses to
     * cross. What is not worth running is the fixture in between, where the palettes are indirect and
     * already minimal: there the optimised arm and the control do the same nothing at the same width,
     * and the trial would look like a priced optimisation while measuring a no-op.
     * </p>
     * <p>
     * The distinction is the reason this method does not simply demand a narrowing, which is what it
     * was first written to do. Demanding one aborts the {@code 1024} state trial, and that trial is the
     * one the plan most needs, because a generator that produces more than {@code 256} distinct states
     * in a section is exactly the case in which {@code optimize} charges full price for nothing.
     * </p>
     *
     * @throws IllegalStateException if no staged palette is wider than its packed form and the staged
     *                               palettes are not all at the direct width, which would make every
     *                               number of this run a measurement of an unremarkable no-op
     */
    private void verifyTheFixtureIsWide() {
        if (this.distinctStates == 1) {
            return;
        }
        boolean everyStagedPaletteIsDirect = true;

        for (int index = 0; index < this.staged.size(); index++) {
            final Palette stagedPalette = this.staged.get(index);

            if (stagedPalette.bitsPerEntry() > this.packed.get(index).bitsPerEntry()) {
                return;
            }
            everyStagedPaletteIsDirect &= stagedPalette.bitsPerEntry() == Palette.BLOCK_PALETTE_DIRECT_BITS;
        }
        if (everyStagedPaletteIsDirect) {
            return;
        }
        throw new IllegalStateException("Not one of the " + this.staged.size() + " staged palettes is "
                + "wider than its optimised form at " + this.distinctStates + " distinct states, and "
                + "none of them is at the direct width either, so this trial would report the cost of "
                + "an optimisation that changes nothing");
    }
}
