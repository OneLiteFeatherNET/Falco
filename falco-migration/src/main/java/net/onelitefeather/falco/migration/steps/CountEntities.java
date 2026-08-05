package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

/**
 * Counts the entities a chunk still carries in its own {@code Entities} list, and moves nothing.
 * <p>
 * From Minecraft 1.17 the server reads entities only from separate {@code entities/} region files; a
 * chunk below that version still carries them inline, under {@code Level.Entities}. This step only
 * ever runs before {@link UnfoldLevel} in
 * {@link net.onelitefeather.falco.migration.ChunkMigration}'s chain, so a chunk it applies to still
 * has its {@code Level} compound intact when this step sees it — {@code Entities} is read from there,
 * never from the chunk's root.
 * </p>
 * <p>
 * <b>This step does not move that list anywhere, and neither does anything else in this chain.</b>
 * The design's "entity debt" is stated plainly because it is the same failure mode the loader's
 * version guard exists to end: a world converted by this module keeps its entity data in the chunk,
 * where the target version will never look for it. Every mob, item frame, armour stand, painting and
 * dropped item is effectively gone — the bytes are still there, nothing is deleted, but nothing reads
 * them either. This step's only job is to make that consequence visible instead of silent: it adds
 * the size of every chunk's own {@code Entities} list to
 * {@link MigrationContext#countEntitiesLeftBehind(int)}, so a caller can report the total rather than
 * discover a converted world's missing mobs the hard way.
 * </p>
 * <p>
 * {@link #appliesTo(int)} accepts a source version strictly below {@value #APPLIES_BELOW} —
 * {@code DataVersion} 2681, snapshot 20w45a, the snapshot that actually extracted entities into
 * their own {@code entities/} region files. This is deliberately <b>not</b> 2724, the
 * {@code DataVersion} of the 1.17 release that snapshot led up to: the design's own step table names
 * 2724, which is the release number substituted for the snapshot number that actually carries the
 * change — the exact mistake this module's block-state rules already found and corrected once for
 * this identical snapshot (grass_path's rename also landed in 20w45a). Verified against two
 * independent fetches of 20w45a's own wiki infobox and changelog text, 2026-08-04; see the task
 * report. A chunk at or above {@value #APPLIES_BELOW} has already had its entities extracted by the
 * time it reaches this module, so whatever remains of its own {@code Entities} list, if any, is not
 * this step's concern.
 * </p>
 * <p>
 * A chunk with no {@code Level} compound, or no {@code Entities} list within it, contributes nothing
 * to the count and is returned unchanged either way.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class CountEntities implements MigrationStep {

    /**
     * DataVersion 2681 is the 20w45a snapshot that extracted entities from the chunk into separate
     * {@code entities/} region files.
     */
    private static final int APPLIES_BELOW = 2681;

    private static final String LEVEL_KEY = "Level";
    private static final String ENTITIES_KEY = "Entities";

    /**
     * Creates a new instance of this stateless step.
     */
    public CountEntities() {
    }

    @Override
    public boolean appliesTo(int sourceVersion) {
        return sourceVersion < APPLIES_BELOW;
    }

    /**
     * {@inheritDoc}
     *
     * @param chunk   {@inheritDoc}
     * @param context {@inheritDoc} — {@link MigrationContext#countEntitiesLeftBehind(int)} receives
     *                the size of {@code chunk}'s own {@code Level.Entities} list, if present
     * @return {@code chunk}, unchanged — this step counts, it never moves or deletes
     */
    @Override
    public CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context) {
        if (chunk.get(LEVEL_KEY) instanceof CompoundBinaryTag level
                && level.get(ENTITIES_KEY) instanceof ListBinaryTag entities
                && !entities.isEmpty()) {
            context.countEntitiesLeftBehind(entities.size());
        }
        return chunk;
    }
}
