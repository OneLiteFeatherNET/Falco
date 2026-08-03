package net.onelitefeather.falco.light;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * The {@link SectionOpacity} class holds the light properties of every block of a section in a form
 * the propagation can read without touching a registry.
 * <p>
 * Resolving the properties of a block is the dominant cost of a light propagation, because a
 * breadth-first search visits the same block from up to six directions and would otherwise resolve
 * it again every time. This class resolves every distinct block state once when the table is built
 * and answers from two arrays afterwards.
 * </p>
 * <p>
 * The occlusion of a block is stored per face. A block which occludes only some of its faces, such
 * as a slab or a stair, is common enough that a single flag per block would produce visibly wrong
 * light.
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
public final class SectionOpacity {

    private static final BlockFace[] FACES = BlockFace.values();

    /**
     * The marker a uniformity scan reports when a section holds more than one state.
     */
    private static final int NOT_UNIFORM = Integer.MIN_VALUE;

    /**
     * The amount of bits the occluded faces of a block occupy in a resolved state.
     * One bit per face, so a resolved state fits into a short together with its emission.
     */
    private static final int OCCLUSION_BITS = 6;

    /**
     * The bits which carry the occluded faces of a resolved state.
     */
    private static final int OCCLUSION_MASK = (1 << OCCLUSION_BITS) - 1;

    private final byte @Nullable [] occlusion;
    private final byte @Nullable [] emission;
    private final byte uniformOcclusion;
    private final byte uniformEmission;
    private final boolean hasEmission;
    private final boolean fullyTransparent;

    /**
     * Creates a new table from the given values.
     *
     * @param occlusion        the occluded faces of every block, or null for a uniform section
     * @param emission         the emitted light level of every block, or null for a uniform section
     * @param uniformOcclusion the occluded faces of a uniform section
     * @param uniformEmission  the emitted light level of a uniform section
     * @param hasEmission      whether any block of the section emits light
     * @param fullyTransparent whether no block of the section occludes any face
     */
    private SectionOpacity(byte @Nullable [] occlusion, byte @Nullable [] emission,
                           byte uniformOcclusion, byte uniformEmission,
                           boolean hasEmission, boolean fullyTransparent) {
        this.occlusion = occlusion;
        this.emission = emission;
        this.uniformOcclusion = uniformOcclusion;
        this.uniformEmission = uniformEmission;
        this.hasEmission = hasEmission;
        this.fullyTransparent = fullyTransparent;
    }

    /**
     * Builds the table for a section from the state ids of its blocks.
     * Every distinct state id is resolved exactly once.
     *
     * @param stateIds the state id of every block of the section
     * @param source   the source which describes the light properties of a block
     * @return the created table
     * @throws IllegalArgumentException if the given array does not cover the whole section
     */
    public static SectionOpacity of(int[] stateIds, BlockLightSource source) {
        if (stateIds.length != LightNibbles.BLOCK_COUNT) {
            throw new IllegalArgumentException(
                    "A section holds " + LightNibbles.BLOCK_COUNT + " blocks but the given array holds " + stateIds.length
            );
        }

        // A section of one repeated state needs no table at all. Whole sections of a world are
        // exactly that, so the shortcut saves both the per position lookups and the two arrays. The
        // scan stops at the first differing block, which makes it free for every other section.
        int uniform = uniformStateOf(stateIds);

        if (uniform != NOT_UNIFORM) {
            int properties = resolve(uniform, source);
            byte occluded = (byte) (properties & OCCLUSION_MASK);
            byte emitted = (byte) (properties >>> OCCLUSION_BITS);
            return new SectionOpacity(null, null, occluded, emitted, emitted != 0, occluded == 0);
        }

        byte[] occlusion = new byte[stateIds.length];
        byte[] emission = new byte[stateIds.length];
        StateCache cache = new StateCache();
        int anyEmission = 0;
        int anyOcclusion = 0;

        // Blocks of a world come in runs, so the state of a block is very often the state of the
        // one before it. Remembering the last one turns the lookup of a run into a comparison.
        int previousState = NOT_UNIFORM;
        int previousProperties = 0;

        for (int index = 0; index < stateIds.length; index++) {
            int stateId = stateIds[index];
            int properties = previousProperties;

            if (stateId != previousState) {
                properties = cache.propertiesOf(stateId, source);
                previousState = stateId;
                previousProperties = properties;
            }
            int occluded = properties & OCCLUSION_MASK;
            int emitted = properties >>> OCCLUSION_BITS;
            occlusion[index] = (byte) occluded;
            emission[index] = (byte) emitted;
            anyEmission |= emitted;
            anyOcclusion |= occluded;
        }
        return new SectionOpacity(occlusion, emission, (byte) 0, (byte) 0, anyEmission != 0, anyOcclusion == 0);
    }

