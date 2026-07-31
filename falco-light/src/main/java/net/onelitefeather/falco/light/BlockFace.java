package net.onelitefeather.falco.light;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

/**
 * The {@link BlockFace} enum names the six faces of a block through which light can travel.
 * <p>
 * The engine keeps its own enum instead of using the one of the server so the propagation can be
 * verified without a running server. The offsets follow the block coordinate system, so a face
 * describes both the side of a block and the direction a neighbour lies in.
 * </p>
 * <p>
 * This type is experimental. The light engine is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public enum BlockFace {

    /**
     * The face towards the negative y axis.
     */
    BOTTOM(0, -1, 0),

    /**
     * The face towards the positive y axis.
     */
    TOP(0, 1, 0),

    /**
     * The face towards the negative z axis.
     */
    NORTH(0, 0, -1),

    /**
     * The face towards the positive z axis.
     */
    SOUTH(0, 0, 1),

    /**
     * The face towards the negative x axis.
     */
    WEST(-1, 0, 0),

    /**
     * The face towards the positive x axis.
     */
    EAST(1, 0, 0);

    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;

    /**
     * Creates a new face with the offset it points at.
     *
     * @param offsetX the offset on the x axis
     * @param offsetY the offset on the y axis
     * @param offsetZ the offset on the z axis
     */
    BlockFace(int offsetX, int offsetY, int offsetZ) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    /**
     * Returns the offset of this face on the x axis.
     *
     * @return the offset on the x axis
     */
    @Contract(pure = true)
    public int offsetX() {
        return this.offsetX;
    }

    /**
     * Returns the offset of this face on the y axis.
     *
     * @return the offset on the y axis
     */
    @Contract(pure = true)
    public int offsetY() {
        return this.offsetY;
    }

    /**
     * Returns the offset of this face on the z axis.
     *
     * @return the offset on the z axis
     */
    @Contract(pure = true)
    public int offsetZ() {
        return this.offsetZ;
    }

    /**
     * Returns the face which points in the opposite direction.
     *
     * @return the opposite face
     */
    @Contract(pure = true)
    public BlockFace opposite() {
        return switch (this) {
            case BOTTOM -> TOP;
            case TOP -> BOTTOM;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }
}
