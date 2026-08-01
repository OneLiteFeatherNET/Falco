package net.onelitefeather.falco.benchmark.instance;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.palette.Palette;
import net.onelitefeather.falco.anvil.NbtReads;
import net.onelitefeather.falco.anvil.PaletteData;
import net.onelitefeather.falco.anvil.PaletteEntryResolver;
import net.onelitefeather.falco.anvil.RegionConstants;
import net.onelitefeather.falco.anvil.RegionFile;
import net.onelitefeather.falco.anvil.SectionCodec;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.openjdk.jol.info.GraphLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Counts how many sections of a real Anvil world hold nothing but air, and prices the two section
 * layouts that share of empty sections decides between.
 * <p>
 * This class is not a benchmark and measures no time. It exists because
 * {@code LazySectionBenchmark} carries an axis whose interesting value, a share of ninety percent
 * empty sections, was an assumption rather than a measurement. A benchmark that reports a curve over
 * an assumed share reports a curve over a world nobody has. The plan for that measurement says so in
 * as many words: the share has to be counted in real worlds first, with a counting tool over
 * {@code falco-anvil} rather than with a benchmark. This is that tool.
 * </p>
 *
 * <h2>What counts as empty</h2>
 * <p>
 * A section is counted as empty when the {@code block_states} container it was written with holds a
 * palette of exactly one entry and that entry is {@code minecraft:air}. That is the definition the
 * flyweight can act on, because it is the one a loader can decide without unpacking anything: the
 * format stores a section in which every block is the same with a single palette entry and without a
 * {@code data} array at all.
 * </p>
 * <p>
 * Three further classes are counted separately rather than folded into one number, because they
 * answer different questions. A section with a single palette entry that is not air is uniform
 * ground, usually stone or bedrock or deepslate, and is shareable by exactly the same mechanism with
 * a second singleton rather than one, so it is the size of the next lever after the empty one. A
 * section with more than one palette entry is genuinely mixed and can never be shared. A section
 * without a {@code block_states} container at all is a boundary section that a server wrote for the
 * light data alone; it holds no blocks, and counting it as empty would inflate the share with
 * sections that a chunk of Minestom does not even allocate.
 * </p>
 * <p>
 * One inaccuracy is deliberate and has to be stated rather than hidden: a section whose stored
 * palette holds several entries can still be uniform in fact, if every index of its {@code data}
 * array points at the same entry. The classification does not unpack the indices and therefore
 * counts such a section as mixed. The count of empty sections is exact; the count of shareable
 * sections is a lower bound. How far below the truth that bound sits is not left to the imagination
 * either: the verification described below unpacks a bounded sample of the mixed sections and the
 * report states how many of them turned out to be uniform in fact.
 * </p>
 *
 * <h2>Why the census verifies itself</h2>
 * <p>
 * A counting tool whose failure looks like a result is worse than no tool at all. Every way this one
 * can go wrong produces a number, not an exception: a palette container that is looked for under the
 * wrong key yields sections without blocks, a biome container mistaken for a block container yields
 * a palette of strings where compounds were expected, a packed section whose {@code data} array is
 * overlooked yields a palette of one entry, and each of those turns into a share that a reader has
 * no way of telling apart from a share that was counted correctly. The single assertion this class
 * used to carry, that it had read more than no chunk at all, would have passed through all of them.
 * </p>
 * <p>
 * The census therefore carries its own evidence rather than its own opinion. It refuses to report a
 * run in which a written chunk resolved no section at all, or in which a section it called air only
 * still carried a {@code data} array, or in which not one non air block name was decoded anywhere in
 * the sections it read. The last of those is the load bearing one: a run over thousands of written
 * chunks that decodes not a single block name that is not air has misread the format, whatever the
 * world looks like.
 * </p>
 * <p>
 * On top of that it re-reads a bounded sample of the sections through {@code SectionCodec} and
 * {@code PaletteData}, which is the same path the loader of {@code falco-anvil} takes in production,
 * unpacks every one of the four thousand ninety six block entries and confirms that the class the
 * fast classification assigned matches what the section actually holds. The resolver behind that
 * decode interns the palette entries instead of asking a registry for them, so the verification
 * needs no running server and stays a property of the file rather than of a block table.
 * </p>
 * <p>
 * What it deliberately does not do is assert a share. There is no plausible range to check against,
 * because plausibility depends entirely on the kind of world: a generated overworld fills its lower
 * sections with stone and deepslate and leaves the upper ones empty, while a void build world is
 * empty from bedrock to build limit and a share above ninety nine percent is the correct answer for
 * it. An assertion that demanded terrain would fail on a world that is simply not made of terrain,
 * which is inventing a number by the back door. The report instead states what kind of world it
 * counted, through the generation status of the chunks it read and the block names it decoded, so a
 * share can be read together with the world that produced it.
 * </p>
 *
 * <h2>A trap in judging a world by its region files</h2>
 * <p>
 * The size of a region file on disk carries almost no information about how much terrain it holds. A
 * chunk occupies whole sectors of four thousand ninety six bytes, and the smallest chunk that exists
 * still occupies one of them, so a region file whose thousand and twenty four chunks are all empty
 * is still slightly over four megabytes. The world this class was first run against has region files
 * between sixty nine kilobytes and four and a half megabytes, which reads like dense terrain and is
 * not: the largest of them holds six hundred kilobytes of compressed chunk payload inside its four
 * and a third megabytes, and every remaining byte is sector padding. Any judgement about a world has
 * to come from its chunks, which is what this class reports, and not from {@code ls}.
 * </p>
 *
 * <h2>Which world it reads</h2>
 * <p>
 * The world is not part of this repository and never can be: it is large, often private and always
 * somebody else's. {@code falco-demo/world} is the directory the demo asks a developer to drop a
 * world into, and everything in it is ignored by git, so this test looks there first and accepts
 * both the modern {@code dimensions/<namespace>/<value>/region} layout and the older {@code region}
 * one. A world somewhere else is named with {@code -Dfalco.census.world=...}, which accepts a world
 * root or a region directory.
 * </p>
 * <p>
 * When no world is present the test does not fail. It is a tool, and a machine without a world is
 * not a broken build. It stops with an assumption that names the directory it looked in and what has
 * to be put there, which is the only outcome that keeps the missing number visible instead of
 * turning it into a green tick.
 * </p>
 * <p>
 * The read opens the region files through {@code RegionFile}, which opens its channel for reading
 * and writing because that is the only mode it has. Nothing here writes, but a world on a read only
 * medium cannot be counted, and a world a server is currently running on should not be.
 * </p>
 *
 * <h2>Why the footprint is measured in the same class</h2>
 * <p>
 * A share is not an argument by itself. Ninety percent of the sections being empty only matters if
 * an empty section costs something, and what it costs is a JOL question rather than a JMH one. The
 * second measurement of this class builds both layouts for a configurable number of chunks and asks
 * JOL for the retained size of each, so the share the first measurement counted can be turned into
 * bytes without anybody having to estimate an object header. The two layouts are built over the same
 * section content, so the difference between them is the empty sections and nothing else.
 * </p>
 * <p>
 * The chunk count is configurable and defaults below the four thousand ninety six the measurement
 * plan asks for, because the graph of that many chunks is half a million objects and this class runs
 * inside an ordinary test task. The report states the per chunk figure, which is the one that
 * extrapolates: the only object the chunks share is the empty section itself, so the total for any
 * chunk count is the per chunk figure times that count plus a constant of a few hundred bytes.
 * </p>
 * <p>
 * That second measurement asks {@link JolMeasurement#require()} first, and the census above it
 * deliberately does not. JOL answers a size question in one of two ways — through the instrumentation
 * agent it attached to the JVM, or from a layout model when the attach failed — and it does not say
 * which, so a byte figure has to name its mode or not be printed. The census counts sections in a
 * file and never asks JOL anything, so a JVM without an agent is no reason to leave it uncounted. The
 * footprint report prints the mode next to the object header mode for the same reason it prints that
 * one at all.
 * </p>
 *
 * <h2>Running it</h2>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:test --tests "*EmptySectionCensusTest" -i
 * ./gradlew :falco-benchmarks:test --tests "*EmptySectionCensusTest" -i \
 *     -Dfalco.census.world=/srv/worlds/survival -Dfalco.census.chunks=16384
 * ./gradlew :falco-benchmarks:test --tests "*EmptySectionCensusTest" -i \
 *     -Dfalco.census.jolChunks=4096
 * }</pre>
 * <p>
 * The build already passes {@code -Djdk.attach.allowAttachSelf=true} to the test JVM and switches
 * {@code -XX:+UseCompactObjectHeaders} through {@code -Pfalco.compactHeaders}, so the footprint can
 * be taken under both header layouts without touching a build file. The report prints which of the
 * two it ran under, because a footprint without that flag is not comparable to one with it.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ResourceLock(Resources.GLOBAL)
