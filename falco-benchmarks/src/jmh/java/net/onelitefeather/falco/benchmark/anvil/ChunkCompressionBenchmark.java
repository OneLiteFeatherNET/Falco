package net.onelitefeather.falco.benchmark.anvil;

import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.ChunkColumn;
import net.onelitefeather.falco.benchmark.support.ChunkPayloads;
import net.onelitefeather.falco.benchmark.support.FakePaletteEntryResolver;
import net.onelitefeather.falco.anvil.ChunkCompression;
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
 * The {@link ChunkCompressionBenchmark} class measures the compression and the decompression of a
 * complete chunk payload.
 * <p>
 * The payload is not random noise. It is the serialised NBT of a chunk of twenty four sections,
 * built through the same codec the save path uses, so its entropy matches what a region file really
 * stores. Random bytes would not compress at all and would make zlib look far more expensive than
 * it is on real data.
 * </p>
 * <p>
 * This benchmark carries the weight of the central design claim of the loader. Compression and
 * decompression are the most expensive stage of a chunk transfer and the loader performs both of
 * them outside of the region lock. The number this benchmark reports is the amount of time that
 * would otherwise be spent inside that lock.
 * </p>
 * <p>
 * The scheme is a parameter because the format allows all three. Vanilla writes zlib, gzip appears
 * in older worlds and none appears in worlds which were written by a tool that optimised for speed.
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
public class ChunkCompressionBenchmark {

    /**
     * The compression scheme the payload is stored with.
     */
    @Param({"ZLIB", "GZIP", "NONE"})
    public ChunkCompression compression;

    /**
     * The amount of distinct block states a single section of the chunk holds.
     * The value decides how well the payload compresses.
     */
    @Param({"8", "200"})
    public int distinctStates;

    private byte[] raw;
    private byte[] compressed;

    /**
     * Creates a new benchmark instance.
     */
    public ChunkCompressionBenchmark() {
    }

    /**
     * Builds the serialised chunk and the compressed form of it.
     *
     * @throws IOException if the chunk cannot be serialised
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        ChunkColumn column = ChunkColumn.of(BenchmarkConstants.OVERWORLD_SECTIONS, this.distinctStates);
        FakePaletteEntryResolver resolver = new FakePaletteEntryResolver();
        this.raw = ChunkPayloads.serialize(column, resolver, resolver);
        this.compressed = this.compression.compress(this.raw);
    }

    /**
     * Compresses a whole chunk payload, which is the last stage of a chunk save.
     *
     * @return the compressed payload
     * @throws IOException if the payload cannot be compressed
     */
    @Benchmark
    public byte[] compress() throws IOException {
        return this.compression.compress(this.raw);
    }

    /**
     * Decompresses a whole chunk payload, which is the first stage of a chunk load.
     *
     * @return the uncompressed payload
     * @throws IOException if the payload cannot be decompressed
     */
    @Benchmark
    public byte[] decompress() throws IOException {
        return this.compression.decompress(this.compressed);
    }
}
