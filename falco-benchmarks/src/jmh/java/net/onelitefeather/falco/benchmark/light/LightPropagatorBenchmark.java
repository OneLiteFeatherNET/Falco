package net.onelitefeather.falco.benchmark.light;

import net.onelitefeather.falco.benchmark.support.FakeBlockLightSource;
import net.onelitefeather.falco.benchmark.support.SectionStates;
import net.onelitefeather.falco.light.LightNibbles;
import net.onelitefeather.falco.light.LightPropagator;
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

import java.util.concurrent.TimeUnit;

/**
 * The {@link LightPropagatorBenchmark} class measures the block light propagation of a single
 * section.
 * <p>
 * The propagation is a breadth-first search whose cost grows with the amount of positions it
 * queues, so the amount of light sources of the section is the parameter that matters. A section
 * without any source is answered without a search at all, which is the case for the overwhelming
 * majority of the sections of a world and which is exactly why the shortcut exists.
 * </p>
 * <p>
 * The share of solid blocks is the second parameter. Solid blocks stop the search early, so a
 * section full of them performs less work than an open one with the same amount of sources. A
 * benchmark which only measured an empty section would therefore report the worst case and call it
 * the normal one.
 * </p>
 * <p>
 * The propagator instance is created once and reused across every invocation. That is how the class
 * is meant to be used, because it keeps its working buffers between runs, and creating a fresh one
 * per invocation would measure two array allocations instead of the search.
 * </p>
 * <p>
 * The opacity table is built in the setup. Building it inside the measured method would make this
 * benchmark a duplicate of {@link SectionOpacityBenchmark}.
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
public class LightPropagatorBenchmark {

    /**
     * The amount of light emitting blocks the section holds.
     */
    @Param({"0", "1", "8", "64"})
    public int lightSources;

    /**
     * The share of solid blocks of the section in percent.
     */
    @Param({"0", "25"})
    public int occlusionPercent;

    private LightPropagator propagator;
    private SectionOpacity opacity;

    /**
     * Creates a new benchmark instance.
     */
    public LightPropagatorBenchmark() {
    }

    /**
     * Builds the opacity table of the section and the propagator which walks it.
     */
    @Setup(Level.Trial)
    public void setUp() {
        this.propagator = new LightPropagator();
        this.opacity = SectionOpacity.of(
                SectionStates.lit(this.lightSources, this.occlusionPercent), new FakeBlockLightSource()
        );
    }

    /**
     * Spreads the light of every emitting block through the section.
     *
     * @return the calculated light of the section
     */
    @Benchmark
    public LightNibbles propagate() {
        return this.propagator.propagate(this.opacity);
    }
}
