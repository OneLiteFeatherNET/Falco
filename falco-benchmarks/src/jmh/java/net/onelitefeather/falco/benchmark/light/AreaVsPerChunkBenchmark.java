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
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The {@link AreaVsPerChunkBenchmark} class decides whether area forming earns its complexity.
 * <p>
 * The design of the self maintaining light rests on one claim: lighting a group of connected chunks
 * in a single pass is cheaper than lighting each of them with its own three by three neighbourhood.
 * The reason it should be is that reading the block states of a chunk and building its opacity
 * tables is the expensive part of the whole operation, and a per-chunk neighbourhood reads every
 * chunk up to nine times while an area reads each of its chunks exactly once.
 * </p>
 * <p>
 * If the claim does not hold, the simpler design — one chunk at a time — is the better one and area
 * forming should be removed rather than tuned until the benchmark agrees. That is the entire point
 * of this class: it is a decision, not a report.
 * </p>
 * <p>
 * Both sides run on the same chunks and write into them, which is what the real code does. The
 * chunks are rebuilt per iteration so neither side benefits from the light the other one left
 * behind.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class AreaVsPerChunkBenchmark {

    /**
     * The seed of the block layout, so every run measures the same world.
     */
    private static final int SEED = 20260731;

    /**
     * The share of blocks which are solid, in percent.
     */
    private static final int OCCLUSION_PERCENT = 30;

    /**
     * The amount of light sources placed into every chunk.
     */
    private static final int SOURCES_PER_CHUNK = 4;

    /**
     * The amount of connected chunks which are lit together.
     */
    @Param({"1", "4", "9", "16"})
    private int chunkCount;

    private Instance instance;
    private ChunkLightService service;
    private ChunkLightArea area;
    private List<ChunkArea> positions;

    /**
     * Starts the server once so the block registry is available and builds the connected square of
     * chunks both sides are measured on.
     */
    @Setup(Level.Trial)
    public void setUp() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }

        this.service = new ChunkLightService();
        this.area = new ChunkLightArea(this.service);
        this.instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        this.positions = new ArrayList<>(this.chunkCount);

        int edge = (int) Math.round(Math.sqrt(this.chunkCount));
        Random random = new Random(SEED);

        for (int index = 0; index < this.chunkCount; index++) {
            int chunkX = index % edge;
            int chunkZ = index / edge;

            fill(this.instance.loadChunk(chunkX, chunkZ).join(), random);
            this.positions.add(new ChunkArea(chunkX, chunkZ));
        }
    }

    /**
     * Puts solid blocks and light sources into the given chunk.
     *
     * @param chunk  the chunk to fill
     * @param random the source of the layout
     */
    private static void fill(Chunk chunk, Random random) {
        chunk.lockWriteLock();
        try {
            for (int y = 32; y < 48; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (random.nextInt(100) < OCCLUSION_PERCENT) {
                            chunk.setBlock(x, y, z, Block.STONE);
                        }
                    }
                }
            }

            for (int placed = 0; placed < SOURCES_PER_CHUNK; placed++) {
                chunk.setBlock(random.nextInt(16), 32 + random.nextInt(16), random.nextInt(16), Block.GLOWSTONE);
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Measures one area computation over the whole square.
     *
     * @return the chunks whose light was written
     */
    @Benchmark
    public List<ChunkArea> area() {
        return this.area.compute(this.instance, this.positions, false);
    }

    /**
     * Measures one neighbourhood computation per chunk of the square.
     *
     * @return the amount of chunks that were lit
     */
    @Benchmark
    public int perChunk() {
        for (ChunkArea position : this.positions) {
            this.service.calculateWithNeighbours(this.instance, position.x(), position.z());
        }
        return this.positions.size();
    }
}
