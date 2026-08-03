package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.coordinate.CoordConversion;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import space.vectrix.flare.fastutil.Long2ObjectSyncMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Prices the boxed chunk index against the unboxed one, on the lookup and on the write.
 * <p>
 * US-3.05 asks for the boxing to go and the design refuses to sell that as a speed change, because
 * the cost of the boxing is not established. This benchmark is what would establish it, and it
 * measures both directions on purpose: the lookup, which is what the change is for, and the write,
 * which is where {@code Long2ObjectSyncMap} is <em>expected</em> to be the more expensive of the two,
 * since a write after a run of misses rebuilds its dirty map. A change that reports only the side it
 * improves is not a measurement.
 * </p>
 * <p>
 * That expectation is stated here rather than asserted, because the first run did not meet it: the
 * primitive arm was the cheaper one on the write side too, in time and in allocation, at all three
 * sizes. Read that as "this benchmark did not provoke a promotion", not as "the promotion is free" —
 * every operation of the write arms puts and removes the same single key, which is the cheapest shape
 * a dirty map can be asked for. The figures are in the {@code Stage 3 result} section of
 * {@code docs/superpowers/plans/2026-08-02-falco-instance-facade.md}, together with the note that
 * their timings come from a scouting configuration on a loaded machine and may not be quoted.
 * </p>
 * <p>
 * Both maps are driven with the same key sequence and the same content. Neither arm touches a real
 * chunk — the value is a plain {@code Object} standing in for one — because the question is about the
 * map and a chunk would put a two hundred kilobyte object into a cache line argument.
 * </p>
 *
 * <h2>Why the boxed lookup arm allocates far more than a box</h2>
 * <p>
 * The keys are a square grid of chunk positions and {@code Long#hashCode} of a chunk index is
 * {@code chunkX ^ chunkZ}, so from a side of about ten upwards the whole grid falls into a handful of
 * buckets and the bins of the boxed map treeify. A treeified bin reaches
 * {@code HashMap#comparableClassFor}, which calls {@code Class#getGenericInterfaces} on every lookup
 * and allocates reflectively. Measured outside JMH with a per-thread counter: 24 B of the boxed arm
 * are the box and about 46 B are this, the second figure appearing with a pre-boxed key as well and
 * disappearing when the keys are spread or the grid stays under the treeify threshold.
 * </p>
 * <p>
 * The effect belongs to real chunk coordinates and not to this key generator, which is why it is kept
 * rather than designed away — but it means the allocation column of the boxed arm must not be read as
 * "this is what boxing costs".
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.4.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ChunkLookupBenchmark {

    /**
     * How many chunk positions the maps hold, which is roughly a view distance of eight, sixteen and
     * a streaming world.
     */
    @Param({"289", "1089", "4096"})
    public int positions;

    /**
     * The value every key maps to.
     */
    private final Object value = new Object();

    /**
     * The boxed map, the shape this stage replaced.
     */
    private Map<Long, Object> boxed;

    /**
     * The primitive map, the shape this stage installed.
     */
    private Long2ObjectSyncMap<Object> primitive;

    /**
     * The keys, in the order the benchmark walks them.
     */
    private long[] keys;

    /**
     * Creates the state object, which JMH fills through {@link #setUp()}.
     */
    public ChunkLookupBenchmark() {
    }

    /**
     * Fills both maps with the same content.
     */
    @Setup
    public void setUp() {
        this.boxed = new ConcurrentHashMap<>();
        this.primitive = Long2ObjectSyncMap.hashmap();
        this.keys = new long[this.positions];

        final int side = (int) Math.ceil(Math.sqrt(this.positions));
        for (int index = 0; index < this.positions; index++) {
            final long key = CoordConversion.chunkIndex(index % side, index / side);
            this.keys[index] = key;
            this.boxed.put(key, this.value);
            this.primitive.put(key, this.value);
        }
    }

    /**
     * Walks every position through the boxed map.
     *
     * @param blackhole where the results go
     */
    @Benchmark
    public void boxedLookup(Blackhole blackhole) {
        for (long key : this.keys) blackhole.consume(this.boxed.get(key));
    }

    /**
     * Walks every position through the primitive map.
     *
     * @param blackhole where the results go
     */
    @Benchmark
    public void primitiveLookup(Blackhole blackhole) {
        for (long key : this.keys) blackhole.consume(this.primitive.get(key));
    }

    /**
     * Puts and removes one position in the boxed map, which is what a load and an unload do.
     *
     * @param blackhole where the results go
     */
    @Benchmark
    public void boxedLoadAndUnload(Blackhole blackhole) {
        final long key = CoordConversion.chunkIndex(9999, 9999);
        blackhole.consume(this.boxed.put(key, this.value));
        blackhole.consume(this.boxed.remove(key));
    }

    /**
     * Puts and removes one position in the primitive map.
     *
     * @param blackhole where the results go
     */
    @Benchmark
    public void primitiveLoadAndUnload(Blackhole blackhole) {
        final long key = CoordConversion.chunkIndex(9999, 9999);
        blackhole.consume(this.primitive.put(key, this.value));
        blackhole.consume(this.primitive.remove(key));
    }
}
