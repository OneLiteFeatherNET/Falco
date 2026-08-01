package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * The {@link BiomePaletteResolver} class translates between the biome entries of the Anvil format
 * and the biome ids of the server registry.
 * <p>
 * A biome the registry does not know is replaced with the plains biome instead of failing, which
 * follows the behaviour of the built-in loader. Every replaced name is reported once through the
 * diagnostics.
 * </p>
 * <p>
 * The registry is resolved on the first use instead of in a static initializer or in the
 * constructor. Both would require a running server before the class is touched for the first time,
 * which prevents a loader from being created while the server is still starting.
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
public final class BiomePaletteResolver implements PaletteEntryResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(BiomePaletteResolver.class);

    private static final String NAME_KEY = "Name";

    private final AnvilDiagnostics diagnostics;
    private final Supplier<DynamicRegistry<Biome>> registrySupplier;

    private volatile @Nullable Registries resolved;

    /**
     * Creates a new resolver which uses the biome registry of the running server.
     *
     * @param diagnostics the diagnostics which throttle the reports
     */
    public BiomePaletteResolver(AnvilDiagnostics diagnostics) {
        this(diagnostics, MinecraftServer::getBiomeRegistry);
    }

    /**
     * Creates a new resolver which uses the registry the given supplier provides.
     * <p>
     * The registry is resolved on the first use instead of in the constructor. A loader is often
     * created while the server is still starting and reading the registry too early would fail
     * before the loader ever touches a chunk.
     * </p>
     *
     * @param diagnostics      the diagnostics which throttle the reports
     * @param registrySupplier the supplier which provides the registry of the known biomes
     */
    public BiomePaletteResolver(AnvilDiagnostics diagnostics, Supplier<DynamicRegistry<Biome>> registrySupplier) {
        this.diagnostics = diagnostics;
        this.registrySupplier = registrySupplier;
    }

    /**
     * Returns the registry of this resolver and resolves it on the first call.
     *
     * @return the registry and the id of the fallback biome
     */
    private Registries registries() {
        Registries current = this.resolved;

        if (current != null) {
            return current;
        }

        synchronized (this) {
            Registries created = this.resolved;

            if (created == null) {
                DynamicRegistry<Biome> registry = this.registrySupplier.get();
                created = new Registries(registry, registry.getId(Biome.PLAINS));
                this.resolved = created;
            }
            return created;
        }
    }

    /**
     * The {@link Registries} record holds the resolved registry together with the id of the biome
     * which replaces an unknown one.
     *
     * @param registry   the registry which holds the known biomes
     * @param fallbackId the id of the biome which replaces an unknown one
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    private record Registries(DynamicRegistry<Biome> registry, int fallbackId) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int toId(String name, @Nullable CompoundBinaryTag properties) {
        Registries registries = registries();
        int id = registries.registry().getId(RegistryKey.unsafeOf(name));

        if (id != -1) {
            return id;
        }
        if (this.diagnostics.reportUnknownBiome(name)) {
            LOGGER.warn("The biome '{}' is unknown and is replaced with plains, further chunks with it are not reported", name);
        }
        return registries.fallbackId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompoundBinaryTag toEntry(int id) {
        RegistryKey<Biome> key = registries().registry().getKey(id);
        String name = key == null ? Biome.PLAINS.key().asString() : key.key().asString();
        return CompoundBinaryTag.builder().putString(NAME_KEY, name).build();
    }
}
