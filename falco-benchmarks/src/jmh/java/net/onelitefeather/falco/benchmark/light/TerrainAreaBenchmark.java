package net.onelitefeather.falco.benchmark.light;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.onelitefeather.falco.light.ChunkArea;
import net.onelitefeather.falco.light.ChunkLightArea;
import net.onelitefeather.falco.light.ChunkLightService;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The {@link TerrainAreaBenchmark} class measures an area pass over a world that looks like one.
 * <p>
 * <b>Why it exists next to {@link AreaPassStageBenchmark}.</b> Every other light benchmark fills a
 * single slab of sixteen blocks with random stone and leaves the rest of the column as air. That
 * makes one section per chunk interesting and the other twenty-three uniform, and a uniform section
 * is the case every stage of the engine answers without a table. The light of such a world settles
 * in a few blocks, so the benchmarks measure the fixed cost of a pass far more than the search.
 * </p>
 * <p>
 * The world here is the opposite on purpose. Hills rise from solid ground, caves run through the
 * stone below them and open into the surface as overhangs, and glowstone sits in the caves and on
 * the ground. Many sections of a column are mixed, light travels across chunk borders through the
 * caves, and the sky light has to find its way under every overhang. Both kinds of light are
 * measured, because a tick pays for both.
 * </p>
 * <p>
 * The terrain is computed from seeded value noise in world coordinates rather than per chunk, so
 * caves and hills continue across borders the way they do in a generated world. The area and its
 * ring are filled alike for the reason {@link AreaPassStageBenchmark} gives: the ring is read on
 * every pass, and an empty ring would make reading it look free.
 * </p>
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class TerrainAreaBenchmark {

    /**
     * The seed of the terrain, so every run measures the same world.
     */
    private static final long SEED = 20261022L;

    /**
     * The height the hills oscillate around.
     */
    private static final int SEA_LEVEL = 64;

    /**
     * How far the surface rises above or sinks below {@link #SEA_LEVEL}.
     */
    private static final int HILL_AMPLITUDE = 24;

    /**
     * The horizontal size of one hill, in blocks.
     */
    private static final int HILL_SCALE = 32;

    /**
     * The size of one cell of the cave noise, in blocks.
     */
    private static final int CAVE_SCALE = 8;

    /**
     * The noise value above which a position of the ground is a cave. Lower values carve more.
     */
    private static final double CAVE_THRESHOLD = 0.62;

    /**
     * The attempts made per chunk to put a light source into a cave.
     */
    private static final int CAVE_SOURCE_ATTEMPTS = 64;

    /**
     * The light sources placed on the surface of every chunk.
     */
    private static final int SURFACE_SOURCES = 4;

    /**
     * The amount of connected chunks the area holds.
     */
    @Param({"1", "16", "64"})
    private int chunkCount;

    /**
     * Whether the sky light is computed instead of the block light.
     */
    @Param({"false", "true"})
    private boolean sky;

    private Instance instance;
    private ChunkLightArea area;
    private List<ChunkArea> areaPositions;

    /**
     * Creates a new benchmark instance.
     */
    public TerrainAreaBenchmark() {
    }

    /**
     * Builds the square, loads the ring around it and generates the terrain into both.
     */
    @Setup(Level.Trial)
    public void setUp() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }

        this.area = new ChunkLightArea(new ChunkLightService());
        this.instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        this.areaPositions = new ArrayList<>(this.chunkCount);

        int edge = (int) Math.round(Math.sqrt(this.chunkCount));

        for (int index = 0; index < this.chunkCount; index++) {
            this.areaPositions.add(new ChunkArea(index % edge, index / edge));
        }

        Set<ChunkArea> pass = new LinkedHashSet<>(this.areaPositions);

        for (ChunkArea position : this.areaPositions) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    pass.add(new ChunkArea(position.x() + offsetX, position.z() + offsetZ));
                }
            }
        }

        for (ChunkArea position : pass) {
            generate(this.instance.loadChunk(position.x(), position.z()).join());
        }
    }

    /**
     * Generates hills, caves and light sources into the given chunk.
     *
     * @param chunk the chunk to fill
     */
    static void generate(Chunk chunk) {
        int baseX = chunk.getChunkX() * 16;
        int baseZ = chunk.getChunkZ() * 16;
        int minY = chunk.getMinSection() * 16;
        // Seeded per chunk, so a chunk receives the same sources whatever order the ring is loaded in.
        Random random = new Random(SEED ^ (31L * chunk.getChunkX() + chunk.getChunkZ()) * 0x9E3779B97F4A7C15L);

        chunk.lockWriteLock();
        try {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = baseX + x;
                    int worldZ = baseZ + z;
                    int surface = surfaceAt(worldX, worldZ);

                    for (int y = minY; y <= surface; y++) {
                        if (!isCave(worldX, y, worldZ)) {
                            chunk.setBlock(worldX, y, worldZ, Block.STONE);
                        }
                    }
                }
            }

            for (int attempt = 0; attempt < CAVE_SOURCE_ATTEMPTS; attempt++) {
                int worldX = baseX + random.nextInt(16);
                int worldZ = baseZ + random.nextInt(16);
                int y = minY + random.nextInt(surfaceAt(worldX, worldZ) - minY);

                if (isCave(worldX, y, worldZ)) {
                    chunk.setBlock(worldX, y, worldZ, Block.GLOWSTONE);
                }
            }

            for (int placed = 0; placed < SURFACE_SOURCES; placed++) {
                int worldX = baseX + random.nextInt(16);
                int worldZ = baseZ + random.nextInt(16);
                chunk.setBlock(worldX, surfaceAt(worldX, worldZ) + 1, worldZ, Block.GLOWSTONE);
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Returns the height of the ground at the given column.
     *
     * @param worldX the x coordinate of the column
     * @param worldZ the z coordinate of the column
     * @return the y coordinate of the topmost ground block
     */
    private static int surfaceAt(int worldX, int worldZ) {
        double broad = noise(worldX / (double) HILL_SCALE, 0, worldZ / (double) HILL_SCALE, 1);
        double fine = noise(worldX / (HILL_SCALE / 4.0), 0, worldZ / (HILL_SCALE / 4.0), 2);
        return SEA_LEVEL + (int) Math.round(((broad * 0.8 + fine * 0.2) * 2 - 1) * HILL_AMPLITUDE);
    }

    /**
     * Checks whether the given position of the ground is carved out by a cave.
     *
     * @param worldX the x coordinate
     * @param y      the y coordinate
     * @param worldZ the z coordinate
     * @return true if the position is air, otherwise false
     */
    private static boolean isCave(int worldX, int y, int worldZ) {
        return noise(worldX / (double) CAVE_SCALE, y / (double) CAVE_SCALE, worldZ / (double) CAVE_SCALE, 3)
                > CAVE_THRESHOLD;
    }

    /**
     * Samples smoothly interpolated value noise.
     *
     * @param x       the x coordinate in noise cells
     * @param y       the y coordinate in noise cells
     * @param z       the z coordinate in noise cells
     * @param channel separates independent noise fields that share coordinates
     * @return a value within [0, 1]
     */
    private static double noise(double x, double y, double z, int channel) {
        int cellX = (int) Math.floor(x);
        int cellY = (int) Math.floor(y);
        int cellZ = (int) Math.floor(z);
        double fractionX = smooth(x - cellX);
        double fractionY = smooth(y - cellY);
        double fractionZ = smooth(z - cellZ);

        double bottom = lerp(fractionZ,
                lerp(fractionX, corner(cellX, cellY, cellZ, channel), corner(cellX + 1, cellY, cellZ, channel)),
                lerp(fractionX, corner(cellX, cellY, cellZ + 1, channel), corner(cellX + 1, cellY, cellZ + 1, channel)));
        double top = lerp(fractionZ,
                lerp(fractionX, corner(cellX, cellY + 1, cellZ, channel), corner(cellX + 1, cellY + 1, cellZ, channel)),
                lerp(fractionX, corner(cellX, cellY + 1, cellZ + 1, channel), corner(cellX + 1, cellY + 1, cellZ + 1, channel)));
        return lerp(fractionY, bottom, top);
    }

    /**
     * Returns the random value of one corner of the noise lattice.
     *
     * @param x       the x coordinate of the corner
     * @param y       the y coordinate of the corner
     * @param z       the z coordinate of the corner
     * @param channel the noise field the corner belongs to
     * @return a value within [0, 1)
     */
    private static double corner(int x, int y, int z, int channel) {
        long hash = SEED + channel * 0x632BE59BD9B4E019L;
        hash ^= x * 0x9E3779B97F4A7C15L;
        hash ^= y * 0xC2B2AE3D27D4EB4FL;
        hash ^= z * 0x165667B19E3779F9L;
        hash = (hash ^ (hash >>> 31)) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ (hash >>> 29)) * 0x94D049BB133111EBL;
        hash ^= hash >>> 32;
        return (hash >>> 11) * 0x1.0p-53;
    }

    /**
     * Eases the given fraction so the noise has no visible edges at the cell borders.
     *
     * @param fraction a value within [0, 1]
     * @return the eased value
     */
    private static double smooth(double fraction) {
        return fraction * fraction * (3 - 2 * fraction);
    }

    /**
     * Interpolates linearly between two values.
     *
     * @param fraction the share of the second value
     * @param from     the first value
     * @param to       the second value
     * @return the interpolated value
     */
    private static double lerp(double fraction, double from, double to) {
        return from + fraction * (to - from);
    }

    /**
     * Computes the light of the whole area, the ring included, and writes the area back.
     *
     * @return the chunks which were written
     */
    @Benchmark
    public List<ChunkArea> wholePass() {
        return this.area.compute(this.instance, this.areaPositions, this.sky);
    }
}
