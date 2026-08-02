package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.instance.InstanceChunkUnloadEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import net.minestom.server.utils.chunk.ChunkSupplier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The {@link ChunkLifecycle} class is everything that happens to a chunk between not existing and
 * not existing again: it is created, filled, published, marked and taken away.
 * <p>
 * Every step is a method of its own and every one of them is reachable without the others. That is
 * the whole point of the class and it is US-3.02: {@code publishChunk} and {@code completeLoad} were
 * {@code private} methods of a class of more than 1 300 lines, so the only way to run them was to ask
 * the instance for a chunk. The case they exist for cannot be arranged that way — a publish is
 * refused when an unload claims the position while the loader is still working, and a caller driving
 * the whole load path has no seam to interleave at.
 * </p>
 *
 * <h2>What runs while a position is held, and what does not</h2>
 * <p>
 * Putting a chunk into the registry and giving it a tick partition are one step, taken while the
 * position is held, so an unload of the same position can only run entirely before or entirely after
 * it. Splitting them is what lets Minestom delete a partition that is created a moment later, which
 * leaves the chunk being ticked for the rest of the life of the server even though nothing else knows
 * about it any more.
 * </p>
 * <p>
 * On the publish side the loaded flag of the chunk is deliberately set <em>outside</em> the lock,
 * and the asymmetry with {@link #unload(Chunk)}, which clears it from inside, is not an oversight.
 * {@code Chunk#onLoad()} sets no flag: a chunk reports {@code isLoaded()} from the moment it is
 * constructed, so nothing a reader of the instance can see depends on that hook having run yet. The
 * unload hook does set the flag, and a chunk which has left the registry while still reporting itself
 * as loaded is one every {@code ChunkUtils#isLoaded} check in Minestom believes in — which is why
 * that one step is inside and the other is not.
 * </p>
 * <p>
 * The packet, the event, the entities and the loader follow outside the lock in every case, because
 * all four can call back into the instance, and holding a position while foreign code runs is how two
 * chunks deadlock each other. What the removal step may do is stated on {@link ChunkRegistry} and
 * applies in full, including to the hook a caller installs through
 * {@link FalcoInstance#setChunkLifecycle(Consumer, Consumer)}.
 * </p>
 *
 * <h2>Why this class speaks {@link Chunk} rather than {@link FalcoChunk}</h2>
 * <p>
 * Because {@link FalcoInstance#setChunkLifecycle(Consumer, Consumer)} exists. A caller which owns
 * another chunk type — a lighting chunk from {@code falco-light}, say — hands over the two
 * {@code protected} hooks, and its chunks then take part in this lifecycle without ever being a
 * {@link FalcoChunk}. Narrowing {@link #create(int, int)} or {@link #publish} to {@link FalcoChunk}
 * would turn that supported case into a failure on the load path.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 *
 * <h2>Where a listener of a chunk comes from</h2>
 * <p>
 * From here, through {@link #addListener(ChunkLifecycleListener)}, and it is handed to the chunk in
 * {@link #create(int, int)} rather than kept and consulted by this class. Only one of the five
 * transitions a {@link ChunkLifecycleListener} reports is driven by this class at all — the publish;
 * the tick and the block write reach a chunk without ever passing through a lifecycle, and a design
 * which notified from here would have to leave those two out.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.2.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class ChunkLifecycle {

    /**
     * The instance whose chunks these are, needed for the events, the entities and the supplier.
     */
    private final FalcoInstance owner;

    /**
     * Where a chunk goes when it is published and where it is taken from when it is unloaded.
     */
    private final ChunkRegistry registry;

    /**
     * Where a chunk is read from and where its removal is reported to.
     */
    private final ChunkPersistence persistence;

    /**
     * What fills a chunk no loader knows about.
     */
    private final ChunkGeneration generation;

    /**
     * The factory every chunk of this instance is created by.
     * <p>
     * Volatile because the setter is public and unsynchronized while the load path reads the field
     * from a chunk task on another thread. Without it the reader may not only miss the change, it may
     * see a half-constructed supplier: the value is an arbitrary object handed in by a caller, and
     * only a volatile write publishes that object safely. Synchronizing the setter instead would put
     * a lock on the monitor of a public object, which is exactly what callers must not be able to
     * hold against this instance.
     * </p>
     */
    private volatile ChunkSupplier chunkSupplier = FalcoChunk::new;

    /**
     * Whether a chunk which is asked for is loaded on demand.
     */
    private volatile boolean autoChunkLoad = true;

    /**
     * How a chunk of this instance is told that it was loaded, or null for the built-in way.
     * <p>
     * {@code Chunk#onLoad()} and {@code Chunk#unload()} are {@code protected}, so this package can
     * drive them only on {@link FalcoChunk}, a type it defines itself. A caller that owns another
     * chunk type can reach both hooks and hands them over through
     * {@link FalcoInstance#setChunkLifecycle(Consumer, Consumer)}. Null means the built-in pair,
     * which requires a {@link FalcoChunk} exactly as before.
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
     * What every chunk of this instance is told about its own transitions, null while nobody listens.
     * <p>
     * This is the instance-wide half of US-3.03; the per-chunk half is
     * {@link FalcoChunk#addLifecycleListener(ChunkLifecycleListener)}. What is kept here is only the
     * registration: {@link #create(int, int)} hands this listener to every chunk it builds, and every
     * notification is then made by the chunk itself. A lifecycle which notified on behalf of its
     * chunks would have to be reachable from a tick, and a tick has a chunk and no lifecycle.
     * </p>
     * <p>
     * Volatile for the same reason as {@link #chunkSupplier}: a caller object written by a public
     * setter and read on the load path from another thread.
     * </p>
     */
    private volatile @Nullable ChunkLifecycleListener listener;

    /**
     * Creates the lifecycle of the chunks of one instance.
     *
     * @param owner       the instance whose chunks this lifecycle drives
     * @param registry    which chunk sits at which position, and which position is busy
     * @param persistence where a chunk is read from and where its removal is reported to
     * @param generation  what fills a chunk no loader knows about
     */
    public ChunkLifecycle(FalcoInstance owner, ChunkRegistry registry, ChunkPersistence persistence,
                          ChunkGeneration generation) {
        this.owner = owner;
        this.registry = registry;
        this.persistence = persistence;
        this.generation = generation;
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
    public CompletableFuture<Chunk> retrieve(int chunkX, int chunkZ) {
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
     * The chunk is produced first and published second, and the publish may be refused. Everything in
     * between the two is the window in which an unload can decide that this chunk is not wanted any
     * more; a load which is refused therefore has to undo itself rather than complain, which is what
     * the discard below does.
     * </p>
     * <p>
     * The two ends of that undo do not necessarily reach the same loader. The chunk is read through
     * the loader handed in here, which {@link #retrieve(int, int)} captured before the load started,
     * while {@link ChunkPersistence#unloaded(Chunk)} tells whichever loader is current when it runs. A
     * {@link FalcoInstance#setChunkLoader(ChunkLoader)} in between therefore hands the discarded chunk
     * to a loader that never produced it — which its own documentation permits, since Minestom gives a
     * loader no way to tell its own chunks apart anyway. It is written down rather than fixed because
     * changing it is a change of behaviour.
     * </p>
     * <p>
     * The listener of this lifecycle is installed on both arms, and the two arms do it at different
     * moments on purpose. A chunk this class builds itself receives it inside {@link #create(int, int)},
     * before the generator runs, because generation writes blocks. A chunk a {@link ChunkLoader}
     * returns was built by that loader and never passed through {@link #create(int, int)}, so it is
     * given the listener here — as late as the loader lets us and therefore after the loader has
     * already written its blocks. That asymmetry is the loader's, not a choice: a loaded chunk has no
     * moment before its blocks that this class can reach.
     * </p>
     *
     * @param index  the chunk index of the position, the key in the registry
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @param loader the loader the chunk is read from
     * @param future the future handed to the callers waiting for this chunk
     */
    public void completeLoad(long index, int chunkX, int chunkZ, ChunkLoader loader,
                             CompletableFuture<Chunk> future) {
        final Chunk managed;
        try {
            Chunk chunk = loader.loadChunk(this.owner, chunkX, chunkZ);
            if (chunk == null) {
                chunk = create(chunkX, chunkZ);
                chunk.onGenerate();
            } else {
                // The loader built this chunk itself, so create() never saw it. Without this line a
                // world read from disk would report no transition at all while a freshly generated
                // neighbour reports all five.
                installListener(chunk);
            }
            managed = requireManaged(chunk);
        } catch (Throwable throwable) {
            this.registry.release(index, future);
            future.completeExceptionally(throwable);
            return;
        }
        if (!publish(index, managed, future)) {
            // The chunk was never part of this instance, so there is no registry entry and no
            // partition to clean up. The loader is still told, because it created the chunk and may
            // hold bookkeeping for it, which its own documentation allows for explicitly.
            notifyUnloaded(managed);
            this.persistence.unloaded(managed);
            future.completeExceptionally(new FalcoInstanceException("the chunk " + chunkX + ":" + chunkZ
                    + " was unloaded while it was being loaded, so the loaded chunk was discarded"));
            return;
        }
        notifyLoaded(managed);
        future.complete(managed);
        EventDispatcher.call(new InstanceChunkLoadEvent(this.owner, managed));
    }

    /**
     * Makes a freshly built chunk part of this instance, unless somebody claimed its position.
     * <p>
     * The step handed to the registry has no foreign code in it, but that is a property of this
     * method rather than a rule of the registry; {@link ChunkRegistry} states what a step handed to it
     * may do, and the removal step of {@link #unload(Chunk)} is bound by exactly the same rules.
     * </p>
     *
     * @param index  the chunk index of the position
     * @param chunk  the chunk to publish
     * @param future the future of this load, which has to still be the entry of the position
     * @return true if the chunk is now part of this instance, false if the load was claimed
     */
    public boolean publish(long index, Chunk chunk, CompletableFuture<Chunk> future) {
        final boolean published = this.registry.publish(index, chunk, future,
                inLock -> MinecraftServer.process().dispatcher().createPartition(inLock));
        // Outside the step and therefore outside the position lock: a listener may call back into
        // the instance, and the registry forbids exactly that from inside.
        if (published && chunk instanceof FalcoChunk falcoChunk) falcoChunk.notifyPublished();
        return published;
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
    public void discard(long index) {
        final CompletableFuture<Chunk> running = this.registry.discard(index);
        if (running == null) return;
        running.completeExceptionally(new FalcoInstanceException("the chunk "
                + CoordConversion.chunkIndexGetX(index) + ":" + CoordConversion.chunkIndexGetZ(index)
                + " was unloaded while it was being loaded, so the load was cancelled"));
    }

    /**
     * Hands this lifecycle's listener to a chunk that can carry one.
     * <p>
     * Two conditions have to hold and neither is an accident. There has to be a listener at all —
     * the common case is that there is none, and the call then costs one field read. And the chunk
     * has to be a {@link FalcoChunk}, because carrying a listener is what that type adds; a chunk
     * from a foreign supplier takes part in the lifecycle without reporting it, which is a
     * limitation of the supplier rather than of this class.
     * </p>
     * <p>
     * Extracted because the two arms of {@link #retrieve(int, int)} need it at different moments and
     * a copy in each would be a place for them to drift apart. The moments themselves are documented
     * where they are chosen, not here.
     * </p>
     *
     * @param chunk the chunk to install the listener on
     */
    private void installListener(Chunk chunk) {
        final ChunkLifecycleListener installed = this.listener;

        if (installed != null && chunk instanceof FalcoChunk falcoChunk) {
            falcoChunk.addLifecycleListener(installed);
        }
    }

    /**
     * Creates a chunk through the chunk supplier of this instance and generates it.
     * <p>
     * This is the path a chunk takes which no {@link ChunkLoader} knows about. Without a generator the
     * chunk stays empty, which is a world made of air rather than a failure.
     * </p>
     * <p>
     * The chunk type is not checked here. A supplier may legitimately produce something this package
     * cannot drive on its own, and whether that is acceptable depends on the pair of hooks a caller
     * installed; {@link #completeLoad} is where that question is answered, and it is answered once.
     * </p>
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return the created chunk
     * @throws FalcoInstanceException if the chunk supplier returned null
     */
    public Chunk create(int chunkX, int chunkZ) {
        final Chunk chunk = this.chunkSupplier.createChunk(this.owner, chunkX, chunkZ);
        if (chunk == null) {
            throw new FalcoInstanceException("the chunk supplier returned null for chunk " + chunkX + ":" + chunkZ);
        }
        // Before the generator runs, not after it: generation writes the blocks which carry a
        // handler through Chunk#setBlock, and a listener registered afterwards would miss them.
        installListener(chunk);
        final Generator current = this.generation.generator();
        if (current != null && chunk.shouldGenerate()) {
            this.generation.apply(chunk, current);
            this.owner.refreshLastBlockChangeTime();
        } else {
            this.generation.applyPending(chunk);
        }
        return chunk;
    }

    /**
     * Removes a chunk from this instance.
     * <p>
     * Taking the chunk out of the registry, clearing its loaded flag and deleting its tick partition
     * are one step, taken while the position of the chunk is held, so a load which is publishing the
     * same position cannot interleave with it. Everything else — the packet, the event, the entities
     * and the loader — follows outside, because all four can call back into the instance and holding a
     * position while foreign code runs is how two chunks deadlock each other.
     * </p>
     * <p>
     * A running load is not cancelled here and not waited for either, and that is not an omission: a
     * position which is loading has no chunk in the registry, so a chunk a caller can hand to this
     * method is never the one being loaded. It is either the chunk of that position, which the atomic
     * step below removes, or a chunk of an earlier life of that position, which was already unloaded
     * and is refused by the first line. Cancelling a load needs a position rather than a chunk, and
     * {@link #discard(long)} is where that happens.
     * </p>
     * <p>
     * Unloading the same chunk twice does nothing the second time, which makes this usable in a
     * cleanup path that may run more than once.
     * </p>
     *
     * @param chunk the chunk to remove, which this instance has to be able to drive
     * @throws FalcoInstanceException if the chunk is not a {@link FalcoChunk} and no lifecycle pair
     *                                was installed
     */
    public void unload(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        final Chunk managed = requireManaged(chunk);
        final int chunkX = managed.getChunkX();
        final int chunkZ = managed.getChunkZ();
        final long index = CoordConversion.chunkIndex(chunkX, chunkZ);
        final boolean removed = this.registry.remove(index, managed, unloaded -> {
            notifyUnloaded(unloaded);
            MinecraftServer.process().dispatcher().deletePartition(unloaded);
        });

        if (!removed) return;
        managed.sendPacketToViewers(new UnloadChunkPacket(chunkX, chunkZ));
        EventDispatcher.call(new InstanceChunkUnloadEvent(this.owner, managed));
        this.owner.getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES)
                .forEach(Entity::remove);
        this.persistence.unloaded(managed);
    }

    /**
     * Hands out what fills a chunk no loader knows about.
     * <p>
     * This is the only route to {@link ChunkGeneration} there is, and that is the point rather than an
     * inconvenience. A chunk is generated exactly once and that once is inside its load, so generation
     * is a collaborator of this class; giving {@link FalcoInstance} a field for it would have made it a
     * fifth part of a facade that holds four, which {@code InstanceFacadeTest} refuses. The three
     * members of {@link FalcoInstance} that still speak about generation —
     * {@link FalcoInstance#generator()}, {@link FalcoInstance#setGenerator(Generator)} and
     * {@link FalcoInstance#generateChunk(int, int, Generator)} — reach it through here.
     * </p>
     *
     * @return the generation side of this lifecycle
     * @since 0.4.0
     */
    public ChunkGeneration generation() {
        return this.generation;
    }

    /**
     * Adds a listener every chunk this lifecycle creates from now on is given.
     * <p>
     * A second listener composes with the first through {@link ChunkLifecycleListener#of}, so two
     * extensions can live beside each other on the same chunk, which is what US-3.03 asks for and
     * what a superclass could never provide.
     * </p>
     * <p>
     * Chunks which already exist are deliberately not touched. A listener is handed over in
     * {@link #create(int, int)}, before the generator runs, so a chunk either had the listener for
     * its whole life or never had it — a chunk that received one halfway through would report a
     * transition whose counterpart the listener never saw. A caller which wants a listener on a chunk
     * that is already loaded adds it to that chunk through
     * {@link FalcoChunk#addLifecycleListener(ChunkLifecycleListener)} and knows what it is asking for.
     * </p>
     * <p>
     * Registration is not atomic, for the reason given on
     * {@link FalcoChunk#addLifecycleListener(ChunkLifecycleListener)}: two threads registering at the
     * same moment can lose one of the two. This is a setup call and belongs before the first chunk of
     * the instance is asked for.
     * </p>
     *
     * @param listener the listener every chunk created from now on is given
     * @throws NullPointerException if the listener is null
     * @since 0.4.0
     */
    public void addListener(ChunkLifecycleListener listener) {
        final ChunkLifecycleListener current = this.listener;
        this.listener = current == null ? Objects.requireNonNull(listener,
                "the listener cannot be null") : ChunkLifecycleListener.of(current, listener);
    }

    /**
     * Hands out what every chunk created by this lifecycle is given.
     *
     * @return the listener of this lifecycle, or null if nothing listens
     * @since 0.4.0
     */
    public @Nullable ChunkLifecycleListener listener() {
        return this.listener;
    }

    /**
     * Hands out what produces the chunk objects of this instance.
     *
     * @return the current chunk supplier
     */
    public ChunkSupplier supplier() {
        return this.chunkSupplier;
    }

    /**
     * Changes what produces the chunk objects of this instance.
     *
     * @param supplier the new chunk supplier
     * @throws NullPointerException if the supplier is null
     */
    public void supplier(ChunkSupplier supplier) {
        this.chunkSupplier = Objects.requireNonNull(supplier, "the chunk supplier cannot be null");
    }

    /**
     * Reports whether a chunk which is asked for is loaded on demand.
     *
     * @return true if chunks are loaded on demand
     */
    public boolean autoLoad() {
        return this.autoChunkLoad;
    }

    /**
     * Sets whether a chunk which is asked for is loaded on demand.
     *
     * @param enable true to load chunks on demand
     */
    public void autoLoad(boolean enable) {
        this.autoChunkLoad = enable;
    }

    /**
     * Says how a chunk of this instance is told that it was loaded and that it left.
     * <p>
     * Both halves are set at once so the pair cannot end up half configured. What a caller may write
     * into either of them differs, and the difference is documented on
     * {@link FalcoInstance#setChunkLifecycle(Consumer, Consumer)}, which is the public door to this.
     * </p>
     *
     * @param onLoaded   what tells a chunk that it is part of this instance
     * @param onUnloaded what tells a chunk that it left this instance
     * @throws NullPointerException if either half is null
     */
    public void hooks(Consumer<Chunk> onLoaded, Consumer<Chunk> onUnloaded) {
        Objects.requireNonNull(onLoaded, "the loaded half of the lifecycle cannot be null");
        Objects.requireNonNull(onUnloaded, "the unloaded half of the lifecycle cannot be null");
        this.chunkLoaded = onLoaded;
        this.chunkUnloaded = onUnloaded;
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
     * @return the same chunk
     * @throws FalcoInstanceException if the chunk is not a {@link FalcoChunk} and no lifecycle pair
     *                                was installed
     */
    private Chunk requireManaged(Chunk chunk) {
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
     * Tells a chunk that it is now part of this instance.
     *
     * @param chunk the chunk which finished loading
     */
    private void notifyLoaded(Chunk chunk) {
        final @Nullable Consumer<Chunk> configured = this.chunkLoaded;

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
        final @Nullable Consumer<Chunk> configured = this.chunkUnloaded;

        if (configured == null) {
            ((FalcoChunk) chunk).markUnloaded();
            return;
        }
        configured.accept(chunk);
    }
}
