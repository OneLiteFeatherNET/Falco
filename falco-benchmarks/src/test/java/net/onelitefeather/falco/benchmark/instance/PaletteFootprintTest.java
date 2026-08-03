package net.onelitefeather.falco.benchmark.instance;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.instance.palette.Palettes;
import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link PaletteFootprintTest} class measures how many bytes a Minestom block palette retains,
 * across every storage model the palette has and across the size of the palette itself.
 * <p>
 * This is a measurement expressed as a test rather than as a JMH benchmark on purpose. The question
 * is retained size, not throughput, and retained size is precisely what {@code -prof gc} cannot
 * answer: that profiler reports the allocation rate of a benchmark, which says nothing about how
 * much a long lived structure holds on to once it has stopped allocating. JOL walks the object graph
 * of a live instance instead and returns bytes, which is the unit the entire memory argument of this
 * project is denominated in. JOL also needs {@code -Djdk.attach.allowAttachSelf=true} to read the
 * real object header layout of the running VM, and the build sets that flag on the test JVM rather
 * than on the JMH JVM. That is the second reason this file is a test.
 * </p>
 *
 * <h2>Why every table names the mode JOL measured in</h2>
 * <p>
 * JOL has two ways of answering how large an object is: it asks the instrumentation agent it attached
 * to the running JVM, or, when the attach failed, it computes the size from a layout model and keeps
 * answering without saying so. Only the first is a measurement, so every header below states which of
 * the two produced the numbers under it, and a run that cannot reach the agent stops with an
 * assumption rather than publishing a modelled table. {@link JolMeasurement} makes that call and
 * explains it; this class only asks it first and prints what it says.
 * </p>
 * <p>
 * That guard is also what keeps this class from breaking its neighbours. {@code jol.magicFieldOffset},
 * the option {@link ChunkFootprintTest} needs to walk a chunk at all, is read by JOL exactly once, in
 * the class initialiser that the first JOL call in the test JVM triggers — which used to be whichever
 * of the two classes JUnit happened to run first. It is a JVM argument of the build now, and
 * {@link JolMeasurement#require()} verifies that JOL really saw it, from every class that touches JOL,
 * so the flag cannot silently go missing again.
 * </p>
 *
 * <h2>What the storage models are, and why the null fields are the point</h2>
 * <p>
 * {@code PaletteImpl} has three storage models and each one drops fields the other two carry. At
 * {@code bitsPerEntry == 0} the palette holds a single value in an {@code int} field and every array
 * reference is null. In indirect mode, {@code bitsPerEntry} between {@code 4} and {@code 8}, it
 * holds a packed {@code long[]} of palette indices, an {@code IntArrayList} that maps an index back
 * to a block state, and an {@code Int2IntOpenHashMap} that maps a block state to an index. In direct
 * mode, {@code bitsPerEntry == 15}, it holds the packed {@code long[]} alone and both palette
 * structures are null again; the field declarations say so in a comment.
 * </p>
 * <p>
 * A footprint measurement that only printed totals would hide exactly that. The tests below assert
 * on the object graph itself: an empty palette must be a single object with nothing hanging off it, a
 * direct palette must be exactly two objects, and an indirect palette must hold exactly one of each
 * palette structure. If a future Minestom version starts allocating those structures eagerly, these
 * assertions fail before any number in a table gets a chance to look plausible.
 * </p>
 *
 * <h2>The break-even is the number this file exists for</h2>
 * <p>
 * The research report claims that the reverse index of the indirect mode makes an indirect palette
 * more expensive than a direct one somewhere above {@code 192} entries, and marks the claim as a
 * hypothesis, because it came out of arithmetic over assumed object layouts rather than out of a
 * measurement. An earlier round of the same arithmetic said {@code 256}. Two hand computations that
 * disagree by a factor close to two are not a basis on which to choose a palette representation, so
 * the break-even is measured here and nowhere else.
 * </p>
 * <p>
 * The measurement walks the whole indirect range, one entry at a time, and reports the smallest
 * palette size whose indirect footprint reaches the constant footprint of a direct palette. It does
 * that twice, because Minestom builds indirect palettes along two different paths and they do not
 * cost the same. {@code Palette#load(int[], long[])}, the path the Anvil loader takes, hands the
 * palette array to {@code new IntArrayList(int[])} and sizes the reverse map with
 * {@code new Int2IntOpenHashMap(int)}, so both structures come out sized to the content. Growing a
 * palette through {@code Palette#set(int, int, int, int)}, the path every block placement takes,
 * starts from the default capacity and grows in steps, so both structures carry slack. The
 * break-even of the two paths is not the same number, and the report only ever spoke of one.
 * </p>
 *
 * <h2>Why the alternative reverse index is measured as a bare structure</h2>
 * <p>
 * {@code Palette} is declared {@code public sealed interface Palette permits PaletteImpl}. A palette
 * that swaps the {@code Int2IntOpenHashMap} for a sorted {@code int[]} cannot be written at all: not
 * as a subtype, not through reflection and not through a proxy, because the permits clause is
 * enforced by the verifier and not by the compiler alone. The alternative is therefore measured as
 * what it would be if Falco ever owned its own section storage, namely as bare arrays, and the
 * comparison is one of structures rather than of palettes.
 * </p>
 * <p>
 * Two alternatives are measured, because the substitution is not free of consequences and the two
 * ways of paying for it differ by a third of the memory. Keeping the palette in insertion order, as
 * Minestom does, means the packed array never has to be rewritten when a state is added, but the
 * reverse direction then needs a sorted copy of the states plus a parallel array of the palette
 * index each of them maps to: three arrays of the palette size. Keeping the palette in sorted order
 * collapses all three into one, because the palette index becomes the position in the sorted array
 * and both directions are then the same array, but every insertion in the middle shifts the indices
 * of everything behind it and the packed array of {@code 4096} entries has to be remapped. The
 * second is the cheaper structure and the more expensive write, and only a measurement of both makes
 * that a choice rather than a preference.
 * </p>
 *
 * <h2>What this measurement deliberately does not see</h2>
 * <p>
 * {@code PaletteImpl} keeps a {@code ThreadLocal<int[]>} write cache of {@code 4096} ints, which is
 * {@code 16} KiB per thread that has ever written to a palette and is never released. It hangs off a
 * static field, so it is not part of the object graph of any instance and no number below contains
 * it. It has to be counted once per thread of the pool, separately.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * The measured numbers depend on the object header layout, so a run has to say which layout it
 * means. The build wires both through one property and every table header repeats the mode:
 * </p>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:test --tests "*PaletteFootprintTest" -i
 * ./gradlew :falco-benchmarks:test --tests "*PaletteFootprintTest" -Pfalco.compactHeaders -i
 * }</pre>
 * <p>
 * The first line measures the layout of a stock JDK 25 under {@code -XX:-UseCompactObjectHeaders},
 * the second the layout Falco would see under {@code -XX:+UseCompactObjectHeaders}. Both tables are
 * needed before a break-even is quoted anywhere, because compact headers take bytes off every one of
 * the four objects an indirect palette is made of and none off the payload of the packed
 * {@code long[]} of a direct one, which moves the break-even upwards.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ResourceLock(Resources.GLOBAL)
class PaletteFootprintTest {

    /**
     * The edge length of a block palette, mirrored from {@code Palette.BLOCK_DIMENSION}.
     */
    private static final int DIMENSION = Palette.BLOCK_DIMENSION;

    /**
     * The smallest amount of bits an indirect block palette uses per entry.
     */
    private static final int MIN_BITS = Palette.BLOCK_PALETTE_MIN_BITS;

    /**
     * The largest amount of bits an indirect block palette uses per entry.
     * One more bit and the palette switches to direct storage.
     */
    private static final int MAX_BITS = Palette.BLOCK_PALETTE_MAX_BITS;

    /**
     * The amount of bits a direct block palette uses per entry.
     */
    private static final int DIRECT_BITS = Palette.BLOCK_PALETTE_DIRECT_BITS;

    /**
     * The amount of entries a block palette holds.
     */
    private static final int ENTRIES = DIMENSION * DIMENSION * DIMENSION;

    /**
     * The largest palette size that still stays in indirect mode.
     * Beyond it both {@code load} and {@code set} switch the palette to direct storage.
     */
    private static final int MAX_INDIRECT_SIZE = Palettes.maxPaletteSize(MAX_BITS);

    /**
     * The seed every generated block state sequence uses, so two runs measure the same palettes.
     * <p>
     * It is {@link BenchmarkConstants#SEED}, the same seed the JMH benchmarks draw their block state
     * sequences from. Sharing it is what lets a footprint printed here be read next to a timing
     * printed by {@code PaletteIndirectGetBenchmark} without asking whether the two saw the same
     * palettes.
     * </p>
     */
    private static final long SEED = BenchmarkConstants.SEED;

    /**
     * The palette sizes the printed tables walk.
     * <p>
     * The ladder is dense around the top of the indirect range because that is where the break-even
     * is expected and where the reverse map takes its last rehash step, and sparse below it because
     * the shape there is a straight line.
     * </p>
     */
    private static final int[] SIZE_LADDER = {1, 2, 4, 8, 16, 32, 64, 128, 160, 192, 224, 256};

    /**
     * Verifies that a palette which has never been written to is a single object, and reports how
     * many bytes that object costs.
     * <p>
     * This is the state the {@code 24} sections of a fresh overworld chunk are in, twice over, once
     * for blocks and once for biomes. If the empty palette carried a backing array, the fixed cost of
     * a chunk would be dominated by palettes rather than by object headers, and the first row of the
     * memory table of the report would point at the wrong thing.
     * </p>
     */
    @Test
    void emptyPaletteHoldsNoArrays() {
        JolMeasurement.require();

        Palette palette = Palette.blocks();
        GraphLayout layout = GraphLayout.parseInstance(palette);

        printHeader("empty palette, bitsPerEntry == 0");
        System.out.println(layout.toFootprint());

        assertEquals(0, palette.bitsPerEntry(), "a fresh block palette must be in single value mode");
        assertEquals(1L, layout.totalCount(), "a single value palette must not reference any object");
        assertEquals(ClassLayout.parseInstance(palette).instanceSize(), layout.totalSize(),
                "the retained size of a single value palette must be its own instance size");
        assertFalse(holds(layout, IntArrayList.class), "a single value palette must not hold a forward index");
        assertFalse(holds(layout, Int2IntOpenHashMap.class), "a single value palette must not hold a reverse index");
    }

    /**
     * Verifies that a direct palette holds nothing but its packed array, and that its footprint does
     * not depend on what is stored in it.
     * <p>
     * The independence is the load bearing half of this test. A direct palette is what every
     * generated chunk ends up in, and the reason the break-even below can be a single number rather
     * than a curve is that the right hand side of the comparison is constant. Two palettes with
     * entirely different content are measured and have to come out equal before that constant is used
     * anywhere.
     * </p>
     */
    @Test
    void directPaletteHoldsNoPaletteStructures() {
        JolMeasurement.require();

        int[] states = distinctStates(512);
        Palette dense = Palette.blocks(DIRECT_BITS);
        for (int index = 0; index < ENTRIES; index++) {
            dense.set(index & 15, index >> 8, (index >> 4) & 15, states[index % states.length]);
        }
        Palette sparse = Palette.blocks(DIRECT_BITS);
        sparse.set(0, 0, 0, states[1]);

        GraphLayout layout = GraphLayout.parseInstance(dense);

        printHeader("direct palette, bitsPerEntry == " + DIRECT_BITS);
        System.out.println(layout.toFootprint());

        assertEquals(DIRECT_BITS, dense.bitsPerEntry(), "the palette must be in direct mode");
        assertEquals(2L, layout.totalCount(), "a direct palette must be the palette and its packed array");
        assertFalse(holds(layout, IntArrayList.class), "a direct palette must not hold a forward index");
        assertFalse(holds(layout, Int2IntOpenHashMap.class), "a direct palette must not hold a reverse index");
        assertEquals(Palettes.arrayLength(DIMENSION, DIRECT_BITS), packedLength(dense),
                "a direct palette must pack every entry at the direct width");
        assertEquals(GraphLayout.parseInstance(sparse).totalSize(), layout.totalSize(),
                "the footprint of a direct palette must not depend on its content");
    }

    /**
     * Verifies that an indirect palette holds exactly one forward and one reverse index, at every
     * width the indirect mode covers.
     * <p>
     * Exactly one of each matters more than it reads. The reverse map is the structure the break-even
     * turns on, and a palette that carried a second copy of it, or that shared one with a neighbouring
     * palette, would make every byte figure below either double counted or unattributable.
     * </p>
     */
    @Test
    void indirectPaletteHoldsBothIndexes() {
        JolMeasurement.require();

        printHeader("indirect palettes, one row per width, filled to the width");
        for (int bits = MIN_BITS; bits <= MAX_BITS; bits++) {
            int size = Palettes.maxPaletteSize(bits);
            Palette palette = grown(bits, size);
            GraphLayout layout = GraphLayout.parseInstance(palette);

            System.out.printf("bitsPerEntry=%2d  paletteSize=%3d  objects=%d  bytes=%d%n",
                    bits, size, layout.totalCount(), layout.totalSize());

            assertEquals(bits, palette.bitsPerEntry(), "the palette must stay at the requested width");
            assertEquals(1L, layout.getClassCounts().count(IntArrayList.class),
                    "an indirect palette must hold exactly one forward index");
            assertEquals(1L, layout.getClassCounts().count(Int2IntOpenHashMap.class),
                    "an indirect palette must hold exactly one reverse index");
        }
    }

    /**
     * Reports the footprint grid over every storage model and every palette size that model can hold,
     * and verifies the two invariants which make the grid readable.
     * <p>
     * The grid separates the two costs that a single palette footprint mixes together. The packed
     * {@code long[]} is a function of the width alone: it is the same amount of bytes at width
     * {@code 4} whether the palette holds two states or sixteen. Both index structures are a function
     * of the palette size alone. Reading a column downwards therefore prices the width, reading a row
     * across prices the size, and only the sum of the two is what a section actually costs.
     * </p>
     * <p>
     * The width is requested here rather than derived from the size. Minestom would never build a
     * palette of width {@code 8} holding two states on its own, but measuring that cell is exactly
     * what separates the two costs, and the cell is reachable through {@code Palette#blocks(int)},
     * which takes the width as an argument.
     * </p>
     * <p>
     * Width {@code 0} and width {@code 15} appear with a single row each, because a single value
     * palette holds one value by definition and a direct palette has no palette size at all. Writing
     * them as one row rather than repeating a constant across the whole size ladder is what keeps the
     * table honest about which cells exist.
     * </p>
     */
    @Test
    void footprintGridOverWidthAndSize() {
        JolMeasurement.require();

        printHeader("footprint grid, bytes retained by one block palette");
        System.out.printf("%12s %12s %8s %10s %12s %12s%n",
                "bitsPerEntry", "paletteSize", "objects", "bytes", "packedBytes", "indexBytes");

        row(0, 0, GraphLayout.parseInstance(Palette.blocks()));
        row(DIRECT_BITS, 0, GraphLayout.parseInstance(Palette.blocks(DIRECT_BITS)));

        for (int bits = MIN_BITS; bits <= MAX_BITS; bits++) {
            long packed = -1L;
            long previous = -1L;
            for (int size : SIZE_LADDER) {
                if (size > Palettes.maxPaletteSize(bits)) {
                    continue;
                }
                GraphLayout layout = GraphLayout.parseInstance(grown(bits, size));
                row(bits, size, layout);

                long packedBytes = layout.getClassSizes().count(long[].class);
                if (packed < 0) {
                    packed = packedBytes;
                } else {
                    assertEquals(packed, packedBytes,
                            "the packed array of width " + bits + " must not depend on the palette size");
                }
                assertTrue(layout.totalSize() >= previous,
                        "a larger palette of width " + bits + " must not retain fewer bytes");
                previous = layout.totalSize();
            }
        }
    }

    /**
     * Measures the break-even between an indirect and a direct palette, once for a palette built by
     * {@code load} and once for a palette grown by {@code set}, and reports both.
     * <p>
     * The two curves answer two questions the report asked as one. The {@code load} curve is the
     * palette an Anvil region file produces, sized exactly to its content, and it decides whether
     * keeping a loaded chunk in indirect mode is worth the memory. The {@code set} curve is the
     * palette a running server produces one placement at a time, carrying the slack of two growth
     * policies, and it decides whether a chunk that is being edited should be pushed to direct mode
     * early rather than be allowed to climb to the top of the indirect range.
     * </p>
     * <p>
     * The assertions bracket the break-even instead of naming it. A palette holding a single state
     * has to be cheaper than a direct one, or the indirect mode would have no reason to exist, and a
     * palette filled to the top of the indirect range has to be more expensive than a direct one, or
     * there is no break-even inside that range at all and the premise of the report is wrong. Both
     * are claims a measurement can settle. The number in between is printed rather than asserted,
     * because asserting it would pin down exactly the arithmetic this file was written to replace.
     * </p>
     */
    @Test
    void breakEvenBetweenIndirectAndDirect() {
        JolMeasurement.require();

        long direct = GraphLayout.parseInstance(Palette.blocks(DIRECT_BITS)).totalSize();

        long[] loaded = new long[MAX_INDIRECT_SIZE + 1];
        long[] grown = new long[MAX_INDIRECT_SIZE + 1];
        for (int size = 1; size <= MAX_INDIRECT_SIZE; size++) {
            loaded[size] = GraphLayout.parseInstance(loaded(size)).totalSize();
            grown[size] = GraphLayout.parseInstance(grown(naturalBits(size), size)).totalSize();
        }

        printHeader("break-even against a direct palette of " + direct + " bytes");
        System.out.printf("%12s %6s %14s %14s %14s %14s%n",
                "paletteSize", "bits", "load() bytes", "set() bytes", "load()-direct", "set()-direct");
        for (int size : SIZE_LADDER) {
            System.out.printf("%12d %6d %14d %14d %14d %14d%n",
                    size, naturalBits(size), loaded[size], grown[size],
                    loaded[size] - direct, grown[size] - direct);
        }
        System.out.println("first size at which load() reaches the direct footprint: " + firstAtLeast(loaded, direct));
        System.out.println("first size at which set()  reaches the direct footprint: " + firstAtLeast(grown, direct));

        assertTrue(loaded[1] < direct,
                "an indirect palette of one state must be cheaper than a direct one, got "
                        + loaded[1] + " against " + direct);
        assertTrue(loaded[MAX_INDIRECT_SIZE] > direct,
                "a full indirect palette must be more expensive than a direct one, got "
                        + loaded[MAX_INDIRECT_SIZE] + " against " + direct
                        + "; without that there is no break-even inside the indirect range");

        int breakEven = firstAtLeast(loaded, direct);
        assertTrue(breakEven > 1 && breakEven <= MAX_INDIRECT_SIZE,
                "the break-even must lie inside the indirect range, got " + breakEven);
        assertTrue(loaded[breakEven - 1] < direct,
                "the reported break-even must be the first size that reaches the direct footprint");
    }

    /**
     * Measures what the palette would cost if its indexes were plain arrays instead of an
     * {@code IntArrayList} plus an {@code Int2IntOpenHashMap}, and reports how far each alternative
     * moves the break-even.
     * <p>
     * Three structures are compared at every palette size. The first is what Minestom allocates: the
     * list of states in palette order plus the hash map of the reverse direction. The second keeps
     * the palette in insertion order and replaces only the reverse direction, which needs a sorted
     * copy of the states and a parallel array of palette indices next to the forward array, so three
     * arrays in total. The third keeps the palette in sorted order, where the palette index is the
     * position in the sorted array and one array serves both directions.
     * </p>
     * <p>
     * The hash map is the structure with the sharp edges. It is sized through
     * {@code new Int2IntOpenHashMap(expected)}, which rounds up to a power of two at load factor
     * {@code 0.75} and then still rehashes once the insertions reach that fill, so its footprint is a
     * staircase rather than a line, and both of its arrays are one entry longer than the power of
     * two. An array pair is exactly four bytes per state per array plus a header. Where the staircase
     * steps decides how much of the gap below is real and how much is an artefact of one particular
     * palette size, which is why the substituted curves are walked at every size and only printed at
     * the ladder.
     * </p>
     *
     * @see #breakEvenBetweenIndirectAndDirect()
     */
    @Test
    void arrayIndexesAgainstFastutilIndexes() {
        JolMeasurement.require();

        long direct = GraphLayout.parseInstance(Palette.blocks(DIRECT_BITS)).totalSize();

        long[] minestom = new long[MAX_INDIRECT_SIZE + 1];
        long[] withStable = new long[MAX_INDIRECT_SIZE + 1];
        long[] withSorted = new long[MAX_INDIRECT_SIZE + 1];

        printHeader("index alternatives, bytes retained by the index structures alone");
        System.out.printf("%12s %14s %14s %14s %16s %16s%n",
                "paletteSize", "fastutil", "3 arrays", "1 array", "3 arrays-direct", "1 array-direct");

        for (int size = 1; size <= MAX_INDIRECT_SIZE; size++) {
            int[] states = distinctStates(size);
            int[] sorted = sortedStates(states);

            long fastutil = GraphLayout.parseInstance(forwardIndex(states), reverseIndex(states)).totalSize();
            long stable = GraphLayout.parseInstance(states.clone(), sorted.clone(), sortedIndices(states)).totalSize();
            long single = GraphLayout.parseInstance(sorted.clone()).totalSize();

            minestom[size] = GraphLayout.parseInstance(loaded(size)).totalSize();
            withStable[size] = minestom[size] - fastutil + stable;
            withSorted[size] = minestom[size] - fastutil + single;

            if (contains(SIZE_LADDER, size)) {
                System.out.printf("%12d %14d %14d %14d %16d %16d%n",
                        size, fastutil, stable, single,
                        withStable[size] - direct, withSorted[size] - direct);
            }

            assertTrue(stable <= fastutil,
                    "three arrays of " + size + " states must not cost more than a list plus a hash map, got "
                            + stable + " against " + fastutil);
            assertTrue(single <= stable, "one array must not cost more than three of the same length");
        }

        int baseBreakEven = firstAtLeast(minestom, direct);
        int stableBreakEven = firstAtLeast(withStable, direct);
        int singleBreakEven = firstAtLeast(withSorted, direct);
        System.out.println("break-even as Minestom stores it:        " + describe(baseBreakEven));
        System.out.println("break-even with a stable sorted index:   " + describe(stableBreakEven));
        System.out.println("break-even with a sorted order palette:  " + describe(singleBreakEven));

        assertTrue(stableBreakEven == -1 || stableBreakEven >= baseBreakEven,
                "a smaller index must not move the break-even towards the direct mode, got "
                        + stableBreakEven + " against " + baseBreakEven);
        assertTrue(singleBreakEven == -1 || singleBreakEven >= baseBreakEven,
                "a smaller index must not move the break-even towards the direct mode, got "
                        + singleBreakEven + " against " + baseBreakEven);
    }

    /**
     * Prints one row of the footprint grid.
     *
     * @param bits   the width of the measured palette
     * @param size   the amount of states the measured palette holds, or {@code 0} when the storage
     *               model has no palette at all
     * @param layout the parsed object graph of the measured palette
     */
    private static void row(int bits, int size, GraphLayout layout) {
        long packed = layout.getClassSizes().count(long[].class);
        long index = layout.getClassSizes().count(IntArrayList.class)
                + layout.getClassSizes().count(Int2IntOpenHashMap.class)
                + layout.getClassSizes().count(int[].class);
        System.out.printf("%12d %12s %8d %10d %12d %12d%n",
                bits, size == 0 ? "-" : Integer.toString(size),
                layout.totalCount(), layout.totalSize(), packed, index);
    }

    /**
     * Builds an indirect palette of the requested width and size by writing distinct states into it,
     * which is the path a running server takes.
     *
     * @param bits the width to build at, between {@value #MIN_BITS} and {@value #MAX_BITS}
     * @param size the amount of distinct states the palette ends up holding
     * @return the built palette
     * @throws IllegalArgumentException if the size does not fit into the requested width
     * @throws IllegalStateException    if the palette left the requested width while being filled
     */
    private static Palette grown(int bits, int size) {
        if (size < 1 || size > Palettes.maxPaletteSize(bits)) {
            throw new IllegalArgumentException("A palette of width " + bits + " cannot hold " + size + " states");
        }
        Palette palette = Palette.blocks(bits);
        // The constructor already seeded the palette with air at index 0, so the loop starts at the
        // second state. The positions are distinct for every index below 256, which is the largest
        // palette the indirect mode holds, so no write ever overwrites an earlier one.
        int[] states = distinctStates(size);
        for (int index = 1; index < size; index++) {
            palette.set(index & 15, 0, (index >> 4) & 15, states[index]);
        }
        if (palette.bitsPerEntry() != bits) {
            throw new IllegalStateException("The palette left width " + bits + " for " + palette.bitsPerEntry());
        }
        return palette;
    }

    /**
     * Builds an indirect palette of the requested size through {@code load}, which is the path the
     * Anvil loader takes.
     * <p>
     * The packed array is filled with palette indices spread over the whole range rather than left at
     * zero. A palette whose packed array points at index {@code 0} everywhere retains the same amount
     * of bytes, but it is a palette no chunk on disk could produce, and a fixture that is wrong in a
     * way the current measurement happens not to see is a trap for the next one.
     * </p>
     *
     * @param size the amount of distinct states the palette holds, from one up to the largest
     *             palette the indirect mode still holds
     * @return the built palette
     * @throws IllegalArgumentException if the size would leave the indirect range
     * @throws IllegalStateException    if {@code load} chose a different width than expected
     */
    private static Palette loaded(int size) {
        if (size < 1 || size > MAX_INDIRECT_SIZE) {
            throw new IllegalArgumentException("A size of " + size + " is outside the indirect range");
        }
        int bits = naturalBits(size);
        int[] states = distinctStates(size);
        long[] packed = new long[Palettes.arrayLength(DIMENSION, bits)];
        Random random = new Random(SEED);
        for (int y = 0; y < DIMENSION; y++) {
            for (int z = 0; z < DIMENSION; z++) {
                for (int x = 0; x < DIMENSION; x++) {
                    Palettes.write(DIMENSION, bits, packed, x, y, z, random.nextInt(size));
                }
            }
        }
        Palette palette = Palette.blocks();
        palette.load(states, packed);
        if (palette.bitsPerEntry() != bits) {
            throw new IllegalStateException("load() chose width " + palette.bitsPerEntry() + " instead of " + bits);
        }
        return palette;
    }

    /**
     * Returns the width {@code load} picks for a palette of the given size.
     * <p>
     * The formula repeats what {@code PaletteImpl#load(int[], long[])} does, rather than calling
     * {@code MathUtils#bitsToRepresent(int)}, so that a fixture never silently follows a change of an
     * internal utility that the palette itself no longer uses.
     * </p>
     *
     * @param size the amount of states in the palette
     * @return the width in bits
     */
    private static int naturalBits(int size) {
        if (size <= 1) {
            return MIN_BITS;
        }
        return Math.max(MIN_BITS, Integer.SIZE - Integer.numberOfLeadingZeros(size - 1));
    }

    /**
     * Returns distinct block state ids, air first, drawn from a fixed seed.
     * <p>
     * Air comes first because every palette a chunk produces holds it at index {@code 0}, and the
     * remaining ids are spread over the range a real block registry occupies rather than being
     * consecutive. Neither choice changes a byte of the footprint, since both index structures are
     * sized by count alone, but a consecutive sequence would be an input no chunk ever has and it
     * would flatter any structure that happens to like dense keys.
     * </p>
     *
     * @param count the amount of ids to return
     * @return the ids, distinct and in a stable order
     */
    private static int[] distinctStates(int count) {
        int[] states = new int[count];
        IntOpenHashSet seen = new IntOpenHashSet(count);
        Random random = new Random(SEED);
        seen.add(0);
        int written = 1;
        while (written < count) {
            int candidate = 1 + random.nextInt(26_000);
            if (seen.add(candidate)) {
                states[written++] = candidate;
            }
        }
        return states;
    }

    /**
     * Builds the forward index the way {@code load} builds it.
     *
     * @param states the palette content
     * @return the list mapping a palette index to a block state
     */
    private static IntArrayList forwardIndex(int[] states) {
        return new IntArrayList(states);
    }

    /**
     * Builds the reverse index the way {@code load} builds it, including its default return value.
     *
     * @param states the palette content
     * @return the map from a block state to its palette index
     */
    private static Int2IntOpenHashMap reverseIndex(int[] states) {
        Int2IntOpenHashMap map = new Int2IntOpenHashMap(states.length);
        map.defaultReturnValue(-1);
        for (int index = 0; index < states.length; index++) {
            map.put(states[index], index);
        }
        return map;
    }

    /**
     * Returns the palette content in ascending order.
     *
     * @param states the palette content
     * @return a sorted copy
     */
    private static int[] sortedStates(int[] states) {
        int[] sorted = states.clone();
        Arrays.sort(sorted);
        return sorted;
    }

    /**
     * Returns the palette index of each state of {@link #sortedStates(int[])}, at the same position.
     *
     * @param states the palette content
     * @return the parallel index array of the stable sorted alternative
     */
    private static int[] sortedIndices(int[] states) {
        int[] sorted = sortedStates(states);
        int[] indices = new int[states.length];
        for (int index = 0; index < states.length; index++) {
            indices[Arrays.binarySearch(sorted, states[index])] = index;
        }
        return indices;
    }

    /**
     * Returns the smallest index of the curve whose value reaches the threshold.
     *
     * @param curve     the measured footprints, indexed by palette size, entry {@code 0} unused
     * @param threshold the footprint to reach
     * @return the palette size, or {@code -1} if the curve never reaches the threshold
     */
    private static int firstAtLeast(long[] curve, long threshold) {
        for (int size = 1; size < curve.length; size++) {
            if (curve[size] >= threshold) {
                return size;
            }
        }
        return -1;
    }

    /**
     * Describes a break-even that may not exist.
     *
     * @param breakEven the palette size, or {@code -1}
     * @return the size, or a sentence saying that the indirect mode never gets that expensive
     */
    private static String describe(int breakEven) {
        return breakEven == -1 ? "never inside the indirect range" : Integer.toString(breakEven);
    }

    /**
     * Returns the length of the packed array of a palette.
     *
     * @param palette the palette to read
     * @return the amount of longs the palette packs its entries into, or {@code 0} if it has none
     */
    private static int packedLength(Palette palette) {
        long[] values = palette.indexedValues();
        return values == null ? 0 : values.length;
    }

    /**
     * Reports whether the object graph holds an instance of the given class.
     *
     * @param layout the parsed object graph
     * @param type   the class to look for
     * @return {@code true} if at least one instance is part of the graph
     */
    private static boolean holds(GraphLayout layout, Class<?> type) {
        return layout.getClassCounts().count(type) > 0;
    }

    /**
     * Reports whether the array holds the value.
     *
     * @param values the array to search
     * @param value  the value to look for
     * @return {@code true} if the value is present
     */
    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prints a table header that names the object header layout the numbers below were measured in,
     * and the mode JOL measured them with.
     * <p>
     * Without the first a table is unreadable, because {@code -XX:+UseCompactObjectHeaders} changes
     * every number in it and the flag is chosen by a Gradle property rather than by this file.
     * Without the second it is not even known to be a table of measurements, because JOL falls back
     * to a layout model when it cannot attach its agent and reports the model exactly as it reports a
     * reading of the heap.
     * </p>
     *
     * @param title what the table below measures
     */
    private static void printHeader(String title) {
        System.out.println();
        System.out.println("== " + title
                + "  [compactObjectHeaders=" + System.getProperty("falco.compactHeaders", "unknown") + "]");
        System.out.println("   " + JolMeasurement.describe());
    }
}
