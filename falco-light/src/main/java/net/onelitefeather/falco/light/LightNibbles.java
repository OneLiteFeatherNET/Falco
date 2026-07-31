package net.onelitefeather.falco.light;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * The {@link LightNibbles} class stores the light level of every block of a section.
 * <p>
 * A level occupies four bits, so two of them share a byte and a full section needs
 * {@value #ARRAY_LENGTH} bytes. A section in which every block holds the same level is kept without
 * an array at all, which is the common case: most sections of a world are either completely dark or
 * completely lit by the sky. The array is allocated the moment a level differs from the rest and is
 * released again when the section becomes uniform.
 * </p>
 * <p>
 * Instances are not thread safe. A propagation builds them on one thread and publishes the result
 * afterwards.
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
public final class LightNibbles {

    /**
     * The edge length of a section in blocks.
     */
    public static final int DIMENSION = 16;

    /**
     * The amount of blocks a section holds.
     */
    public static final int BLOCK_COUNT = DIMENSION * DIMENSION * DIMENSION;

    /**
     * The amount of bytes a fully stored section occupies.
     */
    public static final int ARRAY_LENGTH = BLOCK_COUNT / 2;

    /**
     * The highest light level a block can carry.
     */
    public static final int MAX_LEVEL = 15;

    private byte @Nullable [] levels;
    private int uniformLevel;

    /**
     * Creates a section which stores the given level for every block.
     *
     * @param level the level every block of the section carries
     */
    private LightNibbles(int level) {
        this.levels = null;
        this.uniformLevel = level;
    }

    /**
     * Creates a section in which every block carries the given level.
     * No array is allocated for it.
     *
     * @param level the level every block of the section carries
     * @return the created section
     * @throws IllegalArgumentException if the level is outside of the allowed range
     */
    @Contract(pure = true, value = "_ -> new")
    public static LightNibbles uniform(int level) {
        return new LightNibbles(checkLevel(level));
    }

    /**
     * Creates a section from a stored array.
     * An array which holds a single repeated level is collapsed instead of being kept.
     *
     * @param array the stored bytes of the section
     * @return the created section
     * @throws IllegalArgumentException if the array does not have the expected length
     */
    @Contract(pure = true, value = "_ -> new")
    public static LightNibbles of(byte[] array) {
        if (array.length != ARRAY_LENGTH) {
            throw new IllegalArgumentException(
                    "A light section holds " + ARRAY_LENGTH + " bytes but the given array holds " + array.length
            );
        }

        int uniform = uniformLevelOf(array);

        if (uniform >= 0) {
            return new LightNibbles(uniform);
        }

        LightNibbles nibbles = new LightNibbles(0);
        nibbles.levels = array.clone();
        return nibbles;
    }

    /**
     * Creates a section from one level per position, packing two of them into a byte.
     * <p>
     * A propagation calculates its levels into a flat array of one byte per position and has to
     * hand them over in the packed form. Writing that result back through {@link #set(int, int, int, int)}
     * costs a call, a range check and a read of the byte the level shares with its neighbour for
     * every single position. Packing the two neighbours together instead reads every level once and
     * writes every byte once, and it is the same operation for all 4096 of them.
     * </p>
     * <p>
     * The levels of a section lie next to each other in the index order of the section, so a whole
     * chunk column can keep the levels of all of its sections in one array and hand out one section
     * of it through the offset.
     * </p>
     *
     * @param levels one level per position, starting at the given offset
     * @param offset the position at which the section starts inside the array
     * @return the created section
     * @throws IllegalArgumentException if the array does not hold a whole section behind the offset
     *                                  or if a level is outside of the allowed range
     */
    @Contract(pure = true, value = "_, _ -> new")
    static LightNibbles ofLevels(byte[] levels, int offset) {
        if (offset < 0 || levels.length - offset < BLOCK_COUNT) {
            throw new IllegalArgumentException(
                    "A section holds " + BLOCK_COUNT + " levels but the given array holds "
                            + (levels.length - offset) + " behind the offset " + offset
            );
        }

        byte[] packed = new byte[ARRAY_LENGTH];
        int first = levels[offset];
        int differing = 0;
        int outOfRange = 0;

        for (int index = 0; index < ARRAY_LENGTH; index++) {
            int low = levels[offset + (index << 1)];
            int high = levels[offset + (index << 1) + 1];
            packed[index] = (byte) ((low & 0x0F) | ((high & 0x0F) << 4));
            differing |= (low ^ first) | (high ^ first);
            outOfRange |= low | high;
        }

        // Every level is checked at once rather than one at a time. A level outside of a nibble
        // sets a bit no level may reach, and one accumulated value carries the bits of all of them.
        if ((outOfRange & ~0x0F) != 0) {
            throw new IllegalArgumentException("A light level must be within [0, " + MAX_LEVEL + "]");
        }

        if (differing == 0) {
            return new LightNibbles(first);
        }

        LightNibbles nibbles = new LightNibbles(0);
        nibbles.levels = packed;
        return nibbles;
    }

    /**
     * Returns the light level of the given block.
     *
     * @param x the x coordinate inside the section
     * @param y the y coordinate inside the section
     * @param z the z coordinate inside the section
     * @return the level of the block
     */
    @Contract(pure = true)
    public int get(int x, int y, int z) {
        byte[] array = this.levels;

        if (array == null) {
            return this.uniformLevel;
        }

        int index = index(x, y, z);
        return (array[index >> 1] >> ((index & 1) << 2)) & 0x0F;
    }

    /**
     * Sets the light level of the given block.
     * Writing the level the section already holds everywhere keeps it without an array.
     *
     * @param x     the x coordinate inside the section
     * @param y     the y coordinate inside the section
     * @param z     the z coordinate inside the section
     * @param level the level to store
     * @throws IllegalArgumentException if the level is outside of the allowed range
     */
    public void set(int x, int y, int z, int level) {
        checkLevel(level);

        if (this.levels == null) {
            if (level == this.uniformLevel) {
                return;
            }
            this.levels = expand(this.uniformLevel);
        }

        int index = index(x, y, z);
        int shift = (index & 1) << 2;
        int position = index >> 1;
        this.levels[position] = (byte) ((this.levels[position] & (0xF0 >>> shift)) | (level << shift));
    }

    /**
     * Sets the level of every block of the section and releases the array.
     *
     * @param level the level every block of the section carries afterwards
     * @throws IllegalArgumentException if the level is outside of the allowed range
     */
    public void fill(int level) {
        this.uniformLevel = checkLevel(level);
        this.levels = null;
    }

    /**
     * Checks whether every block of the section carries the same level.
     *
     * @return true if the section holds a single level, otherwise false
     */
    @Contract(pure = true)
    public boolean isUniform() {
        return this.levels == null;
    }

    /**
     * Returns the bytes of the section as they are stored.
     * A uniform section of level zero reports an empty array, which is how the format stores a
     * section without any light. Every other section reports its full bytes.
     *
     * @return a copy of the stored bytes
     */
    @Contract(pure = true)
    public byte[] toArray() {
        byte[] array = this.levels;

        if (array != null) {
            return array.clone();
        }
        return this.uniformLevel == 0 ? new byte[0] : expand(this.uniformLevel);
    }

    /**
     * Returns the bytes of the section, expanding a uniform section into a full array.
     *
     * @return a copy of the bytes of every block
     */
    @Contract(pure = true)
    public byte[] toDenseArray() {
        byte[] array = this.levels;
        return array != null ? array.clone() : expand(this.uniformLevel);
    }

    /**
     * Creates a section which holds the same levels but shares no storage with this one.
     *
     * @return the created copy
     */
    @Contract(pure = true, value = "-> new")
    public LightNibbles copy() {
        LightNibbles copy = new LightNibbles(this.uniformLevel);
        byte[] array = this.levels;

        if (array != null) {
            copy.levels = array.clone();
        }
        return copy;
    }

    /**
     * Calculates the index of a block inside the section.
     *
     * @param x the x coordinate inside the section
     * @param y the y coordinate inside the section
     * @param z the z coordinate inside the section
     * @return the index of the block
     */
    @Contract(pure = true)
    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /**
     * Builds a full array in which every block carries the given level.
     *
     * @param level the level every block carries
     * @return the created array
     */
    @Contract(pure = true)
    private static byte[] expand(int level) {
        byte[] array = new byte[ARRAY_LENGTH];

        if (level != 0) {
            Arrays.fill(array, (byte) (level | (level << 4)));
        }
        return array;
    }

    /**
     * Determines whether the given array holds a single repeated level.
     *
     * @param array the array to inspect
     * @return the repeated level or a negative value if the array holds more than one
     */
    @Contract(pure = true)
    private static int uniformLevelOf(byte[] array) {
        byte first = array[0];
        int low = first & 0x0F;

        if (low != ((first >> 4) & 0x0F)) {
            return -1;
        }
        for (byte value : array) {
            if (value != first) {
                return -1;
            }
        }
        return low;
    }

    /**
     * Verifies that the given level can be stored in a nibble.
     *
     * @param level the level to check
     * @return the given level
     * @throws IllegalArgumentException if the level is outside of the allowed range
     */
    @Contract(pure = true)
    private static int checkLevel(int level) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("A light level must be within [0, " + MAX_LEVEL + "] but was " + level);
        }
        return level;
    }
}
