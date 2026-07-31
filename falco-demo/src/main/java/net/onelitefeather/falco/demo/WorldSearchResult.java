package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;

import java.nio.file.Path;

/**
 * The {@link WorldSearchResult} interface is the outcome of looking for the world the demo should
 * measure.
 * <p>
 * A world which is not there is the normal case on a fresh clone, not an error, and it carries an
 * explanation rather than a stack trace. Modelling that as a result instead of as an exception is
 * what keeps the caller from having to decide which throwable is worth printing in full.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public sealed interface WorldSearchResult {

    /**
     * A world which was found together with the directory its region files live in.
     * <p>
     * The layout is part of the result because the two loaders disagree about it.
     * {@code FalcoAnvilLoader} falls back to {@code worldRoot/region} when no dimension directory
     * exists, while Minestom's {@code AnvilLoader} only looks there when it is constructed without a
     * dimension key. Without this flag the two run tasks would read different files on an older
     * world and the comparison would be meaningless.
     * </p>
     *
     * @param worldRoot       the root directory of the world, the one holding {@code level.dat}
     * @param regionDirectory the directory which holds the {@code .mca} files
     * @param dimension       the key of the dimension the region directory belongs to
     * @param legacyLayout    whether the region files sit directly under the world root instead of
     *                        under {@code dimensions/<namespace>/<value>/region}
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    record Located(Path worldRoot, Path regionDirectory, Key dimension, boolean legacyLayout) implements WorldSearchResult {
    }

    /**
     * No world the demo could measure, together with the reason.
     * <p>
     * The reason names the concrete directory that was inspected. A message such as "no world
     * found" would send the reader looking for the path the demo actually used, which is the one
     * piece of information they cannot guess.
     * </p>
     *
     * @param reason a sentence describing what is missing, naming the paths involved
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    record Missing(String reason) implements WorldSearchResult {
    }
}