    /**
     * Resolves the light properties of a single block state.
     * <p>
     * The occluded faces and the emission are returned as one value rather than as a pair, because
     * a pair would have to be an object and this method is called once per distinct state of every
     * section of every chunk a server lights.
     * </p>
     *
     * @param stateId the state id to resolve
     * @param source  the source which describes the light properties of a block
     * @return the occluded faces in the low bits and the emission above them
     */
    @Contract(pure = true)
    private static int resolve(int stateId, BlockLightSource source) {
        int mask = 0;

        for (BlockFace face : FACES) {
            if (source.blocksFace(stateId, face)) {
                mask |= 1 << face.ordinal();
            }
        }
        return mask | (source.emission(stateId) << OCCLUSION_BITS);
    }

    /**
     * The {@link StateCache} class remembers the resolved properties of every state a table build
     * has already seen.
     * <p>
     * A section holds 4096 blocks but only a handful of distinct states, and resolving one of them
     * reaches into the block registry of the server. The build therefore asks this cache once per
     * block and the registry once per distinct state.
     * </p>
     * <p>
     * The cache is a table with linear probing rather than a {@link java.util.HashMap}, for one
     * reason: a map is keyed by objects. Its key would be a boxed state id and its value would be a
     * pair object, and both would be created per block rather than per distinct state. That cost
     * was measured and it dominated the build. Two flat arrays and a packed short have no such
     * cost, and the whole cache dies with the build that created it.
     * </p>
     */
    private static final class StateCache {

        /**
         * The amount of slots a cache starts with. Enough for a section of a real world, which
         * rarely holds more than a few dozen distinct states.
         */
        private static final int INITIAL_SLOTS = 64;

        /**
         * The marker an unused slot carries.
         * <p>
         * A state id of exactly this value would be mistaken for an empty slot. The lowest possible
         * integer is not a state id any registry produces, and the uniformity scan of the table
         * already reserves it for the same reason.
         * </p>
         */
        private static final int EMPTY = Integer.MIN_VALUE;

        private int[] keys;
        private short[] properties;
        private int size;

        /**
         * Creates an empty cache.
         */
        private StateCache() {
            this.keys = new int[INITIAL_SLOTS];
            this.properties = new short[INITIAL_SLOTS];
            Arrays.fill(this.keys, EMPTY);
        }

        /**
         * Returns the resolved properties of the given state, resolving it if this is the first
         * time the state is seen.
         *
         * @param stateId the state id to look up
         * @param source  the source which describes the light properties of a block
         * @return the occluded faces in the low bits and the emission above them
         */
        private int propertiesOf(int stateId, BlockLightSource source) {
            int[] table = this.keys;
            int mask = table.length - 1;
            int slot = spread(stateId) & mask;
            int key = table[slot];

            while (key != stateId) {
                if (key == EMPTY) {
                    return insert(slot, stateId, source);
                }
                slot = (slot + 1) & mask;
                key = table[slot];
            }
            return this.properties[slot];
        }

        /**
         * Resolves a state which was seen for the first time and stores it in the given free slot.
         *
         * @param slot    the free slot the probe ended on
         * @param stateId the state id to resolve
         * @param source  the source which describes the light properties of a block
         * @return the occluded faces in the low bits and the emission above them
         */
        private int insert(int slot, int stateId, BlockLightSource source) {
            short resolved = (short) resolve(stateId, source);
            this.keys[slot] = stateId;
            this.properties[slot] = resolved;
            this.size++;

            // Linear probing degrades once a table fills up, so it is grown well before it is full.
            if (this.size * 2 >= this.keys.length) {
                grow();
            }
            return resolved;
        }

        /**
         * Doubles the amount of slots and moves every stored state into the larger table.
         */
        private void grow() {
            int[] oldKeys = this.keys;
            short[] oldProperties = this.properties;
            int[] newKeys = new int[oldKeys.length * 2];
            short[] newProperties = new short[oldKeys.length * 2];
            int mask = newKeys.length - 1;
            Arrays.fill(newKeys, EMPTY);

            for (int index = 0; index < oldKeys.length; index++) {
                int key = oldKeys[index];

                if (key == EMPTY) {
                    continue;
                }
                int slot = spread(key) & mask;

                while (newKeys[slot] != EMPTY) {
                    slot = (slot + 1) & mask;
                }
                newKeys[slot] = key;
                newProperties[slot] = oldProperties[index];
            }
            this.keys = newKeys;
            this.properties = newProperties;
        }

