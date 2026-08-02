package net.onelitefeather.falco.instance;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.instance.InstanceBlockUpdateEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.Block.Getter.Condition;
import net.minestom.server.instance.block.BlockEntityType;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.BlockEntityDataPacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.utils.PacketSendingUtils;
import net.minestom.server.utils.block.BlockUtils;
import net.minestom.server.utils.chunk.ChunkCache;
import net.minestom.server.world.DimensionType;
import net.minestom.server.worldevent.WorldEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link BlockWriter} class is everything a {@link FalcoInstance} does when a block changes: the
 * three entry points, the write itself, the neighbours it wakes, the packets it sends and the event
 * it announces.
 *
 * <h2>What is held while what runs</h2>
 * <p>
 * No lock of the instance is taken at all; the only lock in a write is the write lock of the one
 * chunk that receives the block. The container of Minestom instead takes its own monitor around the
 * whole of this, which turns every block write in the world into a queue behind every other one, and
 * two writes to two chunks have no reason to wait for each other. That is NFR-006, and it is a
 * property of the <em>ordering</em> of this class rather than of any one statement in it.
 * </p>
 * <p>
 * The chunk lock is taken before the placement rule is asked and given back after the block reached
 * the storage. Three pieces of foreign code therefore run <em>under</em> it, and it is worth naming
 * them rather than pretending otherwise: {@code BlockPlacementRule#blockPlace} of the placed block,
 * and — inside {@link FalcoChunk#setBlock(int, int, int, Block, BlockHandler.Placement,
 * BlockHandler.Destroy)}, which requires that very lock — {@code BlockHandler#onDestroy} of the block
 * that was replaced and {@code BlockHandler#onPlace} of the one that replaced it. They are inside
 * because they are part of deciding and recording what the block <em>is</em>: a rule that runs after
 * the write would be answering about a block already written, and a handler that runs after the lock
 * was given back could be told about a block a second writer has since overwritten.
 * </p>
 * <p>
 * Three further steps run <em>outside</em> it, and this is where the ordering is deliberate: the
 * neighbour pass, the two packets and {@code InstanceBlockUpdateEvent} all happen after the lock was
 * given back. Each of them reaches code this module does not own as well — a rule reshaping a
 * neighbour, a viewer, an arbitrary listener — but none of them is needed to establish the block, so
 * none of them has a reason to hold a chunk lock while it runs. The neighbour pass is the sharpest
 * case: a neighbour usually lives in another chunk and takes that chunk's lock on the way, so running
 * it inside would mean holding two chunk locks at once, in an order two concurrent writes can
 * disagree about.
 * </p>
 *
 * <h2>The hazard that the handlers leave standing</h2>
 * <p>
 * What is inside the lock is not free of that same problem, and no amount of ordering in this class
 * removes it: a {@code BlockHandler#onPlace} or {@code #onDestroy} that writes a block in
 * <em>another</em> chunk re-enters {@link #write} and takes a second chunk write lock while the first
 * one is still held. Two such writes started in opposite chunk order on two threads deadlock each
 * other. This is a real hazard of this design and not a theoretical one; it is simply the price of not
 * having an instance-wide monitor, which is exactly what {@code InstanceContainer} pays for by
 * serialising every block write in the world. It is inherited behaviour, unchanged from before this
 * class existed, and it is written here so that the next person to touch the lock finds it stated
 * rather than has to derive it.
 * </p>
 * <p>
 * The class exists so that ordering is a thing somebody can look at. It used to be the tail of one
 * {@code private} method of a class of more than 1 300 lines, where moving a single
 * {@code unlockWriteLock()} one line down would have undone the measurement of stage 1 without
 * failing anything.
 * </p>
 *
 * <h2>Why the write takes a {@link FalcoChunk}</h2>
 * <p>
 * Because the block setter carrying a placement and a destruction is {@code protected} on
 * {@code Chunk} and only widened to public by {@code DynamicChunk}. That is a lifecycle barrier of the
 * same kind as the two hooks {@link FalcoChunk} re-exposes, and it is answered the same way;
 * {@link FalcoChunk#require(Chunk)} is where the check lives, shared with {@link FalcoInstance}.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class BlockWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockWriter.class);

    /**
     * The faces a block change offers to its neighbours for a placement rule update.
     */
    private static final BlockFace[] BLOCK_UPDATE_FACES = {
            BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.BOTTOM, BlockFace.TOP
    };

    /**
     * The instance the written blocks belong to.
     * <p>
     * Reached for the chunk map, the dimension and the events. Everything else this class needs it
     * carries itself.
     * </p>
     */
    private final FalcoInstance owner;

    /**
     * The blocks changed since the last end of tick, used to break recursion between block handlers.
     * <p>
     * Concurrent rather than a plain map behind a lock that guarded nothing, which is the shape the
     * container has.
     * </p>
     */
    private final Map<BlockVec, Block> currentlyChangingBlocks = new ConcurrentHashMap<>();

    /**
     * When the last block of the instance changed, in nanoseconds of an arbitrary origin.
     * <p>
     * Volatile because a block write and a reader of the timestamp are rarely the same thread, and a
     * stale read here is a batch which believes nothing has changed.
     * </p>
     */
    private volatile long lastBlockChangeTime = System.nanoTime();

    /**
     * Creates a block writer for an instance.
     *
     * @param owner the instance the written blocks belong to
     */
    public BlockWriter(FalcoInstance owner) {
        this.owner = Objects.requireNonNull(owner, "the owner of a block writer cannot be null");
    }

    /**
     * Writes a block, loading its chunk first if that is allowed.
     *
     * @param x              the block X
     * @param y              the block Y
     * @param z              the block Z
     * @param block          the block to write
     * @param doBlockUpdates true to let the neighbours of the block reshape themselves
     * @throws IllegalStateException if the chunk is not loaded and auto chunk load is off
     */
    public void setBlock(int x, int y, int z, Block block, boolean doBlockUpdates) {
        Chunk chunk = this.owner.getChunkAt(x, z);
        if (chunk == null) {
            if (!this.owner.lifecycle().autoLoad()) {
                throw new IllegalStateException(
                        "tried to set a block in the unloaded chunk " + CoordConversion.globalToChunk(x)
                                + ":" + CoordConversion.globalToChunk(z) + " while auto chunk load is disabled");
            }
            chunk = this.owner.loadChunk(CoordConversion.globalToChunk(x), CoordConversion.globalToChunk(z)).join();
        }
        if (chunk.isLoaded()) write(FalcoChunk.require(chunk), x, y, z, block, null, null, doBlockUpdates, 0);
    }

    /**
     * Carries out a placement.
     *
     * @param placement      the placement to carry out
     * @param doBlockUpdates true to let the neighbours of the placed block reshape themselves
     * @return true if the placement reached a chunk, false if there is no loaded chunk at its position
     */
    public boolean placeBlock(BlockHandler.Placement placement, boolean doBlockUpdates) {
        final Point blockPosition = placement.getBlockPosition();
        final Chunk chunk = this.owner.getChunkAt(blockPosition);
        if (chunk == null || !chunk.isLoaded()) return false;
        write(FalcoChunk.require(chunk), blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ(),
                placement.getBlock(), placement, null, doBlockUpdates, 0);
        return true;
    }

    /**
     * Lets a player break a block.
     *
     * @param player         the player who broke the block
     * @param blockPosition  the position of the broken block
     * @param blockFace      the face the player broke the block from
     * @param doBlockUpdates true to let the neighbours of the broken block reshape themselves
     * @return true if a block was broken, false if there is nothing to break or the event was cancelled
     */
    public boolean breakBlock(Player player, Point blockPosition, BlockFace blockFace, boolean doBlockUpdates) {
        final Chunk chunk = this.owner.getChunkAt(blockPosition);
        if (chunk == null || !chunk.isLoaded() || chunk.isReadOnly()) return false;

        final Block block = this.owner.getBlock(blockPosition);
        if (block.isAir()) {
            // The client believes there is a block here; hand it the chunk it actually has.
            chunk.sendChunk(player);
            return false;
        }
        final PlayerBlockBreakEvent event = new PlayerBlockBreakEvent(player, this.owner, block, Block.AIR,
                blockPosition.asBlockVec(), blockFace);
        EventDispatcher.call(event);
        if (event.isCancelled()) return false;

        final Block resultBlock = event.getResultBlock();
        write(FalcoChunk.require(chunk), blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ(), resultBlock,
                null, new BlockHandler.PlayerDestroy(block, resultBlock, this.owner, blockPosition, player),
                doBlockUpdates, 0);
        PacketSendingUtils.sendGroupedPacket(chunk.getViewers(),
                new WorldEventPacket(WorldEvent.PARTICLES_DESTROY_BLOCK.id(), blockPosition, block.stateId(), false),
                // The breaking player already played the effect locally.
                viewer -> !viewer.equals(player));
        return true;
    }

    /**
     * Writes a block into a chunk and tells everyone who needs to know.
     * <p>
     * The write lock of the given chunk is the only lock taken, and it is held from the placement rule
     * to the end of {@link FalcoChunk#setBlock(int, int, int, Block, BlockHandler.Placement,
     * BlockHandler.Destroy)} — which means across the rule and across the block handlers of the old and
     * the new block. The neighbour pass, the packets and the event follow it with no lock held. What
     * that buys, and the re-entrancy hazard the handlers leave standing, is the subject of the class
     * documentation.
     * </p>
     * <p>
     * The chunk is taken rather than looked up, which is what makes this reachable one write at a
     * time: the caller decides which chunk receives the block, so a write can be driven against a
     * chunk that no instance ever published.
     * </p>
     *
     * @param chunk          the chunk which receives the block, has to be loaded
     * @param x              the block X
     * @param y              the block Y
     * @param z              the block Z
     * @param block          the block to write
     * @param placement      the placement which caused the write, null if it was not a placement
     * @param destroy        the destruction which caused the write, null if it was not a break
     * @param doBlockUpdates true to let the neighbours of the block reshape themselves
     * @param updateDistance how many neighbour updates deep this write already is
     */
    public void write(FalcoChunk chunk, int x, int y, int z, Block block,
                      @Nullable BlockHandler.Placement placement, @Nullable BlockHandler.Destroy destroy,
                      boolean doBlockUpdates, int updateDistance) {
        if (chunk.isReadOnly()) return;
        final DimensionType dimension = this.owner.getCachedDimensionType();
        if (y >= dimension.maxY() || y < dimension.minY()) {
            LOGGER.warn("tried to set a block outside the world bounds, should be within [{}, {}): {}",
                    dimension.minY(), dimension.maxY(), y);
            return;
        }
        final BlockVec blockPosition = new BlockVec(x, y, z);
        // A handler which destroys its own block would otherwise recurse until the stack ends.
        if (Objects.equals(this.currentlyChangingBlocks.get(blockPosition), block)) return;
        this.currentlyChangingBlocks.put(blockPosition, block);

        Block placed = block;
        chunk.lockWriteLock();
        try {
            this.lastBlockChangeTime = System.nanoTime();
            final BlockPlacementRule rule = MinecraftServer.getBlockManager().getBlockPlacementRule(placed);
            if (placement != null && rule != null && doBlockUpdates) {
                placed = Objects.requireNonNullElse(rule.blockPlace(placementState(placement, placed, blockPosition)), Block.AIR);
            }
            chunk.setBlock(x, y, z, placed, placement, destroy);
        } finally {
            chunk.unlockWriteLock();
        }

        // Outside the chunk lock on purpose: a neighbour may live in another chunk, and taking a
        // second chunk lock while holding the first is how two block writes deadlock each other.
        if (doBlockUpdates) updateNeighbours(blockPosition, updateDistance);

        chunk.sendPacketToViewers(new BlockChangePacket(blockPosition, placed.stateId()));
        final BlockEntityType blockEntityType = placed.registry().blockEntityType();
        if (blockEntityType != null) {
            final CompoundBinaryTag data = BlockUtils.extractClientNbt(placed);
            chunk.sendPacketToViewers(new BlockEntityDataPacket(blockPosition, blockEntityType, data));
        }
        EventDispatcher.call(new InstanceBlockUpdateEvent(this.owner, blockPosition, placed));
    }

    /**
     * Builds the state a placement rule is asked about.
     *
     * @param placement     the placement which caused the write
     * @param block         the block which is about to be placed
     * @param blockPosition the position the block goes to
     * @return the state to hand to {@code BlockPlacementRule#blockPlace}
     */
    @Contract("_, _, _ -> new")
    private BlockPlacementRule.PlacementState placementState(BlockHandler.Placement placement, Block block,
                                                             Point blockPosition) {
        if (placement instanceof BlockHandler.PlayerPlacement playerPlacement) {
            final Player player = playerPlacement.getPlayer();
            return new BlockPlacementRule.PlacementState(this.owner, block, playerPlacement.getBlockFace(), blockPosition,
                    new Vec(playerPlacement.getCursorX(), playerPlacement.getCursorY(), playerPlacement.getCursorZ()),
                    player.getPosition(), player.getItemInHand(playerPlacement.getHand()), player.isSneaking());
        }
        return new BlockPlacementRule.PlacementState(this.owner, block, null, blockPosition, null, null, null, false);
    }

    /**
     * Lets the six neighbours of a changed block reshape themselves.
     *
     * @param blockPosition  the position of the block which changed
     * @param updateDistance how many neighbour updates deep the causing write already was
     */
    private void updateNeighbours(Point blockPosition, int updateDistance) {
        final ChunkCache cache = new ChunkCache(this.owner, null, null);
        final DimensionType dimension = this.owner.getCachedDimensionType();
        for (BlockFace face : BLOCK_UPDATE_FACES) {
            final var direction = face.toDirection();
            final int neighbourX = blockPosition.blockX() + direction.normalX();
            final int neighbourY = blockPosition.blockY() + direction.normalY();
            final int neighbourZ = blockPosition.blockZ() + direction.normalZ();
            if (neighbourY < dimension.minY() || neighbourY >= dimension.maxY()) continue;
            final Block neighbour = cache.getBlock(neighbourX, neighbourY, neighbourZ, Condition.NONE);
            if (neighbour == null || neighbour.isAir()) continue;
            final BlockPlacementRule rule = MinecraftServer.getBlockManager().getBlockPlacementRule(neighbour);
            if (rule == null || updateDistance >= rule.maxUpdateDistance()) continue;

            final Vec neighbourPosition = new Vec(neighbourX, neighbourY, neighbourZ);
            final Block updated = rule.blockUpdate(new BlockPlacementRule.UpdateState(
                    this.owner, neighbourPosition, neighbour, face.getOppositeFace()));
            if (neighbour.equals(updated)) continue;
            final Chunk neighbourChunk = this.owner.getChunkAt(neighbourPosition);
            if (neighbourChunk == null || !neighbourChunk.isLoaded()) continue;
            write(FalcoChunk.require(neighbourChunk), neighbourX, neighbourY, neighbourZ, updated, null, null,
                    true, updateDistance + 1);
        }
    }

    /**
     * Gets the time at which the last block of the instance changed.
     * <p>
     * Only usable as a delta against another reading of the same clock.
     * </p>
     *
     * @return the time of the last block change in nanoseconds
     */
    public long lastChangeTime() {
        return this.lastBlockChangeTime;
    }

    /**
     * Records that a block of the instance changed.
     * <p>
     * Needed when blocks are written through a {@link Chunk} directly, which bypasses this writer.
     * </p>
     */
    public void refreshLastChangeTime() {
        this.lastBlockChangeTime = System.nanoTime();
    }

    /**
     * Clears the recursion guard of the block writes.
     * <p>
     * The guard is scoped to a single tick, which is what makes it a guard rather than a memory: a
     * handler which writes its own block again is stopped within the tick it started in, and the same
     * block can be written to the same position again in the next one.
     * </p>
     */
    public void endTick() {
        this.currentlyChangingBlocks.clear();
    }
}