class EmptySectionCensusTest {

    /**
     * The system property that names the world to count in.
     */
    private static final String WORLD_PROPERTY = "falco.census.world";

    /**
     * The system property that caps how many chunks are read.
     */
    private static final String CHUNK_PROPERTY = "falco.census.chunks";

    /**
     * The system property that decides how many chunks the footprint is taken over.
     */
    private static final String JOL_CHUNK_PROPERTY = "falco.census.jolChunks";

    /**
     * The system property that restricts the count to chunks of one generation status.
     * <p>
     * A region file holds chunks at every stage the generator has reached, and the early stages carry
     * no terrain at all. Counting them together with the finished ones does not measure a world, it
     * measures how far its generator happened to get: a chunk at {@code minecraft:structure_starts}
     * contributes twenty-four empty sections and is indistinguishable, in the total, from a section
     * that is empty because the world has nothing there. Setting this to {@code minecraft:full}
     * yields the share a server actually holds in memory once the chunk is playable, which is the
     * only share a decision about sharing empty sections can rest on.
     * </p>
     */
    private static final String STATUS_PROPERTY = "falco.census.status";

    /**
     * The system property the build sets to say whether compact object headers are on.
     */
    private static final String COMPACT_HEADERS_PROPERTY = "falco.compactHeaders";

    /**
     * The directory the demo asks a developer to drop a world into.
     */
    private static final Path DEMO_WORLD = Path.of("falco-demo", "world");

    /**
     * The amount of directory levels the search walks upwards looking for the demo world.
     */
    private static final int SEARCH_DEPTH = 6;

