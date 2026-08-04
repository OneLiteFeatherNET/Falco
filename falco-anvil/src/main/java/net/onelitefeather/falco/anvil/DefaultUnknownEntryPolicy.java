package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The {@link UnknownEntryPolicy} a resolver falls back to when nothing else was configured:
 * substitutes air for an unknown block and plains for an unknown biome, exactly the values
 * {@link BlockPaletteResolver} and {@link BiomePaletteResolver} hard-coded before this policy
 * existed.
 * <p>
 * The plains id is resolved from the biome registry lazily rather than in the constructor, for the
 * same reason {@link BiomePaletteResolver} resolves its registry lazily: a policy is often built
 * while the server is still starting, and reading the registry too early would fail before the
 * policy ever meets a chunk.
 * </p>
 * <p>
 * This type is experimental, like everything else in this package.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.2.0
 */
@ApiStatus.Experimental
public final class DefaultUnknownEntryPolicy implements UnknownEntryPolicy {

    private final Supplier<DynamicRegistry<Biome>> registrySupplier;

    private volatile @Nullable Integer resolvedFallbackBiomeId;

    /**
     * Creates a new policy which resolves the plains id from the biome registry of the running
     * server.
     */
    public DefaultUnknownEntryPolicy() {
        this(MinecraftServer::getBiomeRegistry);
    }

    /**
     * Creates a new policy which resolves the plains id from the registry the given supplier
     * provides.
     * <p>
     * The registry is resolved on the first use instead of in the constructor. A policy is often
     * built while the server is still starting and reading the registry too early would fail before
     * the policy ever meets a chunk.
     * </p>
     *
     * @param registrySupplier the supplier which provides the registry of the known biomes
     */
    public DefaultUnknownEntryPolicy(Supplier<DynamicRegistry<Biome>> registrySupplier) {
        this.registrySupplier = registrySupplier;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always returns {@link Block#AIR}'s state id.
     * </p>
     */
    @Override
    public int onUnknownBlock(String name, @Nullable CompoundBinaryTag properties) {
        return Block.AIR.stateId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always returns the id of {@link Biome#PLAINS} in the resolved registry.
     * </p>
     */
    @Override
    public int onUnknownBiome(String name) {
        Integer current = this.resolvedFallbackBiomeId;

        if (current != null) {
            return current;
        }

        synchronized (this) {
            Integer created = this.resolvedFallbackBiomeId;

            if (created == null) {
                created = this.registrySupplier.get().getId(Biome.PLAINS);
                this.resolvedFallbackBiomeId = created;
            }
            return created;
        }
    }
}
