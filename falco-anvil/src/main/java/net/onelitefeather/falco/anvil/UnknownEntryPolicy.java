package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Decides what becomes of a palette entry the running server does not know.
 * <p>
 * Returning a name substitutes it; throwing {@link AnvilChunkException} fails the chunk.
 * Substituting is right for a server that wants a world to stay loadable and wrong for a tool that
 * converts one, which is why this is a policy and not a constant.
 * </p>
 * <p>
 * A policy names a replacement; it does not resolve one. It returns a palette name such as
 * {@code "minecraft:stone"}, not an id — the resolver that consults it owns the registry lookup
 * that turns a name into an id, the same registry it already needed to look the original, unknown
 * name up in the first place. That split keeps this interface, and any implementation of it, free of
 * a dependency on Minestom or any registry: naming {@code "minecraft:air"} takes no more than a
 * string literal.
 * </p>
 * <p>
 * A policy only decides. It does not count and it does not log: the resolver which consults it keeps
 * reporting the name to its {@link AnvilDiagnostics} and writing the log line regardless of what the
 * policy does with the entry, so a substituting run stays as visible as a refusing one. Nor does a
 * policy get asked twice: if the name it returns is itself unknown, the resolver fails the chunk
 * instead of consulting the policy again, which would risk a loop.
 * </p>
 * <p>
 * <b>Called from several threads at once.</b> The policy is resolved once, when the loader is built,
 * and both {@link BlockPaletteResolver} and {@link BiomePaletteResolver} keep consulting that same
 * instance for as long as the loader lives — including every parallel load, since
 * {@link FalcoAnvilLoader#supportsParallelLoading()} reports {@code true}. An implementation
 * therefore has to be thread-safe on its own; neither resolver takes a lock around the call.
 * {@link DefaultUnknownEntryPolicy}, the shipped default, holds no state and needs none.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 2.1.0
 */
@ApiStatus.Experimental
public interface UnknownEntryPolicy {

    /**
     * Decides what an unknown block becomes.
     *
     * @param name       the block name stored in the palette
     * @param properties the stored properties, or null if the entry carries none
     * @return the name of the block to use instead
     * @throws AnvilChunkException if the chunk should fail rather than carry a substitute
     */
    String onUnknownBlock(String name, @Nullable CompoundBinaryTag properties);

    /**
     * Decides what an unknown biome becomes.
     *
     * @param name the biome name stored in the palette
     * @return the name of the biome to use instead
     * @throws AnvilChunkException if the chunk should fail rather than carry a substitute
     */
    String onUnknownBiome(String name);
}
