package net.onelitefeather.falco.benchmark.light;

import net.onelitefeather.falco.benchmark.support.FakeBlockLightSource;
import net.onelitefeather.falco.benchmark.support.SectionStates;
import net.onelitefeather.falco.light.ChunkLightPropagator;
import net.onelitefeather.falco.light.LightNibbles;
import net.onelitefeather.falco.light.SectionOpacity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The {@link ChunkLightPropagatorBenchmark} class measures the light propagation over a whole chunk
 * column.
 * <p>
 * A propagation which stops at a section border produces a seam every sixteen blocks, so the chunk
 * propagator treats every section of a chunk as one column and lets the search cross their borders.
 * That makes the amount of sections the parameter which decides the size of the search space: four
 * sections are a flat map, sixteen a shallow world and twenty four the full height of a modern
 * overworld.
 * </p>
 * <p>
 * Two searches are measured because the engine performs two of them per chunk. Block light starts
 * at the emitting blocks and is bounded by their amount, while sky light starts at every block that
 * sees the open sky, which in an open column is nearly the whole chunk. The two therefore behave
 * very differently and reporting only one of them would describe half of the work.
 * </p>
 * <p>
 * The propagator instance is reused across invocations. It sizes its buffers for the largest column
 * it has seen and keeps them, so a fresh instance per invocation would measure the buffer
 * allocation and not the search.
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
public class ChunkLightPropagatorBenchmark {

    /**
     * The amount of sections the chunk holds.
     */
    @Param({"4", "16", "24"})
    public int sectionCount;

    /**
     * The amount of light emitting blocks a single section of the chunk holds.
     */
    @Param({"1", "8"})
    public int lightSourcesPerSection;

    private ChunkLightPropagator propagator;
    private List<SectionOpacity> sections;

    /**
     * Creates a new benchmark instance.
     */
    public ChunkLightPropagatorBenchmark() {
    }

    /**
     * Builds the opacity table of every section of the chunk and warms the buffers of the
     * propagator so the first measured invocation does not pay for their allocation.
     */
    @Setup(Level.Trial)
    public void setUp() {
        FakeBlockLightSource source = new FakeBlockLightSource();
        this.propagator = new ChunkLightPropagator();
        this.sections = new ArrayList<>(this.sectionCount);

        for (int section = 0; section < this.sectionCount; section++) {
            // A quarter of the blocks of the lower half are solid, which gives the search something
            // to stop at, while the upper half stays open so the sky light really reaches downwards.
            int occlusion = section < this.sectionCount / 2 ? 25 : 0;
            this.sections.add(SectionOpacity.of(SectionStates.lit(this.lightSourcesPerSection, occlusion), source));
        }
        this.propagator.propagate(this.sections);
        this.propagator.propagateSky(this.sections);
    }

    /**
     * Spreads the light of every emitting block of the chunk through the whole column.
     *
     * @return the calculated light of every section
     */
    @Benchmark
    public List<LightNibbles> propagate() {
        return this.propagator.propagate(this.sections);
    }

    /**
     * Lets the sky light fall into the chunk and spread from where it is stopped.
     *
     * @return the calculated sky light of every section
     */
    @Benchmark
    public List<LightNibbles> propagateSky() {
        return this.propagator.propagateSky(this.sections);
    }
}
