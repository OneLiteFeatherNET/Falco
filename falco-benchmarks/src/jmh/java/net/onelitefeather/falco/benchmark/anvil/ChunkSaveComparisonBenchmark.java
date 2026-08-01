package net.onelitefeather.falco.benchmark.anvil;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.minestom.server.instance.block.Block;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The {@link ChunkSaveComparisonBenchmark} class measures the whole chunk loader of Falco against
 * the {@code AnvilLoader} of Minestom, on the same chunks and through the same registries.
 * <p>
 * The loader of Minestom cannot be reached from a bare benchmark fork: its static fields read the
 * biome registry and the block state count, so touching the class before a server exists fails in
 * the class initialiser. The benchmark therefore starts a server in its trial setup, exactly as the
 * light engine comparison does, and only then builds the chunks and the two loaders.
 * </p>
 * <p>
 * The interesting axis is the amount of distinct block states a section holds, because that is
 * where the two save paths differ structurally. Minestom deduplicates a palette entry with a linear
 * search over an {@code IntArrayList}, so the cost of a section grows with the product of its block
 * count and its palette size. Falco deduplicates through a hash map, so the cost grows with the
 * block count alone. A single measurement point cannot tell those two apart, a series over the
 * palette size can.
 * </p>
 * <p>
 * The two loaders are measured as they ship, which includes their different compression levels:
 * Minestom writes at the default level of the platform, Falco at
 * {@link ChunkCompression#DEFAULT_LEVEL}. That difference is deliberate on the side of Falco and
 * belongs to what a user of the loader gets, so hiding it would misrepresent both. It is also the
 * one part of the result that is not about the loader structure, which is why the two calibration
 * benchmarks below measure it separately: subtracting them from the save numbers leaves the part of
 * the difference that the compression level does not explain.
 * </p>
 * <p>
 * Every thread saves a chunk of its own, all of them inside the same region file. That is what a
 * server does when it flushes a region, and it is the case in which the lock of the region file
 * decides the throughput.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ChunkSaveComparisonBenchmark {

    /**
     * The amount of chunks the benchmark prepares, one per benchmark thread in rotation.
     */
    private static final int CHUNK_COUNT = 32;

    /**
     * The width of the chunk grid, chosen so all chunks land in the same region file.
     */
    private static final int GRID_WIDTH = 8;

    /**
     * The compression level the Adventure writer of Minestom uses for a chunk payload.
     */
    private static final int MINESTOM_LEVEL = 6;

    private static final long SEED = 20260731L;

    private static final BinaryTagIO.Writer TAG_WRITER = BinaryTagIO.writer();

    /**
     * Hands every benchmark thread a chunk of its own.
     */
    private static final AtomicInteger SLOTS = new AtomicInteger();

    /**
     * The amount of distinct block states a single section of the measured chunks holds.
     * <p>
     * One is the uniform section both sides recognise and skip, which is the control point of the
     * series. The values beyond it grow the palette, which is the axis the linear search of
     * Minestom is expected to be sensitive to and the hash map of Falco is not.
     * </p>
     */
    @Param({"1", "16", "64", "256", "1024"})
    public int distinctStates;

    private Path directory;
    private Instance instance;
    private List<Chunk> chunks;
    private AnvilLoader minestomLoader;
    private FalcoAnvilLoader falcoLoader;
    private byte[] serialized;

    /**
     * Creates a new benchmark instance.
     */
    public ChunkSaveComparisonBenchmark() {
    }

    /**
     * Starts the server, builds the chunks and creates both loaders on separate world directories.
     *
     * @throws IOException if the world directories cannot be prepared
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }

        this.directory = Files.createTempDirectory("falco-save-comparison");
        Key dimension = Key.key("minecraft:overworld");
        Path minestomRoot = this.directory.resolve("minestom");
        Path falcoRoot = this.directory.resolve("falco");
        Files.createDirectories(minestomRoot.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(falcoRoot.resolve("dimensions/minecraft/overworld/region"));

        this.minestomLoader = new AnvilLoader(minestomRoot, dimension);
        this.falcoLoader = new FalcoAnvilLoader(falcoRoot, dimension);

        this.instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        int[] states = distinctStates(this.distinctStates);
        this.chunks = new ArrayList<>(CHUNK_COUNT);

        for (int slot = 0; slot < CHUNK_COUNT; slot++) {
            this.chunks.add(build(this.instance, chunkX(slot), chunkZ(slot), states, slot));
        }

        ByteArrayOutputStream target = new ByteArrayOutputStream(128 * 1024);
        TAG_WRITER.writeNamed(Map.entry("", snapshotOf(this.chunks.getFirst())), target, BinaryTagIO.Compression.NONE);
        this.serialized = target.toByteArray();
    }

    /**
     * Removes the world directories of both loaders.
     *
     * @throws IOException if the loader of Falco cannot be closed
     */
    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        this.falcoLoader.close();

        try (Stream<Path> entries = Files.walk(this.directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                    // A leftover file in the temporary directory does not invalidate a measurement.
                }
            });
        }
    }

    /**
     * Saves a chunk through the loader of Minestom.
     *
     * @param slot the chunk this thread works on
     */
    @Benchmark
    public void minestomSave(ThreadSlot slot) {
        this.minestomLoader.saveChunk(this.chunks.get(slot.slot));
    }

    /**
     * Saves the same chunk through the loader of Falco.
     *
     * @param slot the chunk this thread works on
     */
    @Benchmark
    public void falcoSave(ThreadSlot slot) {
        this.falcoLoader.saveChunk(this.chunks.get(slot.slot));
    }

    /**
     * Compresses the serialised chunk at the level the loader of Falco ships with.
     * <p>
     * The method measures no loader at all. It exists so the share of the save difference that is
     * only the compression level can be subtracted from the two save numbers.
     * </p>
     *
     * @return the compressed payload
     * @throws IOException if the payload cannot be compressed
     */
    @Benchmark
    public byte[] compressFalcoLevel() throws IOException {
        return ChunkCompression.ZLIB.compress(this.serialized, ChunkCompression.DEFAULT_LEVEL);
    }

    /**
     * Compresses the same serialised chunk at the level the loader of Minestom writes with.
     *
     * @return the compressed payload
     * @throws IOException if the payload cannot be compressed
     */
    @Benchmark
    public byte[] compressMinestomLevel() throws IOException {
        return ChunkCompression.ZLIB.compress(this.serialized, MINESTOM_LEVEL);
    }

    /**
     * Builds a chunk whose sections hold the given amount of distinct block states.
     *
     * @param instance the instance the chunk belongs to
     * @param chunkX   the absolute chunk x coordinate
     * @param chunkZ   the absolute chunk z coordinate
     * @param states   the block state ids the sections are filled from
     * @param seed     an offset which keeps the chunks from being identical
     * @return the created chunk
     */
    private static Chunk build(Instance instance, int chunkX, int chunkZ, int[] states, int seed) {
        Chunk chunk = instance.getChunkSupplier().createChunk(instance, chunkX, chunkZ);
        Random random = new Random(SEED + seed);
        byte[] light = new byte[2048];

        for (Section section : chunk.getSections()) {
            int[] values = shuffled(states, random);
            section.blockPalette().setAll((x, y, z) -> values[(y << 8) | (z << 4) | x]);
            random.nextBytes(light);
            section.skyLight().set(light.clone());
            section.blockLight().set(new byte[2048]);
        }
        return chunk;
    }

    /**
     * Builds the value of every block of a section so the section holds exactly as many distinct
     * states as the given array does.
     *
     * @param states the block state ids to spread over the section
     * @param random the source of the shuffle
     * @return the value of every block of the section
     */
    private static int[] shuffled(int[] states, Random random) {
        int[] values = new int[16 * 16 * 16];

        for (int index = 0; index < values.length; index++) {
            values[index] = states[index % states.length];
        }
        for (int index = values.length - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            int swap = values[index];
            values[index] = values[other];
            values[other] = swap;
        }
        return values;
    }

    /**
     * Collects the requested amount of distinct block state ids from the registry.
     *
     * @param wanted the amount of distinct block states to collect
     * @return the collected block state ids
     * @throws IllegalStateException if the registry holds fewer states than requested
     */
    private static int[] distinctStates(int wanted) {
        int[] states = Block.values().stream()
                .flatMap(block -> block.possibleStates().stream())
                .mapToInt(Block::stateId)
                .limit(wanted)
                .toArray();

        if (states.length < wanted) {
            throw new IllegalStateException("The registry holds only " + states.length + " of " + wanted + " states");
        }
        return states;
    }

    /**
     * Builds the chunk compound of the given chunk through the loader of Falco.
     * <p>
     * The result is only used to obtain a realistic uncompressed payload for the two calibration
     * benchmarks, which need the bytes and not the loader.
     * </p>
     *
     * @param chunk the chunk to describe
     * @return the chunk data as the loader of Falco stores it
     * @throws IOException if the chunk cannot be described
     */
    private CompoundBinaryTag snapshotOf(Chunk chunk) throws IOException {
        Path probeRoot = this.directory.resolve("probe");
        Files.createDirectories(probeRoot.resolve("dimensions/minecraft/overworld/region"));

        try (FalcoAnvilLoader probe = new FalcoAnvilLoader(probeRoot, Key.key("minecraft:overworld"))) {
            probe.saveChunk(chunk);
        }
        Path region = probeRoot.resolve("dimensions/minecraft/overworld/region")
                .resolve("r." + (chunk.getChunkX() >> 5) + "." + (chunk.getChunkZ() >> 5) + ".mca");

        try (RegionFile file = RegionFile.open(region)) {
            RegionFile.RawChunk raw = file.readRaw(chunk.getChunkX(), chunk.getChunkZ());

            if (raw == null) {
                throw new IOException("The probe region file does not hold the chunk");
            }
            return BinaryTagIO.unlimitedReader().read(
                    new ByteArrayInputStream(raw.decompress()), BinaryTagIO.Compression.NONE
            );
        }
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
     * The {@link ThreadSlot} class assigns one of the prepared chunks to a benchmark thread.
     * <p>
     * Two threads which save the same chunk would collide on the lock of that chunk, which is a
     * different measurement than the one this benchmark is after. A server flushing a region saves
     * different chunks into the same region file, which is what a slot per thread reproduces.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    @State(Scope.Thread)
    public static class ThreadSlot {

        /**
         * The index of the chunk this thread works on.
         */
        public int slot;

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
            this.slot = SLOTS.getAndIncrement() % CHUNK_COUNT;
        }
    }
}
