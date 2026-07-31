package net.onelitefeather.falco.demo;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.onelitefeather.falco.demo.ChunkInventory.ChunkPosition;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@link ChunkLoadDemo} class is what the two gradle tasks start.
 * <p>
 * It exists so somebody can check the central claim of this repository on their own world and their
 * own machine instead of taking a table in a document at face value. Both tasks run this class with
 * identical arguments except for {@code --loader}, so the comparison rests on one variable.
 * </p>
 * <p>
 * The order of the steps is the measurement design. The world is located and the chunk list is
 * built before anything is timed, so which loader is running has no influence on which chunks are
 * asked for. The registries are started next, because Minestom's loader reads them in a static
 * initialiser. Only then does the stopwatch appear, and it runs the warm-up rounds before the
 * measured ones. Nothing about this makes it a benchmark — it is a rough figure from one machine,
 * and the report says so.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class ChunkLoadDemo {

    /**
     * The system property the gradle tasks use to name the directory the world is looked for in.
     * <p>
     * Passed explicitly rather than derived from the working directory, because the working
     * directory of a {@code JavaExec} is a gradle detail and a run started from an ide has a
     * different one. The fallbacks below cover that case.
     * </p>
     */
    public static final String WORLD_DIRECTORY_PROPERTY = "falco.demo.world";

    /**
     * This class is only an entry point and is never instantiated.
     */
    private ChunkLoadDemo() {
    }

    /**
     * Runs one measurement and prints its report.
     * <p>
     * Everything the user can get wrong — the command line, a missing world, a world without
     * chunks — ends in a printed explanation and a normal exit. Only a genuine defect is allowed to
     * leave this method as an exception, so a stack trace on the console always means the same
     * thing.
     * </p>
     *
     * @param arguments the command line of the run
     * @throws Exception if the measurement itself fails, which is a defect and not a user error
     */
    public static void main(String[] arguments) throws Exception {
        DemoOptions options;

        try {
            options = DemoOptions.parse(arguments, Runtime.getRuntime().availableProcessors());
        } catch (IllegalArgumentException exception) {
            System.out.print(DemoReport.invalidOptions(exception.getMessage()));
            return;
        }

        Path worldsDirectory = worldsDirectory(System.getProperty(WORLD_DIRECTORY_PROPERTY), Path.of("").toAbsolutePath());

        WorldSearchResult search = WorldLocator.locate(worldsDirectory, options.dimension());

        if (search instanceof WorldSearchResult.Missing missing) {
            System.out.print(DemoReport.missingWorld(worldsDirectory, missing));
            return;
        }

        WorldSearchResult.Located world = (WorldSearchResult.Located) search;

        List<ChunkPosition> chunks = ChunkInventory.scan(world.regionDirectory(), options.chunks());

        if (chunks.isEmpty()) {
            System.out.print(DemoReport.missingWorld(worldsDirectory, new WorldSearchResult.Missing(
                    "the region files in " + world.regionDirectory() + " mark no chunk as written"
            )));
            return;
        }

        System.out.println("Found " + chunks.size() + " chunks in " + world.regionDirectory());
        System.out.println("Starting the Minestom registries, which both loaders need before they can decode anything.");
        MinecraftServer.init();

        ChunkLoader loader = options.loader().create(world);

        try {
            Instance instance = MinecraftServer.getInstanceManager().createInstanceContainer(loader);

            System.out.println("Warming up for " + options.warmupRounds() + " rounds and measuring "
                    + options.measurementRounds() + " rounds on " + options.threads() + " threads. This takes a moment.");
            System.out.println();

            LoadMeasurement.Result result = LoadMeasurement.run(loader, instance, chunks, options);
            System.out.print(DemoReport.render(options, world, chunks.size(), result, DemoReport.Environment.current()));

            // A run which returned nothing has measured nothing, and the report above says so only
            // in a single field. The counters are read here rather than inside the report, because
            // they have to be taken from the loader before the finally block closes it.
            if (result.mostLoadedChunks() == 0) {
                System.out.println();
                System.out.print(DemoReport.emptyResult(options, world, chunks.size(), LoaderDiagnosis.of(loader)));
            }
        } finally {
            if (loader instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }

        // The registries start threads which keep a jvm alive on their own, and a demo that hangs
        // after printing its result would look like a defect in the loader.
        System.exit(0);
    }

    /**
     * Decides which directory the world is looked for in.
     * <p>
     * The gradle tasks set the property, so the two fallbacks only matter for a run started by
     * hand: from inside the module, and from the root of the repository.
     * </p>
     *
     * @param configured       the value of the system property, or {@code null} when it is not set
     * @param workingDirectory the directory the process was started in
     * @return the directory the world is looked for in
     */
    static Path worldsDirectory(@Nullable String configured, Path workingDirectory) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }

        Path insideTheModule = workingDirectory.resolve("world");

        if (Files.isDirectory(insideTheModule)) {
            return insideTheModule;
        }

        return workingDirectory.resolve("falco-demo").resolve("world");
    }
}
