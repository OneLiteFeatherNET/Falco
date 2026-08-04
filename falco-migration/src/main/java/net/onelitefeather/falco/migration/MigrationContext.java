package net.onelitefeather.falco.migration;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The two {@code DataVersion}s a {@link MigrationStep} needs to know — the chunk's own, and the one
 * {@link ChunkMigration#migrate(net.kyori.adventure.nbt.CompoundBinaryTag, int)} was asked to reach —
 * and the running total of entities a chunk's own {@code Entities} list left behind rather than moved.
 * <p>
 * A step decides whether it runs at all from {@code sourceVersion} alone, through
 * {@link MigrationStep#appliesTo(int)}. {@code targetVersion} is carried for the steps that have to
 * know how far a chunk is going, not only where it started — a step that only moves or deletes data
 * (unfolding {@code Level}, discarding heightmaps and light) never needs it, but a step that resolves
 * a rename table for a specific target does.
 * </p>
 * <p>
 * {@code entityCounter} is this context's counting sink: {@code CountEntities} adds to it through
 * {@link #countEntitiesLeftBehind(int)} once per chunk it finds still carrying entities, and a caller
 * driving several chunks through the same context — a batch run over a whole world, in particular —
 * reads {@link #entitiesLeftBehind()} afterwards for the total the design's "entity debt" warning
 * requires. It is exposed as a plain record component rather than hidden behind this class alone
 * because {@link MigrationContext} is already the one object every step in the chain receives and can
 * write into; a second, parallel channel for this one count would only duplicate what the context
 * already is.
 * </p>
 *
 * @param sourceVersion  the chunk's own {@code DataVersion}, read before any step ran
 * @param targetVersion  the {@code DataVersion} the whole chain is converting towards
 * @param entityCounter  the counter {@link #countEntitiesLeftBehind(int)} adds to and
 *                       {@link #entitiesLeftBehind()} reads
 * @since 2.1.0
 */
@ApiStatus.Experimental
public record MigrationContext(int sourceVersion, int targetVersion, AtomicInteger entityCounter) {

    /**
     * Creates a context with a fresh counter starting at zero.
     *
     * @param sourceVersion the chunk's own {@code DataVersion}, read before any step ran
     * @param targetVersion the {@code DataVersion} the whole chain is converting towards
     */
    public MigrationContext(int sourceVersion, int targetVersion) {
        this(sourceVersion, targetVersion, new AtomicInteger());
    }

    /**
     * Adds {@code count} to the running total of entities left behind in a chunk's own storage rather
     * than moved to where the target version reads them from.
     *
     * @param count how many entities one chunk's {@code Entities} list held; never negative
     */
    public void countEntitiesLeftBehind(int count) {
        entityCounter.addAndGet(count);
    }

    /**
     * The running total {@link #countEntitiesLeftBehind(int)} has accumulated so far.
     *
     * @return the total entity count counted through this context
     */
    public int entitiesLeftBehind() {
        return entityCounter.get();
    }
}
