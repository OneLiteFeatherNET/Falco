package net.onelitefeather.falco.light;

import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link MinestomBlockLightSource} class answers the light properties of a block from the
 * registry of the running server.
 * <p>
 * It is the only part of the light engine which knows about Minestom. The propagation itself works
 * against {@link BlockLightSource}, so the algorithm can be verified without a server and this
 * class carries the whole dependency on the block registry.
 * </p>
 * <p>
 * A state id the registry does not know is treated as fully transparent and without emission. The
 * alternative would be to fail during a propagation, which would cost the light of a whole section
 * over a single unknown block.
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
public final class MinestomBlockLightSource implements BlockLightSource {

    // Written out because this package holds a BlockFace of its own, which owns the simple name
    // here. Importing the one of the server would not even compile against it.
    private static final net.minestom.server.instance.block.BlockFace[] SERVER_FACES =
            net.minestom.server.instance.block.BlockFace.values();

    /**
     * Creates a source which answers from the block registry of the running server.
     * <p>
     * The instance holds no state of its own, so one is enough for a whole server and it can be
     * handed to any number of threads.
     * </p>
     */
    public MinestomBlockLightSource() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int emission(int stateId) {
        Block block = resolve(stateId);
        return block == null ? 0 : block.registry().lightEmission();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean blocksFace(int stateId, BlockFace face) {
        Block block = resolve(stateId);

        if (block == null) {
            return false;
        }
        return block.registry().occlusionShape().isFaceFull(toServerFace(face));
    }

    /**
     * Maps a face of this engine onto the matching face of the server.
     *
     * @param face the face to map
     * @return the matching face of the server
     */
    @Contract(pure = true)
    private static net.minestom.server.instance.block.BlockFace toServerFace(BlockFace face) {
        return SERVER_FACES[face.ordinal()];
    }

    /**
     * Resolves the block which belongs to the given state id.
     * <p>
     * The lookup of the server indexes an array without checking its bounds, so a state id outside
     * of the known range throws instead of reporting an unknown block. The failure is turned into
     * an absent block here, because a propagation must not lose a whole section over one unknown
     * state.
     * </p>
     *
     * @param stateId the state id to resolve
     * @return the block or null if the registry does not know the state
     */
    @Contract(pure = true)
    private static @Nullable Block resolve(int stateId) {
        if (stateId < 0) {
            return null;
        }

        try {
            return Block.fromStateId(stateId);
        } catch (IndexOutOfBoundsException _) {
            return null;
        }
    }
}
