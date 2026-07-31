package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down that lighting a changed block incrementally reaches exactly the result a full
 * recalculation reaches.
 * <p>
 * The cheap direction of an incremental light is spreading brightness, and a test which only ever
 * places a source proves nothing: light that is added never has to be taken back. The hard direction
 * is the one where light <em>disappears</em> — a source that is removed, or a block that starts
 * blocking one — because the brightness it had spread is still stored in every position around it
 * and a second spreading would keep that glow forever. The sequences below therefore remove as often
 * as they place, and every single step is compared against a recalculation from the block states,
 * position by position and for both kinds of light.
 * </p>
 * <p>
 * The comparison is against {@link ChunkLightArea} rather than against a handwritten expectation on
 * purpose. The area computation is the definition of what the light of a group of chunks is, so a
 * difference between the two is a defect of the incremental path by construction, whatever the
 * expected brightness of any single block might be.
 * </p>
 * <p>
 * Every change is made in the middle chunk of a five by five world. That keeps the group of chunks
 * the scheduler forms identical to the group the reference is asked for — the three by three around
 * the middle — while the ring around that group is fully loaded, so the border exchange has real
 * neighbours on all four sides rather than the empty edge a smaller world would give it.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class IncrementalLightUpdateTest {

    /**
     * Runs every task on the calling thread, so a pass is finished when onTick returns.
     */
    private static final Executor DIRECT = Runnable::run;

    /**
     * The seed of the world and of the change sequences, so a failure can be reproduced.
     */
    private static final long SEED = 20260731L;

    /**
     * The edge length of the world, in chunks.
     */
    private static final int WORLD_EDGE = 5;

    /**
     * The chunk coordinate of the middle chunk, which is the only one that is ever changed.
     */
    private static final int MIDDLE = WORLD_EDGE / 2;

    /**
     * An area cap which never splits the dirty set of this world, so one tick forms one area.
     */
    private static final int AREA_SIZE = 64;

    /**
     * The height of the ceiling every chunk is covered with.
     */
    private static final int CEILING = 48;

    /**
     * The lowest height a block of the world is placed at.
     */
    private static final int FLOOR = 32;

    /**
     * The amount of changes a sequence applies.
     */
    private static final int CHANGES = 48;

    @Test
    void testASecondPassAfterOneBlockLightsNoChunkFromScratch(Env env) {
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, DIRECT, AREA_SIZE);
        instance.setChunkSupplier(scheduler.supplier());

        List<Chunk> chunks = world(instance);
        chunks.getFirst().tick(1L);

        long lit = scheduler.fullPropagations();
        assertTrue(lit > 0, "the first pass has to light the world from scratch");

        place(instance, MIDDLE * 16 + 8, 40, MIDDLE * 16 + 8, Block.GLOWSTONE);
        chunks.getFirst().tick(2L);

        assertEquals(lit, scheduler.fullPropagations(),
                "a single changed block must not make any chunk be lit from scratch again");
        assertFalse(scheduler.isDirty(MIDDLE, MIDDLE), "and the changed chunk still has to be finished");
    }

    @Test
    void testAnUnknownChangeStillLightsTheChunkFromScratch(Env env) {
        // markDirty says that a chunk changed without saying where, which is the case the
        // incremental path cannot follow. It has to fall back rather than trust a stale light.
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, DIRECT, AREA_SIZE);
        instance.setChunkSupplier(scheduler.supplier());

        List<Chunk> chunks = world(instance);
        chunks.getFirst().tick(1L);

        long lit = scheduler.fullPropagations();
        scheduler.markDirty(instance, MIDDLE, MIDDLE);
        chunks.getFirst().tick(2L);

        assertTrue(scheduler.fullPropagations() > lit, "an unplaceable change has to be recalculated");
    }

    @Test
    void testTheIncrementalResultMatchesAFullRecalculationAfterASequenceOfChanges(Env env) {
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, DIRECT, AREA_SIZE);
        instance.setChunkSupplier(scheduler.supplier());

        List<Chunk> chunks = world(instance);
        chunks.getFirst().tick(1L);

        Random random = new Random(SEED);

        for (int change = 0; change < CHANGES; change++) {
            int x = MIDDLE * 16 + random.nextInt(16);
            int y = FLOOR + random.nextInt(CEILING - FLOOR + 1);
            int z = MIDDLE * 16 + random.nextInt(16);

            place(instance, x, y, z, blockOf(random));
            chunks.getFirst().tick(2L + change);

            assertMatchesFullRecalculation(instance, "after change " + change + " at " + x + "/" + y + "/" + z);
        }
    }

    @Test
    void testRemovingALightSourceMatchesAFullRecalculation(Env env) {
        // The direction an incremental light gets wrong: the brightness of a source that is gone is
        // still stored around it, so it has to be retracted rather than merely not spread again.
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, DIRECT, AREA_SIZE);
        instance.setChunkSupplier(scheduler.supplier());

        List<Chunk> chunks = world(instance);
        chunks.getFirst().tick(1L);

        int x = MIDDLE * 16 + 15;
        int z = MIDDLE * 16 + 8;

        place(instance, x, 40, z, Block.GLOWSTONE);
        chunks.getFirst().tick(2L);

        assertEquals(15, service.blockLightAt(instance.getChunk(MIDDLE, MIDDLE), x, 40, z));
        assertTrue(service.blockLightAt(instance.getChunk(MIDDLE + 1, MIDDLE), x + 1, 40, z) > 0,
                "a source on the border has to light the chunk behind it");

        place(instance, x, 40, z, Block.AIR);
        chunks.getFirst().tick(3L);

        assertMatchesFullRecalculation(instance, "after the source was removed");
    }

    @Test
    void testABlockPlacedInFrontOfASourceMatchesAFullRecalculation(Env env) {
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, DIRECT, AREA_SIZE);
        instance.setChunkSupplier(scheduler.supplier());

        List<Chunk> chunks = world(instance);
        chunks.getFirst().tick(1L);

        int x = MIDDLE * 16 + 8;
        int z = MIDDLE * 16 + 8;

        place(instance, x, 40, z, Block.GLOWSTONE);
        chunks.getFirst().tick(2L);

        place(instance, x + 1, 40, z, Block.STONE);
        chunks.getFirst().tick(3L);

        assertMatchesFullRecalculation(instance, "after a block was placed next to the source");
    }

    @Test
    void testSeveralChangesInOneTickMatchAFullRecalculation(Env env) {
        // A batch, or simply a player breaking several blocks inside one tick, reaches the pass as a
        // list of positions rather than as one. They are applied against the block states the pass
        // reads, which is the state after all of them, so the order they arrived in must not matter.
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, DIRECT, AREA_SIZE);
        instance.setChunkSupplier(scheduler.supplier());

        List<Chunk> chunks = world(instance);
        chunks.getFirst().tick(1L);

        Random random = new Random(SEED);

        for (int round = 0; round < 4; round++) {
            for (int change = 0; change < 8; change++) {
                place(instance,
                        MIDDLE * 16 + random.nextInt(16),
                        FLOOR + random.nextInt(CEILING - FLOOR + 1),
                        MIDDLE * 16 + random.nextInt(16),
                        blockOf(random));
            }
            chunks.getFirst().tick(2L + round);
            assertMatchesFullRecalculation(instance, "after round " + round + " of eight changes");
        }
    }

    /**
     * Picks the block a change places, weighted so light is removed as often as it is added.
     *
     * @param random the source of the sequence
     * @return the block to place
     */
    private static Block blockOf(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> Block.GLOWSTONE;
            case 1 -> Block.STONE;
            default -> Block.AIR;
        };
    }

    /**
     * Loads the world every test runs on and fills it with a reproducible layout.
     *
     * @param instance the instance which receives the chunks
     * @return the loaded chunks
     */
    private static List<Chunk> world(Instance instance) {
        List<Chunk> chunks = new ArrayList<>(WORLD_EDGE * WORLD_EDGE);
        Random random = new Random(SEED);

        for (int chunkZ = 0; chunkZ < WORLD_EDGE; chunkZ++) {
            for (int chunkX = 0; chunkX < WORLD_EDGE; chunkX++) {
                chunks.add(instance.loadChunk(chunkX, chunkZ).join());
            }
        }

        for (Chunk chunk : chunks) {
            fill(chunk, random);
        }
        return chunks;
    }

    /**
     * Puts a ceiling, scattered solid blocks and a few sources into the given chunk.
     * <p>
     * The ceiling is what makes the sky light worth comparing: without it every column is open and
     * the whole world is uniformly bright, which any engine would agree on.
     * </p>
     *
     * @param chunk  the chunk to fill
     * @param random the source of the layout
     */
    private static void fill(Chunk chunk, Random random) {
        int baseX = chunk.getChunkX() * 16;
        int baseZ = chunk.getChunkZ() * 16;

        chunk.lockWriteLock();
        try {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    chunk.setBlock(baseX + x, CEILING, baseZ + z, Block.STONE);

                    for (int y = FLOOR; y < CEILING; y++) {
                        if (random.nextInt(100) < 25) {
                            chunk.setBlock(baseX + x, y, baseZ + z, Block.STONE);
                        }
                    }
                }
            }

            for (int placed = 0; placed < 4; placed++) {
                chunk.setBlock(baseX + random.nextInt(16), FLOOR + random.nextInt(CEILING - FLOOR),
                        baseZ + random.nextInt(16), Block.GLOWSTONE);
            }
            // One hole in the ceiling, so the sky reaches below it in exactly one column.
            chunk.setBlock(baseX + random.nextInt(16), CEILING, baseZ + random.nextInt(16), Block.AIR);
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Places a block through the instance, which is the path a server takes.
     *
     * @param instance the instance which holds the chunk
     * @param x        the x coordinate of the block
     * @param y        the y coordinate of the block
     * @param z        the z coordinate of the block
     * @param block    the block to place
     */
    private static void place(Instance instance, int x, int y, int z, Block block) {
        Chunk chunk = instance.getChunkAt(x, z);
        chunk.lockWriteLock();
        try {
            chunk.setBlock(x, y, z, block);
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Asserts that the light of the whole world is what a recalculation from the block states gives.
     * <p>
     * The stored light is read first, then the reference is computed over the very group of chunks
     * the scheduler forms for a change in the middle chunk, and the two are compared. The reference
     * writes its result into the same chunks, which is harmless: the incremental path keeps its own
     * light and never reads the sections back.
     * </p>
     *
     * @param instance the instance whose light is checked
     * @param message  what the assertion is about, for the failure message
     */
    private static void assertMatchesFullRecalculation(Instance instance, String message) {
        List<Layer> incremental = snapshot(instance);
        List<ChunkArea> group = new ArrayList<>(9);

        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                group.add(new ChunkArea(MIDDLE + offsetX, MIDDLE + offsetZ));
            }
        }

        ChunkLightArea reference = new ChunkLightArea(new ChunkLightService());
        reference.compute(instance, group, false);

        if (instance.getCachedDimensionType().hasSkylight()) {
            reference.compute(instance, group, true);
        }

        List<Layer> full = snapshot(instance);
        assertEquals(full.size(), incremental.size());

        for (int index = 0; index < full.size(); index++) {
            Layer expected = full.get(index);
            Layer actual = incremental.get(index);

            if (!Arrays.equals(expected.light(), actual.light())) {
                int differing = firstDifference(expected.light(), actual.light());
                assertEquals(
                        level(expected.light(), differing), level(actual.light(), differing),
                        message + ", " + expected.describe(differing)
                );
            }
        }
    }

    /**
     * Finds the first position two light arrays disagree on.
     *
     * @param expected the light a recalculation produced
     * @param actual   the light the incremental path produced
     * @return the position inside the section, or minus one if the arrays agree
     */
    private static int firstDifference(byte[] expected, byte[] actual) {
        for (int position = 0; position < LightNibbles.BLOCK_COUNT; position++) {
            if (level(expected, position) != level(actual, position)) {
                return position;
            }
        }
        return -1;
    }

    /**
     * Reads one level out of a packed light array.
     *
     * @param light    the packed light of a section
     * @param position the position inside the section
     * @return the level stored for that position
     */
    private static int level(byte[] light, int position) {
        int packed = light[position >> 1];
        return (position & 1) == 0 ? packed & 15 : (packed >> 4) & 15;
    }

    /**
     * Reads the stored light of every section of every chunk of the world.
     *
     * @param instance the instance to read
     * @return one layer per section and kind of light, in a fixed order
     */
    private static List<Layer> snapshot(Instance instance) {
        List<Layer> light = new ArrayList<>();

        for (int chunkZ = 0; chunkZ < WORLD_EDGE; chunkZ++) {
            for (int chunkX = 0; chunkX < WORLD_EDGE; chunkX++) {
                Chunk chunk = instance.getChunk(chunkX, chunkZ);
                chunk.lockReadLock();
                try {
                    List<Section> sections = chunk.getSections();

                    for (int index = 0; index < sections.size(); index++) {
                        Section section = sections.get(index);
                        int base = (chunk.getMinSection() + index) * LightNibbles.DIMENSION;

                        light.add(new Layer(chunkX, chunkZ, base, false, normalise(section.blockLight().array())));
                        light.add(new Layer(chunkX, chunkZ, base, true, normalise(section.skyLight().array())));
                    }
                } finally {
                    chunk.unlockReadLock();
                }
            }
        }
        return light;
    }

    /**
     * Expands the empty array an unlit section is stored as into a dark one.
     *
     * @param light the stored light array
     * @return an array of the regular length
     */
    private static byte[] normalise(byte[] light) {
        return light.length == LightNibbles.ARRAY_LENGTH ? light.clone() : new byte[LightNibbles.ARRAY_LENGTH];
    }

    /**
     * The {@link Layer} record holds the stored light of one section, with enough around it to name
     * a differing block in world coordinates.
     *
     * @param chunkX the chunk x coordinate the section belongs to
     * @param chunkZ the chunk z coordinate the section belongs to
     * @param baseY  the y coordinate of the lowest block of the section
     * @param sky    whether the layer holds the sky light instead of the block light
     * @param light  the packed light of the section
     */
    private record Layer(int chunkX, int chunkZ, int baseY, boolean sky, byte[] light) {

        /**
         * Names the block at the given position of this section in world coordinates.
         *
         * @param position the position inside the section
         * @return a description of which block and which kind of light differ
         */
        private String describe(int position) {
            return (this.sky ? "sky" : "block") + " light at "
                    + (this.chunkX * LightNibbles.DIMENSION + (position & 15)) + "/"
                    + (this.baseY + (position >> 8)) + "/"
                    + (this.chunkZ * LightNibbles.DIMENSION + ((position >> 4) & 15));
        }
    }
}
