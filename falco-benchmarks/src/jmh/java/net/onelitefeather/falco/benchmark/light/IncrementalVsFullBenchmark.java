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
import java.util.function.ToLongFunction;

/**
 * The {@link IncrementalVsFullBenchmark} class decides whether replaying a changed block earns its
 * complexity against simply lighting the chunks again.
 * <p>
 * A single block change marks the chunk it happened in and the eight around it, and the light of all
 * nine of them plus the ring around them is what one pass produces. The question this answers is
 * what that pass costs when the light of those chunks is already known and only one position has to
 * be replayed on it, against what it costs when every one of them is searched from its block states
 * — which is what the engine did before the kept light existed.
 * </p>
 * <p>
 * Both sides do exactly the same work apart from that: the same block is toggled between a light
 * source and air, the same nine chunks are computed, the same ring of sixteen chunks is read, and
 * the result is written into the same sections. The block is toggled rather than merely placed so
 * the world alternates between two states instead of drifting, and so both directions of an
 * incremental light — brightness added and brightness taken back — are measured rather than only the
 * easy one.
 * </p>
 * <p>
 * If the incremental side is not measurably cheaper, the kept light is memory spent for nothing and
 * should be removed rather than tuned until the benchmark agrees.
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
public class IncrementalVsFullBenchmark {

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
     * The edge length of the world, in chunks.
     * <p>
     * Five is the smallest world in which the nine chunks a change marks have a fully loaded ring on
     * all four sides, which is what a pass reads and therefore what has to be paid for.
     * </p>
     */
    private static final int WORLD_EDGE = 5;

    /**
     * The chunk which is changed, and the middle of the nine that are computed.
     */
    private static final int MIDDLE = WORLD_EDGE / 2;

    /**
     * The height the world is built at.
     */
    private static final int BASE_Y = 32;

    /**
     * A caller for whom nothing changed while the pass ran.
     */
    private static final ToLongFunction<ChunkArea> SETTLED = position -> ChunkLightArea.CLEAN;

    /**
     * Whether the sky light is measured instead of the block light. A tick pays for both.
     */
    @Param({"false", "true"})
    private boolean sky;

    private Instance instance;
    private Chunk middle;
    private ChunkLightArea incremental;
    private ChunkLightArea recalculating;
    private List<ChunkArea> group;
    private boolean lit;

    /**
     * Starts the server once so the block registry is available, builds the world and brings the
     * kept light of the incremental side up to date, which is the situation it is measured in.
     */
    @Setup(Level.Trial)
    public void setUp() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }

        ChunkLightService service = new ChunkLightService();
        this.instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        this.incremental = new ChunkLightArea(service);
        this.recalculating = new ChunkLightArea(service);
        this.group = new ArrayList<>(9);

        Random random = new Random(SEED);

        for (int chunkZ = 0; chunkZ < WORLD_EDGE; chunkZ++) {
            for (int chunkX = 0; chunkX < WORLD_EDGE; chunkX++) {
                fill(this.instance.loadChunk(chunkX, chunkZ).join(), random);
            }
        }

        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                this.group.add(new ChunkArea(MIDDLE + offsetX, MIDDLE + offsetZ));
            }
        }
        this.middle = this.instance.getChunk(MIDDLE, MIDDLE);

        // Every chunk of the area and of its ring has to be known before the first measured pass,
        // otherwise the first invocations would measure the fallback rather than the kept light.
        List<ChunkArea> everything = new ArrayList<>(WORLD_EDGE * WORLD_EDGE);

        for (int chunkZ = 0; chunkZ < WORLD_EDGE; chunkZ++) {
            for (int chunkX = 0; chunkX < WORLD_EDGE; chunkX++) {
                everything.add(new ChunkArea(chunkX, chunkZ));
            }
        }
        this.incremental.computeIncrementally(this.instance, everything, false, SETTLED);
        this.incremental.computeIncrementally(this.instance, everything, true, SETTLED);
    }

    /**
     * Puts solid blocks, light sources and a ceiling into the given chunk.
     *
     * @param chunk  the chunk to fill
     * @param random the source of the layout
     */
    private static void fill(Chunk chunk, Random random) {
        int baseX = chunk.getChunkX() * 16;
        int baseZ = chunk.getChunkZ() * 16;

        chunk.lockWriteLock();
        try {
            for (int y = BASE_Y; y < BASE_Y + 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (random.nextInt(100) < OCCLUSION_PERCENT) {
                            chunk.setBlock(baseX + x, y, baseZ + z, Block.STONE);
                        }
                    }
                }
            }

            for (int placed = 0; placed < SOURCES_PER_CHUNK; placed++) {
                chunk.setBlock(baseX + random.nextInt(16), BASE_Y + random.nextInt(16),
                        baseZ + random.nextInt(16), Block.GLOWSTONE);
            }

            // A ceiling, so the sky light of the measured area is not one uniform value.
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    chunk.setBlock(baseX + x, BASE_Y + 16, baseZ + z, Block.STONE);
                }
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Toggles the measured block between a light source and air.
     *
     * @return the position of the block inside the chunk column, as the light state indexes it
     */
    private int toggle() {
        this.lit = !this.lit;
        int x = 8;
        int y = BASE_Y + 8;
        int z = 8;

        this.middle.lockWriteLock();
        try {
            this.middle.setBlock(MIDDLE * 16 + x, y, MIDDLE * 16 + z, this.lit ? Block.GLOWSTONE : Block.AIR);
        } finally {
            this.middle.unlockWriteLock();
        }
        return y - this.middle.getMinSection() * 16;
    }

    /**
     * Measures one pass which replays the changed position on the light it already has.
     *
     * @return the chunks whose light was written
     */
    @Benchmark
    public List<ChunkArea> incremental() {
        int columnY = toggle();
        this.incremental.recordChange(new ChunkArea(MIDDLE, MIDDLE), 8, columnY, 8);
        return this.incremental.computeIncrementally(this.instance, this.group, this.sky, SETTLED);
    }

    /**
     * Measures one pass which lights every chunk of the area and of its ring from its block states.
     *
     * @return the chunks whose light was written
     */
    @Benchmark
    public List<ChunkArea> full() {
        toggle();
        return this.recalculating.compute(this.instance, this.group, this.sky);
    }
}
