package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * The {@link AnvilFormatException} class is the root of everything the stored data got wrong.
 * <p>
 * <b>It deliberately does not extend {@link IOException}.</b> That was the one open decision of the
 * design, and it was made against migration cost: an {@code IOException} root would keep every
 * existing {@code catch (IOException)} catching these types, including the block in
 * {@code saveChunk} that swallows a failure with a log line and leaves the chunk unwritten. The
 * hierarchy would then be a compile-time rename that changes nothing at runtime, and it would be
 * least effective in the path where a silent failure costs the most.
 * </p>
 * <p>
 * Checked, so the compiler names every site that has to decide what to do. There is exactly one
 * place where these become unchecked: the boundary in {@code FalcoAnvilLoader}, which wraps them
 * into an {@link AnvilChunkException}. Nothing else in this package may do that, or the origin of a
 * failure is lost halfway.
 * </p>
 * <p>
 * A failure is described twice, and the two descriptions do different work: {@code reason()} is what
 * a program branches on, the message is what a person reads. Adding a case to a reason is additive;
 * adding a type would not be.
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
public abstract sealed class AnvilFormatException extends Exception
        implements AnvilFault permits ChunkDataException, RegionFormatException {

    private final transient @Nullable ChunkLocation location;

    /**
     * Creates a fault which does not know where it happened.
     *
     * @param message what is wrong with the data
     */
    AnvilFormatException(String message) {
        super(message);
        this.location = null;
    }

    /**
     * Creates a fault at a known location, caused by another one.
     *
     * @param message  what is wrong with the data
     * @param location where the failure happened
     * @param cause    the failure this one was built from
     */
    AnvilFormatException(String message, @Nullable ChunkLocation location, @Nullable Throwable cause) {
        super(message, cause);
        this.location = location;
    }

    @Override
    public @Nullable ChunkLocation location() {
        return this.location;
    }

    /**
     * Returns the same fault with a location attached.
     * <p>
     * The classes that detect a format violation read bytes and have never been told which world
     * those bytes came from, so the location is attached where it is known: at the loader boundary.
     * The result is a new exception with this one as its cause, which keeps the stack trace of the
     * place that actually noticed.
     * </p>
     *
     * @param location where the failure happened
     * @return a fault of the same kind and reason, carrying the location
     */
    public abstract AnvilFormatException at(ChunkLocation location);
}
