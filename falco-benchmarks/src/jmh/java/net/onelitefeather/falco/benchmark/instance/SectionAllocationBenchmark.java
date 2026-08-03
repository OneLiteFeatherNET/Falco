package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.light.Light;
import net.minestom.server.instance.light.LightCompute;
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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link SectionAllocationBenchmark} class measures what it costs to construct one chunk, and
 * splits that cost into the three posts a chunk pays before a single block is written: the sections
 * themselves, the palettes inside them and the light carriers hanging off them.
 * <p>
 * A fresh overworld chunk of Minestom allocates 24 {@link Section}s eagerly, each holding two
 * {@link Palette}s and two {@link Light} carriers, and each light carrier holds one
 * {@link AtomicBoolean} whose only job is to remember whether the section still has to be sent. That
 * is 24 sections, 48 palettes, 48 light carriers and 48 atomic booleans, roughly 168 objects, for a
 * chunk that is entirely air. This benchmark takes those posts apart one at a time so that the
 * numbers say which of them is worth an architecture rather than which of them is easiest to name.
 * </p>
 *
 * <h2>What this benchmark decides</h2>
 * <p>
 * The original claim under investigation was that the 48 atomic booleans are the largest avoidable
 * post of a chunk. The adversarial review downgraded it before a line of this class existed: 48
 * atomic booleans are about 768 bytes, while the 48 palette objects alone are roughly 1.9 KiB and
 * the 24 sections and 48 light carriers come on top. The suspected lever is therefore the eager
 * section array of {@code DynamicChunk}, which fills every one of its slots with a freshly built
 * {@code new Section()}.
 * </p>
 * <p>
 * The two arms that settle this are {@link #packedFlagLight()} and {@link #sharedAirSection()}. The
 * first removes nothing but the atomic booleans; the second removes the sections, and with them
 * their palettes and light carriers. If the difference between the first and the baseline is small
 * while the difference between the second and the baseline is large, the downgrade was right. The
 * arms are built so that this comparison cannot be blurred: every arm allocates the same section
 * array and wraps it in the same immutable list, so the only thing that varies between two arms is
 * the thing the arm is named after.
 * </p>
 *
 * <h2>The ladder of arms</h2>
 * <table border="1">
 *   <caption>What each arm allocates per chunk</caption>
 *   <tr><th>Arm</th><th>Sections</th><th>Palettes</th><th>Light carriers</th><th>Atomic booleans</th></tr>
 *   <tr><td>{@link #minestomDynamicChunk()}</td><td>24</td><td>48</td><td>48</td><td>48</td></tr>
 *   <tr><td>{@link #eagerReplicaLight()}</td><td>24</td><td>48</td><td>48</td><td>48</td></tr>
 *   <tr><td>{@link #packedFlagLight()}</td><td>24</td><td>48</td><td>48</td><td>0</td></tr>
 *   <tr><td>{@link #sharedUnlitLight()}</td><td>24</td><td>48</td><td>0</td><td>0</td></tr>
 *   <tr><td>{@link #sharedAirSection()}</td><td>0</td><td>0</td><td>0</td><td>0</td></tr>
 * </table>
 * <p>
 * {@link #eagerReplicaLight()} measures nothing new on purpose. It is the control: it builds the
 * same 24 eager sections as the baseline, but with the light carrier of this class instead of the
 * one of Minestom, which holds the same fields in the same shape. Its delta against the baseline has
 * to be zero within the error bars. If it is not, the replica is not faithful, and every number this
 * class produces about the light carrier is void, because the difference would then include the
 * replica rather than the change under test. Without that arm, the delta between the baseline and
 * {@link #packedFlagLight()} would mix two causes and could not answer anything.
 * </p>
 *
 * <h2>How much of this is Minestom and how much is a prototype</h2>
 * <p>
 * More of it is Minestom than the plan assumed, and the difference is worth stating exactly.
 * {@link Section} of the pinned build is a record, not a class with hidden constructors, so its
 * canonical constructor {@code Section(Palette, Palette, Light, Light)} is public, and {@link Light}
 * is an ordinary public interface rather than a sealed one. An own light carrier can therefore be
 * put inside a real Minestom section. Every arm of this class consequently builds real
 * {@link Section}s holding real {@link Palette}s obtained from {@code Palette.blocks()} and
 * {@code Palette.biomes()}; only the light carrier is written here.
 * </p>
 * <p>
 * The chunk type is a prototype, but a thin one: {@code PrototypeChunk} extends {@link DynamicChunk}
 * and adds nothing but access to the protected constructor that takes a prepared list of sections.
 * Heightmaps, block entity maps, the packet cache, {@code setBlock} and {@code getBlock} are the
 * inherited originals, so an arm differs from the baseline in the sections it is handed and in
 * nothing else. What is genuinely not measured here is light computation: {@code LightCompute#compute}
 * and {@code LightCompute#getLight} are package-private, so the prototypes reproduce the storage
 * shape and the flag semantics of {@code BlockLight} and {@code SkyLight}, not their algorithm.
 * {@code calculateInternal} and {@code calculateExternal} therefore refuse to run. This benchmark
 * constructs chunks and never lights them, so no arm reaches those methods, and one that started to
 * would fail loudly instead of silently measuring less work.
 * </p>
 *
 * <h2>Why one replica serves as both sky and block light</h2>
 * <p>
 * {@code BlockLight} and {@code SkyLight} differ in one field: the sky variant carries an extra
 * {@code boolean fullyLit}. Both hold three byte array references, one volatile boolean and one
 * atomic boolean, which lands them in the same size class under any header layout, because the extra
 * boolean falls into alignment padding that already exists. The control arm is what turns that
 * sentence from an assumption into a measurement: if the padding argument were wrong, the replica
 * would be a size class smaller than the pair it replaces and the control would show it.
 * </p>
 *
 * <h2>Why a FalcoInstance owns the chunks</h2>
 * <p>
 * The constructor of {@link Chunk} registers a viewable with the entity tracker of its instance,
 * keyed by the shared instance list of that instance. An {@code InstanceContainer} hands out a new
 * unmodifiable list on every call, and the key compares that list by identity, so every constructed
 * chunk inserts a new entry into a map that is never cleared. A benchmark that constructs millions of
 * chunks against a container would therefore measure a growing hash map, and would eventually run out
 * of heap. A {@link FalcoInstance} is not an {@code InstanceContainer}, receives the {@code List.of()}
 * singleton and hits the same key every time, so the map holds exactly one entry for the whole run.
 * That is why the instance under all arms is a Falco one. It is a fixture decision and not a
 * measurement: the instance is identical for every arm and nothing here compares Falco to Minestom.
 * </p>
 *
 * <h2>The shared section is only safe because nothing writes</h2>
 * <p>
 * {@link #sharedAirSection()} points all 24 slots at a single air section that is built once per
 * trial. That is the flyweight the plan asks for, and it is sound for a construction measurement
 * because every chunk this class builds stays empty and every check only reads. A production chunk
 * would need a copy on write branch that materialises the slot before the first write, which costs
 * nothing at construction time and is therefore outside what this benchmark can measure. It cannot be
 * prototyped inside {@link DynamicChunk} either: the {@code sections} field is a final immutable list,
 * so a materialising chunk needs its own storage and its own type. That is a finding about the shape
 * of a future chunk, not a gap in this measurement.
 * </p>
 *
 * <h2>Every arm has to build the same chunk</h2>
 * <p>
 * Before the first measurement, the setup builds one chunk per arm and compares each of them against
 * the baseline chunk over every block position and both heightmaps through
 * {@code MinestomChunks#assertSameBlocks}, and over the section count, the palette state and the light
 * level of every position of every section. A mismatch throws and ends the trial. A cheaper arm that
 * no longer produces the same chunk is not a cheaper chunk, it is a different one, and a number taken
 * from it would compare two worlds instead of two layouts.
 * </p>
 *
 * <h2>Hypothesis</h2>
 * <p>
 * Expected, not measured: the control lands on the baseline; the packed flag saves the 48 atomic
 * booleans, about 768 bytes per chunk, and close to nothing in time, because an
 * {@link AtomicBoolean} is a plain allocation and not a synchronisation; dropping the light carriers
 * saves a multiple of that; and the shared section arm collapses the whole per chunk cost of sections
 * to the section array itself. If that ordering holds, the eager sections are the lever and the
 * atomic booleans are a rounding error on top of them.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * The allocation profiler is what this benchmark is actually about; the time column mostly reports
 * how fast the allocator is. It runs project wide, but is stated here so a single run reproduces the
 * numbers:
 * </p>
 * <pre>{@code
 * java -jar build/libs/falco-*-jmh.jar "SectionAllocationBenchmark" -prof gc -f 1 -wi 5 -i 5
 * }</pre>
 * <p>
 * Because the measured objects are small and numerous, the object header layout moves the result more
 * than any single field does. The second run is the one that says by how much:
 * </p>
 * <pre>{@code
 * java -jar build/libs/falco-*-jmh.jar "SectionAllocationBenchmark" -prof gc -f 1 -wi 5 -i 5 \
 *     -jvmArgsAppend -XX:+UseCompactObjectHeaders
 * }</pre>
 * <p>
 * The saving of compact headers is zero or eight bytes per class and never a percentage, so the two
 * runs have to be reported as two numbers per arm rather than as one number and a factor.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class SectionAllocationBenchmark {

    /**
     * The chunk X every arm builds at.
     * All arms share it so that the entity tracker of the instance answers from the same key for all
     * of them and the key never becomes a difference between two arms.
     */
    private static final int CHUNK_X = 0;

    /**
     * The chunk Z every arm builds at.
     */
    private static final int CHUNK_Z = 0;

    /**
     * The amount of blocks a section holds along one axis.
     * Used by the equivalence stage to walk the light of a section.
     */
    private static final int SECTION_SIZE = Chunk.CHUNK_SECTION_SIZE;

    /**
     * The message the prototype carriers refuse their calculate methods with.
     */
    private static final String NO_COMPUTATION =
            "This light carrier reproduces the storage of Minestom for an allocation benchmark and "
                    + "cannot compute light, because LightCompute#compute is package-private";

    /**
     * The message the shared carrier refuses every mutating call with.
     */
    private static final String SHARED =
            "This light carrier is shared by every section of the benchmark and must not be mutated";

    /**
     * The instance every chunk of every arm belongs to.
     * See the class documentation for why this is a {@link FalcoInstance} and why that choice does not
     * make this a comparison between Falco and Minestom.
     */
    private FalcoInstance instance;

    /**
     * The amount of sections a chunk of the configured dimension holds.
     * Read from the instance rather than assumed, and cross checked against
     * {@link BenchmarkConstants#OVERWORLD_SECTIONS}.
     */
    private int sectionCount;

    /**
     * The single air section all slots of {@link #sharedAirSection()} point at.
     * Built once per trial, so its cost is not part of any measurement, which is the whole point of the
     * arm.
     */
    private Section sharedAir;

    /**
     * Builds the instance, derives the section count and proves that all five arms produce the same
     * chunk.
     *
     * @throws IllegalStateException if the dimension does not hold the expected amount of sections or
     *                               if two arms disagree about the chunk they build
     */
    @Setup(Level.Trial)
    public void setUp() {
        MinestomChunks.ensureServer();
        this.instance = MinestomChunks.newFalcoInstance();
        this.sectionCount = this.instance.getCachedDimensionType().height() / SECTION_SIZE;

        if (this.sectionCount != BenchmarkConstants.OVERWORLD_SECTIONS) {
            throw new IllegalStateException("The fixture dimension holds " + this.sectionCount
                    + " sections but the benchmark documents " + BenchmarkConstants.OVERWORLD_SECTIONS
                    + "; the reported per chunk numbers would not describe an overworld chunk");
        }
        this.sharedAir = new Section();

        verifyEveryArmBuildsTheSameChunk();
    }

    /**
     * Releases the instance so the chunks and the tracker entry of this trial do not survive into the
     * next one.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        MinestomChunks.release(this.instance);
        this.instance = null;
        this.sharedAir = null;
    }

    /**
     * Measures the baseline: the chunk Minestom builds today, with 24 eager sections, 48 palettes, 48
     * light carriers and 48 atomic booleans.
     *
     * @return the constructed chunk, returned so the allocation cannot be eliminated
     */
    @Benchmark
    public Chunk minestomDynamicChunk() {
        return new DynamicChunk(this.instance, CHUNK_X, CHUNK_Z);
    }

    /**
     * Measures the control: the same 24 eager sections as the baseline, but with the light carrier of
     * this class, which holds the same fields as the pair it replaces and still allocates one
     * {@link AtomicBoolean} each.
     * <p>
     * The delta of this arm against {@link #minestomDynamicChunk()} has to be zero within the error
     * bars. It is the arm that decides whether the two arms below are allowed to be read as statements
     * about their subject.
     * </p>
     *
     * @return the constructed chunk, returned so the allocation cannot be eliminated
     */
    @Benchmark
    public Chunk eagerReplicaLight() {
        final Section[] sections = new Section[this.sectionCount];
        for (int index = 0; index < sections.length; index++) {
            sections[index] = new Section(Palette.blocks(), Palette.biomes(),
                    new ReplicaLight(), new ReplicaLight());
        }
        return new PrototypeChunk(this.instance, CHUNK_X, CHUNK_Z, List.of(sections));
    }

    /**
     * Measures the first candidate: everything of the control, with the 48 atomic booleans folded into
     * a packed integer field per light carrier.
     * <p>
     * Against the control this isolates the atomic booleans and nothing else. The light carrier keeps
     * its three byte array references and swaps one reference for one integer, which lands it in the
     * same size class, so the whole delta is the 48 objects that are gone.
     * </p>
     *
     * @return the constructed chunk, returned so the allocation cannot be eliminated
     */
    @Benchmark
    public Chunk packedFlagLight() {
        final Section[] sections = new Section[this.sectionCount];
        for (int index = 0; index < sections.length; index++) {
            sections[index] = new Section(Palette.blocks(), Palette.biomes(),
                    new PackedFlagLight(), new PackedFlagLight());
        }
        return new PrototypeChunk(this.instance, CHUNK_X, CHUNK_Z, List.of(sections));
    }

    /**
     * Measures the second candidate: 24 eager sections with real palettes, but no light carrier of
     * their own, pointing at a stateless shared one instead.
     * <p>
     * Against the control this isolates the 48 light carriers together with their 48 atomic booleans,
     * which is what a chunk that creates its light lazily would save. A chunk cannot do that with a
     * real {@link Section} once it has to light itself, because the light field of the record is final;
     * the arm therefore measures the ceiling of that saving rather than a design that is ready to be
     * shipped.
     * </p>
     *
     * @return the constructed chunk, returned so the allocation cannot be eliminated
     */
    @Benchmark
    public Chunk sharedUnlitLight() {
        final Section[] sections = new Section[this.sectionCount];
        for (int index = 0; index < sections.length; index++) {
            sections[index] = new Section(Palette.blocks(), Palette.biomes(),
                    SharedUnlitLight.INSTANCE, SharedUnlitLight.INSTANCE);
        }
        return new PrototypeChunk(this.instance, CHUNK_X, CHUNK_Z, List.of(sections));
    }

    /**
     * Measures the third candidate: no section of its own at all, every slot pointing at one shared air
     * section.
     * <p>
     * Against the control this isolates the 24 sections with their 48 palettes and 48 light carriers,
     * and against {@link #packedFlagLight()} it answers the question this benchmark exists for. The
     * section array and its immutable list are still allocated per invocation, exactly as in every
     * other arm, so the delta is the content of the array and never its shape.
     * </p>
     *
     * @return the constructed chunk, returned so the allocation cannot be eliminated
     */
    @Benchmark
    public Chunk sharedAirSection() {
        final Section[] sections = new Section[this.sectionCount];
        Arrays.fill(sections, this.sharedAir);
        return new PrototypeChunk(this.instance, CHUNK_X, CHUNK_Z, List.of(sections));
    }

    /**
     * Proves that all five arms build the same chunk before the first measurement is taken.
     * <p>
     * The blocks and both heightmaps are compared through {@code MinestomChunks#assertSameBlocks},
     * which walks every position of the chunk. On top of that the sections are compared directly,
     * because two chunks can answer the same to every block read and still differ in what their
     * sections hold: a palette that is empty against one that stores the air state explicitly reads the
     * same and costs a different amount of memory, and a light carrier that reports a level where the
     * baseline reports none would change what the chunk sends without changing what it stores.
     * </p>
     *
     * @throws IllegalStateException if any arm disagrees with the baseline
     */
    private void verifyEveryArmBuildsTheSameChunk() {
        final Chunk baseline = minestomDynamicChunk();

        assertSameChunk("eagerReplicaLight", baseline, eagerReplicaLight());
        assertSameChunk("packedFlagLight", baseline, packedFlagLight());
        assertSameChunk("sharedUnlitLight", baseline, sharedUnlitLight());
        assertSameChunk("sharedAirSection", baseline, sharedAirSection());
    }

    /**
     * Compares one arm against the baseline over its blocks, its heightmaps and its sections.
     *
     * @param arm      the name of the arm, used in the failure message
     * @param baseline the chunk the baseline arm built
     * @param actual   the chunk the compared arm built
     * @throws IllegalStateException if the two chunks differ
     */
    private void assertSameChunk(String arm, Chunk baseline, Chunk actual) {
        MinestomChunks.assertSameBlocks(baseline, actual);

        final List<Section> expectedSections = baseline.getSections();
        final List<Section> actualSections = actual.getSections();

        if (expectedSections.size() != actualSections.size()) {
            throw new IllegalStateException("The arm " + arm + " built " + actualSections.size()
                    + " sections while the baseline built " + expectedSections.size());
        }
        for (int index = 0; index < expectedSections.size(); index++) {
            assertSameSection(arm, index, expectedSections.get(index), actualSections.get(index));
        }
    }

    /**
     * Compares one section of an arm against the matching section of the baseline.
     * <p>
     * The palettes are compared over their dimension, their bit width and their entry count rather than
     * over a walk of their values, because the block walk of the caller already covers the values and
     * these three are what decides how much the palette costs. The light is compared over every
     * position of the section, together with the length of the array it would send and the two flags it
     * answers with, since those are the only observable state an unlit carrier has.
     * </p>
     *
     * @param arm      the name of the arm, used in the failure message
     * @param index    the index of the section inside the chunk
     * @param expected the section of the baseline chunk
     * @param actual   the section of the compared chunk
     * @throws IllegalStateException if the two sections differ
     */
    private void assertSameSection(String arm, int index, Section expected, Section actual) {
        assertSamePalette(arm, index, "block", expected.blockPalette(), actual.blockPalette());
        assertSamePalette(arm, index, "biome", expected.biomePalette(), actual.biomePalette());
        assertSameLight(arm, index, "sky", expected.skyLight(), actual.skyLight());
        assertSameLight(arm, index, "block", expected.blockLight(), actual.blockLight());
    }

    /**
     * Compares one palette of a section against the matching palette of the baseline.
     *
     * @param arm      the name of the arm, used in the failure message
     * @param index    the index of the section inside the chunk
     * @param kind     the role of the palette, used in the failure message
     * @param expected the palette of the baseline section
     * @param actual   the palette of the compared section
     * @throws IllegalStateException if the two palettes differ
     */
    private void assertSamePalette(String arm, int index, String kind, Palette expected, Palette actual) {
        if (expected.dimension() == actual.dimension()
                && expected.bitsPerEntry() == actual.bitsPerEntry()
                && expected.count() == actual.count()) {
            return;
        }
        throw new IllegalStateException("The arm " + arm + " holds a different " + kind
                + " palette in section " + index + ": expected dimension " + expected.dimension()
                + ", " + expected.bitsPerEntry() + " bits and " + expected.count() + " entries but got "
                + actual.dimension() + ", " + actual.bitsPerEntry() + " bits and " + actual.count()
                + " entries");
    }

    /**
     * Compares one light carrier of a section against the matching carrier of the baseline.
     * <p>
     * {@code requiresSend} is asked once and only on the chunks of this stage, never on a measured one,
     * because the call clears the flag it reports.
     * </p>
     *
     * @param arm      the name of the arm, used in the failure message
     * @param index    the index of the section inside the chunk
     * @param kind     the role of the carrier, used in the failure message
     * @param expected the light carrier of the baseline section
     * @param actual   the light carrier of the compared section
     * @throws IllegalStateException if the two carriers differ
     */
    private void assertSameLight(String arm, int index, String kind, Light expected, Light actual) {
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    final int expectedLevel = expected.getLevel(x, y, z);
                    final int actualLevel = actual.getLevel(x, y, z);

                    if (expectedLevel == actualLevel) {
                        continue;
                    }
                    throw new IllegalStateException("The arm " + arm + " holds a different " + kind
                            + " light level in section " + index + " at x=" + x + " y=" + y + " z=" + z
                            + ": expected " + expectedLevel + " but got " + actualLevel);
                }
            }
        }
        if (expected.array().length != actual.array().length) {
            throw new IllegalStateException("The arm " + arm + " would send a different " + kind
                    + " light array for section " + index + ": expected " + expected.array().length
                    + " bytes but got " + actual.array().length);
        }
        if (expected.requiresUpdate() != actual.requiresUpdate() || expected.requiresSend() != actual.requiresSend()) {
            throw new IllegalStateException("The arm " + arm + " reports different " + kind
                    + " light flags for section " + index);
        }
    }

    /**
     * The {@link PrototypeChunk} class is a {@link DynamicChunk} that accepts a prepared list of
     * sections.
     * <p>
     * It adds no field, no override and no behaviour. Its only reason to exist is that the constructor
     * of {@link DynamicChunk} which takes a section list is protected, so the sections of an arm cannot
     * be handed to a chunk from the outside. Everything else the chunk does, from the heightmaps over
     * the block entity maps to the packet cache, is the inherited original, which is what keeps an arm
     * comparable to the baseline.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    private static final class PrototypeChunk extends DynamicChunk {

        /**
         * Creates a chunk over a prepared list of sections.
         *
         * @param instance the instance the chunk belongs to
         * @param chunkX   the chunk X
         * @param chunkZ   the chunk Z
         * @param sections the sections the chunk is built over
         */
        private PrototypeChunk(Instance instance, int chunkX, int chunkZ, List<Section> sections) {
            super(instance, chunkX, chunkZ, sections);
        }
    }

    /**
     * The {@link ReplicaLight} class reproduces the storage of {@code BlockLight} and {@code SkyLight}
     * field for field, including the {@link AtomicBoolean} that holds the send flag.
     * <p>
     * It exists so that the arms below it differ from the baseline in one thing at a time. Minestom
     * keeps both of its carriers package-private, so an arm that wants to change one field of them has
     * to bring its own carrier, and a carrier that is brought in has to be proven equal in footprint
     * before any of its variants may be read as a saving. That proof is the control arm.
     * </p>
     * <p>
     * What is deliberately not reproduced is the light computation. {@code LightCompute#compute} is
     * package-private, so this class cannot run the search, and both calculate methods refuse instead
     * of returning something cheaper than the original would.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    private static final class ReplicaLight implements Light {

        /**
         * The computed light of the section, null until the section is lit, as in the original.
         */
        private byte[] content;

        /**
         * The light propagated in from the neighbours, null until the section is lit.
         */
        private byte[] contentPropagation;

        /**
         * The staging buffer the original swaps in on {@code flip}.
         */
        private byte[] contentPropagationSwap;

        /**
         * Whether the borders of the section are still valid, volatile as in the original.
         */
        private volatile boolean isValidBorders = true;

        /**
         * Whether the section still has to be sent, in the one object per flag form under test.
         */
        private final AtomicBoolean needsSend = new AtomicBoolean(false);

        @Override
        public void flip() {
            if (this.contentPropagationSwap != null) {
                this.contentPropagation = this.contentPropagationSwap;
            }
            this.contentPropagationSwap = null;
        }

        @Override
        public void invalidate() {
            this.needsSend.set(true);
            this.isValidBorders = false;
            this.contentPropagation = null;
        }

        @Override
        public boolean requiresUpdate() {
            return !this.isValidBorders;
        }

        @Override
        public void set(byte[] copyArray) {
            this.content = copyArray;
            this.contentPropagation = copyArray;
            this.isValidBorders = true;
            this.needsSend.set(true);
        }

        @Override
        public boolean requiresSend() {
            return this.needsSend.getAndSet(false);
        }

        @Override
        public byte[] array() {
            return this.content == null ? LightCompute.UNSET_CONTENT : this.content;
        }

        @Override
        public int getLevel(int x, int y, int z) {
            return nibble(this.content, x | (z << 4) | (y << 8));
        }

        @Override
        public Set<Point> calculateInternal(Palette blockPalette,
                                            int chunkX, int chunkY, int chunkZ,
                                            int[] heightmap, int maxY,
                                            LightLookup lightLookup) {
            throw new UnsupportedOperationException(NO_COMPUTATION);
        }

        @Override
        public Set<Point> calculateExternal(Palette blockPalette,
                                            Point[] neighbors,
                                            LightLookup lightLookup,
                                            PaletteLookup paletteLookup) {
            throw new UnsupportedOperationException(NO_COMPUTATION);
        }
    }

    /**
     * The {@link PackedFlagLight} class is the {@link ReplicaLight} with its {@link AtomicBoolean}
     * folded into a packed integer field that is updated through a {@link VarHandle}.
     * <p>
     * This is the candidate the downgraded claim is about. The carrier trades one reference for one
     * integer, which does not change its size class, so the entire difference against the control is
     * the 48 atomic booleans a chunk no longer allocates. The atomicity is kept rather than dropped:
     * {@code requiresSend} still reads and clears in one step, because the send flag is written by the
     * lighting thread and read by the thread that builds the packet, and a benchmark that silently
     * removed that guarantee would be comparing a correct chunk against a broken one.
     * </p>
     * <p>
     * Two bits are enough here, and the same technique scales further than this arm shows: a chunk
     * could hold the flags of all 48 carriers in a single long. That variant is not measured, because
     * it changes the ownership of the flag as well as its representation and would no longer isolate
     * one cause.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    private static final class PackedFlagLight implements Light {

        /**
         * The bit that holds what the original keeps in its atomic boolean.
         */
        private static final int NEEDS_SEND = 1;

        /**
         * The bit that holds what the original keeps in its volatile boolean.
         */
        private static final int VALID_BORDERS = 1 << 1;

        /**
         * The handle the flag field is updated through.
         */
        private static final VarHandle FLAGS;

        static {
            try {
                FLAGS = MethodHandles.lookup().findVarHandle(PackedFlagLight.class, "flags", int.class);
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        /**
         * The computed light of the section, null until the section is lit.
         */
        private byte[] content;

        /**
         * The light propagated in from the neighbours, null until the section is lit.
         */
        private byte[] contentPropagation;

        /**
         * The staging buffer the original swaps in on {@code flip}.
         */
        private byte[] contentPropagationSwap;

        /**
         * Both flags of the original in one field, starting with valid borders and nothing to send.
         */
        @SuppressWarnings("unused")
        private volatile int flags = VALID_BORDERS;

        @Override
        public void flip() {
            if (this.contentPropagationSwap != null) {
                this.contentPropagation = this.contentPropagationSwap;
            }
            this.contentPropagationSwap = null;
        }

        @Override
        public void invalidate() {
            update(NEEDS_SEND, VALID_BORDERS);
            this.contentPropagation = null;
        }

        @Override
        public boolean requiresUpdate() {
            return ((int) FLAGS.getVolatile(this) & VALID_BORDERS) == 0;
        }

        @Override
        public void set(byte[] copyArray) {
            this.content = copyArray;
            this.contentPropagation = copyArray;
            update(NEEDS_SEND | VALID_BORDERS, 0);
        }

        @Override
        public boolean requiresSend() {
            int witness = (int) FLAGS.getVolatile(this);
            while ((witness & NEEDS_SEND) != 0) {
                final int updated = witness & ~NEEDS_SEND;
                final int seen = (int) FLAGS.compareAndExchange(this, witness, updated);

                if (seen == witness) {
                    return true;
                }
                witness = seen;
            }
            return false;
        }

        @Override
        public byte[] array() {
            return this.content == null ? LightCompute.UNSET_CONTENT : this.content;
        }

        @Override
        public int getLevel(int x, int y, int z) {
            return nibble(this.content, x | (z << 4) | (y << 8));
        }

        @Override
        public Set<Point> calculateInternal(Palette blockPalette,
                                            int chunkX, int chunkY, int chunkZ,
                                            int[] heightmap, int maxY,
                                            LightLookup lightLookup) {
            throw new UnsupportedOperationException(NO_COMPUTATION);
        }

        @Override
        public Set<Point> calculateExternal(Palette blockPalette,
                                            Point[] neighbors,
                                            LightLookup lightLookup,
                                            PaletteLookup paletteLookup) {
            throw new UnsupportedOperationException(NO_COMPUTATION);
        }

        /**
         * Sets and clears bits of the flag field in one atomic step.
         *
         * @param set   the bits to set
         * @param clear the bits to clear
         */
        private void update(int set, int clear) {
            int witness = (int) FLAGS.getVolatile(this);
            while (true) {
                final int updated = (witness | set) & ~clear;

                if (updated == witness) {
                    return;
                }
                final int seen = (int) FLAGS.compareAndExchange(this, witness, updated);

                if (seen == witness) {
                    return;
                }
                witness = seen;
            }
        }
    }

    /**
     * The {@link SharedUnlitLight} class is a stateless carrier every section of the
     * {@link #sharedUnlitLight()} arm points at.
     * <p>
     * It answers exactly what a freshly constructed carrier of Minestom answers, which is what makes
     * that arm pass the equivalence stage: no light anywhere, an empty array, nothing to send and
     * nothing to update. It holds no field, so it can be shared, and it is the ceiling of what a chunk
     * saves by not creating light carriers until something lights it.
     * </p>
     * <p>
     * Every mutating method refuses. A shared carrier that accepted a write would corrupt every section
     * of every chunk of the run, and a construction benchmark that lit something would no longer be
     * measuring construction, so failing is the only honest answer here.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    private static final class SharedUnlitLight implements Light {

        /**
         * The one carrier every section of the arm shares.
         */
        private static final SharedUnlitLight INSTANCE = new SharedUnlitLight();

        /**
         * Blocks a second instance because the class is a flyweight.
         */
        private SharedUnlitLight() {
        }

        @Override
        public void flip() {
            throw new UnsupportedOperationException(SHARED);
        }

        @Override
        public void invalidate() {
            throw new UnsupportedOperationException(SHARED);
        }

        @Override
        public boolean requiresUpdate() {
            return false;
        }

        @Override
        public void set(byte[] copyArray) {
            throw new UnsupportedOperationException(SHARED);
        }

        @Override
        public boolean requiresSend() {
            return false;
        }

        @Override
        public byte[] array() {
            return LightCompute.UNSET_CONTENT;
        }

        @Override
        public int getLevel(int x, int y, int z) {
            return 0;
        }

        @Override
        public Set<Point> calculateInternal(Palette blockPalette,
                                            int chunkX, int chunkY, int chunkZ,
                                            int[] heightmap, int maxY,
                                            LightLookup lightLookup) {
            throw new UnsupportedOperationException(SHARED);
        }

        @Override
        public Set<Point> calculateExternal(Palette blockPalette,
                                            Point[] neighbors,
                                            LightLookup lightLookup,
                                            PaletteLookup paletteLookup) {
            throw new UnsupportedOperationException(SHARED);
        }
    }

    /**
     * Reads one light level out of a packed nibble array, in the layout of {@code LightCompute#getLight}.
     * <p>
     * The method of Minestom is package-private, so the layout is repeated here rather than called. It
     * is four bits per position, two positions per byte, low nibble first.
     * </p>
     *
     * @param content the packed array, null when the section was never lit
     * @param index   the position inside the section
     * @return the light level, zero when the section was never lit or the array is too short
     */
    private static int nibble(byte[] content, int index) {
        if (content == null || index >>> 1 >= content.length) {
            return 0;
        }
        return (content[index >>> 1] >>> ((index & 1) << 2)) & 0xF;
    }
}
