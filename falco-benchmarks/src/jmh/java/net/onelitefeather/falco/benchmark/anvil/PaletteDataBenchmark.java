package net.onelitefeather.falco.benchmark.anvil;

import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.SectionStates;
import net.onelitefeather.falco.anvil.PaletteData;
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

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * The {@link PaletteDataBenchmark} class measures the conversion of a section between its raw state
 * ids and its palette representation.
 * <p>
 * The encoding is the dominant part of a chunk save. It walks the whole section once to collect the
 * distinct states into a palette and once more to pack the indices which reference them. The
 * collection runs through a hash map, which is why the amount of distinct states of a section is
 * the parameter of this benchmark and not the section size.
 * </p>
 * <p>
 * The chosen amounts describe the sections a real world holds. One distinct state is a section of
 * pure air or pure stone, which is the majority of every world and which the encoder answers
 * without packing anything at all. Eight is an ordinary underground section, sixty four a surface
 * section with vegetation, and two hundred a heavily built section which already needs eight bits
 * per entry.
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
public class PaletteDataBenchmark {

    /**
     * The amount of distinct block states a single section holds.
     */
    @Param({"1", "8", "64", "200"})
    public int distinctStates;

    private int[] values;
    private PaletteData encoded;

    /**
     * Builds the raw states of the section and the already encoded representation of it.
     */
    @Setup(Level.Trial)
    public void setUp() {
        this.values = SectionStates.distinct(BenchmarkConstants.BLOCK_ENTRIES, this.distinctStates, 1);
        this.encoded = PaletteData.encode(this.values, BenchmarkConstants.BLOCK_PALETTE_MIN_BITS);
    }

    /**
     * Creates a new benchmark instance.
     */
    public PaletteDataBenchmark() {
    }

    /**
     * Collects the palette of a section and packs the indices which reference it.
     * This is the work a chunk save performs once per section.
     *
     * @return the palette representation of the section
     */
    @Benchmark
    public PaletteData encode() {
        return PaletteData.encode(this.values, BenchmarkConstants.BLOCK_PALETTE_MIN_BITS);
    }

    /**
     * Resolves every entry of a section through its palette.
     * This is the work a chunk load performs once per section.
     *
     * @return the state id of every block of the section
     * @throws IOException if a packed index does not address a palette entry
     */
    @Benchmark
    public int[] unpack() throws IOException {
        return this.encoded.unpack();
    }

    /**
     * Encodes and resolves a section again, which is what a load followed by a save performs.
     *
     * @return the state id of every block of the section
     * @throws IOException if a packed index does not address a palette entry
     */
    @Benchmark
    public int[] roundTrip() throws IOException {
        return PaletteData.encode(this.values, BenchmarkConstants.BLOCK_PALETTE_MIN_BITS).unpack();
    }
}
