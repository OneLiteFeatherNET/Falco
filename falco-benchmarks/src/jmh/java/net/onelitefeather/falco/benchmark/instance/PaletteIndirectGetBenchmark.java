package net.onelitefeather.falco.benchmark.instance;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.instance.palette.Palettes;
import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
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
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The {@link PaletteIndirectGetBenchmark} class measures the indirect storage model of the Minestom
 * block palette: the cost of reading through it, the cost of looking a block state up in it, and the
 * cost of growing it, each against a plain array structure that serves the same purpose.
 * <p>
 * Minestom carries palette benchmarks of its own. What they do not carry is the indirect path at the
 * widths between {@code 5} and {@code 8} bits, and the growth of the palette in isolation from the
 * remapping of the packed array. Those are the two measurements taken here; everything Minestom
 * already measures is deliberately left where it is.
 * </p>
 *
 * <h2>Why the fixture is built through load and never through setAll</h2>
 * <p>
 * {@code Palette#setAll(EntrySupplier)} switches the palette to direct storage the moment the
 * supplier returns more than one distinct value. It calls {@code makeDirect} unconditionally in that
 * case, which nulls both palette structures and pushes {@code bitsPerEntry} to {@code 15}. A
 * benchmark that filled its fixture that way and then called it an indirect benchmark would be
 * measuring the direct path under a wrong label, and the wider the palette the more convincing the
 * wrong number would look.
 * </p>
 * <p>
 * {@code Palette#load(int[], long[])} is the path that produces a genuinely indirect palette, and it
 * is also the path a chunk read from an Anvil region file actually takes. The width it picks follows
 * from the size of the palette array alone, so the parameter of this benchmark is the palette size
 * and the width comes along with it: sizes up to {@code 16} give width {@code 4}, then {@code 5},
 * {@code 6}, {@code 7} and finally {@code 8} at {@code 256}. One state more and {@code load} chooses
 * direct storage; the setup refuses to run in that case rather than silently measure it.
 * </p>
 *
 * <h2>What the alternative structure is, and what it is not</h2>
 * <p>
 * {@code Palette} is declared {@code public sealed interface Palette permits PaletteImpl}. A second
 * palette implementation cannot be written: not by subclassing, not through reflection, not through
 * a proxy, because the permits clause is enforced by the verifier rather than by the compiler alone.
 * {@link ArrayIndex} is therefore not a palette and does not pretend to be one. It is a standalone
 * structure that answers the same two questions a palette answers internally, index to state and
 * state to index, out of plain {@code int} arrays instead of an {@code IntArrayList} and an
 * {@code Int2IntOpenHashMap}.
 * </p>
 * <p>
 * It reads the packed data through {@code Palettes#read(int, int, long[], int, int, int)}, the same
 * static helper the palette itself reads through, and it shares the very same {@code long[]} that
 * the measured palette holds. Unpacking is therefore identical work on both sides by construction,
 * and what the two read arms compare is one indirection and nothing else: the palette reaches its
 * states through {@code paletteToValueList.elements()}, the alternative holds the {@code int[]}
 * directly. If that difference disappears in the noise, the answer is that the forward direction of
 * the palette costs nothing worth removing, which is a result and not a failure.
 * </p>
 * <p>
 * The reverse direction is where the two structures genuinely differ. The palette hashes, through
 * {@code Int2IntOpenHashMap#putIfAbsent}, which is what {@code valueToPaletteIndex} calls even when
 * the state is already known. The alternative runs a binary search over a sorted copy of the states
 * and reads the palette index out of a parallel array. A hash lookup is a constant amount of work on
 * one cache line, a binary search over {@code 256} entries is eight dependent loads over a kilobyte;
 * whichever wins, the palette sizes at which it wins are the point of the size axis.
 * </p>
 *
 * <h2>Why the growth arm keeps the packed array out of it</h2>
 * <p>
 * Adding an unknown state to a palette does three things: it inserts into both index structures, it
 * may widen the palette, and widening remaps all {@code 4096} entries of the packed array. The third
 * is the same work for any index structure, so measuring it would add a constant to both sides and
 * bury the difference under it. The growth arm therefore builds the index structures alone, from
 * empty to the parameterised size, and the remap is left to the sibling benchmark that measures
 * {@code optimize}.
 * </p>
 * <p>
 * That decision also fixes which alternative can be measured here. A structure that keeps its
 * palette in sorted order needs a single array for both directions and is the smallest of all, but
 * every insertion in the middle shifts the palette indices behind it and forces a remap of the
 * packed array that Minestom would not have done. It is therefore not comparable in a growth arm
 * that excludes the remap, and it is left to the footprint measurement, where the remap does not
 * exist. What is measured here is the alternative that keeps the palette in insertion order and pays
 * for the reverse direction with a sorted side index, so that the packed array is untouched on both
 * sides and the numbers describe the same task.
 * </p>
 *
 * <h2>Both sides have to agree</h2>
 * <p>
 * Every trial checks, before any measurement is taken, that the two structures return the same state
 * for all {@code 4096} positions, the same palette index for every state of the palette, and the
 * same mapping after being grown from empty, and it fails the trial when they do not. It also checks
 * that the palette really is in indirect mode. A faster number must never come from doing something
 * else, and the way this particular benchmark could quietly start doing something else is that the
 * fixture slides into direct storage.
 * </p>
 *
 * <h2>What the numbers mean</h2>
 * <p>
 * The read and lookup arms perform {@link BenchmarkConstants#BLOCK_ENTRIES} operations per
 * invocation, over a shuffled order so that neither structure gets a sequential walk handed to it,
 * and the reported time is therefore the time for a whole section rather than for one lookup. The
 * growth arm performs as many insertions as the palette size parameter says. Dividing is the reader
 * task; multiplying a per lookup figure by a section is what a section costs.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * The benchmark needs no server: a palette resolves nothing through the block registry and the state
 * ids are generated. It therefore runs under the two fork configuration of the server free
 * benchmarks of this project.
 * </p>
 * <pre>{@code
 * java -jar build/libs/falco-*-jmh.jar "PaletteIndirectGetBenchmark" -f 2 -wi 5 -i 5
 * java -jar build/libs/falco-*-jmh.jar "PaletteIndirectGetBenchmark.(minestom|array)Get" \
 *     -p paletteSize=16,64,256 -f 2 -wi 5 -i 5
 * }</pre>
 * <p>
 * The first line runs the full cross product, which is six methods over six palette sizes. The
 * second is the shortest run that still covers the read path at the bottom, the middle and the top
 * of the indirect range.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class PaletteIndirectGetBenchmark {

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
     * One more bit and {@code load} chooses direct storage instead.
     */
    private static final int MAX_BITS = Palette.BLOCK_PALETTE_MAX_BITS;

    /**
     * The largest block state id the generated palettes draw from.
     * <p>
     * The bound keeps the ids inside the range a real block registry occupies, so that the reverse
     * index sees keys of a realistic magnitude and spread rather than a consecutive run, which every
     * hash and every binary search would find easier than the real thing.
     * </p>
     */
    private static final int STATE_BOUND = 26_000;

    /**
     * The amount of distinct block states the measured palette holds.
     * <p>
     * The ladder is the width ladder in disguise, because {@code load} derives the width from the
     * size: {@code 16} gives width {@code 4}, {@code 32} gives {@code 5}, {@code 64} gives {@code 6},
     * {@code 128} gives {@code 7}, and {@code 192} and {@code 256} both give {@code 8}. The last two
     * share a width on purpose: they hold the width fixed and vary only the amount of entries in the
     * index structures, which is the axis the memory break-even turns on and the one on which a hash
     * and a binary search diverge fastest.
     * </p>
     */
    @Param({"16", "32", "64", "128", "192", "256"})
    public int paletteSize;

    private Palette palette;
    private ArrayIndex arrayIndex;
    private int[] states;
    private int[] positions;
    private int[] queries;

    /**
     * Builds the palette and the alternative structure over the same packed data and verifies that
     * they agree before the first measurement is taken.
     *
     * @throws IllegalStateException if the palette did not end up in indirect mode, or if the two
     *                               structures disagree on any position, any state or any insertion
     */
    @Setup(Level.Trial)
    public void setUp() {
        int bitsPerEntry = naturalBits(this.paletteSize);
        if (bitsPerEntry > MAX_BITS) {
            throw new IllegalStateException("A palette of " + this.paletteSize
                    + " states does not fit into the indirect mode, which stops at width " + MAX_BITS);
        }

        this.states = distinctStates(this.paletteSize);

        long[] packed = new long[Palettes.arrayLength(DIMENSION, bitsPerEntry)];
        Random random = new Random(BenchmarkConstants.SEED);
        for (int y = 0; y < DIMENSION; y++) {
            for (int z = 0; z < DIMENSION; z++) {
                for (int x = 0; x < DIMENSION; x++) {
                    Palettes.write(DIMENSION, bitsPerEntry, packed, x, y, z, random.nextInt(this.paletteSize));
                }
            }
        }

        this.palette = Palette.blocks();
        this.palette.load(this.states, packed);
        if (this.palette.bitsPerEntry() < MIN_BITS || this.palette.bitsPerEntry() > MAX_BITS) {
            throw new IllegalStateException("The fixture left the indirect mode: load() chose width "
                    + this.palette.bitsPerEntry() + " for " + this.paletteSize + " states");
        }
        this.arrayIndex = new ArrayIndex(this.states, this.palette.indexedValues(), this.palette.bitsPerEntry());

        this.positions = shuffledPositions(random);
        this.queries = new int[this.positions.length];
        for (int index = 0; index < this.positions.length; index++) {
            int position = this.positions[index];
            this.queries[index] = this.palette.get(position & 15, (position >> 8) & 15, (position >> 4) & 15);
        }

        verifyBothStructuresAgree();
    }

    /**
     * Measures reading a whole section through the palette of Minestom.
     *
     * @return the sum of the read block states, so that no read can be optimised away
     */
    @Benchmark
    public int minestomGet() {
        Palette palette = this.palette;
        int[] positions = this.positions;
        int sum = 0;
        for (int position : positions) {
            sum += palette.get(position & 15, (position >> 8) & 15, (position >> 4) & 15);
        }
        return sum;
    }

    /**
     * Measures reading the same section through the array structure, over the very same packed data.
     *
     * @return the sum of the read block states, so that no read can be optimised away
     */
    @Benchmark
    public int arrayGet() {
        ArrayIndex index = this.arrayIndex;
        int[] positions = this.positions;
        int sum = 0;
        for (int position : positions) {
            sum += index.get(position & 15, (position >> 8) & 15, (position >> 4) & 15);
        }
        return sum;
    }

    /**
     * Measures the reverse direction of the palette of Minestom, the hashed lookup that every block
     * write goes through.
     * <p>
     * The queried states are all present in the palette, which is what keeps
     * {@code valueToPaletteIndex} a lookup instead of an insertion, and they are queried in the
     * frequency they occur in the section rather than uniformly, because that is the distribution a
     * write pattern over a real chunk produces.
     * </p>
     *
     * @return the sum of the returned palette indices, so that no lookup can be optimised away
     */
    @Benchmark
    public int minestomReverseLookup() {
        Palette palette = this.palette;
        int[] queries = this.queries;
        int sum = 0;
        for (int state : queries) {
            sum += palette.valueToPaletteIndex(state);
        }
        return sum;
    }

    /**
     * Measures the reverse direction of the array structure, the binary search over the sorted states.
     *
     * @return the sum of the returned palette indices, so that no lookup can be optimised away
     */
    @Benchmark
    public int arrayReverseLookup() {
        ArrayIndex index = this.arrayIndex;
        int[] queries = this.queries;
        int sum = 0;
        for (int state : queries) {
            sum += index.indexOf(state);
        }
        return sum;
    }

    /**
     * Measures growing the index structures of Minestom from empty to the parameterised palette size.
     * <p>
     * The two calls per state are the two the palette makes: {@code putIfAbsent} on the reverse map,
     * which is the call that decides whether the state is new, and {@code add} on the forward list.
     * Both growth policies are therefore in the measurement, the doubling of the list and the
     * rehashing of the map, and the remap of the packed array is in neither.
     * </p>
     *
     * @return the built structures, so that the allocation cannot be optimised away
     */
    @Benchmark
    public Object[] fastutilIndexGrowth() {
        int[] states = this.states;
        IntArrayList forward = new IntArrayList();
        Int2IntOpenHashMap reverse = new Int2IntOpenHashMap();
        reverse.defaultReturnValue(-1);
        for (int index = 0; index < states.length; index++) {
            if (reverse.putIfAbsent(states[index], index) == -1) {
                forward.add(states[index]);
            }
        }
        return new Object[]{forward, reverse};
    }

    /**
     * Measures growing the array structure from empty to the parameterised palette size.
     * <p>
     * This is the arm that has to justify the alternative, because it is the one where a sorted array
     * is structurally worse: every insertion shifts the tail of two arrays, which is work that grows
     * with the palette while a hash insertion does not. If the shift stays cheap up to the top of the
     * indirect range, the smaller structure is free; if it does not, the memory the footprint
     * measurement saves has a price and this arm is where it is written down.
     * </p>
     *
     * @return the built structure, so that the allocation cannot be optimised away
     */
    @Benchmark
    public ArrayIndex arrayIndexGrowth() {
        int[] states = this.states;
        ArrayIndex index = new ArrayIndex(states.length);
        for (int state : states) {
            index.add(state);
        }
        return index;
    }

    /**
     * Verifies that the palette and the array structure describe the same section and the same
     * palette, in both directions and after being grown from empty.
     * <p>
     * The check runs once per trial, before any measurement. Without it a change to either side could
     * win time by no longer answering the same question, and the specific way this benchmark could
     * drift is a fixture that stops being indirect, which the setup already refuses, or an
     * alternative structure whose sorted side index is off by one, which nothing else would notice
     * because both arms would still return a number.
     * </p>
     *
     * @throws IllegalStateException if the two structures disagree anywhere
     */
    private void verifyBothStructuresAgree() {
        for (int y = 0; y < DIMENSION; y++) {
            for (int z = 0; z < DIMENSION; z++) {
                for (int x = 0; x < DIMENSION; x++) {
                    int expected = this.palette.get(x, y, z);
                    int actual = this.arrayIndex.get(x, y, z);
                    if (expected != actual) {
                        throw new IllegalStateException("The structures disagree at " + x + ", " + y + ", " + z
                                + ": the palette reads " + expected + " and the array reads " + actual);
                    }
                }
            }
        }

        for (int state : this.states) {
            int expected = this.palette.valueToPaletteIndex(state);
            int actual = this.arrayIndex.indexOf(state);
            if (expected != actual) {
                throw new IllegalStateException("The structures disagree on state " + state
                        + ": the palette maps it to " + expected + " and the array to " + actual);
            }
        }

        ArrayIndex grown = arrayIndexGrowth();
        if (grown.size() != this.states.length) {
            throw new IllegalStateException("Growing the array structure produced " + grown.size()
                    + " states instead of " + this.states.length);
        }
        for (int index = 0; index < this.states.length; index++) {
            int actual = grown.indexOf(this.states[index]);
            if (actual != index) {
                throw new IllegalStateException("Growing the array structure assigned state " + this.states[index]
                        + " the index " + actual + " instead of " + index);
            }
        }
    }

    /**
     * Returns the width {@code load} picks for a palette of the given size.
     * <p>
     * The formula repeats what {@code PaletteImpl#load(int[], long[])} does rather than calling the
     * internal utility it uses, so that the fixture never silently follows a change of a helper the
     * palette itself may stop using.
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
     * Returns distinct block state ids, air first, drawn from the shared seed.
     *
     * @param count the amount of ids to return
     * @return the ids, distinct and in a stable order
     */
    private static int[] distinctStates(int count) {
        int[] states = new int[count];
        IntOpenHashSet seen = new IntOpenHashSet(count);
        Random random = new Random(BenchmarkConstants.SEED);
        seen.add(0);
        int written = 1;
        while (written < count) {
            int candidate = 1 + random.nextInt(STATE_BOUND);
            if (seen.add(candidate)) {
                states[written++] = candidate;
            }
        }
        return states;
    }

    /**
     * Returns every position of a section, packed as {@code x | z << 4 | y << 8}, in a shuffled order.
     * <p>
     * A sequential walk would hand both structures a prefetch friendly stream of packed longs and
     * would hide whatever the two index structures do to the cache, which is the only thing this
     * benchmark is about. The order is drawn from the shared seed, so both arms see the same one.
     * </p>
     *
     * @param random the generator to draw the order from
     * @return the shuffled positions
     */
    private static int[] shuffledPositions(Random random) {
        int[] positions = new int[BenchmarkConstants.BLOCK_ENTRIES];
        for (int index = 0; index < positions.length; index++) {
            positions[index] = index;
        }
        for (int index = positions.length - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            int swap = positions[index];
            positions[index] = positions[other];
            positions[other] = swap;
        }
        return positions;
    }

    /**
     * The {@link ArrayIndex} class answers the two questions a palette answers internally, out of
     * plain {@code int} arrays.
     * <p>
     * It is not a {@code Palette} and cannot be one, because that interface is sealed to
     * {@code PaletteImpl}. It holds no packed data of its own either: the instance the benchmark
     * measures shares the {@code long[]} of the palette it is compared against, so that a read is the
     * same unpacking on both sides and only the lookup differs.
     * </p>
     * <p>
     * Three arrays make up the structure. {@code statesByIndex} is the forward direction and is the
     * exact counterpart of {@code paletteToValueList}. {@code sortedStates} and {@code sortedIndices}
     * are the reverse direction and together replace {@code valueToPaletteMap}: the first is searched,
     * the second says which palette index the found state belongs to. Keeping the palette in
     * insertion order is what makes the second array necessary, and it is also what makes an insertion
     * cost a shift rather than a remap of the packed array.
     * </p>
     */
    public static final class ArrayIndex {

        private final long[] packed;
        private final int bitsPerEntry;
        private final int[] statesByIndex;
        private final int[] sortedStates;
        private final int[] sortedIndices;
        private int size;

        /**
         * Creates a structure over an existing palette content and its packed data.
         *
         * @param states       the block states in palette order
         * @param packed       the packed palette indices, shared with the palette it is compared to
         * @param bitsPerEntry the width the packed data uses
         */
        ArrayIndex(int[] states, long[] packed, int bitsPerEntry) {
            this.packed = packed;
            this.bitsPerEntry = bitsPerEntry;
            this.statesByIndex = states.clone();
            this.sortedStates = states.clone();
            this.sortedIndices = new int[states.length];
            this.size = states.length;
            Arrays.sort(this.sortedStates);
            for (int index = 0; index < states.length; index++) {
                this.sortedIndices[Arrays.binarySearch(this.sortedStates, states[index])] = index;
            }
        }

        /**
         * Creates an empty structure with room for the given amount of states.
         * <p>
         * The capacity is handed in rather than grown, because the growth arm compares insertion
         * against insertion and a doubling policy of this structure would be a second variable that
         * the fastutil side does not have in the same form. The arrays it is measured against grow on
         * their own terms, which is the honest comparison of two designs and not of two capacity
         * policies.
         * </p>
         *
         * @param capacity the amount of states the structure will hold
         */
        ArrayIndex(int capacity) {
            this.packed = null;
            this.bitsPerEntry = 0;
            this.statesByIndex = new int[capacity];
            this.sortedStates = new int[capacity];
            this.sortedIndices = new int[capacity];
            this.size = 0;
        }

        /**
         * Reads the block state at the given position out of the shared packed data.
         *
         * @param x the position on the x axis
         * @param y the position on the y axis
         * @param z the position on the z axis
         * @return the block state
         */
        public int get(int x, int y, int z) {
            return this.statesByIndex[Palettes.read(DIMENSION, this.bitsPerEntry, this.packed, x, y, z)];
        }

        /**
         * Returns the palette index of a block state.
         *
         * @param state the block state to look up
         * @return the palette index, or {@code -1} if the state is not in the palette
         */
        public int indexOf(int state) {
            int found = Arrays.binarySearch(this.sortedStates, 0, this.size, state);
            return found < 0 ? -1 : this.sortedIndices[found];
        }

        /**
         * Adds a block state and returns the palette index it received.
         * <p>
         * A state that is already known is returned unchanged, which mirrors what
         * {@code valueToPaletteIndex} does. A new state is appended to the forward array, which keeps
         * every index that was handed out before it valid, and inserted into the sorted arrays at the
         * position the search found, which shifts everything behind it.
         * </p>
         *
         * @param state the block state to add
         * @return the palette index of the state
         */
        public int add(int state) {
            int found = Arrays.binarySearch(this.sortedStates, 0, this.size, state);
            if (found >= 0) {
                return this.sortedIndices[found];
            }
            int insertion = -(found + 1);
            int assigned = this.size;
            System.arraycopy(this.sortedStates, insertion, this.sortedStates, insertion + 1, this.size - insertion);
            System.arraycopy(this.sortedIndices, insertion, this.sortedIndices, insertion + 1, this.size - insertion);
            this.sortedStates[insertion] = state;
            this.sortedIndices[insertion] = assigned;
            this.statesByIndex[assigned] = state;
            this.size = assigned + 1;
            return assigned;
        }

        /**
         * Returns the amount of states the structure holds.
         *
         * @return the palette size
         */
        public int size() {
            return this.size;
        }
    }
}
