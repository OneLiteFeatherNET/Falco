package net.onelitefeather.falco.benchmark.anvil;

import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.anvil.BitPacker;
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

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The {@link BitPackerBenchmark} class measures the packing and the unpacking of the palette
 * indices of a single section.
 * <p>
 * Both methods are pure loops over the 4096 entries of a section and they run once per section on
 * every chunk load and on every chunk save. A chunk of a
 * full height overworld therefore runs them twenty four times, which makes them the hottest loop of
 * the whole codec.
 * </p>
 * <p>
 * The parameter is the amount of bits a single entry occupies, because that value alone decides how
 * many entries share a long and therefore how many iterations the loop performs per long. Four bits
 * is the smallest amount the block palette allows, five and eight are what a normal and a busy
 * section produce, and fifteen is the direct palette which stores a state id without a palette at
 * all.
 * </p>
 * <p>
 * The time is reported per whole section, not per entry. Dividing by the entry count gives the cost
 * of a single entry.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BitPackerBenchmark {

    /**
     * The amount of bits a single palette index occupies.
     */
    @Param({"4", "5", "8", "15"})
    public int bitsPerEntry;

    private int[] values;
    private long[] packed;

    /**
     * Creates a new benchmark instance.
     */
    public BitPackerBenchmark() {
    }

    /**
     * Builds the packed and the unpacked representation of a section.
     * Both benchmarks read a prepared input so neither of them measures the generation of it.
     */
    @Setup(Level.Trial)
    public void setUp() {
        Random random = new Random(BenchmarkConstants.SEED);
        int bound = this.bitsPerEntry >= Integer.SIZE - 1 ? Integer.MAX_VALUE : 1 << this.bitsPerEntry;
        this.values = new int[BenchmarkConstants.BLOCK_ENTRIES];

        for (int index = 0; index < this.values.length; index++) {
            this.values[index] = random.nextInt(bound);
        }
        this.packed = BitPacker.pack(this.values, this.bitsPerEntry);
    }

    /**
     * Packs a whole section of palette indices into longs.
     * The returned array is handed back to the harness so the loop cannot be removed.
     *
     * @return the packed representation of the section
     */
    @Benchmark
    public long[] pack() {
        return BitPacker.pack(this.values, this.bitsPerEntry);
    }

    /**
     * Unpacks a whole section of palette indices out of longs.
     * The returned array is handed back to the harness so the loop cannot be removed.
     *
     * @return the palette indices of the section
     */
    @Benchmark
    public int[] unpack() {
        return BitPacker.unpack(this.packed, BenchmarkConstants.BLOCK_ENTRIES, this.bitsPerEntry);
    }

    /**
     * Packs and unpacks a section, which is what a load followed by a save performs.
     *
     * @return the palette indices of the section
     */
    @Benchmark
    public int[] roundTrip() {
        return BitPacker.unpack(
                BitPacker.pack(this.values, this.bitsPerEntry), BenchmarkConstants.BLOCK_ENTRIES, this.bitsPerEntry
        );
    }
}
