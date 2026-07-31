package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;


/**
 * The {@link AnvilChunkException} is thrown when a chunk which exists on disk cannot be read.
 * <p>
 * The failure has to propagate instead of being reported as an absent chunk. An absent chunk makes
 * the server generate a replacement which then overwrites the stored data on the next save, so a
 * read failure would silently destroy the very data it failed to read.
 * </p>
 *
 * <p>
 * This type is experimental. The Anvil loader is new and its API may still change while it is
 * being validated against real worlds.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public class AnvilChunkException extends RuntimeException {

    /**
     * Creates a new exception with the given message.
     *
     * @param message the message which describes the failure
     */
    public AnvilChunkException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message the message which describes the failure
     * @param cause   the failure which caused this one
     */
    public AnvilChunkException(String message, Throwable cause) {
        super(message, cause);
    }
}
