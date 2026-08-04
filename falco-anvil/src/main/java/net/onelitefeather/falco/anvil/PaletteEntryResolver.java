package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link PaletteEntryResolver} interface translates between the named palette entries of the
 * Anvil format and the numeric ids a server works with.
 * <p>
 * The format stores a block as a name with an optional set of properties and a biome as a plain
 * name, while Minestom addresses both through an id. Keeping that translation behind an interface
 * separates the file format from the registries of a running server, which lets the codec be
 * verified without starting one.
 * </p>
 *
 * <p>
 * This type is experimental. The Anvil loader is new and its API may still change while it is
 * being validated against real worlds.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public interface PaletteEntryResolver {

    /**
     * Resolves the id which belongs to the given palette entry.
     * <p>
     * An implementation is allowed to fail for an unknown name — unchecked, since this method
     * declares no {@code throws} clause. {@link BlockPaletteResolver} and
     * {@link BiomePaletteResolver} both delegate that decision to a caller-supplied
     * {@link UnknownEntryPolicy} rather than deciding it themselves: the shipped default
     * substitutes a replacement id, which keeps a world holding entries of a mod or of a newer game
     * version loadable instead of losing a whole chunk over a single unknown entry, but a policy
     * configured to refuse instead throws {@link AnvilChunkException} from here. A caller of
     * {@code toId} therefore has to be ready for either outcome, depending on how the resolver it
     * holds was configured.
     * </p>
     *
     * @param name       the name of the palette entry
     * @param properties the properties of the palette entry or null if it carries none
     * @return the id which belongs to the entry
     * @throws AnvilChunkException if the implementation was configured to refuse an unknown name
     *                             instead of substituting one
     */
    int toId(String name, @Nullable CompoundBinaryTag properties);

    /**
     * Builds the palette entry which belongs to the given id.
     *
     * @param id the id to describe
     * @return the palette entry of the id
     */
    CompoundBinaryTag toEntry(int id);
}
