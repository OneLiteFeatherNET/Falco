package net.onelitefeather.falco.light;

import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link BlockLightSource} interface describes the light properties of a block state.
 * <p>
 * The propagation only needs to know three things about a block: how much light it emits, which of
 * its faces light cannot pass and whether sky light loses a level falling through it. Keeping that
 * behind an interface separates the algorithm from the
 * registries of a running server, which is what allows the engine to be verified without starting
 * one. It is the same separation the Anvil codec uses for its palette entries.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public interface BlockLightSource {

    /**
     * Returns the amount of light the given block emits on its own.
     *
     * @param stateId the state id of the block
     * @return the emitted light level between zero and {@link LightNibbles#MAX_LEVEL}
     */
    int emission(int stateId);

    /**
     * Checks whether light is unable to pass the given face of the block.
     * <p>
     * The answer has to be given per face. Roughly one in seven block types of the game occludes
     * some of its faces and not others, slabs, stairs, snow and farmland among them, so a single
     * flag per block would answer this incorrectly for a large amount of real blocks.
     * </p>
     *
     * @param stateId the state id of the block
     * @param face    the face to check
     * @return true if light cannot pass the face, otherwise false
     */
    boolean blocksFace(int stateId, BlockFace face);

    /**
     * Checks whether sky light loses a level when it falls straight down into the given block.
     * <p>
     * Sky light keeps its full level only while it falls through blocks which do not dim light at
     * all, such as air or glass. Leaves, water, ice and every waterlogged block let light through
     * on every face but take one level from it, so the space below them is no longer at the full
     * level. Light travelling in any other direction loses one level per block anyway, which is
     * why this only matters for the fall of the sky.
     * </p>
     * <p>
     * The default answers false, which is right for a source that only knows blocks which either
     * stop light or let it through unchanged.
     * </p>
     *
     * @param stateId the state id of the block
     * @return true if sky light falling into the block loses a level, otherwise false
     */
    default boolean dimsSkyLight(int stateId) {
        return false;
    }
}
