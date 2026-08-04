package net.onelitefeather.falco.migration;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.onelitefeather.falco.migration.steps.DiscardHeightmapsAndLight;
import net.onelitefeather.falco.migration.steps.NamespaceStatus;
import net.onelitefeather.falco.migration.steps.UnfoldLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Runs the whole step chain over one chunk's root compound, from its stored {@code DataVersion} up to
 * a target version.
 * <p>
 * {@link #migrate(CompoundBinaryTag, int)} reads {@code DataVersion} off the chunk, declines a chunk
 * older than {@link #MINIMUM_SOURCE_VERSION} with a {@link MigrationException}, runs every
 * {@link MigrationStep} whose {@link MigrationStep#appliesTo(int)} accepts the source version in the
 * chain's declared order, and finally stamps the target version onto the result.
 * </p>
 * <p>
 * This task wires only the three steps that move or delete data without needing any renaming
 * knowledge: {@link UnfoldLevel}, {@link NamespaceStatus} and {@link DiscardHeightmapsAndLight}, in
 * that order — the order the design's step table gives them (steps 3, 6 and 8). The remaining steps
 * of that table (bit packing, entity counting, biome rebuilding, Y-range widening and block-state
 * renaming) belong to later tasks and are not part of this chain yet.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class ChunkMigration {

    /**
     * The lowest {@code DataVersion} this engine accepts: 1519, the release version of Minecraft
     * 1.13, the version that introduced the palette-based block state format every step in this
     * module assumes.
     */
    public static final int MINIMUM_SOURCE_VERSION = 1519;

    private static final String DATA_VERSION_KEY = "DataVersion";

    private static final List<MigrationStep> STEPS =
            List.of(new UnfoldLevel(), new NamespaceStatus(), new DiscardHeightmapsAndLight());

    private ChunkMigration() {
    }

    /**
     * Converts one chunk's root compound from the {@code DataVersion} it was stored with to
     * {@code targetVersion}.
     *
     * @param chunk         the chunk's root compound, as read from a region file
     * @param targetVersion the {@code DataVersion} the result should carry
     * @return the converted chunk, stamped with {@code targetVersion}
     * @throws MigrationException if the chunk's own {@code DataVersion} is older than
     *                             {@link #MINIMUM_SOURCE_VERSION}
     */
    public static CompoundBinaryTag migrate(CompoundBinaryTag chunk, int targetVersion) {
        int sourceVersion = chunk.getInt(DATA_VERSION_KEY);
        if (sourceVersion < MINIMUM_SOURCE_VERSION) {
            throw new MigrationException("Chunk DataVersion " + sourceVersion + " is older than the "
                    + "supported floor " + MINIMUM_SOURCE_VERSION + " (Minecraft 1.13); nothing below "
                    + "the flattening can be migrated by this module");
        }

        MigrationContext context = new MigrationContext(sourceVersion, targetVersion);
        CompoundBinaryTag result = chunk;
        for (MigrationStep step : STEPS) {
            if (step.appliesTo(sourceVersion)) {
                result = step.apply(result, context);
            }
        }
        return result.putInt(DATA_VERSION_KEY, targetVersion);
    }
}
