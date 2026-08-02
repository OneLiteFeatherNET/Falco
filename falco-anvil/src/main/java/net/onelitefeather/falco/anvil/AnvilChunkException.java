package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;


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
public non-sealed class AnvilChunkException extends RuntimeException implements AnvilFault {

    /**
     * The serialisation id, fixed so a failure which crosses a version boundary still deserialises.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Where the failure happened, or null when the thrower could not say.
     * <p>
     * Transient because a {@link ChunkLocation} carries a {@code Path} and a {@code Key}, neither of
     * which promises to serialise. A deserialised exception therefore keeps its message and loses
     * its location, which is the honest outcome — the alternative is a failure that cannot cross a
     * boundary at all.
     * </p>
     */
    private final transient @Nullable ChunkLocation location;

    /**
     * Creates a new exception with the given message.
     *
     * @param message the message which describes the failure
     */
    public AnvilChunkException(String message) {
        this(message, null, null);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message the message which describes the failure
     * @param cause   the failure which caused this one
     */
    public AnvilChunkException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /**
     * Creates a new exception which knows which chunk it belongs to.
     * <p>
     * This is what the loader boundary uses: a format fault arrives with its location already
     * attached, and the location travels on rather than being formatted into the message a second
     * time.
     * </p>
     *
     * @param message  the message which describes the failure
     * @param location where the failure happened, or null if it is not known
     * @param cause    the failure which caused this one, or null if there is none
     */
    public AnvilChunkException(String message, @Nullable ChunkLocation location, @Nullable Throwable cause) {
        super(message, cause);
        this.location = location;
    }

    @Override
    public @Nullable ChunkLocation location() {
        return this.location;
    }
}
