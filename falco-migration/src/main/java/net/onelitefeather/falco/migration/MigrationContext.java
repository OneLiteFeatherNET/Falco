package net.onelitefeather.falco.migration;

import org.jetbrains.annotations.ApiStatus;

/**
 * The two {@code DataVersion}s a {@link MigrationStep} needs to know: the chunk's own, and the one
 * {@link ChunkMigration#migrate(net.kyori.adventure.nbt.CompoundBinaryTag, int)} was asked to reach.
 * <p>
 * A step decides whether it runs at all from {@code sourceVersion} alone, through
 * {@link MigrationStep#appliesTo(int)}. {@code targetVersion} is carried for the steps that have to
 * know how far a chunk is going, not only where it started — none of the three structural steps built
 * in this task need it, but a step that resolves a rename table for a specific target does.
 * </p>
 *
 * @param sourceVersion the chunk's own {@code DataVersion}, read before any step ran
 * @param targetVersion the {@code DataVersion} the whole chain is converting towards
 * @since 2.1.0
 */
@ApiStatus.Experimental
public record MigrationContext(int sourceVersion, int targetVersion) {
}
