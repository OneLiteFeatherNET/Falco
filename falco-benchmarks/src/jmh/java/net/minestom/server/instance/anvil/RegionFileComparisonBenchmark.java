package net.minestom.server.instance.anvil;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The {@link RegionFileComparisonBenchmark} class measures the region file of Falco against the one
 * Minestom ships with, on the same bytes and through the same compression code.
 * <p>
 * The class lives in the Minestom anvil package because {@code net.minestom.server.instance.anvil.RegionFile}
 * is package-private. This is the only way to measure the original rather than a reimplementation
 * of it, the same reason the light engine comparison lives in the Minestom light package.
 * </p>
 * <p>
 * The region file is the layer at which the two loaders can be compared without a running server.
 * Minestom's {@code AnvilLoader} cannot be touched from a bare benchmark fork at all: its static
 * fields read the biome registry and the block state count, so the class initialiser fails before
 * any measurement starts. Its region file reads no registry and is therefore measurable directly.
 * </p>
 * <p>
 * Both sides are measured over the same total work, from a stored chunk to a parsed compound and
 * back. The difference between them is where that work happens relative to the lock of the file:
 * </p>
 * <ul>
 *   <li>Minestom reads the bytes, inflates them and parses the NBT with the file lock held, so two
 *       readers of the same region file cannot overlap at all.</li>
 *   <li>Falco reads the bytes through positional channel operations without any lock and validates
 *       the read against a version counter afterwards, so only the inflate and the parse of a
 *       reader overlap with those of another one.</li>
 *   <li>Minestom rewrites the whole {@code 8192} byte header on every write, Falco rewrites the
 *       eight bytes of the affected entry.</li>
 * </ul>
 * <p>
 * Fairness of the compression is enforced rather than assumed. Both sides run the identical
 * Adventure writer at its default level, and the payload both files hold is byte for byte the same,
 * so nothing of what is measured here is a compression level in disguise. The loader of Falco ships
 * a lower level by default, which is a property of the loader and not of the region file, and is
 * therefore measured by {@code ChunkSaveComparisonBenchmark} instead.
 * </p>
 * <p>
 * The thread count is the axis that carries this benchmark. Run it over a series with the
 * {@code -t} option of the harness, because a single thread cannot show a difference that only
 * exists between threads.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RegionFileComparisonBenchmark {

    /**
     * The amount of chunks the measured region file holds.
     * <p>
     * Every thread works on a chunk of its own so the threads never collide on a single entry.
     * What they still share is the region file, which is exactly the resource the two
     * implementations guard differently.
     * </p>
     */
    private static final int CHUNK_COUNT = 32;

    /**
     * The width of the chunk grid inside the region file.
     */
    private static final int GRID_WIDTH = 8;

    private static final BinaryTagIO.Reader TAG_READER = BinaryTagIO.unlimitedReader();
    private static final BinaryTagIO.Writer TAG_WRITER = BinaryTagIO.writer();

    /**
     * Hands every benchmark thread a chunk of its own.
     */
    private static final AtomicInteger SLOTS = new AtomicInteger();

    /**
     * The amount of distinct block states a single section of the measured chunk holds.
     * <p>
     * The value decides how large the stored payload is and therefore how much of a read is the
     * inflate and the parse rather than the transfer of the bytes.
     * </p>
     */
    @Param({"8", "200"})
    public int distinctStates;

    private Path directory;
    private RegionFile minestomRegion;
    private net.onelitefeather.falco.anvil.RegionFile falcoRegion;
    private CompoundBinaryTag chunkData;

    /**
     * Creates a new benchmark instance.
     */
    public RegionFileComparisonBenchmark() {
    }

    /**
     * Builds one chunk compound and stores it in both region files, with byte identical payloads.
     * <p>
     * The payload is produced once with the Adventure writer both implementations use and handed to
     * both files, so a later read of either side decodes the very same bytes.
     * </p>
     *
     * @throws IOException if the region files cannot be prepared
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        this.directory = Files.createTempDirectory("falco-region-comparison");

        ChunkColumn column = ChunkColumn.of(BenchmarkConstants.OVERWORLD_SECTIONS, this.distinctStates);
        FakePaletteEntryResolver resolver = new FakePaletteEntryResolver();
        this.chunkData = ChunkPayloads.encode(column, resolver, resolver);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream(64 * 1024);
        TAG_WRITER.writeNamed(Map.entry("", this.chunkData), compressed, BinaryTagIO.Compression.ZLIB);
        byte[] payload = compressed.toByteArray();

        this.minestomRegion = new RegionFile(this.directory.resolve("minestom.mca"));
        this.falcoRegion = net.onelitefeather.falco.anvil.RegionFile.open(this.directory.resolve("falco.mca"));

        for (int slot = 0; slot < CHUNK_COUNT; slot++) {
            int chunkX = chunkX(slot);
            int chunkZ = chunkZ(slot);
            this.minestomRegion.writeChunkData(chunkX, chunkZ, this.chunkData);
            this.falcoRegion.writeRaw(chunkX, chunkZ, ChunkCompression.ZLIB, payload);
        }
    }

    /**
     * Closes both region files and removes the temporary directory.
     *
     * @throws IOException if the directory cannot be removed
     */
    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        this.minestomRegion.close();
        this.falcoRegion.close();

        try (Stream<Path> entries = Files.walk(this.directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover file in the temporary directory does not invalidate a measurement.
                }
            });
        }
    }

    /**
     * Reads a stored chunk through the region file of Minestom.
     * The lock of the file is held for the transfer, the inflate and the parse.
     *
     * @param slot the chunk this thread works on
     * @return the parsed chunk data
     * @throws IOException if the chunk cannot be read
     */
    @Benchmark
    public CompoundBinaryTag minestomRead(ThreadSlot slot) throws IOException {
        return this.minestomRegion.readChunkData(slot.chunkX, slot.chunkZ);
    }

    /**
     * Reads the same stored chunk through the region file of Falco.
     * Only the transfer of the bytes is guarded, the inflate and the parse run without a lock.
     *
     * @param slot the chunk this thread works on
     * @return the parsed chunk data
     * @throws IOException if the chunk cannot be read
     */
    @Benchmark
    public CompoundBinaryTag falcoRead(ThreadSlot slot) throws IOException {
        net.onelitefeather.falco.anvil.RegionFile.RawChunk raw =
                this.falcoRegion.readRaw(slot.chunkX, slot.chunkZ);
        return TAG_READER.read(new ByteArrayInputStream(raw.decompress()), BinaryTagIO.Compression.NONE);
    }

    /**
     * Writes a chunk through the region file of Minestom, which rewrites the whole header.
     *
     * @param slot the chunk this thread works on
     * @throws IOException if the chunk cannot be written
     */
    @Benchmark
    public void minestomWrite(ThreadSlot slot) throws IOException {
        this.minestomRegion.writeChunkData(slot.chunkX, slot.chunkZ, this.chunkData);
    }

    /**
     * Writes the same chunk through the region file of Falco, which rewrites the affected entry.
     * <p>
     * The serialisation and the compression happen inside the measured method on purpose. They do
     * so on the Minestom side as well, so both sides carry the identical amount of work and only
     * the part behind the lock differs.
     * </p>
     *
     * @param slot the chunk this thread works on
     * @throws IOException if the chunk cannot be written
     */
    @Benchmark
    public void falcoWrite(ThreadSlot slot) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream(64 * 1024);
        TAG_WRITER.writeNamed(Map.entry("", this.chunkData), target, BinaryTagIO.Compression.ZLIB);
        this.falcoRegion.writeRaw(slot.chunkX, slot.chunkZ, ChunkCompression.ZLIB, target.toByteArray());
    }

    /**
     * Returns the x coordinate of the chunk which belongs to the given slot.
     *
     * @param slot the slot to resolve
     * @return the absolute chunk x coordinate
     */
    private static int chunkX(int slot) {
        return slot % GRID_WIDTH;
    }

    /**
     * Returns the z coordinate of the chunk which belongs to the given slot.
     *
     * @param slot the slot to resolve
     * @return the absolute chunk z coordinate
     */
    private static int chunkZ(int slot) {
        return slot / GRID_WIDTH;
    }

    /**
     * The {@link ThreadSlot} class assigns a chunk of the shared region file to a benchmark thread.
     * <p>
     * Without it every thread would hammer the same entry, which measures the contention on one
     * chunk rather than the contention on the file. Real parallel loading reads different chunks of
     * the same region, which is what a slot per thread reproduces.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    @State(Scope.Thread)
    public static class ThreadSlot {

        /**
         * The absolute chunk x coordinate this thread works on.
         */
        public int chunkX;

        /**
         * The absolute chunk z coordinate this thread works on.
         */
        public int chunkZ;

        /**
         * Creates a new thread slot.
         */
        public ThreadSlot() {
        }

        /**
         * Picks the chunk of this thread.
         */
        @Setup(Level.Trial)
        public void setUp() {
            int slot = SLOTS.getAndIncrement() % CHUNK_COUNT;
            this.chunkX = chunkX(slot);
            this.chunkZ = chunkZ(slot);
        }
    }
}
