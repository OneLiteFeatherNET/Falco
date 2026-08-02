package net.onelitefeather.falco.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what a block write through {@link FalcoInstance} does, before the code that does it moves.
 * <p>
 * Every case here covers a path that had no test at all when this class was written: the placement
 * entry point, the break entry point, the neighbour update that follows a write, the recursion guard
 * that keeps a handler from destroying its own block forever, and the change timestamp. The plan of
 * stage 3 moves all of them into {@code BlockWriter}, and a move can only be checked against
 * behaviour somebody wrote down first.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("A block write through a Falco instance")
class FalcoInstanceBlockWriteTest {

    /**
     * The height every case writes at, well inside the overworld and away from both limits.
     */
    private static final int Y = 64;

    /**
     * A handler which counts how often a block carrying it reached a chunk, and may write back.
     * <p>
     * Counting the calls of {@code onPlace} rather than reading the block back is what makes the
     * recursion guard observable at all: the guard drops a second write of the same block to the same
     * position, and a dropped write leaves the chunk holding exactly what a performed write would have
     * left it holding. Only the number of times the chunk was actually written tells the two apart.
     * </p>
     *
     * @param writes the counter raised once per write which reached the chunk
     * @param echo   what the handler does with the placement it was told about, null to do nothing
     */
    private record CountingHandler(AtomicInteger writes,
                                   @Nullable Consumer<BlockHandler.Placement> echo) implements BlockHandler {

        @Override
        public void onPlace(Placement placement) {
            this.writes.incrementAndGet();
            if (this.echo != null) this.echo.accept(placement);
        }

        @Override
        public Key getKey() {
            return Key.key("falco", "counting");
        }
    }

    /**
     * Creates a registered instance in the environment of the test.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("places a block through placeBlock and reports that it did")
    void testPlaceBlockWritesTheBlock(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();

        final boolean placed = instance.placeBlock(new BlockHandler.Placement(Block.STONE, Block.AIR,
                instance, new BlockVec(1, Y, 1)), true);

        assertTrue(placed, "a loaded chunk accepts a placement");
        assertEquals(Block.STONE, instance.getBlock(1, Y, 1));
    }

    @Test
    @DisplayName("refuses a placement into a chunk which is not loaded")
    void testPlaceBlockRefusesAnUnloadedChunk(Env env) {
        final FalcoInstance instance = registered(env);

        final boolean placed = instance.placeBlock(new BlockHandler.Placement(Block.STONE, Block.AIR,
                instance, new BlockVec(1, Y, 1)), true);

        assertFalse(placed, "there is no chunk at that position, so nothing can be placed");
    }

    @Test
    @DisplayName("breaks a block, replaces it with what the event decided and tells the viewers")
    void testBreakBlockReplacesTheBlock(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        instance.setBlock(1, Y, 1, Block.STONE);
        final var connection = env.createConnection();
        final var player = connection.connect(instance, new Pos(0, Y, 0));

        final boolean broken = instance.breakBlock(player, new BlockVec(1, Y, 1), BlockFace.TOP, true);

        assertTrue(broken, "a solid block in a loaded chunk can be broken");
        assertEquals(Block.AIR, instance.getBlock(1, Y, 1));
    }

    @Test
    @DisplayName("refuses to break air and does not pretend it broke something")
    void testBreakBlockRefusesAir(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final var connection = env.createConnection();
        final var player = connection.connect(instance, new Pos(0, Y, 0));

        assertFalse(instance.breakBlock(player, new BlockVec(1, Y, 1), BlockFace.TOP, true),
                "there is no block there, so the client is resent the chunk instead");
    }

    @Test
    @DisplayName("lets a placement rule reshape the neighbour of a written block")
    void testANeighbourReshapesItself(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final AtomicInteger updates = new AtomicInteger();
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new BlockPlacementRule(Block.GLASS) {

            @Override
            public Block blockUpdate(UpdateState state) {
                updates.incrementAndGet();
                return Block.GLOWSTONE;
            }

            @Override
            public Block blockPlace(PlacementState state) {
                return state.block();
            }
        });
        instance.setBlock(2, Y, 1, Block.GLASS);

        instance.setBlock(1, Y, 1, Block.STONE, true);

        assertTrue(updates.get() > 0, "the neighbour of the written block has to be asked to reshape itself");
        assertEquals(Block.GLOWSTONE, instance.getBlock(2, Y, 1),
                "what the rule returned has to end up in the chunk");
    }

    @Test
    @DisplayName("does not run neighbour updates when the caller switched them off")
    void testNeighbourUpdatesCanBeSwitchedOff(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final AtomicInteger updates = new AtomicInteger();
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new BlockPlacementRule(Block.OAK_LEAVES) {

            @Override
            public Block blockUpdate(UpdateState state) {
                updates.incrementAndGet();
                return state.currentBlock();
            }

            @Override
            public Block blockPlace(PlacementState state) {
                return state.block();
            }
        });
        instance.setBlock(4, Y, 1, Block.OAK_LEAVES);

        instance.setBlock(3, Y, 1, Block.STONE, false);

        assertEquals(0, updates.get(), "doBlockUpdates=false has to skip the neighbour pass entirely");
    }

    @Test
    @DisplayName("stops a handler which writes its own block again from recursing")
    void testTheRecursionGuardHolds(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final AtomicInteger writes = new AtomicInteger();
        final Block looping = Block.STONE.withHandler(new CountingHandler(writes,
                placement -> instance.setBlock(placement.getBlockPosition(), placement.getBlock())));

        instance.setBlock(5, Y, 5, looping);

        assertEquals(1, writes.get(),
                "the second write of the same block to the same position has to be dropped by the guard");
    }

    @Test
    @DisplayName("lets the same block be written again after the tick which cleared the guard")
    void testTheGuardIsClearedByATick(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final AtomicInteger writes = new AtomicInteger();
        final Block counted = Block.STONE.withHandler(new CountingHandler(writes, null));

        instance.setBlock(6, Y, 6, counted);
        instance.setBlock(6, Y, 6, counted);
        assertEquals(1, writes.get(), "the same block at the same position reaches the chunk once per tick");

        instance.tick(System.currentTimeMillis());
        instance.setBlock(6, Y, 6, counted);

        assertEquals(2, writes.get(),
                "the guard is scoped to one tick, so the same block can be written again afterwards");
    }

    @Test
    @DisplayName("moves the last change time when a block is written")
    void testTheChangeTimeMoves(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final long before = instance.getLastBlockChangeTime();

        instance.setBlock(7, Y, 7, Block.STONE);

        assertNotEquals(before, instance.getLastBlockChangeTime(),
                "a block write has to move the timestamp the batches read");
    }

    @Test
    @DisplayName("loads the chunk a write lands in when auto chunk load is on")
    void testAWriteLoadsItsChunk(Env env) {
        final FalcoInstance instance = registered(env);

        instance.setBlock(600, Y, 600, Block.STONE);

        final Chunk chunk = instance.getChunkAt(600, 600);
        assertTrue(chunk != null && chunk.isLoaded(), "the write has to have brought its chunk into the world");
        assertEquals(Block.STONE, instance.getBlock(600, Y, 600));
    }
}
