package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down when {@link ChunkLightArea} reuses the opacity tables of a chunk and when it must not.
 * <p>
 * These are counts rather than timings, and that is the point of having them. Whether the reuse is
 * worth anything is a question for a benchmark and depends on the machine; whether it <em>happens</em>
 * is exact, and so is whether it stops happening when the blocks of a chunk move. The second is the
 * one that can go wrong silently: a table which outlives the blocks it describes produces light that
 * is written with the update flag of the section cleared, so nothing ever corrects it.
 * </p>
 * <p>
 * That the light itself stays right is not asserted here. {@link IncrementalLightUpdateTest} already
 * compares the incremental path against a full recalculation position by position across a sequence
 * of changes, which is a far stronger statement than anything this class could add — deliberately
 * breaking the invalidation turns four of its six tests red.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class OpacityTableReuseTest {

    /**
     * A world of three by three chunks, so a one chunk area has a full ring around it.
     */
    private static final int WORLD_EDGE = 3;

    /**
     * The chunk every test lights, in the middle of the world.
     */
    private static final ChunkArea MIDDLE = new ChunkArea(1, 1);

    /**
     * Reports every chunk as not waiting for light, which is what a settled world looks like.
     */
    private static final ToLongFunction<ChunkArea> SETTLED = _ -> ChunkLightArea.CLEAN;

    /**
     * Loads a square world and puts one source into the middle chunk.
     *
     * @param instance the instance which receives the chunks
     * @return the loaded chunks
     */
    private static List<Chunk> world(Instance instance) {
        List<Chunk> chunks = new ArrayList<>(WORLD_EDGE * WORLD_EDGE);

        for (int x = 0; x < WORLD_EDGE; x++) {
            for (int z = 0; z < WORLD_EDGE; z++) {
                chunks.add(instance.loadChunk(x, z).join());
            }
        }
        place(instance, 8 + MIDDLE.x() * 16, 40, 8 + MIDDLE.z() * 16, Block.GLOWSTONE);
        return chunks;
    }

    /**
     * Places a block while holding the write lock of its chunk.
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

    @Test
    void testASecondPassOverAnUnchangedAreaBuildsNoTableAtAll(Env env) {
        Instance instance = env.createEmptyInstance();
        world(instance);

        ChunkLightArea area = new ChunkLightArea(new ChunkLightService());
        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        long afterFirst = area.tableBuilds();

        assertEquals(9, afterFirst, "the first pass reads the chunk, its ring and its diagonals");

        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        assertEquals(afterFirst, area.tableBuilds(), "nothing moved, so the second pass builds none");
    }

    @Test
    void testTheSkyPassReusesTheTablesTheBlockPassBuilt(Env env) {
        Instance instance = env.createEmptyInstance();
        world(instance);

        ChunkLightArea area = new ChunkLightArea(new ChunkLightService());
        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        long afterBlock = area.tableBuilds();

        area.computeIncrementally(instance, List.of(MIDDLE), true, SETTLED);

        assertEquals(afterBlock, area.tableBuilds(),
                "a table describes the blocks of a section, not a kind of light");
    }

    @Test
    void testAReportedChangeRebuildsTheTablesOfThatChunkOnly(Env env) {
        Instance instance = env.createEmptyInstance();
        world(instance);

        ChunkLightArea area = new ChunkLightArea(new ChunkLightService());
        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        long afterFirst = area.tableBuilds();

        place(instance, 8 + MIDDLE.x() * 16, 41, 8 + MIDDLE.z() * 16, Block.STONE);
        area.recordChange(MIDDLE, 8, 41, 8);
        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        assertEquals(afterFirst + 1, area.tableBuilds(),
                "the changed chunk is read again and the eight around it are not");
    }

    @Test
    void testAForgottenChunkRebuildsItsTables(Env env) {
        Instance instance = env.createEmptyInstance();
        world(instance);

        ChunkLightArea area = new ChunkLightArea(new ChunkLightService());
        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        long afterFirst = area.tableBuilds();

        area.forget(MIDDLE);
        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        assertEquals(afterFirst + 1, area.tableBuilds(),
                "a caller which cannot say what changed gets the tables rebuilt");
    }

    @Test
    void testAChangeInARingChunkIsSeenByTheAreaThatBordersIt(Env env) {
        Instance instance = env.createEmptyInstance();
        world(instance);

        ChunkArea ring = new ChunkArea(MIDDLE.x() + 1, MIDDLE.z());
        ChunkLightArea area = new ChunkLightArea(new ChunkLightService());
        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        long afterFirst = area.tableBuilds();

        place(instance, 8 + ring.x() * 16, 41, 8 + ring.z() * 16, Block.STONE);
        area.recordChange(ring, 8, 41, 8);
        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        assertEquals(afterFirst + 1, area.tableBuilds(),
                "the ring is read on every pass, so its tables have to follow its blocks too");
    }

    @Test
    void testTheRecalculatingPathKeepsNoTables(Env env) {
        Instance instance = env.createEmptyInstance();
        world(instance);

        ChunkLightArea area = new ChunkLightArea(new ChunkLightService());
        area.compute(instance, List.of(MIDDLE), false);

        long afterFirst = area.tableBuilds();

        area.compute(instance, List.of(MIDDLE), false);

        assertEquals(afterFirst * 2, area.tableBuilds(),
                "compute is documented as keeping nothing, and a caller with no revisions to offer "
                        + "has reported no changes either");
    }

    @Test
    void testTablesAreDroppedWithTheLightWhenTheBoundIsReached(Env env) {
        Instance instance = env.createEmptyInstance();
        world(instance);

        ChunkLightArea area = new ChunkLightArea(new ChunkLightService(), 0);
        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        long afterFirst = area.tableBuilds();

        area.computeIncrementally(instance, List.of(MIDDLE), false, SETTLED);

        assertTrue(area.tableBuilds() > afterFirst,
                "a bound of zero keeps no light and therefore no tables either");
    }
}
