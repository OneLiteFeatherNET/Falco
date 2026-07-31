package net.onelitefeather.falco.benchmark.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The {@link ChunkSaveStageBenchmark} class splits a whole chunk save into the three stages it
 * consists of and measures each of them on its own.
 * <p>
 * The loader is built around one claim: only the snapshot stage holds the read lock of the chunk
 * and only the transfer stage holds the lock of the region file, while the codec stage, which is
 * the expensive one, runs without any lock at all. That claim is structural in the source and this
 * benchmark is what turns it into a number. Comparing the three stages shows how much of a save
 * really happens outside of a lock.
 * </p>
 * <ul>
 *   <li>{@code snapshot} copies the arrays of every section. The real loader performs this while it
 *       holds the read lock of the chunk, so a game thread waits for exactly this stage.</li>
 *   <li>{@code codec} builds the palettes, packs the indices, serialises the NBT and compresses the
 *       result. No lock is held here.</li>
 *   <li>{@code transfer} hands the finished bytes to the region file. The region lock is held for
 *       the sector allocation and the header update inside this stage.</li>
 *   <li>{@code full} performs all three so the sum of the parts can be checked against the whole.</li>
 * </ul>
 * <p>
 * The chunk is not a Minestom chunk. A Minestom section needs a started server and its registries,
 * which would put registry time into the codec stage and hide the very thing this benchmark is
 * supposed to isolate. The section arrays are therefore plain arrays of state ids and the resolver
 * is a fake one.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class ChunkSaveStageBenchmark {

    private static final int CHUNK_X = 3;
    private static final int CHUNK_Z = 11;

    /**
     * The amount of distinct block states a single section of the chunk holds.
     */
    @Param({"8", "200"})
    public int distinctStates;

    private ChunkColumn column;
    private FakePaletteEntryResolver resolver;
    private ChunkColumn snapshot;
    private byte[] compressed;
    private Path directory;
    private RegionFile region;

    /**
     * Creates a new benchmark instance.
     */
    public ChunkSaveStageBenchmark() {
    }

    /**
     * Builds the chunk, the intermediate results of every stage and the temporary region file.
     * <p>
     * Every stage benchmark needs the output of the previous one as its input. Preparing those here
     * keeps each measured method restricted to the stage it is named after.
     * </p>
     *
     * @throws IOException if the region file or the payload cannot be prepared
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        this.column = ChunkColumn.of(BenchmarkConstants.OVERWORLD_SECTIONS, this.distinctStates);
        this.resolver = new FakePaletteEntryResolver();
        this.snapshot = this.column.copy();
        this.compressed = ChunkCompression.ZLIB.compress(
                ChunkPayloads.serialize(this.snapshot, this.resolver, this.resolver)
        );

        this.directory = Files.createTempDirectory("falco-save-benchmark");
        this.region = RegionFile.open(this.directory.resolve("r.0.0.mca"));
        this.region.writeRaw(CHUNK_X, CHUNK_Z, ChunkCompression.ZLIB, this.compressed);
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
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        }
    }

    /**
     * Copies every section array of the chunk.
     * This is the only stage the real loader performs under the read lock of the chunk.
     *
     * @return the copied chunk
     */
    @Benchmark
    public ChunkColumn snapshot() {
        return this.column.copy();
    }

    /**
     * Builds the palettes, the NBT and the compressed payload from an already copied chunk.
     * This stage runs without holding any lock.
     *
     * @return the compressed payload of the chunk
     * @throws IOException if the payload cannot be built
     */
    @Benchmark
    public byte[] codec() throws IOException {
        return ChunkCompression.ZLIB.compress(ChunkPayloads.serialize(this.snapshot, this.resolver, this.resolver));
    }

    /**
     * Builds only the NBT of an already copied chunk, without compressing it.
     * Comparing this against {@link #codec()} splits the codec stage into its palette part and its
     * compression part.
     *
     * @return the chunk data of the chunk
     */
    @Benchmark
    public CompoundBinaryTag codecWithoutCompression() {
        return ChunkPayloads.encode(this.snapshot, this.resolver, this.resolver);
    }

    /**
     * Hands an already compressed payload to the region file.
     * The region lock is held inside this stage.
     *
     * @throws IOException if the payload cannot be written
     */
    @Benchmark
    public void transfer() throws IOException {
        this.region.writeRaw(CHUNK_X, CHUNK_Z, ChunkCompression.ZLIB, this.compressed);
    }

    /**
     * Performs a whole chunk save from the snapshot to the written bytes.
     *
     * @throws IOException if the chunk cannot be saved
     */
    @Benchmark
    public void full() throws IOException {
        ChunkColumn copy = this.column.copy();
        byte[] payload = ChunkCompression.ZLIB.compress(ChunkPayloads.serialize(copy, this.resolver, this.resolver));
        this.region.writeRaw(CHUNK_X, CHUNK_Z, ChunkCompression.ZLIB, payload);
    }
}
