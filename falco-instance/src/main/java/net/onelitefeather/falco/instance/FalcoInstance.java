package net.onelitefeather.falco.instance;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
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
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockEntityType;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.instance.generator.GeneratorImpl;
import net.minestom.server.instance.palette.Palette;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
     * <p>
     * This map is also the lock of a chunk position. Every transition of a position — starting a
     * load, publishing its result, unloading the chunk again — happens inside a
     * {@link ConcurrentHashMap#compute} on the index of that position, which serialises them without
     * putting a monitor over the whole instance. That is what the entry of a position is worth far
     * more than the future it holds: without it, an unload and the load it races can both believe
     * they went first, and the chunk which loses ends up in the instance with its loaded flag
     * already cleared, where nothing will ever unload it again.
     * </p>
     */
    private final Map<Long, CompletableFuture<Chunk>> loadingChunks = new ConcurrentHashMap<>();

    /**
     * The section modifiers a generator produced for chunks which were not loaded at the time,
     * keyed by the chunk index of the chunk they belong to.
     * <p>
     * A generator may write outside the chunk it was asked about through
     * {@link GenerationUnit#fork(java.util.function.Consumer)}. Those writes cannot be applied yet
     * when their target does not exist, and dropping them would make a generator produce different
     * worlds depending on the order in which chunks happened to be requested.
     * </p>
     */
    private final Map<Long, List<GeneratorImpl.SectionModifierImpl>> generationForks = new ConcurrentHashMap<>();

    /**
     * The registries the biomes of a generated chunk are looked up in.
     * <p>
     * Kept here rather than read from {@link MinecraftServer} so an instance built against a process
     * which is not the global one generates against the registries of that process.
     * </p>
     */
    private final Registries registries;

    /**
     * The generator which fills a chunk no loader knows about, null while the world stays empty.
     */
    private volatile @Nullable Generator generator;

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
     * The loader chunks of this instance are read from and written to, a no-op loader when the
     * instance was built without one.
     * <p>
     * Volatile for the same reason as {@link #chunkSupplier}: written by a public unsynchronized
     * setter, read on the load path from another thread, and the value is a caller object whose
     * construction has to be visible to that reader.
     * </p>
     */
    private volatile ChunkLoader chunkLoader;

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
            for (Long index : List.copyOf(this.loadingChunks.keySet())) discardRunningLoad(index);
            for (Chunk chunk : List.copyOf(this.chunks.values())) unloadChunk(chunk);
            if (this.loadingChunks.isEmpty() && this.chunks.isEmpty()) {
                // A fork whose target chunk was never requested waits forever, and after this there
                // is nothing left it could wait for.
                this.generationForks.clear();
                return;
            }
        }
        this.generationForks.clear();
        LOGGER.warn("chunks kept arriving while the instance {} was unregistered; {} chunks and {} loads are left behind",
                getUuid(), this.chunks.size(), this.loadingChunks.size());
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
        final CompletableFuture<Chunk> running = this.loadingChunks.remove(index);
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
        if (chunk.isLoaded()) writeBlock(requireManagedChunk(chunk), x, y, z, block, null, null, doBlockUpdates, 0);
    }

    @Override
    public boolean placeBlock(BlockHandler.Placement placement, boolean doBlockUpdates) {
        final Point blockPosition = placement.getBlockPosition();
        final Chunk chunk = getChunkAt(blockPosition);
        if (chunk == null || !chunk.isLoaded()) return false;
        writeBlock(requireManagedChunk(chunk), blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ(),
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
        writeBlock(requireManagedChunk(chunk), blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ(), resultBlock,
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
    private void writeBlock(DynamicChunk chunk, int x, int y, int z, Block block,
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
            writeBlock(requireManagedChunk(neighbourChunk), neighbourX, neighbourY, neighbourZ, updated, null, null,
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
     * future. The decision is taken inside a {@link ConcurrentHashMap#compute} on the position, and
     * the chunk map is read a second time in there. Without that second read a caller which looked
     * at the chunk map just before a load published, and reached this point just after that load
     * removed its entry, would start a second load for a position which already has a chunk. The
     * second chunk then replaces the first one in the map and the first one is orphaned: still
     * marked as loaded, still holding its tick partition and its viewers, and no longer reachable.
     * </p>
     * <p>
     * The work itself starts after the decision, never inside it. A loader without parallel support
     * runs on the calling thread, and a nested {@code compute} on the same map would deadlock.
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

        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        final AtomicReference<Chunk> published = new AtomicReference<>();
        final CompletableFuture<Chunk> slot = this.loadingChunks.compute(index, (_, running) -> {
            if (running != null) return running;
            final Chunk cached = this.chunks.get(index);
            if (cached != null) {
                published.set(cached);
                return null;
            }
            return own;
        });
        final Chunk cached = published.get();
        if (cached != null) return CompletableFuture.completedFuture(cached);
        if (slot != own) return slot;

        final ChunkLoader loader = this.chunkLoader;
        if (loader.supportsParallelLoading()) {
            Thread.startVirtualThread(() -> completeLoad(index, chunkX, chunkZ, loader, own));
        } else {
            // A loader without parallel support is read on the calling thread, which keeps a
            // `loadChunk(…).join()` from a tick free of a thread hand-off it would only wait for.
            completeLoad(index, chunkX, chunkZ, loader, own);
        }
        return own;
    }

    /**
     * Reads a chunk through the loader, publishes it and completes the waiting future.
     * <p>
     * The chunk is produced first and published second, and the publish may be refused. Everything
     * in between the two is the window in which an unload can decide that this chunk is not wanted
     * any more; a load which is refused therefore has to undo itself rather than complain, which is
     * what the discard below does.
     * </p>
     *
     * @param index  the chunk index of the position, the key in the map of loading chunks
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @param loader the loader the chunk is read from
     * @param future the future handed to the callers waiting for this chunk
     */
    private void completeLoad(long index, int chunkX, int chunkZ, ChunkLoader loader, CompletableFuture<Chunk> future) {
        final DynamicChunk falcoChunk;
        try {
            Chunk chunk = loader.loadChunk(this, chunkX, chunkZ);
            if (chunk == null) {
                chunk = createChunk(chunkX, chunkZ);
                chunk.onGenerate();
            }
            falcoChunk = requireManagedChunk(chunk);
        } catch (Throwable throwable) {
            this.loadingChunks.remove(index, future);
            future.completeExceptionally(throwable);
            return;
        }
        if (!publishChunk(index, falcoChunk, future)) {
            // The chunk was never part of this instance, so there is no map entry and no partition
            // to clean up. The loader is still told, because it created the chunk and may hold
            // bookkeeping for it, which its own documentation allows for explicitly.
            notifyUnloaded(falcoChunk);
            this.chunkLoader.unloadChunk(falcoChunk);
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
     * The loaded flag of the chunk is deliberately set outside, because it calls a hook a subclass
     * may override, and foreign code has no business running while a position is held.
     * </p>
     *
     * @param index  the chunk index of the position
     * @param chunk  the chunk to publish
     * @param future the future of this load, which has to still be the entry of the position
     * @return true if the chunk is now part of this instance, false if the load was claimed
     */
    private boolean publishChunk(long index, DynamicChunk chunk, CompletableFuture<Chunk> future) {
        final AtomicBoolean published = new AtomicBoolean();
        this.loadingChunks.compute(index, (_, running) -> {
            if (running != future) return running;
            this.chunks.put(index, chunk);
            MinecraftServer.process().dispatcher().createPartition(chunk);
            published.set(true);
            return null;
        });
        return published.get();
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
        final Generator current = this.generator;
        if (current != null && chunk.shouldGenerate()) {
            applyGenerator(chunk, current);
        } else {
            applyPendingForks(chunk);
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
    private DynamicChunk requireManagedChunk(Chunk chunk) {
        if (this.chunkLoaded == null || this.chunkUnloaded == null) {
            if (chunk instanceof FalcoChunk falcoChunk) return falcoChunk;
            throw new FalcoInstanceException("this instance only manages " + FalcoChunk.class.getName()
                    + ", but its chunk supplier produced a " + chunk.getClass().getName()
                    + "; the lifecycle hooks of any other chunk cannot be reached from this package."
                    + " Configure setChunkLifecycle if you own the chunk type and can reach them");
        }
        if (chunk instanceof DynamicChunk dynamicChunk) return dynamicChunk;
        throw new FalcoInstanceException("this instance manages subtypes of "
                + DynamicChunk.class.getName() + ", but its chunk supplier produced a "
                + chunk.getClass().getName());
    }

    /**
     * Tells a chunk that it is now part of this instance.
     *
     * @param chunk the chunk which finished loading
     */
    private void notifyLoaded(DynamicChunk chunk) {
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
    private void notifyUnloaded(DynamicChunk chunk) {
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
        final DynamicChunk falcoChunk = requireManagedChunk(chunk);
        final int chunkX = falcoChunk.getChunkX();
        final int chunkZ = falcoChunk.getChunkZ();
        final long index = CoordConversion.chunkIndex(chunkX, chunkZ);
        final AtomicBoolean removed = new AtomicBoolean();
        this.loadingChunks.compute(index, (_, running) -> {
            if (this.chunks.remove(index, falcoChunk)) {
                notifyUnloaded(falcoChunk);
                MinecraftServer.process().dispatcher().deletePartition(falcoChunk);
                removed.set(true);
            }
            return running;
        });
        if (!removed.get()) return;
        falcoChunk.sendPacketToViewers(new UnloadChunkPacket(chunkX, chunkZ));
        EventDispatcher.call(new InstanceChunkUnloadEvent(this, falcoChunk));
        getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES).forEach(Entity::remove);
        this.chunkLoader.unloadChunk(falcoChunk);
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
     * Gets the generator which fills a chunk no loader knows about.
     *
     * @return the current generator, null if chunks without a loader stay empty
     */
    @Override
    public @Nullable Generator generator() {
        return this.generator;
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
        this.generator = generator;
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
                applyGenerator(chunk, generator);
                chunk.sendChunk();
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /**
     * Runs a generator over a chunk and commits everything it produced in one step.
     * <p>
     * The generator writes into copies of the palettes of the chunk, not into the palettes
     * themselves, and the copies are moved over only after the generator returned. That is the whole
     * difference to {@code InstanceContainer#generateChunk(Chunk, Generator)}, which hands the live
     * palettes over and catches whatever the generator throws into the exception manager of the
     * server. A generator which fails halfway there leaves a chunk that is half built, published and
     * reported as loaded, and the caller who asked for the chunk is told nothing. Here the failure
     * travels to that caller and the chunk is exactly as it was.
     * </p>
     * <p>
     * The copies cost one palette clone per section. On a chunk which is still empty — the case
     * which matters, because that is where a generator normally runs — a palette is in its single
     * value mode and holds no array at all, so the clone is a few bytes.
     * </p>
     * <p>
     * The write lock of the chunk is held for the commit only. Minestom holds it across the whole
     * generator instead, which stops every read and every write of that chunk for as long as the
     * generator runs.
     * </p>
     *
     * @param chunk     the chunk to fill
     * @param generator the generator to run over the chunk
     */
    private void applyGenerator(Chunk chunk, Generator generator) {
        final List<Section> sections = chunk.getSections();
        final int sectionCount = sections.size();
        final GeneratorImpl.GenSection[] staged = new GeneratorImpl.GenSection[sectionCount];
        Arrays.setAll(staged, index -> {
            final Section section = sections.get(index);
            return new GeneratorImpl.GenSection(section.blockPalette().clone(), section.biomePalette().clone());
        });
        final GeneratorImpl.UnitImpl unit = GeneratorImpl.chunk(this.registries.biome(), staged,
                chunk.getChunkX(), chunk.getMinSection(), chunk.getChunkZ());

        generator.generate(unit);

        chunk.lockWriteLock();
        try {
            for (int index = 0; index < sectionCount; index++) {
                final Section section = sections.get(index);
                final GeneratorImpl.GenSection generated = staged[index];
                section.blockPalette().copyFrom(generated.blocks());
                section.biomePalette().copyFrom(generated.biomes());
                writeSpecialBlocks(chunk, generated.specials(),
                        (chunk.getMinSection() + index) * Chunk.CHUNK_SECTION_SIZE);
            }
            chunk.invalidate();
        } finally {
            chunk.unlockWriteLock();
        }

        applyForks(chunk, unit);
        applyPendingForks(chunk);
        refreshLastBlockChangeTime();
    }

    /**
     * Writes the blocks of a generated section which need more than a palette entry.
     * <p>
     * A palette holds a block state and nothing else, so a block which carries nbt, a handler or a
     * block entity has to be written through the chunk as well. The generator collected those
     * separately, keyed by a position relative to its section.
     * </p>
     * <p>
     * The caller has to hold the write lock of the chunk.
     * </p>
     *
     * @param chunk         the chunk which receives the blocks
     * @param specials      the blocks of the section which need their own entry
     * @param sectionStartY the block Y at which the section begins
     */
    private void writeSpecialBlocks(Chunk chunk, Int2ObjectMap<Block> specials, int sectionStartY) {
        if (specials.isEmpty()) return;
        for (Int2ObjectMap.Entry<Block> entry : specials.int2ObjectEntrySet()) {
            final int position = entry.getIntKey();
            chunk.setBlock(CoordConversion.chunkBlockIndexGetX(position),
                    CoordConversion.chunkBlockIndexGetY(position) + sectionStartY,
                    CoordConversion.chunkBlockIndexGetZ(position),
                    entry.getValue());
        }
    }

    /**
     * Delivers the writes a generator made outside the chunk it was asked about.
     * <p>
     * A fork which lands in a chunk that exists is applied right away, and one which lands in a
     * chunk that does not is remembered until that chunk is created. Dropping the second kind is
     * what would make a generator produce a different world depending on the order in which chunks
     * were requested, which is the property a fork exists to avoid.
     * </p>
     *
     * @param chunk the chunk the generator was asked about
     * @param unit  the unit the generator wrote into
     */
    private void applyForks(Chunk chunk, GeneratorImpl.UnitImpl unit) {
        final int chunkX = chunk.getChunkX();
        final int chunkZ = chunk.getChunkZ();
        for (GeneratorImpl.UnitImpl fork : unit.forks()) {
            if (!(fork.modifier() instanceof GeneratorImpl.AreaModifierImpl area)) continue;
            for (GenerationUnit section : area.sections()) {
                if (!(section.modifier() instanceof GeneratorImpl.SectionModifierImpl modifier)) continue;
                if (modifier.genSection().blocks().count() == 0) continue;
                final Point start = section.absoluteStart();
                if (start.chunkX() == chunkX && start.chunkZ() == chunkZ) {
                    applyFork(chunk, modifier);
                    continue;
                }
                final Chunk target = getChunkAt(start);
                if (target != null && target.isLoaded()) {
                    applyFork(target, modifier);
                    target.sendChunk();
                    continue;
                }
                this.generationForks.compute(CoordConversion.chunkIndex(start), (_, modifiers) -> {
                    final List<GeneratorImpl.SectionModifierImpl> pending =
                            modifiers == null ? new ArrayList<>() : modifiers;
                    pending.add(modifier);
                    return pending;
                });
            }
        }
    }

    /**
     * Applies the forks which were waiting for the given chunk to exist.
     *
     * @param chunk the chunk which just came into being
     */
    private void applyPendingForks(Chunk chunk) {
        final long index = CoordConversion.chunkIndex(chunk.getChunkX(), chunk.getChunkZ());
        this.generationForks.compute(index, (_, modifiers) -> {
            if (modifiers != null) {
                for (GeneratorImpl.SectionModifierImpl modifier : modifiers) applyFork(chunk, modifier);
            }
            return null;
        });
    }

    /**
     * Writes one section of a fork into a chunk.
     *
     * @param chunk    the chunk which receives the blocks
     * @param modifier the section of the fork to write
     */
    private void applyFork(Chunk chunk, GeneratorImpl.SectionModifierImpl modifier) {
        final int sectionStartY = modifier.start().blockY();
        chunk.lockWriteLock();
        try {
            final Palette blocks = chunk.getSectionAt(sectionStartY).blockPalette();
            // A forked section marks an untouched position with a zero, so every block it does carry
            // was stored with its state raised by one and has to be lowered again here.
            modifier.genSection().blocks().getAllPresent((x, y, z, value) -> blocks.set(x, y, z, value - 1));
            writeSpecialBlocks(chunk, modifier.genSection().specials(), sectionStartY);
            chunk.invalidate();
        } finally {
            chunk.unlockWriteLock();
        }
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