    /**
     * The amount of chunks the census reads unless it is told otherwise.
     */
    private static final int DEFAULT_CHUNK_LIMIT = 4096;

    /**
     * The amount of chunks the footprint is taken over unless it is told otherwise.
     */
    private static final int DEFAULT_JOL_CHUNKS = 256;

    /**
     * The amount of chunks the measurement plan asks the footprint to be reported for.
     */
    private static final int REPORTED_CHUNKS = 4096;

    /**
     * The amount of sections a chunk of a full height overworld holds.
     */
    private static final int SECTION_COUNT = 24;

    /**
     * The amount of distinct block states a section that is not empty is filled from.
     */
    private static final int FILLED_STATES = 64;

    /**
     * The shares of empty sections the footprint is reported for, in percent.
     */
    private static final int[] REPORTED_SHARES = {0, 50, 62, 90};

    /**
     * The name of air in the block palette of the format.
     */
    private static final String AIR_NAME = "minecraft:air";

    /**
     * The key of the section list inside a chunk.
     */
    private static final String SECTIONS_KEY = "sections";

    /**
     * The key of the block palette container inside a section.
     */
    private static final String BLOCK_STATES_KEY = "block_states";

    /**
     * The key of the palette inside a palette container.
     */
    private static final String PALETTE_KEY = "palette";

    /**
     * The key of the name of a palette entry.
     */
    private static final String NAME_KEY = "Name";

    /**
     * The key of the packed palette indices inside a palette container.
     */
    private static final String DATA_KEY = "data";

    /**
     * The key of the generation status inside a chunk.
     */
    private static final String STATUS_KEY = "Status";

    /**
     * The key of the section height inside a section.
     */
    private static final String SECTION_Y_KEY = "Y";

    /**
     * The amount of block entries a section holds.
     */
    private static final int BLOCK_ENTRIES = 16 * 16 * 16;

    /**
     * The amount of sections of each storage shape the verification decodes in full.
     * <p>
     * The cap is per shape rather than overall because the two shapes are wildly unequal in number
     * and the interesting one is the rarer. A world of empty sections would spend an entire budget on
     * data less sections and never unpack a packed one, which is exactly the section a misread of the
     * format hides in.
     * </p>
     */
    private static final int VERIFIED_PER_SHAPE = 2048;

    /**
     * The amount of block names the report lists to describe what the world is built of.
     */
    private static final int REPORTED_NAMES = 12;

    /**
     * The suffix of an Anvil region file.
     */
    private static final String REGION_SUFFIX = ".mca";

    /**
     * The offset that turns a section height into an index of the per height tables.
     */
    private static final int Y_OFFSET = 64;

    /**
     * The length of the per height tables, which covers every section height a dimension can use.
     */
    private static final int Y_RANGE = 192;

    /**
     * The reader the chunks are parsed with. The payload is already decompressed at that point.
     */
    private static final BinaryTagIO.Reader TAG_READER = BinaryTagIO.unlimitedReader();

    /**
     * Counts the empty sections of a real world and reports the share.
     * <p>
     * The test asserts nothing about the share itself. There is no correct value to assert against:
     * the share is the property of the world that was counted, and a test that demanded a particular
     * one would be inventing the very number this tool exists to stop people from inventing. A range
     * would be no better, because a generated overworld and a void build world sit at opposite ends
     * of the scale and both are legitimate worlds to count.
     * </p>
     * <p>
     * What it does assert is that the census read the format it thinks it read. The classes have to
     * add up to the sections, the per height tables have to add up to the classes, every written
     * chunk has to have resolved sections, no section counted as air only may carry packed data, the
     * run has to have decoded at least one block name that is not air, and the sample the decoder of
     * {@code falco-anvil} re-read has to agree with how the census sorted it. Each of those catches a
     * different way of turning a misread file into a share that looks like a measurement, and none of
     * them assumes what the world is made of.
     * </p>
     *
     * @throws IOException if a region file cannot be read or holds a chunk that cannot be parsed
     */
    @Test
    void testTheEmptySectionShareOfARealWorld() throws IOException {
        final Path regionDirectory = locateRegionDirectory();

        Assumptions.assumeTrue(regionDirectory != null, () -> "No Anvil world was found. The census needs a "
                + "world with region files, either below " + DEMO_WORLD + " next to this repository or named "
                + "with -D" + WORLD_PROPERTY + "=<world root or region directory>. The world root is the "
                + "directory that holds level.dat and either region/ or dimensions/<namespace>/<value>/region. "
                + "Until this has run over a real world, the share of empty sections that "
                + "LazySectionBenchmark sweeps over is an assumption and not a measurement");

        final int chunkLimit = intProperty(CHUNK_PROPERTY, DEFAULT_CHUNK_LIMIT);
        final String statusFilter = System.getProperty(STATUS_PROPERTY);
        final Census census = count(regionDirectory, chunkLimit, statusFilter);

        System.out.println(report(regionDirectory, chunkLimit, statusFilter, census));

        assertTrue(census.chunks() > 0, "The region directory " + regionDirectory
                + (statusFilter == null
                        ? " holds region files but none of them marks a chunk as written"
                        : " holds no chunk at status " + statusFilter + ", so nothing was counted"));
        assertTrue(census.sections() > 0, "The " + census.chunks()
                + " chunks that were read hold no section at all, which no written chunk does");
        assertEquals(census.sections(),
                census.empty() + census.uniform() + census.mixed() + census.withoutBlockStates(),
                "The counted classes do not add up to the counted sections");
        assertEquals(census.empty(), sum(census.emptyByY()),
                "The empty sections per section height do not add up to the counted empty sections");
        assertEquals(census.sections(), sum(census.totalByY()),
                "The sections per section height do not add up to the counted sections, so the census "
                        + "met a section height outside the range a dimension can use");

        assertEquals(0, census.chunksWithoutSections(), "Of the " + census.chunks() + " chunks that were "
                + "read, " + census.chunksWithoutSections() + " resolved no section at all. A written "
                + "chunk always stores sections, so the section list was looked for under a key this "
                + "world does not use and every share below is a share of nothing");
        assertEquals(0, census.packedEmpty(), "Of the " + census.empty() + " sections counted as air "
                + "only, " + census.packedEmpty() + " still store a packed data array. The format writes "
                + "a section of a single repeated block without one, so those sections hold more than the "
                + "one palette entry the census looked at and the share of empty sections is too high");
        assertTrue(census.nonAirNames().size() > 0, "The " + census.chunks() + " chunks that were read "
                + "decoded not a single block name other than air. A world can be empty, but a written "
                + "chunk that holds literally nothing anywhere is a misread of the format rather than a "
                + "measurement, so the share of empty sections cannot be trusted");
        assertEquals(0, census.verification().mismatches(), "The decoder of the loader disagrees with the "
                + "census about " + census.verification().mismatches() + " of the sections it re-read: "
                + census.verification().firstMismatch());
    }

