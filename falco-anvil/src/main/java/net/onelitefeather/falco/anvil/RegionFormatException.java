package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link RegionFormatException} class reports a region file whose structure does not hold.
 * <p>
 * This is the outer layer: the bytes that describe where chunks are, how long they are and how they
 * are compressed. A fault here is worth telling apart from a broken chunk, because it can affect
 * every chunk of the file rather than one of them, and because it usually means the file was
 * truncated or written by something else — not that one chunk was saved badly.
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
public final class RegionFormatException extends AnvilFormatException {

    /**
     * The kinds of region file failure this loader distinguishes.
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 1.0.0
     */
    @ApiStatus.Experimental
    public enum Reason {

        /**
         * The file is shorter than the header the format requires.
         */
        HEADER_TOO_SHORT,

        /**
         * A chunk declares a length that does not fit into the sectors reserved for it.
         */
        CHUNK_LENGTH_OUT_OF_RANGE,

        /**
         * The file ended before the bytes a chunk announced had been read.
         */
        TRUNCATED_FILE,

        /**
         * A chunk announces a compression scheme this loader cannot read.
         */
        UNSUPPORTED_COMPRESSION,

        /**
         * Two chunks claim the same sectors, so at least one of them cannot be intact.
         */
        OVERLAPPING_SECTORS
    }

    /**
     * Which kind of region file failure this is.
     */
    private final Reason reason;

    /**
     * Creates a region format fault that does not know which chunk it belongs to.
     *
     * @param reason  the kind of failure
     * @param message what is wrong with the file
     */
    public RegionFormatException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    private RegionFormatException(Reason reason, String message, ChunkLocation location, Throwable cause) {
        super(message, location, cause);
        this.reason = reason;
    }

    /**
     * Returns which kind of region file failure this is.
     *
     * @return the kind of failure
     */
    public Reason reason() {
        return this.reason;
    }

    @Override
    public RegionFormatException at(ChunkLocation location) {
        return new RegionFormatException(this.reason, getMessage() + " (" + location + ")", location, this);
    }
}
