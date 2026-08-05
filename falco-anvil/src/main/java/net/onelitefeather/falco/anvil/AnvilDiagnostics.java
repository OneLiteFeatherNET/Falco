package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * The {@link AnvilDiagnostics} class throttles repeating warnings of a chunk loader and collects
 * the counters which are reported when the loader is closed.
 * <p>
 * A world can hold thousands of chunks which all trigger the same problem, for example a block
 * which the server does not know. Logging that problem once per chunk would flood the log without
 * adding any information, so every report is reduced to the first occurrence of a distinct name.
 * The amount of tracked names is capped because a broken world can contain an unbounded amount of
 * unknown names which would otherwise grow the tracking sets without a limit.
 * </p>
 * <p>
 * Throttling a message and counting an event are two different things, and this class keeps them
 * apart. A loader which returns nothing has to be able to say how many chunks it dropped and for
 * which of the three possible reasons, so every report increments a counter even when it is not
 * logged. A single line saying "a chunk was not fully generated" answers none of the questions a
 * user has when sixty-four chunks turn into zero; the three counters and the status values behind
 * {@link #partialChunkStatuses()} do.
 * </p>
 * <p>
 * Instances are safe to use from multiple threads which is required because a loader reports from
 * every thread that loads or saves a chunk.
 * </p>
 *
 * <p>
 * This type is experimental. The Anvil loader is new and its API may still change while it is
 * being validated against real worlds.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class AnvilDiagnostics {

    /**
     * The highest amount of distinct names a single category tracks.
     */
    public static final int MAX_TRACKED_NAMES = 64;

    /**
     * The status a partially generated chunk is counted under when the caller does not know which
     * value the chunk carried.
     */
    public static final String UNKNOWN_STATUS = "<unknown>";

    /**
     * The value an unsupported chunk is counted under when it stored no {@code DataVersion} at all.
     */
    public static final String UNKNOWN_DATA_VERSION = "<none>";

    private final Set<String> unknownBlocks;
    private final Set<String> unknownBiomes;
    private final Map<String, LongAdder> partialChunkStatuses;
    private final Map<String, LongAdder> unsupportedChunkVersions;
    private final AtomicBoolean missingRegionFileReported;
    private final AtomicBoolean missingChunkEntryReported;
    private final AtomicBoolean sectionRangeReported;
    private final LongAdder chunksLoaded;
    private final LongAdder chunksSaved;
    private final LongAdder errors;
    private final LongAdder chunksWithoutRegionFile;
    private final LongAdder chunksWithoutEntry;
    private final LongAdder partialChunks;
    private final LongAdder unsupportedChunks;
    private final LongAdder chunksMigrated;
    private final Map<String, LongAdder> migratedSourceVersions;

    /**
     * Creates a new diagnostics instance with empty counters.
     */
    public AnvilDiagnostics() {
        this.unknownBlocks = ConcurrentHashMap.newKeySet();
        this.unknownBiomes = ConcurrentHashMap.newKeySet();
        this.partialChunkStatuses = new ConcurrentHashMap<>();
        this.unsupportedChunkVersions = new ConcurrentHashMap<>();
        this.missingRegionFileReported = new AtomicBoolean();
        this.missingChunkEntryReported = new AtomicBoolean();
        this.sectionRangeReported = new AtomicBoolean();
        this.chunksLoaded = new LongAdder();
        this.chunksSaved = new LongAdder();
        this.errors = new LongAdder();
        this.chunksWithoutRegionFile = new LongAdder();
        this.chunksWithoutEntry = new LongAdder();
        this.partialChunks = new LongAdder();
        this.unsupportedChunks = new LongAdder();
        this.chunksMigrated = new LongAdder();
        this.migratedSourceVersions = new ConcurrentHashMap<>();
    }

    /**
     * Checks whether an unknown block name should be reported.
     * Only the first occurrence of a name passes so a repeating problem does not flood the log.
     *
     * @param name the name of the unknown block
     * @return true if the caller should log the name, otherwise false
     */
    public boolean reportUnknownBlock(String name) {
        return track(this.unknownBlocks, name);
    }

    /**
     * Checks whether an unknown biome name should be reported.
     * Only the first occurrence of a name passes so a repeating problem does not flood the log.
     *
     * @param name the name of the unknown biome
     * @return true if the caller should log the name, otherwise false
     */
    public boolean reportUnknownBiome(String name) {
        return track(this.unknownBiomes, name);
    }

    /**
     * Counts a chunk which was skipped because its region file does not exist and decides whether
     * that should be logged.
     * <p>
     * A chunk without a region file is the normal case for a world which is still being generated,
     * so only the first one is logged while every one of them is counted. The count is what
     * separates "the world has holes" from "the loader is reading the wrong directory".
     * </p>
     *
     * @return true if the caller should log the problem, otherwise false
     */
    public boolean reportMissingRegionFile() {
        this.chunksWithoutRegionFile.increment();
        return this.missingRegionFileReported.compareAndSet(false, true);
    }

    /**
     * Counts a chunk which was skipped because its region file holds no entry for it and decides
     * whether that should be logged.
     * <p>
     * Kept apart from {@link #reportMissingRegionFile()} on purpose. Both return no chunk, but the
     * first says the loader found no file at all and the second says it found the file and the
     * chunk was never written into it, which are two different mistakes with two different remedies.
     * </p>
     *
     * @return true if the caller should log the problem, otherwise false
     */
    public boolean reportMissingChunkEntry() {
        this.chunksWithoutEntry.increment();
        return this.missingChunkEntryReported.compareAndSet(false, true);
    }

    /**
     * Counts a partially generated chunk under the status it carries and decides whether that
     * should be logged.
     * <p>
     * The throttling is per status value rather than per loader, so a world which holds several
     * generation stages names all of them exactly once. That is also what bounds the amount of log
     * lines, because the amount of tracked status values is capped like every other category here.
     * A status beyond the cap is still counted in {@link #chunksSkippedAsPartial()} and only loses
     * its own entry in {@link #partialChunkStatuses()}.
     * </p>
     *
     * @param status the status value which was stored in the chunk
     * @return true if the caller should log the problem, otherwise false
     */
    public boolean reportPartialChunk(String status) {
        this.partialChunks.increment();
        LongAdder counter = this.partialChunkStatuses.get(status);

        if (counter == null) {
            // The size check and the insertion cannot be one atomic step, so racing threads can
            // push the map past the cap by at most one entry each. Unlike the name sets the map is
            // not trimmed back afterwards: an entry which was already counted cannot be removed
            // without losing the count it carries, and the counts are the point of this map.
            if (this.partialChunkStatuses.size() >= MAX_TRACKED_NAMES) {
                return false;
            }
            LongAdder created = new LongAdder();
            LongAdder previous = this.partialChunkStatuses.putIfAbsent(status, created);

            if (previous == null) {
                created.increment();
                return true;
            }
            counter = previous;
        }
        counter.increment();
        return false;
    }

    /**
     * Counts a partially generated chunk whose status value is not known to the caller.
     * <p>
     * Kept so callers written against the first version of this class keep compiling. Anything
     * which can read the status should call {@link #reportPartialChunk(String)} instead, because
     * the status is the only part of this report which says what is wrong with the world.
     * </p>
     *
     * @return true if the caller should log the problem, otherwise false
     */
    public boolean reportPartialChunk() {
        return reportPartialChunk(UNKNOWN_STATUS);
    }

    /**
     * Reports a chunk which comes from a version this loader cannot read.
     * <p>
     * The throttling is per version value rather than per loader, so a world holding several
     * versions names each of them exactly once. A version beyond the cap is still counted in
     * {@link #chunksSkippedAsUnsupported()} and only loses its own entry in
     * {@link #unsupportedChunkVersions()}.
     * </p>
     *
     * @param version the stored data version, or {@link #UNKNOWN_DATA_VERSION} if none was stored
     * @return true if the caller should log the problem, otherwise false
     * @since 1.1.0
     */
    public boolean reportUnsupportedChunkVersion(String version) {
        this.unsupportedChunks.increment();
        LongAdder counter = this.unsupportedChunkVersions.get(version);

        if (counter == null) {
            // The size check and the insertion cannot be one atomic step, so racing threads can
            // push the version map past the cap by at most one entry each. The map is not trimmed
            // back afterwards: a version which was already counted cannot be removed without losing
            // the count it carries, and tracking the counts per version is the point of this map.
            if (this.unsupportedChunkVersions.size() >= MAX_TRACKED_NAMES) {
                return false;
            }
            LongAdder created = new LongAdder();
            LongAdder previous = this.unsupportedChunkVersions.putIfAbsent(version, created);

            if (previous == null) {
                created.increment();
                return true;
            }
            previous.increment();
            return false;
        }
        counter.increment();
        return false;
    }

    /**
     * Checks whether a section outside of the dimension height should be reported.
     *
     * @return true if the caller should log the problem, otherwise false
     */
    public boolean reportSectionOutOfRange() {
        return this.sectionRangeReported.compareAndSet(false, true);
    }

    /**
     * Counts a chunk which was loaded successfully.
     */
    public void countChunkLoaded() {
        this.chunksLoaded.increment();
    }

    /**
     * Counts a chunk which was saved successfully.
     */
    public void countChunkSaved() {
        this.chunksSaved.increment();
    }

    /**
     * Counts a chunk which was translated from an older version to the one the server writes.
     * <p>
     * Counted per source version as well as in total, because the two answer different questions. A
     * total says how much work migration is costing this run; the breakdown says which versions a
     * world actually holds, which is what tells somebody whether a conversion is nearly finished or
     * has barely begun. The same per-version cap as elsewhere in this class applies: a version
     * beyond it is still counted in {@link #chunksMigrated()} and only loses its own entry.
     * </p>
     *
     * @param sourceVersion the data version the chunk carried before it was translated
     * @since 2.2.0
     */
    public void countChunkMigrated(int sourceVersion) {
        this.chunksMigrated.increment();
        String version = Integer.toString(sourceVersion);
        LongAdder counter = this.migratedSourceVersions.get(version);

        if (counter == null) {
            if (this.migratedSourceVersions.size() >= MAX_TRACKED_NAMES) {
                return;
            }
            LongAdder created = new LongAdder();
            LongAdder previous = this.migratedSourceVersions.putIfAbsent(version, created);
            (previous == null ? created : previous).increment();
            return;
        }
        counter.increment();
    }

    /**
     * Returns the amount of chunks which were translated from an older version.
     *
     * @return the amount of migrated chunks
     * @since 2.2.0
     */
    public long chunksMigrated() {
        return this.chunksMigrated.sum();
    }

    /**
     * Returns how many chunks were migrated per stored source version.
     *
     * @return the amount of migrated chunks per source version
     * @since 2.2.0
     */
    public @Unmodifiable Map<String, Long> migratedSourceVersions() {
        Map<String, Long> snapshot = new java.util.HashMap<>();
        this.migratedSourceVersions.forEach((version, counter) -> snapshot.put(version, counter.sum()));
        return Map.copyOf(snapshot);
    }

    /**
     * Counts a chunk which could not be loaded or saved.
     */
    public void countError() {
        this.errors.increment();
    }

    /**
     * Returns the amount of chunks which were loaded successfully.
     *
     * @return the amount of loaded chunks
     */
    @Contract(pure = true)
    public long chunksLoaded() {
        return this.chunksLoaded.sum();
    }

    /**
     * Returns the amount of chunks which were saved successfully.
     *
     * @return the amount of saved chunks
     */
    @Contract(pure = true)
    public long chunksSaved() {
        return this.chunksSaved.sum();
    }

    /**
     * Returns the amount of chunks which could not be loaded or saved.
     *
     * @return the amount of failed chunks
     */
    @Contract(pure = true)
    public long errors() {
        return this.errors.sum();
    }

    /**
     * Returns the amount of chunks which were skipped because their region file does not exist.
     *
     * @return the amount of chunks without a region file
     */
    @Contract(pure = true)
    public long chunksSkippedWithoutRegionFile() {
        return this.chunksWithoutRegionFile.sum();
    }

    /**
     * Returns the amount of chunks which were skipped because their region file holds no entry
     * for them.
     *
     * @return the amount of chunks without an entry in their region file
     */
    @Contract(pure = true)
    public long chunksSkippedWithoutEntry() {
        return this.chunksWithoutEntry.sum();
    }

    /**
     * Returns the amount of chunks which were skipped because they are not fully generated.
     *
     * @return the amount of partially generated chunks
     */
    @Contract(pure = true)
    public long chunksSkippedAsPartial() {
        return this.partialChunks.sum();
    }

    /**
     * Returns the amount of chunks which were skipped for any of the three reasons.
     * <p>
     * A skipped chunk is not an error and is therefore not part of {@link #errors()}. It is still
     * the difference between the chunks a world holds and the chunks a loader returned, which is
     * the number somebody stares at when a run reports nothing.
     * </p>
     *
     * @return the amount of skipped chunks
     */
    @Contract(pure = true)
    public long chunksSkipped() {
        return chunksSkippedWithoutRegionFile() + chunksSkippedWithoutEntry() + chunksSkippedAsPartial();
    }

    /**
     * Returns how often each status value of a partially generated chunk was seen.
     * <p>
     * The snapshot is taken while other threads may still be reporting, so a value can be one
     * increment behind. It is a report and not a ledger; what matters is which status values a
     * world holds, and that is exact.
     * </p>
     *
     * @return the amount of chunks per status value, sorted by the status value
     */
    @Contract(pure = true)
    public @Unmodifiable Map<String, Long> partialChunkStatuses() {
        Map<String, Long> snapshot = new LinkedHashMap<>();

        this.partialChunkStatuses.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue().sum()));

        // A LinkedHashMap rather than Map#copyOf, because the order of the entries is part of what
        // is promised here: a summary which lists the status values in a different order on every
        // shutdown cannot be compared between two runs.
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns how many chunks were refused because their version could not be read.
     *
     * @return the amount of refused chunks
     * @since 1.1.0
     */
    @Contract(pure = true)
    public long chunksSkippedAsUnsupported() {
        return this.unsupportedChunks.sum();
    }

    /**
     * Returns the amount of refused chunks per stored data version, sorted by the version value.
     *
     * @return the amount of refused chunks per version
     * @since 1.1.0
     */
    @Contract(pure = true)
    public @Unmodifiable Map<String, Long> unsupportedChunkVersions() {
        Map<String, Long> snapshot = new LinkedHashMap<>();

        this.unsupportedChunkVersions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue().sum()));

        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns the amount of distinct unknown block names which were reported.
     *
     * @return the amount of unknown block names
     */
    @Contract(pure = true)
    public int unknownBlockCount() {
        return this.unknownBlocks.size();
    }

    /**
     * Returns the amount of distinct unknown biome names which were reported.
     *
     * @return the amount of unknown biome names
     */
    @Contract(pure = true)
    public int unknownBiomeCount() {
        return this.unknownBiomes.size();
    }

    /**
     * Adds the given name to the tracking set as long as the cap is not reached yet.
     *
     * @param names the set which tracks the names of a category
     * @param name  the name to track
     * @return true if the name was added by this call, otherwise false
     */
    private static boolean track(Set<String> names, String name) {
        // The size check and the insertion cannot be one atomic step on a set, so racing threads
        // could push it past the cap. A slightly relaxed bound is acceptable here because the cap
        // exists to protect the heap, not to be an exact quota, but the set is trimmed back so it
        // cannot drift upwards over time.
        if (names.size() >= MAX_TRACKED_NAMES) {
            return false;
        }
        if (!names.add(name)) {
            return false;
        }
        if (names.size() > MAX_TRACKED_NAMES) {
            names.remove(name);
            return false;
        }
        return true;
    }
}
