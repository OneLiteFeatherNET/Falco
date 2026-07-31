package net.onelitefeather.falco.benchmark.support;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.onelitefeather.falco.anvil.PaletteEntryResolver;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link FakePaletteEntryResolver} class translates between palette names and ids without a
 * registry.
 * <p>
 * The real resolver asks the Minestom registry, which needs a started server. Since the benchmarks
 * measure the codec and not the registry, the translation is reduced to a name which carries the id
 * in its own text. The shape of the produced entry matches what the format stores, so the amount of
 * NBT the codec has to build stays realistic.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class FakePaletteEntryResolver implements PaletteEntryResolver {

    private static final String PREFIX = "falco:state_";

    /**
     * Creates a new resolver.
     */
    public FakePaletteEntryResolver() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int toId(String name, @Nullable CompoundBinaryTag properties) {
        if (!name.startsWith(PREFIX)) {
            return 0;
        }
        return Integer.parseInt(name.substring(PREFIX.length()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompoundBinaryTag toEntry(int id) {
        return CompoundBinaryTag.builder().putString("Name", PREFIX + id).build();
    }
}
