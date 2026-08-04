package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link UnknownEntryPolicy} a resolver falls back to when nothing else was configured:
 * substitutes air for an unknown block and plains for an unknown biome, exactly the values
 * {@link BlockPaletteResolver} and {@link BiomePaletteResolver} hard-coded before this policy
 * existed.
 * <p>
 * Both names are plain string literals. This policy resolves nothing itself — {@link
 * UnknownEntryPolicy} hands back a name, not an id, precisely so that the shipped default needs
 * neither Minestom nor a registry to answer. The resolver that consults this policy already holds
 * the registry it needs to turn {@code "minecraft:air"} or {@code "minecraft:plains"} into an id,
 * because it just used that same registry to look the original, unknown name up.
 * </p>
 * <p>
 * This type is experimental, like everything else in this package.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class DefaultUnknownEntryPolicy implements UnknownEntryPolicy {

    /**
     * The name substituted for an unknown block.
     */
    static final String AIR = "minecraft:air";

    /**
     * The name substituted for an unknown biome.
     */
    static final String PLAINS = "minecraft:plains";

    /**
     * Creates the policy. It holds no state of its own, so every instance behaves the same way.
     */
    public DefaultUnknownEntryPolicy() {
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always returns {@code "minecraft:air"}.
     * </p>
     */
    @Override
    public String onUnknownBlock(String name, @Nullable CompoundBinaryTag properties) {
        return AIR;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always returns {@code "minecraft:plains"}.
     * </p>
     */
    @Override
    public String onUnknownBiome(String name) {
        return PLAINS;
    }
}
