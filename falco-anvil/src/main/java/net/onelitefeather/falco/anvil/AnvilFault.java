package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link AnvilFault} interface is the common contract of everything this loader reports as a
 * failure of the data rather than of the caller.
 * <p>
 * It exists to be matched, not to be caught. A {@code catch (AnvilFault)} is not even legal, since
 * an interface is not a {@link Throwable}; what it buys is an exhaustive {@code switch} after a
 * broad catch, so a caller that wants to treat a broken region file differently from a broken chunk
 * gets a compiler error when a third branch appears rather than a silent fall-through.
 * </p>
 * <p>
 * <b>Sealed, and therefore confined to this package.</b> Without a module system a sealed type may
 * only permit subtypes of its own package, so these types live beside the classes that throw them
 * rather than in a package of their own. That is a language rule, not a preference.
 * </p>
 * <p>
 * This type is experimental, like everything else in this package.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.0.0
 */
@ApiStatus.Experimental
public sealed interface AnvilFault permits AnvilFormatException, AnvilChunkException {

    /**
     * Returns where the failure happened, or null while it is not known yet.
     * <p>
     * The format classes of this package cannot know it: they read bytes and have never been told
     * which world those bytes belong to. Everything that leaves the loader carries one.
     * </p>
     *
     * @return the chunk, file and dimension the failure belongs to, or null
     */
    @Nullable ChunkLocation location();
}
