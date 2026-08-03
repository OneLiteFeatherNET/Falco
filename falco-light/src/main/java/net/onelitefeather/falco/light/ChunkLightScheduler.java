package net.onelitefeather.falco.light;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.utils.chunk.ChunkSupplier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The {@link ChunkLightScheduler} class turns "a block changed somewhere" into "the light of that
 * region is up to date again", once per tick and off the tick thread.
 * <p>
 * Every piece of complexity of the self maintaining light lives here, at one address. The chunk
 * that reports a change holds none of it, and the area that computes the light holds none of it
 * either: the chunk says <em>what</em> changed, the area says <em>how</em> light is computed, and
 * this class decides <em>when</em> and <em>with which other chunks</em>.
 * </p>
 * <p>
 * <b>Once per tick, not once per chunk.</b> {@code Chunk#tick(long)} runs per chunk, but a light
 * pass has to see every change of the tick before it forms its areas. The tick timestamp is the
 * same value for every chunk of one tick, so the scheduler remembers the last value it saw and runs
 * its pass for the first chunk that reports a new one. If no chunk of the instance ticks, nothing
 * happens, which is correct: nobody is looking.
 * </p>
 * <p>
 * <b>Nothing ever blocks on a computation.</b> A chunk hands out whatever its sections hold right
 * now, which is the previous result while a new one is in flight. When a computation finishes, the
 * affected chunks are invalidated and, if they can, send their light themselves through
 * {@link LightUpdateAware}.
 * </p>
 * <p>
 * <b>Back pressure and staleness are two different rules.</b> A chunk whose area is still being
 * computed stays marked but is not submitted again, so a slow area cannot pile up work. Separately,
 * every chunk carries a revision that its own {@code setBlock} raises; an area records the revision
 * of each of its chunks before it reads them and compares again before it writes. A chunk whose
 * revision moved is discarded and stays dirty rather than being written from block states that are
 * already gone — which matters here more than almost anywhere, because writing light also clears
 * the update flag of the section and a wrong result would never be recomputed.
 * </p>
 * <p>
 * <b>A changed block costs a changed block, not nine chunks.</b> The position of every block change
 * is handed to the area together with the mark, and the area replays it on the light it already
 * holds for that chunk instead of searching the chunk again. The eight chunks around it are marked
 * as well, because light crosses borders, but nothing of theirs is thrown away: their own blocks did
 * not move, and what reaches them across the border is derived again by every pass anyway. A change
 * that cannot be placed — a chunk that was generated, loaded, or written past {@code setBlock} —
 * goes through {@link #markChanged(Instance, int, int)} and is lit from the block states, which is
 * what every change did before.
 * </p>
 * <p>
 * <b>One scheduler belongs to one instance.</b> The dirty set is keyed by chunk coordinates alone,
 * and every instance of a server ticks with the same timestamp, so a scheduler shared between two
 * instances would light the wrong chunks and would run its pass for only one of them per tick.
 * Rather than let that fail as a dark world, the second instance is rejected with an
 * {@link IllegalStateException}.
 * </p>
 * <p>
 * This type is experimental. The light engine is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class ChunkLightScheduler {

    /**
     * The amount of chunks a single area holds at most when no other value is given.
     * <p>
     * One {@link ChunkLightState} is roughly 100 KB at the height of an overworld chunk, so sixteen
     * chunks plus their ring is a few megabytes of working memory per pass — which is what an area
     * this size is chosen against, since every chunk of it is also read and turned into opacity
     * tables inside one tick.
     * </p>
     */
    public static final int DEFAULT_MAX_AREA_SIZE = 16;

    /**
     * The tick timestamp of a scheduler that has never run a pass.
     */
    private static final long NEVER = Long.MIN_VALUE;

    /**
     * The amount of chunks a change reaches beyond the chunk it happened in.
     * <p>
     * A light level of fifteen drops to nothing after fifteen blocks and a chunk is sixteen blocks
     * wide, so a change can raise or lower the light of the eight chunks around its own and of no
     * chunk further out.
     * </p>
     */
    private static final int AFFECTED_RADIUS = 1;

    private final ChunkLightArea area;
    private final Executor executor;
    private final int maxAreaSize;
    private final SkyLight skyLight;

    /**
     * Where failures are reported, or null for the exception manager of the running server.
     * <p>
     * Null rather than a captured default, for the same reason the anvil loader resolves its sink
     * per failure: {@code MinecraftServer.getExceptionManager()} needs a server process, and a
     * scheduler built before one exists would die in its own error path.
     * </p>
     */
    private final @Nullable Consumer<Throwable> failureSink;

    /**
     * What is told about a finished area, given the claimed chunks and the written ones.
     */
    private final BiConsumer<List<ChunkArea>, List<ChunkArea>> areaCompleted;

    private final Map<ChunkArea, Long> dirty = new ConcurrentHashMap<>();
    private final Set<ChunkArea> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastTick = new AtomicLong(NEVER);
    private final AtomicReference<@Nullable Instance> bound = new AtomicReference<>();

    /**
     * Creates a scheduler with the default executor and the default area size.
     *
     * @param service the service which computes and writes the light
     */
    public ChunkLightScheduler(ChunkLightService service) {
        this(service, defaultExecutor(), DEFAULT_MAX_AREA_SIZE);
    }

    /**
     * Creates a scheduler which submits its areas to the given executor.
     *
     * @param service     the service which computes and writes the light
     * @param executor    the executor which runs one area per task
     * @param maxAreaSize the largest amount of chunks a single area may hold
     * @param maxCachedChunks the amount of chunks whose light is kept between two passes
     * @throws IllegalArgumentException if the given area size is smaller than one or the given
     *                                  amount of kept chunks is negative
     */
    public ChunkLightScheduler(ChunkLightService service, Executor executor, int maxAreaSize, int maxCachedChunks) {
        this(builder(service).executor(executor).maxAreaSize(maxAreaSize).maxCachedChunks(maxCachedChunks));
    }

    /**
     * Creates a scheduler from the values collected by a builder.
     *
     * @param settings the builder which holds the configured values
     */
    private ChunkLightScheduler(Builder settings) {
        this.area = new ChunkLightArea(settings.service, settings.maxCachedChunks);
        this.executor = settings.executor == null ? defaultExecutor() : settings.executor;
        this.maxAreaSize = settings.maxAreaSize;
        this.skyLight = settings.skyLight;
        this.failureSink = settings.failureSink;
        this.areaCompleted = settings.areaCompleted;
    }

    /**
     * Creates a scheduler which submits its areas to the given executor.
     *
     * @param service     the service which computes and writes the light
     * @param executor    the executor which runs one area per task
     * @param maxAreaSize the largest amount of chunks a single area may hold
     * @throws IllegalArgumentException if the given area size is smaller than one
     */
    public ChunkLightScheduler(ChunkLightService service, Executor executor, int maxAreaSize) {
        this(service, executor, maxAreaSize, ChunkLightArea.DEFAULT_MAX_CACHED_CHUNKS);
    }

    /**
     * Builds the executor a scheduler uses when the caller names none.
     * <p>
     * Every area gets its own virtual thread, and a semaphore keeps no more of them running at once
     * than the machine has processors — the same shape {@code FalcoAnvilLoader} uses to bound its
     * saves. The bound sits inside the task rather than around the submission on purpose: acquiring
     * it before starting the thread would block whichever chunk happened to trigger the pass, and
     * that chunk is being ticked by the server.
     * </p>
     *
     * <p>
     * This is public so the threading policy and the area size stay independent of one another. The
     * three and four parameter constructors take both, so a caller who only wants a different area
     * size would otherwise have to invent an executor and would silently replace this policy:
     * </p>
     * <pre>{@code
     * new ChunkLightScheduler(service, ChunkLightScheduler.defaultExecutor(), 8);
     * }</pre>
     * <p>
     * Every call builds a new executor with its own bound. Two schedulers built from two calls do
     * not share a limit; pass one instance to both if that is what you want.
     * </p>
     *
     * @return an executor which runs areas on virtual threads, bounded by the processor count
     */
    @Contract(pure = true)
    public static Executor defaultExecutor() {
        Semaphore limit = new Semaphore(Math.max(Runtime.getRuntime().availableProcessors(), 2));

        return task -> Thread.startVirtualThread(() -> {
            limit.acquireUninterruptibly();
            try {
                task.run();
            } finally {
                limit.release();
            }
        });
    }

    /**
     * Returns a builder for a scheduler whose defaults are those of the constructors.
     * <p>
     * The service is the only required value and therefore stands here rather than in a slot. The
     * builder reaches what the constructors cannot: the kept-chunk count without also naming an
     * executor and an area size, the sky light policy, the failure sink and the completion observer.
     * </p>
     *
     * @param service the service which computes and writes the light
     * @return a new builder with the defaults of the constructors
     */
    @Contract(value = "_ -> new", pure = true)
    public static Builder builder(ChunkLightService service) {
        return new Builder(service, null, DEFAULT_MAX_AREA_SIZE, ChunkLightArea.DEFAULT_MAX_CACHED_CHUNKS,
                SkyLight.FROM_DIMENSION, null, (group, written) -> {
        });
    }

    /**
     * Decides whether a pass computes sky light.
     * <p>
     * The two passes are not symmetrical, which is why this is a three-state and not a flag per
     * kind of light: only the block light pass yields the chunks that were written, and entries
     * leave the dirty set through those. A scheduler without a block light pass would never clear
     * its dirty set, so that configuration is deliberately not representable.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.4.0
     */
    @ApiStatus.Experimental
    public enum SkyLight {

        /**
         * Asks the dimension on every pass, which is what the scheduler has always done.
         */
        FROM_DIMENSION,

        /**
         * Never computes sky light, even in a dimension which carries it.
         * <p>
         * The case this exists for is a lobby under a closed roof on an overworld dimension: the
         * sky pass reads every block state a second time and writes a result nobody can see.
         * </p>
         */
        DISABLED,

        /**
         * Always computes sky light, even in a dimension which carries none.
         */
        ENABLED;

        /**
         * Decides whether the sky pass runs for the given instance.
         *
         * @param instance the instance whose chunks are lit
         * @return true if the pass runs, otherwise false
         */
        boolean appliesTo(Instance instance) {
            return switch (this) {
                case FROM_DIMENSION -> instance.getCachedDimensionType().hasSkylight();
                case DISABLED -> false;
                case ENABLED -> true;
            };
        }
    }

    /**
     * Collects the values of a scheduler before it is built.
     * <p>
     * <b>Immutable.</b> Every slot returns a new builder and leaves the one it was called on
     * untouched, so a half configured builder can be shared, stored and derived from without anyone
     * having to reason about who changes it. That is also what an architecture rule requires here:
     * a class which declares a field from {@code java.util.concurrent} — the {@code Executor} — has
     * to publish every field safely, and final fields do.
     * </p>
     * <p>
     * The executor is the one value not resolved until {@link #build()}, so a builder which is
     * never built does not start a thread pool it does not need, and two schedulers from one builder
     * each get their own bound.
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

        private final ChunkLightService service;
        private final @Nullable Executor executor;
        private final int maxAreaSize;
        private final int maxCachedChunks;
        private final SkyLight skyLight;
        private final @Nullable Consumer<Throwable> failureSink;
        private final BiConsumer<List<ChunkArea>, List<ChunkArea>> areaCompleted;

        private Builder(ChunkLightService service, @Nullable Executor executor, int maxAreaSize,
                        int maxCachedChunks, SkyLight skyLight, @Nullable Consumer<Throwable> failureSink,
                        BiConsumer<List<ChunkArea>, List<ChunkArea>> areaCompleted) {
            this.service = service;
            this.executor = executor;
            this.maxAreaSize = maxAreaSize;
            this.maxCachedChunks = maxCachedChunks;
            this.skyLight = skyLight;
            this.failureSink = failureSink;
            this.areaCompleted = areaCompleted;
        }

        /**
         * Sets the executor which runs one area per task.
         * <p>
         * The default is {@link ChunkLightScheduler#defaultExecutor()}, resolved when the scheduler
         * is built. A caller who only wants a different area size or cache size no longer has to
         * name one, which is what this builder exists for.
         * </p>
         *
         * @param executor the executor which runs one area per task
         * @return a new builder with this executor
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder executor(Executor executor) {
            return new Builder(this.service, executor, this.maxAreaSize, this.maxCachedChunks,
                    this.skyLight, this.failureSink, this.areaCompleted);
        }

        /**
         * Sets the largest amount of chunks a single area may hold.
         *
         * @param maxAreaSize the largest amount of chunks a single area may hold
         * @return a new builder with this area size
         * @throws IllegalArgumentException if the size is smaller than one
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder maxAreaSize(int maxAreaSize) {
            if (maxAreaSize < 1) {
                throw new IllegalArgumentException("An area has to be able to hold at least one chunk but the cap was " + maxAreaSize);
            }
            return new Builder(this.service, this.executor, maxAreaSize, this.maxCachedChunks,
                    this.skyLight, this.failureSink, this.areaCompleted);
        }

        /**
         * Sets how many chunks keep their light between two passes.
         * <p>
         * Zero keeps none and recalculates every chunk of every pass. This is the value that was
         * reachable only through the four parameter constructor, and therefore only together with
         * an executor and an area size.
         * </p>
         *
         * @param maxCachedChunks the amount of chunks whose light is kept between two passes
         * @return a new builder with this cache size
         * @throws IllegalArgumentException if the amount is negative
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder maxCachedChunks(int maxCachedChunks) {
            if (maxCachedChunks < 0) {
                throw new IllegalArgumentException("A negative amount of kept chunks makes no sense but " + maxCachedChunks + " was given");
            }
            return new Builder(this.service, this.executor, this.maxAreaSize, maxCachedChunks,
                    this.skyLight, this.failureSink, this.areaCompleted);
        }

        /**
         * Sets whether a pass computes sky light.
         *
         * @param skyLight the sky light policy of the scheduler
         * @return a new builder with this policy
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder skyLight(SkyLight skyLight) {
            return new Builder(this.service, this.executor, this.maxAreaSize, this.maxCachedChunks,
                    skyLight, this.failureSink, this.areaCompleted);
        }

        /**
         * Sets where the scheduler reports the failure of an area.
         * <p>
         * This moves the sink, not the control flow: the chunks of a failed area stay dirty and are
         * computed again on the next tick, because a half written light result would be worse than
         * a late one.
         * </p>
         *
         * @param failureSink the sink which receives the failure of an area
         * @return a new builder with this sink
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder onFailure(Consumer<Throwable> failureSink) {
            return new Builder(this.service, this.executor, this.maxAreaSize, this.maxCachedChunks,
                    this.skyLight, failureSink, this.areaCompleted);
        }

        /**
         * Sets what is told about every finished area.
         * <p>
         * The observer receives the chunks the pass claimed and the chunks it actually wrote; the
         * difference between the two is the chunks that changed while the area was being computed
         * and therefore stayed dirty. It carries no duration: whoever supplies the executor wraps
         * the task and measures the same span without a further type.
         * </p>
         * <p>
         * The call happens on the thread of the executor, inside the guarded body of the pass — an
         * observer which throws is reported through {@link #onFailure(Consumer)} like any other
         * failure of the area.
         * </p>
         *
         * @param areaCompleted the observer of claimed and written chunks
         * @return a new builder with this observer
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder onAreaCompleted(BiConsumer<List<ChunkArea>, List<ChunkArea>> areaCompleted) {
            return new Builder(this.service, this.executor, this.maxAreaSize, this.maxCachedChunks,
                    this.skyLight, this.failureSink, areaCompleted);
        }

        /**
         * Builds a scheduler for one instance.
         * <p>
         * Every call returns an independent scheduler. Bind each of them to exactly one instance,
         * as the class comment requires.
         * </p>
         *
         * @return a new scheduler with the configured values
         */
        @Contract(value = "-> new", pure = true)
        public ChunkLightScheduler build() {
            return new ChunkLightScheduler(this);
        }
    }

    /**
     * Returns a chunk supplier which produces chunks that report to this scheduler.
     * <p>
     * This is the entire setup a consumer needs:
     * </p>
     * <pre>{@code
     * ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());
     * instance.setChunkSupplier(scheduler.supplier());
     * }</pre>
     * <p>
     * The chunks it produces are {@link net.onelitefeather.falco.instance.FalcoChunk}s, so they work
     * in a {@code FalcoInstance} exactly as they do in an {@code InstanceContainer} — which is what
     * US-3.06 was about, since the two used to be mutually exclusive. That also means a caller of
     * this method needs {@code falco-instance} on the classpath beside {@code falco-light}; the rest
     * of this class does not, see {@link FalcoLightingChunk}.
     * </p>
     * <p>
     * Keep the scheduler for exactly the instance it was handed to; see the class comment.
     * </p>
     *
     * @return a supplier of chunks which keep their own light up to date
     */
    @Contract(pure = true)
    public ChunkSupplier supplier() {
        return (instance, chunkX, chunkZ) -> new FalcoLightingChunk(this, instance, chunkX, chunkZ);
    }

    /**
     * Reports that the given chunk changed and has to be lit again.
     * <p>
     * The revision the staleness rule compares is kept here rather than on the chunk, and it counts
     * <em>marks</em> rather than block changes. That is a deliberate difference from a counter
     * living on the chunk, and it is what makes the rule hold: a chunk is marked not only by its own
     * block changes but also by a change in any of the eight chunks around it, whose light reaches
     * into it. Two counters raised by two different chunks cannot be merged into one number without
     * a case in which the merged value happens not to move, and a value that does not move reads as
     * "nothing changed" — which would clear a chunk from the dirty set on the strength of a result
     * that is already wrong.
     * </p>
     * <p>
     * This says that a chunk changed without saying where, so the light kept for it is thrown away
     * and the chunk is searched again. A caller which knows the position should say so through
     * {@link #markChanged(Instance, int, int, int, int, int)} instead; this method marks the given
     * chunk alone and leaves the eight around it to the caller, which is what it always did.
     * </p>
     *
     * @param instance the instance the chunk belongs to
     * @param chunkX   the chunk x coordinate
     * @param chunkZ   the chunk z coordinate
     * @throws IllegalStateException if the scheduler is already serving another instance
     */
    public void markDirty(Instance instance, int chunkX, int chunkZ) {
        bind(instance);
        ChunkArea position = new ChunkArea(chunkX, chunkZ);
        // Nothing says which blocks moved, so the light of that chunk cannot be carried over.
        this.area.forget(position);
        this.dirty.merge(position, 1L, Long::sum);
    }

    /**
     * Reports that the blocks of the given chunk changed without saying which ones.
     * <p>
     * This is what a chunk that was generated, loaded or written past {@code setBlock} reports. The
     * light of the chunk is thrown away rather than guessed at, which costs one search of that chunk
     * and is the only honest answer: light is written with the update flag of the section cleared,
     * so a wrong guess here would never be corrected by anybody.
     * </p>
     *
     * @param instance the instance the chunk belongs to
     * @param chunkX   the chunk x coordinate
     * @param chunkZ   the chunk z coordinate
     * @throws IllegalStateException if the scheduler is already serving another instance
     */
    public void markChanged(Instance instance, int chunkX, int chunkZ) {
        bind(instance);
        this.area.forget(new ChunkArea(chunkX, chunkZ));
        markNeighbourhood(chunkX, chunkZ);
    }

    /**
     * Reports that one block of the given chunk changed.
     * <p>
     * This is the entry point that makes a placed torch cost a torch rather than nine chunks. The
     * position is handed to the area, which replays it on the light it already has instead of
     * searching the chunk again, and reaches the same result either way.
     * </p>
     * <p>
     * The position is recorded <em>before</em> the chunks are marked, and that order is not
     * cosmetic. A pass reads the revision of a chunk before it takes the recorded positions, so a
     * position which is recorded first is either already in the list the pass takes or belongs to a
     * revision the pass will see as newer and discard. The other order leaves a window in which a
     * change is invisible to both, which would be committed as if it had never happened.
     * </p>
     *
     * @param instance the instance the chunk belongs to
     * @param chunkX   the chunk x coordinate
     * @param chunkZ   the chunk z coordinate
     * @param x        the x coordinate of the changed block
     * @param y        the y coordinate of the changed block
     * @param z        the z coordinate of the changed block
     * @throws IllegalStateException if the scheduler is already serving another instance
     */
    public void markChanged(Instance instance, int chunkX, int chunkZ, int x, int y, int z) {
        bind(instance);
        ChunkArea position = new ChunkArea(chunkX, chunkZ);
        int columnY = y - instance.getCachedDimensionType().minY();

        if (columnY < 0 || columnY >= instance.getCachedDimensionType().height()) {
            this.area.forget(position);
        } else {
            this.area.recordChange(position, x & 15, columnY, z & 15);
        }
        markNeighbourhood(chunkX, chunkZ);
    }

    /**
     * Marks the given chunk and the eight around it as needing light.
     * <p>
     * Marking only the chunk that changed would leave a seam: a lamp on the eastern edge of a chunk
     * belongs in the light of the chunk east of it, and that chunk would never be told. The ring
     * around an area is read but never written, precisely because a ring chunk is missing the light
     * from beyond it — so a chunk which has to change has to be part of an area, not part of a ring.
     * </p>
     * <p>
     * The eight neighbours are marked but nothing of theirs is thrown away. Their own blocks did not
     * move, so the light they carry is still the light of those blocks; all that changed for them is
     * what arrives across the border, and that is derived again by every pass anyway.
     * </p>
     *
     * @param chunkX the chunk x coordinate of the chunk that changed
     * @param chunkZ the chunk z coordinate of the chunk that changed
     */
    private void markNeighbourhood(int chunkX, int chunkZ) {
        for (int offsetZ = -AFFECTED_RADIUS; offsetZ <= AFFECTED_RADIUS; offsetZ++) {
            for (int offsetX = -AFFECTED_RADIUS; offsetX <= AFFECTED_RADIUS; offsetX++) {
                this.dirty.merge(new ChunkArea(chunkX + offsetX, chunkZ + offsetZ), 1L, Long::sum);
            }
        }
    }

    /**
     * Returns how many chunks this scheduler has lit from scratch since it was created.
     * <p>
     * A number that keeps growing while the same chunks are being edited means the incremental path
     * is not being taken, which is worth knowing before anything else about the light is measured.
     * </p>
     *
     * @return the amount of chunks which were searched rather than updated
     */
    @Contract(pure = true)
    public long fullPropagations() {
        return this.area.fullPropagations();
    }

    /**
     * Returns the revision the given chunk is currently at.
     *
     * @param position the chunk to look up
     * @return the amount of times the chunk was marked, or {@link ChunkLightArea#CLEAN} if it is not
     *         waiting for light at all
     */
    @Contract(pure = true)
    private long revisionOf(ChunkArea position) {
        return this.dirty.getOrDefault(position, ChunkLightArea.CLEAN);
    }

    /**
     * Runs the light pass of one tick, if this tick has not run one yet.
     * <p>
     * Every chunk of the instance calls this with the same timestamp, and only the first call wins.
     * The pass takes every dirty chunk which is not already being computed, groups the result into
     * connected areas of at most the configured size and hands one task per area to the executor.
     * </p>
     *
     * @param instance the instance whose chunks are lit
     * @param time     the timestamp of the current tick
     * @throws IllegalStateException if the scheduler is already serving another instance
     */
    public void onTick(Instance instance, long time) {
        bind(instance);

        if (this.lastTick.getAndSet(time) == time) {
            return;
        }

        List<ChunkArea> claimed = new ArrayList<>();

        for (ChunkArea position : this.dirty.keySet()) {
            if (this.inFlight.add(position)) {
                claimed.add(position);
            }
        }

        if (claimed.isEmpty()) {
            return;
        }

        for (List<ChunkArea> group : ChunkArea.group(claimed, this.maxAreaSize)) {
            submit(instance, group);
        }
    }

    /**
     * Hands one area to the executor.
     * <p>
     * An executor which refuses the task must not leave its chunks marked as being computed,
     * because nothing would ever clear that mark again and those chunks would stop receiving light
     * for the lifetime of the server.
     * </p>
     *
     * @param instance the instance whose chunks are lit
     * @param group    the chunks of the area
     */
    private void submit(Instance instance, List<ChunkArea> group) {
        try {
            this.executor.execute(() -> compute(instance, group));
        } catch (Throwable throwable) {
            release(group);
            report(throwable);
        }
    }

    /**
     * Computes one area and writes back the chunks which did not change while it ran.
     * <p>
     * The whole body is guarded. An exception here must damage neither the tick it came from nor
     * another area, so it is reported and the chunks of the area simply stay dirty — a half written
     * result would be far worse than a late one. Clearing the in flight marks happens in every
     * case, because a mark that survives a failure freezes its chunks forever.
     * </p>
     * <p>
     * A chunk which is no longer part of the instance is dropped rather than computed. Only a
     * completed computation used to clear an entry, and a chunk that is not loaded is never
     * computed, so its entry survived every following pass: claimed again on every tick, released
     * again, never clean. The dirty set grew for the lifetime of the server, by one entry per chunk
     * that was ever edited and then unloaded. The kept light of those chunks needs no handling here,
     * because {@code ChunkLightArea#computeIncrementally} already drops it when it finds no chunk.
     * </p>
     * <p>
     * Both removals are conditional on the recorded revision for the same reason: a change which
     * arrived while the area was being computed must survive the pass, whether the chunk is still
     * loaded or not.
     * </p>
     *
     * @param instance the instance whose chunks are lit
     * @param group    the chunks of the area
     */
    private void compute(Instance instance, List<ChunkArea> group) {
        try {
            Map<ChunkArea, Long> recorded = new HashMap<>(group.size());

            for (ChunkArea position : group) {
                recorded.put(position, this.dirty.getOrDefault(position, ChunkLightArea.CLEAN));
            }

            List<ChunkArea> written = this.area.computeIncrementally(instance, group, false, this::revisionOf);

            if (this.skyLight.appliesTo(instance)) {
                this.area.computeIncrementally(instance, group, true, this::revisionOf);
            }

            for (ChunkArea position : written) {
                this.dirty.remove(position, recorded.get(position));
                deliver(instance, position);
            }

            for (ChunkArea position : group) {
                if (instance.getChunk(position.x(), position.z()) == null) {
                    this.dirty.remove(position, recorded.get(position));
                }
            }

            this.areaCompleted.accept(group, written);
        } catch (Throwable throwable) {
            report(throwable);
        } finally {
            release(group);
        }
    }

    /**
     * Tells a chunk that its light changed, so a client that is already looking at it sees it.
     * <p>
     * Invalidating the chunk drops the cached full chunk packet, which carries the light of the
     * chunk inside it, so the next player to receive the chunk receives the new state. That covers
     * nobody who already has it, which is what {@link LightUpdateAware} is for.
     * </p>
     *
     * @param instance the instance whose chunks are lit
     * @param position the chunk whose light was written
     */
    private static void deliver(Instance instance, ChunkArea position) {
        @Nullable Chunk chunk = instance.getChunk(position.x(), position.z());

        if (chunk == null || !chunk.isLoaded()) {
            return;
        }
        chunk.invalidate();

        if (chunk instanceof LightUpdateAware aware) {
            aware.onLightUpdated();
        }
    }

    /**
     * Clears the in flight mark of every chunk of the given area.
     *
     * @param group the chunks of the area
     */
    private void release(List<ChunkArea> group) {
        for (ChunkArea position : group) {
            this.inFlight.remove(position);
        }
    }

    /**
     * Reports a failure of one area to the configured sink, or to the exception manager.
     * <p>
     * The default is resolved here rather than when the scheduler is built, so a scheduler created
     * before {@code MinecraftServer.init()} does not fail with a null pointer instead of the failure
     * it was asked to report.
     * </p>
     *
     * @param throwable the failure to report
     */
    private void report(Throwable throwable) {
        if (this.failureSink == null) {
            MinecraftServer.getExceptionManager().handleException(throwable);
            return;
        }
        this.failureSink.accept(throwable);
    }

    /**
     * Ties the scheduler to the given instance, or verifies that it is still the same one.
     *
     * @param instance the instance the caller belongs to
     * @throws IllegalStateException if the scheduler is already serving another instance
     */
    private void bind(Instance instance) {
        @Nullable Instance previous = this.bound.compareAndExchange(null, instance);

        if (previous != null && previous != instance) {
            throw new IllegalStateException(
                    "A ChunkLightScheduler serves exactly one instance, but it was given a second one. "
                            + "Build one scheduler per instance."
            );
        }
    }

    /**
     * Checks whether the given chunk is still waiting for its light.
     *
     * @param chunkX the chunk x coordinate
     * @param chunkZ the chunk z coordinate
     * @return true if the chunk is marked dirty, otherwise false
     */
    @Contract(pure = true)
    public boolean isDirty(int chunkX, int chunkZ) {
        return this.dirty.containsKey(new ChunkArea(chunkX, chunkZ));
    }
}
