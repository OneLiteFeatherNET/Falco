package net.onelitefeather.falco.demo;

import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
import org.jetbrains.annotations.Contract;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The {@link LoaderKind} enum names the two chunk loaders the demo can measure and builds them.
 * <p>
 * Building both loaders in one place is what makes the comparison honest. The two run tasks differ
 * in a single command line argument which selects one of these constants; everything after that
 * point — the chunk list, the thread count, the warm-up, the stopwatch — is the same code.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public enum LoaderKind {

    /**
     * The loader of this repository, which splits a chunk into three stages so the expensive part
     * runs outside every lock.
     */
    FALCO("falco", FalcoAnvilLoader.class.getName()),

    /**
     * The loader Minestom ships with, measured as it is rather than as a reimplementation.
     */
    MINESTOM("minestom", AnvilLoader.class.getName());

    private final String option;
    private final String implementationName;

    /**
     * Creates a constant with the name it is selected by and the class it builds.
     *
     * @param option             the value the {@code --loader} argument accepts
     * @param implementationName the fully qualified name of the loader class
     */
    LoaderKind(String option, String implementationName) {
        this.option = option;
        this.implementationName = implementationName;
    }

    /**
     * Returns the value the {@code --loader} argument accepts for this constant.
     *
     * @return the option value
     */
    @Contract(pure = true)
    public String option() {
        return this.option;
    }

    /**
     * Returns the fully qualified name of the loader class this constant builds.
     * The report prints it so a reader can see which type produced a number without trusting a
     * label.
     *
     * @return the fully qualified class name of the loader
     */
    @Contract(pure = true)
    public String implementationName() {
        return this.implementationName;
    }

    /**
     * Selects the constant for the given option value, ignoring case.
     *
     * @param value the value of the {@code --loader} argument
     * @return the selected constant
     * @throws IllegalArgumentException if no constant carries that option value
     */
    public static LoaderKind parse(String value) {
        String normalised = value.toLowerCase(Locale.ROOT);

        for (LoaderKind kind : values()) {
            if (kind.option.equals(normalised)) {
                return kind;
            }
        }

        String known = Arrays.stream(values()).map(LoaderKind::option).collect(Collectors.joining(", "));
        throw new IllegalArgumentException("Unknown loader '" + value + "'. Known loaders are " + known);
    }

    /**
     * Builds the loader for a located world.
     * <p>
     * The layout decides which constructor Minestom's loader gets. Its two argument constructor
     * looks only under {@code dimensions/<namespace>/<value>/region} and has no fallback, so on an
     * older world it would find nothing while the Falco loader read the files — a difference that
     * would show up as an enormous and entirely fictional speedup.
     * </p>
     *
     * @param world the world the loader reads from
     * @return the loader, which the caller closes if it is closeable
     */
    // The single argument constructor of Minestom's loader is deprecated for removal, and it is
    // still the only way to make that loader read a world in the older layout. Dropping the branch
    // would not remove the layout from anybody's disk, it would only make the demo compare a loader
    // that reads the world against one that finds nothing.
    @SuppressWarnings("removal")
    public ChunkLoader create(WorldSearchResult.Located world) {
        return switch (this) {
            case FALCO -> new FalcoAnvilLoader(world.worldRoot(), world.dimension());
            case MINESTOM -> world.legacyLayout()
                    ? new AnvilLoader(world.worldRoot())
                    : new AnvilLoader(world.worldRoot(), world.dimension());
        };
    }
}
