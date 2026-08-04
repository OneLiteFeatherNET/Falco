package net.onelitefeather.falco.migration;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;

/**
 * A block state as stored in Anvil chunk NBT since Minecraft 1.13: a namespaced block name together
 * with its property map.
 *
 * @param name       the namespaced block identifier, for example {@code "minecraft:oak_log"}
 * @param properties the block's properties, copied into an unmodifiable map by the canonical
 *                   constructor
 * @since 2.1.0
 */
@ApiStatus.Experimental
public record BlockState(String name, @Unmodifiable Map<String, String> properties) {

    /**
     * Copies {@code properties} so that the record is immutable regardless of what the caller does
     * with the map afterwards.
     *
     * @param name       the namespaced block identifier
     * @param properties the block's properties, copied rather than retained
     */
    public BlockState {
        properties = Map.copyOf(properties);
    }

    /**
     * Creates a block state with no properties.
     *
     * @param name the namespaced block identifier
     * @return a block state named {@code name} with an empty property map
     */
    @Contract(pure = true)
    public static BlockState of(String name) {
        return new BlockState(name, Map.of());
    }
}
