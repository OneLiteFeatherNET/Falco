package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The {@link ChunkPersistence} class is everything a Falco instance does with a {@link ChunkLoader}.
 * <p>
 * Four save entry points, one read and one unload notification, and the decision on which thread each
 * of them runs. That decision is the only piece of judgement in this class and it belongs to the
 * loader: a loader which reports {@code supportsParallelSaving()} is moved onto a virtual thread, and
 * one which does not runs where it was called, so a {@code saveInstance().join()} from a tick is not
 * a thread hand-off the caller only waits for.
 * </p>
 * <p>
 * A failure completes the returned future exceptionally and stops there. It is deliberately not also
 * pushed into the exception manager of the server the way {@code InstanceContainer} does it, because
 * a failure that is both reported and returned gets handled twice and logged twice — which is
 * NFR-005 for the save direction.
 * </p>
 * <p>
 * This type was carved out of {@link FalcoInstance} and nothing about the four save paths changed in
 * the move. The loader field is still {@code volatile} for the same reason it was there: it is
 * written by a public unsynchronized setter and read on the load path from another thread, and the
 * value is an object handed in by a caller whose construction has to be visible to that reader.
 * </p>
 *
 * <h2>Why the two shutdown settings live here</h2>
 * <p>
 * {@link #saveOnShutdown()} and {@link #ownsLoader()} steer {@link FalcoInstance#shutdown(net.minestom.server.instance.InstanceManager)},
 * so at first sight they look like settings of the instance, and they were fields of it until stage 3
 * asserted that the facade holds nothing but its four parts. They are not arbitrary refugees from that
 * assertion: both are questions about the loader and about nothing else — whether the chunks are
 * written through it before the world goes away, and whether it is closed afterwards — and this is the
 * class that owns the loader. An instance without a loader answers both of them the same way whatever
 * they are set to.
 * </p>
 * <p>
 * They are deliberately not reachable from {@link FalcoInstance}, which has no {@code persistence()}
 * door. That keeps them what they were before the move: values a caller sets on
 * {@code FalcoInstance.Builder} while the world is being built, and not a switch that can be flipped
 * from under a running shutdown.
 * </p>
 * <p>
 * What the move did <em>not</em> do is worth being exact about, because the commit that made it could
 * be read as claiming more. No method of this class consults either setting; the only reader of both
 * is {@code FalcoInstance#shutdown}, and the shutdown sequence stays there because the save has to
 * happen before the unregister and the close after it — an order about the instance, not about the
 * loader. So the two values were re-homed, not consumed here, and the facade still acts on them one
 * hop away. Bringing the sequence into this class would move behaviour rather than structure, which
 * stage 3 does not do; if a later change wants that, it is a decision of its own and the argument for
 * it is that these two accessors would then have a caller inside their own class.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.1
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class ChunkPersistence {

    /**
     * The loader chunks are read from and written to, never null.
     */
    private volatile ChunkLoader chunkLoader;

    /**
     * Whether the shutdown of the instance saves the chunks before it unregisters.
     * <p>
     * Volatile for the same reason as {@link #chunkLoader}: written by a public unsynchronized setter
     * and read on a path that may run on another thread.
     * </p>
     */
    private volatile boolean saveOnShutdown = true;

    /**
     * Whether the shutdown of the instance closes the loader, if the loader can be closed.
     *
     * @see #saveOnShutdown
     */
    private volatile boolean ownsLoader;

    /**
     * Creates a persistence over a loader.
     *
     * @param loader the loader chunks are read from and written to, null for a loader which loads and
     *               saves nothing
     */
    public ChunkPersistence(@Nullable ChunkLoader loader) {
        this.chunkLoader = Objects.requireNonNullElseGet(loader, ChunkLoader::noop);
    }

    /**
     * Returns the loader chunks are read from and written to.
     *
     * @return the current chunk loader
     */
    public ChunkLoader loader() {
        return this.chunkLoader;
    }

    /**
     * Changes the loader chunks are read from and written to.
     * <p>
     * Chunks which are already loaded are not affected, and {@code ChunkLoader#loadInstance} is not
     * called again — it belongs to the construction of the instance, and calling it on a world which
     * already has chunks would overwrite live state with what is on disk.
     * </p>
     *
     * @param loader the new chunk loader
     * @throws NullPointerException if the loader is null
     */
    public void loader(ChunkLoader loader) {
        this.chunkLoader = Objects.requireNonNull(loader, "the chunk loader cannot be null");
    }

    /**
     * Reports whether the shutdown of the instance saves the chunks before it unregisters.
     *
     * @return true if the shutdown saves first
     * @since 0.4.0
     */
    public boolean saveOnShutdown() {
        return this.saveOnShutdown;
    }

    /**
     * Sets whether the shutdown of the instance saves the chunks before it unregisters.
     * <p>
     * The default is true, and the asymmetry is deliberate: saving a world nobody changed costs time,
     * while not saving one that was changed costs the changes.
     * </p>
     *
     * @param enable true if the shutdown saves before it unregisters
     * @since 0.4.0
     */
    public void saveOnShutdown(boolean enable) {
        this.saveOnShutdown = enable;
    }

    /**
     * Reports whether the shutdown of the instance closes the loader.
     *
     * @return true if the instance closes the loader when it shuts down
     * @since 0.4.0
     */
    public boolean ownsLoader() {
        return this.ownsLoader;
    }

    /**
     * Sets whether the shutdown of the instance closes the loader.
     * <p>
     * The default is false, because a loader is usually shared: the overworld and the nether of one
     * world are two instances on one loader, and the first of them to shut down must not close it
     * under the second.
     * </p>
     *
     * @param owns true if the instance closes the loader when it shuts down
     * @since 0.4.0
     */
    public void ownsLoader(boolean owns) {
        this.ownsLoader = owns;
    }

    /**
     * Reads a chunk through the current loader.
     *
     * @param instance the instance the chunk is read for
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @return the chunk the loader produced, or null if it knows nothing about that position
     */
    public @Nullable Chunk read(Instance instance, int chunkX, int chunkZ) {
        return this.chunkLoader.loadChunk(instance, chunkX, chunkZ);
    }

    /**
     * Tells the loader that a chunk is no longer part of its instance.
     * <p>
     * Called for a chunk which was unloaded and for a chunk whose load was discarded before it was
     * ever published: the loader created it and may hold bookkeeping for it, which its own
     * documentation allows for explicitly.
     * </p>
     *
     * @param chunk the chunk which left the instance
     */
    public void unloaded(Chunk chunk) {
        this.chunkLoader.unloadChunk(chunk);
    }

    /**
     * Saves the instance itself.
     *
     * @param instance the instance to save
     * @return a future completed once the work is done, completed exceptionally if it threw
     */
    public CompletableFuture<Void> saveInstance(Instance instance) {
        final ChunkLoader loader = this.chunkLoader;
        return run(loader.supportsParallelSaving(), () -> loader.saveInstance(instance));
    }

    /**
     * Saves one chunk.
     *
     * @param chunk the chunk to save
     * @return a future completed once the work is done, completed exceptionally if it threw
     */
    public CompletableFuture<Void> saveChunk(Chunk chunk) {
        final ChunkLoader loader = this.chunkLoader;
        return run(loader.supportsParallelSaving(), () -> loader.saveChunk(chunk));
    }

    /**
     * Saves a batch of chunks.
     *
     * @param chunks the chunks to save
     * @return a future completed once the work is done, completed exceptionally if it threw
     */
    public CompletableFuture<Void> saveChunks(List<Chunk> chunks) {
        final ChunkLoader loader = this.chunkLoader;
        return run(loader.supportsParallelSaving(), () -> loader.saveChunks(chunks));
    }

    /**
     * Runs a save either on the calling thread or on a virtual thread.
     *
     * @param parallel true to move the work off the calling thread
     * @param save     the work to perform
     * @return a future completed once the work is done, completed exceptionally if it threw
     */
    private CompletableFuture<Void> run(boolean parallel, Runnable save) {
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
}
