package net.onelitefeather.falco.instance;

import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link ChunkLifecycleEvent} record is what a {@link ChunkLifecycleListener} is told about a
 * transition of a chunk.
 * <p>
 * It is a record with two components rather than four method parameters because a transition will
 * grow things worth reporting and a parameter list cannot. It is built by the chunk, once per
 * transition, and <em>only</em> when a listener is installed — {@link FalcoChunk} checks the listener
 * field before it constructs anything, which is what makes a chunk nobody listens to free.
 * {@code ChunkLifecycleAllocationTest} measures both halves of that sentence.
 * </p>
 * <p>
 * The instance is not a component: it is {@code chunk.getInstance()} and duplicating it would make
 * the record wider for every transition to save one call on the few that need it.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @param chunk the chunk the transition happened to
 * @param time  the tick time in milliseconds for {@link ChunkLifecycleListener#onTick}, and
 *              {@code 0} for every other transition, because the other three do not happen at a tick
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public record ChunkLifecycleEvent(FalcoChunk chunk, long time) {
}
