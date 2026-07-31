package net.onelitefeather.falco.demo;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * The {@link TimingChunkLoader} class wraps the loader under test and times every chunk it returns.
 * <p>
 * The measurement has to sit here and nowhere else. Minestom offers no event which carries how long
 * a load took — {@code InstanceChunkLoadEvent} arrives after the fact and knows only the chunk — so
 * anything further out would time the queueing, the light and the packet building along with it. A
 * decorator around {@code ChunkLoader#loadChunk} times exactly the call the two stacks differ in,
 * and it does so identically for both because it is the same class in both servers.
 * </p>
 * <p>
 * <b>Only a returned chunk is timed.</b> Asking for a chunk which was never generated costs a header
 * lookup and returns null, and counting those would make a flight over the edge of the world look
 * like the fastest loading the machine has ever done. The two loaders also disagree about which
 * chunks count as present, which is the second reason the null case is left out rather than averaged
 * in.
 * </p>
 * <p>
 * Everything else is delegation. The loader below may hold region files open and may be
 * {@link AutoCloseable}, so this class is closeable as well and passes the call on; a demo which left
 * the file handles of the region files behind would be the one defect a loader benchmark must not
 * have.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
public final class TimingChunkLoader implements ChunkLoader, AutoCloseable {

    private final ChunkLoader delegate;

    private final LiveMetrics metrics;

    /**
     * Wraps a loader so its loads are reported to the given metrics.
     *
     * @param delegate the loader which does the work
     * @param metrics  the metrics the durations are reported to
     */
    public TimingChunkLoader(ChunkLoader delegate, LiveMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    /**
     * Returns the loader this one wraps, so the report can name it by its class.
     *
     * @return the wrapped loader
     */
    @Contract(pure = true)
    public ChunkLoader delegate() {
        return this.delegate;
    }

    /**
     * Loads one chunk and records how long it took, unless there was no chunk to load.
     *
     * @param instance the instance the chunk belongs to
     * @param chunkX   the chunk x coordinate
     * @param chunkZ   the chunk z coordinate
     * @return the loaded chunk, or null if the world holds none at that position
     */
    @Override
    public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        long start = System.nanoTime();
        @Nullable Chunk chunk = this.delegate.loadChunk(instance, chunkX, chunkZ);
        long elapsed = System.nanoTime() - start;

        if (chunk != null) {
            this.metrics.chunkLoaded(elapsed);
        }

        return chunk;
    }

    /**
     * Passes the instance load on.
     *
     * @param instance the instance to load the data of
     */
    @Override
    public void loadInstance(Instance instance) {
        this.delegate.loadInstance(instance);
    }

    /**
     * Passes the instance save on.
     *
     * @param instance the instance to save
     */
    @Override
    public void saveInstance(Instance instance) {
        this.delegate.saveInstance(instance);
    }

    /**
     * Passes the chunk save on.
     *
     * @param chunk the chunk to save
     */
    @Override
    public void saveChunk(Chunk chunk) {
        this.delegate.saveChunk(chunk);
    }

    /**
     * Passes the bulk save on, so the delegate keeps whatever bounding it applies.
     *
     * @param chunks the chunks to save
     */
    @Override
    public void saveChunks(Collection<Chunk> chunks) {
        this.delegate.saveChunks(chunks);
    }

    /**
     * Passes the unload on, because the delegate may keep bookkeeping per chunk.
     *
     * @param chunk the chunk which was unloaded
     */
    @Override
    public void unloadChunk(Chunk chunk) {
        this.delegate.unloadChunk(chunk);
    }

    /**
     * Reports whether the delegate may be called from several threads at once.
     * <p>
     * Answering this from the delegate rather than hard-coding it is what keeps the two servers
     * honest: Minestom starts a virtual thread per chunk only when the loader says it may, so a
     * decorator which claimed otherwise would change the very behaviour being compared.
     * </p>
     *
     * @return whether chunks may be loaded in parallel
     */
    @Override
    public boolean supportsParallelLoading() {
        return this.delegate.supportsParallelLoading();
    }

    /**
     * Reports whether the delegate may be saved to from several threads at once.
     *
     * @return whether chunks may be saved in parallel
     */
    @Override
    public boolean supportsParallelSaving() {
        return this.delegate.supportsParallelSaving();
    }

    /**
     * Closes the wrapped loader if it holds anything which has to be released.
     *
     * @throws Exception if the wrapped loader fails to close
     */
    @Override
    public void close() throws Exception {
        if (this.delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
