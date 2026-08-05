package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Lifts the stored form of a chunk from the version it was written by to the version the server
 * runs.
 * <p>
 * This interface exists in {@code falco-anvil} rather than next to the engine that implements it
 * because the dependency only runs one way: {@code falco-migration} depends on this module, so this
 * module cannot depend on it back. A loader therefore names the capability and finds a provider on
 * the classpath, exactly as it does for {@link ChunkVersionPolicy} and {@link UnknownEntryPolicy}.
 * A deployment that never puts a migration engine on the classpath carries no migration code at
 * all.
 * </p>
 * <p>
 * <b>A migrator translates and nothing else.</b> It does not decide whether migration should happen
 * — {@link ChunkMigrationMode} does — it does not read or write region files, and it does not log or
 * count. It is handed the root compound of one chunk and returns the root compound that same chunk
 * would have if the current version had written it.
 * </p>
 * <p>
 * <b>Called from several threads at once.</b> The migrator is resolved once, when the loader is
 * built, and every load after that consults the same instance, including every parallel load. An
 * implementation has to be thread-safe on its own; the loader takes no lock around the call.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 2.2.0
 */
@ApiStatus.Experimental
public interface ChunkMigrator {

    /**
     * Reports whether this migrator can lift a chunk of the given stored version.
     * <p>
     * Asked before {@link #migrate} so that a chunk this migrator cannot help with is passed through
     * untouched rather than failing the load. An engine that starts at Minecraft 1.13 answers
     * {@code false} for everything below it, and the loader then treats the chunk exactly as it
     * would in {@link ChunkMigrationMode#OFF} — which is what the caller had before, not a
     * regression.
     * </p>
     *
     * @param sourceVersion the data version the chunk carries
     * @param targetVersion the data version the server writes
     * @return whether {@link #migrate} would do anything useful with such a chunk
     */
    boolean canMigrate(int sourceVersion, int targetVersion);

    /**
     * Translates one chunk into the form the target version would have written.
     *
     * @param data          the root compound of the chunk, as read from the region file
     * @param targetVersion the data version the server writes
     * @return the translated root compound, which may be {@code data} itself if nothing applied
     * @throws ChunkDataException if the chunk cannot be translated, which fails that one chunk's
     *                            load rather than being silently passed through — a chunk that
     *                            could not be migrated would otherwise reach the server as the
     *                            partly-unreadable data this whole option exists to prevent
     */
    CompoundBinaryTag migrate(CompoundBinaryTag data, int targetVersion) throws ChunkDataException;
}
