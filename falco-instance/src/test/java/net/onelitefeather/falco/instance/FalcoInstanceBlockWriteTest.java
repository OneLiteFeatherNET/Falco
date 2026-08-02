package net.onelitefeather.falco.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * @version 1.1.0
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
     * A placement rule which keeps the state it was asked about and answers with a fixed block.
     * <p>
     * The rule of the placed block is the one branch of a write that reshapes the block before it
     * reaches the chunk, and it is the only caller of the state builder. Answering with a block that
     * differs from the placed one makes the branch observable in the chunk; keeping the state makes
     * observable what the builder put into it, which no assertion on the chunk could show.
     * </p>
     */
    private static final class RecordingRule extends BlockPlacementRule {

        /**
         * The state of the last placement this rule was asked about, null until it was asked.
         */
        private final AtomicReference<PlacementState> lastState = new AtomicReference<>();

        /**
         * What every placement is answered with, null to cancel the placement.
         */
        private final @Nullable Block result;

        /**
         * Creates a rule for a block.
         *
         * @param block  the block this rule answers for
         * @param result the block every placement is answered with, null to cancel it
         */
        private RecordingRule(Block block, @Nullable Block result) {
            super(block);
            this.result = result;
        }

        @Override
        public @Nullable Block blockPlace(PlacementState state) {
            this.lastState.set(state);
            return this.result;
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
    @DisplayName("writes what the placement rule of the placed block decided, not what was placed")
    void testAPlacementRuleDecidesTheWrittenBlock(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        MinecraftServer.getBlockManager().registerBlockPlacementRule(
                new RecordingRule(Block.SANDSTONE, Block.BRICKS));

        final boolean placed = instance.placeBlock(new BlockHandler.Placement(Block.SANDSTONE, Block.AIR,
                instance, new BlockVec(8, Y, 8)), true);

        assertTrue(placed, "a loaded chunk accepts a placement");
        assertEquals(Block.BRICKS, instance.getBlock(8, Y, 8),
                "the block the rule answered with is the one that has to reach the chunk");
    }

    @Test
    @DisplayName("leaves air behind when the placement rule cancels the placement")
    void testACancellingPlacementRuleLeavesAir(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        MinecraftServer.getBlockManager().registerBlockPlacementRule(
                new RecordingRule(Block.OAK_PLANKS, null));

        final boolean placed = instance.placeBlock(new BlockHandler.Placement(Block.OAK_PLANKS, Block.AIR,
                instance, new BlockVec(9, Y, 9)), true);

        assertTrue(placed, "the placement was carried out, the rule only decided what it carried");
        assertEquals(Block.AIR, instance.getBlock(9, Y, 9),
                "a rule which answers null cancels the placement, which leaves air rather than the placed block");
    }

    @Test
    @DisplayName("hands a rule the position and the block of a placement which had no player")
    void testAPlacementWithoutAPlayerCarriesNoPlayerState(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final RecordingRule rule = new RecordingRule(Block.COBBLESTONE, Block.COBBLESTONE);
        MinecraftServer.getBlockManager().registerBlockPlacementRule(rule);
        final BlockVec position = new BlockVec(10, Y, 10);

        instance.placeBlock(new BlockHandler.Placement(Block.COBBLESTONE, Block.AIR, instance, position), true);

        final BlockPlacementRule.PlacementState state = rule.lastState.get();
        assertNotNull(state, "the rule of the placed block has to be asked before the block is written");
        assertSame(instance, state.instance(), "the rule is asked about the instance the write goes to");
        assertEquals(Block.COBBLESTONE, state.block());
        assertEquals(position, state.placePosition());
        assertNull(state.blockFace(), "a placement without a player clicked no face");
        assertNull(state.cursorPosition(), "a placement without a player has no cursor");
        assertNull(state.playerPosition(), "a placement without a player has no player position");
        assertNull(state.usedItemStack(), "a placement without a player used no item");
        assertFalse(state.isPlayerShifting(), "a placement without a player is not shifting");
    }

    @Test
    @DisplayName("hands a rule the face, the cursor and the player of a placement which had one")
    void testAPlayerPlacementCarriesThePlayerIntoTheRule(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final RecordingRule rule = new RecordingRule(Block.MOSSY_COBBLESTONE, Block.SMOOTH_STONE);
        MinecraftServer.getBlockManager().registerBlockPlacementRule(rule);
        final var connection = env.createConnection();
        final var player = connection.connect(instance, new Pos(0, Y, 0));
        final ItemStack held = ItemStack.of(Material.DIAMOND);
        player.setItemInMainHand(held);
        player.setSneaking(true);
        final BlockVec position = new BlockVec(11, Y, 11);

        instance.placeBlock(new BlockHandler.PlayerPlacement(Block.MOSSY_COBBLESTONE, Block.AIR, instance, position,
                player, PlayerHand.MAIN, BlockFace.WEST, 0.25F, 0.5F, 0.75F), true);

        final BlockPlacementRule.PlacementState state = rule.lastState.get();
        assertNotNull(state, "the rule of the placed block has to be asked before the block is written");
        assertEquals(BlockFace.WEST, state.blockFace(), "the face the player clicked has to reach the rule");
        assertEquals(new Vec(0.25, 0.5, 0.75), state.cursorPosition(),
                "the cursor of the player has to reach the rule");
        assertEquals(player.getPosition(), state.playerPosition(),
                "the position of the player has to reach the rule");
        assertEquals(held, state.usedItemStack(),
                "the item in the hand the placement names has to reach the rule");
        assertTrue(state.isPlayerShifting(), "a sneaking player has to reach the rule as shifting");
        assertEquals(Block.SMOOTH_STONE, instance.getBlock(position),
                "the block the rule answered with is the one that has to reach the chunk");
    }

    @Test
    @DisplayName("breaks a block and leaves air where it stood")
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
