/**
 * A block and sky light engine.
 * <p>
 * The entry point for a caller is {@link net.onelitefeather.falco.light.ChunkLightService}, which
 * lights a whole chunk and is safe to use from any number of threads because every call gets its
 * own working state. Below it,
 * {@link net.onelitefeather.falco.light.LightPropagator} runs the search over a single section and
 * {@link net.onelitefeather.falco.light.ChunkLightPropagator} over a chunk column, both keeping
 * reusable buffers and therefore confined to one thread each.
 * </p>
 * <p>
 * The propagation works against {@link net.onelitefeather.falco.light.BlockLightSource} rather than
 * the block registry, so the algorithm can be tested without a running server.
 * {@link net.onelitefeather.falco.light.MinestomBlockLightSource} is the only type here that knows
 * about Minestom. Results are handed over through {@code Light#set}, which is why this engine works
 * with chunk implementations the engine of the server ignores.
 * </p>
 * <p>
 * Every public type here is experimental and may still change in a minor release.
 * </p>
 *
 * @since 0.1.0
 */
@NotNullByDefault
package net.onelitefeather.falco.light;

import org.jetbrains.annotations.NotNullByDefault;
