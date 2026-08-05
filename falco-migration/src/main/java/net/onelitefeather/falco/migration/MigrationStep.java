package net.onelitefeather.falco.migration;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;

/**
 * One transformation in the chain {@link ChunkMigration} runs over a chunk's root compound.
 * <p>
 * A step is attached to a {@code DataVersion} threshold through {@link #appliesTo(int)}, which
 * {@link ChunkMigration#migrate(CompoundBinaryTag, int)} asks before calling {@link #apply}, so a step
 * that only concerns pre-1.18 chunks never has to guard its own body against a chunk that already has
 * the shape it would otherwise produce.
 * </p>
 * <p>
 * Implementations do not mutate {@code chunk}; {@link CompoundBinaryTag} is immutable by construction,
 * so {@link #apply} returns the chunk a step produced rather than changing the one it received.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public interface MigrationStep {

    /**
     * Whether this step has anything to do for a chunk with the given source version.
     *
     * @param sourceVersion the chunk's {@code DataVersion}, read once before the chain starts
     * @return {@code true} if {@link #apply(CompoundBinaryTag, MigrationContext)} should run
     */
    boolean appliesTo(int sourceVersion);

    /**
     * Transforms {@code chunk}. Only called for a chunk {@link #appliesTo(int)} accepted.
     *
     * @param chunk   the chunk's root compound, as produced by every step that ran before this one
     * @param context the source and target {@code DataVersion} of the whole chain
     * @return the transformed chunk
     */
    CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context);
}
