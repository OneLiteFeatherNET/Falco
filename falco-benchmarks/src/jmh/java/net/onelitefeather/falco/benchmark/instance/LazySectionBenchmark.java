package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * The {@link LazySectionBenchmark} class measures what a chunk pays for holding twenty four eagerly
 * allocated sections against what it pays for holding one shared empty section and materialising a
 * section only when something is written into it.
 * <p>
 * A {@code DynamicChunk} allocates every one of its sections in its constructor. The three lines
 * that do it are {@code var sectionsTemp = new Section[maxSection - minSection];},
 * {@code Arrays.setAll(sectionsTemp, value -> new Section());} and
 * {@code this.sections = List.of(sectionsTemp);}. A full height overworld chunk therefore owns
 * twenty four {@code Section} records, forty eight palettes and forty eight light objects the moment
 * it exists, whether or not a single block was ever written into any of them. In an overworld most
 * of those sections stay air for the entire life of the chunk, and that is the observation this
 * benchmark is built to price.
 * </p>
 *
 * <h2>What the candidate can and cannot be</h2>
 * <p>
 * The flyweight cannot be a subtype of {@code Section} and cannot be a subtype of {@code Palette}.
 * {@code Section} is a {@code record} and therefore final, and {@code Palette} is declared
 * {@code sealed interface Palette permits PaletteImpl}, so neither a lazy section nor a lazy palette
 * can be handed to Minestom. The only place the pattern fits is one level above: a chunk that owns
 * its own section container and answers block level reads and writes from it. {@link LazySections}
 * is exactly that container and nothing more, which is why the candidate arm of this benchmark is a
 * prototype rather than a call into {@code falco-instance}.
 * </p>
 * <p>
 * That restriction has a consequence a reader of the numbers has to carry: {@code Chunk#getSection}
 * and {@code Chunk#getSections} hand out {@code Section} objects, and every caller of those two
 * methods forces a lazy chunk to materialise. The chunk packet builder and the anvil writer are such
 * callers. The saving measured here is therefore a saving on the block accessor path, not a saving
 * that survives an arbitrary caller reaching into the chunk.
 * </p>
 *
 * <h2>Why the materialisation allocates instead of cloning</h2>
 * <p>
 * The obvious copy on write step is {@code EMPTY.clone()}, and it is the wrong one.
 * {@code Section#clone} rebuilds the light through {@code skyLight.set(this.skyLight.array())}, and
 * {@code SkyLight#set} stores {@code LightCompute.EMPTY_CONTENT} and marks the borders valid and the
 * section as needing to be sent. A section that is about to receive its first block has no valid
 * light, so a materialisation through {@code clone} would install a lie together with a reference to
 * a shared array. {@code new Section()} produces the same block content, leaves the light unset, and
 * allocates less. The candidate therefore materialises with {@code new Section()}.
 * </p>
 * <p>
 * The same reasoning removes a cost the plan for this measurement expected. A copy on write step is
 * normally priced as a {@code long[]} copy, but the empty flyweight has no {@code long[]} at all:
 * {@code PaletteImpl} keeps {@code values} at {@code null} while {@code bitsPerEntry == 0} and
 * {@code PaletteImpl#clone} returns before it would copy anything. The price of materialising an
 * empty section is one {@code Section} record, two {@code PaletteImpl} and two {@code Light}
 * objects, all of them without a backing array. Whether that is cheap enough is what
 * {@link #firstWriteLazy()} answers, and {@code -prof gc} answers it more precisely than the timer
 * does.
 * </p>
 *
 * <h2>The three questions and the arms that answer them</h2>
 * <p>
 * Reading an empty section has to become <em>faster</em>, because the candidate answers it with a
 * constant instead of walking into a palette. Reading a full section has to stay <em>identical</em>,
 * because a proxy that costs anything in the hot path is not worth the memory it saves. The first
 * write to an empty section has to be affordable, because it is the one moment the candidate pays
 * for what it saved. {@link #readEmptyEager()} against {@link #readEmptyLazy()} answers the first,
 * {@link #readFullEager()} against {@link #readFullLazy()} and {@link #steadyWriteEager()} against
 * {@link #steadyWriteLazy()} answer the second for both directions of access, and
 * {@link #firstWriteLazy()} against {@link #steadyWriteLazy()} answers the third as a difference
 * rather than as an absolute.
 * </p>
 * <p>
 * The baseline of the first write deserves a word, because the naive one is wrong. A chunk with
 * eager sections never performs a materialisation at runtime: it performed all twenty four of them
 * in its constructor, which is what {@link #buildSectionsEager()} measures. The runtime baseline the
 * candidate has to be compared against is therefore the steady state write, and the materialisation
 * premium is the difference between the two. Comparing {@link #firstWriteLazy()} against an eager
 * arm that also allocates would compare two allocations and report a premium of zero.
 * </p>
 *
 * <h2>Why a third arm keeps the list out of the result</h2>
 * <p>
 * {@code DynamicChunk} stores its sections in a {@code List.of(...)}, which is an
 * {@code ImmutableCollections.ListN} whose {@code get} performs a bounds check against a field
 * before it reaches the array. The candidate stores them in a plain {@code Section[]}. Swapping the
 * container is not the same change as introducing the flyweight, and a two arm benchmark would
 * silently credit the flyweight with whatever the container change is worth.
 * {@link #scatteredReadMinestom()} and {@link #buildSectionsMinestom()} reproduce the Minestom
 * container exactly, {@link #scatteredReadEager()} and {@link #buildSectionsEager()} keep the eager
 * sections but move to the array, and only the difference between those two and the lazy arm belongs
 * to the pattern under test.
 * </p>
 *
 * <h2>The axis, and which measurements it actually moves</h2>
 * <p>
 * {@link #emptyPercent} is the share of sections of the measured chunk that hold nothing but air.
 * The empty sections are the topmost ones, because that is where they sit in an overworld: terrain
 * ends somewhere below the build limit and everything above it is air. Placing them at the top
 * rather than spreading them evenly is a deliberate choice, since the scattered read walks the
 * container and a run of empty sections is what a real read pattern meets.
 * </p>
 * <p>
 * The share of {@code 90} is the value the plan for this measurement calls the overworld case, and
 * it is the one number in this benchmark that must not be taken on trust. {@code EmptySectionCensusTest}
 * in the test source set of this module counts the real share in a real world; until it has been run
 * over a world, {@code 90} is an assumption and the curve should be read as one.
 * </p>
 * <p>
 * The axis only moves two of the measurements. {@link #scatteredReadMinestom()} and its two siblings
 * meet a different mixture of empty and full sections at every share, and
 * {@link #buildSectionsMinestom()} and its two siblings allocate a different number of sections at
 * every share. The per section measurements read and write one empty and one full section that are
 * built outside the layout, so they are invariant under the axis and only need to be run once. They
 * are built outside the layout for a second reason: at a share of {@code 0} the layout holds no
 * empty section at all and at a share of {@code 100} it would hold no full one, so a measurement
 * that took its subject from the layout would have no subject at one end of its own axis.
 * </p>
 *
 * <h2>Why the arms are proved equal before the first measurement</h2>
 * <p>
 * A flyweight that is not installed degenerates into the baseline and reports that the pattern
 * changes nothing, and a flyweight that returns the wrong constant reports that it is faster at
 * answering a different question. {@link #verifyAllArmsAgree()} therefore walks all
 * {@code 24 * 16 * 16 * 16} positions of all three arms and throws on the first disagreement, checks
 * by identity that every empty slot of the candidate really points at the shared section and that no
 * full slot does, and refuses a fixture whose full sections hold a single state or nothing but air.
 * The trial dies rather than publishes.
 * </p>
 *
 * <h2>Why this benchmark reports nanoseconds</h2>
 * <p>
 * The convention of this module is {@code MICROSECONDS}, and it is the right unit for the work a
 * light engine or a region file does. A single palette read is around two nanoseconds and would be
 * printed as {@code 0.002 us/op}, which throws away the digits the comparison consists of. The
 * measurement plan for this benchmark asks for nanoseconds per operation, so that is what it reports.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * The fixture starts a Minestom server to resolve the block states of the full sections, so the run
 * takes the raised heap the convention prescribes for that case. The single fork the convention
 * prescribes with it does not survive contact with the scouting run: that run put the whole start,
 * JVM launch and server included, at about {@code 1,3 s} per fork of this class, against the
 * {@code 10 s} of iterations a fork runs here, so the reason the convention gives for one fork does
 * not apply and the citable runs use three. Two commands, because the two families of measurement
 * need different parts of the axis:
 * </p>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:jmhJar
 * java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
 *     "LazySectionBenchmark.(scatteredRead|buildSections).*" \
 *     -p emptyPercent=0,62,90 -f 3 -wi 5 -i 5 -prof gc
 * java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
 *     "LazySectionBenchmark.(readEmpty|readFull|steadyWrite|firstWrite).*" \
 *     -p emptyPercent=90 -f 3 -wi 5 -i 5 -prof gc
 * }</pre>
 * <p>
 * The second command pins the axis to a single value on purpose: those four families do not read the
 * layout, so running them at three shares would produce the same number three times. The scouting
 * run confirmed it — {@code firstWriteLazy} read {@code 2720,0 B/op} at every one of the three
 * shares it was taken at, and the four read and write families read {@code 0 B/op} at all of them.
 * {@code -prof gc} is not optional for either command. The allocation rate is the metric that
 * answers the memory question directly, and it is the only one of the two that the timer cannot
 * distort.
 * </p>
 * <p>
 * Neither command passes {@code -jvmArgs}. The heap this fixture needs is already in the
 * {@code @Fork} annotation as {@code jvmArgsAppend}, and restating it on the command line replaces
 * the inherited base arguments instead of adding to them, which is a different JVM configuration
 * than the one the annotation describes. The fork count is raised to three on the command line
 * because the annotation's single fork measures one JVM launch and reports variance between
 * iterations of that one launch as if it were the whole of it.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.4.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class LazySectionBenchmark {

    /**
     * The amount of sections a chunk of this benchmark holds.
     */
    private static final int SECTION_COUNT = BenchmarkConstants.OVERWORLD_SECTIONS;

    /**
     * The edge length of a section in blocks.
     */
    private static final int SECTION_SIZE = Chunk.CHUNK_SECTION_SIZE;

    /**
     * The state id of air.
     * <p>
     * The value is written down rather than read from {@code Block.AIR.stateId()} because it is the
     * constant the candidate returns for a shared section, and a constant that is resolved during
     * class initialisation would depend on whether the registry is already up at that point.
     * {@link #verifyAllArmsAgree()} checks the value against the registry once the server is
     * running, so the shortcut cannot drift away from Minestom unnoticed.
     * </p>
     */
    private static final int AIR_STATE = 0;

    /**
     * The amount of distinct block states the full sections of the fixture are filled from.
     * <p>
     * Held constant rather than made an axis. The state count decides the bit width of a palette and
     * therefore belongs to the chunk comparison, which owns that axis. Sixty four states put the
     * block palette at six bits, which is the indirect mode a real terrain section runs in, and keeps
     * this benchmark about the flyweight rather than about the packing.
     * </p>
     */
    private static final int FULL_SECTION_STATES = 64;

    /**
     * The arrangement the full sections are filled in.
     * <p>
     * The terrain like shape, because a section that is not empty in an overworld is not a random
     * scatter of states but a stack of strata. The other two shapes exist to bracket that one and
     * belong to the benchmark that varies them.
     * </p>
     */
    private static final MinestomChunks.FillShape FILL_SHAPE = MinestomChunks.FillShape.RANDOM_RUNS;

    /**
     * The amount of positions a single scattered read invocation visits.
     */
    private static final int SCATTERED_READS = 1024;

    /**
     * The index of the empty section inside the probe container.
     */
    private static final int PROBE_EMPTY = 0;

    /**
     * The index of the full section inside the probe container.
     */
    private static final int PROBE_FULL = 1;

    /**
     * The share of sections of the measured chunk that hold nothing but air, in percent.
     * <p>
     * The share is converted into a section count by truncation, so {@code 90} of twenty four
     * sections means twenty one empty and three full ones rather than twenty one and a half. The
     * exact counts are stated by {@link #verifyAllArmsAgree()} when they do not match what the arms
     * were built with.
     * </p>
     *
     * <h2>Where {@code 62} comes from, and why it is not {@code 90}</h2>
     * <p>
     * {@code 62} is the only point on this axis that was counted rather than chosen.
     * {@code EmptySectionCensusTest} read a generated overworld and found {@code 62,24 %} of the
     * sections of its finished chunks to hold nothing but air, over a height profile that leaves no
     * doubt about the reading: the sections below world height {@code 64} are empty in {@code 0,0 %}
     * of the chunks, the surface transition sits at {@code Y} four to six, and everything above it is
     * empty in all of them.
     * </p>
     * <p>
     * The research report behind this benchmark assumed {@code 90 %} instead, and that number is the
     * reason this axis originally stopped there. It came from a world whose chunks are almost all
     * air — counting the same share in a void hub world yields {@code 99,6 %}, and counting an
     * overworld without regard for how far its generator got yields {@code 87,5 %}, because a chunk
     * at {@code minecraft:structure_starts} contributes twenty four empty sections and no terrain.
     * Sharing empty sections is worth roughly a third less at the measured point than at the assumed
     * one, so the assumed point is kept here only as the upper bound it actually is.
     * </p>
     * <p>
     * The counted share rests on the {@code 441} finished chunks around the spawn of one world. That
     * is a real measurement and a narrow one: an ocean or a mountain range would not produce the same
     * share, and no claim beyond "a generated overworld near its spawn" is licensed by it.
     * </p>
     *
     * <h2>Why {@code 50} is no longer on this axis</h2>
     * <p>
     * The axis carried a fourth point, {@code 50}, which was chosen rather than counted and which the
     * scouting run of 2026-08-01 showed to be predictable from the other three. That run took
     * {@code 0}, {@code 62} and {@code 90} at {@code -f 1 -wi 2 -i 3 -prof gc} and read
     * {@code buildSectionsLazy} at {@code 5120,0 B/op}, {@code 2208,0 B/op} and {@code 752,0 B/op},
     * each with an error of at most {@code 0,7 B}. Converted to the full section count the axis
     * really varies — twenty four, ten and three — those three points lie exactly on
     * {@code 128 + 208 * fullSections}: {@code 128 + 208 * 24 = 5120}, {@code 128 + 208 * 10 = 2208},
     * {@code 128 + 208 * 3 = 752}. The residual is zero at all three, not small at all three.
     * </p>
     * <p>
     * {@code 50} truncates to twelve empty and twelve full sections, so the line predicts
     * {@code 2624 B/op} for it. A point that an exact affine fit through three measured points
     * already names cannot disagree with the fit without disproving the fit, and it lies between
     * {@code 62} and {@code 0} rather than beyond either of them, so it does not extend the span
     * either. It was removed because it costs thirteen further trials — one per benchmark method —
     * and answers nothing the remaining three do not.
     * </p>
     * <p>
     * The three that stay each answer something the others cannot. {@code 0} is the control that
     * prices the candidate where sharing can win nothing, and it is the point that showed the lazy
     * arm to cost {@code 16 B} more than the eager one rather than the same
     * ({@code 5120,0} against {@code 5104,0}). {@code 62} is the only counted point. {@code 90} is
     * the upper bound the research report assumed, kept so the assumption can be read against the
     * measurement. Allocation is the metric this reasoning rests on because it is the one the
     * scouting run could resolve — the same rows carried time errors between {@code 16 %} and
     * {@code 230 %} of their own means, and nothing may be concluded from those.
     * </p>
     */
    @Param({"0", "62", "90"})
    public int emptyPercent;

    /**
     * The instance the fixture chunk is created in.
     */
    private InstanceContainer container;

    /**
     * The sections of the baseline arm in the container Minestom uses.
     */
    private List<Section> minestomSections;

    /**
     * The sections of the baseline arm in a plain array, which isolates the container change.
     */
    private Section[] eagerSections;

    /**
     * The sections of the candidate arm, with the shared section in every empty slot.
     */
    private LazySections lazySections;

    /**
     * The probe container of the baseline arm, holding one empty and one full section.
     */
    private Section[] probeEager;

    /**
     * The probe container of the candidate arm, holding the shared section and one full section.
     */
    private LazySections probeLazy;

    /**
     * The packed positions the scattered read visits.
     */
    private int[] scattered;

    /**
     * The block states the probe section already holds, so a write cannot grow its palette.
     */
    private int[] presentStates;

    /**
     * The mask that turns a cursor into an index of {@link #presentStates}.
     */
    private int presentStatesMask;

    /**
     * The amount of sections the layout holds that are not empty.
     */
    private int filledSections;

    /**
     * The position the per section measurements read or write next.
     */
    private int cursor;

    /**
     * Builds the fixture chunk, derives all three arms from it and proves that they agree.
     * <p>
     * The content of the full sections comes from a real chunk that the shared fixture filled, so the
     * states are states the registry actually holds and the arrangement is the one every other chunk
     * benchmark of this module measures on. Every arm receives its own clone of every full section:
     * two arms that shared a section object would report identical numbers for a reason that has
     * nothing to do with either of them, and the write measurements would corrupt each other.
     * </p>
     *
     * @throws IllegalStateException if the three arms disagree or the fixture degenerated
     */
    @Setup(Level.Trial)
    public void setUp() {
        MinestomChunks.ensureServer();
        this.container = MinestomChunks.newContainer();

        final Chunk source = MinestomChunks.newChunk(this.container, 0, 0);
        MinestomChunks.fill(source, FULL_SECTION_STATES, FILL_SHAPE);

        final List<Section> filled = source.getSections();

        if (filled.size() != SECTION_COUNT) {
            throw new IllegalStateException("The fixture chunk holds " + filled.size()
                    + " sections but the benchmark is written for " + SECTION_COUNT);
        }
        final int emptyCount = SECTION_COUNT * this.emptyPercent / 100;
        this.filledSections = SECTION_COUNT - emptyCount;

        final Section[] minestomArm = new Section[SECTION_COUNT];
        final Section[] eagerArm = new Section[SECTION_COUNT];
        final Section[] lazyArm = new Section[SECTION_COUNT];

        // The empty sections are the topmost ones, which is where an overworld keeps them.
        for (int index = 0; index < SECTION_COUNT; index++) {
            if (index >= this.filledSections) {
                minestomArm[index] = new Section();
                eagerArm[index] = new Section();
                lazyArm[index] = LazySections.EMPTY;
                continue;
            }
            final Section content = filled.get(index);
            minestomArm[index] = content.clone();
            eagerArm[index] = content.clone();
            lazyArm[index] = content.clone();
        }
        this.minestomSections = List.of(minestomArm);
        this.eagerSections = eagerArm;
        this.lazySections = new LazySections(lazyArm);

        // The probes sit outside the layout so that both of them exist at every share of the axis.
        final Section probeContent = filled.getFirst();
        this.probeEager = new Section[]{new Section(), probeContent.clone()};
        this.probeLazy = new LazySections(new Section[]{LazySections.EMPTY, probeContent.clone()});

        this.presentStates = statesOf(probeContent);
        this.presentStatesMask = this.presentStates.length - 1;
        this.scattered = drawScatteredPositions();

        verifyAllArmsAgree();
    }

    /**
     * Unregisters the instance the fixture chunk was created in.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        MinestomChunks.release(this.container);
        this.container = null;
    }

    /**
     * Measures a read from an empty section through the container Minestom uses today.
     * <p>
     * The section exists, so the read walks into its block palette. The palette is in single value
     * mode and answers from a field, which is already close to a constant return, and that is exactly
     * why this pairing is worth measuring rather than assuming: the candidate saves two dereferences
     * and a coordinate validation, not a table lookup.
     * </p>
     *
     * @return the state id the section holds at the read position
     */
    @Benchmark
    public int readEmptyEager() {
        final int position = this.cursor++;
        return this.probeEager[PROBE_EMPTY].blockPalette()
                .get(position & 15, (position >>> 8) & 15, (position >>> 4) & 15);
    }

    /**
     * Measures a read from an empty section through the candidate, which answers it with a constant.
     *
     * @return the state id the candidate reports for the shared section
     */
    @Benchmark
    public int readEmptyLazy() {
        final int position = this.cursor++;
        return this.lazyProbeGet(PROBE_EMPTY, position);
    }

    /**
     * Measures a read from a full section through the container Minestom uses today.
     *
     * @return the state id the section holds at the read position
     */
    @Benchmark
    public int readFullEager() {
        final int position = this.cursor++;
        return this.probeEager[PROBE_FULL].blockPalette()
                .get(position & 15, (position >>> 8) & 15, (position >>> 4) & 15);
    }

    /**
     * Measures a read from a full section through the candidate, which has to pay a branch for it.
     * <p>
     * This is the measurement that decides whether the pattern is affordable. Every read of every
     * block of every non empty section in the server goes through the branch this method adds, and a
     * result outside the error bars of {@link #readFullEager()} means the memory the pattern saves is
     * paid for in the hot path.
     * </p>
     *
     * @return the state id the section holds at the read position
     */
    @Benchmark
    public int readFullLazy() {
        final int position = this.cursor++;
        return this.lazyProbeGet(PROBE_FULL, position);
    }

    /**
     * Measures a write into a section that is already materialised, through the container Minestom
     * uses today.
     * <p>
     * The written state is one the section already holds, so the palette never grows and the
     * measurement stays the same from the first invocation to the last.
     * </p>
     */
    @Benchmark
    public void steadyWriteEager() {
        final int position = this.cursor++;
        this.probeEager[PROBE_FULL].blockPalette().set(position & 15, (position >>> 8) & 15,
                (position >>> 4) & 15, this.presentStates[position & this.presentStatesMask]);
    }

    /**
     * Measures a write into a section that is already materialised, through the candidate.
     * <p>
     * The counterpart of {@link #readFullLazy()} for the write path, and the baseline
     * {@link #firstWriteLazy()} has to be read against. The branch is taken on the cold side here, so
     * the difference to {@link #steadyWriteEager()} is what the pattern costs a server that is past
     * the first write into every section it uses.
     * </p>
     */
    @Benchmark
    public void steadyWriteLazy() {
        final int position = this.cursor++;
        this.probeLazy.set(PROBE_FULL, position & 15, (position >>> 8) & 15,
                (position >>> 4) & 15, this.presentStates[position & this.presentStatesMask]);
    }

    /**
     * Measures the materialisation of a shared section together with the write that triggered it.
     * <p>
     * The method deliberately does not store the materialised section back into the container. A
     * store would end the state the measurement needs after the first invocation, since the slot
     * would no longer be shared and every following invocation would measure
     * {@link #steadyWriteLazy()} instead. What is left out is a single array write; what is measured
     * is the allocation of a {@code Section}, of two palettes and of two light objects, plus the
     * growth of the block palette from single value mode to four bits and the write itself.
     * </p>
     *
     * @return the materialised section, so that nothing of it can be eliminated
     */
    @Benchmark
    public Section firstWriteLazy() {
        final int position = this.cursor++;
        final Section materialised = new Section();
        materialised.blockPalette().set(position & 15, (position >>> 8) & 15,
                (position >>> 4) & 15, this.presentStates[position & this.presentStatesMask]);
        return materialised;
    }

    /**
     * Measures scattered reads over a whole chunk held in the container Minestom uses today.
     *
     * @return the accumulated state ids, so that no read can be eliminated
     */
    @Benchmark
    @OperationsPerInvocation(SCATTERED_READS)
    public int scatteredReadMinestom() {
        final int[] positions = this.scattered;
        final List<Section> sections = this.minestomSections;
        int sink = 0;

        for (int index = 0; index < positions.length; index++) {
            final int packed = positions[index];
            sink ^= sections.get(packed >>> 12).blockPalette()
                    .get(packed & 15, (packed >>> 8) & 15, (packed >>> 4) & 15);
        }
        return sink;
    }

    /**
     * Measures scattered reads over a whole chunk held in a plain array of eager sections.
     * <p>
     * The control arm. Its distance to {@link #scatteredReadMinestom()} is what moving off
     * {@code List.of} is worth, and only what is left after subtracting it belongs to the flyweight.
     * </p>
     *
     * @return the accumulated state ids, so that no read can be eliminated
     */
    @Benchmark
    @OperationsPerInvocation(SCATTERED_READS)
    public int scatteredReadEager() {
        final int[] positions = this.scattered;
        final Section[] sections = this.eagerSections;
        int sink = 0;

        for (int index = 0; index < positions.length; index++) {
            final int packed = positions[index];
            sink ^= sections[packed >>> 12].blockPalette()
                    .get(packed & 15, (packed >>> 8) & 15, (packed >>> 4) & 15);
        }
        return sink;
    }

    /**
     * Measures scattered reads over a whole chunk held by the candidate.
     * <p>
     * The share of the reads that land in a shared section is {@link #emptyPercent} by construction
     * of the drawn positions, so this is the measurement in which the axis turns into a curve.
     * </p>
     *
     * @return the accumulated state ids, so that no read can be eliminated
     */
    @Benchmark
    @OperationsPerInvocation(SCATTERED_READS)
    public int scatteredReadLazy() {
        final int[] positions = this.scattered;
        final LazySections sections = this.lazySections;
        int sink = 0;

        for (int index = 0; index < positions.length; index++) {
            final int packed = positions[index];
            sink ^= sections.get(packed >>> 12, packed & 15, (packed >>> 8) & 15, (packed >>> 4) & 15);
        }
        return sink;
    }

    /**
     * Measures the section allocation a {@code DynamicChunk} performs in its constructor.
     * <p>
     * The three lines are copied from {@code DynamicChunk}, down to the {@code Arrays.setAll} and the
     * {@code List.of}, because the point of this arm is what the constructor of Minestom costs and
     * not what a rewrite of it would cost.
     * </p>
     *
     * @return the built sections, so that the allocation cannot be eliminated
     */
    @Benchmark
    public List<Section> buildSectionsMinestom() {
        final Section[] sections = new Section[SECTION_COUNT];
        Arrays.setAll(sections, index -> new Section());
        return List.of(sections);
    }

    /**
     * Measures the same allocation without the immutable list around it.
     *
     * @return the built sections, so that the allocation cannot be eliminated
     */
    @Benchmark
    public Section[] buildSectionsEager() {
        final Section[] sections = new Section[SECTION_COUNT];
        Arrays.setAll(sections, index -> new Section());
        return sections;
    }

    /**
     * Measures what the candidate allocates for a chunk that ends up with the configured share of
     * empty sections.
     * <p>
     * A chunk of the candidate allocates nothing at construction: it fills its array with the shared
     * section and is done. The sections that are not empty are materialised when the loader writes
     * their first block, so the honest comparison against the constructor of Minestom is the array
     * plus one materialisation per non empty section. That is what this arm builds, which is why it
     * is the one place where {@link #emptyPercent} decides how much memory is touched rather than how
     * much is read.
     * </p>
     *
     * @return the built container, so that the allocation cannot be eliminated
     */
    @Benchmark
    public LazySections buildSectionsLazy() {
        final Section[] sections = new Section[SECTION_COUNT];
        Arrays.fill(sections, LazySections.EMPTY);

        for (int index = 0; index < this.filledSections; index++) {
            sections[index] = new Section();
        }
        return new LazySections(sections);
    }

    /**
     * Reads a position of the probe container of the candidate.
     *
     * @param section  the index of the section inside the probe container
     * @param position the packed position the cursor produced
     * @return the state id at the position
     */
    private int lazyProbeGet(int section, int position) {
        return this.probeLazy.get(section, position & 15, (position >>> 8) & 15, (position >>> 4) & 15);
    }

    /**
     * Draws the positions the scattered read visits.
     * <p>
     * The positions are drawn over the whole chunk and not over the sections separately, so the share
     * of reads that meet an empty section is the share of sections that are empty. A benchmark that
     * drew the same amount of positions per section would report the same number for every setting of
     * the axis and would look like a curve without being one.
     * </p>
     *
     * @return the packed positions, section index in the upper bits and the coordinates below it
     */
    private int[] drawScatteredPositions() {
        final Random random = new Random(BenchmarkConstants.SEED);
        final int[] positions = new int[SCATTERED_READS];

        for (int index = 0; index < positions.length; index++) {
            final int section = random.nextInt(SECTION_COUNT);
            final int x = random.nextInt(SECTION_SIZE);
            final int y = random.nextInt(SECTION_SIZE);
            final int z = random.nextInt(SECTION_SIZE);
            positions[index] = (section << 12) | (y << 8) | (z << 4) | x;
        }
        return positions;
    }

    /**
     * Collects the distinct block states a section holds, rounded down to a power of two.
     * <p>
     * The write measurements pick their state from this set so that the palette of the section can
     * never grow while they run. A palette that grew during a measurement would report the cost of a
     * resize as if it were the cost of a write. The length is rounded down to a power of two so the
     * pick is a mask rather than a division, which keeps the arithmetic out of the result.
     * </p>
     *
     * @param section the section to collect from
     * @return the collected states
     * @throws IllegalStateException if the section holds fewer than two distinct states
     */
    private static int[] statesOf(Section section) {
        final SortedSet<Integer> distinct = new TreeSet<>();
        section.blockPalette().getAll((x, y, z, value) -> distinct.add(value));

        if (distinct.size() < 2) {
            throw new IllegalStateException("The fixture section holds " + distinct.size()
                    + " distinct states, so a measurement on it would measure a uniform palette");
        }
        final List<Integer> ordered = new ArrayList<>(distinct);
        int length = Integer.highestOneBit(ordered.size());
        final int[] states = new int[length];

        for (int index = 0; index < length; index++) {
            states[index] = ordered.get(index);
        }
        return states;
    }

    /**
     * Proves that the three arms hold the same chunk before a single measurement is taken.
     * <p>
     * The walk covers every position of every section of all three arms, so a slot that received the
     * wrong content and a candidate that returns the wrong constant are both caught rather than
     * measured. The checks around it cover the failures the walk cannot see: a candidate whose shared
     * slots were quietly replaced by ordinary sections would pass the walk and report that the
     * pattern is free, and a fixture whose fill did not take would pass it and report the best
     * numbers this benchmark will ever produce for a chunk that holds nothing.
     * </p>
     *
     * @throws IllegalStateException if the arms disagree, the sharing is not installed, the state id
     *                               of air is not the constant the candidate returns or the full
     *                               sections degenerated to a single state
     */
    private void verifyAllArmsAgree() {
        if (Block.AIR.stateId() != AIR_STATE) {
            throw new IllegalStateException("Air has the state id " + Block.AIR.stateId()
                    + " but the candidate answers a shared section with " + AIR_STATE);
        }
        for (int section = 0; section < SECTION_COUNT; section++) {
            final boolean shared = this.lazySections.isShared(section);

            if (shared != (section >= this.filledSections)) {
                throw new IllegalStateException("The section " + section + " of the candidate is "
                        + (shared ? "shared" : "materialised") + " but the layout for " + this.emptyPercent
                        + " percent empty sections asks for the opposite");
            }
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int z = 0; z < SECTION_SIZE; z++) {
                    for (int x = 0; x < SECTION_SIZE; x++) {
                        final int minestom = this.minestomSections.get(section).blockPalette().get(x, y, z);
                        final int eager = this.eagerSections[section].blockPalette().get(x, y, z);
                        final int lazy = this.lazySections.get(section, x, y, z);

                        if (minestom != eager || minestom != lazy) {
                            throw new IllegalStateException("The arms disagree at section " + section
                                    + " position " + x + ":" + y + ":" + z + ": minestom " + minestom
                                    + ", eager " + eager + ", lazy " + lazy);
                        }
                    }
                }
            }
        }
        verifyProbesAgree();
    }

    /**
     * Proves that the two probe containers hold the same empty and the same full section.
     *
     * @throws IllegalStateException if the probes disagree or the full probe holds nothing but air
     */
    private void verifyProbesAgree() {
        if (!this.probeLazy.isShared(PROBE_EMPTY) || this.probeLazy.isShared(PROBE_FULL)) {
            throw new IllegalStateException("The probe container of the candidate does not share its "
                    + "empty section or shares its full one");
        }
        boolean foundNonAir = false;

        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    final int emptyEager = this.probeEager[PROBE_EMPTY].blockPalette().get(x, y, z);
                    final int emptyLazy = this.probeLazy.get(PROBE_EMPTY, x, y, z);
                    final int fullEager = this.probeEager[PROBE_FULL].blockPalette().get(x, y, z);
                    final int fullLazy = this.probeLazy.get(PROBE_FULL, x, y, z);

                    if (emptyEager != AIR_STATE || emptyLazy != AIR_STATE) {
                        throw new IllegalStateException("The empty probe holds " + emptyEager + " and "
                                + emptyLazy + " at " + x + ":" + y + ":" + z + " instead of air");
                    }
                    if (fullEager != fullLazy) {
                        throw new IllegalStateException("The full probes disagree at " + x + ":" + y + ":"
                                + z + ": eager " + fullEager + ", lazy " + fullLazy);
                    }
                    foundNonAir |= fullEager != AIR_STATE;
                }
            }
        }
        if (!foundNonAir) {
            throw new IllegalStateException("The full probe holds nothing but air over all "
                    + BenchmarkConstants.BLOCK_ENTRIES + " positions");
        }
    }

    /**
     * The {@link LazySections} class is the section container of the candidate arm: an array in which
     * every section that holds nothing but air is one and the same shared object, and in which a
     * section is created only when something is written into it.
     * <p>
     * The class is a prototype and lives here rather than in {@code falco-instance} because nothing
     * of it can be handed to Minestom. {@code Section} is a record and {@code Palette} is a sealed
     * interface, so the pattern cannot be expressed as a subtype of either, and a chunk that wanted
     * it would have to own its section container and answer {@code getBlock} and {@code setBlock}
     * from it. Measuring the container in isolation says whether that rewrite is worth starting.
     * </p>
     * <p>
     * The shared section is a {@code static final} field on purpose. It makes the identity comparison
     * in {@link #get(int, int, int, int)} a comparison against a constant the compiler knows, which is
     * the cheapest form the check can take and therefore the form the measurement has to use.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    public static final class LazySections {

        /**
         * The section every empty slot of every container points at.
         * <p>
         * It is never written to. {@link #set(int, int, int, int, int)} replaces the slot before it
         * writes, which is the copy on write step of the pattern.
         * </p>
         */
        static final Section EMPTY = new Section();

        /**
         * The sections, with {@link #EMPTY} in every slot that holds nothing but air.
         */
        private final Section[] sections;

        /**
         * Creates a container around the given sections.
         *
         * @param sections the sections, with {@link #EMPTY} in every empty slot
         */
        LazySections(Section[] sections) {
            this.sections = sections;
        }

        /**
         * Reads a block state.
         *
         * @param section the index of the section inside the container
         * @param x       the x coordinate inside the section
         * @param y       the y coordinate inside the section
         * @param z       the z coordinate inside the section
         * @return the state id at the position
         */
        int get(int section, int x, int y, int z) {
            final Section stored = this.sections[section];

            if (stored == EMPTY) {
                return AIR_STATE;
            }
            return stored.blockPalette().get(x, y, z);
        }

        /**
         * Writes a block state, materialising the section if it is still the shared one.
         * <p>
         * The materialisation allocates a new section rather than cloning the shared one. Cloning
         * would carry the light state of the shared section over, and the shared section claims a
         * light it does not have as soon as {@code Section#clone} has run through
         * {@code SkyLight#set}. A freshly allocated section holds the same blocks and no light, which
         * is what a section that is about to receive its first block has to hold.
         * </p>
         *
         * @param section the index of the section inside the container
         * @param x       the x coordinate inside the section
         * @param y       the y coordinate inside the section
         * @param z       the z coordinate inside the section
         * @param state   the state id to write
         */
        void set(int section, int x, int y, int z, int state) {
            Section stored = this.sections[section];

            if (stored == EMPTY) {
                stored = new Section();
                this.sections[section] = stored;
            }
            stored.blockPalette().set(x, y, z, state);
        }

        /**
         * Reports whether a slot still points at the shared section.
         *
         * @param section the index of the section inside the container
         * @return whether the slot is shared
         */
        boolean isShared(int section) {
            return this.sections[section] == EMPTY;
        }
    }
}
