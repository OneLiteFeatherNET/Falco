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
 * A biome the registry does not know is handed to an {@link UnknownEntryPolicy} instead of failing
 * outright: the default substitutes plains, which follows the behaviour of the built-in loader, but
 * a caller that converts or checks a world can configure a policy which refuses it instead. Every
 * unknown name is reported once through the diagnostics, regardless of what the policy does with it.
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
 * @version 1.2.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class BiomePaletteResolver implements PaletteEntryResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(BiomePaletteResolver.class);

    private static final String NAME_KEY = "Name";

    private final AnvilDiagnostics diagnostics;
    private final UnknownEntryPolicy policy;
    private final Supplier<DynamicRegistry<Biome>> registrySupplier;

    private volatile @Nullable DynamicRegistry<Biome> resolvedRegistry;

    /**
     * Creates a new resolver which uses the biome registry of the running server and replaces an
     * unknown biome following {@link DefaultUnknownEntryPolicy}.
     *
     * @param diagnostics the diagnostics which throttle the reports
     */
    public BiomePaletteResolver(AnvilDiagnostics diagnostics) {
        this(diagnostics, new DefaultUnknownEntryPolicy(), MinecraftServer::getBiomeRegistry);
    }

    /**
     * Creates a new resolver which uses the biome registry of the running server and decides what
     * an unknown biome becomes through the given policy.
     *
     * @param diagnostics the diagnostics which throttle the reports
     * @param policy      the policy consulted for a biome the registry does not know
     * @since 2.1.0
     */
    public BiomePaletteResolver(AnvilDiagnostics diagnostics, UnknownEntryPolicy policy) {
        this(diagnostics, policy, MinecraftServer::getBiomeRegistry);
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
        this(diagnostics, new DefaultUnknownEntryPolicy(), registrySupplier);
    }

    /**
     * Creates a new resolver which uses the registry the given supplier provides and decides what an
     * unknown biome becomes through the given policy.
     * <p>
     * Package-private on purpose: nothing in this module needs both a custom policy and a custom
     * registry at once, and there is no reason to promise that combination as public API before a
     * caller actually needs it. It exists for this package's own tests, which use it to exercise
     * {@link #toId(String, CompoundBinaryTag)} against a fake registry without a running server.
     * </p>
     *
     * @param diagnostics      the diagnostics which throttle the reports
     * @param policy           the policy consulted for a biome the registry does not know
     * @param registrySupplier the supplier which provides the registry of the known biomes
     * @since 2.1.0
     */
    BiomePaletteResolver(AnvilDiagnostics diagnostics, UnknownEntryPolicy policy,
                          Supplier<DynamicRegistry<Biome>> registrySupplier) {
        this.diagnostics = diagnostics;
        this.policy = policy;
        this.registrySupplier = registrySupplier;
    }

    /**
     * Returns the registry of this resolver and resolves it on the first call.
     *
     * @return the registry of the known biomes
     */
    private DynamicRegistry<Biome> registry() {
        DynamicRegistry<Biome> current = this.resolvedRegistry;

        if (current != null) {
            return current;
        }

        synchronized (this) {
            DynamicRegistry<Biome> created = this.resolvedRegistry;

            if (created == null) {
                created = this.registrySupplier.get();
                this.resolvedRegistry = created;
            }
            return created;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws AnvilChunkException   if the configured policy refuses an unknown biome
     * @throws IllegalStateException if the name the policy substitutes is itself unknown; not an
     *                               {@link AnvilChunkException}, because {@code FalcoAnvilLoader} is
     *                               the only place that constructs one, and it wraps this into one
     *                               when a chunk is read through the loader
     */
    @Override
    public int toId(String name, @Nullable CompoundBinaryTag properties) {
        DynamicRegistry<Biome> registry = registry();
        int id = registry.getId(RegistryKey.unsafeOf(name));

        if (id != -1) {
            return id;
        }
        if (this.diagnostics.reportUnknownBiome(name)) {
            LOGGER.warn("The biome '{}' is unknown, further chunks with it are not reported", name);
        }
        String substituteName = this.policy.onUnknownBiome(name);
        int substituteId = registry.getId(RegistryKey.unsafeOf(substituteName));

        // The policy is not consulted a second time for its own substitute, for the same reason
        // BlockPaletteResolver does not: a second call could loop, and a substitute the registry
        // does not know either is a failure that has to reach the caller, not carry on silently.
        if (substituteId == -1) {
            throw new IllegalStateException("The biome '" + name + "' is unknown and its substitute '"
                    + substituteName + "' is unknown too");
        }
        return substituteId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompoundBinaryTag toEntry(int id) {
        RegistryKey<Biome> key = registry().getKey(id);
        String name = key == null ? Biome.PLAINS.key().asString() : key.key().asString();
        return CompoundBinaryTag.builder().putString(NAME_KEY, name).build();
    }
}
