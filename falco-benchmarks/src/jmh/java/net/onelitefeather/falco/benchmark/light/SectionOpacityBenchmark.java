package net.onelitefeather.falco.benchmark.light;

import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.FakeBlockLightSource;
import net.onelitefeather.falco.benchmark.support.SectionStates;
import net.onelitefeather.falco.light.BlockLightSource;
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
 * The {@link SectionOpacityBenchmark} class measures the construction of the opacity table of a
 * section.
 * <p>
 * The table exists because a breadth-first search reaches the same block from up to six directions
 * and would otherwise ask the registry for its properties every single time. The table asks once
 * per distinct block state and answers from two arrays afterwards. This benchmark is what shows
 * what that caching is worth.
 * </p>
 * <p>
 * Two parameters describe the two things the cost depends on. The amount of distinct states decides
 * how often the cache misses, and the resolve cost describes how expensive a single miss is. The
 * cost is a parameter because the real source is a registry lookup and not an arithmetic
 * expression. A source which answers instantly would make the hash map behind the cache look like
 * pure overhead, which is the opposite of what happens on a running server.
 * </p>
 * <ul>
 *   <li>A resolve cost of zero measures the table construction itself, so the cost of the hash map
 *       and of the two array writes per block.</li>
 *   <li>A resolve cost above zero measures what the cache saves. Seven resolutions happen per
 *       distinct state, one per face plus the emission, instead of seven per block.</li>
 * </ul>
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
public class SectionOpacityBenchmark {

    /**
     * The amount of distinct block states the section holds.
     */
    @Param({"1", "8", "64", "200"})
    public int distinctStates;

    /**
     * The amount of work tokens a single resolution of a block state burns.
     * Zero measures the table itself, a higher value measures what the caching saves.
     */
    @Param({"0", "50"})
    public int resolveCost;

    private int[] stateIds;
    private BlockLightSource source;

    /**
     * Creates a new benchmark instance.
     */
    public SectionOpacityBenchmark() {
    }

    /**
     * Builds the states of the section and the source which describes them.
     * <p>
     * The states start above {@link FakeBlockLightSource#FILLER_BASE} so every one of them behaves
     * like air. The benchmark measures how often a state is resolved, not what the answer is, and a
     * mixture of solid and transparent blocks would only add noise to that.
     * </p>
     */
    @Setup(Level.Trial)
    public void setUp() {
        this.stateIds = SectionStates.distinct(
                BenchmarkConstants.BLOCK_ENTRIES, this.distinctStates, FakeBlockLightSource.FILLER_BASE
        );
        this.source = new FakeBlockLightSource(this.resolveCost);
    }

    /**
     * Builds the opacity table of a whole section.
     *
     * @return the created table
     */
    @Benchmark
    public SectionOpacity of() {
        return SectionOpacity.of(this.stateIds, this.source);
    }
}
