package net.onelitefeather.falco.instance;

import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link FalcoInstanceException} is thrown when an instance of this module cannot hand back the
 * chunk that was asked of it.
 * <p>
 * Two things cause that. Every chunk has to be a {@link FalcoChunk}, because the lifecycle hooks of
 * any other chunk are unreachable from this package, so a foreign chunk would report itself as
 * loaded for the rest of the life of the server. And a chunk whose load was still running when its
 * instance was unregistered is thrown away rather than published, so the callers waiting for it are
 * told instead of being handed a chunk which belongs to nothing.
 * </p>
 * <p>
 * One type covers both cases on purpose. They share the meaning "the chunk you asked for is not
 * coming", and a caller which wants to react at all wants to react to the whole group.
 * </p>
 * <p>
 * A failure of the world generator is deliberately not wrapped in this type. It travels to the
 * caller exactly as the generator threw it, because the generator is code the caller wrote and a
 * wrapper would only put a layer between them.
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
