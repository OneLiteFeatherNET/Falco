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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
 * The world generator is not reimplemented in this version. Handing one over raises
 * {@link FalcoInstanceException} rather than storing a generator that would never run; a world here
 * comes from its {@link ChunkLoader} or stays empty.
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
 * @version 1.0.0
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
     * The loaded chunks, keyed by the chunk index of their position.
     * <p>
     * A plain concurrent hash map rather than the synchronised long map of the container: chunk
     * streaming is a lookup-dominated access pattern, and the copy-on-write map underneath the
     * container pays for every load and unload instead.
     * </p>
     */
    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();

    /**
     * The chunks which are being loaded right now, keyed by chunk index.
     * <p>
     * Holding the future rather than a flag is what makes two concurrent requests for the same
     * chunk share one load instead of racing into two chunk objects.
     * </p>
     */
    private final Map<Long, CompletableFuture<Chunk>> loadingChunks = new ConcurrentHashMap<>();

    /**
     * The blocks changed since the last tick, used to break recursion between block handlers.
     * <p>
     * Concurrent rather than a plain map behind a lock that guarded nothing, which is the shape the
     * container has.
     * </p>
     */
    private final Map<BlockVec, Block> currentlyChangingBlocks = new ConcurrentHashMap<>();

    private ChunkSupplier chunkSupplier = FalcoChunk::new;

    private ChunkLoader chunkLoader;

    private volatile boolean autoChunkLoad = true;

    private volatile long lastBlockChangeTime;

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
        this.chunkLoader = Objects.requireNonNullElseGet(loader, ChunkLoader::noop);
        this.chunkLoader.loadInstance(this);
        this.lastBlockChangeTime = System.nanoTime();
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
     *
     * @param instanceManager the manager this instance is registered with
     * @throws IllegalStateException if a player is still online in this instance
     */
    public void unregister(InstanceManager instanceManager) {
        if (isRegistered()) instanceManager.unregisterInstance(this);
        for (Chunk chunk : List.copyOf(this.chunks.values())) unloadChunk(chunk);
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
        if (chunk.isLoaded()) writeBlock(requireFalcoChunk(chunk), x, y, z, block, null, null, doBlockUpdates, 0);
    }

    @Override
    public boolean placeBlock(BlockHandler.Placement placement, boolean doBlockUpdates) {
        final Point blockPosition = placement.getBlockPosition();
        final Chunk chunk = getChunkAt(blockPosition);
        if (chunk == null || !chunk.isLoaded()) return false;
        writeBlock(requireFalcoChunk(chunk), blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ(),
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
        writeBlock(requireFalcoChunk(chunk), blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ(), resultBlock,
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
     *
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
            writeBlock(requireFalcoChunk(neighbourChunk), neighbourX, neighbourY, neighbourZ, updated, null, null,
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
     * Two callers asking for the same chunk at the same time share one load: the first one to put
     * its future into the map of loading chunks performs the work, everyone else receives that same
     * future.
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
        final Chunk loaded = this.chunks.get(index);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);

        final CompletableFuture<Chunk> future = new CompletableFuture<>();
        final CompletableFuture<Chunk> running = this.loadingChunks.putIfAbsent(index, future);
        if (running != null) return running;

        final ChunkLoader loader = this.chunkLoader;
        if (loader.supportsParallelLoading()) {
            Thread.startVirtualThread(() -> completeLoad(index, chunkX, chunkZ, loader, future));
        } else {
            // A loader without parallel support is read on the calling thread, which keeps a
            // `loadChunk(…).join()` from a tick free of a thread hand-off it would only wait for.
            completeLoad(index, chunkX, chunkZ, loader, future);
        }
        return future;
    }

    /**
     * Reads a chunk through the loader, publishes it and completes the waiting future.
     *
     * @param index  the chunk index of the position, the key in the map of loading chunks
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @param loader the loader the chunk is read from
     * @param future the future handed to the callers waiting for this chunk
     */
    private void completeLoad(long index, int chunkX, int chunkZ, ChunkLoader loader, CompletableFuture<Chunk> future) {
        try {
            Chunk chunk = loader.loadChunk(this, chunkX, chunkZ);
            if (chunk == null) chunk = createChunk(chunkX, chunkZ);
            final FalcoChunk falcoChunk = requireFalcoChunk(chunk);
            cacheChunk(falcoChunk);
            falcoChunk.markLoaded();
            this.loadingChunks.remove(index, future);
            future.complete(falcoChunk);
            EventDispatcher.call(new InstanceChunkLoadEvent(this, falcoChunk));
        } catch (Throwable throwable) {
            this.loadingChunks.remove(index, future);
            future.completeExceptionally(throwable);
        }
    }

    /**
     * Creates an empty chunk through the chunk supplier of this instance.
     * <p>
     * No generator runs here. This version does not reimplement the generator path, so a chunk which
     * no loader knows about stays empty.
     * </p>
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return the created chunk
     */
    protected Chunk createChunk(int chunkX, int chunkZ) {
        final Chunk chunk = this.chunkSupplier.createChunk(this, chunkX, chunkZ);
        if (chunk == null) {
            throw new FalcoInstanceException("the chunk supplier returned null for chunk " + chunkX + ":" + chunkZ);
        }
        return chunk;
    }

    /**
     * Puts a chunk into the chunk map and gives it a tick partition.
     *
     * @param chunk the chunk to publish
     */
    private void cacheChunk(Chunk chunk) {
        this.chunks.put(CoordConversion.chunkIndex(chunk.getChunkX(), chunk.getChunkZ()), chunk);
        MinecraftServer.process().dispatcher().createPartition(chunk);
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
    private FalcoChunk requireFalcoChunk(Chunk chunk) {
        if (chunk instanceof FalcoChunk falcoChunk) return falcoChunk;
        throw new FalcoInstanceException("this instance only manages " + FalcoChunk.class.getName()
                + ", but its chunk supplier produced a " + chunk.getClass().getName()
                + "; the lifecycle hooks of any other chunk cannot be reached from this package");
    }

    @Override
    public void unloadChunk(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        final FalcoChunk falcoChunk = requireFalcoChunk(chunk);
        final int chunkX = falcoChunk.getChunkX();
        final int chunkZ = falcoChunk.getChunkZ();
        if (this.chunks.remove(CoordConversion.chunkIndex(chunkX, chunkZ), falcoChunk)) {
            falcoChunk.sendPacketToViewers(new UnloadChunkPacket(chunkX, chunkZ));
            EventDispatcher.call(new InstanceChunkUnloadEvent(this, falcoChunk));
            getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES).forEach(Entity::remove);
        }
        falcoChunk.markUnloaded();
        this.chunkLoader.unloadChunk(falcoChunk);
        MinecraftServer.process().dispatcher().deletePartition(falcoChunk);
    }

    @Override
    public @Nullable Chunk getChunk(int chunkX, int chunkZ) {
        return this.chunks.get(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    @Override
    public @UnmodifiableView Collection<Chunk> getChunks() {
        return Collections.unmodifiableCollection(this.chunks.values());
    }

    @Override
    public CompletableFuture<Void> saveInstance() {
        final ChunkLoader loader = this.chunkLoader;
        return runSave(loader.supportsParallelSaving(), () -> loader.saveInstance(this));
    }

    @Override
    public CompletableFuture<Void> saveChunkToStorage(Chunk chunk) {
        final ChunkLoader loader = this.chunkLoader;
        return runSave(loader.supportsParallelSaving(), () -> loader.saveChunk(chunk));
    }

    @Override
    public CompletableFuture<Void> saveChunksToStorage() {
        final ChunkLoader loader = this.chunkLoader;
        final List<Chunk> snapshot = List.copyOf(this.chunks.values());
        return runSave(loader.supportsParallelSaving(), () -> loader.saveChunks(snapshot));
    }

    /**
     * Runs a save either on the calling thread or on a virtual thread.
     *
     * @param parallel true to move the work off the calling thread
     * @param save     the work to perform
     * @return a future completed once the work is done, completed exceptionally if it threw
     */
    private CompletableFuture<Void> runSave(boolean parallel, Runnable save) {
        if (!parallel) {
            try {
                save.run();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                save.run();
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
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
     * Gets the loader chunks of this instance are read from and written to.
     *
     * @return the current chunk loader
     */
    public ChunkLoader getChunkLoader() {
        return this.chunkLoader;
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
        this.chunkLoader = Objects.requireNonNull(chunkLoader, "the chunk loader cannot be null");
    }

    /**
     * Gets the generator of this instance, which is always null.
     * <p>
     * The generator path is not reimplemented in this version. Null is the truthful answer rather
     * than a placeholder, and {@link #setGenerator(Generator)} refuses to make it anything else.
     * </p>
     *
     * @return null, always
     */
    @Override
    public @Nullable Generator generator() {
        return null;
    }

    /**
     * Refuses to take a generator.
     * <p>
     * Storing one would be worse than refusing it: nothing here would ever call it, and the world
     * would come out empty with no hint as to why.
     * </p>
     *
     * @param generator the generator to install, null to clear the generator that is not there
     * @throws FalcoInstanceException if a generator is passed
     */
    @Override
    public void setGenerator(@Nullable Generator generator) {
        if (generator == null) return;
        throw new FalcoInstanceException("this instance does not run a generator yet; "
                + "load the world through a ChunkLoader or use an InstanceContainer for generated worlds");
    }

    /**
     * Refuses to generate a chunk.
     *
     * @param chunkX    the chunk X
     * @param chunkZ    the chunk Z
     * @param generator the generator which should have produced the chunk
     * @return never, the call always throws
     * @throws FalcoInstanceException always
     */
    @Override
    public CompletableFuture<Void> generateChunk(int chunkX, int chunkZ, Generator generator) {
        throw new FalcoInstanceException("this instance does not run a generator yet; "
                + "cannot generate chunk " + chunkX + ":" + chunkZ);
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
