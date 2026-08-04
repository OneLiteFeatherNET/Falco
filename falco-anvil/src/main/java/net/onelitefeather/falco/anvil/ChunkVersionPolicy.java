package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Decides whether a chunk is one this loader can read.
 * <p>
 * A policy only decides. It does not count and it does not log: the loader catches the failure,
 * records it in its {@link AnvilDiagnostics} and writes the log line, so every diagnostic of a load
 * stays in one place and this contract stays free of the loader's infrastructure.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.2.0
 */
@ApiStatus.Experimental
public interface ChunkVersionPolicy {

    /**
     * Checks the given chunk data and throws if the loader cannot read it.
     *
     * @param data               the root compound of the chunk
     * @param minimumDataVersion the lowest data version the loader was configured to accept
     * @throws ChunkDataException if the chunk cannot be read
     */
    void check(CompoundBinaryTag data, int minimumDataVersion) throws ChunkDataException;
}