    /**
     * Adds up a per height table.
     *
     * @param values the table to add up
     * @return the sum of the table
     */
    private static int sum(int[] values) {
        int total = 0;

        for (int value : values) {
            total += value;
        }
        return total;
    }

    /**
     * Measures what the two section layouts retain, for every share of empty sections the benchmark
     * sweeps over.
     * <p>
     * Both layouts are built from the same content: the sections that are not empty hold a filled
     * block palette in both, and the only difference is what sits in the slots that are empty. In the
     * eager layout that is a {@code Section} of its own, exactly as the constructor of
     * {@code DynamicChunk} leaves it; in the lazy layout it is one shared section that every chunk of
     * the run points at. The difference between the two totals is therefore the price of the eager
     * empty sections and nothing else.
     * </p>
     * <p>
     * The block states the filled sections hold are synthetic. This test does not start a server and
     * has no registry to draw real ones from, and it does not need one: what a filled section retains
     * is decided by the length of its {@code long[]}, which follows from the number of distinct values
     * and not from what those values mean.
     * </p>
     */
    @Test
    void testTheFootprintOfBothSectionLayouts() {
        JolMeasurement.require();

        final int chunks = intProperty(JOL_CHUNK_PROPERTY, DEFAULT_JOL_CHUNKS);
        final StringBuilder report = new StringBuilder();

        report.append("Section layout footprint over ").append(chunks).append(" chunks of ")
                .append(SECTION_COUNT).append(" sections, compact object headers ")
                .append(System.getProperty(COMPACT_HEADERS_PROPERTY, "unknown")).append('\n')
                .append(JolMeasurement.describe()).append('\n')
                .append(String.format("%-8s %16s %16s %16s %16s%n",
                        "empty", "eager bytes", "lazy bytes", "saved/chunk", "saved/" + REPORTED_CHUNKS));

        for (int share : REPORTED_SHARES) {
            final int filled = SECTION_COUNT - SECTION_COUNT * share / 100;
            final Section[][] eager = buildLayouts(chunks, filled, false);
            final Section[][] lazy = buildLayouts(chunks, filled, true);

            final long eagerBytes = GraphLayout.parseInstance((Object) eager).totalSize();
            final long lazyBytes = GraphLayout.parseInstance((Object) lazy).totalSize();
            final long savedPerChunk = (eagerBytes - lazyBytes) / chunks;

            report.append(String.format("%7d%% %16d %16d %16d %16d%n",
                    share, eagerBytes, lazyBytes, savedPerChunk, savedPerChunk * REPORTED_CHUNKS));

            assertTrue(lazyBytes <= eagerBytes, "The lazy layout retains " + lazyBytes
                    + " bytes at a share of " + share + " percent empty sections while the eager one "
                    + "retains " + eagerBytes + ", so sharing the empty sections made the chunk larger");

            if (share > 0) {
                assertTrue(lazyBytes < eagerBytes, "The lazy layout retains exactly as much as the eager "
                        + "one at a share of " + share + " percent empty sections, which means the shared "
                        + "section was not installed and the measurement compared a layout with itself");
            }
        }
        System.out.println(report);
    }

    /**
     * Builds one section layout per chunk.
     *
     * @param chunks the amount of chunks to build
     * @param filled the amount of sections per chunk that hold blocks
     * @param shared whether the empty sections are one shared object or one object per slot
     * @return the built layouts
     */
    private static Section[][] buildLayouts(int chunks, int filled, boolean shared) {
        final Section empty = new Section();
        final Section template = filledSection();
        final Section[][] layouts = new Section[chunks][];

        for (int chunk = 0; chunk < chunks; chunk++) {
            final Section[] sections = new Section[SECTION_COUNT];

            for (int index = 0; index < SECTION_COUNT; index++) {
                if (index < filled) {
                    sections[index] = template.clone();
                } else {
                    sections[index] = shared ? empty : new Section();
                }
            }
            layouts[chunk] = sections;
        }
        return layouts;
    }

