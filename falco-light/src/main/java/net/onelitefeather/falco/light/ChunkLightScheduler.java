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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

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
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class ChunkLightScheduler {

    /**
     * The amount of chunks a single area holds at most when no other value is given.
     * <p>
     * One {@link ChunkLightState} is roughly 980 KB of buffers, so sixteen chunks plus their ring
     * is about the largest allocation that still fits comfortably inside a tick.
     * </p>
     */
    public static final int DEFAULT_MAX_AREA_SIZE = 16;

    /**
     * The tick timestamp of a scheduler that has never run a pass.
     */
    private static final long NEVER = Long.MIN_VALUE;

    private final ChunkLightArea area;
    private final Executor executor;
    private final int maxAreaSize;

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
     * @throws IllegalArgumentException if the given area size is smaller than one
     */
    public ChunkLightScheduler(ChunkLightService service, Executor executor, int maxAreaSize) {
        if (maxAreaSize < 1) {
            throw new IllegalArgumentException("An area has to be able to hold at least one chunk but the cap was " + maxAreaSize);
        }
        this.area = new ChunkLightArea(service);
        this.executor = executor;
        this.maxAreaSize = maxAreaSize;
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
     * @return an executor which runs areas on virtual threads, bounded by the processor count
     */
    private static Executor defaultExecutor() {
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
     * Returns a chunk supplier which produces chunks that report to this scheduler.
     * <p>
     * This is the entire setup a consumer needs:
     * </p>
     * <pre>{@code
     * ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());
     * instance.setChunkSupplier(scheduler.supplier());
     * }</pre>
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
     *
     * @param instance the instance the chunk belongs to
     * @param chunkX   the chunk x coordinate
     * @param chunkZ   the chunk z coordinate
     * @throws IllegalStateException if the scheduler is already serving another instance
     */
    public void markDirty(Instance instance, int chunkX, int chunkZ) {
        bind(instance);
        this.dirty.merge(new ChunkArea(chunkX, chunkZ), 1L, Long::sum);
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
     *
     * @param instance the instance whose chunks are lit
     * @param group    the chunks of the area
     */
    private void compute(Instance instance, List<ChunkArea> group) {
        try {
            Map<ChunkArea, Long> recorded = new HashMap<>(group.size());

            for (ChunkArea position : group) {
                recorded.put(position, this.dirty.getOrDefault(position, NEVER));
            }
            Predicate<ChunkArea> unchanged = position -> Objects.equals(this.dirty.get(position), recorded.get(position));

            List<ChunkArea> written = this.area.compute(instance, group, false, unchanged);

            if (instance.getCachedDimensionType().hasSkylight()) {
                this.area.compute(instance, group, true, unchanged);
            }

            for (ChunkArea position : written) {
                this.dirty.remove(position, recorded.get(position));
                deliver(instance, position);
            }
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
     * Reports a failure of one area to the exception manager of the server.
     *
     * @param throwable the failure to report
     */
    private static void report(Throwable throwable) {
        MinecraftServer.getExceptionManager().handleException(throwable);
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
