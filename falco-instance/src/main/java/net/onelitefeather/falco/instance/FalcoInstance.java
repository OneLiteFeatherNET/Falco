package net.onelitefeather.falco.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.registry.Registries;
import net.minestom.server.timer.SchedulerManager;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.utils.chunk.ChunkSupplier;
import net.minestom.server.world.DimensionType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The {@link FalcoInstance} class is a world of a Minestom server which cleans up after itself, and
 * it is a facade: it declares four references and nothing else.
 *
 * <h2>The four parts and where the line between them runs</h2>
 * <ul>
 *   <li>{@link ChunkRegistry} — which chunk sits at which position, and which position is busy. It
 *   holds the two maps and the four transitions between them, and it is the only thing that decides
 *   whether a position is free, loading or taken.</li>
 *   <li>{@link ChunkLifecycle} — everything that happens to a chunk between not existing and not
 *   existing again: create, read, publish, notify, unload, and the two settings that steer them. It
 *   uses the registry to make those transitions; it does not duplicate them. {@link ChunkGeneration}
 *   is its collaborator rather than a fifth part of this class, because a chunk is generated exactly
 *   once and that once is inside its load, and it is reached through
 *   {@link ChunkLifecycle#generation()}.</li>
 *   <li>{@link BlockWriter} — a block write and everything it wakes: the neighbours, the packets, the
 *   event, the recursion guard and the change timestamp.</li>
 *   <li>{@link ChunkPersistence} — the loader, the four save paths, the read and the unload
 *   notification, plus the two settings the shutdown of this instance asks it about.</li>
 * </ul>
 * <p>
 * The line between them is the one worth stating, because it is the one a later change is most likely
 * to blur: a part holds whatever it needs to answer its own question, and it never holds a reference
 * to another part it was not handed. {@link ChunkLifecycle} is handed the registry, the persistence
 * and the generation rather than reaching for them, which is what lets each of the four be driven on
 * its own by a test — US-3.02, and the reason the split was worth six commits.
 * </p>
 * <p>
 * That is a rule about references, not a claim that the four never look at each other. Two of them do,
 * both through this class rather than through a field, and they are named here because a rule with two
 * exceptions nobody wrote down is a rule that gets discovered by breaking it:
 * </p>
 * <ul>
 *   <li>{@link BlockWriter#setBlock(int, int, int, Block, boolean)} asks
 *   {@link ChunkLifecycle#autoLoad()} whether it may load the chunk it is about to write into.
 *   The setting steers a load, so it belongs to the lifecycle; the question is asked on the write
 *   path, so the writer has to ask it.</li>
 *   <li>{@link ChunkLifecycle#create(int, int)} calls {@link #refreshLastBlockChangeTime()} after a
 *   generator filled a chunk, and this class forwards that to {@link BlockWriter}, which keeps the
 *   timestamp because every other writer of it is a block write.</li>
 * </ul>
 * <p>
 * Both couplings predate this class becoming a facade and neither is a reference: each goes through a
 * public method of the other part, so either part can still be constructed and driven alone. A change
 * to {@code autoLoad} or to the timestamp has to be checked against the part on the other side.
 * </p>
 *
 * <h2>Why this class declares nothing but its parts</h2>
 * <p>
 * A facade that keeps state of its own is the class it replaced with delegation in front of it, and
 * the difference is invisible from the outside: every method below still reads like a one-liner while
 * a fifth field quietly makes two parts disagree. That is not left to a reader.
 * {@code InstanceFacadeTest} asks {@code getDeclaredFields()} on every build and fails if this class
 * declares anything but one {@code final} reference per part. Anything that looks like it belongs here
 * belongs in one of the four instead; if it belongs in none of them, the split is wrong and needs a
 * fifth part rather than a field.
 * </p>
 * <p>
 * What that test asserts is the declaration and only the declaration, which is narrower than <em>the
 * facade holds no state</em>: {@link ChunkPersistence#saveOnShutdown()} and
 * {@link ChunkPersistence#ownsLoader()} are read by {@link #shutdown(InstanceManager)} and by nothing
 * else, so a value the facade acts on does live one hop away. That is stated rather than glossed over,
 * and the reason it is still the right home is written at {@link ChunkPersistence}.
 * </p>
 *
 * <h2>What being an {@link Instance} rather than a container costs</h2>
 * <p>
 * This class extends {@link Instance} directly instead of {@code InstanceContainer}. Deriving from the
 * container looks cheaper but leads nowhere: the chunk lifecycle hooks it would have to override are
 * {@code protected} members of {@code net.minestom.server.instance}, so a subclass in this package
 * cannot reach them. Starting from {@link Instance} makes the same barrier visible once, at the chunk,
 * where {@link FalcoChunk} answers it.
 * </p>
 * <p>
 * Four places in Minestom branch on {@code instanceof InstanceContainer} and quietly take a different
 * path for any other instance. The split changed none of them, so all four still apply exactly as they
 * did. Three are harmless here, one is not:
 * </p>
 * <ul>
 *   <li>{@code InstanceManager#unregisterInstance} does not unload the chunks of a foreign
 *   instance, which leaks every chunk the instance ever loaded. {@link #unregister(InstanceManager)}
 *   is the answer and it is still the reason this class exists — the four parts are how it is built,
 *   not why.</li>
 *   <li>{@code SharedInstance} is typed on the container throughout, so this instance cannot back
 *   one. That is a missing feature rather than a defect, and it is refused by the compiler.</li>
 *   <li>The {@code Chunk} constructor asks the instance for its shared instances and gets an empty
 *   list here. Since there are no shared instances, an empty list is the correct answer.</li>
 *   <li>The block batches skip {@code refreshLastBlockChangeTime()} for a foreign instance.
 *   {@link BlockWriter} keeps the timestamp, but nothing outside it refreshes it, so batch copies
 *   must not rely on it.</li>
 * </ul>
 * <p>
 * Where this class deviates from {@code InstanceContainer} on purpose — the generator that runs
 * against staged palettes instead of the live ones, the publish and the unload of one position that
 * are made mutually exclusive, and the block write that takes the lock of one chunk instead of a
 * monitor on the whole world — the reasoning now sits with the code it is about, in
 * {@link ChunkGeneration#apply(Chunk, Generator)}, {@link ChunkLifecycle} and {@link BlockWriter}
 * respectively. It moved with them rather than being dropped: a comment about a lock is worth
 * something only next to the statement that takes it.
 * </p>
 * <p>
 * On threading this class promises no more than Minestom does, and for a reason worth stating: the
 * parallelism of chunk and entity ticking lives in the global {@code ThreadDispatcher} of the server
 * process, not in the instance. Replacing the instance cannot make ticking faster.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 2.1.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public class FalcoInstance extends Instance {

    private static final Logger LOGGER = LoggerFactory.getLogger(FalcoInstance.class);

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
     * are, in {@link ChunkRegistry}, and the steps handed to {@link ChunkRegistry#publish} and
     * {@link ChunkRegistry#remove} come from {@link ChunkLifecycle}.
     * </p>
     */
    private final ChunkRegistry registry;

    /**
     * Everything this instance does when a block changes.
     * <p>
     * The three entry points, the write, the neighbour pass, the packets, the event, the recursion
     * guard and the change timestamp used to be members of this class, and the ordering they promise —
     * the write lock of one chunk held across the write and across nothing else — was a property of
     * one {@code private} method nobody could drive on its own. {@link BlockWriter} carries all of it,
     * which is what makes that ordering something a test can measure.
     * </p>
     */
    private final BlockWriter blockWriter;

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

    /**
     * Everything that happens to a chunk between not existing and not existing again, and what fills a
     * chunk no loader knows about.
     * <p>
     * The create, the read, the publish, the unload and the two settings which steer them —
     * the chunk supplier and the auto load flag — used to be {@code private} members of this class,
     * which meant that a publish could only be reached by driving a whole load through a loader. They
     * are a responsibility of their own and {@link ChunkLifecycle} carries it, which is what makes
     * each step reachable and measurable one at a time.
     * </p>
     * <p>
     * {@link ChunkGeneration} is held by this part rather than beside it, and is reached through
     * {@link ChunkLifecycle#generation()}. A chunk is generated exactly once and that once is inside
     * its load, so a field here would have been a fifth reference nothing but the lifecycle ever
     * touches.
     * </p>
     */
    private final ChunkLifecycle lifecycle;

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
        this.registry = new ChunkRegistry();
        this.persistence = new ChunkPersistence(loader);
        this.blockWriter = new BlockWriter(this);
        // The registries are handed straight through rather than kept. They are what the biomes of a
        // generated chunk are looked up in, generation is the only thing that asks, and this is the
        // only line that hands them over — a field for them would be state of the facade that nothing
        // reads twice. Taken as an argument rather than read from MinecraftServer so an instance built
        // against a process which is not the global one generates against the registries of that
        // process. ChunkGeneration is handed getChunkAt rather than this instance, because a neighbour
        // a fork writes into is the only thing generation ever needs a world for.
        this.lifecycle = new ChunkLifecycle(this, this.registry, this.persistence,
                new ChunkGeneration(registries, this::getChunkAt));
        // Last, and outside every constructor above: loadInstance may call back into this instance,
        // and a callback into an object whose parts are not all built yet reads one of them as null.
        this.persistence.loader().loadInstance(this);
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
        if (this.persistence.saveOnShutdown()) {
            try {
                saveChunksToStorage().join();
            } catch (Throwable throwable) {
                throw new FalcoInstanceException("the chunks of the instance " + getUuid()
                        + " could not be saved, so it was left registered and loaded", throwable);
            }
        }
        unregister(instanceManager);

        if (!this.persistence.ownsLoader()) return;

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
            for (Long index : this.registry.loadingPositions()) this.lifecycle.discard(index);
            for (Chunk chunk : this.registry.snapshot()) this.lifecycle.unload(chunk);
            if (this.registry.idle()) {
                // A fork whose target chunk was never requested waits forever, and after this there
                // is nothing left it could wait for.
                this.lifecycle.generation().clearPending();
                return;
            }
        }
        this.lifecycle.generation().clearPending();
        LOGGER.warn("chunks kept arriving while the instance {} was unregistered; {} chunks and {} loads are left behind",
                getUuid(), this.registry.size(), this.registry.loading());
    }

    /**
     * Hands out the registry of chunk positions of this instance.
     * <p>
     * Exposed because a facade whose parts cannot be reached is a facade whose parts cannot be
     * tested, which is the whole reason this class was split.
     * </p>
     *
     * @return the registry of this instance
     * @since 0.4.0
     */
    public ChunkRegistry registry() {
        return this.registry;
    }

    /**
     * Hands out the lifecycle of the chunks of this instance.
     *
     * @return the lifecycle of this instance
     * @since 0.4.0
     */
    public ChunkLifecycle lifecycle() {
        return this.lifecycle;
    }

    /**
     * Hands out the block writer of this instance.
     * <p>
     * Exposed for the same reason as {@link #registry()}: the ordering {@link BlockWriter} promises
     * around the lock of a chunk can only be measured by a caller which can drive one write on its
     * own, and no entry point of this class offers that.
     * </p>
     *
     * @return the block writer of this instance
     * @since 0.4.0
     */
    public BlockWriter blockWriter() {
        return this.blockWriter;
    }

    @Override
    public void setBlock(int x, int y, int z, Block block, boolean doBlockUpdates) {
        this.blockWriter.setBlock(x, y, z, block, doBlockUpdates);
    }

    @Override
    public boolean placeBlock(BlockHandler.Placement placement, boolean doBlockUpdates) {
        return this.blockWriter.placeBlock(placement, doBlockUpdates);
    }

    @Override
    public boolean breakBlock(Player player, Point blockPosition, BlockFace blockFace, boolean doBlockUpdates) {
        return this.blockWriter.breakBlock(player, blockPosition, blockFace, doBlockUpdates);
    }

    @Override
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        return this.lifecycle.retrieve(chunkX, chunkZ);
    }

    @Override
    public CompletableFuture<@Nullable Chunk> loadOptionalChunk(int chunkX, int chunkZ) {
        final Chunk loaded = getChunk(chunkX, chunkZ);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);
        if (!this.lifecycle.autoLoad()) return CompletableFuture.completedFuture(null);
        return this.lifecycle.retrieve(chunkX, chunkZ);
    }

    /**
     * Removes a chunk from this instance.
     * <p>
     * What that means step by step, and which of the steps runs while the position of the chunk is
     * held, is documented on {@link ChunkLifecycle#unload(Chunk)}; this is the door
     * {@code Instance} demands.
     * </p>
     * <p>
     * Unloading the same chunk twice does nothing the second time, which makes this usable in a
     * cleanup path that may run more than once.
     * </p>
     *
     * @param chunk the chunk to remove, has to be a {@link FalcoChunk} unless a lifecycle pair was
     *              installed through {@link #setChunkLifecycle(Consumer, Consumer)}
     * @throws FalcoInstanceException if this instance cannot drive the lifecycle of that chunk
     */
    @Override
    public void unloadChunk(Chunk chunk) {
        this.lifecycle.unload(chunk);
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
        this.lifecycle.supplier(chunkSupplier);
    }

    @Override
    public ChunkSupplier getChunkSupplier() {
        return this.lifecycle.supplier();
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
            instance.persistence.saveOnShutdown(this.saveOnShutdown);
            instance.persistence.ownsLoader(this.ownsLoader);

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
     * type can reach both hooks — the type is theirs, so the {@code protected} pair is in reach of
     * its own package — and connects them here, without either module having to know the other.
     * </p>
     * <pre>{@code
     * instance.setChunkSupplier(MyChunk::new);
     * instance.setChunkLifecycle(
     *         chunk -> ((MyChunk) chunk).markLoaded(),
     *         chunk -> ((MyChunk) chunk).markUnloaded());
     * }</pre>
     * <p>
     * The lighting chunk of {@code falco-light} used to be the worked example here and is one no
     * longer: since US-3.06 {@code FalcoLightingChunk} extends {@link FalcoChunk}, so
     * {@code instance.setChunkSupplier(scheduler.supplier())} is the whole setup and this method has
     * nothing left to do for it. What remains for this pair is the case it was always the general
     * answer to — a chunk type this repository never sees.
     * </p>
     * <p>
     * Both halves are one call so the pair cannot be set half way. Set them before the first chunk
     * is loaded; a chunk that was published under one lifecycle is not told about a later change.
     * The instance stops checking for {@link FalcoChunk} from here on and requires only a
     * {@link Chunk}, so an unsuitable supplier now fails on the cast inside your own function rather
     * than with a message from this class.
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
        this.lifecycle.hooks(onLoaded, onUnloaded);
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
        return this.lifecycle.generation().generator();
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
        this.lifecycle.generation().generator(generator);
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
                this.lifecycle.generation().apply(chunk, generator);
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
        this.lifecycle.autoLoad(enable);
    }

    @Override
    public boolean hasEnabledAutoChunkLoad() {
        return this.lifecycle.autoLoad();
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
        return this.blockWriter.lastChangeTime();
    }

    /**
     * Records that a block of this instance changed.
     * <p>
     * Needed when blocks are written through a {@link Chunk} directly, which bypasses the instance.
     * </p>
     */
    public void refreshLastBlockChangeTime() {
        this.blockWriter.refreshLastChangeTime();
    }

    /**
     * Runs one tick of this instance.
     * <p>
     * Beyond what the base class does, this ends the tick of {@link BlockWriter}, which clears the
     * recursion guard of the block writes; the guard is scoped to a single tick.
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
        this.blockWriter.endTick();
    }
}