    /**
     * Builds a section whose block palette holds the configured amount of distinct states.
     *
     * @return the built section
     */
    private static Section filledSection() {
        final Section section = new Section();
        final Palette palette = section.blockPalette();
        final Random random = new Random(20260731L);

        palette.setAll((x, y, z) -> 1 + random.nextInt(FILLED_STATES));
        return section;
    }

    /**
     * Counts the sections of every chunk the region directory holds, up to the given limit.
     *
     * @param regionDirectory the directory the region files sit in
     * @param chunkLimit      the amount of chunks to stop after
     * @param statusFilter    the generation status a chunk has to carry to be counted, or {@code null}
     *                        to count every chunk the region files hold
     * @return the counted census
     * @throws IOException if a region file cannot be read or holds a chunk that cannot be parsed
     */
    private static Census count(Path regionDirectory, int chunkLimit, @Nullable String statusFilter)
            throws IOException {
        final List<Path> files = regionFiles(regionDirectory);
        final int[] totalByY = new int[Y_RANGE];
        final int[] emptyByY = new int[Y_RANGE];
        final Map<String, Integer> nonAirNames = new TreeMap<>();
        final Map<String, Integer> statuses = new TreeMap<>();
        final Verification verification = new Verification();
        int readFiles = 0;
        int chunks = 0;
        int chunksWithoutSections = 0;
        int sections = 0;
        int empty = 0;
        int uniform = 0;
        int mixed = 0;
        int withoutBlockStates = 0;
        int packedEmpty = 0;

        for (Path file : files) {
            if (chunks >= chunkLimit) {
                break;
            }
            readFiles++;

            try (RegionFile region = RegionFile.open(file)) {
                // The region file masks the coordinates into the region itself, so the local ones
                // address the same entries the absolute ones would.
                for (int localZ = 0; localZ < RegionConstants.REGION_SIZE && chunks < chunkLimit; localZ++) {
                    for (int localX = 0; localX < RegionConstants.REGION_SIZE && chunks < chunkLimit; localX++) {
                        final RegionFile.RawChunk raw = region.readRaw(localX, localZ);

                        if (raw == null) {
                            continue;
                        }
                        final CompoundBinaryTag data = TAG_READER.read(
                                new ByteArrayInputStream(raw.decompress()), BinaryTagIO.Compression.NONE);
                        final ListBinaryTag list = NbtReads.optionalList(data, SECTIONS_KEY, BinaryTagTypes.COMPOUND);

                        final String status = NbtReads.optionalString(data, STATUS_KEY);
                        statuses.merge(status == null ? "<absent>" : status, 1, Integer::sum);

                        if (statusFilter != null && !statusFilter.equals(status)) {
                            continue;
                        }
                        chunks++;

                        if (list.size() == 0) {
                            chunksWithoutSections++;
                        }

                        for (int index = 0; index < list.size(); index++) {
                            final CompoundBinaryTag section = list.getCompound(index);
                            final int sectionY = NbtReads.integer(section, SECTION_Y_KEY);
                            final int slot = sectionY + Y_OFFSET;
                            final CompoundBinaryTag blockStates = NbtReads.optionalCompound(section, BLOCK_STATES_KEY);
                            final SectionClass sectionClass = classify(blockStates);
                            sections++;

                            if (slot >= 0 && slot < Y_RANGE) {
                                totalByY[slot]++;
                            }
                            switch (sectionClass) {
                                case EMPTY -> {
                                    empty++;
                                    if (slot >= 0 && slot < Y_RANGE) {
                                        emptyByY[slot]++;
                                    }
                                    // A palette of a single air entry describes a section in which
                                    // every block is air, which the format writes without any packed
                                    // data at all. Packed data next to such a palette means the
                                    // classification looked at something other than what the section
                                    // stores, and the share it produced is not a measurement.
                                    if (blockStates != null && blockStates.get(DATA_KEY) instanceof LongArrayBinaryTag) {
                                        packedEmpty++;
                                    }
                                }
                                case UNIFORM -> uniform++;
                                case MIXED -> mixed++;
                                case NO_BLOCK_STATES -> withoutBlockStates++;
                            }
                            if (blockStates != null) {
                                collectNames(blockStates, nonAirNames);
                                verification.verify(blockStates, sectionClass);
                            }
                        }
                    }
                }
            }
        }
        return new Census(readFiles, files.size(), chunks, chunksWithoutSections, sections, empty, uniform,
                mixed, withoutBlockStates, packedEmpty, totalByY, emptyByY, nonAirNames, statuses, verification);
    }

