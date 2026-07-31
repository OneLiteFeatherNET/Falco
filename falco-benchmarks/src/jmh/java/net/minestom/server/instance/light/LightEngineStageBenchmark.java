package net.minestom.server.instance.light;

import it.unimi.dsi.fastutil.shorts.ShortArrayFIFOQueue;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.onelitefeather.falco.light.LightNibbles;
import net.onelitefeather.falco.light.LightPropagator;
import net.onelitefeather.falco.light.MinestomBlockLightSource;
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

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The {@link LightEngineStageBenchmark} class splits both light engines into the stages they consist
 * of and measures every stage on its own.
 * <p>
 * {@link LightEngineComparisonBenchmark} reports one number per engine, which says which one is
 * faster but never says why. This class exists to answer the second question: it measures the same
 * scenarios on the same section, but one stage at a time, so a difference between the two engines
 * can be attributed to a stage instead of being guessed at.
 * </p>
 * <p>
 * The stages of the Falco path are reading the palette into an array of state ids, building the
 * opacity table from those ids, running the breadth-first search and packing the result into the
 * dense array the server expects. Every stage receives the finished output of the previous one from
 * the setup, so a measured method really only performs the stage it is named after.
 * </p>
 * <p>
 * The built-in path is split into building the seed queue and everything else. Its search consumes
 * the queue it is handed, so the search cannot be measured on a prepared queue without rebuilding
 * that queue per invocation, which would put the rebuild back into the measurement. The search is
 * therefore the difference between {@link #minestomFull()} and {@link #minestomQueue()}.
 * </p>
 * <p>
 * The class lives in the Minestom light package for the same reason
 * {@link LightEngineComparisonBenchmark} does: {@code BlockLight.buildInternalQueue} and
 * {@code LightCompute.compute} are package-private.
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
@Measurement(iterations = 5, time = 1)
public class LightEngineStageBenchmark {

    private static final int DIMENSION = 16;
    private static final int BLOCK_COUNT = DIMENSION * DIMENSION * DIMENSION;
    private static final long SEED = 20260731L;

    /**
     * The amount of light emitting blocks the measured section holds.
     */
    @Param({"1", "8", "64"})
    public int lightSources;

    /**
     * The share of solid blocks in the measured section, in percent.
     */
    @Param({"0", "30"})
    public int occlusionPercent;

    private Palette palette;
    private int[] stateIds;
    private MinestomBlockLightSource source;
    private LightPropagator propagator;
    private SectionOpacity opacity;
    private LightNibbles light;

    /**
     * Creates a new benchmark instance.
     */
    public LightEngineStageBenchmark() {
    }

    /**
     * Starts the server once so the block registry is available, builds the section both engines are
     * measured on and prepares the input of every single stage.
     */
    @Setup(Level.Trial)
    public void setUp() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }

        this.source = new MinestomBlockLightSource();
        this.propagator = new LightPropagator();
        this.stateIds = new int[BLOCK_COUNT];

        Random random = new Random(SEED);
        int air = Block.AIR.stateId();
        int stone = Block.STONE.stateId();
        int glowstone = Block.GLOWSTONE.stateId();

        for (int index = 0; index < BLOCK_COUNT; index++) {
            this.stateIds[index] = random.nextInt(100) < this.occlusionPercent ? stone : air;
        }
        for (int placed = 0; placed < this.lightSources; placed++) {
            this.stateIds[random.nextInt(BLOCK_COUNT)] = glowstone;
        }

        this.palette = Palette.blocks();
        this.palette.setAll((x, y, z) -> this.stateIds[(y << 8) | (z << 4) | x]);

        this.opacity = SectionOpacity.of(this.stateIds, this.source);
        this.light = new LightPropagator().propagate(this.opacity);
    }

    /**
     * Measures reading the block palette into an array of state ids.
     *
     * @return the read state ids
     */
    @Benchmark
    public int[] falcoReadStates() {
        int[] states = new int[BLOCK_COUNT];
        this.palette.getAll((x, y, z, value) -> states[(y << 8) | (z << 4) | x] = value);
        return states;
    }

    /**
     * Measures building the opacity table from an already read array of state ids.
     *
     * @return the created table
     */
    @Benchmark
    public SectionOpacity falcoOpacity() {
        return SectionOpacity.of(this.stateIds, this.source);
    }

    /**
     * Measures the breadth-first search on an already built opacity table.
     *
     * @return the calculated light of the section
     */
    @Benchmark
    public LightNibbles falcoPropagate() {
        return this.propagator.propagate(this.opacity);
    }

    /**
     * Measures packing an already calculated result into the dense array the server expects.
     *
     * @return the packed light array
     */
    @Benchmark
    public byte[] falcoCollect() {
        return this.light.toDenseArray();
    }

    /**
     * Measures the whole Falco path so the sum of the stages can be checked against it.
     *
     * @return the calculated light array of the section
     */
    @Benchmark
    public byte[] falcoFull() {
        int[] states = new int[BLOCK_COUNT];
        this.palette.getAll((x, y, z, value) -> states[(y << 8) | (z << 4) | x] = value);
        LightNibbles result = this.propagator.propagate(SectionOpacity.of(states, this.source));
        return result.toDenseArray();
    }

    /**
     * Measures building the seed queue of the built-in path.
     *
     * @return the built seed queue
     */
    @Benchmark
    public ShortArrayFIFOQueue minestomQueue() {
        return BlockLight.buildInternalQueue(this.palette);
    }

    /**
     * Measures the whole built-in path. Its search is the difference to {@link #minestomQueue()}.
     *
     * @return the calculated light array of the section
     */
    @Benchmark
    public byte[] minestomFull() {
        ShortArrayFIFOQueue queue = BlockLight.buildInternalQueue(this.palette);
        return LightCompute.compute(this.palette, queue);
    }
}
