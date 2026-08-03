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
 * which is where the map that removes the boxing is more expensive. A change that reports only the
 * side it improves is not a measurement.
 * </p>
 * <p>
 * Both maps are driven with the same key sequence and the same content. Neither arm touches a real
 * chunk — the value is a plain {@code Object} standing in for one — because the question is about the
 * map and a chunk would put a two hundred kilobyte object into a cache line argument.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
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
