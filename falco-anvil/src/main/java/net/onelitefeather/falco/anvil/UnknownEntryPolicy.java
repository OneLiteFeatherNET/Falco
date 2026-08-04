package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Decides what becomes of a palette entry the running server does not know.
 * <p>
 * Returning an id substitutes it; throwing {@link AnvilChunkException} fails the chunk. Substituting
 * is right for a server that wants a world to stay loadable and wrong for a tool that converts one,
 * which is why this is a policy and not a constant.
 * </p>
 * <p>
 * A policy only decides. It does not count and it does not log: the resolver which consults it keeps
 * reporting the name to its {@link AnvilDiagnostics} and writing the log line regardless of what the
 * policy does with the entry, so a substituting run stays as visible as a refusing one.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.2.0
 */
@ApiStatus.Experimental
public interface UnknownEntryPolicy {

    /**
     * Decides what an unknown block becomes.
     *
     * @param name       the block name stored in the palette
     * @param properties the stored properties, or null if the entry carries none
     * @return the state id to use instead
     * @throws AnvilChunkException if the chunk should fail rather than carry a substitute
     */
    int onUnknownBlock(String name, @Nullable CompoundBinaryTag properties);

    /**
     * Decides what an unknown biome becomes.
     *
     * @param name the biome name stored in the palette
     * @return the id to use instead
     * @throws AnvilChunkException if the chunk should fail rather than carry a substitute
     */
    int onUnknownBiome(String name);
}
