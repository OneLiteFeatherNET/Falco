package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Resolves the two decisions that currently sit hardcoded in the loader as classpath services:
 * which worlds are readable, and what an unknown block becomes. This type builds the resolution
 * rules once, so the extension points built on top of it do not each reinvent them.
 * <p>
 * A caller either configures an instance explicitly, or asks for classpath discovery, but not both
 * at once: {@link #choose(Class, Object, boolean)} refuses the combination outright rather than
 * silently preferring one side. Discovery itself, in {@link #discover(Class)}, refuses to guess
 * between more than one registered provider.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.0.0
 */
final class ServiceResolution {

    private ServiceResolution() {
    }

    /**
     * Finds the single provider of the given service on the classpath.
     *
     * @param service the service interface
     * @param <T>     the service type
     * @return the provider, or null if the classpath carries none
     * @throws IllegalStateException if more than one provider is registered
     */
    static <T> @Nullable T discover(Class<T> service) {
        List<T> providers = new ArrayList<>();
        ServiceLoader.load(service).forEach(providers::add);

        if (providers.isEmpty()) {
            return null;
        }
        if (providers.size() > 1) {
            // Naming them is the whole value of this branch: "several providers" sends the reader
            // to the classpath, the two class names send them to the jar that should not be there.
            throw new IllegalStateException(
                    "Several providers of " + service.getName() + " are registered and none can be chosen for you: "
                            + providers.stream().map(provider -> provider.getClass().getName()).sorted().toList()
                            + ". Set one explicitly on the builder instead."
            );
        }
        return providers.getFirst();
    }

    /**
     * Chooses between an explicitly configured instance and classpath discovery.
     *
     * @param service  the service interface
     * @param explicit the instance the caller configured, or null
     * @param discover whether the caller asked for discovery
     * @param <T>      the service type
     * @return the chosen provider, or null if the caller asked for neither
     * @throws IllegalStateException if the caller asked for both, or if discovery is ambiguous
     */
    static <T> @Nullable T choose(Class<T> service, @Nullable T explicit, boolean discover) {
        if (explicit != null && discover) {
            throw new IllegalStateException(
                    "An explicit " + service.getSimpleName() + " and discovery were both configured. "
                            + "Choose one: the explicit instance, or the classpath."
            );
        }
        if (explicit != null) {
            return explicit;
        }
        return discover ? discover(service) : null;
    }
}
