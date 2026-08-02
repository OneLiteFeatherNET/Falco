package net.onelitefeather.falco.benchmark.instance;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.Section;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import net.onelitefeather.falco.benchmark.support.MinestomChunks.FillShape;
import net.onelitefeather.falco.instance.FalcoInstance;
import net.onelitefeather.falco.instance.LazySectionBlockStorage;
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
import java.util.function.LongUnaryOperator;
import java.util.function.ToLongFunction;

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
 * project wants to be told about. An <em>absolute</em> byte figure is not asserted that way. It moves
 * with the JDK, with the header mode and with the object alignment, and a test that turns red on a JDK
 * upgrade teaches nobody anything, so an absolute byte figure is only bounded generously — an empty
 * chunk is asserted to be kibibytes rather than megabytes, and a chunk whose palettes have gone direct
 * is asserted to be dominated by the twenty-four {@code long[1024]} arrays that arithmetic says must
 * be there.
 * </p>
 * <p>
 * One comparison is asserted strictly, and it is the one this class exists for: the two chunk types
 * against each other. Outside the classes stage 2 declared a difference for, {@code FalcoChunk} must
 * weigh exactly what {@code DynamicChunk} weighs; inside them it must weigh exactly what the table
 * declares. Bytes are hard there and nowhere else, because both sides are measured in the same run of
 * the same JVM, so the header mode and the alignment are the same on both and cancel — a strict
 * comparison of two sides is a different thing from a constant, and every expectation of that
 * comparison is derived from the Minestom side of the same walk or from an object this test builds
 * next to it. A deviation would not be a tolerance to widen, it would be a finding.
 * </p>
 *
 * <h2>The declared difference table, and why it replaced an equality</h2>
 * <p>
 * Stage 1 asserted that the two chunk types retain identical objects and identical bytes in every
 * class but one, of which the Falco side held exactly one — its {@code SectionBlockStorage}, the
 * indirection that replaced an inherited section list. Zero was never reachable while the storage is
 * a separate type, so the price was named rather than rounded away, and three injected defects were
 * used to prove the comparison still bit. The most instructive of them was a primitive {@code long}
 * field, which adds no object at all and fitted into the padding that was already there: only the
 * byte comparison caught it.
 * </p>
 * <p>
 * Stage 2 makes that equality impossible by construction, because removing objects is the point. A
 * fresh {@code FalcoChunk} shares one {@code LazySectionBlockStorage#EMPTY} section instead of
 * owning twenty-four, builds neither heightmap until one is asked for, and keeps one block map and a
 * counter where {@code DynamicChunk} keeps two maps. Measured on the pinned build, with the sections
 * of tasks 2 and 3, the lazy heightmaps of task 7 and the single block map of task 8 all in place,
 * that is {@code 25} objects and {@code 840} bytes against {@code 192} and {@code 6848} — a hundred
 * and sixty-seven objects fewer, not one more.
 * </p>
 * <p>
 * What survives the rewrite is the property the equality had, and it is what
 * {@link #assertOnlyTheDeclaredClassesDiffer} is named after: a class the Falco chunk retains and
 * the plan did not declare has to fail the test. The two tables {@link #FRESH_DIFFERENCE} and
 * {@link #FILLED_DIFFERENCE} name every class the two sides may differ in, the count the Falco side
 * has to show for it and the bytes that count is worth; every class outside them is still asserted
 * equal on both objects and bytes. A tolerance of the form "at most six kibibytes" was considered and
 * rejected, because it would pass for a chunk that saved the sections and grew a field — the exact
 * failure the strict comparison of stage 1 was written to catch.
 * </p>
 * <p>
 * The byte half of those rows was missing when the tables were first written, and the hole it left is
 * worth naming, because the shape of it recurs. A declared class was asserted on its count alone, and
 * the total that was supposed to catch the rest was a sum of the very bytes it was compared against —
 * true by arithmetic in every run, and therefore never the first assertion to fail. What that left
 * unguarded was not a corner: {@code [I} is a declared class, and in a filled chunk it holds the index
 * array of every palette that went indirect, so a Falco side with the same number of arrays and wider
 * ones satisfied the whole table. Injecting exactly that — one fastutil map of the chunk constructed
 * with room for eight entries instead of none — passed the class as it stood, and fails it now by
 * name. Each declared row therefore carries a byte expectation that is derived from the Minestom side
 * of the same walk, or from a {@link Probes} object built by this test; never from the chunk it is
 * asserted against.
 * </p>
 * <p>
 * Three rows of the fresh table are worth reading before the rest. The one {@code Section}, one
 * {@code SkyLight}, one {@code BlockLight}, two {@code PaletteImpl} and two {@code AtomicBoolean} on
 * the Falco side are not a section this chunk owns: they are the single static {@code EMPTY}
 * flyweight, which no walk of the instance reaches and which a difference walk therefore charges to
 * whichever chunk it starts at. The whole JVM holds one of them. That is asserted as such rather
 * than assumed, by measuring two chunks at once — shared stays at one, owned would become two.
 * </p>
 * <p>
 * Eight defects were injected to find out where the new comparison stops biting, and seven of them
 * were caught by name: a field of an undeclared class, a second storage, a {@code long} that grows
 * the chunk object, a {@code Section} materialised in the constructor, an eager
 * {@code SectionBlockStorage} in place of the lazy one, a shared section that is shared per storage
 * instead of per JVM — that one only by the two chunk measurement, which is why it is there — and a
 * block map given room for eight entries, which changes no count anywhere and is caught only by the
 * byte expectation of the two array rows. The eighth survives and is stated rather than hidden: a
 * {@code boolean} field added to {@code FalcoChunk} fits into the padding the object already carries,
 * so it adds no object, no byte and no shallow size, and nothing here can see it. It is caught by the
 * field after it, which is the one that pushes the object over the next alignment boundary. This
 * comparison measures bytes, and a field which costs no byte is a field this comparison cannot be
 * asked about.
 * </p>
 *
 * <h2>Why the equivalence check runs after the measurement and not before</h2>
 * <p>
 * A probe that writes to its specimen is not a probe. {@code MinestomChunks#assertSameBlocks} used to
 * run first in all three places below, and on a lazy storage it was a write: it asked both chunks for
 * {@code Chunk#getSections()}, which a {@code FalcoChunk} answers by giving every shared slot a
 * section of its own. Every fresh Falco chunk in these tables was therefore fully materialised before
 * a single byte of it was counted, and the table said so without saying so — {@code 193} objects
 * against {@code 192}, the same delta the eager storage of stage 1 produced, which is exactly what a
 * chunk with twenty-four sections of its own costs. The chunk that was being hidden held {@code 32}
 * objects at the time of that diagnosis, which was taken with tasks 2 and 3 in place and tasks 7 and 8
 * not yet written; the {@code 25} of the section above is the same measurement after both of them
 * landed, and the two numbers differ by the four objects of the heightmaps and the three of the second
 * block map rather than by anything the diagnosis got wrong. See the diagnosis report of 2026-08-02 in
 * {@code .superpowers/sdd/2026-08-02-falco-lazy-sections}.
 * </p>
 * <p>
 * Two things follow and both are done. {@code MinestomChunks#assertSameBlocks} no longer reaches
 * through {@code getSections()} at all, which is a fix every benchmark of this module shares. And the
 * order here is inverted: the chunk is measured first and proved equivalent afterwards, before any
 * table is printed. That keeps what the check is for — a run whose two sides disagree still fails and
 * still publishes nothing — while removing the last way it can decide the number it is guarding. The
 * residue is stated rather than removed: comparing the heightmaps forces both sides to compute them,
 * which since task 7 means building the two a fresh Falco chunk does not have, and the column descent
 * of {@code Heightmap#refresh(int, int, int)} then materialises the one section it lands in. Measured
 * on the pinned build, that residue is {@code 11} objects and {@code 1 328} bytes — the two heightmaps
 * with their {@code short[256]}, and the seven objects of one section — which takes the chunk from
 * {@code 25} objects and {@code 840} bytes to {@code 36} and {@code 2 168}. What that residue is made
 * of is asserted class by class in {@link #aFreshChunkHoldsTheObjectsTheSourceDeclares()}, which also
 * prints the two totals with every run, so a third heightmap or a second materialised section turns
 * this paragraph red rather than stale. It is Minestom's descent and not this class's, and after the
 * inversion it lands outside every number above.
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
 * @version 2.1.0
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
    private static final String SECTION_LIST = "java.util.ImmutableCollections$ListN";
    private static final String LAZY_STORAGE = "net.onelitefeather.falco.instance.LazySectionBlockStorage";
    private static final String STORAGE_VIEW = "net.onelitefeather.falco.instance.LazySectionBlockStorage$1";
    private static final String SECTION_ARRAY = "[Lnet.minestom.server.instance.Section;";
    private static final String IDENTIFIER = "java.util.UUID";
    private static final String HEIGHTS = "[S";
    private static final String PACKED_VALUES = "[J";
    private static final String INT_ARRAY = "[I";
    private static final String OBJECT_ARRAY = "[Ljava.lang.Object;";
    private static final String LIGHT_ARRAY = "[B";

    /**
     * What a fresh {@code FalcoChunk} is allowed to differ from a fresh {@code DynamicChunk} in.
     * <p>
     * Every row was derived from the tasks of this stage before it was compared with a measurement,
     * because a table that is edited until the measurement fits it asserts nothing. Six of the
     * fifteen rows came back different, and where the plan and the measurement disagree it is the
     * measurement that stands: the five rows of the shared section, which the plan put at zero for
     * the reason the row of {@link #SECTION} corrects, and the {@code int[]} row, which the plan did
     * not have at all. A class outside these fifteen still has to be equal to the Minestom side on
     * both its object count and its bytes.
     * </p>
     * <p>
     * Every row carries a byte expectation as well as a count, and where the two differ in shape it
     * is because the class does. {@link #exactly(long, String)} multiplies the count by the size one
     * instance has on the Minestom side, which is only meaningful for a class whose instances are all
     * the same size and which therefore refuses a class where they are not.
     * {@link #fewerBy(long, ToLongFunction, String)} and {@link #added(long, ToLongFunction, String)}
     * carry the size of what was removed or added, taken from {@link Probes} — objects built by this
     * test rather than read off the chunk under test, because a byte expectation read off the subject
     * asserts nothing about it.
     * </p>
     */
    private static final Map<String, Declared> FRESH_DIFFERENCE = Map.ofEntries(
            Map.entry(SECTION, exactly(1,
                    "the one LazySectionBlockStorage#EMPTY every slot of a fresh chunk points at. It is "
                            + "static and therefore exists once per JVM, but it is unreachable from the "
                            + "instance, so this walk charges it to whichever chunk is measured")),
            Map.entry(PALETTE, exactly(2,
                    "the block and the biome palette of that one shared section")),
            Map.entry(SKY_LIGHT, exactly(1, "the sky light carrier of that one shared section")),
            Map.entry(BLOCK_LIGHT, exactly(1, "the block light carrier of that one shared section")),
            Map.entry(NEEDS_SEND, exactly(2,
                    "one needsSend flag per light carrier of that one shared section, against forty-eight "
                            + "for the twenty-four sections a DynamicChunk owns")),
            Map.entry(MOTION_BLOCKING, exactly(0, "task 7: no heightmap exists until one is asked for")),
            Map.entry(WORLD_SURFACE, exactly(0, "task 7: no heightmap exists until one is asked for")),
            Map.entry(HEIGHTS, exactly(0, "the short[256] of each heightmap that was never built")),
            Map.entry(BLOCK_INDEX_MAP, exactly(1,
                    "task 8: one block map and an int counter instead of entries and tickableMap")),
            Map.entry(INT_ARRAY, fewerBy(1, Probes::mapKeys,
                    "the int[] key array of the block map task 8 removed, which is the key array of an "
                            + "Int2ObjectOpenHashMap constructed the way DynamicChunk constructs both of "
                            + "its own")),
            Map.entry(OBJECT_ARRAY, fewerBy(2, probes -> probes.mapValues() + probes.slotArray(),
                    "the Object[] value array of that same map, and the backing array of Minestom's "
                            + "List.of(Section...), which holds one reference per section and is therefore "
                            + "the size of the slot array that replaced it")),
            Map.entry(SECTION_LIST, exactly(0,
                    "Minestom's List.of(Section...); the storage keeps the Section[] itself")),
            Map.entry(SECTION_ARRAY, added(1, Probes::slotArray,
                    "that Section[], the slot array of the storage, one reference per section")),
            Map.entry(LAZY_STORAGE, added(1, Probes::storage,
                    "the storage, which is what the seam of stage 1 costs")),
            Map.entry(STORAGE_VIEW, added(1, Probes::storageView,
                    "the AbstractList that BlockStorage#views answers with")));

    /**
     * What a filled {@code FalcoChunk} is allowed to differ from a filled {@code DynamicChunk} in.
     * <p>
     * Shorter than {@link #FRESH_DIFFERENCE} by construction: {@code MinestomChunks#fill} writes
     * through {@code Chunk#setBlock}, which materialises every section and builds both heightmaps, so
     * everything the flyweight and task 7 save is bought back and only the bookkeeping remains.
     * </p>
     */
    private static final Map<String, Declared> FILLED_DIFFERENCE = Map.ofEntries(
            Map.entry(BLOCK_INDEX_MAP, exactly(1,
                    "task 8: one block map and an int counter instead of entries and tickableMap")),
            Map.entry(INT_ARRAY, fewerBy(1, Probes::mapKeys,
                    "the int[] key array of the block map task 8 removed. Every other int[] of a filled "
                            + "chunk belongs to a palette that went indirect, and this row is what bounds "
                            + "them: the Falco side may hold one array fewer and not one byte more")),
            Map.entry(OBJECT_ARRAY, fewerBy(2, probes -> probes.mapValues() + probes.slotArray(),
                    "the Object[] value array of that same map, and the backing array of Minestom's "
                            + "List.of(Section...), which holds one reference per section and is therefore "
                            + "the size of the slot array that replaced it")),
            Map.entry(SECTION_LIST, exactly(0,
                    "Minestom's List.of(Section...); the storage keeps the Section[] itself")),
            Map.entry(SECTION_ARRAY, added(1, Probes::slotArray,
                    "that Section[], the slot array of the storage, one reference per section")),
            Map.entry(LAZY_STORAGE, added(1, Probes::storage,
                    "the storage, which is what the seam of stage 1 costs")),
            Map.entry(STORAGE_VIEW, added(1, Probes::storageView,
                    "the AbstractList that BlockStorage#views answers with")));

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
     * this method is what the two cost against each other, which after stage 2 is a subtraction
     * rather than an addition: {@code FalcoChunk} holds the classes {@link #FRESH_DIFFERENCE}
     * declares, in the counts it declares, and is equal to {@code DynamicChunk} in every class that
     * table does not name.
     * </p>
     * <p>
     * The asserted counts on the Minestom side are the ones the source dictates rather than the ones
     * that happened to be measured. Twenty-four sections come from {@code DynamicChunk.java:61-67},
     * the two palettes and the two light carriers per section from {@code Section.java:11-13}, and
     * the one {@code AtomicBoolean} per light carrier from the {@code needsSend} field of
     * {@code BlockLight} and {@code SkyLight}. All five are package-private types, which is why they
     * are named by string here — a test that had to live inside {@code net.minestom.server.instance}
     * to count them would be a heavier coupling than the count is worth.
     * </p>
     * <p>
     * The one section the Falco side does show is measured a second time, from two chunks at once,
     * because the count alone cannot tell a shared object from an owned one. A difference walk
     * charges {@code LazySectionBlockStorage#EMPTY} to whichever chunk it starts at — the field is
     * static, so no walk of the instance ever reaches it — and one section per chunk is exactly what
     * a chunk that quietly materialised one would also report. Two chunks separate the two readings:
     * shared stays at one, owned becomes two.
     * </p>
     * <p>
     * The last measurement of this method is of the equivalence check rather than of the chunk, and it
     * is the reason the check runs after everything else. {@code MinestomChunks#assertSameBlocks} asks
     * both sides for their heightmaps, which on a lazy chunk builds the two that task 7 removed, and
     * the column descent of {@code Heightmap#refresh(int, int, int)} then materialises the one section
     * it lands in. The residue that leaves behind is asserted here class by class, so that the figure
     * the class documentation quotes for it is one this run produced and not one from before task 7.
     * </p>
     */
    @Test
    @DisplayName("A fresh chunk holds the objects the source declares, and Falco holds what stage 2 declared")
    void aFreshChunkHoldsTheObjectsTheSourceDeclares() {
        JolMeasurement.require();

        final Chunk minestomChunk = MinestomChunks.newChunk(container, 0, 0);
        final Chunk falcoChunk = MinestomChunks.newChunk(falco, 0, 0);
        final Chunk secondMinestomChunk = MinestomChunks.newChunk(container, 1, 0);
        final Chunk secondFalcoChunk = MinestomChunks.newChunk(falco, 1, 0);

        final Footprint minestom = measure(minestomChunk, container);
        final Footprint falcoFootprint = measure(falcoChunk, falco);
        final Footprint twoMinestomChunks = measureBoth(minestomChunk, secondMinestomChunk, container);
        final Footprint twoFalcoChunks = measureBoth(falcoChunk, secondFalcoChunk, falco);
        final Footprint sections = measureSections(minestomChunk);
        MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);
        final Footprint afterTheCheck = measure(falcoChunk, falco);

        final StringBuilder out = new StringBuilder();
        appendHeader(out, "a fresh chunk, before a single block is set");
        appendProfileHeader(out);
        appendProfileRow(out, "fresh", "-", "-", minestom, falcoFootprint);
        out.append(System.lineSeparator());
        appendBreakdown(out, minestomChunk, minestom, sections);
        appendClassTable(out, minestom);
        report(out);

        assertOnlyTheDeclaredClassesDiffer(minestom, minestomChunk, falcoFootprint, falcoChunk,
                FRESH_DIFFERENCE, probes(falcoChunk), "a fresh chunk");

        assertEquals(2 * minestom.objectsOf(SECTION), twoMinestomChunks.objectsOf(SECTION),
                "two DynamicChunks own two full sets of sections, which is the control this comparison "
                        + "needs: without it, one section per Falco chunk cannot be told apart from one "
                        + "section for every Falco chunk in the JVM");
        assertEquals(1, twoFalcoChunks.objectsOf(SECTION),
                "two fresh FalcoChunks have to share the one EMPTY section between them, and they "
                        + "retained " + twoFalcoChunks.objectsOf(SECTION) + " between them");
        assertEquals(2, twoFalcoChunks.objectsOf(SECTION_ARRAY),
                "what does scale with the chunk count is the slot array, one per storage");
        assertEquals(1, falcoFootprint.objectsOf(SECTION),
                "a fresh Falco chunk owns no section at all. The one this walk charges it with is the "
                        + "shared EMPTY flyweight, which exists once per JVM and is proved shared by the "
                        + "two chunk measurement above; anything beyond it was materialised");
        assertEquals(2, falcoFootprint.objectsOf(NEEDS_SEND),
                "forty-six of the forty-eight AtomicBoolean send flags of a fresh chunk are gone with "
                        + "the sections that held them; the two that remain belong to the shared EMPTY "
                        + "section, and the ones that come back with a materialised section are two per "
                        + "section and are what US-2.05 does not remove");
        assertTrue(falcoFootprint.bytes() * 4 < minestom.bytes(),
                "a fresh Falco chunk retained " + falcoFootprint.bytes() + " bytes against "
                        + minestom.bytes() + " for a DynamicChunk; the sections are 74,9 % of that "
                        + "figure and both heightmaps another 16,4 %, so anything above a quarter "
                        + "means one of the two did not actually go");

        assertEquals(1, afterTheCheck.objectsOf(MOTION_BLOCKING),
                "the equivalence check asks for the heightmaps, so it builds the one task 7 removed");
        assertEquals(1, afterTheCheck.objectsOf(WORLD_SURFACE),
                "the equivalence check asks for the heightmaps, so it builds the one task 7 removed");
        assertEquals(2, afterTheCheck.objectsOf(HEIGHTS),
                "the short[256] of each of those two heightmaps");
        assertEquals(2, afterTheCheck.objectsOf(SECTION),
                "the shared EMPTY section this walk charges the chunk with, plus the one the column "
                        + "descent of Heightmap#refresh materialised. A third would mean the descent "
                        + "walked further than the one section it lands in");
        report(new StringBuilder()
                .append(" The equivalence check is not free on a lazy chunk: proving the two sides equal "
                        + "left the fresh Falco chunk at ")
                .append(afterTheCheck.objects()).append(" objects and ").append(afterTheCheck.bytes())
                .append(" bytes, against ").append(falcoFootprint.objects()).append(" and ")
                .append(falcoFootprint.bytes()).append(" before it. That residue is both heightmaps with "
                        + "their short[256] and the one section the descent materialised, and it lands "
                        + "outside every table above because the check runs after the measurement.")
                .append(System.lineSeparator()));

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
     * The two sides are proved equal through {@code MinestomChunks#assertSameBlocks}, which walks
     * every position and both heightmaps and throws. A footprint comparison of two chunks with
     * different content is not a comparison. It runs after each pair has been measured and before the
     * row is written, for the reason the class documentation gives: the check computes heightmaps, and
     * a heightmap computation on a lazy storage allocates. Running last still stops a run whose two
     * sides disagree, and it stops it before a number reaches standard output.
     * </p>
     * <p>
     * The {@code fresh} row is measured on both sides rather than printed twice from the Minestom
     * side. It used to be the latter, which cost nothing while the delta was zero and would now print
     * a zero in the one column where the two sides differ most — a row that reports a difference it
     * never measured. Its pair is now compared like every other row, which it was not:
     * the two fresh chunks went unchecked here, and the only reason that never showed is that the
     * check they were missing was the one destroying the row above them.
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
        final Probes probes = probes(freshFalcoChunk);
        MinestomChunks.assertSameBlocks(freshChunk, freshFalcoChunk);

        final StringBuilder out = new StringBuilder();
        appendHeader(out, "one chunk over the distinct state count and the arrangement");
        appendProfileHeader(out);
        appendProfileRow(out, "fresh", "-", "-", fresh, freshFalco);
        assertOnlyTheDeclaredClassesDiffer(fresh, freshChunk, freshFalco, freshFalcoChunk,
                FRESH_DIFFERENCE, probes, "fresh");


        long directModeBytes = 0;
        long layeredAtLargestCount = 0;

        for (int states : STATE_COUNTS) {
            for (FillShape shape : FillShape.values()) {
                final Chunk minestomChunk = MinestomChunks.newChunk(container, 0, 0);
                final Chunk falcoChunk = MinestomChunks.newChunk(falco, 0, 0);
                MinestomChunks.fill(minestomChunk, states, shape);
                MinestomChunks.fill(falcoChunk, states, shape);

                final Footprint minestom = measure(minestomChunk, container);
                final Footprint falcoFootprint = measure(falcoChunk, falco);
                MinestomChunks.assertSameBlocks(minestomChunk, falcoChunk);
                appendProfileRow(out, shape.name(), Integer.toString(states),
                        Integer.toString(MinestomChunks.countDistinctStates(minestomChunk)),
                        minestom, falcoFootprint);

                assertOnlyTheDeclaredClassesDiffer(minestom, minestomChunk, falcoFootprint, falcoChunk,
                        FILLED_DIFFERENCE, probes, states + " states in " + shape);
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
     * States what the chunk identifier costs and why this stage does not remove it.
     * <p>
     * US-2.08 asks for the {@code UUID} of a chunk to go, on the grounds that {@code grep
     * getIdentifier} finds only its declaration in all of Minestom — which it does, at
     * {@code Chunk.java:167}, with no caller anywhere in the server. It cannot go from here anyway.
     * {@code Chunk.java:48} declares {@code private final UUID identifier} and {@code Chunk.java:66}
     * assigns it {@code UUID.randomUUID()} in the constructor every subclass has to call. A subclass
     * cannot remove a field of its superclass, and not extending {@code Chunk} is not available
     * either, because {@code Instance} is typed on it throughout. What this test does instead is
     * state the price, so that the story is closed by a number rather than by a shrug.
     * </p>
     * <p>
     * The identifier is deliberately absent from both declared difference tables. It is one object on
     * either side, so the class comparison already demands that the two chunks carry the same one,
     * and a change to that is a finding rather than a saving.
     * </p>
     */
    @Test
    @DisplayName("The chunk identifier cannot be removed from a subclass, and this is what it costs")
    void theChunkIdentifierIsOutOfReach() {
        JolMeasurement.require();

        final Chunk falcoChunk = MinestomChunks.newChunk(falco, 0, 0);
        final Footprint falcoFootprint = measure(falcoChunk, falco);

        assertEquals(1, falcoFootprint.objectsOf(IDENTIFIER),
                "every chunk of Minestom allocates one UUID in the constructor of Chunk");
        report(new StringBuilder()
                .append(" The chunk identifier costs ")
                .append(falcoFootprint.bytesOf(IDENTIFIER))
                .append(" bytes per chunk and is unreachable from a subclass (Chunk.java:48, :66).")
                .append(System.lineSeparator()));
    }

    /**
     * Fails unless the two chunks differ in exactly the classes this stage declared they differ in.
     * <p>
     * The comparison of stage 1 demanded equality everywhere except one class, and it could, because
     * the seam added one object and removed none. Stage 2 removes a hundred and sixty-seven of them,
     * so equality is no longer the right shape — but the property it existed for is unchanged and is
     * preserved here: a class the Falco chunk retains and this table does not name still fails, on
     * both its object count and its bytes. A class the table does name is asserted twice over, on the
     * count the table declares and on the bytes that count is worth, and neither of those two numbers
     * is read off the chunk being asserted about: the count comes from the plan, the size comes either
     * from the Minestom side of the same walk or from a {@link Probes} object this test built itself.
     * There is no tolerance anywhere in it, and no class is left unbounded on either axis.
     * </p>
     * <p>
     * The byte side of the declared rows is the part that was missing until it was pointed out, and
     * the row it was missing from most is {@link #INT_ARRAY} of {@link #FILLED_DIFFERENCE}: every
     * indirect palette of a filled chunk keeps its index array in that class, so a table that declared
     * only the count would have let the Falco side hold arrays of any width at all. The total at the
     * end is not what closes that hole and is no longer advertised as if it were — see below.
     * </p>
     * <p>
     * A tolerance was considered and rejected. "The Falco chunk retains at most six kibibytes" would
     * pass for a chunk that saved the sections and grew a field, which is the exact failure the strict
     * comparison of stage 1 was written to catch and the reason three defects were injected into it to
     * prove that it did.
     * </p>
     * <p>
     * The sum at the end is a check on the apparatus and not on the chunk, and it is worth being clear
     * about which. Once every class has been compared — the declared ones against their expectation,
     * the undeclared ones against the Minestom side, the chunk class as one post — the difference of
     * the two totals is already determined, so this assertion cannot be the first one to fail on a
     * chunk that changed. What it can still catch is a walk whose per class table does not add up to
     * the total it reported, which would mean the two footprints below are not describing the same set
     * of objects and that every number this class prints is suspect.
     * </p>
     * <p>
     * The declared table is iterated together with the union of the two footprints rather than only
     * over it. A class that vanished from both sides would otherwise never be visited, and a
     * declaration of one storage would pass for a chunk that holds none — which is a hole the byte
     * sum would report as an unattributable remainder instead of by name.
     * </p>
     * <p>
     * One post is exempt from the per class comparison, and it is the chunk class itself.
     * {@code DynamicChunk} and {@code FalcoChunk} are different classes by construction, and so are
     * the lambda classes the JVM spins for the method reference each of them hands to its
     * {@code CachedPacket} — those carry a generated name which need not even be stable between two
     * runs of the same build. Everything whose class name starts with the name of the chunk class is
     * consequently compared as a single post: same object count, same bytes. That is where a field
     * added to {@code FalcoChunk} shows up first, and it is why {@code startsWith} is used rather
     * than an equality that would let the lambda escape the comparison entirely. It is checked twice
     * over, once by class name and once through {@code ClassLayout}, because a field that fits into
     * the padding of the object grows neither.
     * </p>
     *
     * @param minestom      the footprint of the Minestom side
     * @param minestomChunk the chunk the Minestom side was measured from
     * @param falcoSide     the footprint of the Falco side
     * @param falcoChunk    the chunk the Falco side was measured from
     * @param declared      the expected count and byte weight per class on the Falco side, for every
     *                      class the two sides may differ in
     * @param probes        the sizes the byte expectations of the declared rows are derived from
     * @param context       what was measured, named in every failure message
     */
    private static void assertOnlyTheDeclaredClassesDiffer(Footprint minestom, Chunk minestomChunk,
                                                          Footprint falcoSide, Chunk falcoChunk,
                                                          Map<String, Declared> declared, Probes probes,
                                                          String context) {
        final String minestomType = minestomChunk.getClass().getName();
        final String falcoType = falcoChunk.getClass().getName();

        assertEquals(minestom.bytesOf(BLOCK_INDEX_MAP) / minestom.objectsOf(BLOCK_INDEX_MAP),
                probes.mapObject(),
                context + ": the byte expectations of the two array rows are the size of the arrays of a "
                        + "map this test built, and that only states anything if it is the same map a chunk "
                        + "builds. The one this test built weighs " + probes.mapObject() + " bytes against "
                        + (minestom.bytesOf(BLOCK_INDEX_MAP) / minestom.objectsOf(BLOCK_INDEX_MAP))
                        + " for the ones a DynamicChunk holds");

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
        classNames.addAll(declared.keySet());

        long declaredBytes = 0;

        for (String className : classNames) {
            if (className.startsWith(minestomType) || className.startsWith(falcoType)) {
                continue;
            }
            final Declared row = declared.get(className);

            if (row == null) {
                assertEquals(minestom.objectsOf(className), falcoSide.objectsOf(className),
                        context + ": FalcoChunk retains " + falcoSide.objectsOf(className) + " objects of "
                                + className + " against " + minestom.objectsOf(className) + " of DynamicChunk, "
                                + "and this class is not one the plan of stage 2 declared a difference for");
                assertEquals(minestom.bytesOf(className), falcoSide.bytesOf(className),
                        context + ": FalcoChunk retains " + falcoSide.bytesOf(className) + " bytes of "
                                + className + " against " + minestom.bytesOf(className) + " of DynamicChunk, "
                                + "and this class is not one the plan of stage 2 declared a difference for");
                continue;
            }
            final long expected = row.expected().applyAsLong(minestom.objectsOf(className));
            assertEquals(expected, falcoSide.objectsOf(className),
                    context + ": the plan declares " + expected + " objects of " + className
                            + " on the Falco side, against " + minestom.objectsOf(className)
                            + " on the Minestom side, because " + row.reason() + ". The chunk holds "
                            + falcoSide.objectsOf(className));

            final long expectedBytes = row.bytes().applyAsLong(new ByteContext(expected,
                    minestom.objectsOf(className), minestom.bytesOf(className), probes, className));
            assertEquals(expectedBytes, falcoSide.bytesOf(className),
                    context + ": the plan declares " + expected + " objects of " + className
                            + " on the Falco side and " + expectedBytes + " bytes for them, against "
                            + minestom.bytesOf(className) + " bytes on the Minestom side, because "
                            + row.reason() + ". The chunk holds " + falcoSide.bytesOf(className)
                            + " bytes, so it holds the declared objects at a size nobody declared");
            declaredBytes += expectedBytes - minestom.bytesOf(className);
        }
        assertEquals(declaredBytes, falcoSide.bytes() - minestom.bytes(),
                context + ": the two chunks differ by " + (falcoSide.bytes() - minestom.bytes())
                        + " bytes while the classes the plan declared account for " + declaredBytes
                        + " and every other class was just asserted equal. The two do not add up, which "
                        + "is a statement about the walk rather than about the chunk: the per class table "
                        + "of a footprint has to sum to the total that footprint reports.");
        assertEquals(ClassLayout.parseInstance(minestomChunk).instanceSize(),
                ClassLayout.parseInstance(falcoChunk).instanceSize(),
                context + ": the two chunk objects themselves must still have the same shallow size");
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
        footprintOf(instance, chunk);
        final Footprint footprint = footprintOf(instance, chunk);

        if (footprint.objects() <= 0) {
            throw new IllegalStateException("The chunk " + chunk.getChunkX() + ":" + chunk.getChunkZ()
                    + " is already reachable from its instance, so the difference between the two walks "
                    + "is not its footprint but zero. Measure a chunk from MinestomChunks#newChunk, "
                    + "which is deliberately not registered.");
        }
        return footprint;
    }

    /**
     * Measures what two chunks of the same instance retain between them.
     * <p>
     * The number this answers that {@link #measure(Chunk, Instance)} cannot is how much of a
     * footprint is shared. An object both chunks point at is walked once and counted once, so a post
     * that is twice as large here as in a single measurement is owned per chunk, and one that did not
     * grow at all is shared by the JVM. That distinction is the whole claim of the flyweight, and
     * without a second chunk it is not observable from a footprint at all.
     * </p>
     *
     * @param first    the first chunk, which must not be registered with the instance
     * @param second   the second chunk, which must not be registered with the instance
     * @param instance the instance both chunks were built for
     * @return the footprint of the two chunks together
     */
    private static Footprint measureBoth(Chunk first, Chunk second, Instance instance) {
        footprintOf(instance, first, second);
        return footprintOf(instance, first, second);
    }

    /**
     * Walks the chunk together with its instance and the instance alone, and returns the difference.
     *
     * @param instance the instance the chunks were built for
     * @param chunks   the chunks to measure, walked as one graph so that anything they share is
     *                 counted once
     * @return the difference between the two walks, per class and in total
     */
    private static Footprint footprintOf(Instance instance, Chunk... chunks) {
        final Object[] roots = new Object[chunks.length + 1];
        roots[0] = instance;
        System.arraycopy(chunks, 0, roots, 1, chunks.length);

        final GraphLayout environment = walk(instance);
        final GraphLayout together = walk(roots);
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
     * One row of a declared difference table: how many objects of a class the Falco side may hold,
     * and how many bytes those objects may weigh.
     * <p>
     * The count is a function of the count on the Minestom side rather than a constant, because two
     * of the rows can only be stated that way. The {@code int[]} and {@code Object[]} a chunk holds
     * are the key and value arrays of its fastutil maps <em>and</em> the arrays of every palette that
     * went indirect, so their absolute number moves with the fill — a filled chunk of this class
     * shows anything between one and seventy-four of them — while what task 8 removed is exactly one
     * of each, at every fill. A constant would either not hold or would pin a palette detail this
     * comparison has no business pinning; {@link #fewerBy(long, ToLongFunction, String)} states the
     * removal instead.
     * </p>
     * <p>
     * The byte expectation exists because the count alone leaves the declared classes unbounded, and
     * the row that shows why is {@link #INT_ARRAY} of {@link #FILLED_DIFFERENCE}: a filled chunk holds
     * one {@code int[]} per indirect palette, all of them inside a class this table declares, so a
     * Falco side that allocated the same number of arrays and made them wider would satisfy every
     * count in the table. It is written as a function of the Minestom side and of {@link Probes}
     * rather than as a literal, because a literal would be a figure for one header mode and this class
     * runs under two.
     * </p>
     * <p>
     * The reason is carried along and printed in the failure, because a bare count in a table is the
     * kind of assertion the next reader deletes.
     * </p>
     *
     * @param expected how many objects the Falco side may hold, given the count on the Minestom side
     * @param bytes    how many bytes those objects may weigh
     * @param reason   why the plan of this stage declares that count
     */
    private record Declared(LongUnaryOperator expected, ToLongFunction<ByteContext> bytes, String reason) {
    }

    /**
     * What a byte expectation is allowed to be computed from.
     *
     * @param expectedObjects  the count this row declares for the Falco side
     * @param minestomObjects  how many objects of the class the Minestom side holds
     * @param minestomBytes    how many bytes those objects weigh
     * @param probes           the sizes measured from objects this test built itself
     * @param className        the class the row is about, named in the failure of a rejected rule
     */
    private record ByteContext(long expectedObjects, long minestomObjects, long minestomBytes,
                               Probes probes, String className) {
    }

    /**
     * Declares a count that does not depend on what the Minestom side holds.
     * <p>
     * The byte expectation that comes with it is the count times the size one instance has on the
     * Minestom side. That is only a statement for a class whose instances are all the same size, so a
     * class whose bytes are not a whole multiple of its object count is rejected rather than asserted
     * about: every class this factory is used for — {@code Section}, {@code PaletteImpl}, the two
     * light carriers, {@code AtomicBoolean}, {@code Int2ObjectOpenHashMap}, the two heightmaps, the
     * {@code short[256]} of a heightmap and {@code ListN} — has a fixed shape, and one that stopped
     * having it would need a row of a different kind rather than a wider tolerance.
     * </p>
     *
     * @param objects the amount of objects the Falco side has to hold
     * @param reason  why the plan of this stage declares that count
     * @return the declaration
     */
    private static Declared exactly(long objects, String reason) {
        return new Declared(minestomObjects -> objects, context -> perInstance(context) * objects, reason);
    }

    /**
     * Declares a count as a removal from what the Minestom side holds.
     *
     * @param objects      the amount of objects the Falco side holds fewer of
     * @param removedBytes the size of what was removed, measured from an object this test built
     * @param reason       why the plan of this stage declares that removal
     * @return the declaration
     */
    private static Declared fewerBy(long objects, ToLongFunction<Probes> removedBytes, String reason) {
        return new Declared(minestomObjects -> minestomObjects - objects,
                context -> context.minestomBytes() - removedBytes.applyAsLong(context.probes()), reason);
    }

    /**
     * Declares a class the Falco side holds and the Minestom side does not.
     * <p>
     * The Minestom side offers no size to derive a byte expectation from here, so it is taken from a
     * {@link Probes} object of the same shape. For the slot array that is an independent statement:
     * an array of one reference per section is a thing this test can build without asking the chunk.
     * For the storage and its view list it is the weaker one — a field added to
     * {@code LazySectionBlockStorage} grows the probe as well and stays invisible — so what this row
     * asserts is that the chunk holds one storage of the size a plain storage has, and not that a
     * plain storage is the right size. That second question belongs to the tests of the storage.
     * </p>
     *
     * @param objects   the amount of objects the Falco side has to hold
     * @param bytesEach the size of one of them, measured from an object this test built
     * @param reason    why the plan of this stage declares that addition
     * @return the declaration
     */
    private static Declared added(long objects, ToLongFunction<Probes> bytesEach, String reason) {
        return new Declared(minestomObjects -> objects,
                context -> bytesEach.applyAsLong(context.probes()) * objects, reason);
    }

    /**
     * Returns the size one instance of a class has on the Minestom side.
     *
     * @param context what the byte expectation may be computed from
     * @return the size of one instance, zero when the row declares no object at all
     * @throws IllegalStateException if the instances of the class are not all the same size, which
     *                               makes a per instance size a number that means nothing
     */
    private static long perInstance(ByteContext context) {
        if (context.expectedObjects() == 0) {
            return 0;
        }
        if (context.minestomObjects() <= 0) {
            throw new IllegalStateException("The declared row of " + context.className() + " states a count "
                    + "for the Falco side and takes the size of one instance from the Minestom side, which "
                    + "holds none. A class only the Falco side holds needs an added(...) row, whose size "
                    + "comes from a probe.");
        }
        if (context.minestomBytes() % context.minestomObjects() != 0) {
            throw new IllegalStateException("The " + context.minestomObjects() + " instances of "
                    + context.className() + " the Minestom side holds weigh " + context.minestomBytes()
                    + " bytes, which is not a whole multiple of their count, so they are not all the same "
                    + "size and the byte expectation of an exactly(...) row cannot be stated for them.");
        }
        return context.minestomBytes() / context.minestomObjects();
    }

    /**
     * The sizes the byte expectations of the two declared tables are derived from.
     * <p>
     * Every one of them is measured on the running JVM from an object this test constructed, which is
     * what keeps them independent of the chunk they are asserted against and correct under both header
     * modes. The map is built the way {@code DynamicChunk.java:55-56} builds the two it declares, with
     * an expected size of zero, so its two arrays are the arrays task 8 removed.
     * </p>
     *
     * @param mapObject   the size of one {@code Int2ObjectOpenHashMap} as a chunk constructs it
     * @param mapKeys     the size of its {@code int[]} key array
     * @param mapValues   the size of its {@code Object[]} value array
     * @param slotArray   the size of an array of one reference per section
     * @param storage     the shallow size of a {@link LazySectionBlockStorage}
     * @param storageView the shallow size of the list its {@code views()} answers with
     */
    private record Probes(long mapObject, long mapKeys, long mapValues, long slotArray,
                          long storage, long storageView) {
    }

    /**
     * Measures the sizes the declared byte expectations are derived from.
     * <p>
     * It runs inside a test method rather than in a static initialiser for the reason the class
     * documentation gives about {@code jol.magicFieldOffset}: nothing in this class may touch JOL
     * before {@link JolMeasurement#require()} has confirmed that the test JVM was started the way
     * these measurements need.
     * </p>
     *
     * @param chunk the chunk the section bounds are read from, so that the slot array probe has the
     *              length the chunk under test gives its own
     * @return the sizes, for the header mode this run uses
     */
    private static Probes probes(Chunk chunk) {
        final int sectionCount = chunk.getMaxSection() - chunk.getMinSection();
        final Int2ObjectOpenHashMap<Object> map = new Int2ObjectOpenHashMap<>(0);
        final LazySectionBlockStorage storage = new LazySectionBlockStorage(chunk.getMinSection(), sectionCount);

        walk(map);
        final Footprint mapFootprint = difference(walk(map), null);
        return new Probes(mapFootprint.bytesOf(BLOCK_INDEX_MAP),
                mapFootprint.bytesOf(INT_ARRAY),
                mapFootprint.bytesOf(OBJECT_ARRAY),
                ClassLayout.parseInstance(new Section[sectionCount]).instanceSize(),
                ClassLayout.parseInstance(storage).instanceSize(),
                ClassLayout.parseInstance(storage.views()).instanceSize());
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
