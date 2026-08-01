package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import net.onelitefeather.falco.benchmark.support.MinestomChunks.FillShape;
import net.onelitefeather.falco.instance.FalcoInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ChunkFootprintTest} class measures how many bytes a single chunk of Minestom really
 * retains, with JOL, and prints the result as a table that can be copied into the documentation.
 * <p>
 * It exists because every memory number this project has written down so far is an estimate. The
 * research report of 2026-08-01 says so itself: its whole balance sheet — roughly one hundred and
 * seventy objects per chunk, six to ten kibibytes empty, about two hundred kibibytes once a
 * generator has filled it — comes from layout arithmetic over assumed object sizes, and the same
 * kind of arithmetic had already put one break-even point wrong by a factor of two. This class
 * replaces all of those numbers with measured ones. Nothing here is a benchmark: no time is taken,
 * no warmup is needed for the subject, and the quantity of interest is bytes on the heap, which is
 * exactly what {@code -prof gc} cannot tell anyone, because allocation rate is not footprint.
 * </p>
 *
 * <h2>Why a chunk cannot simply be handed to JOL</h2>
 * <p>
 * {@code GraphLayout.parseInstance(chunk).totalSize()} answers the wrong question. A chunk holds a
 * reference to its {@code Instance} ({@code Chunk.java:37}), the instance holds an entity tracker, a
 * scheduler, an event node and a world border, and a reachability walk that starts at the chunk
 * therefore reports the weight of the world the chunk lives in. On the pinned build that is roughly
 * eight hundred objects of instance against under two hundred of chunk, so the number would be
 * dominated by the part nobody asked about, and it would drift with every field Minestom adds to
 * {@code Instance}.
 * </p>
 * <p>
 * What this class reports instead is the difference between two walks: everything reachable from the
 * chunk <em>and</em> the instance together, minus everything reachable from the instance alone. Both
 * walks deduplicate by object identity internally, so the difference is exactly the set of objects
 * that exist because the chunk exists — and it is computed from two independent totals rather than
 * from {@code GraphLayout#subtract}, which matches objects by their address and therefore silently
 * miscounts every object the garbage collector moved between the two snapshots.
 * </p>
 * <p>
 * The boundary that draws is worth stating, because it is a result in itself: anything a chunk
 * causes to be allocated but stores <em>outside</em> itself falls on the instance side and is not in
 * these numbers. That is not an oversight, it is the only honest split, and the part that falls
 * outside is measured separately by {@link #aChunkAlsoCostsBytesInsideItsInstance()}.
 * </p>
 *
 * <h2>Why every measurement is taken twice</h2>
 * <p>
 * JOL caches the reflective field list of every class it meets, and that cache hangs off the very
 * {@code Class} objects the walk traverses. The first walk over a shape therefore allocates
 * metadata that the second walk finds already there, which showed up as a thirty object, sixteen
 * hundred byte difference between the first and the second measurement of the same chunk. Every
 * figure below is consequently taken from a second pass whose first pass is discarded, after which
 * repeated measurements of the same chunk are byte for byte identical.
 * </p>
 *
 * <h2>What JOL needs to work here, and why the build says so rather than this class</h2>
 * <p>
 * Two options, and both of them are JVM arguments of the test task in
 * {@code falco-benchmarks/build.gradle.kts}. The measurement of an object size goes through the
 * instrumentation agent JOL attaches to the running JVM, which is why the build passes
 * {@code -Djdk.attach.allowAttachSelf=true} and {@code -XX:+EnableDynamicAgentLoading}. Without them
 * JOL keeps answering, from a layout model instead of from the JVM, and a modelled number under
 * {@code -XX:+UseCompactObjectHeaders} is a guess about a feature the model may not know.
 * </p>
 * <p>
 * The second is less pleasant. On JDK 25 a plain graph walk that reaches a record class inside
 * {@code java.base} dies with {@code Cannot get the field offset}, because
 * {@code Unsafe#objectFieldOffset} refuses record classes. The walk gets there through any
 * {@code Class} field — an {@code EventNode} holds one, so every instance of this server has that
 * path — and JOL only survives it with {@code -Djol.magicFieldOffset=true}, which lets it reach
 * {@code jdk.internal.misc.Unsafe} instead.
 * </p>
 * <p>
 * That second option used to be set from a static initialiser of this class, and doing so is what
 * made these three measurements flaky. JOL reads the option exactly once, in the class initialiser of
 * its {@code HotspotUnsafe}, which runs the first time anything in the JVM touches JOL. This class
 * shares its test JVM with {@link PaletteFootprintTest} and {@link EmptySectionCensusTest}, which walk
 * object graphs too, and the order JUnit runs test classes in is not specified — it falls out of
 * classpath scanning and changes whenever the class files are rewritten. Ran this class first, the
 * property arrived in time and all three tests passed; ran one of the others first, JOL had already
 * cached {@code false} and all three failed with {@code Cannot get the field offset}. Same code, same
 * machine, both outcomes. A JVM argument has no such ordering, which is why the option now lives in
 * the build, and {@link JolMeasurement} reads back what JOL actually decided so a lost flag fails with
 * that sentence instead of with a stack trace.
 * </p>
 * <p>
 * One warning appears in the log and is harmless: JOL cannot attach the Serviceability Agent under
 * the default {@code ptrace_scope} of Linux and says that computed <em>addresses</em> are guesses.
 * Nothing here uses addresses — not {@code toPrintable}, not {@code toImage}, and deliberately not
 * {@code subtract} — so the warning does not touch a single number in these tables. The header of
 * every table states that it did not attach, next to the mode the sizes did come from.
 * </p>
 *
 * <h2>Why the header mode and the measurement mode are printed with every table</h2>
 * <p>
 * {@code -XX:+UseCompactObjectHeaders} (JEP 519, final in 25 but off by default) changes the object
 * header from twelve bytes to eight, and the research report is explicit that the gain is zero or
 * eight bytes per class and never a percentage. A footprint quoted without its header mode is
 * therefore not a measurement of anything, and the table header states both the mode the build
 * declared through {@code -Pfalco.compactHeaders} and the header size JOL actually observed. When
 * the two disagree the test fails, because a mislabelled number is worse than no number.
 * </p>
 * <p>
 * The same holds for where the bytes came from. JOL has two ways of answering how large an object is
 * and chooses between them at runtime, so every table also names the one that was used, read out of
 * JOL rather than assumed. A run that cannot size through the instrumentation agent stops with an
 * assumption instead of printing modelled numbers under a heading that claims otherwise;
 * {@link JolMeasurement} is where that decision is made and explained.
 * </p>
 *
 * <h2>Which assertions are hard, and which are not</h2>
 * <p>
 * The object <em>count</em> of a fresh chunk can be read off the source: twenty-four sections, two
 * palettes and two light carriers each, one {@code AtomicBoolean} per light carrier. Those counts
 * are asserted exactly, and a change in any of them is a structural change in Minestom that this
 * project wants to be told about. The byte figures are not asserted that way. They move with the
 * JDK, with the header mode and with the object alignment, and a test that turns red on a JDK
 * upgrade teaches nobody anything, so bytes are only bounded generously — an empty chunk is
 * asserted to be kibibytes rather than megabytes, and a chunk whose palettes have gone direct is
 * asserted to be dominated by the twenty-four {@code long[1024]} arrays that arithmetic says must be
 * there.
 * </p>
 * <p>
 * One comparison is asserted strictly, and it is the one this stage exists for: {@code FalcoChunk}
 * must weigh exactly what {@code DynamicChunk} weighs, plus the one object the seam costs. A
 * deviation would not be a tolerance to widen, it would be a finding.
 * </p>
 *
 * <h2>What the seam costs, and why the delta is not zero</h2>
 * <p>
 * Until the storage moved, {@code FalcoChunk} extended {@code DynamicChunk} and declared no field of
 * its own, so the delta was zero by construction and this class asserted it as such. It no longer
 * is. {@code FalcoChunk} now holds a {@link net.onelitefeather.falco.instance.BlockStorage} instead
 * of inheriting a section list, and an indirection is an object: the
 * {@code SectionBlockStorage} that holds {@code minSection} and the same
 * {@code List.of(Section...)} the old chunk held directly. On the pinned build that object is
 * {@code 24} bytes, which is what these tables report as {@code DELTA B} and what the assertions
 * below expect.
 * </p>
 * <p>
 * Zero was never reachable while the storage is a separate type, and buying it back would mean
 * letting the chunk implement its own storage — which is exactly the coupling stage 1 paid to
 * remove, because it is what kept {@code FalcoChunk} and {@code FalcoLightingChunk} from ever being
 * combined. So the number is not rounded away and not tolerated either: the assertion names the
 * class the extra object belongs to and requires every extra byte to come from it. A second object,
 * or a byte from anywhere else, still fails — which is the property the old equality had and the
 * reason it is replaced rather than deleted.
 * </p>
 * <p>
 * For scale, next to the figures below: {@code 24} bytes against the {@code 6848} of a fresh chunk
 * is {@code 0,35} percent, and against a generated chunk of roughly two hundred kibibytes it is
 * {@code 0,01} percent. The stage that follows this one removes thousands of bytes per empty chunk,
 * so the seam is paid for many times over — but it is paid, and a reader of this table should see
 * the price rather than a zero.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * The tables go to standard output, which Gradle only shows at info level, so the {@code -i} is part
 * of the command rather than an extra:
 * </p>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest" -i
 * ./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest" -Pfalco.compactHeaders -i
 * }</pre>
 * <p>
 * The two runs answer the same question under the two header modes, and a number from one of them
 * must never be quoted next to a number from the other.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.4.0
 */
@DisplayName("The retained size of a chunk, measured with JOL")
@ResourceLock(Resources.GLOBAL)
class ChunkFootprintTest {

    /**
     * The property the build sets from {@code -Pfalco.compactHeaders}.
     */
    private static final String COMPACT_HEADERS = "falco.compactHeaders";

    /**
     * The object header size the JVM uses when compact object headers are enabled.
     */
    private static final int COMPACT_HEADER_SIZE = 8;

    /**
     * The object header size the JVM uses without compact object headers.
     */
    private static final int LEGACY_HEADER_SIZE = 12;

    /**
     * The distinct block state counts the footprint is measured over.
     * <p>
     * The axis of the research report. It ends at {@code 1024} because that is where a palette has
     * long left the indirect mode behind and every section stores fifteen bits per entry, which is
     * the state the report claims a generated chunk is permanently stuck in.
     * </p>
     */
    private static final int[] STATE_COUNTS = {1, 16, 64, 256, 1024};

    /**
     * How many chunks the instance side measurement builds.
     * <p>
     * More than one, because the first chunk pays for the hash table its entry lands in and would
     * report a per chunk cost roughly ten times the real one.
     * </p>
     */
    private static final int TRACKER_CHUNKS = 16;

    /**
     * The width of the label column of the breakdown table.
     */
    private static final int LABEL_WIDTH = 52;

    private static final String SECTION = "net.minestom.server.instance.Section";
    private static final String PALETTE = "net.minestom.server.instance.palette.PaletteImpl";
    private static final String PALETTE_INDEX_LIST = "it.unimi.dsi.fastutil.ints.IntArrayList";
    private static final String PALETTE_REVERSE_MAP = "it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap";
    private static final String SKY_LIGHT = "net.minestom.server.instance.light.SkyLight";
    private static final String BLOCK_LIGHT = "net.minestom.server.instance.light.BlockLight";
    private static final String NEEDS_SEND = "java.util.concurrent.atomic.AtomicBoolean";
    private static final String MOTION_BLOCKING = "net.minestom.server.instance.heightmap.MotionBlockingHeightmap";
    private static final String WORLD_SURFACE = "net.minestom.server.instance.heightmap.WorldSurfaceHeightmap";
    private static final String BLOCK_INDEX_MAP = "it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap";
    private static final String BLOCK_STORAGE = "net.onelitefeather.falco.instance.SectionBlockStorage";
    private static final String HEIGHTS = "[S";
    private static final String PACKED_VALUES = "[J";
    private static final String INT_ARRAY = "[I";
    private static final String LIGHT_ARRAY = "[B";

    /**
     * The instance the Minestom side of every comparison is built in.
     */
    private static InstanceContainer container;

    /**
     * The instance the Falco side of every comparison is built in.
     */
    private static FalcoInstance falco;

    /**
     * Starts the server once and creates the two instances the chunks are built in.
     */
    @BeforeAll
    static void setUp() {
        MinestomChunks.ensureServer();
        container = MinestomChunks.newContainer();
        falco = MinestomChunks.newFalcoInstance();
    }

    /**
     * Releases the two instances so they do not outlive the class.
     */
    @AfterAll
    static void tearDown() {
        MinestomChunks.release(container);
        MinestomChunks.release(falco);
        container = null;
        falco = null;
    }

    /**
     * Measures a chunk that has never been written to and prints the full breakdown.
     * <p>
     * This is the number the research report calls the fixed cost of a chunk, and the one every
     * argument about lazy sections, shared empty sections and a leaner light representation has to
     * be measured against. It is taken for both chunk types at once, because the second finding of
     * this method is what the two cost against each other: {@code FalcoChunk} holds one object the
     * {@code DynamicChunk} does not, its {@code BlockStorage}, and nothing else. Any further object
     * would be one added behind this project's back.
     * </p>
     * <p>
     * The asserted counts are the ones the source dictates rather than the ones that happened to be
     * measured. Twenty-four sections come from {@code DynamicChunk.java:61-67}, the two palettes and
     * the two light carriers per section from {@code Section.java:11-13}, and the one
     * {@code AtomicBoolean} per light carrier from the {@code needsSend} field of {@code BlockLight}
     * and {@code SkyLight}. All five are package-private types, which is why they are named by
     * string here — a test that had to live inside {@code net.minestom.server.instance} to count
     * them would be a heavier coupling than the count is worth.
     * </p>
     */
    @Test
    @DisplayName("A fresh chunk holds the objects the source declares, and Falco adds none")
    void aFreshChunkHoldsTheObjectsTheSourceDeclares() {
        JolMeasurement.require();

        final Chunk minestomChunk = MinestomChunks.newChunk(container, 0, 0);
        final Chunk falcoChunk = MinestomChunks.newChunk(falco, 0, 0);
        MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);

        final Footprint minestom = measure(minestomChunk, container);
        final Footprint falcoFootprint = measure(falcoChunk, falco);
        final Footprint sections = measureSections(minestomChunk);

        final StringBuilder out = new StringBuilder();
        appendHeader(out, "a fresh chunk, before a single block is set");
        appendProfileHeader(out);
        appendProfileRow(out, "fresh", "-", "-", minestom, falcoFootprint);
        out.append(System.lineSeparator());
        appendBreakdown(out, minestomChunk, minestom, sections);
        appendClassTable(out, minestom);
        report(out);

        assertTheSeamIsTheOnlyDifference(minestom, minestomChunk, falcoFootprint, falcoChunk,
                "a fresh chunk");
        assertEquals(ClassLayout.parseInstance(minestomChunk).instanceSize(),
                ClassLayout.parseInstance(falcoChunk).instanceSize(),
                "The two chunk objects themselves must have the same shallow size");

        assertEquals(24, minestom.objectsOf(SECTION), "sections per overworld chunk");
        assertEquals(48, minestom.objectsOf(PALETTE), "palettes per overworld chunk, one for blocks and one for biomes per section");
        assertEquals(24, minestom.objectsOf(SKY_LIGHT), "sky light carriers per overworld chunk");
        assertEquals(24, minestom.objectsOf(BLOCK_LIGHT), "block light carriers per overworld chunk");
        assertEquals(48, minestom.objectsOf(NEEDS_SEND), "AtomicBoolean flags per overworld chunk, one per light carrier");
        assertEquals(1, minestom.objectsOf(MOTION_BLOCKING), "motion blocking heightmaps per chunk");
        assertEquals(1, minestom.objectsOf(WORLD_SURFACE), "world surface heightmaps per chunk");
        assertEquals(2, minestom.objectsOf(BLOCK_INDEX_MAP), "block index maps per chunk, entries and tickableMap");
        assertEquals(0, minestom.objectsOf(PACKED_VALUES),
                "an untouched palette has bitsPerEntry == 0 and must not own a backing array yet");

        assertTrue(minestom.objects() > 150 && minestom.objects() < 300,
                "A fresh chunk retained " + minestom.objects() + " objects, which is nowhere near the "
                        + "roughly two hundred the section structure of Minestom dictates");
        assertTrue(minestom.bytes() > 4_096 && minestom.bytes() < 32_768,
                "A fresh chunk retained " + minestom.bytes() + " bytes, which is outside the kibibyte "
                        + "range object headers alone can produce");
    }

    /**
     * Measures the footprint over the distinct state count and the arrangement of the states.
     * <p>
     * Two axes, because the palette is a compressor and both of its inputs decide what it costs. The
     * state count decides how many bits an entry needs and whether the palette stays indirect at
     * all; the arrangement decides how many distinct states a single section actually sees, which is
     * why {@link FillShape#LAYERED} can be handed a thousand states and still leave every section on
     * four bits. The measured distinct count is printed next to the requested one so that no row of
     * this table can be read as an answer to a question it did not ask.
     * </p>
     * <p>
     * The two sides are proved equal before the first number is taken, through
     * {@code MinestomChunks#assertSameBlocks}, which walks every position and both heightmaps and
     * throws. A footprint comparison of two chunks with different content is not a comparison.
     * </p>
     * <p>
     * The {@code fresh} row is measured on both sides rather than printed twice from the Minestom
     * side. It used to be the latter, which cost nothing while the delta was zero and would now print
     * a zero in a column where every other row shows what the seam costs — a row that reports a
     * difference it never measured.
     * </p>
     */
    @Test
    @DisplayName("The state count and the arrangement decide the bytes, not the block count")
    void theStateCountAndTheShapeDecideTheBytes() {
        JolMeasurement.require();

        final Chunk freshChunk = MinestomChunks.newChunk(container, 0, 0);
        final Chunk freshFalcoChunk = MinestomChunks.newChunk(falco, 0, 0);
        final Footprint fresh = measure(freshChunk, container);
        final Footprint freshFalco = measure(freshFalcoChunk, falco);

        final StringBuilder out = new StringBuilder();
        appendHeader(out, "one chunk over the distinct state count and the arrangement");
        appendProfileHeader(out);
        appendProfileRow(out, "fresh", "-", "-", fresh, freshFalco);
        assertTheSeamIsTheOnlyDifference(fresh, freshChunk, freshFalco, freshFalcoChunk, "fresh");

        long directModeBytes = 0;
        long layeredAtLargestCount = 0;

        for (int states : STATE_COUNTS) {
            for (FillShape shape : FillShape.values()) {
                final Chunk minestomChunk = MinestomChunks.newChunk(container, 0, 0);
                final Chunk falcoChunk = MinestomChunks.newChunk(falco, 0, 0);
                MinestomChunks.fill(minestomChunk, states, shape);
                MinestomChunks.fill(falcoChunk, states, shape);
                MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);

                final Footprint minestom = measure(minestomChunk, container);
                final Footprint falcoFootprint = measure(falcoChunk, falco);
                appendProfileRow(out, shape.name(), Integer.toString(states),
                        Integer.toString(MinestomChunks.countDistinctStates(minestomChunk)),
                        minestom, falcoFootprint);

                assertTheSeamIsTheOnlyDifference(minestom, minestomChunk, falcoFootprint, falcoChunk,
                        states + " states in " + shape);
                assertTrue(minestom.bytes() >= fresh.bytes(),
                        "A filled chunk cannot retain less than an empty one, " + states + " states in " + shape);

                if (states == STATE_COUNTS[STATE_COUNTS.length - 1] && shape == FillShape.UNIFORM) {
                    directModeBytes = minestom.bytes();
                }
                if (states == STATE_COUNTS[STATE_COUNTS.length - 1] && shape == FillShape.LAYERED) {
                    layeredAtLargestCount = minestom.bytes();
                }
            }
        }
        out.append(System.lineSeparator());
        out.append(" The registry of the pinned build holds ").append(MinestomChunks.availableBlocks())
                .append(" blocks which are neither air nor a block entity. Above that count the fill")
                .append(System.lineSeparator())
                .append(" falls back to further states of the same blocks, so the two halves of a curve")
                .append(" over that boundary answer slightly").append(System.lineSeparator())
                .append(" different questions. LAYERED shows at most sixteen states per section and")
                .append(" therefore never reaches the largest count.").append(System.lineSeparator());
        report(out);

        assertTrue(directModeBytes > 100 * 1024 && directModeBytes < 400 * 1024,
                "A chunk whose sections all went direct retained " + directModeBytes + " bytes, while the "
                        + "twenty-four long[1024] arrays it must hold are already " + (24 * 8208) + " bytes");
        assertTrue(layeredAtLargestCount * 2 < directModeBytes,
                "The arrangement has to matter: a layered chunk retained " + layeredAtLargestCount
                        + " bytes against " + directModeBytes + " for the same state count spread uniformly");
    }

    /**
     * Measures what a chunk costs in the instance it was built for rather than in itself.
     * <p>
     * The constructor of {@code Chunk} asks the entity tracker of its instance for a viewable
     * ({@code Chunk.java:74-76}), and the tracker caches that view under a key built from the chunk
     * position. Nothing ever removes it — neither unloading a chunk nor dropping the last reference
     * to it — so the bytes reported here are retained by the instance for as long as the instance
     * lives, and they belong to no chunk that could be measured. They are invisible in every other
     * table of this class by construction, which is the reason this one exists.
     * </p>
     * <p>
     * The second finding is the difference between the two instance types. An
     * {@code InstanceContainer} hands the tracker a fresh {@code unmodifiableList} of its shared
     * instances on every chunk construction, while a {@link FalcoInstance} is not an
     * {@code InstanceContainer} and gets the {@code List.of()} singleton, so the container side
     * retains one wrapper object per chunk that the Falco side does not. The report predicted that
     * difference from the code; this is the measurement of it.
     * </p>
     */
    @Test
    @DisplayName("A chunk also costs bytes inside the instance it was built for")
    void aChunkAlsoCostsBytesInsideItsInstance() {
        JolMeasurement.require();

        final InstanceContainer freshContainer = MinestomChunks.newContainer();
        final FalcoInstance freshFalco = MinestomChunks.newFalcoInstance();
        try {
            final long[] containerCost = costInsideInstance(freshContainer);
            final long[] falcoCost = costInsideInstance(freshFalco);

            final StringBuilder out = new StringBuilder();
            appendHeader(out, "what constructing " + TRACKER_CHUNKS + " chunks adds to the instance itself");
            out.append(String.format(Locale.ROOT, " %-" + LABEL_WIDTH + "s %9s %11s %11s%n",
                    "INSTANCE TYPE", "OBJECTS", "BYTES", "B/CHUNK"));
            appendInstanceRow(out, "InstanceContainer", containerCost);
            appendInstanceRow(out, "FalcoInstance", falcoCost);
            out.append(System.lineSeparator())
                    .append(" Nothing releases these objects again, not even unloading the chunk.")
                    .append(System.lineSeparator());
            report(out);

            assertTrue(containerCost[1] > 0,
                    "Constructing chunks has to leave something behind in the entity tracker of the instance");
            assertTrue(falcoCost[1] > 0,
                    "Constructing chunks has to leave something behind in the entity tracker of the instance");
            assertTrue(falcoCost[1] <= containerCost[1],
                    "A FalcoInstance receives the List.of() singleton instead of a fresh unmodifiable list, so "
                            + "it cannot cost more per chunk than an InstanceContainer, but it retained "
                            + falcoCost[1] + " against " + containerCost[1] + " bytes");
        } finally {
            MinestomChunks.release(freshContainer);
            MinestomChunks.release(freshFalco);
        }
    }

    /**
     * Fails unless the entire difference between the two chunk types is one {@code SectionBlockStorage}.
     * <p>
     * This is the strict comparison of this class, and it is what allows the delta to be quoted as a
     * number at all. Subtracting the two totals would not be enough: a chunk that grew a field
     * pointing at a twenty-four byte object of any other class would produce the very same delta and
     * pass, and catching exactly that was the whole point of the equality this replaces. The
     * comparison therefore runs per class, over the union of the classes either side retains, and
     * demands equality everywhere except {@link #BLOCK_STORAGE} — which the Falco side has to hold
     * once and the Minestom side not at all. The tallies are the ones the tables above are printed
     * from, taken from the same two walks, so nothing is measured a second time here.
     * </p>
     * <p>
     * One post is exempt from the per class comparison, and it is the chunk class itself.
     * {@code DynamicChunk} and {@code FalcoChunk} are different classes by construction, and so are
     * the lambda classes the JVM spins for the method reference each of them hands to its
     * {@code CachedPacket} — those carry a generated name which need not even be stable between two
     * runs of the same build. Everything whose class name starts with the name of the chunk class is
     * consequently compared as a single post: same object count, same bytes. That still holds the
     * shallow size of the chunk object under assertion, which is where a field added to
     * {@code FalcoChunk} shows up first, and it is why {@code startsWith} is used rather than an
     * equality that would let the lambda escape the comparison entirely.
     * </p>
     *
     * @param minestom      the footprint of the Minestom side
     * @param minestomChunk the chunk the Minestom side was measured from
     * @param falcoSide     the footprint of the Falco side
     * @param falcoChunk    the chunk the Falco side was measured from
     * @param context       what was measured, named in every failure message
     */
    private static void assertTheSeamIsTheOnlyDifference(Footprint minestom, Chunk minestomChunk,
                                                        Footprint falcoSide, Chunk falcoChunk,
                                                        String context) {
        final String minestomType = minestomChunk.getClass().getName();
        final String falcoType = falcoChunk.getClass().getName();

        assertEquals(minestom.objectsUnder(minestomType), falcoSide.objectsUnder(falcoType),
                context + ": the chunk object and the lambdas the JVM spins for it are "
                        + falcoSide.objectsUnder(falcoType) + " objects on the Falco side against "
                        + minestom.objectsUnder(minestomType) + " on the Minestom side");
        assertEquals(minestom.bytesUnder(minestomType), falcoSide.bytesUnder(falcoType),
                context + ": the chunk object and the lambdas the JVM spins for it weigh "
                        + falcoSide.bytesUnder(falcoType) + " bytes on the Falco side against "
                        + minestom.bytesUnder(minestomType) + " on the Minestom side, so FalcoChunk "
                        + "has grown a field of its own");

        final Set<String> classNames = new TreeSet<>(minestom.perClass().keySet());
        classNames.addAll(falcoSide.perClass().keySet());

        for (String className : classNames) {
            if (className.startsWith(minestomType) || className.startsWith(falcoType)
                    || BLOCK_STORAGE.equals(className)) {
                continue;
            }
            assertEquals(minestom.objectsOf(className), falcoSide.objectsOf(className),
                    context + ": FalcoChunk retains " + falcoSide.objectsOf(className) + " objects of "
                            + className + " against " + minestom.objectsOf(className) + " of DynamicChunk, "
                            + "and " + BLOCK_STORAGE + " is the only class the two may differ in");
            assertEquals(minestom.bytesOf(className), falcoSide.bytesOf(className),
                    context + ": FalcoChunk retains " + falcoSide.bytesOf(className) + " bytes of "
                            + className + " against " + minestom.bytesOf(className) + " of DynamicChunk, "
                            + "and " + BLOCK_STORAGE + " is the only class the two may differ in");
        }
        assertEquals(0, minestom.objectsOf(BLOCK_STORAGE),
                context + ": a DynamicChunk cannot hold a BlockStorage, so the walk that reported one "
                        + "measured something other than what this comparison assumes");
        assertEquals(1, falcoSide.objectsOf(BLOCK_STORAGE),
                context + ": FalcoChunk has to hold exactly one BlockStorage, not "
                        + falcoSide.objectsOf(BLOCK_STORAGE));
        assertEquals(minestom.objects() + 1, falcoSide.objects(),
                context + ": the seam costs one object, the storage. FalcoChunk retained "
                        + falcoSide.objects() + " objects against " + minestom.objects() + " of DynamicChunk");
        assertTrue(falcoSide.bytesOf(BLOCK_STORAGE) > 0,
                context + ": the storage was not sized, which makes the delta below unattributable");
        assertEquals(falcoSide.bytesOf(BLOCK_STORAGE), falcoSide.bytes() - minestom.bytes(),
                context + ": every extra byte has to belong to the storage, but FalcoChunk retained "
                        + (falcoSide.bytes() - minestom.bytes()) + " bytes more than DynamicChunk while its "
                        + "storage is only " + falcoSide.bytesOf(BLOCK_STORAGE) + " bytes");
    }

    /**
     * Measures how much an instance grows while {@link #TRACKER_CHUNKS} chunks are constructed in it.
     *
     * @param instance the instance to build the chunks in
     * @return the object count, the byte count and the bytes per chunk, in that order
     */
    private static long[] costInsideInstance(Instance instance) {
        walk(instance);
        final GraphLayout before = walk(instance);

        for (int index = 0; index < TRACKER_CHUNKS; index++) {
            MinestomChunks.newChunk(instance, index, 0);
        }
        final GraphLayout after = walk(instance);
        final long objects = after.totalCount() - before.totalCount();
        final long bytes = after.totalSize() - before.totalSize();
        return new long[]{objects, bytes, bytes / TRACKER_CHUNKS};
    }

    /**
     * Measures everything a chunk retains that its instance does not retain anyway.
     * <p>
     * The first pass is thrown away on purpose; see the class documentation on why a JOL walk is not
     * repeatable until the metadata of every class it meets exists.
     * </p>
     *
     * @param chunk    the chunk to measure, which must not be registered with the instance
     * @param instance the instance the chunk was built for
     * @return the footprint of the chunk alone
     * @throws IllegalStateException if the chunk is reachable from the instance, which would make
     *                               the difference between the two walks meaningless
     */
    private static Footprint measure(Chunk chunk, Instance instance) {
        footprintOf(chunk, instance);
        final Footprint footprint = footprintOf(chunk, instance);

        if (footprint.objects() <= 0) {
            throw new IllegalStateException("The chunk " + chunk.getChunkX() + ":" + chunk.getChunkZ()
                    + " is already reachable from its instance, so the difference between the two walks "
                    + "is not its footprint but zero. Measure a chunk from MinestomChunks#newChunk, "
                    + "which is deliberately not registered.");
        }
        return footprint;
    }

    /**
     * Walks the chunk together with its instance and the instance alone, and returns the difference.
     *
     * @param chunk    the chunk to measure
     * @param instance the instance the chunk was built for
     * @return the difference between the two walks, per class and in total
     */
    private static Footprint footprintOf(Chunk chunk, Instance instance) {
        final GraphLayout environment = walk(instance);
        final GraphLayout together = walk(chunk, instance);
        return difference(together, environment);
    }

    /**
     * Measures everything below {@code chunk.getSections()}.
     * <p>
     * This walk needs no instance to be subtracted, because a {@code Section} references nothing but
     * its two palettes and its two light carriers and none of them reference the chunk. It is
     * therefore the one part of the footprint that can be attributed without any ambiguity, which
     * matters for the breakdown: inside it every {@code long[]} belongs to a palette, every
     * {@code byte[]} to a light carrier and every {@code int[]} to the index structures of a
     * palette, while the same three array types occur in several places once the whole chunk is
     * looked at.
     * </p>
     *
     * @param chunk the chunk whose sections are measured
     * @return the footprint of the section list and everything below it
     */
    private static Footprint measureSections(Chunk chunk) {
        walk(chunk.getSections());
        final GraphLayout layout = walk(chunk.getSections());
        return difference(layout, null);
    }

    /**
     * Runs a JOL graph walk and turns its two known failure modes into a readable message.
     *
     * @param roots the objects to start the walk from
     * @return the layout of everything reachable from the roots
     * @throws IllegalStateException if JOL cannot walk the graph on this JVM
     */
    private static GraphLayout walk(Object... roots) {
        try {
            return GraphLayout.parseInstance(roots);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("JOL could not walk the object graph on this JVM. The usual "
                    + "cause on JDK 25 is a record class inside java.base, which Unsafe#objectFieldOffset "
                    + "refuses; the build passes -Djol.magicFieldOffset=true to the test JVM for exactly "
                    + "that case and JolMeasurement confirmed that JOL initialised with it, so a failure "
                    + "here means the workaround itself stopped working and the footprint numbers cannot "
                    + "be produced at all.", exception);
        }
    }

    /**
     * Subtracts one layout from another, per class and in total.
     *
     * @param together the layout that holds the subject and the environment
     * @param environment the layout that holds the environment alone, null if there is none
     * @return the difference of the two
     */
    private static Footprint difference(GraphLayout together, GraphLayout environment) {
        final Set<Class<?>> classes = new LinkedHashSet<>(together.getClasses());

        if (environment != null) {
            classes.addAll(environment.getClasses());
        }
        final TreeMap<String, Tally> perClass = new TreeMap<>();

        for (Class<?> type : classes) {
            final long objects = together.getClassCounts().count(type)
                    - (environment == null ? 0 : environment.getClassCounts().count(type));
            final long bytes = together.getClassSizes().count(type)
                    - (environment == null ? 0 : environment.getClassSizes().count(type));

            if (objects != 0 || bytes != 0) {
                perClass.put(type.getName(), new Tally(type.getTypeName(), objects, bytes));
            }
        }
        final long objects = together.totalCount() - (environment == null ? 0 : environment.totalCount());
        final long bytes = together.totalSize() - (environment == null ? 0 : environment.totalSize());
        return new Footprint(objects, bytes, perClass);
    }

    /**
     * Writes the block every table of this class is introduced by.
     *
     * @param out     the builder to write into
     * @param subject what the table below describes
     */
    private static void appendHeader(StringBuilder out, String subject) {
        final int headerSize = VM.current().objectHeaderSize();
        final String declared = System.getProperty(COMPACT_HEADERS);

        assertHeaderMode(headerSize, declared);
        out.append(System.lineSeparator())
                .append("=".repeat(96)).append(System.lineSeparator())
                .append(" ChunkFootprintTest: ").append(subject).append(System.lineSeparator())
                .append(" retained by the chunk alone, the instance it belongs to is subtracted")
                .append(System.lineSeparator())
                .append(String.format(Locale.ROOT, " object header %d bytes, alignment %d bytes, %s%n",
                        headerSize, VM.current().objectAlignment(),
                        declared == null
                                ? "-P" + COMPACT_HEADERS + " not stated by the build"
                                : COMPACT_HEADERS + "=" + declared))
                .append(String.format(Locale.ROOT, " %s %s%n",
                        System.getProperty("java.vm.name"), System.getProperty("java.version")))
                .append(String.format(Locale.ROOT, " %s%n", JolMeasurement.describe()))
                .append("=".repeat(96)).append(System.lineSeparator());
    }

    /**
     * Fails when the header size JOL observes contradicts the mode the build declared.
     *
     * @param headerSize the header size JOL observed
     * @param declared   the value of the property the build set, null if it set none
     */
    private static void assertHeaderMode(int headerSize, String declared) {
        if (declared == null) {
            return;
        }
        final int expected = Boolean.parseBoolean(declared) ? COMPACT_HEADER_SIZE : LEGACY_HEADER_SIZE;
        assertEquals(expected, headerSize,
                "The build declared " + COMPACT_HEADERS + "=" + declared + " but the JVM uses a header of "
                        + headerSize + " bytes. Every number of this run would carry the wrong label.");
    }

    /**
     * Writes the column titles of the profile table.
     *
     * @param out the builder to write into
     */
    private static void appendProfileHeader(StringBuilder out) {
        out.append(String.format(Locale.ROOT, " %-14s %8s %9s %9s %11s %9s %11s %9s%n",
                "ARRANGEMENT", "STATES", "DISTINCT", "OBJECTS", "MINESTOM B", "OBJECTS", "FALCO B", "DELTA B"));
    }

    /**
     * Writes one row of the profile table.
     *
     * @param out      the builder to write into
     * @param shape    the arrangement of the states, or a dash for an unfilled chunk
     * @param states   the requested amount of distinct states, or a dash
     * @param distinct the measured amount of distinct states, or a dash
     * @param minestom the footprint of the Minestom side
     * @param falcoSide the footprint of the Falco side
     */
    private static void appendProfileRow(StringBuilder out, String shape, String states, String distinct,
                                         Footprint minestom, Footprint falcoSide) {
        out.append(String.format(Locale.ROOT, " %-14s %8s %9s %9d %11d %9d %11d %9d%n",
                shape, states, distinct, minestom.objects(), minestom.bytes(),
                falcoSide.objects(), falcoSide.bytes(), falcoSide.bytes() - minestom.bytes()));
    }

    /**
     * Writes one row of the instance cost table.
     *
     * @param out  the builder to write into
     * @param type the name of the instance type
     * @param cost the object count, the byte count and the bytes per chunk
     */
    private static void appendInstanceRow(StringBuilder out, String type, long[] cost) {
        out.append(String.format(Locale.ROOT, " %-" + LABEL_WIDTH + "s %9d %11d %11d%n",
                type, cost[0], cost[1], cost[2]));
    }

    /**
     * Writes the breakdown by the posts the research report argues about.
     * <p>
     * The section side is taken from its own walk, where every array type is unambiguous. The rest
     * of the chunk is what remains after the sections, the two heightmaps and the two block index
     * maps have been named, and it is reported as one row rather than split further, because the
     * {@code int[]} and {@code Object[]} left in it are shared between the fastutil maps and the tag
     * handler and cannot be attributed by their class alone. The full class table follows, so
     * nothing is hidden behind that row.
     * </p>
     *
     * @param out      the builder to write into
     * @param chunk    the chunk that was measured
     * @param owned    the footprint of the whole chunk
     * @param sections the footprint of the section list and everything below it
     */
    private static void appendBreakdown(StringBuilder out, Chunk chunk, Footprint owned, Footprint sections) {
        final long sectionRecords = sections.bytesOf(SECTION);
        final long palettes = sections.bytesOf(PALETTE, PALETTE_INDEX_LIST, PALETTE_REVERSE_MAP,
                PACKED_VALUES, INT_ARRAY);
        final long skyLight = sections.bytesOf(SKY_LIGHT);
        final long blockLight = sections.bytesOf(BLOCK_LIGHT);
        final long lightArrays = sections.bytesOf(LIGHT_ARRAY);
        final long needsSend = sections.bytesOf(NEEDS_SEND);
        final long sectionList = sections.bytes() - sectionRecords - palettes - skyLight - blockLight
                - lightArrays - needsSend;
        final long heightmaps = owned.bytesOf(MOTION_BLOCKING, WORLD_SURFACE, HEIGHTS);
        final long blockIndexMaps = owned.bytesOf(BLOCK_INDEX_MAP);
        final long rest = owned.bytes() - sections.bytes() - heightmaps - blockIndexMaps;

        out.append(String.format(Locale.ROOT, " %-" + LABEL_WIDTH + "s %9s %11s %8s%n",
                "POST", "OBJECTS", "BYTES", "SHARE"));
        appendPost(out, "the section list and everything below it", sections.objects(), sections.bytes(), owned);
        appendPost(out, "  Section records", sections.objectsOf(SECTION), sectionRecords, owned);
        appendPost(out, "  block and biome palettes, with their arrays",
                sections.objectsOf(PALETTE, PALETTE_INDEX_LIST, PALETTE_REVERSE_MAP, PACKED_VALUES, INT_ARRAY),
                palettes, owned);
        appendPost(out, "  sky light carriers", sections.objectsOf(SKY_LIGHT), skyLight, owned);
        appendPost(out, "  block light carriers", sections.objectsOf(BLOCK_LIGHT), blockLight, owned);
        appendPost(out, "  light arrays", sections.objectsOf(LIGHT_ARRAY), lightArrays, owned);
        appendPost(out, "  AtomicBoolean needsSend flags", sections.objectsOf(NEEDS_SEND), needsSend, owned);
        appendPost(out, "  the immutable list that holds the sections",
                sections.objects() - sections.objectsOf(SECTION, PALETTE, PALETTE_INDEX_LIST,
                        PALETTE_REVERSE_MAP, PACKED_VALUES, INT_ARRAY, SKY_LIGHT, BLOCK_LIGHT,
                        LIGHT_ARRAY, NEEDS_SEND),
                sectionList, owned);
        appendPost(out, "both heightmaps, with their short[256]",
                owned.objectsOf(MOTION_BLOCKING, WORLD_SURFACE, HEIGHTS), heightmaps, owned);
        appendPost(out, "entries and tickableMap, the map objects",
                owned.objectsOf(BLOCK_INDEX_MAP), blockIndexMaps, owned);
        appendPost(out, "the " + chunk.getClass().getSimpleName() + " itself and the rest of its fields",
                owned.objects() - sections.objects()
                        - owned.objectsOf(MOTION_BLOCKING, WORLD_SURFACE, HEIGHTS, BLOCK_INDEX_MAP),
                rest, owned);
        out.append(String.format(Locale.ROOT, " %-" + LABEL_WIDTH + "s %9d %11d %7.1f%%%n",
                "(total)", owned.objects(), owned.bytes(), 100.0));
        out.append(System.lineSeparator());
    }

    /**
     * Writes one row of the breakdown table.
     *
     * @param out     the builder to write into
     * @param label   the name of the post
     * @param objects the amount of objects the post holds
     * @param bytes   the amount of bytes the post holds
     * @param owned   the footprint the share is calculated against
     */
    private static void appendPost(StringBuilder out, String label, long objects, long bytes, Footprint owned) {
        out.append(String.format(Locale.ROOT, " %-" + LABEL_WIDTH + "s %9d %11d %7.1f%%%n",
                label, objects, bytes, owned.bytes() == 0 ? 0.0 : 100.0 * bytes / owned.bytes()));
    }

    /**
     * Writes the full per class table, in the shape {@code GraphLayout#toFootprint} uses.
     * <p>
     * The same information, computed as a difference of two walks rather than taken from one, which
     * is what keeps the instance out of it.
     * </p>
     *
     * @param out   the builder to write into
     * @param owned the footprint to print
     */
    private static void appendClassTable(StringBuilder out, Footprint owned) {
        out.append(String.format(Locale.ROOT, " %9s %9s %11s   %s%n", "COUNT", "AVG", "SUM", "DESCRIPTION"));

        owned.perClass().values().stream()
                .sorted(Comparator.comparing(Tally::name))
                .forEach(tally -> out.append(String.format(Locale.ROOT, " %9d %9d %11d   %s%n",
                        tally.objects(), tally.objects() == 0 ? 0 : tally.bytes() / tally.objects(),
                        tally.bytes(), tally.name())));
        out.append(String.format(Locale.ROOT, " %9d %9s %11d   %s%n",
                owned.objects(), "", owned.bytes(), "(total)"));
    }

    /**
     * Prints a finished table.
     *
     * @param out the builder that holds it
     */
    private static void report(StringBuilder out) {
        System.out.print(out);
        System.out.flush();
    }

    /**
     * The amount of objects and bytes one class contributes to a footprint.
     *
     * @param name    the readable name of the class
     * @param objects the amount of instances
     * @param bytes   the amount of bytes those instances occupy
     */
    private record Tally(String name, long objects, long bytes) {
    }

    /**
     * Everything a subject retains, in total and per class.
     *
     * @param objects  the amount of objects
     * @param bytes    the amount of bytes
     * @param perClass the same numbers per class, keyed by the binary name of the class
     */
    private record Footprint(long objects, long bytes, TreeMap<String, Tally> perClass) {

        /**
         * Returns how many objects of the given classes the footprint holds.
         *
         * @param classNames the binary names of the classes to sum over
         * @return the amount of objects
         */
        private long objectsOf(String... classNames) {
            long total = 0;

            for (String className : classNames) {
                final Tally tally = perClass.get(className);
                total += tally == null ? 0 : tally.objects();
            }
            return total;
        }

        /**
         * Returns how many bytes the objects of the given classes occupy.
         *
         * @param classNames the binary names of the classes to sum over
         * @return the amount of bytes
         */
        private long bytesOf(String... classNames) {
            long total = 0;

            for (String className : classNames) {
                final Tally tally = perClass.get(className);
                total += tally == null ? 0 : tally.bytes();
            }
            return total;
        }

        /**
         * Returns how many objects of a class and of everything the JVM generated from it are held.
         * <p>
         * Prefix rather than equality, because the classes the JVM spins for a lambda or a method
         * reference are named after the class that declares them and can therefore only be attributed
         * that way; their own names carry a counter and an address and are not worth naming.
         * </p>
         *
         * @param prefix the binary name of the class whose objects are summed
         * @return the amount of objects
         */
        private long objectsUnder(String prefix) {
            long total = 0;

            for (Map.Entry<String, Tally> entry : perClass.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    total += entry.getValue().objects();
                }
            }
            return total;
        }

        /**
         * Returns how many bytes a class and everything the JVM generated from it occupy.
         *
         * @param prefix the binary name of the class whose bytes are summed
         * @return the amount of bytes
         */
        private long bytesUnder(String prefix) {
            long total = 0;

            for (Map.Entry<String, Tally> entry : perClass.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    total += entry.getValue().bytes();
                }
            }
            return total;
        }
    }
}
