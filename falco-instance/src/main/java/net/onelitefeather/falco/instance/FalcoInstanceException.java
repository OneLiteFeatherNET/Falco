package net.onelitefeather.falco.instance;

import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link FalcoInstanceException} is thrown when an instance of this module is asked for
 * something it deliberately cannot do yet.
 * <p>
 * The instance of this module is a smaller thing than the one Minestom ships with: the world
 * generator is not reimplemented, and every chunk has to be a {@link FalcoChunk} because the
 * lifecycle hooks of any other chunk are unreachable from this package. Both limits are easy to run
 * into by accident, and both would otherwise show up as a world that is quietly empty or a chunk
 * that never reports itself as unloaded. A refusal at the call site names the cause; a silent
 * fallback would surface much later and somewhere else.
 * </p>
 * <p>
 * One type covers both cases on purpose. They share the meaning "this instance does not do that
 * (yet)", and a caller which wants to react at all wants to react to the whole group.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public class FalcoInstanceException extends RuntimeException {

    /**
     * Creates a new exception with the given message.
     *
     * @param message the message which describes what was asked for and why it was refused
     */
    public FalcoInstanceException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message the message which describes what was asked for and why it was refused
     * @param cause   the failure which caused this one
     */
    public FalcoInstanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
