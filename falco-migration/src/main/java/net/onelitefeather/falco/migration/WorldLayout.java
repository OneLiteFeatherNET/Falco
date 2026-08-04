package net.onelitefeather.falco.migration;

import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds every dimension a stored Anvil world holds region files for, across both directory layouts
 * such a world has used since Minecraft 1.13.
 * <p>
 * Worlds before Minecraft 1.16 kept the overworld directly under the world root in {@code region},
 * and put the nether and the end in the sibling directories {@code DIM-1} and {@code DIM1}
 * respectively — names carried over from a numeric dimension id scheme that predates namespaced keys
 * entirely. Since 1.16, every dimension, vanilla or added by a data pack, lives under
 * {@code dimensions/<namespace>/<value>/region}, with no directory reserved for the overworld: it is
 * simply {@code dimensions/minecraft/overworld}. A world converted by this engine may still be in the
 * first shape, so both have to be understood at once.
 * </p>
 * <p>
 * {@code FalcoAnvilLoader.resolveRegionDirectory} is not reused here because it solves a narrower
 * problem: given a single dimension a caller already named, it builds that dimension's modern path
 * and falls back to {@code <world>/region} only when the modern path is absent. That fallback does
 * not look at the dimension it was asked for at all — asking it to resolve
 * {@code minecraft:the_nether} on a legacy world returns {@code <world>/region}, the overworld's own
 * directory, silently mislabeled as the nether's. It never inspects {@code DIM-1} or {@code DIM1}, so
 * a converter built on top of it would read the overworld's blocks twice and never see the other two
 * dimensions at all. This class instead enumerates every dimension a world actually has, in either
 * layout, rather than resolving one the caller already knew to ask for.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class WorldLayout {

    private static final String OVERWORLD_KEY = "minecraft:overworld";
    private static final String NETHER_KEY = "minecraft:the_nether";
    private static final String END_KEY = "minecraft:the_end";
    private static final String DIMENSIONS_DIRECTORY = "dimensions";
    private static final String REGION_DIRECTORY = "region";

    private WorldLayout() {
    }

    /**
     * Finds every region directory a world holds, in either the pre-1.16 layout or the modern
     * {@code dimensions/} layout, or a mix of both when a world was only partially converted.
     * <p>
     * The two layouts are read independently and neither is skipped because the other already
     * produced a match, so the same {@code dimensionKey} can appear twice in the result: once with
     * {@code legacy = true} from {@code <world>/region} (or {@code DIM-1}/{@code DIM1}) and once with
     * {@code legacy = false} from {@code dimensions/minecraft/overworld/region} (or the nether's or
     * end's modern path), if a world was converted far enough to have created the modern directory
     * but still has the old one sitting next to it. This method does not decide which of the two
     * copies is authoritative — a caller that folds the result down to one entry per
     * {@code dimensionKey} has to pick, and must not do so simply by keeping whichever appears last
     * in iteration order, since that can silently discard an already-migrated modern copy in favor of
     * the stale legacy source.
     * </p>
     *
     * @param worldRoot the root directory of the world, the directory that directly contains either
     *                  {@code region} or {@code dimensions}
     * @return every dimension for which a region directory was found; empty when the world has none;
     *         may contain two entries for the same {@code dimensionKey} as described above
     * @throws IOException if a directory under {@code worldRoot} cannot be listed
     */
    public static List<Region> discover(Path worldRoot) throws IOException {
        List<Region> regions = new ArrayList<>();

        Path legacyOverworld = worldRoot.resolve(REGION_DIRECTORY);
        if (Files.isDirectory(legacyOverworld)) {
            regions.add(new Region(legacyOverworld, OVERWORLD_KEY, true));
        }
        Path legacyNether = worldRoot.resolve("DIM-1").resolve(REGION_DIRECTORY);
        if (Files.isDirectory(legacyNether)) {
            regions.add(new Region(legacyNether, NETHER_KEY, true));
        }
        Path legacyEnd = worldRoot.resolve("DIM1").resolve(REGION_DIRECTORY);
        if (Files.isDirectory(legacyEnd)) {
            regions.add(new Region(legacyEnd, END_KEY, true));
        }

        Path dimensions = worldRoot.resolve(DIMENSIONS_DIRECTORY);
        if (Files.isDirectory(dimensions)) {
            try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(dimensions, Files::isDirectory)) {
                for (Path namespace : namespaces) {
                    try (DirectoryStream<Path> values = Files.newDirectoryStream(namespace, Files::isDirectory)) {
                        for (Path value : values) {
                            Path region = value.resolve(REGION_DIRECTORY);
                            if (Files.isDirectory(region)) {
                                String dimensionKey = namespace.getFileName() + ":" + value.getFileName();
                                regions.add(new Region(region, dimensionKey, false));
                            }
                        }
                    }
                }
            }
        }

        return List.copyOf(regions);
    }

    /**
     * Returns where a dimension's region files belong once a world is converted, always in the
     * modern {@code dimensions/} shape regardless of where {@code discover} found them, because that
     * is the only layout the target version reads.
     *
     * @param worldRoot    the root directory of the world
     * @param dimensionKey the dimension's namespaced key, for example {@code "minecraft:the_nether"}
     *                     or {@code "mypack:mining"}
     * @return the region directory that dimension's converted files belong in
     * @throws IllegalArgumentException if {@code dimensionKey} has no {@code ':'} separating a
     *                                   namespace from a value
     */
    public static Path targetDirectory(Path worldRoot, String dimensionKey) {
        int separator = dimensionKey.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException(
                    "dimensionKey must be namespaced as '<namespace>:<value>', but was '" + dimensionKey + "'");
        }
        String namespace = dimensionKey.substring(0, separator);
        String value = dimensionKey.substring(separator + 1);
        return worldRoot.resolve(DIMENSIONS_DIRECTORY).resolve(namespace).resolve(value).resolve(REGION_DIRECTORY);
    }

    /**
     * A single dimension's region directory, together with the key that names it and whether it was
     * found in the pre-1.16 layout.
     *
     * @param directory    the directory that holds that dimension's region files
     * @param dimensionKey the dimension's namespaced key, for example {@code "minecraft:overworld"}
     * @param legacy       whether the directory came from the layout without a {@code dimensions/}
     *                     tree
     */
    public record Region(Path directory, String dimensionKey, boolean legacy) {
    }
}
