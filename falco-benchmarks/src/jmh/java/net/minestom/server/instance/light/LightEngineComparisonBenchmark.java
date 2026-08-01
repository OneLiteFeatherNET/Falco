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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The {@link LightEngineComparisonBenchmark} class measures the light engine of Falco against the one
 * Minestom ships with, on the same section and to the same result.
 * <p>
 * The class lives in the Minestom light package because the two methods that form the built-in path,
 * {@code BlockLight.buildInternalQueue} and {@code LightCompute.compute}, are package-private. This
 * is the only way to measure the original rather than a reimplementation of it.
 * </p>
 * <p>
 * Both sides are measured over their full path, from a block palette to a finished light array of
 * {@code 2048} bytes. Neither side gets to skip its preparation: the built-in path builds its seed
 * queue, and the Falco path builds its opacity table through the real block registry rather than a
 * stand-in. Measuring only the searches would flatter whichever side does more of its work up front.
 * </p>
 *
 * <h2>Why the brightness of the sources is a parameter</h2>
 * <p>
 * The propagation of Falco is a breadth-first search that assumes the queued positions are ordered by
 * their level, which only holds while every source starts at the same one. {@code LightPropagator}
 * says so where it grows its queue, and {@code LightEngineConcurrencyTest} says so where it pins its
 * fixtures to a single level. Mixing levels makes the search revisit positions, and the amount of
 * queued entries stops being bounded by the amount of positions.
 * </p>
 * <p>
 * That distinction decides whether a bucket queue is worth having. A bucket queue pops the brightest
 * position first and therefore touches every position once, at the price of a bucket per level. It
 * is the wrong trade when every source is equally bright, because the ordering it buys is already
 * there for free, and the right one as soon as the levels differ. A benchmark that only ever places
 * one kind of source would report such a change as a regression while real worlds hold the case in
 * which it wins, so the mixture is a parameter here rather than a constant.
 * </p>
 *
 * <h2>The emission levels the mixture uses</h2>
 * <p>
 * {@link EmissionMix#MIXED} places the blocks a real world actually lights its interiors with, at
 * the levels the block registry gives them: glowstone {@code 15}, lantern {@code 15}, torch
 * {@code 14}, redstone torch {@code 7} and magma block {@code 3}. Two properties of that set are
 * deliberate. It spans almost the whole range, from {@code 3} to {@code 15}, so a search that
 * expects one level meets the worst spread a builder can produce without exotic blocks; and it holds
 * two pairs that sit one level apart or on the same level, {@code 15}/{@code 15} and
 * {@code 15}/{@code 14}, because a mixture of neighbouring levels costs a search far less than one
 * of distant levels and a set of only distant levels would overstate the effect. Torch, lantern and
 * redstone torch occlude nothing while glowstone and magma block occlude every face, so the set also
 * carries both occlusion shapes an emitter can have.
 * </p>
 *
 * <h2>The two engines have to agree</h2>
 * <p>
 * Every trial verifies that both paths produce the same {@code 2048} bytes before a single
 * measurement is taken, and fails the trial if they do not. A faster number must never come from
 * computing something else, and a mixture of levels is exactly the input on which an order dependent
 * search would start to drift away from the reference.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * The full cross product is {@code 3 x 2 x 2} scenarios per method. One of them measures nothing:
 * with a single source the mixture degenerates, because the first block of the set is glowstone and
 * a lone source is placed at the same position with the same level as under
 * {@link EmissionMix#UNIFORM}. The recommended run therefore leaves it out:
 * </p>
 * <pre>{@code
 * java -jar build/libs/falco-*-jmh.jar "LightEngineComparisonBenchmark.(falco|minestom)" \
 *     -p emissionMix=UNIFORM -f 1 -wi 3 -i 5
 * java -jar build/libs/falco-*-jmh.jar "LightEngineComparisonBenchmark.(falco|minestom)" \
 *     -p emissionMix=MIXED -p lightSources=8,64 -f 1 -wi 3 -i 5
 * }</pre>
 * <p>
 * The first line reproduces the six documented scenarios unchanged, the second adds the four new
 * ones, which is ten scenarios per method instead of the twelve a plain run would take.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.1.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class LightEngineComparisonBenchmark {

    private static final int DIMENSION = 16;
    private static final int BLOCK_COUNT = DIMENSION * DIMENSION * DIMENSION;
    private static final long SEED = 20260731L;

    /**
     * The brightness the light emitting blocks of the measured section carry.
     * <p>
     * The two constants differ in nothing but the blocks that are placed. The positions of the
     * sources are drawn from the same seeded sequence either way, so a pair of scenarios that shares
     * its source count and its occlusion share the very same section apart from the levels.
     * </p>
     */
    public enum EmissionMix {

        /**
         * Every source is a glowstone block and emits level {@code 15}.
         * This is the form the benchmark had before the mixture became a parameter, down to the
         * drawn positions, so the scenarios measured under it stay comparable to the documented
         * numbers.
         */
        UNIFORM,

        /**
         * The sources cycle through glowstone, lantern, torch, redstone torch and magma block, which
         * emit {@code 15}, {@code 15}, {@code 14}, {@code 7} and {@code 3}.
         * Eight sources already cover every one of those levels.
         */
        MIXED
    }

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

    /**
     * Whether the sources of the measured section are equally bright or of mixed brightness.
     */
    @Param({"UNIFORM", "MIXED"})
    public EmissionMix emissionMix;

    private Palette palette;
    private int[] stateIds;
    private MinestomBlockLightSource source;
    private LightPropagator propagator;

    /**
     * Starts the server once so the block registry is available, builds the section both sides are
     * measured on and verifies that both sides agree on its light.
     *
     * @throws IllegalStateException if the two engines calculate different light for the section
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
        int[] emitters = emittersOf(this.emissionMix);

        for (int index = 0; index < BLOCK_COUNT; index++) {
            this.stateIds[index] = random.nextInt(100) < this.occlusionPercent ? stone : air;
        }
        // The block is picked by the round the source is placed in rather than by another draw, so
        // the drawn positions stay the same for both mixtures and the levels are the only difference
        // between them.
        for (int placed = 0; placed < this.lightSources; placed++) {
            this.stateIds[random.nextInt(BLOCK_COUNT)] = emitters[placed % emitters.length];
        }

        this.palette = Palette.blocks();
        this.palette.setAll((x, y, z) -> this.stateIds[(y << 8) | (z << 4) | x]);

        verifyBothEnginesAgree();
    }

    /**
     * Measures the built-in path: building the seed queue and running the search of Minestom.
     *
     * @return the calculated light array of the section
     */
    @Benchmark
    public byte[] minestom() {
        ShortArrayFIFOQueue queue = BlockLight.buildInternalQueue(this.palette);
        return LightCompute.compute(this.palette, queue);
    }

    /**
     * Measures the Falco path: building the opacity table through the real registry and running the
     * search, ending in the same light array layout.
     *
     * @return the calculated light array of the section
     */
    @Benchmark
    public byte[] falco() {
        int[] states = new int[BLOCK_COUNT];
        this.palette.getAll((x, y, z, value) -> states[(y << 8) | (z << 4) | x] = value);
        LightNibbles light = this.propagator.propagate(SectionOpacity.of(states, this.source));
        return light.toDenseArray();
    }

    /**
     * Returns the blocks the sources of the given mixture are placed as.
     * <p>
     * Glowstone comes first so that a section with a single source holds the same block under both
     * mixtures, which is what makes that scenario a duplicate rather than a second measurement.
     * </p>
     *
     * @param mix the mixture to build the blocks for
     * @return the state ids the sources cycle through
     */
    private static int[] emittersOf(EmissionMix mix) {
        return switch (mix) {
            case UNIFORM -> new int[]{Block.GLOWSTONE.stateId()};
            case MIXED -> new int[]{
                    Block.GLOWSTONE.stateId(),
                    Block.LANTERN.stateId(),
                    Block.TORCH.stateId(),
                    Block.REDSTONE_TORCH.stateId(),
                    Block.MAGMA_BLOCK.stateId()
            };
        };
    }

    /**
     * Verifies that both engines calculate the same light for the section that was just built.
     * <p>
     * The check runs once per trial, before any measurement, and stops the trial when the results
     * differ. Without it a change to either engine could win time by no longer computing the same
     * thing, and the numbers of the two sides would stop describing the same task.
     * </p>
     *
     * @throws IllegalStateException if the two engines calculate different light for the section
     */
    private void verifyBothEnginesAgree() {
        byte[] expected = minestom();
        byte[] actual = falco();

        if (Arrays.equals(expected, actual)) {
            return;
        }
        throw new IllegalStateException(describeDifference(expected, actual));
    }

    /**
     * Describes how far apart the light of the two engines is.
     *
     * @param expected the light the built-in engine calculated
     * @param actual   the light the Falco engine calculated
     * @return a message naming the amount of differing blocks, the largest difference and the first
     * block the two engines disagree on
     */
    private String describeDifference(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            return "The engines returned light arrays of different length: Minestom " + expected.length
                    + " bytes against Falco " + actual.length + " bytes";
        }

        int differing = 0;
        int largest = 0;
        int first = -1;

        for (int index = 0; index < BLOCK_COUNT; index++) {
            int expectedLevel = levelAt(expected, index);
            int actualLevel = levelAt(actual, index);

            if (expectedLevel == actualLevel) {
                continue;
            }
            differing++;
            largest = Math.max(largest, Math.abs(expectedLevel - actualLevel));

            if (first < 0) {
                first = index;
            }
        }
        return "The engines disagree on " + differing + " of " + BLOCK_COUNT + " blocks, largest difference "
                + largest + " levels, first at x=" + (first & 15) + " y=" + ((first >> 8) & 15)
                + " z=" + ((first >> 4) & 15) + " where Minestom holds " + levelAt(expected, first)
                + " and Falco holds " + levelAt(actual, first) + " (lightSources=" + this.lightSources
                + ", occlusionPercent=" + this.occlusionPercent + ", emissionMix=" + this.emissionMix + ")";
    }

    /**
     * Reads the light level of a block out of a light array.
     * Both engines store two levels per byte in the same order, so one reader serves both.
     *
     * @param light the light array to read from
     * @param index the index of the block inside the section
     * @return the level of the block
     */
    private static int levelAt(byte[] light, int index) {
        return (light[index >> 1] >> ((index & 1) << 2)) & 0x0F;
    }
}