    /**
     * Decides which class a stored section belongs to.
     *
     * @param blockStates the block palette container of the section, or null if it holds none
     * @return the class of the section
     * @throws IOException if the container holds a palette entry without a name
     */
    private static SectionClass classify(@Nullable CompoundBinaryTag blockStates) throws IOException {
        if (blockStates == null) {
            return SectionClass.NO_BLOCK_STATES;
        }
        final ListBinaryTag palette = NbtReads.optionalList(blockStates, PALETTE_KEY, BinaryTagTypes.COMPOUND);

        if (palette.size() == 0) {
            return SectionClass.NO_BLOCK_STATES;
        }
        if (palette.size() > 1) {
            return SectionClass.MIXED;
        }
        return AIR_NAME.equals(NbtReads.string(palette.getCompound(0), NAME_KEY))
                ? SectionClass.EMPTY
                : SectionClass.UNIFORM;
    }

    /**
     * Records every block name of a palette container that is not air.
     * <p>
     * The names are the evidence that the census decoded block content rather than an arbitrary
     * structure that happens to parse. They also describe the world well enough for a reader to tell
     * a generated overworld from a void build world, which is the difference a share of empty
     * sections has to be read against.
     * </p>
     *
     * @param blockStates the block palette container of a section
     * @param names       the table the names are counted into
     * @throws IOException if the container holds a palette entry without a name
     */
    private static void collectNames(CompoundBinaryTag blockStates, Map<String, Integer> names) throws IOException {
        final ListBinaryTag palette = NbtReads.optionalList(blockStates, PALETTE_KEY, BinaryTagTypes.COMPOUND);

        for (int index = 0; index < palette.size(); index++) {
            final String name = NbtReads.string(palette.getCompound(index), NAME_KEY);

            if (!AIR_NAME.equals(name)) {
                names.merge(name, 1, Integer::sum);
            }
        }
    }

    /**
     * Renders the census as the report the tool exists to produce.
     *
     * @param regionDirectory the directory that was counted
     * @param chunkLimit      the amount of chunks the run was allowed to read
     * @param statusFilter    the generation status the run was restricted to, or {@code null}
     * @param census          the counted census
     * @return the report
     */
    private static String report(Path regionDirectory, int chunkLimit, @Nullable String statusFilter,
                                 Census census) {
        final StringBuilder report = new StringBuilder();
        report.append("Empty section census of ").append(regionDirectory).append('\n')
                .append("counted chunks     ").append(statusFilter == null
                        ? "every status, including the ones the generator has not finished"
                        : "only status " + statusFilter).append('\n')
                .append("region files read  ").append(census.readFiles()).append(" of ")
                .append(census.regionFiles()).append('\n')
                .append("chunks read        ").append(census.chunks()).append(" (limit ")
                .append(chunkLimit).append(")\n")
                .append("sections stored    ").append(census.sections()).append('\n')
                .append(line("empty (air only)", census.empty(), census.sections()))
                .append(line("uniform non air", census.uniform(), census.sections()))
                .append(line("mixed", census.mixed(), census.sections()))
                .append(line("without blocks", census.withoutBlockStates(), census.sections()))
                .append(line("shareable total", census.empty() + census.uniform(), census.sections()))
                .append('\n')
                .append("what the world is made of\n")
                .append("chunk status       ").append(census.statuses()).append('\n')
                .append("block names        ").append(census.nonAirNames().size())
                .append(" other than air\n")
                .append("most common        ").append(topNames(census.nonAirNames())).append('\n')
                .append('\n')
                .append("verification against the decoder of the loader\n")
                .append("sections unpacked  ").append(census.verification().packedVerified)
                .append(" packed, ").append(census.verification().flatVerified).append(" without data\n")
                .append("mismatches         ").append(census.verification().mismatches()).append('\n')
                .append("mixed but uniform  ").append(census.verification().mixedButUniform)
                .append(" of the verified mixed sections, ").append(census.verification().mixedButAir)
                .append(" of them air\n")
                .append('\n')
                .append("empty share by section height\n")
                .append(String.format("%6s %10s %10s %8s%n", "Y", "sections", "empty", "share"));

        for (int slot = 0; slot < Y_RANGE; slot++) {
            if (census.totalByY()[slot] == 0) {
                continue;
            }
            report.append(String.format("%6d %10d %10d %7.1f%%%n", slot - Y_OFFSET, census.totalByY()[slot],
                    census.emptyByY()[slot], 100.0 * census.emptyByY()[slot] / census.totalByY()[slot]));
        }
        return report.toString();
    }

    /**
     * Renders the most common block names of the world.
     *
     * @param names every block name other than air, with the amount of palettes holding it
     * @return the rendered names, or a note that the world holds none
     */
    private static String topNames(Map<String, Integer> names) {
        if (names.isEmpty()) {
            return "none, the sections that were read hold nothing but air";
        }
        return names.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(REPORTED_NAMES)
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
    }

    /**
     * Renders one line of the class table.
     *
     * @param label the name of the class
     * @param value the amount of sections in the class
     * @param total the amount of sections that were counted
     * @return the rendered line
     */
    private static String line(String label, int value, int total) {
        return String.format("%-18s %10d  %6.2f%%%n", label, value, total == 0 ? 0.0 : 100.0 * value / total);
    }

    /**
     * Finds the region directory of the world the census reads.
     *
     * @return the region directory or null if no world was found
     */
    private static @Nullable Path locateRegionDirectory() {
        final String configured = System.getProperty(WORLD_PROPERTY);

        if (configured != null && !configured.isBlank()) {
            return regionDirectoryOf(Path.of(configured));
        }
        Path candidate = Path.of("").toAbsolutePath();

        for (int depth = 0; depth < SEARCH_DEPTH && candidate != null; depth++, candidate = candidate.getParent()) {
            final Path world = candidate.resolve(DEMO_WORLD);

            if (!Files.isDirectory(world)) {
                continue;
            }
            final Path region = regionDirectoryOf(world);

            if (region != null) {
                return region;
            }
        }
        return null;
    }

