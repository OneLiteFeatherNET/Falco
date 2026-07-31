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
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public interface PaletteEntryResolver {

    /**
     * Resolves the id which belongs to the given palette entry.
     * <p>
     * An implementation must not fail for an unknown name. A world can hold entries of a mod or of
     * a newer game version and losing a whole chunk over a single unknown entry would destroy more
     * data than it protects. An implementation is expected to return a replacement id instead and
     * to report the name to the caller.
     * </p>
     *
     * @param name       the name of the palette entry
     * @param properties the properties of the palette entry or null if it carries none
     * @return the id which belongs to the entry
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
