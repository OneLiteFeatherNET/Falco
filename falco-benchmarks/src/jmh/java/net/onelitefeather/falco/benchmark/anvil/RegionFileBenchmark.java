package net.onelitefeather.falco.benchmark.anvil;

import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.ChunkColumn;
import net.onelitefeather.falco.benchmark.support.ChunkPayloads;
import net.onelitefeather.falco.benchmark.support.FakePaletteEntryResolver;
import net.onelitefeather.falco.anvil.ChunkCompression;
import net.onelitefeather.falco.anvil.RegionFile;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The {@link RegionFileBenchmark} class measures the byte transfer of a region file.
 * <p>
 * The measured operations move an already compressed payload. That is the whole point of the split
 * the region file enforces: the caller compresses and decompresses on its own, and only the
 * transfer of the finished bytes touches the sector allocation and the header, which are the parts
 * the internal lock protects.
 * </p>
 * <p>
 * Reading uses positional channel operations and takes no lock, so the read benchmark describes a
 * path several threads can walk at the same time. What it adds on top of the raw transfer are the
 * two reads of the version counter of the chunk which tell the reader whether a writer moved the
 * bytes underneath it. Writing takes the lock for the allocation and the header update, so the write
 * benchmark describes the part of a save which really serialises between threads.
 * </p>
 * <p>
 * The numbers depend heavily on the file system and on the page cache of the machine. A run on a
 * warm cache measures almost no device time at all, which is realistic for a server that saves the
 * same chunks over and over, but it is not a measurement of the storage device.
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
public class RegionFileBenchmark {

    private static final int CHUNK_X = 5;
    private static final int CHUNK_Z = 7;

    private Path directory;
    private RegionFile region;
    private byte[] payload;

    /**
     * Creates a new benchmark instance.
     */
    public RegionFileBenchmark() {
    }

    /**
     * Creates a temporary region file and stores one chunk in it.
     * The stored chunk is what the read benchmark reads back.
     *
     * @throws IOException if the region file cannot be prepared
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        this.directory = Files.createTempDirectory("falco-region-benchmark");
        this.region = RegionFile.open(this.directory.resolve("r.0.0.mca"));

        ChunkColumn column = ChunkColumn.of(BenchmarkConstants.OVERWORLD_SECTIONS, 64);
        FakePaletteEntryResolver resolver = new FakePaletteEntryResolver();
        this.payload = ChunkCompression.ZLIB.compress(ChunkPayloads.serialize(column, resolver, resolver));
        this.region.writeRaw(CHUNK_X, CHUNK_Z, ChunkCompression.ZLIB, this.payload);
    }

    /**
     * Closes the region file and removes the temporary directory again.
     *
     * @throws IOException if the temporary files cannot be removed
     */
    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        this.region.close();

        try (Stream<Path> entries = Files.walk(this.directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }

    /**
     * Writes an already compressed chunk payload into the region file.
     * <p>
     * The chunk is written to the same coordinate every time. The allocator frees the previous
     * sectors after it reserved the new ones, so the file alternates between two sector ranges and
     * cannot grow without bound over a long run.
     * </p>
     *
     * @throws IOException if the payload cannot be written
     */
    @Benchmark
    public void writeRaw() throws IOException {
        this.region.writeRaw(CHUNK_X, CHUNK_Z, ChunkCompression.ZLIB, this.payload);
    }

    /**
     * Reads the raw payload of a chunk without decompressing it.
     *
     * @return the raw chunk which was read
     * @throws IOException if the payload cannot be read
     */
    @Benchmark
    public RegionFile.RawChunk readRaw() throws IOException {
        return this.region.readRaw(CHUNK_X, CHUNK_Z);
    }

    /**
     * Writes and reads a chunk again, which is the full byte transfer of a save followed by a load.
     *
     * @return the raw chunk which was read
     * @throws IOException if the payload cannot be transferred
     */
    @Benchmark
    public RegionFile.RawChunk roundTrip() throws IOException {
        this.region.writeRaw(CHUNK_X, CHUNK_Z, ChunkCompression.ZLIB, this.payload);
        return this.region.readRaw(CHUNK_X, CHUNK_Z);
    }
}
