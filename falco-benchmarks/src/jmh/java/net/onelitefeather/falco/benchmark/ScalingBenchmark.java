package net.onelitefeather.falco.benchmark;

import net.onelitefeather.falco.benchmark.support.FakeBlockLightSource;
import net.onelitefeather.falco.benchmark.support.SectionStates;
import net.onelitefeather.falco.anvil.BitPacker;
import net.onelitefeather.falco.anvil.PaletteData;
import net.onelitefeather.falco.light.ChunkLightPropagator;
import net.onelitefeather.falco.light.LightNibbles;
import net.onelitefeather.falco.light.SectionOpacity;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The {@link ScalingBenchmark} class measures how the cost of the hot operations grows with the
 * size of their input.
 * <p>
 * The other benchmarks answer "how long does this take". This one answers "what happens when it
 * gets bigger", which is the question that decides whether a workload is viable at ten times its
 * current size. Every axis therefore uses many closely spaced sizes rather than a few
 * representative ones, so the shape of the curve becomes visible and can be extrapolated.
 * </p>
 * <p>
 * Reading the result: a straight line through the points means the operation is linear in that
 * axis and a forecast is a simple multiplication. A curve that bends upwards means it is not, and
 * the workload has a size beyond which it stops being affordable.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class ScalingBenchmark {

    /**
     * The amount of sections a chunk column holds.
     * <p>
     * Twenty four is the height of a modern overworld and the value a normal server runs at. The
     * points beyond it describe worlds with a raised height limit, which servers do configure: a
     * value of 128 is a column of 2048 blocks, and 256 is 4096. Measuring them instead of
     * extrapolating from the vanilla range is the point of this axis, because a forecast that is
     * only ever validated inside the common range says nothing about the exotic one.
     * </p>
     */
    @Param({"1", "2", "4", "8", "12", "16", "20", "24", "32", "48", "64", "96", "128", "192", "256"})
    public int sectionCount;

    /**
     * The amount of distinct block states a section holds. A palette grows with the variety of a
     * build, and the cost of resolving it is the claim this axis verifies.
     */
    @Param({"1", "16", "64", "256", "1024"})
    public int distinctStates;

    private List<SectionOpacity> litColumn;
    private List<SectionOpacity> openColumn;
    private ChunkLightPropagator propagator;
    private int[] paletteValues;

    /**
     * Builds the inputs of every axis once, so no benchmark measures its own setup.
     */
    @Setup
    public void setUp() {
        FakeBlockLightSource source = new FakeBlockLightSource();
        this.propagator = new ChunkLightPropagator();
        this.litColumn = new ArrayList<>(this.sectionCount);
        this.openColumn = new ArrayList<>(this.sectionCount);

        for (int section = 0; section < this.sectionCount; section++) {
            this.litColumn.add(SectionOpacity.of(SectionStates.lit(4, 20), source));
            this.openColumn.add(SectionOpacity.of(SectionStates.uniform(LightNibbles.BLOCK_COUNT, FakeBlockLightSource.AIR), source));
        }

        this.paletteValues = new int[LightNibbles.BLOCK_COUNT];

        for (int index = 0; index < this.paletteValues.length; index++) {
            this.paletteValues[index] = index % this.distinctStates;
        }
    }

    /**
     * Measures how the block light search grows with the height of the column.
     *
     * @return the calculated light of every section
     */
    @Benchmark
    public List<LightNibbles> blockLightBySectionCount() {
        return this.propagator.propagate(this.litColumn);
    }

    /**
     * Measures how the sky light search grows with the height of the column.
     * An open column seeds nearly every block, so this is the upper bound of a propagation.
     *
     * @return the calculated sky light of every section
     */
    @Benchmark
    public List<LightNibbles> skyLightBySectionCount() {
        return this.propagator.propagateSky(this.openColumn);
    }

    /**
     * Measures how encoding a section grows with the amount of distinct block states it holds.
     *
     * @return the encoded palette of the section
     */
    @Benchmark
    public PaletteData paletteByDistinctStates() {
        return PaletteData.encode(this.paletteValues, 4);
    }

    /**
     * Measures how packing grows with the amount of bits a palette entry needs, which is itself a
     * function of the amount of distinct states.
     *
     * @param blackhole the sink which keeps the result from being optimised away
     */
    @Benchmark
    public void packingByDistinctStates(Blackhole blackhole) {
        int bits = BitPacker.bitsPerEntry(this.distinctStates, 4);
        blackhole.consume(BitPacker.pack(this.paletteValues, bits));
    }
}