    /**
     * Resolves the region directory of a world root.
     * <p>
     * Both layouts are accepted. A world written by a recent server keeps its region files under
     * {@code dimensions/<namespace>/<value>/region} and an older one under {@code region}, and the
     * directory the demo asks for a world in may hold the world root one level further down.
     * </p>
     *
     * @param root the world root, a region directory or a directory holding a world root
     * @return the region directory or null if none of the layouts holds region files
     */
    private static @Nullable Path regionDirectoryOf(Path root) {
        final Path direct = directRegionDirectory(root);

        if (direct != null) {
            return direct;
        }
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(root)) {
            final List<Path> directories = entries.filter(Files::isDirectory).sorted().toList();

            for (Path entry : directories) {
                final Path nested = directRegionDirectory(entry);

                if (nested != null) {
                    return nested;
                }
            }
        } catch (IOException exception) {
            return null;
        }
        return null;
    }

    /**
     * Resolves the region directory of a world root without descending into unrelated directories.
     *
     * @param root the world root or a region directory
     * @return the region directory or null if neither layout holds region files
     */
    private static @Nullable Path directRegionDirectory(Path root) {
        if (holdsRegionFile(root)) {
            return root;
        }
        final Path legacy = root.resolve("region");

        if (holdsRegionFile(legacy)) {
            return legacy;
        }
        final Path current = root.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region");
        return holdsRegionFile(current) ? current : null;
    }

    /**
     * Decides whether a directory holds at least one region file.
     *
     * @param directory the directory to check
     * @return whether the directory holds a region file
     */
    private static boolean holdsRegionFile(Path directory) {
        return !regionFiles(directory).isEmpty();
    }

    /**
     * Lists the region files of a directory in a stable order.
     *
     * @param directory the directory to list
     * @return the region files, or an empty list if the directory holds none or cannot be read
     */
    private static List<Path> regionFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            final List<Path> files = new ArrayList<>(entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(REGION_SUFFIX))
                    .toList());
            files.sort(Comparator.comparing(Path::toString));
            return files;
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * Reads an int from a system property.
     *
     * @param key          the name of the property
     * @param defaultValue the value to use if the property is absent or unreadable
     * @return the value of the property
     */
    private static int intProperty(String key, int defaultValue) {
        final String value = System.getProperty(key);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            final int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    /**
     * Re-reads a bounded sample of the sections through the decoder the loader uses and confirms
     * that the fast classification agrees with what the section actually holds.
     * <p>
     * The classification looks at the shape of a palette container and never unpacks it, which is
     * what makes a census over thousands of chunks cheap and is also what makes every one of its
     * failure modes silent. This class closes that gap for a sample: it hands the very same container
     * to {@code SectionCodec}, unpacks all four thousand ninety six block entries through
     * {@code PaletteData} and compares the result against the class the container was sorted into. A
     * section the census called air only whose entries are not all air, or one it called uniform
     * whose entries are not all the same, is a defect of the census and not a property of the world.
     * </p>
     * <p>
     * The budget is spent per storage shape rather than in one pool. Packed sections are the rare
     * ones in almost every world and the ones a misread of the format hides in, so they get a budget
     * of their own that a flood of data less sections cannot exhaust.
     * </p>
     * <p>
     * The decode resolves palette entries through an interning resolver instead of a block registry.
     * The verification asks whether the entries of a section are all equal and whether they are all
     * air, and both questions are answered by identity of the entries rather than by their meaning,
     * so no server has to be started to answer them. The properties of an entry take part in that
     * identity, because a section of oak stairs facing four ways is not a uniform section.
     * </p>
     */
    private static final class Verification {

        private final InterningResolver resolver = new InterningResolver();
        private final int airId = this.resolver.toId(AIR_NAME, null);

        private int packedBudget = VERIFIED_PER_SHAPE;
        private int flatBudget = VERIFIED_PER_SHAPE;
        private int packedVerified;
        private int flatVerified;
        private int mismatches;
        private @Nullable String firstMismatch;
        private int mixedButUniform;
        private int mixedButAir;

        /**
         * Verifies one section against its class if the budget of its storage shape allows it.
         *
         * @param blockStates  the block palette container of the section
         * @param sectionClass the class the census sorted the section into
         * @throws IOException if the container cannot be decoded
         */
        private void verify(CompoundBinaryTag blockStates, SectionClass sectionClass) throws IOException {
            if (sectionClass == SectionClass.NO_BLOCK_STATES) {
                return;
            }
            final boolean packed = blockStates.get(DATA_KEY) instanceof LongArrayBinaryTag;

            if (packed) {
                if (this.packedBudget == 0) {
                    return;
                }
                this.packedBudget--;
                this.packedVerified++;
            } else {
                if (this.flatBudget == 0) {
                    return;
                }
                this.flatBudget--;
                this.flatVerified++;
            }

            final PaletteData data = SectionCodec.decode(
                    blockStates, this.resolver, BLOCK_ENTRIES, Palette.BLOCK_PALETTE_MIN_BITS);
            final int[] values = data.unpack();
            boolean allEqual = true;
            boolean allAir = true;

            for (int value : values) {
                allEqual &= value == values[0];
                allAir &= value == this.airId;
            }

            switch (sectionClass) {
                case EMPTY -> {
                    if (!allAir) {
                        record(sectionClass, "its entries are not all air");
                    }
                }
                case UNIFORM -> {
                    if (!allEqual) {
                        record(sectionClass, "its entries are not all the same");
                    } else if (allAir) {
                        record(sectionClass, "its entries are all air");
                    }
                }
                case MIXED -> {
                    // Not a defect. A palette of several entries whose indices all point at one of
                    // them is what a world looks like after a build was torn down again, and the
                    // class documentation states that such a section is counted as mixed. Counting
                    // how often it happens turns the stated lower bound into a measured one.
                    if (allAir) {
                        this.mixedButAir++;
                        this.mixedButUniform++;
                    } else if (allEqual) {
                        this.mixedButUniform++;
                    }
                }
                case NO_BLOCK_STATES -> throw new IllegalStateException("A section without blocks is not verified");
            }
        }

        /**
         * Records a section whose content contradicts the class it was sorted into.
         *
         * @param sectionClass the class the census sorted the section into
         * @param reason       what the unpacked entries say instead
         */
        private void record(SectionClass sectionClass, String reason) {
            this.mismatches++;

            if (this.firstMismatch == null) {
                this.firstMismatch = "a section was counted as " + sectionClass + " although " + reason;
            }
        }

        /**
         * Returns the amount of sections whose content contradicted their class.
         *
         * @return the amount of mismatches
         */
        private int mismatches() {
            return this.mismatches;
        }

        /**
         * Returns the description of the first mismatch, which names what went wrong.
         *
         * @return the description or null if every verified section agreed with its class
         */
        private @Nullable String firstMismatch() {
            return this.firstMismatch;
        }
    }

    /**
     * Resolves palette entries into ids by interning them, without asking a block registry.
     * <p>
     * The verification needs identity of palette entries and nothing else, so an id that is unique
     * per name and property set answers every question it asks. Building the ids this way keeps the
     * whole census free of a running server, which is what lets it run inside an ordinary test task.
     * </p>
     */
    private static final class InterningResolver implements PaletteEntryResolver {

        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> names = new ArrayList<>();

        /**
         * {@inheritDoc}
         */
        @Override
        public int toId(String name, @Nullable CompoundBinaryTag properties) {
            final String key = properties == null || properties.size() == 0 ? name : name + describe(properties);
            final Integer known = this.ids.get(key);

            if (known != null) {
                return known;
            }
            final int id = this.names.size();
            this.names.add(name);
            this.ids.put(key, id);
            return id;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public CompoundBinaryTag toEntry(int id) {
            return CompoundBinaryTag.builder().putString(NAME_KEY, this.names.get(id)).build();
        }

        /**
         * Renders the properties of a palette entry into a stable key.
         *
         * @param properties the properties of the entry
         * @return the key of the properties
         */
        private static String describe(CompoundBinaryTag properties) {
            final Map<String, String> sorted = new TreeMap<>();

            for (Map.Entry<String, ? extends BinaryTag> property : properties) {
                final BinaryTag value = property.getValue();
                sorted.put(property.getKey(),
                        value instanceof StringBinaryTag string ? string.value() : value.toString());
            }
            return sorted.toString();
        }
    }

    /**
     * The class a stored section belongs to.
     */
    private enum SectionClass {

        /**
         * The section stores a palette of one entry and that entry is air.
         */
        EMPTY,

        /**
         * The section stores a palette of one entry and that entry is not air.
         */
        UNIFORM,

        /**
         * The section stores a palette of more than one entry.
         */
        MIXED,

        /**
         * The section stores no block palette at all and therefore holds no blocks.
         */
        NO_BLOCK_STATES
    }

    /**
     * The result of a census.
     *
     * @param readFiles            the amount of region files that were read
     * @param regionFiles          the amount of region files the directory holds
     * @param chunks               the amount of chunks that were read
     * @param chunksWithoutSections the amount of read chunks that resolved no section at all
     * @param sections             the amount of sections those chunks store
     * @param empty                the amount of sections that hold nothing but air
     * @param uniform              the amount of sections that hold a single block that is not air
     * @param mixed                the amount of sections that store more than one palette entry
     * @param withoutBlockStates   the amount of sections that store no block palette at all
     * @param packedEmpty          the amount of sections counted as air only that still store packed data
     * @param totalByY             the amount of sections per section height
     * @param emptyByY             the amount of empty sections per section height
     * @param nonAirNames          every block name other than air, with the amount of palettes holding it
     * @param statuses             the generation status of the read chunks, with the amount of chunks
     * @param verification         the result of re-reading a sample through the decoder of the loader
     */
    private record Census(int readFiles, int regionFiles, int chunks, int chunksWithoutSections, int sections,
                          int empty, int uniform, int mixed, int withoutBlockStates, int packedEmpty,
                          int[] totalByY, int[] emptyByY, Map<String, Integer> nonAirNames,
                          Map<String, Integer> statuses, Verification verification) {
    }
}
