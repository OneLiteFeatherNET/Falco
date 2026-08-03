package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Chunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import space.vectrix.flare.fastutil.Long2ObjectSyncMap;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The {@link ChunkRegistry} class knows which chunk sits at which position and which position is
 * busy, and it is the only place where either of those two answers changes.
 * <p>
 * It was carved out of {@link FalcoInstance}, where the two maps and the four transitions between
 * them were fields and {@code private} methods of a class of 1 722 lines. Nothing about the
 * transitions changed in the move, and that is deliberate: the shape of the
 * {@link ConcurrentHashMap#compute} calls below is what
 * {@code FalcoInstanceLoadRaceTest#testConcurrentLoadsAndUnloadsNeverLeaveAChunkWhichCannotBeUnloaded}
 * exists to protect, and a refactoring that improved them would be a rewrite of the one part of this
 * module that was hardest to get right.
 * </p>
 *
 * <h2>Why the map of running loads is the lock of a position</h2>
 * <p>
 * Every transition of a position — starting a load, publishing its result, unloading the chunk
 * again — happens inside a {@code compute} on the index of that position. That serialises them
 * without putting a monitor over the whole instance, which is what {@code InstanceContainer} does and
 * what NFR-006 forbids. It is worth far more than the future it holds: without it an unload and the
 * load it races can both believe they went first, and the chunk which loses ends up in the instance
 * with its loaded flag already cleared, where nothing will ever unload it again.
 * </p>
 * <p>
 * The steps a caller hands to {@link #publish} and {@link #remove} run <em>inside</em> that lock, and
 * that is the whole reason they are parameters rather than something the caller does afterwards.
 * Creating and deleting a tick partition has to be part of the same atomic step as entering and
 * leaving the chunk map; splitting them is what lets Minestom delete a partition that is created a
 * moment later, which leaves a chunk being ticked for the rest of the life of the server even though
 * nothing else knows about it any more.
 * </p>
 *
 * <h2>What a step handed to publish or remove may do</h2>
 * <p>
 * Both steps run as the remapping function of a {@link ConcurrentHashMap#compute} on the map of
 * running loads, so they inherit the rules of that method rather than merely being called at an
 * awkward moment. A step has to be short, must not block, and must not reach back into this
 * registry — not for its own position and not for another one. {@code compute} states outright that
 * a remapping function must not attempt to update any other mapping of the same map, so a step which
 * calls {@link #acquire}, {@link #publish}, {@link #remove}, {@link #release} or {@link #discard}
 * can wedge a position for the rest of the life of the server. Reading which chunk sits somewhere is
 * a read of the other map and is safe.
 * </p>
 * <p>
 * A step must not throw either. {@code compute} rethrows and leaves its own mapping alone, but the
 * chunk map was already written by the time the step runs, so what a throw leaves behind is a
 * position on which the two maps disagree; {@link #publish} and {@link #remove} each name the state
 * their own failure produces. This registry does not undo it and cannot: a step which failed half
 * way holds bookkeeping only its caller knows about.
 * </p>
 * <p>
 * What this does <em>not</em> say is "no foreign code inside the lock". {@link ChunkLifecycle} hands
 * the removal step the very hook a caller installs through
 * {@link FalcoInstance#setChunkLifecycle(Consumer, Consumer)}, because clearing the loaded flag of a
 * chunk has to be atomic with that chunk leaving the chunk map — a chunk which is out of the map and
 * still reports {@code isLoaded()} is one every {@code ChunkUtils#isLoaded} check in Minestom
 * believes in. The rule is that whatever runs in there obeys the three constraints above. Everything
 * which cannot — the events, the packets, the loader, the listeners — stays outside and is the
 * caller's business.
 * </p>
 *
 * <h2>Why this registry speaks {@link Chunk} and not {@link FalcoChunk}</h2>
 * <p>
 * It holds no opinion about the chunk type, and it must not: since
 * {@link FalcoInstance#setChunkLifecycle(Consumer, Consumer)} exists, a caller which owns another
 * chunk type — a lighting chunk from {@code falco-light}, say — hands over the two {@code protected}
 * hooks and its chunks are managed by this instance without ever being a {@link FalcoChunk}.
 * Narrowing the two transitions to {@link FalcoChunk} would turn that supported case into a
 * {@link ClassCastException} on the load path.
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
public final class ChunkRegistry {

    /**
     * The loaded chunks, keyed by the chunk index of their position.
     * <p>
     * A primitive keyed map rather than a {@code ConcurrentHashMap<Long, Chunk>}, which boxed its key
     * on every lookup — counted by {@code ChunkLookupAllocationTest}, which measures a position whose
     * index is outside the autobox cache and finds nothing left afterwards. This is not offered as a
     * speed change and no figure of this repository claims one: {@code getChunk} is reached on a chunk
     * change rather than per block, because {@code ChunkCache} memoises in between, so the allocation
     * is established and its cost is not.
     * </p>
     * <p>
     * {@code Long2ObjectSyncMap} is a read map plus a dirty map in the shape of Go's {@code sync.Map},
     * not the copy-on-write map underneath {@code InstanceContainer}. Lookups take no lock; a write
     * after a run of misses rebuilds the dirty map, which is linear and lands on the load and unload
     * path, where a tick partition is created and an event is dispatched anyway.
     * {@code ChunkLookupBenchmark} prices both sides.
     * </p>
     * <p>
     * Two costs of that map are paid by this class and named here rather than discovered later.
     * {@link #size()} and {@link #idle()} walk the read map instead of reading a counter, so both are
     * linear; they are reached from {@code FalcoInstance#unregister} and from a log line, never from a
     * tick. And {@link #chunks()} builds a fresh view object per call, because the fastutil base class
     * behind this map does not cache one the way {@code ConcurrentHashMap} does — the wrapper that
     * method returns was allocated per call before this change too.
     * </p>
     */
    private final Long2ObjectSyncMap<Chunk> chunks = Long2ObjectSyncMap.hashmap();

    /**
     * The chunks which are being loaded right now, keyed by chunk index, and the lock of a position.
     * <p>
     * Holding the future rather than a flag is what makes two concurrent requests for the same chunk
     * share one load instead of racing into two chunk objects.
     * </p>
     */
    private final Map<Long, CompletableFuture<Chunk>> loadingChunks = new ConcurrentHashMap<>();

    /**
     * Creates a registry which holds neither a chunk nor a running load.
     * <p>
     * Written out rather than left to the compiler because this type is published: a default
     * constructor carries no documentation, and the Javadoc build of this module treats a public
     * type with one as an error.
     * </p>
     */
    public ChunkRegistry() {
    }

    /**
     * What a caller asking for a position is told.
     * <p>
     * A sealed hierarchy rather than a nullable future plus an out parameter, because the three
     * answers are genuinely different and the caller has to handle all three: the chunk is already
     * there, somebody else is loading it, or this caller now owns the load. The
     * {@code AtomicReference} the previous shape needed to smuggle the first case out of a
     * {@code compute} is what this replaces.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    @ApiStatus.Experimental
    public sealed interface LoadSlot {

        /**
         * The position already carries a chunk and no load is needed.
         *
         * @param chunk the chunk at the position
         * @author TheMeinerLP
         * @version 1.0.0
         * @since 0.4.0
         */
        @ApiStatus.Experimental
        record Loaded(Chunk chunk) implements LoadSlot {
        }

        /**
         * Somebody else is loading this position and the caller has to wait for their future.
         *
         * @param future the future of the running load
         * @author TheMeinerLP
         * @version 1.0.0
         * @since 0.4.0
         */
        @ApiStatus.Experimental
        record Running(CompletableFuture<Chunk> future) implements LoadSlot {
        }

        /**
         * The caller now owns the load of this position and has to complete the future it handed in.
         *
         * @param future the future the caller handed in
         * @author TheMeinerLP
         * @version 1.0.0
         * @since 0.4.0
         */
        @ApiStatus.Experimental
        record Claimed(CompletableFuture<Chunk> future) implements LoadSlot {
        }
    }

    /**
     * Returns the chunk at a position.
     *
     * @param index the chunk index of the position
     * @return the chunk, or null if the position carries none
     */
    public @Nullable Chunk chunk(long index) {
        return this.chunks.get(index);
    }

    /**
     * Returns the chunk at a position.
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return the chunk, or null if the position carries none
     */
    public @Nullable Chunk chunk(int chunkX, int chunkZ) {
        return this.chunks.get(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    /**
     * Returns a live, unmodifiable view of every chunk in this registry.
     *
     * @return the chunks of this registry
     */
    public @UnmodifiableView Collection<Chunk> chunks() {
        return Collections.unmodifiableCollection(this.chunks.values());
    }

    /**
     * Returns a snapshot of every chunk in this registry, safe to iterate while it changes.
     *
     * @return the chunks of this registry at the moment of the call
     */
    public List<Chunk> snapshot() {
        return List.copyOf(this.chunks.values());
    }

    /**
     * Returns a snapshot of every position which is being loaded right now.
     *
     * @return the positions with a running load at the moment of the call
     */
    public List<Long> loadingPositions() {
        return List.copyOf(this.loadingChunks.keySet());
    }

    /**
     * Returns how many chunks this registry holds.
     *
     * @return the amount of loaded chunks
     */
    public int size() {
        return this.chunks.size();
    }

    /**
     * Returns how many loads are running.
     *
     * @return the amount of running loads
     */
    public int loading() {
        return this.loadingChunks.size();
    }

    /**
     * Reports whether this registry holds neither a chunk nor a running load.
     *
     * @return true if nothing is left in this registry
     */
    public boolean idle() {
        return this.chunks.isEmpty() && this.loadingChunks.isEmpty();
    }

    /**
     * Decides who loads a position.
     * <p>
     * The chunk map is read a second time inside the decision. Without that second read a caller
     * which looked at the chunk map just before a load published, and reached this point just after
     * that load removed its entry, would start a second load for a position which already has a
     * chunk. The second chunk then replaces the first one in the map and the first one is orphaned:
     * still marked as loaded, still holding its tick partition and its viewers, and no longer
     * reachable.
     * </p>
     *
     * @param index the chunk index of the position
     * @param own   the future the caller offers to complete if it wins the slot
     * @return which of the three cases the caller is in
     */
    public LoadSlot acquire(long index, CompletableFuture<Chunk> own) {
        final AtomicReference<Chunk> published = new AtomicReference<>();
        final CompletableFuture<Chunk> slot = this.loadingChunks.compute(index, (key, running) -> {
            if (running != null) return running;
            final Chunk cached = this.chunks.get(index);
            if (cached != null) {
                published.set(cached);
                return null;
            }
            return own;
        });
        final Chunk cached = published.get();

        if (cached != null) return new LoadSlot.Loaded(cached);
        if (slot != own) return new LoadSlot.Running(slot);
        return new LoadSlot.Claimed(own);
    }

    /**
     * Gives up a slot without publishing anything, for a load which failed.
     *
     * @param index the chunk index of the position
     * @param own   the future of the load which is giving up
     */
    public void release(long index, CompletableFuture<Chunk> own) {
        this.loadingChunks.remove(index, own);
    }

    /**
     * Takes the slot of a running load so its chunk never reaches this registry.
     * <p>
     * Removing the entry is the whole claim: a load publishes only while its own future is still the
     * entry of the position, so a load which finds the slot empty or taken knows that somebody
     * decided its result is no longer wanted.
     * </p>
     *
     * @param index the chunk index of the position
     * @return the future of the claimed load, or null if there was none
     */
    public @Nullable CompletableFuture<Chunk> discard(long index) {
        final AtomicReference<CompletableFuture<Chunk>> claimed = new AtomicReference<>();

        this.loadingChunks.compute(index, (key, running) -> {
            claimed.set(running);
            return null;
        });
        return claimed.get();
    }

    /**
     * Makes a chunk the chunk of its position, unless somebody claimed the load.
     * <p>
     * The chunk enters the chunk map before the step runs, so the step already meets a registry which
     * answers {@link #chunk(long)} with it. That order is what makes a throwing step expensive: the
     * chunk stays in the chunk map, {@code compute} rethrows, and the entry of the running load
     * survives untouched. The position is then loaded and loading at once, and every later
     * {@link #acquire} on it hands out a {@link LoadSlot.Running} carrying a future nobody is going
     * to complete any more. The constraints named on this class apply in full.
     * </p>
     *
     * @param index      the chunk index of the position
     * @param chunk      the chunk to publish
     * @param future     the future of this load, which has to still be the entry of the position
     * @param insideLock the step to run while the position is held, once, only if the publish
     *                   happens; short, non-blocking, no call back into this registry, no exception
     * @return true if the chunk is now the chunk of its position, false if the load was claimed
     */
    public boolean publish(long index, Chunk chunk, CompletableFuture<Chunk> future,
                           Consumer<Chunk> insideLock) {
        final AtomicBoolean published = new AtomicBoolean();

        this.loadingChunks.compute(index, (key, running) -> {
            if (running != future) return running;
            this.chunks.put(index, chunk);
            insideLock.accept(chunk);
            published.set(true);
            return null;
        });
        return published.get();
    }

    /**
     * Takes a chunk out of its position.
     * <p>
     * The chunk leaves the chunk map before the step runs, and the entry of the position in the map
     * of running loads is handed back unchanged — a position which carries a chunk has no running
     * load, because {@link #acquire} claims a slot only for a position without one. A throwing step
     * therefore leaves the removal standing while {@code compute} rethrows into the caller, which
     * then never reaches the half of the unload that belongs outside the lock: the chunk is gone from
     * this registry and only half unloaded. The constraints named on this class apply in full.
     * </p>
     *
     * @param index      the chunk index of the position
     * @param chunk      the chunk to remove, which has to be the one at that position
     * @param insideLock the step to run while the position is held, once, only if the removal
     *                   happens; short, non-blocking, no call back into this registry, no exception
     * @return true if the chunk was removed, false if it was not the chunk of that position
     */
    public boolean remove(long index, Chunk chunk, Consumer<Chunk> insideLock) {
        final AtomicBoolean removed = new AtomicBoolean();

        this.loadingChunks.compute(index, (key, running) -> {
            if (this.chunks.remove(index, chunk)) {
                insideLock.accept(chunk);
                removed.set(true);
            }
            return running;
        });
        return removed.get();
    }
}
