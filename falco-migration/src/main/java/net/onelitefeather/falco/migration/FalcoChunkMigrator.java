package net.onelitefeather.falco.migration;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.onelitefeather.falco.anvil.ChunkDataException;
import net.onelitefeather.falco.anvil.ChunkMigrator;
import org.jetbrains.annotations.ApiStatus;

/**
 * Makes this module's engine available to {@code falco-anvil}'s loader as a classpath service.
 * <p>
 * The whole class is an adapter and holds no rules of its own: {@link ChunkMigration} is the engine,
 * and this type only translates its two edges — which versions it accepts, and which exception it
 * throws — into the shapes the loader's {@link ChunkMigrator} contract asks for. Putting the
 * {@code META-INF/services} registration on a dedicated adapter rather than on {@code ChunkMigration}
 * also keeps the engine usable without a loader at all, which is what the command line tool needs.
 * </p>
 * <p>
 * <b>Stateless, and therefore safe to call from several threads.</b> Every method delegates to the
 * static engine, which reads its argument and returns a new compound rather than mutating anything.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 2.2.0
 */
@ApiStatus.Experimental
public final class FalcoChunkMigrator implements ChunkMigrator {

    /**
     * Creates the adapter. Required by {@link java.util.ServiceLoader}, which instantiates it
     * through this constructor.
     */
    public FalcoChunkMigrator() {
    }

    /**
     * Reports whether the engine can lift a chunk of the given stored version.
     * <p>
     * The floor is {@link ChunkMigration#MINIMUM_SOURCE_VERSION}: below it a chunk predates the
     * flattening of Minecraft 1.13 and holds numeric block ids this engine's types do not speak.
     * There is no ceiling — a chunk newer than the server is simply not older, and is declined by
     * the second half of this test rather than by a limit of the engine.
     * </p>
     *
     * @param sourceVersion the data version the chunk carries
     * @param targetVersion the data version the server writes
     * @return whether {@link #migrate} would do anything useful with such a chunk
     */
    @Override
    public boolean canMigrate(int sourceVersion, int targetVersion) {
        return sourceVersion >= ChunkMigration.MINIMUM_SOURCE_VERSION && sourceVersion < targetVersion;
    }

    /**
     * Translates one chunk into the form the target version would have written.
     * <p>
     * {@link MigrationException} is unchecked and belongs to this module; the loader's contract
     * speaks {@link ChunkDataException}. The translation happens here rather than being left to the
     * loader, because an unchecked exception crossing that boundary would reach the loader's generic
     * {@code RuntimeException} handler and be reported as an unspecified defect instead of as what
     * it is: this chunk's data could not be converted. The original is kept as the cause.
     * </p>
     *
     * @param data          the root compound of the chunk, as read from the region file
     * @param targetVersion the data version the server writes
     * @return the translated root compound
     * @throws ChunkDataException if the chunk cannot be translated
     */
    @Override
    public CompoundBinaryTag migrate(CompoundBinaryTag data, int targetVersion) throws ChunkDataException {
        try {
            return ChunkMigration.migrate(data, targetVersion);
        } catch (MigrationException exception) {
            ChunkDataException failure = new ChunkDataException(
                    ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION,
                    "The chunk could not be migrated to data version " + targetVersion + ": "
                            + exception.getMessage());
            failure.initCause(exception);
            throw failure;
        }
    }
}
