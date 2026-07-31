package net.onelitefeather.falco.light;

import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link LightUpdateAware} interface lets a chunk react to light that was computed for it.
 * <p>
 * Writing light into the sections of a chunk changes what a client should see, but it sends
 * nothing. Minestom has no hook for this: {@code AbsoluteBlockBatch} and the resend timer both ask
 * {@code chunk instanceof LightingChunk} and skip every other chunk implementation, which is why a
 * custom chunk gets no light packet no matter how correct its light is. This interface is the hook
 * that is missing there, and it is the reason {@link ChunkLightScheduler} can deliver a result
 * without knowing which chunk type it is talking to.
 * </p>
 * <p>
 * A chunk which does not implement it still receives its light — the sections are written either
 * way, and the next full chunk packet carries the new state. What it does not get is an update
 * while a player is already looking at it.
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
public interface LightUpdateAware {

    /**
     * Reports that the light of this chunk has just been recomputed and written into its sections.
     * <p>
     * The call happens on whichever thread computed the area, never under a lock of the chunk, and
     * it must not block: a slow implementation delays every other area the same executor holds.
     * </p>
     */
    void onLightUpdated();
}
