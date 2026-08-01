package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@link WorldLocator} class finds the world the demo measures inside the directory the user
 * drops it into.
 * <p>
 * It exists because every way of getting this wrong looks identical from the outside: an empty
 * directory, the {@code region} directory copied instead of the world root, a world for a dimension
 * the run was not asked for, and two worlds side by side all end in "zero chunks loaded". Each of
 * those gets its own sentence here, so the user never has to guess which of them happened.
 * </p>
 * <p>
 * The search deliberately accepts both layouts. A world written by a recent server keeps its region
 * files under {@code dimensions/<namespace>/<value>/region}, an older one keeps them under
 * {@code region}, and refusing the older one would exclude most of the worlds anybody still has
 * lying around.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class WorldLocator {

    /**
     * The files which the repository keeps inside the world directory itself.
     * They must not make an otherwise empty directory look occupied.
     */
    private static final List<String> PLACEHOLDERS = List.of(".gitignore", ".gitkeep");

    /**
     * The suffix of an Anvil region file.
     */
    private static final String REGION_SUFFIX = ".mca";

    /**
     * This class only provides the static search and is never instantiated.
     */
    private WorldLocator() {
    }

    /**
     * Looks for exactly one world below the given directory and resolves its region directory.
     * <p>
     * The directory itself is accepted as the world root when it already looks like one, which
     * covers the user who copied the contents of a world rather than the folder around it.
     * Otherwise exactly one subdirectory has to look like a world; two candidates are reported
     * rather than silently resolved, because picking one would make the reported path the only
     * clue that the wrong world was measured.
     * </p>
     *
     * @param worldsDirectory the directory the user is asked to put the world into
     * @param dimension       the key of the dimension to measure
     * @return the located world, or the reason why there is none
     */
    public static WorldSearchResult locate(Path worldsDirectory, Key dimension) {
        if (!Files.isDirectory(worldsDirectory)) {
            return new WorldSearchResult.Missing("the directory " + worldsDirectory + " does not exist");
        }

        List<Path> entries;

        try {
            entries = listEntries(worldsDirectory);
        } catch (IOException exception) {
            return new WorldSearchResult.Missing(worldsDirectory + " cannot be read: " + exception.getMessage());
        }

        if (looksLikeWorldRoot(worldsDirectory)) {
            return resolveRegionDirectory(worldsDirectory, dimension);
        }

        if (entries.isEmpty()) {
            return new WorldSearchResult.Missing(worldsDirectory + " is empty");
        }

        if (entries.stream().anyMatch(WorldLocator::isRegionFile)) {
            return new WorldSearchResult.Missing(
                    worldsDirectory + " holds " + REGION_SUFFIX + " files directly. The loaders take the world root, "
                            + "not the region directory, so these files have to sit in a 'region' directory below it"
            );
        }

        List<Path> worlds = entries.stream().filter(WorldLocator::looksLikeWorldRoot).toList();

        if (worlds.isEmpty()) {
            return new WorldSearchResult.Missing(
                    "none of the entries in " + worldsDirectory + " looks like a world, because none of them has a "
                            + "'level.dat', a 'region' directory or a 'dimensions' directory: " + names(entries)
            );
        }

        if (worlds.size() > 1) {
            return new WorldSearchResult.Missing(
                    worldsDirectory + " holds more than one world and the demo will not choose for you: "
                            + names(worlds) + ". Keep exactly one of them there"
            );
        }

        return resolveRegionDirectory(worlds.getFirst(), dimension);
    }

    /**
     * Resolves the region directory of a world root and checks that it holds region files.
     * The current layout wins over the older one when both exist, which is the same order
     * {@code FalcoAnvilLoader} applies.
     *
     * @param worldRoot the root directory of the world
     * @param dimension the key of the dimension to measure
     * @return the located world, or the reason why its region files cannot be found
     */
    private static WorldSearchResult resolveRegionDirectory(Path worldRoot, Key dimension) {
        Path current = worldRoot.resolve("dimensions").resolve(dimension.namespace()).resolve(dimension.value()).resolve("region");
        Path legacy = worldRoot.resolve("region");
        Path regionDirectory;
        boolean legacyLayout;

        if (Files.isDirectory(current)) {
            regionDirectory = current;
            legacyLayout = false;
        } else if (Files.isDirectory(legacy)) {
            regionDirectory = legacy;
            legacyLayout = true;
        } else {
            return new WorldSearchResult.Missing(
                    worldRoot + " has no region directory for the dimension " + dimension.asString()
                            + ". Expected either " + current + " or " + legacy
            );
        }

        if (!containsRegionFile(regionDirectory)) {
            // An empty dimension directory next to a filled legacy one is the one case where the
            // reader is pointed at a path they may never have created while their chunks sit in
            // plain sight one level up. Naming both paths is the difference between "there is
            // nothing here" and "your files are here and something else is being read".
            String hint = !legacyLayout && containsRegionFile(legacy)
                    ? ". The older layout of the same world does hold region files in " + legacy
                    + ", and the dimension layout wins over it as long as its directory exists — so an empty "
                    + "dimension directory hides them from the loaders. Remove " + regionDirectory
                    + " or move the region files into it"
                    : "";

            return new WorldSearchResult.Missing(
                    regionDirectory + " holds no " + REGION_SUFFIX + " file, so there is no chunk to load" + hint
            );
        }

        return new WorldSearchResult.Located(worldRoot, regionDirectory, dimension, legacyLayout);
    }

    /**
     * Decides whether a directory carries the marks of a world root.
     * A world which was never joined has no {@code level.dat}, and one which was only ever used
     * through a chunk loader has no player data either, so any of the three marks is enough.
     *
     * @param candidate the directory to inspect
     * @return whether the directory looks like the root of a world
     */
    @Contract(pure = true)
    private static boolean looksLikeWorldRoot(Path candidate) {
        return Files.isRegularFile(candidate.resolve("level.dat"))
                || Files.isDirectory(candidate.resolve("region"))
                || Files.isDirectory(candidate.resolve("dimensions"));
    }

    /**
     * Lists the entries of a directory without the placeholder files the repository keeps there.
     *
     * @param directory the directory to list
     * @return the entries in a stable order
     * @throws IOException if the directory cannot be read
     */
    private static List<Path> listEntries(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(entry -> !PLACEHOLDERS.contains(entry.getFileName().toString()))
                    .sorted(Comparator.comparing(entry -> entry.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * Decides whether a path is an Anvil region file.
     *
     * @param candidate the path to inspect
     * @return whether the path names a region file
     */
    @Contract(pure = true)
    private static boolean isRegionFile(Path candidate) {
        return Files.isRegularFile(candidate) && candidate.getFileName().toString().endsWith(REGION_SUFFIX);
    }

    /**
     * Decides whether a directory holds at least one region file.
     * An unreadable directory counts as empty here; the caller reports the same sentence either
     * way, and a listing failure at this point has no better remedy than a missing file.
     *
     * @param directory the directory to inspect
     * @return whether the directory holds a region file
     */
    private static boolean containsRegionFile(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.anyMatch(WorldLocator::isRegionFile);
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Joins the file names of the given paths for a message.
     *
     * @param paths the paths to name
     * @return the comma separated file names
     */
    private static String names(List<Path> paths) {
        return paths.stream().map(path -> path.getFileName().toString()).collect(Collectors.joining(", "));
    }
}
