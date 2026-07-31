package net.onelitefeather.falco.benchmark.light;

import net.onelitefeather.falco.light.LightNibbles;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * The {@link LightNibblesBenchmark} class measures the nibble storage of a light section.
 * <p>
 * The storage has two shapes. A section in which every block carries the same level keeps no array
 * at all and answers every read from a single field, and a section whose levels differ keeps a
 * {@value LightNibbles#ARRAY_LENGTH} byte array and has to shift the requested nibble out of it.
 * The first shape is the common one, because most sections of a world are either completely dark or
 * completely lit by the sky, and this benchmark is what shows what that shortcut is worth.
 * </p>
 * <p>
 * Every measured method sweeps the whole section instead of touching a single block. A single
 * nibble read is a handful of instructions, which is below what a harness can separate from its own
 * overhead. The reported time is therefore the time of a full sweep over 4096 blocks and the cost
 * of a single access follows from dividing it.
 * </p>
 * <p>
 * That division only holds for the sweeps over an allocated section. The two sweeps over a uniform
 * section are collapsed by the compiler because their loop body does not depend on the coordinates,
 * and their numbers are lower bounds rather than per access costs. The details are on
 * {@link #sweep(LightNibbles, Blackhole)} and on {@link #setUniformUnchanged()}.
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
public class LightNibblesBenchmark {

    private LightNibbles uniform;
    private LightNibbles allocated;
    private byte[] stored;

    /**
     * Creates a new benchmark instance.
     */
    public LightNibblesBenchmark() {
    }

    /**
     * Builds one section of each shape.
     * The allocated one receives a level pattern which no compaction can collapse again.
     */
    @Setup(Level.Trial)
    public void setUp() {
        this.uniform = LightNibbles.uniform(LightNibbles.MAX_LEVEL);
        this.allocated = LightNibbles.uniform(0);

        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    this.allocated.set(x, y, z, (x + y + z) & LightNibbles.MAX_LEVEL);
                }
            }
        }
        this.stored = this.allocated.toDenseArray();
    }

    /**
     * Reads every block of a section which carries a single level everywhere.
     *
     * @param hole the sink which receives every level that was read
     */
    @Benchmark
    public void getUniform(Blackhole hole) {
        sweep(this.uniform, hole);
    }

    /**
     * Reads every block of a section which keeps a full nibble array.
     *
     * @param hole the sink which receives every level that was read
     */
    @Benchmark
    public void getAllocated(Blackhole hole) {
        sweep(this.allocated, hole);
    }

    /**
     * Writes the level a uniform section already carries to every one of its blocks.
     * The section stays uniform and no array is ever allocated, which is the shortcut a propagation
     * of a dark section relies on.
     * <p>
     * Expect a number close to zero. Every one of these writes returns without touching anything,
     * the compiler can prove that, and it removes the loop. The result is therefore a statement
     * about how cheap the shortcut can get and not a per call cost. It is kept because a change
     * which accidentally makes this path allocate would show up here immediately.
     * </p>
     *
     * @return the section which was written to
     */
    @Benchmark
    public LightNibbles setUniformUnchanged() {
        LightNibbles light = LightNibbles.uniform(0);

        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    light.set(x, y, z, 0);
                }
            }
        }
        return light;
    }

    /**
     * Writes a differing level to every block of a fresh uniform section.
     * The first write allocates the array, which makes this the exact path a propagation takes when
     * it transfers its result into a section.
     *
     * @return the section which was written to
     */
    @Benchmark
    public LightNibbles setAllocating() {
        LightNibbles light = LightNibbles.uniform(0);

        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    light.set(x, y, z, (x + y + z) & LightNibbles.MAX_LEVEL);
                }
            }
        }
        return light;
    }

    /**
     * Reads a stored section back from its byte form, which is what a chunk load performs.
     *
     * @return the section which was read
     */
    @Benchmark
    public LightNibbles ofArray() {
        return LightNibbles.of(this.stored);
    }

    /**
     * Reads the level of every block of the given section into the given sink.
     * <p>
     * Every single level is handed to the sink instead of being summed up, so the reads of a
     * section which keeps an array cannot be folded away.
     * </p>
     * <p>
     * A uniform section is a different matter and the sink does not save it either. Such a section
     * answers every read from one field regardless of the coordinates, which makes the whole loop
     * body loop invariant, and the compiler hoists it out and drops the empty loop. Both blackhole
     * modes behave the same way here, which was verified with
     * {@code -Djmh.blackhole.autoDetect=false}. The number {@link #getUniform(Blackhole)} reports is
     * therefore a lower bound and not the cost of 4096 reads: it says that reading a uniform
     * section can collapse to a single field read, which is the property the shortcut exists for.
     * Only {@link #getAllocated(Blackhole)} divided by 4096 is a per access cost.
     * </p>
     *
     * @param light the section to read
     * @param hole  the sink which receives every level that was read
     */
    private static void sweep(LightNibbles light, Blackhole hole) {
        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    hole.consume(light.get(x, y, z));
                }
            }
        }
    }
}
