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
 * between more than one registered provider — except that a module's own shipped default, named
 * through {@link #discover(Class, Class)}, always steps aside for a foreign one rather than
 * counting as a second vote. Two foreign providers still refuse each other.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
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
        return discover(service, null);
    }

    /**
     * Finds the single provider of the given service on the classpath, letting a module's own
     * shipped default step aside for a foreign one instead of counting as a competing vote.
     * <p>
     * This is not the "no silent choice between two providers" rule weakening: a module's own
     * default is a known quantity, registered by the module itself, not a second undocumented
     * opinion on the classpath. A caller who registers one foreign provider has stated an
     * unambiguous intent, and that intent should not be refused just because the module also ships
     * its own fallback. Two <em>foreign</em> providers are a different situation — neither of them
     * is the known default, so the classpath genuinely holds two competing, undocumented opinions,
     * and this still refuses to guess between them exactly as {@link #discover(Class)} does.
     * </p>
     *
     * @param service        the service interface
     * @param shippedDefault the class of the module's own default implementation, which yields to
     *                       any other registered provider, or null if the service has no such
     *                       default
     * @param <T>            the service type
     * @return the provider, or null if the classpath carries none
     * @throws IllegalStateException if more than one provider other than {@code shippedDefault} is
     *                                registered
     * @since 2.1.0
     */
    static <T> @Nullable T discover(Class<T> service, @Nullable Class<? extends T> shippedDefault) {
        List<T> providers = new ArrayList<>();
        // The thread's context classloader is not a safe bet here: in a CloudNet, extension or
        // plugin classloader environment it may not see this jar at all, which would make discovery
        // silently resolve to nothing instead of finding the provider that is right there on this
        // module's own classloader. The service interface and its providers live in the same
        // module, so that module's classloader is what has to be asked, not whatever classloader
        // happens to be current on the calling thread.
        ServiceLoader.load(service, service.getClassLoader()).forEach(providers::add);

        if (providers.isEmpty()) {
            return null;
        }

        if (shippedDefault != null) {
            List<T> foreign = providers.stream()
                    .filter(provider -> !shippedDefault.equals(provider.getClass()))
                    .toList();

            if (!foreign.isEmpty()) {
                providers = foreign;
            }
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
        return choose(service, explicit, discover, null);
    }

    /**
     * Chooses between an explicitly configured instance and classpath discovery, letting discovery
     * treat a module's own shipped default as stepping aside for a foreign provider.
     *
     * @param service        the service interface
     * @param explicit       the instance the caller configured, or null
     * @param discover       whether the caller asked for discovery
     * @param shippedDefault the class of the module's own default implementation, passed on to
     *                       {@link #discover(Class, Class)}, or null if the service has no such
     *                       default
     * @param <T>            the service type
     * @return the chosen provider, or null if the caller asked for neither
     * @throws IllegalStateException if the caller asked for both, or if discovery is ambiguous
     * @since 2.1.0
     */
    static <T> @Nullable T choose(Class<T> service, @Nullable T explicit, boolean discover,
                                   @Nullable Class<? extends T> shippedDefault) {
        if (explicit != null && discover) {
            throw new IllegalStateException(
                    "An explicit " + service.getSimpleName() + " and discovery were both configured. "
                            + "Choose one: the explicit instance, or the classpath."
            );
        }
        if (explicit != null) {
            return explicit;
        }
        return discover ? discover(service, shippedDefault) : null;
    }
}
