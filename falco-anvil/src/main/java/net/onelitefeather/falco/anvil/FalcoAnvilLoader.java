package net.onelitefeather.falco.anvil;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * The {@link FalcoAnvilLoader} class loads and saves chunks in the Anvil format and replaces the
 * loader which Minestom ships with.
 * <p>
 * The work of a chunk is split into three stages so the expensive part never happens while a lock
 * is held. The chunk state is copied under the read lock of the chunk, the conversion between that
 * copy and the compressed bytes runs without any lock at all, and only the transfer of those bytes
 * into the region file is guarded. That is the difference which makes parallel access worthwhile,
 * because a region file which serializes decompression and parsing gains nothing from more threads.
 * </p>
 * <p>
 * A chunk which cannot be read is reported as an error instead of being reported as absent. An
 * absent chunk makes the server generate a new one which then overwrites the real data on the next
 * save, so a read failure has to stay visible.
 * </p>
 * <p>
 * A region file is never closed while a thread is reading from or writing to it. Every access
 * registers itself on the handle first, and a handle which is dropped from the cache while it is
 * still registered is closed by the last thread which leaves it. Dropping and closing are therefore
 * two separate steps, which is what allows an unload or an eviction to happen at any moment without
 * failing the work that is already running.
 * </p>
 * <p>
 * A loader which was closed refuses further work with an {@link IllegalStateException}. Silently
 * ignoring a load would report the chunk as absent and make the server overwrite it, and silently
 * ignoring a save would drop chunk data during the shutdown it belongs to.
 * </p>
 *
 * <p>
 * This type is experimental. The Anvil loader is new and its API may still change while it is
 * being validated against real worlds.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class FalcoAnvilLoader implements ChunkLoader, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(FalcoAnvilLoader.class);

    private static final BinaryTagIO.Reader TAG_READER = BinaryTagIO.unlimitedReader();
    private static final BinaryTagIO.Writer TAG_WRITER = BinaryTagIO.writer();

    private static final String SECTIONS_KEY = "sections";
    private static final String BLOCK_STATES_KEY = "block_states";
    private static final String BIOMES_KEY = "biomes";
    private static final String BLOCK_ENTITIES_KEY = "block_entities";
    private static final String STATUS_KEY = "Status";
    private static final String LEGACY_STATUS_KEY = "status";
    private static final String FULL_STATUS = "minecraft:full";

    private static final int BLOCK_ENTRIES = 16 * 16 * 16;
    private static final int BIOME_ENTRIES = 4 * 4 * 4;

    /**
     * The amount of region files a loader keeps open by default.
     */
    public static final int DEFAULT_OPEN_REGION_LIMIT = 64;

    private final int openRegionLimit;
    private final int compressionLevel;
    private final Path regionDirectory;
    private final String dimensionLabel;
    private final AnvilDiagnostics diagnostics;
    private final PaletteEntryResolver blockResolver;
    private final PaletteEntryResolver biomeResolver;
    private final Map<Long, RegionHandle> regions;
    private final Map<Long, Set<Long>> trackedChunks;
    private final Semaphore saveLimit;
    private final int dataVersion;

    private volatile boolean closed;

    /**
     * Creates a new loader for the given world directory and dimension.
     *
     * @param worldRoot the root directory of the world
     * @param dimension the key of the dimension the loader reads and writes
     */
    public FalcoAnvilLoader(Path worldRoot, Key dimension) {
        this(worldRoot, dimension, DEFAULT_OPEN_REGION_LIMIT);
    }

    /**
     * Creates a new loader which keeps at most the given amount of region files open.
     * <p>
     * A region file is normally closed as soon as every chunk this loader took from it has been
     * unloaded. The limit is the second line of defence for the case that unload calls never
     * arrive, for example because chunks stay loaded for the whole lifetime of the server. An
     * evicted file is reopened transparently on the next access.
     * </p>
     *
     * @param worldRoot       the root directory of the world
     * @param dimension       the key of the dimension the loader reads and writes
     * @param openRegionLimit the amount of region files the loader keeps open
     * @throws IllegalArgumentException if the limit is not positive
     */
    public FalcoAnvilLoader(Path worldRoot, Key dimension, int openRegionLimit) {
        if (openRegionLimit <= 0) {
            throw new IllegalArgumentException("The amount of open region files must be positive but was " + openRegionLimit);
        }
        this.openRegionLimit = openRegionLimit;
        this.compressionLevel = ChunkCompression.DEFAULT_LEVEL;
        this.regionDirectory = resolveRegionDirectory(worldRoot, dimension);
        this.dimensionLabel = dimension.asString();
        this.diagnostics = new AnvilDiagnostics();
        this.blockResolver = new BlockPaletteResolver(this.diagnostics);
        this.biomeResolver = new BiomePaletteResolver(this.diagnostics);
        this.regions = new ConcurrentHashMap<>();
        this.trackedChunks = new ConcurrentHashMap<>();
        this.saveLimit = new Semaphore(Math.max(Runtime.getRuntime().availableProcessors(), 2));
        this.dataVersion = MinecraftServer.DATA_VERSION;

        LOGGER.info("Opening the anvil loader for region={} dim={}", this.regionDirectory, this.dimensionLabel);
    }

    /**
     * Resolves the directory which holds the region files of a dimension.
     * A world which still uses the layout without a dimension directory keeps working because the
     * legacy directory is used when it exists and the current one does not.
     *
     * @param worldRoot the root directory of the world
     * @param dimension the key of the dimension
     * @return the directory which holds the region files
     */
    @Contract(pure = true)
    private static Path resolveRegionDirectory(Path worldRoot, Key dimension) {
        Path current = worldRoot.resolve("dimensions").resolve(dimension.namespace()).resolve(dimension.value()).resolve("region");
        Path legacy = worldRoot.resolve("region");

        if (!Files.isDirectory(current) && Files.isDirectory(legacy)) {
            return legacy;
        }
        return current;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The region file is registered as in use for the duration of the read, so an unload or an
     * eviction which happens in parallel cannot close the file this call is reading from.
     * </p>
     *
     * @throws IllegalStateException if the loader was already closed or is closed while the call
     *                               is looking for the region file
     */
    @Override
    public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        ensureOpen();
        RegionHandle handle;

        // The acquisition stays outside of the block which reports a failed chunk. It refuses its
        // work once the loader is closed, and a shutdown which arrives during a load is a lifecycle
        // event of the caller rather than a chunk which could not be read.
        try {
            handle = acquireRegion(chunkX, chunkZ, false);
        } catch (IOException exception) {
            throw failedLoad(chunkX, chunkZ, exception);
        }

        if (handle == null) {
            return null;
        }

        try {
            RegionFile.RawChunk raw;

            try {
                raw = handle.file().readRaw(chunkX, chunkZ);
            } finally {
                releaseRegion(handle);
            }

            if (raw == null) {
                return null;
            }

            CompoundBinaryTag data = TAG_READER.read(new ByteArrayInputStream(raw.decompress()), BinaryTagIO.Compression.NONE);

            if (!isFullyGenerated(data)) {
                if (this.diagnostics.reportPartialChunk()) {
                    LOGGER.warn(
                            "Skipping a chunk which is not fully generated chunk=[{},{}] region={} dim={}",
                            chunkX, chunkZ, this.regionDirectory, this.dimensionLabel
                    );
                }
                return null;
            }

            Chunk chunk = instance.getChunkSupplier().createChunk(instance, chunkX, chunkZ);
            // The conversion runs before the lock is taken so only the transfer into the chunk is
            // guarded. That is what keeps parallel loading worthwhile.
            List<DecodedSection> sections = decodeSections(chunk, data);

            chunk.lockWriteLock();
            try {
                for (DecodedSection section : sections) {
                    section.applyTo(chunk);
                }
                applyBlockEntities(chunk, data);
            } finally {
                chunk.unlockWriteLock();
            }
            trackChunk(chunkX, chunkZ);
            this.diagnostics.countChunkLoaded();
            return chunk;
        } catch (IOException | RuntimeException exception) {
            throw failedLoad(chunkX, chunkZ, exception);
        }
    }

    /**
     * Reports a chunk which could not be read and builds the exception which carries that failure
     * to the caller.
     * <p>
     * Reporting the chunk as absent would make the server generate a replacement which overwrites
     * the real data on the next save, so the failure has to propagate.
     * </p>
     *
     * @param chunkX    the absolute chunk x coordinate
     * @param chunkZ    the absolute chunk z coordinate
     * @param exception the failure which stopped the load
     * @return the exception the caller has to throw
     */
    private AnvilChunkException failedLoad(int chunkX, int chunkZ, Throwable exception) {
        this.diagnostics.countError();
        LOGGER.error(
                "Failed to load the chunk chunk=[{},{}] region={} dim={}",
                chunkX, chunkZ, this.regionDirectory, this.dimensionLabel, exception
        );
        MinecraftServer.getExceptionManager().handleException(exception);
        return new AnvilChunkException("The chunk " + chunkX + "/" + chunkZ + " could not be loaded", exception);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the loader was already closed or is closed while the call
     *                               is looking for the region file
     */
    @Override
    public void saveChunk(Chunk chunk) {
        ensureOpen();
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        try {
            CompoundBinaryTag data = snapshot(chunk);
            ByteArrayOutputStream target = new ByteArrayOutputStream(64 * 1024);
            TAG_WRITER.writeNamed(Map.entry("", data), target, BinaryTagIO.Compression.NONE);

            writeToRegion(chunkX, chunkZ, ChunkCompression.ZLIB.compress(target.toByteArray(), this.compressionLevel));
            this.diagnostics.countChunkSaved();
        } catch (IllegalStateException exception) {
            // The loader was closed while this save was running. Counting that as a failed chunk
            // would hide the reason behind a data error, so the refusal reaches the caller as it is.
            throw exception;
        } catch (IOException | RuntimeException exception) {
            this.diagnostics.countError();
            LOGGER.error(
                    "Failed to save the chunk chunk=[{},{}] region={} dim={}",
                    chunkX, chunkZ, this.regionDirectory, this.dimensionLabel, exception
            );
            MinecraftServer.getExceptionManager().handleException(exception);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The chunks are grouped by their region file and every group is handled by a single task. The
     * default implementation starts one thread per chunk which lets thousands of them compete for
     * the same region locks while every chunk snapshot is held in memory at the same time.
     * </p>
     *
     * @throws IllegalStateException if the loader was already closed
     */
    @Override
    public void saveChunks(Collection<Chunk> chunks) {
        ensureOpen();
        Map<Long, List<Chunk>> grouped = new HashMap<>();

        for (Chunk chunk : chunks) {
            long region = CoordConversion.regionIndex(
                    RegionConstants.chunkToRegion(chunk.getChunkX()),
                    RegionConstants.chunkToRegion(chunk.getChunkZ())
            );
            grouped.computeIfAbsent(region, ignored -> new ArrayList<>()).add(chunk);
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(grouped.size());

            for (List<Chunk> group : grouped.values()) {
                futures.add(executor.submit(() -> {
                    this.saveLimit.acquire();
                    try {
                        for (Chunk chunk : group) {
                            saveChunk(chunk);
                        }
                    } finally {
                        this.saveLimit.release();
                    }
                    return null;
                }));
            }
            awaitAll(futures);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsParallelLoading() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsParallelSaving() {
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The region file of the chunk is closed once every chunk this loader took from it has been
     * unloaded. Only chunks this loader handled itself are tracked, because a loader also receives
     * unload calls for chunks it never loaded. An unload call for such a chunk is ignored instead
     * of closing a file which is still in use.
     * </p>
     */
    @Override
    public void unloadChunk(Chunk chunk) {
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        long index = regionIndex(chunkX, chunkZ);
        Set<Long> chunks = this.trackedChunks.get(index);

        if (chunks == null || !chunks.remove(CoordConversion.chunkIndex(chunkX, chunkZ))) {
            return;
        }

        LOGGER.trace("Unloading the chunk chunk=[{},{}] dim={}", chunkX, chunkZ, this.dimensionLabel);

        if (!chunks.isEmpty()) {
            return;
        }
        // The removal has to be conditional, another thread may have registered a chunk since the
        // emptiness check above.
        if (this.trackedChunks.remove(index, chunks)) {
            closeRegion(index);
        }
    }

    /**
     * Drops the region file with the given index from the cache and closes it.
     * <p>
     * A file which is still being read from or written to is only dropped here. The thread which
     * leaves it last performs the actual close, so an unload cannot break a load which is already
     * running.
     * </p>
     *
     * @param index the index of the region file
     */
    private void closeRegion(long index) {
        RegionHandle handle = this.regions.remove(index);

        if (handle != null) {
            retire(handle, "after its last chunk was unloaded");
        }
    }

    /**
     * Closes the given handle unless a thread is still using it.
     * The handle has to be removed from the cache before this method is called, otherwise a thread
     * could register itself on a file which is about to be closed.
     *
     * @param handle the handle which was dropped from the cache
     * @param reason the reason which is written into the log line of a successful close
     */
    private void retire(RegionHandle handle, String reason) {
        if (handle.retire()) {
            closeQuietly(handle, reason);
        }
    }

    /**
     * Closes the file of the given handle and reports a failure instead of propagating it.
     *
     * @param handle the handle whose file is closed
     * @param reason the reason which is written into the log line of a successful close
     */
    private void closeQuietly(RegionHandle handle, String reason) {
        try {
            handle.file().flush();
            handle.file().close();
            LOGGER.debug("Closed the region file region={} dim={} {}", handle.file().path(), this.dimensionLabel, reason);
        } catch (IOException exception) {
            this.diagnostics.countError();
            LOGGER.error("Failed to close the region file region={} dim={}", handle.file().path(), this.dimensionLabel, exception);
        }
    }

    /**
     * Releases a handle which was obtained by {@link #acquireRegion(int, int, boolean)}.
     * The file is closed here when this thread was the last user of a handle which had already been
     * dropped from the cache.
     *
     * @param handle the handle to release
     */
    private void releaseRegion(RegionHandle handle) {
        if (handle.release()) {
            closeQuietly(handle, "after its last user finished");
        }
    }

    /**
     * Verifies that the loader is still usable.
     *
     * @throws IllegalStateException if the loader was already closed
     */
    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                    "The anvil loader for region=" + this.regionDirectory + " dim=" + this.dimensionLabel + " is closed"
            );
        }
    }

    /**
     * Records that this loader handled the given chunk so its region file can be released once
     * every chunk of that file has been unloaded again.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     */
    private void trackChunk(int chunkX, int chunkZ) {
        this.trackedChunks
                .computeIfAbsent(regionIndex(chunkX, chunkZ), ignored -> ConcurrentHashMap.newKeySet())
                .add(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    /**
     * Calculates the index of the region file which holds the given chunk.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the index of the region file
     */
    @Contract(pure = true)
    private static long regionIndex(int chunkX, int chunkZ) {
        return CoordConversion.regionIndex(RegionConstants.chunkToRegion(chunkX), RegionConstants.chunkToRegion(chunkZ));
    }

    /**
     * Returns the diagnostics which collect the counters of this loader.
     *
     * @return the diagnostics of the loader
     */
    @Contract(pure = true)
    public AnvilDiagnostics diagnostics() {
        return this.diagnostics;
    }

    /**
     * Closes every region file the loader opened and reports a summary of its work.
     * <p>
     * A loader is closed while the tasks of the server are still running, because the loader reports
     * parallel work as supported and therefore receives one task per chunk. A file which such a task
     * is still using is dropped from the cache here and closed by that task when it finishes, so no
     * handle survives the shutdown. Every later call is rejected, which is what stops a task from
     * opening a file that nobody would close again.
     * </p>
     *
     * @throws IOException if a region file cannot be closed
     */
    @Override
    public synchronized void close() throws IOException {
        if (this.closed) {
            return;
        }
        // The flag is raised before the cache is emptied. A thread which publishes a handle reads
        // the flag after publishing it, so either that thread sees the flag or the loop below sees
        // the handle, and the file is closed in both cases.
        this.closed = true;
        IOException failure = null;

        for (Long index : List.copyOf(this.regions.keySet())) {
            RegionHandle handle = this.regions.remove(index);

            if (handle == null) {
                continue;
            }
            if (!handle.retire()) {
                LOGGER.debug("Leaving the region file region={} dim={} to the task which is still using it",
                        handle.file().path(), this.dimensionLabel);
                continue;
            }

            try {
                handle.file().flush();
                handle.file().close();
            } catch (IOException exception) {
                failure = exception;
                LOGGER.error("Failed to close the region file region={} dim={}", handle.file().path(), this.dimensionLabel, exception);
            }
        }
        this.regions.clear();
        this.trackedChunks.clear();
        logSummary();

        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Writes the summary of the loader. The line reports on the error level when at least one
     * chunk failed so a shutdown which lost data does not look like a clean one.
     */
    private void logSummary() {
        long errors = this.diagnostics.errors();
        String message = "Closing the anvil loader after {} loaded and {} saved chunks with {} errors,"
                + " {} unknown blocks and {} unknown biomes region={} dim={}";

        if (errors > 0) {
            LOGGER.warn(
                    message, this.diagnostics.chunksLoaded(), this.diagnostics.chunksSaved(), errors,
                    this.diagnostics.unknownBlockCount(), this.diagnostics.unknownBiomeCount(),
                    this.regionDirectory, this.dimensionLabel
            );
            return;
        }
        LOGGER.info(
                message, this.diagnostics.chunksLoaded(), this.diagnostics.chunksSaved(), errors,
                this.diagnostics.unknownBlockCount(), this.diagnostics.unknownBiomeCount(),
                this.regionDirectory, this.dimensionLabel
        );
    }

    /**
     * Writes the given payload into the region file of the chunk.
     * <p>
     * The handle is registered as in use for the duration of the write, so an eviction which
     * happens in parallel drops the file from the cache without closing it under this thread. The
     * write therefore needs no retry.
     * </p>
     *
     * @param chunkX  the absolute chunk x coordinate
     * @param chunkZ  the absolute chunk z coordinate
     * @param payload the compressed payload of the chunk
     * @throws IOException if the chunk cannot be written
     */
    private void writeToRegion(int chunkX, int chunkZ, byte[] payload) throws IOException {
        RegionHandle handle = acquireRegion(chunkX, chunkZ, true);

        if (handle == null) {
            throw new IOException("The region file for the chunk " + chunkX + "/" + chunkZ + " could not be created");
        }

        try {
            handle.file().writeRaw(chunkX, chunkZ, ChunkCompression.ZLIB, payload);
        } finally {
            releaseRegion(handle);
        }
    }

    /**
     * Returns a registered handle for the region file which holds the given chunk.
     * <p>
     * The registration is what keeps the file open for the caller. A handle which was dropped from
     * the cache in the meantime cannot be registered any more, and the loop then opens the file
     * again instead of handing back a file which is about to be closed. Every returned handle has
     * to be released through {@link #releaseRegion(RegionHandle)}.
     * </p>
     * <p>
     * The file is opened outside of the mapping function of the map because opening it performs
     * blocking work. Doing that inside the mapping function blocks a bin of the map for the whole
     * duration and has already caused a deadlock in the loader of Minestom.
     * </p>
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @param create whether the file should be created when it does not exist yet
     * @return the registered handle or null if the file does not exist and should not be created
     * @throws IOException           if the file cannot be opened
     * @throws IllegalStateException if the loader was closed while the file was being opened
     */
    private @Nullable RegionHandle acquireRegion(int chunkX, int chunkZ, boolean create) throws IOException {
        int regionX = RegionConstants.chunkToRegion(chunkX);
        int regionZ = RegionConstants.chunkToRegion(chunkZ);
        long index = CoordConversion.regionIndex(regionX, regionZ);
        Path path = this.regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");

        while (true) {
            RegionHandle cached = this.regions.get(index);

            if (cached != null) {
                if (cached.acquire()) {
                    return cached;
                }
                // The handle was dropped between the lookup and the registration, so it is on its
                // way out and a new one has to be opened.
                continue;
            }

            if (!create && !Files.exists(path)) {
                return null;
            }
            ensureOpen();

            RegionHandle opened = new RegionHandle(RegionFile.open(path));
            RegionHandle previous = this.regions.putIfAbsent(index, opened);

            if (previous != null) {
                opened.file().close();
                continue;
            }
            // The loader can be closed between the check above and this publication. The flag is
            // read after publishing, so whoever of the two threads loses the race still sees the
            // work of the other one and the file cannot survive as an unclosed handle.
            if (this.closed) {
                if (this.regions.remove(index, opened)) {
                    retire(opened, "because the loader was closed while it was being opened");
                }
                ensureOpen();
            }

            LOGGER.debug("Opened the region file region={} dim={}", path, this.dimensionLabel);

            if (!opened.acquire()) {
                continue;
            }
            evictRegions(index);
            return opened;
        }
    }

    /**
     * Drops region files from the cache until the configured limit is met again.
     * <p>
     * The file which was just opened is never dropped so the caller keeps a cached handle. A file
     * which another thread is still using is only dropped here; that thread closes it when it
     * finishes, which can keep the loader above its limit for the duration of a single access.
     * </p>
     *
     * @param keep the index of the region file which must stay cached
     */
    private void evictRegions(long keep) {
        for (Map.Entry<Long, RegionHandle> entry : this.regions.entrySet()) {
            if (this.regions.size() <= this.openRegionLimit) {
                return;
            }
            if (entry.getKey() == keep || !this.regions.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            retire(entry.getValue(), "to stay below the open file limit");
        }
    }

    /**
     * The {@link RegionHandle} class ties a region file to the amount of threads which are working
     * with it, which is what allows the file to be dropped from the cache at any moment without
     * closing it under a thread that is still reading or writing.
     * <p>
     * A handle is either usable, which means it can accept further users, or retired, which means it
     * was dropped from the cache and is closed as soon as its last user leaves. A retired handle
     * never becomes usable again, so a thread which finds one has to open the file anew.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    private static final class RegionHandle {

        private final RegionFile file;

        private int users;
        private boolean retired;

        /**
         * Creates a handle for the given region file.
         *
         * @param file the region file the handle guards
         */
        private RegionHandle(RegionFile file) {
            this.file = file;
        }

        /**
         * Returns the region file of this handle.
         *
         * @return the guarded region file
         */
        @Contract(pure = true)
        private RegionFile file() {
            return this.file;
        }

        /**
         * Registers the calling thread as a user of the region file.
         *
         * @return true if the file may be used, false if the handle is already retired
         */
        private synchronized boolean acquire() {
            if (this.retired) {
                return false;
            }
            this.users++;
            return true;
        }

        /**
         * Removes the calling thread from the users of the region file.
         *
         * @return true if the caller has to close the file because it was the last user of a
         * retired handle, otherwise false
         */
        private synchronized boolean release() {
            this.users--;
            return this.retired && this.users == 0;
        }

        /**
         * Marks the handle as dropped from the cache so it cannot accept further users.
         *
         * @return true if the caller has to close the file because no thread is using it, false if
         * the last user closes it instead
         */
        private synchronized boolean retire() {
            this.retired = true;
            return this.users == 0;
        }
    }

    /**
     * Returns the amount of region files the loader currently keeps open.
     *
     * @return the amount of open region files
     */
    @Contract(pure = true)
    public int openRegionCount() {
        return this.regions.size();
    }

    /**
     * Checks whether the given chunk data describes a fully generated chunk.
     * The key is read in both spellings because Minestom writes it in lower case while the game
     * itself writes it capitalised.
     *
     * @param data the chunk data to check
     * @return true if the chunk is fully generated, otherwise false
     */
    @Contract(pure = true)
    private static boolean isFullyGenerated(CompoundBinaryTag data) {
        String status = NbtReads.optionalString(data, STATUS_KEY);

        if (status == null) {
            status = NbtReads.optionalString(data, LEGACY_STATUS_KEY);
        }
        return status == null || FULL_STATUS.equals(status);
    }

    /**
     * Converts the sections of the given chunk data without touching the chunk.
     * The result is applied to the chunk afterwards while the write lock is held, which keeps the
     * expensive conversion out of the guarded section.
     *
     * @param chunk the chunk the sections belong to
     * @param data  the chunk data to read
     * @return the converted sections
     * @throws IOException if a section is malformed
     */
    private List<DecodedSection> decodeSections(Chunk chunk, CompoundBinaryTag data) throws IOException {
        ListBinaryTag sections = NbtReads.optionalList(data, SECTIONS_KEY, BinaryTagTypes.COMPOUND);
        List<DecodedSection> decoded = new ArrayList<>(sections.size());

        for (int index = 0; index < sections.size(); index++) {
            CompoundBinaryTag sectionData = sections.getCompound(index);
            int sectionY = NbtReads.integer(sectionData, "Y");

            if (sectionY < chunk.getMinSection() || sectionY >= chunk.getMaxSection()) {
                // The game stores one section below and one above the world for lighting purposes.
                LOGGER.trace("Skipping the section {} outside of the world chunk=[{},{}]", sectionY, chunk.getChunkX(), chunk.getChunkZ());
                continue;
            }

            CompoundBinaryTag blockStates = NbtReads.optionalCompound(sectionData, BLOCK_STATES_KEY);
            CompoundBinaryTag biomes = NbtReads.optionalCompound(sectionData, BIOMES_KEY);

            decoded.add(new DecodedSection(
                    sectionY,
                    blockStates == null ? null : SectionCodec.decode(blockStates, this.blockResolver, BLOCK_ENTRIES, Palette.BLOCK_PALETTE_MIN_BITS),
                    biomes == null ? null : SectionCodec.decodeBiomes(biomes, this.biomeResolver, BIOME_ENTRIES, Palette.BIOME_PALETTE_MIN_BITS),
                    lightArray(sectionData, "SkyLight"),
                    lightArray(sectionData, "BlockLight")
            ));
        }
        return decoded;
    }

    /**
     * Reads a light array of a section.
     *
     * @param sectionData the section data to read
     * @param key         the key of the light array
     * @return the light array or null if the section carries none
     */
    @Contract(pure = true)
    private static byte @Nullable [] lightArray(CompoundBinaryTag sectionData, String key) {
        if (sectionData.get(key) instanceof ByteArrayBinaryTag light && light.size() == 2048) {
            return light.value();
        }
        return null;
    }

    /**
     * The {@link DecodedSection} record holds the converted content of a single section until it is
     * transferred into a chunk under its write lock.
     *
     * @param sectionY   the vertical index of the section
     * @param blocks     the converted block palette or null if the section carries none
     * @param biomes     the converted biome palette or null if the section carries none
     * @param skyLight   the stored sky light or null if the section carries none
     * @param blockLight the stored block light or null if the section carries none
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    private record DecodedSection(
            int sectionY,
            @Nullable PaletteData blocks,
            @Nullable PaletteData biomes,
            byte @Nullable [] skyLight,
            byte @Nullable [] blockLight
    ) {

        /**
         * Transfers the content of this section into the given chunk.
         * The caller has to hold the write lock of the chunk.
         *
         * @param chunk the chunk which receives the content
         * @throws IOException if a palette holds an index outside of its palette
         */
        private void applyTo(Chunk chunk) throws IOException {
            Section section = chunk.getSection(this.sectionY);

            if (this.skyLight != null) {
                section.skyLight().set(this.skyLight);
            }
            if (this.blockLight != null) {
                section.blockLight().set(this.blockLight);
            }
            if (this.blocks != null) {
                apply(section.blockPalette(), this.blocks);
            }
            if (this.biomes != null) {
                apply(section.biomePalette(), this.biomes);
            }
        }
    }

    /**
     * Transfers the given palette representation into a palette of Minestom.
     *
     * @param palette the palette which receives the values
     * @param data    the palette representation to transfer
     * @throws IOException if the representation holds an index outside of its palette
     */
    private static void apply(Palette palette, PaletteData data) throws IOException {
        if (data.isSingleValue()) {
            palette.fill(data.singleValue());
            return;
        }
        long[] packed = data.packed();

        if (packed != null && data.bitsPerEntry() == BitPacker.bitsPerEntry(data.palette().length, palette.bitsPerEntry())) {
            palette.load(data.palette(), packed);
            return;
        }

        int[] values = data.unpack();
        palette.setAll((x, y, z) -> values[index(x, y, z, palette.dimension())]);
    }

    /**
     * Calculates the index of a coordinate inside a palette of the given dimension.
     *
     * @param x         the x coordinate inside the palette
     * @param y         the y coordinate inside the palette
     * @param z         the z coordinate inside the palette
     * @param dimension the edge length of the palette
     * @return the index of the coordinate
     */
    @Contract(pure = true)
    private static int index(int x, int y, int z, int dimension) {
        return (y * dimension + z) * dimension + x;
    }

    /**
     * Applies the stored block entities to the chunk.
     *
     * @param chunk the chunk which receives the block entities
     * @param data  the chunk data to read
     * @throws IOException if a block entity is malformed
     */
    private void applyBlockEntities(Chunk chunk, CompoundBinaryTag data) throws IOException {
        ListBinaryTag entities = NbtReads.optionalList(data, BLOCK_ENTITIES_KEY, BinaryTagTypes.COMPOUND);

        for (int index = 0; index < entities.size(); index++) {
            CompoundBinaryTag entity = entities.getCompound(index);
            // The stored position is a world coordinate and has to be mapped back into the chunk.
            int x = NbtReads.integer(entity, "x") & (Chunk.CHUNK_SIZE_X - 1);
            int y = NbtReads.integer(entity, "y");
            int z = NbtReads.integer(entity, "z") & (Chunk.CHUNK_SIZE_Z - 1);

            Block block = chunk.getBlock(x, y, z);
            CompoundBinaryTag.Builder tags = CompoundBinaryTag.builder();

            for (Map.Entry<String, ? extends BinaryTag> entry : entity) {
                String key = entry.getKey();

                if (!"x".equals(key) && !"y".equals(key) && !"z".equals(key) && !"id".equals(key) && !"keepPacked".equals(key)) {
                    tags.put(key, entry.getValue());
                }
            }

            // The id names the block handler. Without resolving it the handler of every block
            // entity would be lost even though it is written back on the next save.
            if (entity.get("id") instanceof StringBinaryTag id) {
                block = block.withHandler(MinecraftServer.getBlockManager().getHandlerOrDummy(id.value()));
            }

            CompoundBinaryTag nbt = tags.build();
            chunk.setBlock(x, y, z, nbt.size() == 0 ? block : block.withNbt(nbt));
        }
    }

    /**
     * Builds the chunk data of the given chunk.
     * The state is copied under the read lock and everything else happens without it so the chunk
     * stays usable while its data is converted.
     *
     * @param chunk the chunk to describe
     * @return the chunk data of the chunk
     * @throws IOException if the chunk data cannot be built
     */
    private CompoundBinaryTag snapshot(Chunk chunk) throws IOException {
        List<Section> copies;
        List<CompoundBinaryTag> blockEntities = new ArrayList<>();

        chunk.lockReadLock();
        try {
            List<Section> sections = chunk.getSections();
            copies = new ArrayList<>(sections.size());

            for (Section section : sections) {
                copies.add(section.clone());
            }
            collectBlockEntities(chunk, blockEntities);
        } finally {
            chunk.unlockReadLock();
        }

        ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);

        for (int index = 0; index < copies.size(); index++) {
            sections.add(encodeSection(copies.get(index), chunk.getMinSection() + index));
        }

        ListBinaryTag.Builder<CompoundBinaryTag> entities = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        blockEntities.forEach(entities::add);

        return CompoundBinaryTag.builder()
                .putInt("DataVersion", this.dataVersion)
                .putInt("xPos", chunk.getChunkX())
                .putInt("zPos", chunk.getChunkZ())
                .putInt("yPos", chunk.getMinSection())
                .putString(STATUS_KEY, FULL_STATUS)
                .putLong("LastUpdate", 0L)
                .put(SECTIONS_KEY, sections.build())
                .put(BLOCK_ENTITIES_KEY, entities.build())
                .build();
    }

    /**
     * Collects the block entities of the given chunk.
     * Every block which carries data or a handler becomes a block entity, including the blocks of a
     * section which holds a single value. The loader of Minestom misses those.
     *
     * @param chunk  the chunk to read
     * @param target the list which receives the block entities
     */
    private static void collectBlockEntities(Chunk chunk, List<CompoundBinaryTag> target) {
        int minY = chunk.getMinSection() * Chunk.CHUNK_SECTION_SIZE;
        int maxY = chunk.getMaxSection() * Chunk.CHUNK_SECTION_SIZE;

        for (int y = minY; y < maxY; y++) {
            for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                    Block block = chunk.getBlock(x, y, z, Block.Getter.Condition.CACHED);

                    if (block == null) {
                        continue;
                    }

                    CompoundBinaryTag nbt = block.nbt();
                    boolean hasHandler = block.handler() != null;

                    if (nbt == null && !hasHandler) {
                        continue;
                    }

                    CompoundBinaryTag.Builder entity = CompoundBinaryTag.builder();

                    if (nbt != null) {
                        for (Map.Entry<String, ? extends BinaryTag> entry : nbt) {
                            entity.put(entry.getKey(), entry.getValue());
                        }
                    }
                    if (hasHandler) {
                        entity.putString("id", block.handler().getKey().asString());
                    }
                    // The format stores the position in world coordinates, not in chunk local ones.
                    target.add(entity
                            .putInt("x", chunk.getChunkX() * Chunk.CHUNK_SIZE_X + x)
                            .putInt("y", y)
                            .putInt("z", chunk.getChunkZ() * Chunk.CHUNK_SIZE_Z + z)
                            .build());
                }
            }
        }
    }

    /**
     * Builds the data of a single section.
     *
     * @param section  the section to describe
     * @param sectionY the vertical index of the section
     * @return the data of the section
     */
    private CompoundBinaryTag encodeSection(Section section, int sectionY) {
        return CompoundBinaryTag.builder()
                .putByte("Y", (byte) sectionY)
                .put(BLOCK_STATES_KEY, SectionCodec.encode(read(section.blockPalette(), BLOCK_ENTRIES, Palette.BLOCK_PALETTE_MIN_BITS), this.blockResolver))
                .put(BIOMES_KEY, SectionCodec.encodeBiomes(read(section.biomePalette(), BIOME_ENTRIES, Palette.BIOME_PALETTE_MIN_BITS), this.biomeResolver))
                .putByteArray("SkyLight", section.skyLight().array())
                .putByteArray("BlockLight", section.blockLight().array())
                .build();
    }

    /**
     * Reads the values of a palette of Minestom into the representation of the codec.
     *
     * @param palette         the palette to read
     * @param entryCount      the amount of entries the palette holds
     * @param minBitsPerEntry the smallest amount of bits the palette type allows
     * @return the palette representation of the palette
     */
    private static PaletteData read(Palette palette, int entryCount, int minBitsPerEntry) {
        int[] values = new int[entryCount];
        int dimension = palette.dimension();
        palette.getAll((x, y, z, value) -> values[index(x, y, z, dimension)] = value);
        return PaletteData.encode(values, minBitsPerEntry);
    }

    /**
     * Waits for every given task and reports the failures of them.
     *
     * @param futures the tasks to wait for
     */
    private void awaitAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException exception) {
                this.diagnostics.countError();
                LOGGER.error("Failed to save a group of chunks region={} dim={}", this.regionDirectory, this.dimensionLabel, exception.getCause());
                MinecraftServer.getExceptionManager().handleException(exception.getCause());
            }
        }
    }
}