        /**
         * Mixes the bits of a state id so that neighbouring ids do not end up in neighbouring slots.
         * <p>
         * A table indexed by a power of two only ever looks at the low bits of its key. State ids of
         * one block are consecutive, so without mixing a section of a single block type would fill
         * one run of slots and probe through all of it.
         * </p>
         *
         * @param stateId the state id to mix
         * @return the mixed value
         */
        @Contract(pure = true)
        private static int spread(int stateId) {
            int mixed = stateId * 0x9E3779B9;
            return mixed ^ (mixed >>> 16);
        }
    }

    /**
     * Checks whether light is unable to pass the given face of the block at the given position.
     *
     * @param x    the x coordinate inside the section
     * @param y    the y coordinate inside the section
     * @param z    the z coordinate inside the section
     * @param face the face to check
     * @return true if light cannot pass the face, otherwise false
     */
    @Contract(pure = true)
    public boolean blocksFace(int x, int y, int z, BlockFace face) {
        byte[] table = this.occlusion;
        byte mask = table == null ? this.uniformOcclusion : table[index(x, y, z)];
        return (mask & (1 << face.ordinal())) != 0;
    }

    /**
     * Writes the occluded faces of every block of this section into one flat array.
     * <p>
     * A propagation over a whole chunk reads the occlusion of a neighbour once per face per queued
     * position, and reaching it through the section costs an index into the list, an interface call
     * and a null test before the byte itself. Laying the whole column out flat once turns all of
     * that into a single array read for the rest of the pass.
     * </p>
     * <p>
     * A uniform section is filled rather than copied, which is why it is still cheaper than one that
     * carries a table: the fill writes the same byte over a contiguous range and never touches a
     * per position table, because a uniform section holds none.
     * </p>
     * <p>
     * Package private on purpose. This hands out the internal layout of the table and is meant for
     * the two propagators of this package, not for callers.
     * </p>
     *
     * @param target the array which receives the occluded faces
     * @param offset the index in the target at which this section begins
     */
    void copyOcclusionInto(byte[] target, int offset) {
        byte[] table = this.occlusion;

        if (table == null) {
            Arrays.fill(target, offset, offset + LightNibbles.BLOCK_COUNT, this.uniformOcclusion);
            return;
        }
        System.arraycopy(table, 0, target, offset, LightNibbles.BLOCK_COUNT);
    }

    /**
     * Returns the amount of light the block at the given position emits.
     *
     * @param x the x coordinate inside the section
     * @param y the y coordinate inside the section
     * @param z the z coordinate inside the section
     * @return the emitted light level of the block
     */
    @Contract(pure = true)
    public int emission(int x, int y, int z) {
        byte[] table = this.emission;
        return table == null ? this.uniformEmission : table[index(x, y, z)];
    }

    /**
     * Checks whether any block of the section emits light.
     * A section without an emitting block needs no block light propagation at all.
     *
     * @return true if a block of the section emits light, otherwise false
     */
    @Contract(pure = true)
    public boolean hasEmission() {
        return this.hasEmission;
    }

    /**
     * Checks whether no block of the section occludes any face.
     * Light travels through such a section without any obstacle.
     *
     * @return true if the section occludes nothing, otherwise false
     */
    @Contract(pure = true)
    public boolean isFullyTransparent() {
        return this.fullyTransparent;
    }

    /**
     * Checks whether every block of the section holds the same state.
     * Such a section carries no per position table.
     *
     * @return true if the section holds a single state, otherwise false
     */
    @Contract(pure = true)
    public boolean isUniform() {
        return this.occlusion == null;
    }

    /**
     * Determines whether every block of the given section holds the same state.
     *
     * @param stateIds the state id of every block of the section
     * @return the repeated state id, or {@link #NOT_UNIFORM} if the section holds more than one
     */
    @Contract(pure = true)
    private static int uniformStateOf(int[] stateIds) {
        int first = stateIds[0];

        for (int stateId : stateIds) {
            if (stateId != first) {
                return NOT_UNIFORM;
            }
        }
        return first;
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
}
