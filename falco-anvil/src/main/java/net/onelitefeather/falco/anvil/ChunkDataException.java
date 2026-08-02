package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link ChunkDataException} class reports chunk data that cannot be read as what it claims to
 * be.
 * <p>
 * This is the inner layer: the region file was structurally sound enough to hand out a chunk, and
 * the NBT inside it is wrong. The distinction matters to a caller, because the two have different
 * blast radii — a broken region header can cost a thousand chunks, a broken palette costs one.
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
public final class ChunkDataException extends AnvilFormatException {

    /**
     * The kinds of chunk data failure this loader distinguishes.
     * <p>
     * One constant per case that the code actually detects, rather than one type per case: a caller
     * branches on the constant, and a new detection is an added constant instead of an added type
     * that has to be published, documented and caught.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 1.0.0
     */
    @ApiStatus.Experimental
    public enum Reason {

        /**
         * A palette container holds no entry, so no block state can be resolved from it.
         */
        EMPTY_PALETTE,

        /**
         * The packed data of a palette container is not the long array the format requires.
         */
        PALETTE_DATA_NOT_LONG_ARRAY,

        /**
         * The packed data holds a number of longs that fits no bit count for the entry count.
         */
        PACKED_LENGTH_MISMATCH,

        /**
         * A packed index addresses an entry the palette does not hold.
         */
        PALETTE_INDEX_OUT_OF_RANGE,

        /**
         * A list holds elements of a type other than the one the key requires.
         */
        UNEXPECTED_LIST_ELEMENT_TYPE
    }

    /**
     * Which kind of chunk data failure this is.
     */
    private final Reason reason;

    /**
     * Creates a chunk data fault that does not know where it happened.
     *
     * @param reason  the kind of failure
     * @param message what is wrong with the data
     */
    public ChunkDataException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    private ChunkDataException(Reason reason, String message, ChunkLocation location, Throwable cause) {
        super(message, location, cause);
        this.reason = reason;
    }

    /**
     * Returns which kind of chunk data failure this is.
     *
     * @return the kind of failure
     */
    public Reason reason() {
        return this.reason;
    }

    @Override
    public ChunkDataException at(ChunkLocation location) {
        return new ChunkDataException(this.reason, getMessage() + " (" + location + ")", location, this);
    }
}
