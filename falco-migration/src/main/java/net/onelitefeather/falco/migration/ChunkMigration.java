package net.onelitefeather.falco.migration;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.onelitefeather.falco.migration.steps.CountEntities;
import net.onelitefeather.falco.migration.steps.DiscardHeightmapsAndLight;
import net.onelitefeather.falco.migration.steps.NamespaceStatus;
import net.onelitefeather.falco.migration.steps.NormaliseBitPacking;
import net.onelitefeather.falco.migration.steps.RebuildBiomes;
import net.onelitefeather.falco.migration.steps.SettleYRange;
import net.onelitefeather.falco.migration.steps.TranslateBlockEntities;
import net.onelitefeather.falco.migration.steps.TranslateBlockStates;
import net.onelitefeather.falco.migration.steps.UnfoldLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Runs the whole step chain over one chunk's root compound, from its stored {@code DataVersion} up to
 * a target version.
 * <p>
 * {@link #migrate(CompoundBinaryTag, int)} reads {@code DataVersion} off the chunk and delegates to
 * {@link #migrate(CompoundBinaryTag, MigrationContext)}, which declines a chunk older than
 * {@link #MINIMUM_SOURCE_VERSION} with a {@link MigrationException}, runs every {@link MigrationStep}
 * whose {@link MigrationStep#appliesTo(int)} accepts the source version in the chain's declared order,
 * and finally stamps the target version onto the result.
 * </p>
 * <p>
 * This chain wires every step of the design's table: {@link NormaliseBitPacking} (step 1),
 * {@link CountEntities} (step 2), {@link UnfoldLevel} (step 3), {@link RebuildBiomes} (step 4),
 * {@link SettleYRange} (step 5), {@link NamespaceStatus} (step 6), {@link TranslateBlockStates} and
 * {@link TranslateBlockEntities} (step 7's block and block-entity halves) and
 * {@link DiscardHeightmapsAndLight} (step 8), in that order.
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

    private static final List<MigrationStep> STEPS = List.of(
            new NormaliseBitPacking(),
            new CountEntities(),
            new UnfoldLevel(),
            new RebuildBiomes(),
            new SettleYRange(),
            new NamespaceStatus(),
            new TranslateBlockStates(),
            new TranslateBlockEntities(),
            new DiscardHeightmapsAndLight());

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
        return migrate(chunk, new MigrationContext(sourceVersion, targetVersion));
    }

    /**
     * Converts one chunk's root compound using an already-built {@code context}, instead of building
     * a fresh one from the chunk's own {@code DataVersion}.
     * <p>
     * This is the entry point a caller reaches for when it needs to read {@code context} back
     * afterward — {@link MigrationContext#entitiesLeftBehind()} in particular, which only accumulates
     * across chunks migrated through the exact same context instance. {@link #migrate(CompoundBinaryTag, int)}
     * is the convenience wrapper every other caller uses; it builds a fresh, single-use context from
     * the chunk itself and delegates here. This overload trusts {@code context.sourceVersion()} as
     * given rather than re-reading the chunk's own {@code DataVersion} field, so a caller driving
     * several chunks through one shared context is responsible for the source version actually
     * matching each of them.
     * </p>
     *
     * @param chunk   the chunk's root compound, as read from a region file
     * @param context the source version, target version and entity counter to run the chain with
     * @return the converted chunk, stamped with {@code context.targetVersion()}
     * @throws MigrationException if {@code context.sourceVersion()} is older than
     *                             {@link #MINIMUM_SOURCE_VERSION}
     */
    public static CompoundBinaryTag migrate(CompoundBinaryTag chunk, MigrationContext context) {
        if (context.sourceVersion() < MINIMUM_SOURCE_VERSION) {
            throw new MigrationException("Chunk DataVersion " + context.sourceVersion() + " is older than the "
                    + "supported floor " + MINIMUM_SOURCE_VERSION + " (Minecraft 1.13); nothing below "
                    + "the flattening can be migrated by this module");
        }

        CompoundBinaryTag result = chunk;
        for (MigrationStep step : STEPS) {
            if (step.appliesTo(context.sourceVersion())) {
                result = step.apply(result, context);
            }
        }
        return result.putInt(DATA_VERSION_KEY, context.targetVersion());
    }
}
