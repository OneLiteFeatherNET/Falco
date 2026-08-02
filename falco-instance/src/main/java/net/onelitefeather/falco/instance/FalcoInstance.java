package net.onelitefeather.falco.instance;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.instance.InstanceBlockUpdateEvent;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.instance.InstanceChunkUnloadEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockEntityType;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.BlockEntityDataPacket;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.registry.Registries;
import net.minestom.server.timer.SchedulerManager;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.utils.PacketSendingUtils;
import net.minestom.server.utils.block.BlockUtils;
import net.minestom.server.utils.chunk.ChunkCache;
import net.minestom.server.utils.chunk.ChunkSupplier;
import net.minestom.server.world.DimensionType;
import net.minestom.server.worldevent.WorldEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The {@link FalcoInstance} class is a world of a Minestom server which cleans up after itself.
 * <p>
 * It extends {@link Instance} directly instead of {@code InstanceContainer}. Deriving from the
 * container looks cheaper but leads nowhere: the chunk lifecycle hooks it would have to override
 * are {@code protected} members of {@code net.minestom.server.instance}, so a subclass in this
 * package cannot reach them. Starting from {@link Instance} makes the same barrier visible once, at
 * the chunk, where {@link FalcoChunk} answers it.
 * </p>
 * <p>
 * Four places in Minestom branch on {@code instanceof InstanceContainer} and quietly take a
 * different path for any other instance. Three of them are harmless here, one is not:
 * </p>
 * <ul>
 *   <li>{@code InstanceManager#unregisterInstance} does not unload the chunks of a foreign
 *   instance, which leaks every chunk the instance ever loaded. {@link #unregister(InstanceManager)}
 *   is the answer and the reason this class exists.</li>
 *   <li>{@code SharedInstance} is typed on the container throughout, so this instance cannot back
 *   one. That is a missing feature rather than a defect, and it is refused by the compiler.</li>
 *   <li>The {@code Chunk} constructor asks the instance for its shared instances and gets an empty
 *   list here. Since there are no shared instances, an empty list is the correct answer.</li>
 *   <li>The block batches skip {@code refreshLastBlockChangeTime()} for a foreign instance. This
 *   class keeps the timestamp itself, but nothing outside it refreshes it, so batch copies must not
 *   rely on it.</li>
 * </ul>
 * <p>
 * A world here comes from its {@link ChunkLoader}, from a {@link Generator}, or stays empty, in that
 * order. The generator runs against staged palettes rather than against the live ones of the chunk,
 * so a generator which fails halfway changes nothing and the failure reaches the caller instead of
 * the exception manager. {@code InstanceContainer} does the opposite on both counts, which is why
 * {@link #generator()} could not simply be inherited in spirit.
 * </p>
 * <p>
 * The other place where this class deviates on purpose is the moment a loaded chunk becomes part of
 * the instance. Publishing a chunk and unloading one are two transitions of the same position, and
 * they are made mutually exclusive, so an unload which meets a running load either sees the finished
 * chunk or claims the load and makes it throw its result away. Minestom lets the two overlap, and
 * the chunk which loses that race stays in the world with nothing left that could unload it.
 * </p>
 * <p>
 * On threading, this class promises no more than Minestom does, and for a reason worth stating: the
 * parallelism of chunk and entity ticking lives in the global {@code ThreadDispatcher} of the server
 * process, not in the instance. Replacing the instance cannot make ticking faster. What it does buy
 * is that block writes are guarded by the lock of the chunk they touch rather than by a monitor on
 * the whole instance, so two writes to two chunks no longer wait for each other.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.5.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public class FalcoInstance extends Instance {

    private static final Logger LOGGER = LoggerFactory.getLogger(FalcoInstance.class);

    /**
     * The faces a block change offers to its neighbours for a placement rule update.
     */
    private static final BlockFace[] BLOCK_UPDATE_FACES = {
            BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.BOTTOM, BlockFace.TOP
    };

    /**
     * The depth below the bottom of the dimension at which a point counts as being in the void.
     */
    private static final int VOID_DEPTH = 64;

    /**
     * How often {@link #unregister(InstanceManager)} sweeps the instance before it gives up.
     * <p>
     * Two passes are enough whenever nobody asks for a new chunk during the unregister: the first
     * one claims every running load, after which no further chunk can appear, and the second one
     * removes whatever the first one published while it was still running. The remaining passes only
     * exist so a caller which keeps loading during the shutdown does not turn this into an endless
     * loop, which is the one failure mode that would hang a server instead of reporting anything.
     * </p>
     */
    private static final int UNREGISTER_PASSES = 4;

    /**
     * Which chunk sits at which position, and which position is busy.
     * <p>
     * The two maps behind this and the four transitions between them used to be fields and
     * {@code private} methods of this class. They are a responsibility rather than a detail of the
     * instance, and {@link ChunkRegistry} carries both the answer and the state it needs to give it,
     * so nothing of the chunk map is left here to be shared with anything else.
     * </p>
     * <p>
     * That the map of running loads is also the lock of a position is stated where the transitions
     * are, in {@link ChunkRegistry}, and the steps this class hands to
     * {@link ChunkRegistry#publish} and {@link ChunkRegistry#remove} are what it contributes to them.
     * </p>
     */
    private final ChunkRegistry registry = new ChunkRegistry();

    /**
     * The registries the biomes of a generated chunk are looked up in.
     * <p>
     * Kept here rather than read from {@link MinecraftServer} so an instance built against a process
     * which is not the global one generates against the registries of that process.
     * </p>
     */
    private final Registries registries;

    /**
     * The generator, the forks it produced for chunks which were not there, and the commit of both.
     * <p>
     * Final, and the generator inside it is what changes. The field which used to sit here was
     * {@code volatile} because a public setter wrote it while the load path read it from another
     * thread; that reason did not go away, it moved into {@link ChunkGeneration} along with the
     * generator.
     * </p>
     * <p>
     * It is handed {@link #getChunkAt(Point)} rather than this instance, because a neighbour a fork
     * writes into is the only thing generation ever needs a world for. What it is <em>not</em> handed
     * is {@link #refreshLastBlockChangeTime()}: the timestamp belongs to the block write side of this
     * class, so the two callers of {@link ChunkGeneration#apply} refresh it themselves.
     * </p>
     */
    private final ChunkGeneration generation;

    /**
     * The blocks changed since the last tick, used to break recursion between block handlers.
     * <p>
     * Concurrent rather than a plain map behind a lock that guarded nothing, which is the shape the
     * container has.
     * </p>
     */
    private final Map<BlockVec, Block> currentlyChangingBlocks = new ConcurrentHashMap<>();

    /**
     * The factory every chunk of this instance is created by.
     * <p>
     * Volatile because the setter is public and unsynchronized while the load path reads the field
     * from a chunk task on another thread. Without it the reader may not only miss the change, it
     * may see a half-constructed supplier: the value is an arbitrary object handed in by a caller,
     * and only a volatile write publishes that object safely. Synchronizing the setter instead
     * would put a lock on the monitor of a public object, which is exactly what callers must not be
     * able to hold against this instance.
     * </p>
     */
    private volatile ChunkSupplier chunkSupplier = FalcoChunk::new;

    /**
     * Everything this instance does with a {@link ChunkLoader}: the four save paths, the read and the
     * notification that a chunk left.
     * <p>
     * Final, and the loader inside it is what changes. The field which used to sit here was
     * {@code volatile} because a public setter wrote it while the load path read it from another
     * thread; that reason did not go away, it moved into {@link ChunkPersistence} along with the
     * loader.
     * </p>
     */
    private final ChunkPersistence persistence;

    private volatile boolean autoChunkLoad = true;

    private volatile long lastBlockChangeTime;

    /**
     * How a chunk of this instance is told that it was loaded, or null for the built-in way.
     * <p>
     * {@code Chunk#onLoad()} and {@code Chunk#unload()} are {@code protected}, so this package can
     * drive them only on {@link FalcoChunk}, a type it defines itself. A caller that owns another
     * chunk type — a lighting chunk from {@code falco-light}, say — can reach both hooks and hands
     * them over here. Null means the built-in pair, which requires a {@link FalcoChunk} exactly as
     * before.
     * </p>
     * <p>
     * Volatile for the same reason as {@link #chunkSupplier}: a caller object written by a public
     * setter and read on the load path from another thread.
     * </p>
     */
    private volatile @Nullable Consumer<Chunk> chunkLoaded;

    /**
     * How a chunk of this instance is told that it left, or null for the built-in way.
     *
     * @see #chunkLoaded
     */
    private volatile @Nullable Consumer<Chunk> chunkUnloaded;

    /**
     * Whether {@link #shutdown(InstanceManager)} saves the chunks before it unregisters.
     */
    private volatile boolean saveOnShutdown = true;

    /**
     * Whether {@link #shutdown(InstanceManager)} closes the loader, if the loader can be closed.
     */
    private volatile boolean ownsLoader;

    /**
     * Creates an instance in the overworld dimension without a chunk loader.
     *
     * @param uuid          the unique id of the instance
     * @param dimensionType the dimension of the instance
     */
    public FalcoInstance(UUID uuid, RegistryKey<DimensionType> dimensionType) {
        this(uuid, dimensionType, null);
    }

    /**
     * Creates an instance with a chunk loader.
     *
     * @param uuid          the unique id of the instance
     * @param dimensionType the dimension of the instance
     * @param loader        the loader chunks are read from and written to, null for a loader which
     *                      loads and saves nothing
     */
    public FalcoInstance(UUID uuid, RegistryKey<DimensionType> dimensionType, @Nullable ChunkLoader loader) {
        this(MinecraftServer.process(), uuid, dimensionType, loader, dimensionType.key());
    }

    /**
     * Creates an instance with an explicit registry set and dimension name.
     * <p>
     * The registry set is taken as an argument rather than read from the running server so the
     * instance can be built against a process which is not the global one.
     * </p>
     *
     * @param registries    the registries the dimension of the instance is looked up in
     * @param uuid          the unique id of the instance
     * @param dimensionType the dimension of the instance
     * @param loader        the loader chunks are read from and written to, null for a loader which
     *                      loads and saves nothing
     * @param dimensionName the name the client is told the dimension has
     */
    public FalcoInstance(Registries registries, UUID uuid, RegistryKey<DimensionType> dimensionType,
                         @Nullable ChunkLoader loader, Key dimensionName) {
        super(registries, uuid, dimensionType, dimensionName);
        this.registries = registries;
        this.generation = new ChunkGeneration(this.registries, this::getChunkAt);
        this.persistence = new ChunkPersistence(loader);
        // Outside the ChunkPersistence constructor on purpose: loadInstance may call back into this
        // instance, and a callback into an object whose constructor has not finished is how a field
        // that is assigned two lines later is read as null.
        this.persistence.loader().loadInstance(this);
        this.lastBlockChangeTime = System.nanoTime();
    }

    /**
     * Returns a builder for an instance in the given dimension.
     * <p>
     * The dimension is the only required value and therefore stands here rather than in a slot.
     * Every other value defaults to what the constructors use.
     * </p>
     *
     * @param dimensionType the dimension of the instance
     * @return a new builder with the defaults of the constructors
     */
    @Contract(value = "_ -> new", pure = true)
    public static Builder builder(RegistryKey<DimensionType> dimensionType) {
        return new Builder(dimensionType, null, null, null, null, null, null, null, true, false, true);
    }

    /**
     * Saves this instance, takes it out of the server and closes what it owns.
     * <p>
     * <b>The order is the point of this method.</b> Saving happens first, and it has to:
     * {@link #saveChunksToStorage()} takes a snapshot of the chunk map, while
     * {@link #unregister(InstanceManager)} empties that map. A save after the unregister therefore
     * writes nothing at all and reports success, which is the shape of data loss this method
     * exists to make unreachable.
     * </p>
     * <p>
     * A failed save stops the shutdown rather than carrying on. The chunks stay in memory and the
     * instance stays registered, so a second call can still succeed — {@code unregister} is
     * explicitly callable more than once. Carrying on would drop exactly the chunks whose saving
     * just failed.
     * </p>
     * <p>
     * The loader is closed last, and only if the instance was told it owns it. A loader shared
     * between the overworld and the nether of the same world outlives either of them.
     * </p>
     *
     * @param instanceManager the manager this instance is registered with
     * @throws FalcoInstanceException if the chunks could not be saved or the loader not closed
     */
    public void shutdown(InstanceManager instanceManager) {
        if (this.saveOnShutdown) {
            try {
                saveChunksToStorage().join();
            } catch (Throwable throwable) {
                throw new FalcoInstanceException("the chunks of the instance " + getUuid()
                        + " could not be saved, so it was left registered and loaded", throwable);
            }
        }
        unregister(instanceManager);

        if (!this.ownsLoader) return;

        if (this.persistence.loader() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new FalcoInstanceException("the loader of the instance " + getUuid()
                        + " could not be closed", exception);
            }
        }
    }

    /**
     * Unregisters this instance and unloads every chunk it holds.
     * <p>
     * {@code InstanceManager#unregisterInstance} only unloads chunks for an
     * {@code InstanceContainer}. For anything else it removes the instance from the manager and
     * leaves the chunks, their tick partitions and every entity in them behind, with nothing left
     * that would ever reach them again. This method delegates first, so listeners of
     * {@code InstanceUnregisterEvent} still see a populated instance in the same order they would
     * for a container, and then performs the cleanup the manager skipped.
     * </p>
     * <p>
     * Calling this on an instance which is already unregistered is allowed and unloads whatever is
     * left, which makes it safe to use as a shutdown step that may run twice.
     * </p>
     * <p>
     * A chunk which is still being loaded is not in the chunk map yet, so walking that map is not
     * enough: the load would finish afterwards and publish its chunk into an instance nothing
     * reaches any more, which is the permanent zombie the wiki's "Research: Instance Container"
     * page describes. Every running load is therefore claimed first, which makes it throw its result away
     * instead of publishing it, and only then are the chunks which are already there unloaded. The
     * second pass exists because a load may publish while the first claim is still walking, and it
     * is the last one which can produce anything.
     * </p>
     * <p>
     * A chunk requested while this method runs is a caller error and is not covered. The sweep gives
     * up after {@link #UNREGISTER_PASSES} passes and says so rather than looping until the world
     * stops changing, because a shutdown which never returns is worse than one which reports a leak.
     * </p>
     *
     * @param instanceManager the manager this instance is registered with
     * @throws IllegalStateException if a player is still online in this instance
     */
    public void unregister(InstanceManager instanceManager) {
        if (isRegistered()) instanceManager.unregisterInstance(this);
        for (int pass = 0; pass < UNREGISTER_PASSES; pass++) {
            for (Long index : this.registry.loadingPositions()) discardRunningLoad(index);
            for (Chunk chunk : this.registry.snapshot()) unloadChunk(chunk);
            if (this.registry.idle()) {
                // A fork whose target chunk was never requested waits forever, and after this there
                // is nothing left it could wait for.
                this.generation.clearPending();
                return;
            }
        }
        this.generation.clearPending();
        LOGGER.warn("chunks kept arriving while the instance {} was unregistered; {} chunks and {} loads are left behind",
                getUuid(), this.registry.size(), this.registry.loading());
    }

    /**
     * Takes the slot of a running load so its chunk never reaches this instance.
     * <p>
     * Removing the entry is the whole claim: the loading thread publishes its chunk only while its
     * own future is still the entry of the position, so a load which finds the slot empty or taken
     * knows that somebody decided its result is no longer wanted. The waiting callers are told with
     * a failure rather than with the chunk, because a chunk which is handed back after it was
     * discarded looks usable and is not.
     * </p>
     *
     * @param index the chunk index of the position whose load is claimed
     */
    private void discardRunningLoad(long index) {
        final CompletableFuture<Chunk> running = this.registry.discard(index);
        if (running == null) return;
        running.completeExceptionally(new FalcoInstanceException("the chunk "
                + CoordConversion.chunkIndexGetX(index) + ":" + CoordConversion.chunkIndexGetZ(index)
                + " was unloaded while it was being loaded, so the load was cancelled"));
    }

    @Override
    public void setBlock(int x, int y, int z, Block block, boolean doBlockUpdates) {
        Chunk chunk = getChunkAt(x, z);
        if (chunk == null) {
            if (!this.autoChunkLoad) {
                throw new IllegalStateException(
                        "tried to set a block in the unloaded chunk " + CoordConversion.globalToChunk(x)
                                + ":" + CoordConversion.globalToChunk(z) + " while auto chunk load is disabled");
            }
            chunk = loadChunk(CoordConversion.globalToChunk(x), CoordConversion.globalToChunk(z)).join();
        }
        if (chunk.isLoaded()) writeBlock(requireWritableChunk(chunk), x, y, z, block, null, null, doBlockUpdates, 0);
    }

    @Override
    public boolean placeBlock(BlockHandler.Placement placement, boolean doBlockUpdates) {
        final Point blockPosition = placement.getBlockPosition();
        final Chunk chunk = getChunkAt(blockPosition);
        if (chunk == null || !chunk.isLoaded()) return false;
        writeBlock(requireWritableChunk(chunk), blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ(),
                placement.getBlock(), placement, null, doBlockUpdates, 0);
        return true;
    }

    @Override
    public boolean breakBlock(Player player, Point blockPosition, BlockFace blockFace, boolean doBlockUpdates) {
        final Chunk chunk = getChunkAt(blockPosition);
        if (chunk == null || !chunk.isLoaded() || chunk.isReadOnly()) return false;

        final Block block = getBlock(blockPosition);
        if (block.isAir()) {
            // The client believes there is a block here; hand it the chunk it actually has.
            chunk.sendChunk(player);
            return false;
        }
        final PlayerBlockBreakEvent event = new PlayerBlockBreakEvent(player, this, block, Block.AIR,
                blockPosition.asBlockVec(), blockFace);
        EventDispatcher.call(event);
        if (event.isCancelled()) return false;

        final Block resultBlock = event.getResultBlock();
        writeBlock(requireWritableChunk(chunk), blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ(), resultBlock,
                null, new BlockHandler.PlayerDestroy(block, resultBlock, this, blockPosition, player),
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
     * Only the write lock of the touched chunk is held here. The container of Minestom takes its own
     * monitor around the whole method, which turns every block write in the world into a queue
     * behind every other one; two writes to two chunks have no reason to wait for each other.
     * </p>
     * <p>
     * The chunk is taken as a {@link FalcoChunk} rather than a {@link Chunk} because the block
     * setter carrying a placement and a destruction is {@code protected} on {@code Chunk} and only
     * widened to public by {@code DynamicChunk}. That is a third lifecycle barrier next to the two
     * hooks {@link FalcoChunk} re-exposes, and it is answered the same way.
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
    private void writeBlock(FalcoChunk chunk, int x, int y, int z, Block block,
                            @Nullable BlockHandler.Placement placement, @Nullable BlockHandler.Destroy destroy,
                            boolean doBlockUpdates, int updateDistance) {
        if (chunk.isReadOnly()) return;
        final DimensionType dimension = getCachedDimensionType();
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
        EventDispatcher.call(new InstanceBlockUpdateEvent(this, blockPosition, placed));
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
            return new BlockPlacementRule.PlacementState(this, block, playerPlacement.getBlockFace(), blockPosition,
                    new Vec(playerPlacement.getCursorX(), playerPlacement.getCursorY(), playerPlacement.getCursorZ()),
                    player.getPosition(), player.getItemInHand(playerPlacement.getHand()), player.isSneaking());
        }
        return new BlockPlacementRule.PlacementState(this, block, null, blockPosition, null, null, null, false);
    }

    /**
     * Lets the six neighbours of a changed block reshape themselves.
     *
     * @param blockPosition  the position of the block which changed
     * @param updateDistance how many neighbour updates deep the causing write already was
     */
    private void updateNeighbours(Point blockPosition, int updateDistance) {
        final ChunkCache cache = new ChunkCache(this, null, null);
        final DimensionType dimension = getCachedDimensionType();
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
                    this, neighbourPosition, neighbour, face.getOppositeFace()));
            if (neighbour.equals(updated)) continue;
            final Chunk neighbourChunk = getChunkAt(neighbourPosition);
            if (neighbourChunk == null || !neighbourChunk.isLoaded()) continue;
            writeBlock(requireWritableChunk(neighbourChunk), neighbourX, neighbourY, neighbourZ, updated, null, null,
                    true, updateDistance + 1);
        }
    }

    @Override
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        return retrieveChunk(chunkX, chunkZ);
    }

    @Override
    public CompletableFuture<@Nullable Chunk> loadOptionalChunk(int chunkX, int chunkZ) {
        final Chunk loaded = getChunk(chunkX, chunkZ);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);
        if (!this.autoChunkLoad) return CompletableFuture.completedFuture(null);
        return retrieveChunk(chunkX, chunkZ);
    }

    /**
     * Hands back the chunk at the given position, loading it if it is not there yet.
     * <p>
     * Two callers asking for the same chunk at the same time share one load: the first one to offer
     * its future to {@link ChunkRegistry#acquire} performs the work, everyone else receives that
     * same future. Which of the three cases a caller is in is the registry's decision and is
     * explained there; this method only acts on the answer.
     * </p>
     * <p>
     * The work itself starts after the decision, never inside it. A loader without parallel support
     * runs on the calling thread, and starting it inside the decision would run it while the
     * position is held, where a nested transition of the same position would deadlock.
     * </p>
     * <p>
     * A failure completes the returned future exceptionally and stops there. It is deliberately not
     * also pushed into the exception manager of the server the way the container does it, because a
     * failure that is both reported and returned gets handled twice and logged twice.
     * </p>
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return a future completed with the chunk, or completed exceptionally if it cannot be created
     */
    private CompletableFuture<Chunk> retrieveChunk(int chunkX, int chunkZ) {
        final long index = CoordConversion.chunkIndex(chunkX, chunkZ);
        final Chunk loaded = this.registry.chunk(index);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);

        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        final ChunkRegistry.LoadSlot slot = this.registry.acquire(index, own);
        switch (slot) {
            case ChunkRegistry.LoadSlot.Loaded(Chunk cached) -> {
                return CompletableFuture.completedFuture(cached);
            }
            case ChunkRegistry.LoadSlot.Running(CompletableFuture<Chunk> running) -> {
                return running;
            }
            case ChunkRegistry.LoadSlot.Claimed ignored -> {
                final ChunkLoader loader = this.persistence.loader();
                if (loader.supportsParallelLoading()) {
                    Thread.startVirtualThread(() -> completeLoad(index, chunkX, chunkZ, loader, own));
                } else {
                    // A loader without parallel support is read on the calling thread, which keeps a
                    // `loadChunk(…).join()` from a tick free of a thread hand-off it would only wait for.
                    completeLoad(index, chunkX, chunkZ, loader, own);
                }
                return own;
            }
        }
    }

    /**
     * Reads a chunk through the loader, publishes it and completes the waiting future.
     * <p>
     * The chunk is produced first and published second, and the publish may be refused. Everything
     * in between the two is the window in which an unload can decide that this chunk is not wanted
     * any more; a load which is refused therefore has to undo itself rather than complain, which is
     * what the discard below does.
     * </p>
     * <p>
     * The two ends of that undo do not necessarily reach the same loader. The chunk is read through
     * the loader handed in here, which {@link #retrieveChunk(int, int)} captured before the load
     * started, while {@link ChunkPersistence#unloaded(Chunk)} tells whichever loader is current when
     * it runs. A {@link #setChunkLoader(ChunkLoader)} in between therefore hands the discarded chunk
     * to a loader that never produced it — which its own documentation permits, since Minestom gives
     * a loader no way to tell its own chunks apart anyway. It is written down rather than fixed
     * because changing it is a change of behaviour.
     * </p>
     *
     * @param index  the chunk index of the position, the key in the map of loading chunks
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @param loader the loader the chunk is read from
     * @param future the future handed to the callers waiting for this chunk
     */
    private void completeLoad(long index, int chunkX, int chunkZ, ChunkLoader loader, CompletableFuture<Chunk> future) {
        final Chunk falcoChunk;
        try {
            Chunk chunk = loader.loadChunk(this, chunkX, chunkZ);
            if (chunk == null) {
                chunk = createChunk(chunkX, chunkZ);
                chunk.onGenerate();
            }
            falcoChunk = requireManagedChunk(chunk);
        } catch (Throwable throwable) {
            this.registry.release(index, future);
            future.completeExceptionally(throwable);
            return;
        }
        if (!publishChunk(index, falcoChunk, future)) {
            // The chunk was never part of this instance, so there is no map entry and no partition
            // to clean up. The loader is still told, because it created the chunk and may hold
            // bookkeeping for it, which its own documentation allows for explicitly.
            notifyUnloaded(falcoChunk);
            this.persistence.unloaded(falcoChunk);
            future.completeExceptionally(new FalcoInstanceException("the chunk " + chunkX + ":" + chunkZ
                    + " was unloaded while it was being loaded, so the loaded chunk was discarded"));
            return;
        }
        notifyLoaded(falcoChunk);
        future.complete(falcoChunk);
        EventDispatcher.call(new InstanceChunkLoadEvent(this, falcoChunk));
    }

    /**
     * Makes a freshly loaded chunk part of this instance, unless somebody claimed its position.
     * <p>
     * Putting the chunk into the chunk map and giving it a tick partition are one step, taken while
     * the position is held, so an unload of the same position can only run entirely before or
     * entirely after it. Splitting them is what lets Minestom delete a partition that is created a
     * moment later, which leaves the chunk being ticked for the rest of the life of the server even
     * though nothing else knows about it any more.
     * </p>
     * <p>
     * Telling the chunk that it was loaded happens outside, and the asymmetry with
     * {@link #unloadChunk(Chunk)}, which tells the chunk it left from <em>inside</em> the lock, is
     * not an oversight. {@code Chunk#onLoad()} sets no flag: a chunk reports {@code isLoaded()} from
     * the moment it is constructed, so nothing a reader of this instance can see depends on that
     * hook having run yet. The unload hook does set the flag, and a chunk which has left the chunk
     * map while still reporting itself as loaded is one every {@code ChunkUtils#isLoaded} check in
     * Minestom believes in — which is why that one step is inside and this one is not.
     * </p>
     * <p>
     * The step below therefore has no foreign code in it, but that is a property of this method
     * rather than a rule of the registry; {@link ChunkRegistry} states what a step handed to it may
     * do, and the removal step of {@link #unloadChunk(Chunk)} is bound by exactly the same rules.
     * </p>
     *
     * @param index  the chunk index of the position
     * @param chunk  the chunk to publish
     * @param future the future of this load, which has to still be the entry of the position
     * @return true if the chunk is now part of this instance, false if the load was claimed
     */
    private boolean publishChunk(long index, Chunk chunk, CompletableFuture<Chunk> future) {
        return this.registry.publish(index, chunk, future,
                published -> MinecraftServer.process().dispatcher().createPartition(published));
    }

    /**
     * Creates a chunk through the chunk supplier of this instance and generates it.
     * <p>
     * This is the path a chunk takes which no {@link ChunkLoader} knows about. Without a generator
     * the chunk stays empty, which is a world made of air rather than a failure.
     * </p>
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return the created chunk
     * @throws FalcoInstanceException if the chunk supplier returned null
     */
    protected Chunk createChunk(int chunkX, int chunkZ) {
        final Chunk chunk = this.chunkSupplier.createChunk(this, chunkX, chunkZ);
        if (chunk == null) {
            throw new FalcoInstanceException("the chunk supplier returned null for chunk " + chunkX + ":" + chunkZ);
        }
        final Generator current = this.generation.generator();
        if (current != null && chunk.shouldGenerate()) {
            this.generation.apply(chunk, current);
            refreshLastBlockChangeTime();
        } else {
            this.generation.applyPending(chunk);
        }
        return chunk;
    }

    /**
     * Checks that a chunk is one this instance can manage.
     * <p>
     * A chunk of any other type would be accepted by everything except the unload path, where the
     * {@code protected} lifecycle hooks are out of reach, so it would silently keep reporting itself
     * as loaded forever. Refusing it here names the cause at the point where the wrong supplier was
     * used.
     * </p>
     *
     * @param chunk the chunk to check
     * @return the same chunk, typed
     * @throws FalcoInstanceException if the chunk is not a {@link FalcoChunk}
     */
    @Contract("_ -> param1")
    private Chunk requireManagedChunk(Chunk chunk) {
        if (this.chunkLoaded == null || this.chunkUnloaded == null) {
            if (chunk instanceof FalcoChunk falcoChunk) return falcoChunk;
            throw new FalcoInstanceException("this instance only manages " + FalcoChunk.class.getName()
                    + ", but its chunk supplier produced a " + chunk.getClass().getName()
                    + "; the lifecycle hooks of any other chunk cannot be reached from this package."
                    + " Configure setChunkLifecycle if you own the chunk type and can reach them");
        }
        return chunk;
    }

    /**
     * Checks that a chunk can be written to through this instance, and types it.
     * <p>
     * Stricter than {@link #requireManagedChunk(Chunk)} on purpose, and the difference is not a
     * matter of taste. The lifecycle hooks can be delegated to a caller-supplied handler, so a chunk
     * of any type is acceptable there. Writing is different: this instance calls the block setter
     * which carries a placement and a destruction, and that one is {@code protected} on
     * {@code Chunk}. Only a subclass can widen it, and {@link FalcoChunk} is the subclass this
     * module ships. A foreign chunk type may therefore take part in the lifecycle and still not be
     * writable through this instance, which is why the two checks are separate rather than one.
     * </p>
     *
     * @param chunk the chunk to check
     * @return the same chunk, typed
     * @throws FalcoInstanceException if the chunk is not a {@link FalcoChunk}
     */
    @Contract("_ -> param1")
    private FalcoChunk requireWritableChunk(Chunk chunk) {
        if (chunk instanceof FalcoChunk falcoChunk) return falcoChunk;
        throw new FalcoInstanceException("this instance writes blocks through "
                + FalcoChunk.class.getName() + ", whose block setter carrying a placement is public,"
                + " but its chunk supplier produced a " + chunk.getClass().getName());
    }

    /**
     * Tells a chunk that it is now part of this instance.
     *
     * @param chunk the chunk which finished loading
     */
    private void notifyLoaded(Chunk chunk) {
        @Nullable Consumer<Chunk> configured = this.chunkLoaded;

        if (configured == null) {
            ((FalcoChunk) chunk).markLoaded();
            return;
        }
        configured.accept(chunk);
    }

    /**
     * Tells a chunk that it is no longer part of this instance.
     *
     * @param chunk the chunk which left the instance
     */
    private void notifyUnloaded(Chunk chunk) {
        @Nullable Consumer<Chunk> configured = this.chunkUnloaded;

        if (configured == null) {
            ((FalcoChunk) chunk).markUnloaded();
            return;
        }
        configured.accept(chunk);
    }

    /**
     * Removes a chunk from this instance.
     * <p>
     * Taking the chunk out of the chunk map, clearing its loaded flag and deleting its tick
     * partition are one step, taken while the position of the chunk is held, so a load which is
     * publishing the same position cannot interleave with it. Everything else — the packet, the
     * event, the entities and the loader — follows outside, because all four can call back into this
     * instance and holding a position while foreign code runs is how two chunks deadlock each other.
     * </p>
     * <p>
     * Clearing the flag is the one step which cannot move out, and it may be foreign code: a caller
     * which installed a lifecycle through {@link #setChunkLifecycle(Consumer, Consumer)} sees its own
     * consumer run right there, while the position is held. That consumer is bound by what
     * {@link ChunkRegistry} requires of such a step — short, non-blocking, no call back into the
     * chunk map of this instance, no exception — and the constraint is documented on both.
     * </p>
     * <p>
     * A running load is not cancelled here and not waited for either, and that is not an omission: a
     * position which is loading has no chunk in the map, so a chunk a caller can hand to this method
     * is never the one being loaded. It is either the chunk of that position, which the atomic step
     * below removes, or a chunk of an earlier life of that position, which was already unloaded and
     * is refused by the first line. Cancelling a load needs a position rather than a chunk, and
     * {@link #unregister(InstanceManager)} is where that happens.
     * </p>
     * <p>
     * Unloading the same chunk twice does nothing the second time, which makes this usable in a
     * cleanup path that may run more than once.
     * </p>
     *
     * @param chunk the chunk to remove, has to be a {@link FalcoChunk}
     * @throws FalcoInstanceException if the chunk is not a {@link FalcoChunk}
     */
    @Override
    public void unloadChunk(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        final Chunk falcoChunk = requireManagedChunk(chunk);
        final int chunkX = falcoChunk.getChunkX();
        final int chunkZ = falcoChunk.getChunkZ();
        final long index = CoordConversion.chunkIndex(chunkX, chunkZ);
        final boolean removed = this.registry.remove(index, falcoChunk, unloaded -> {
            notifyUnloaded(unloaded);
            MinecraftServer.process().dispatcher().deletePartition(unloaded);
        });
        if (!removed) return;
        falcoChunk.sendPacketToViewers(new UnloadChunkPacket(chunkX, chunkZ));
        EventDispatcher.call(new InstanceChunkUnloadEvent(this, falcoChunk));
        getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES).forEach(Entity::remove);
        this.persistence.unloaded(falcoChunk);
    }

    @Override
    public @Nullable Chunk getChunk(int chunkX, int chunkZ) {
        return this.registry.chunk(chunkX, chunkZ);
    }

    @Override
    public @UnmodifiableView Collection<Chunk> getChunks() {
        return this.registry.chunks();
    }

    @Override
    public CompletableFuture<Void> saveInstance() {
        return this.persistence.saveInstance(this);
    }

    @Override
    public CompletableFuture<Void> saveChunkToStorage(Chunk chunk) {
        return this.persistence.saveChunk(chunk);
    }

    @Override
    public CompletableFuture<Void> saveChunksToStorage() {
        return this.persistence.saveChunks(this.registry.snapshot());
    }

    @Override
    public void setChunkSupplier(ChunkSupplier chunkSupplier) {
        this.chunkSupplier = Objects.requireNonNull(chunkSupplier, "the chunk supplier cannot be null");
    }

    @Override
    public ChunkSupplier getChunkSupplier() {
        return this.chunkSupplier;
    }

    /**
     * Collects the values of an instance before it is built.
     * <p>
     * <b>Immutable.</b> Every slot returns a new builder and leaves the one it was called on
     * untouched, which is the same shape {@code ChunkLightScheduler.Builder} has, so the two read
     * the same way. A builder can therefore be shared and derived from without anyone having to
     * reason about who changes it.
     * </p>
     * <p>
     * The terminal methods register the instance, because an instance that is not registered is not
     * yet a world: the server does not tick it and no player can be there. Which of the two you use
     * decides whether the shutdown is something you can still forget.
     * </p>
     * <p>
     * This type is experimental, like everything else in this package.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    @ApiStatus.Experimental
    public static final class Builder {

        private final RegistryKey<DimensionType> dimensionType;
        private final @Nullable UUID uuid;
        private final @Nullable Registries registries;
        private final @Nullable Key dimensionName;
        private final @Nullable ChunkLoader chunkLoader;
        private final @Nullable ChunkSupplier chunkSupplier;
        private final @Nullable Consumer<Chunk> chunkLoaded;
        private final @Nullable Consumer<Chunk> chunkUnloaded;
        private final boolean autoChunkLoad;
        private final boolean ownsLoader;
        private final boolean saveOnShutdown;

        private Builder(RegistryKey<DimensionType> dimensionType, @Nullable UUID uuid,
                        @Nullable Registries registries, @Nullable Key dimensionName,
                        @Nullable ChunkLoader chunkLoader, @Nullable ChunkSupplier chunkSupplier,
                        @Nullable Consumer<Chunk> chunkLoaded, @Nullable Consumer<Chunk> chunkUnloaded,
                        boolean autoChunkLoad, boolean ownsLoader, boolean saveOnShutdown) {
            this.dimensionType = dimensionType;
            this.uuid = uuid;
            this.registries = registries;
            this.dimensionName = dimensionName;
            this.chunkLoader = chunkLoader;
            this.chunkSupplier = chunkSupplier;
            this.chunkLoaded = chunkLoaded;
            this.chunkUnloaded = chunkUnloaded;
            this.autoChunkLoad = autoChunkLoad;
            this.ownsLoader = ownsLoader;
            this.saveOnShutdown = saveOnShutdown;
        }

        /**
         * Sets the unique id of the instance, which defaults to a random one.
         *
         * @param uuid the unique id of the instance
         * @return a new builder with this id
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder uuid(UUID uuid) {
            return new Builder(this.dimensionType, uuid, this.registries, this.dimensionName,
                    this.chunkLoader, this.chunkSupplier, this.chunkLoaded, this.chunkUnloaded,
                    this.autoChunkLoad, this.ownsLoader, this.saveOnShutdown);
        }

        /**
         * Sets the registries the dimension is looked up in, which default to the running server.
         *
         * @param registries the registries of the instance
         * @return a new builder with these registries
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder registries(Registries registries) {
            return new Builder(this.dimensionType, this.uuid, registries, this.dimensionName,
                    this.chunkLoader, this.chunkSupplier, this.chunkLoaded, this.chunkUnloaded,
                    this.autoChunkLoad, this.ownsLoader, this.saveOnShutdown);
        }

        /**
         * Sets the name the client is told the dimension has, which defaults to its key.
         *
         * @param dimensionName the name of the dimension
         * @return a new builder with this name
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder dimensionName(Key dimensionName) {
            return new Builder(this.dimensionType, this.uuid, this.registries, dimensionName,
                    this.chunkLoader, this.chunkSupplier, this.chunkLoaded, this.chunkUnloaded,
                    this.autoChunkLoad, this.ownsLoader, this.saveOnShutdown);
        }

        /**
         * Sets the loader chunks are read from and written to.
         * <p>
         * Without one the instance reads and writes nothing, which is a world of air rather than a
         * failure. A loader and a generator are independent: the generator runs for the chunks the
         * loader has no entry for.
         * </p>
         *
         * @param chunkLoader the loader of the instance
         * @return a new builder with this loader
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder chunkLoader(ChunkLoader chunkLoader) {
            return new Builder(this.dimensionType, this.uuid, this.registries, this.dimensionName,
                    chunkLoader, this.chunkSupplier, this.chunkLoaded, this.chunkUnloaded,
                    this.autoChunkLoad, this.ownsLoader, this.saveOnShutdown);
        }

        /**
         * Sets what produces the chunk objects of the instance.
         *
         * @param chunkSupplier the supplier of chunk objects
         * @return a new builder with this supplier
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder chunkSupplier(ChunkSupplier chunkSupplier) {
            return new Builder(this.dimensionType, this.uuid, this.registries, this.dimensionName,
                    this.chunkLoader, chunkSupplier, this.chunkLoaded, this.chunkUnloaded,
                    this.autoChunkLoad, this.ownsLoader, this.saveOnShutdown);
        }

        /**
         * Says how a chunk is told that it was loaded and that it left.
         * <p>
         * Needed for any chunk type this package did not define, because the two hooks are
         * {@code protected}. See {@link FalcoInstance#setChunkLifecycle(Consumer, Consumer)} for
         * what this buys and what it costs.
         * </p>
         *
         * @param onLoaded   what tells a chunk that it is part of the instance
         * @param onUnloaded what tells a chunk that it left the instance
         * @return a new builder with this lifecycle
         */
        @Contract(value = "_, _ -> new", pure = true)
        public Builder chunkLifecycle(Consumer<Chunk> onLoaded, Consumer<Chunk> onUnloaded) {
            return new Builder(this.dimensionType, this.uuid, this.registries, this.dimensionName,
                    this.chunkLoader, this.chunkSupplier, onLoaded, onUnloaded,
                    this.autoChunkLoad, this.ownsLoader, this.saveOnShutdown);
        }

        /**
         * Sets whether a block written outside a loaded chunk loads that chunk first.
         *
         * @param autoChunkLoad true to load chunks on demand
         * @return a new builder with this setting
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder autoChunkLoad(boolean autoChunkLoad) {
            return new Builder(this.dimensionType, this.uuid, this.registries, this.dimensionName,
                    this.chunkLoader, this.chunkSupplier, this.chunkLoaded, this.chunkUnloaded,
                    autoChunkLoad, this.ownsLoader, this.saveOnShutdown);
        }

        /**
         * Sets whether the shutdown of the instance also closes its loader.
         * <p>
         * The default is false, because a loader is usually shared: the overworld and the nether of
         * one world are two instances on one loader, and the first of them to shut down must not
         * close it under the second.
         * </p>
         *
         * @param ownsLoader true if the instance closes the loader when it shuts down
         * @return a new builder with this setting
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder ownsLoader(boolean ownsLoader) {
            return new Builder(this.dimensionType, this.uuid, this.registries, this.dimensionName,
                    this.chunkLoader, this.chunkSupplier, this.chunkLoaded, this.chunkUnloaded,
                    this.autoChunkLoad, ownsLoader, this.saveOnShutdown);
        }

        /**
         * Sets whether the shutdown of the instance saves its chunks first.
         * <p>
         * The default is true, and the asymmetry is deliberate: saving a world nobody changed costs
         * time, while not saving one that was changed costs the changes.
         * </p>
         *
         * @param saveOnShutdown true if the shutdown saves before it unregisters
         * @return a new builder with this setting
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder saveOnShutdown(boolean saveOnShutdown) {
            return new Builder(this.dimensionType, this.uuid, this.registries, this.dimensionName,
                    this.chunkLoader, this.chunkSupplier, this.chunkLoaded, this.chunkUnloaded,
                    this.autoChunkLoad, this.ownsLoader, saveOnShutdown);
        }

        /**
         * Builds the instance and registers it with the given manager.
         * <p>
         * The shutdown is left to the caller. Use
         * {@link #registerAndShutdownWith(InstanceManager, SchedulerManager)} to make forgetting it
         * impossible.
         * </p>
         *
         * @param instanceManager the manager the instance registers with
         * @return the registered instance
         */
        public FalcoInstance register(InstanceManager instanceManager) {
            FalcoInstance instance = new FalcoInstance(
                    this.registries == null ? MinecraftServer.process() : this.registries,
                    this.uuid == null ? UUID.randomUUID() : this.uuid,
                    this.dimensionType,
                    this.chunkLoader,
                    this.dimensionName == null ? this.dimensionType.key() : this.dimensionName);

            if (this.chunkSupplier != null) instance.setChunkSupplier(this.chunkSupplier);
            if (this.chunkLoaded != null && this.chunkUnloaded != null) {
                instance.setChunkLifecycle(this.chunkLoaded, this.chunkUnloaded);
            }
            instance.enableAutoChunkLoad(this.autoChunkLoad);
            instance.saveOnShutdown = this.saveOnShutdown;
            instance.ownsLoader = this.ownsLoader;

            instanceManager.registerInstance(instance);
            return instance;
        }

        /**
         * Builds the instance, registers it, and registers its shutdown as a shutdown task.
         * <p>
         * This is the reason the builder exists in the shape it has. Setting a world up takes
         * several statements, and exactly one of them — the one that saves and tears down — is the
         * one whose absence shows up days later, as a world that quietly lost its changes. Here it
         * is not a statement a caller writes but part of the expression in which the instance first
         * becomes reachable.
         * </p>
         *
         * @param instanceManager  the manager the instance registers with
         * @param schedulerManager the scheduler the shutdown task is registered with
         * @return the registered instance
         */
        public FalcoInstance registerAndShutdownWith(InstanceManager instanceManager,
                                                     SchedulerManager schedulerManager) {
            FalcoInstance instance = register(instanceManager);
            schedulerManager.buildShutdownTask(() -> instance.shutdown(instanceManager));
            return instance;
        }
    }

    /**
     * Says how a chunk of this instance is told that it was loaded and that it left.
     * <p>
     * Without this the instance manages {@link FalcoChunk} and nothing else, for a reason that is
     * not a preference: {@code Chunk#onLoad()} and {@code Chunk#unload()} are {@code protected}, so
     * this package can drive them only on a type it defines itself. A caller who owns another chunk
     * type can reach both hooks and connects them here — which is how a chunk from another module,
     * a lighting chunk for instance, becomes usable in this instance without either module having
     * to know the other.
     * </p>
     * <pre>{@code
     * instance.setChunkSupplier(scheduler.supplier());
     * instance.setChunkLifecycle(
     *         chunk -> ((FalcoLightingChunk) chunk).markLoaded(),
     *         chunk -> ((FalcoLightingChunk) chunk).markUnloaded());
     * }</pre>
     * <p>
     * Both halves are one call so the pair cannot be set half way. Set them before the first chunk
     * is loaded; a chunk that was published under one lifecycle is not told about a later change.
     * The instance stops checking for {@link FalcoChunk} from here on and requires only a
     * {@code DynamicChunk}, so an unsuitable supplier now fails on the cast inside your own
     * function rather than with a message from this class.
     * </p>
     * <p>
     * The two halves are not called under the same conditions, and the difference matters for what
     * may be written into them. The loaded half runs after the position of the chunk was released and
     * is unconstrained. The unloaded half runs <em>while</em> the position is held, because clearing
     * the loaded flag has to be atomic with the chunk leaving the chunk map, so it runs under the
     * rules {@link ChunkRegistry} states for such a step: it has to be short, must not block, must
     * not call back into the chunk map of this instance — a {@code getChunk}, {@code loadChunk} or
     * {@code unloadChunk} from in there can wedge that position for good — and must not throw, which
     * would leave the chunk out of the map and only half unloaded.
     * </p>
     *
     * @param onLoaded   what tells a chunk that it is part of this instance
     * @param onUnloaded what tells a chunk that it left this instance
     * @throws NullPointerException if either half is null
     */
    public void setChunkLifecycle(Consumer<Chunk> onLoaded, Consumer<Chunk> onUnloaded) {
        Objects.requireNonNull(onLoaded, "the loaded half of the lifecycle cannot be null");
        Objects.requireNonNull(onUnloaded, "the unloaded half of the lifecycle cannot be null");
        this.chunkLoaded = onLoaded;
        this.chunkUnloaded = onUnloaded;
    }

    /**
     * Gets the loader chunks of this instance are read from and written to.
     *
     * @return the current chunk loader
     */
    public ChunkLoader getChunkLoader() {
        return this.persistence.loader();
    }

    /**
     * Changes the loader chunks of this instance are read from and written to.
     * <p>
     * Chunks which are already loaded are not affected, and {@code ChunkLoader#loadInstance} is not
     * called again — it belongs to the construction of the instance, and calling it on a world which
     * already has chunks would overwrite live state with what is on disk.
     * </p>
     *
     * @param chunkLoader the new chunk loader
     */
    public void setChunkLoader(ChunkLoader chunkLoader) {
        this.persistence.loader(chunkLoader);
    }

    /**
     * Gets the generator which fills a chunk no loader knows about.
     *
     * @return the current generator, null if chunks without a loader stay empty
     */
    @Override
    public @Nullable Generator generator() {
        return this.generation.generator();
    }

    /**
     * Changes the generator which fills a chunk no loader knows about.
     * <p>
     * Chunks which are already loaded are not affected. A generator is asked for a chunk exactly
     * once, when that chunk is created, so changing it later changes the parts of the world which
     * are not there yet.
     * </p>
     *
     * @param generator the new generator, null to let chunks without a loader stay empty
     */
    @Override
    public void setGenerator(@Nullable Generator generator) {
        this.generation.generator(generator);
    }

    /**
     * Runs a generator over the chunk at the given position.
     * <p>
     * The chunk is loaded first if it is not there yet, and the generator is then applied on top of
     * what the chunk already holds, so this adds to a world rather than replacing it. The work runs
     * off the calling thread because a generator is allowed to take as long as it wants.
     * </p>
     *
     * @param chunkX    the chunk X
     * @param chunkZ    the chunk Z
     * @param generator the generator to run over the chunk
     * @return a future completed once the chunk carries the result, completed exceptionally if the
     * chunk cannot be loaded, if it is unloaded before the generator can run, or if the generator
     * itself fails
     */
    @Override
    @ApiStatus.Experimental
    public CompletableFuture<Void> generateChunk(int chunkX, int chunkZ, Generator generator) {
        Objects.requireNonNull(generator, "the generator cannot be null");
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                final Chunk chunk = loadChunk(chunkX, chunkZ).join();
                if (!chunk.isLoaded()) {
                    throw new FalcoInstanceException("the chunk " + chunkX + ":" + chunkZ
                            + " was unloaded before the generator could run over it");
                }
                this.generation.apply(chunk, generator);
                refreshLastBlockChangeTime();
                chunk.sendChunk();
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    @Override
    public void enableAutoChunkLoad(boolean enable) {
        this.autoChunkLoad = enable;
    }

    @Override
    public boolean hasEnabledAutoChunkLoad() {
        return this.autoChunkLoad;
    }

    @Override
    public boolean isInVoid(Point point) {
        return point.y() < getCachedDimensionType().minY() - VOID_DEPTH;
    }

    /**
     * Gets the time at which the last block of this instance changed.
     * <p>
     * Only usable as a delta against another reading of the same clock. Note that the block batches
     * of Minestom refresh this only for an {@code InstanceContainer}, so a batch applied to this
     * instance does not move the value.
     * </p>
     *
     * @return the time of the last block change in nanoseconds
     */
    public long getLastBlockChangeTime() {
        return this.lastBlockChangeTime;
    }

    /**
     * Records that a block of this instance changed.
     * <p>
     * Needed when blocks are written through a {@link Chunk} directly, which bypasses the instance.
     * </p>
     */
    public void refreshLastBlockChangeTime() {
        this.lastBlockChangeTime = System.nanoTime();
    }

    /**
     * Runs one tick of this instance.
     * <p>
     * Beyond what the base class does, this clears the recursion guard of the block writes, which
     * is scoped to a single tick.
     * </p>
     * <p>
     * Chunks and entities are not ticked here. They are ticked by the thread dispatcher of the
     * server process through the partitions created when a chunk is loaded.
     * </p>
     *
     * @param time the tick time in milliseconds, usable as a delta only
     */
    @Override
    public void tick(long time) {
        super.tick(time);
        this.currentlyChangingBlocks.clear();
    }
}
