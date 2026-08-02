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
 * The {@link AreaPassStageBenchmark} class answers one question the other light benchmarks do not:
 * how much of a pass is spent building opacity tables rather than propagating light.
 * <p>
 * <b>Why a second area benchmark exists.</b> {@code AreaVsPerChunkBenchmark} loads only the chunks
 * of the area, so the ring around it is absent and {@code read} skips it. That is the right shape
 * for the question it asks — area against per-chunk on identical work — and the wrong one here: the
 * ring is read once per pass and never written, so it is precisely the part a table cache would pay
 * for. A benchmark that leaves it out measures the case the cache does not target.
 * </p>
 * <p>
 * The ring is filled with the same layout as the area for the same reason.
 * {@code SectionOpacity.of} returns early for a section of one repeated state, so an empty ring
 * chunk costs almost nothing and would make the tables look free.
 * </p>
 * <p>
 * Both methods run over the identical set of chunks, so the ratio between them is the share of a
 * pass that a perfectly effective table cache could remove — an upper bound, since a cache still has
 * to look up, invalidate and evict.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.0.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class AreaPassStageBenchmark {

    /**
     * The seed of the block layout, so every run measures the same world.
     */
    private static final int SEED = 20260802;

    /**
     * The share of blocks which are solid, in percent.
     */
    private static final int OCCLUSION_PERCENT = 30;

    /**
     * The amount of light sources placed into every chunk.
     */
    private static final int SOURCES_PER_CHUNK = 4;

    /**
     * The amount of connected chunks the area holds.
     */
    @Param({"1", "4", "16"})
    private int chunkCount;

    private Instance instance;
    private ChunkLightService service;
    private ChunkLightArea area;
    private List<ChunkArea> areaPositions;
    private List<ChunkArea> passPositions;

    /**
     * Creates a new benchmark instance.
     */
    public AreaPassStageBenchmark() {
    }

    /**
     * Builds the square, loads the ring around it and fills both with the same layout.
     */
    @Setup(Level.Trial)
    public void setUp() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }

        this.service = new ChunkLightService();
        this.area = new ChunkLightArea(this.service);
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
        this.passPositions = List.copyOf(pass);

        Random random = new Random(SEED);

        for (ChunkArea position : this.passPositions) {
            fill(this.instance.loadChunk(position.x(), position.z()).join(), random);
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
     * Measures a whole pass over the area, with its ring present.
     *
     * @return the chunks whose light was written
     */
    @Benchmark
    public List<ChunkArea> wholePass() {
        return this.area.compute(this.instance, this.areaPositions, false);
    }

    /**
     * Measures only the opacity tables a pass builds, over the identical set of chunks.
     *
     * @return the amount of section tables that were built
     */
    @Benchmark
    public int opacityTablesOnly() {
        int sections = 0;

        for (ChunkArea position : this.passPositions) {
            Chunk chunk = this.instance.getChunk(position.x(), position.z());

            if (chunk != null) {
                sections += this.service.opacityOf(chunk).size();
            }
        }
        return sections;
    }
}
